import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 안드로이드 장치 통계: 연결된 장치의 CPU·메모리 사용량을 adb로 주기적으로 읽는다. (1.21)
 * - CPU: /proc/stat의 시간 누적값을 두 번 읽은 차이로 전체·코어별 사용률을 구한다.
 * - 메모리: /proc/meminfo의 MemTotal, MemAvailable(없으면 MemFree+Buffers+Cached).
 * - 상위 프로세스: 자세히 보기를 열었을 때만 top으로 CPU 상위 10개.
 * 화면 처리는 하지 않고 Listener로 알린다(백그라운드 스레드에서 호출됨).
 */
public class DeviceStats
{
    public interface Listener
    {
        void onStats(Sample sample);        // 새 측정값 (장치가 없거나 오류면 sample.m_strError)
        // 장치 재부팅을 알아챘다 (boot_id가 바뀜, 1.24)
        default void onReboot(Sample sample) { }
    }

    static final long   INTERVAL_MS   = 2000;      // 기본 측정 주기
    static final int[]  INTERVALS_SEC = { 1, 2, 5, 10, 30, 60 };   // 고를 수 있는 주기 (1.23)
    static final long   ADB_TIMEOUT_MS = 5000;
    static final int    HISTORY       = 60;     // 그래프용 최근 측정 수 (2초 × 60 = 2분)
    // adb 명령 (테스트용으로 -Dklogfilter.adb로 바꿀 수 있음)
    static final String ADB = System.getProperty("klogfilter.adb", "adb");

    /** 한 번의 측정값 */
    public static class Sample
    {
        String   m_strDevice;               // 장치 시리얼 (선택 안 했으면 "")
        String   m_strError;                // 장치 없음·offline 등 (정상이면 null)
        double   m_dCpu = -1;               // 전체 CPU 사용률 % (첫 측정은 -1: 차이를 구할 이전 값이 없음)
        double[] m_arCore = new double[0];  // 코어별 사용률 % (-1이면 그 코어는 꺼져 있음)
        long     m_nMemTotalKb, m_nMemAvailKb;
        List<String[]> m_arTop = new ArrayList<String[]>();   // {PID, CPU%, 이름} 상위 프로세스 (자세히 보기 중일 때만)
        long     m_nTime = System.currentTimeMillis();
        String   m_strBootId;               // /proc/sys/kernel/random/boot_id (재부팅하면 바뀜)
        String   m_strUptime;               // /proc/uptime 첫 값 (초)
        double   m_dTemp = -1;              // CPU 온도 °C (못 읽으면 -1) (1.26)
        List<String[]> m_arThermal = new ArrayList<String[]>();   // 온도 센서 {이름, °C}

        double memUsedPercent()
        {
            return m_nMemTotalKb > 0 ? (m_nMemTotalKb - m_nMemAvailKb) * 100.0 / m_nMemTotalKb : -1;
        }

        // 상태 표시줄용 한 줄 요약
        String summary()
        {
            if(m_strError != null) return "장치 통계: " + m_strError;
            StringBuilder sb = new StringBuilder("장치 ");
            sb.append(m_dCpu < 0 ? "CPU …" : String.format("CPU %.0f%%", m_dCpu));
            if(m_dTemp >= 0)
                sb.append(String.format(" · %.1f°C", m_dTemp));
            if(m_nMemTotalKb > 0)
                sb.append(String.format(" · MEM %.1f/%.1fGB (%.0f%%)", (m_nMemTotalKb - m_nMemAvailKb) / 1048576.0, m_nMemTotalKb / 1048576.0, memUsedPercent()));
            return sb.toString();
        }
    }

    private final Listener   m_listener;
    private volatile String  m_strDevice = "";      // 측정할 장치 (비면 adb 기본 장치)
    private volatile boolean m_bRunning;
    private volatile boolean m_bWantTop;            // 상위 프로세스도 읽을지 (자세히 보기 창이 열려 있을 때)
    private volatile long    m_nIntervalMs = INTERVAL_MS;   // 측정 주기 (1.23)
    private volatile boolean m_bSleeping;           // 다음 측정을 기다리는 중 (이때만 깨운다: adb 실행 중에 깨우면 그 측정이 실패함)
    private Thread           m_thread;
    private long[][]         m_arPrevCpu;           // 이전 /proc/stat (전체 + 코어별) {합계, 쉼}
    private String           m_strPrevBootId;       // 이전 측정의 boot_id (장치가 바뀌면 지움)
    private double           m_dPrevUptime = -1;
    final List<Sample>       m_arHistory = new ArrayList<Sample>();   // 최근 측정값 (그래프용, synchronized)

    public DeviceStats(Listener listener)
    {
        m_listener = listener;
    }

    void setDevice(String strDevice)
    {
        String s = strDevice == null ? "" : strDevice.trim();
        if(!s.equals(m_strDevice))
        {
            m_strDevice = s;
            m_arPrevCpu = null;                     // 장치가 바뀌면 처음부터
            m_strPrevBootId = null;
            m_dPrevUptime = -1;
            synchronized(m_arHistory) { m_arHistory.clear(); }
        }
    }

    void setWantTop(boolean b) { m_bWantTop = b; }

    // 측정 주기를 바꾼다. 기다리는 중이면 바로 다음 측정을 한다.
    synchronized void setInterval(long nMs)
    {
        m_nIntervalMs = Math.max(500, nMs);
        if(m_thread != null && m_bSleeping) m_thread.interrupt();
    }
    long interval() { return m_nIntervalMs; }
    boolean isRunning()        { return m_bRunning; }

    synchronized void start()
    {
        if(m_bRunning) return;
        m_bRunning = true;
        m_thread = new Thread(new Runnable()
        {
            public void run()
            {
                while(m_bRunning)
                {
                    Sample sample = measure();
                    if(!m_bRunning) break;
                    if(sample.m_strError == null)
                        synchronized(m_arHistory)
                        {
                            m_arHistory.add(sample);
                            while(m_arHistory.size() > HISTORY) m_arHistory.remove(0);
                        }
                    m_listener.onStats(sample);
                    if((sample.m_strBootId != null || sample.m_strError == null) && checkReboot(sample))
                        m_listener.onReboot(sample);
                    m_bSleeping = true;
                    try
                    {
                        // 장치가 없으면 덜 자주 (최대 10초)
                        Thread.sleep(sample.m_strError == null ? m_nIntervalMs : Math.max(m_nIntervalMs, Math.min(m_nIntervalMs * 3, 10000)));
                    }
                    catch(InterruptedException e)
                    {
                        // 주기를 바꿨거나(setInterval) 멈췄다(stop): 멈춘 게 아니면 바로 다음 측정
                    }
                    m_bSleeping = false;
                    Thread.interrupted();       // 남은 인터럽트 표시 지우기
                }
            }
        }, "DeviceStats");
        m_thread.setDaemon(true);
        m_thread.start();
    }

    synchronized void stop()
    {
        m_bRunning = false;
        if(m_thread != null) m_thread.interrupt();
        m_thread = null;
        m_arPrevCpu = null;
    }

    // boot_id가 바뀌었거나(없으면 uptime이 줄었으면) 재부팅. 장치가 잠시 안 보였다가 돌아와도 비교한다.
    boolean checkReboot(Sample sample)
    {
        boolean bReboot = false;
        double dUptime = -1;
        try { if(sample.m_strUptime != null) dUptime = Double.parseDouble(sample.m_strUptime); } catch(NumberFormatException e) { }
        if(sample.m_strBootId != null && m_strPrevBootId != null)
            bReboot = !sample.m_strBootId.equals(m_strPrevBootId);
        else if(sample.m_strBootId == null && dUptime >= 0 && m_dPrevUptime >= 0)
            bReboot = dUptime + 1 < m_dPrevUptime;
        if(sample.m_strBootId != null) m_strPrevBootId = sample.m_strBootId;
        if(dUptime >= 0) m_dPrevUptime = dUptime;
        return bReboot;
    }

    List<Sample> history()
    {
        synchronized(m_arHistory) { return new ArrayList<Sample>(m_arHistory); }
    }

    // 한 번 측정한다 (adb 실행)
    Sample measure()
    {
        Sample sample = new Sample();
        sample.m_strDevice = m_strDevice;
        // 한 번의 adb로 CPU·메모리·부팅 정보와 온도 센서(thermal_zone)를 함께 읽는다
        String[] out = runAdb("cat /proc/stat /proc/meminfo /proc/sys/kernel/random/boot_id /proc/uptime; "
                            + "for z in /sys/class/thermal/thermal_zone*; do echo THERMAL $(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null); done");
        if(out[1] != null)
        {
            sample.m_strError = out[1];
            m_arPrevCpu = null;
            return sample;
        }
        // 재부팅 확인용 boot_id·uptime은 CPU 정보를 못 읽어도 따로 쓴다
        String[] boot = LogSource.parseBootInfo(out[0]);
        sample.m_strBootId = boot[0];
        sample.m_strUptime = boot[1];
        long[][] arCpu = parseProcStat(out[0]);
        if(arCpu.length == 0)
        {
            sample.m_strError = errorOf(out[0], "CPU 정보를 읽을 수 없습니다");
            return sample;
        }
        applyCpu(sample, m_arPrevCpu, arCpu);
        m_arPrevCpu = arCpu;
        long[] mem = parseMemInfo(out[0]);
        sample.m_arThermal = parseThermal(out[0]);
        sample.m_dTemp = cpuTemp(sample.m_arThermal);
        sample.m_nMemTotalKb = mem[0];
        sample.m_nMemAvailKb = mem[1];
        if(m_bWantTop)
        {
            String[] top = runAdb("top", "-b", "-n", "1", "-m", "10");
            if(top[1] == null)
                sample.m_arTop = parseTop(top[0], 10);
        }
        return sample;
    }

    // adb [-s 장치] shell <args>. {출력, 오류 메시지(정상이면 null)}
    String[] runAdb(String... arShellArgs)
    {
        List<String> arCmd = new ArrayList<String>(Arrays.asList(ADB.trim().split("\\s+")));
        if(m_strDevice.length() > 0) { arCmd.add("-s"); arCmd.add(m_strDevice); }
        arCmd.add("shell");
        arCmd.addAll(Arrays.asList(arShellArgs));
        Process process = null;
        try
        {
            ProcessBuilder pb = new ProcessBuilder(arCmd);
            pb.redirectErrorStream(true);
            process = pb.start();
            StringBuilder sb = new StringBuilder();
            try(BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.forName("UTF-8"))))
            {
                String s;
                while((s = r.readLine()) != null)
                    sb.append(s).append('\n');
            }
            if(!process.waitFor(ADB_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            {
                process.destroy();
                return new String[]{ "", "adb 응답 없음" };
            }
            String strOut = sb.toString();
            String strError = adbError(strOut);
            return new String[]{ strOut, strError };
        }
        catch(Exception e)
        {
            if(process != null) process.destroy();
            return new String[]{ "", "adb를 실행할 수 없습니다 (" + e.getMessage() + ")" };
        }
    }

    // adb 오류 문구 → 짧은 설명 (정상이면 null)
    static String adbError(String strOut)
    {
        String s = strOut.trim();
        if(s.startsWith("error:") || s.contains("no devices/emulators found") || s.contains("device not found"))
        {
            if(s.contains("more than one device")) return "장치가 여러 개입니다. Device 목록에서 장치를 선택하세요";
            if(s.contains("offline"))              return "장치가 offline입니다";
            if(s.contains("unauthorized"))         return "장치가 unauthorized입니다 (장치에서 USB 디버깅 허용 필요)";
            if(s.contains("no devices") || s.contains("not found")) return "연결된 장치가 없습니다";
            return s.length() > 80 ? s.substring(0, 80) : s;
        }
        return null;
    }

    static String errorOf(String strOut, String strDefault)
    {
        String s = strOut.trim();
        return s.length() == 0 ? strDefault : strDefault + " (" + (s.length() > 60 ? s.substring(0, 60) : s) + ")";
    }

    /** /proc/stat의 "cpu", "cpu0" … 줄 → {합계, 쉼(idle+iowait)} 배열. [0]은 전체, [1..]은 코어 순서대로. */
    static long[][] parseProcStat(String strOut)
    {
        List<long[]> arAll = new ArrayList<long[]>();
        List<long[]> arCore = new ArrayList<long[]>();
        List<Integer> arCoreNo = new ArrayList<Integer>();
        for(String strLine : strOut.split("\n"))
        {
            String s = strLine.trim();
            if(!s.startsWith("cpu")) continue;
            String[] ar = s.split("\\s+");
            if(ar.length < 5) continue;
            long nTotal = 0, nIdle = 0;
            try
            {
                for(int i = 1; i < ar.length && i <= 8; i++)      // user nice system idle iowait irq softirq steal
                {
                    long v = Long.parseLong(ar[i]);
                    nTotal += v;
                    if(i == 4 || i == 5) nIdle += v;
                }
            }
            catch(NumberFormatException e)
            {
                continue;
            }
            if(ar[0].equals("cpu"))
                arAll.add(new long[]{ nTotal, nIdle });
            else
            {
                try { arCoreNo.add(Integer.parseInt(ar[0].substring(3))); } catch(NumberFormatException e) { continue; }
                arCore.add(new long[]{ nTotal, nIdle });
            }
        }
        if(arAll.isEmpty()) return new long[0][];
        // 코어 번호에 맞춰 놓는다 (꺼진 코어는 줄이 없으므로 null)
        int nMax = -1;
        for(int n : arCoreNo) nMax = Math.max(nMax, n);
        long[][] result = new long[nMax + 2][];
        result[0] = arAll.get(0);
        for(int i = 0; i < arCore.size(); i++)
            result[arCoreNo.get(i) + 1] = arCore.get(i);
        return result;
    }

    // 이전·이번 /proc/stat 차이로 사용률을 넣는다
    static void applyCpu(Sample sample, long[][] prev, long[][] cur)
    {
        sample.m_arCore = new double[Math.max(0, cur.length - 1)];
        Arrays.fill(sample.m_arCore, -1);
        if(prev == null) return;
        sample.m_dCpu = usage(prev[0], cur[0]);
        for(int i = 1; i < cur.length; i++)
            if(i < prev.length && prev[i] != null && cur[i] != null)
                sample.m_arCore[i - 1] = usage(prev[i], cur[i]);
    }

    static double usage(long[] prev, long[] cur)
    {
        long nTotal = cur[0] - prev[0], nIdle = cur[1] - prev[1];
        if(nTotal <= 0) return 0;
        return Math.max(0, Math.min(100, (nTotal - nIdle) * 100.0 / nTotal));
    }

    /** "THERMAL <이름> <값>" 줄들 → {이름, °C}. 값이 1000보다 크면 밀리도(45000 → 45.0). 읽을 수 없는 센서는 뺀다. */
    static List<String[]> parseThermal(String strOut)
    {
        List<String[]> ar = new ArrayList<String[]>();
        for(String strLine : strOut.split("\n"))
        {
            String[] t = strLine.trim().split("\\s+");
            if(t.length < 3 || !t[0].equals("THERMAL")) continue;
            try
            {
                double v = Double.parseDouble(t[t.length - 1]);
                if(Math.abs(v) >= 1000) v /= 1000;
                if(v <= -50 || v >= 200) continue;          // 꺼진 센서 등 엉뚱한 값
                StringBuilder sbName = new StringBuilder();
                for(int i = 1; i < t.length - 1; i++) sbName.append(i > 1 ? " " : "").append(t[i]);
                ar.add(new String[]{ sbName.toString(), String.format("%.1f", v) });
            }
            catch(NumberFormatException e) { }
        }
        return ar;
    }

    static final java.util.regex.Pattern CPU_ZONE = java.util.regex.Pattern.compile("(?i).*(cpu|soc|tsens|core|big|little|x86_pkg|ap_|mtktscpu).*");

    // CPU 온도: 이름이 CPU 쪽인 센서 중 가장 높은 값, 없으면 전체에서 가장 높은 값. 센서가 없으면 -1.
    static double cpuTemp(List<String[]> arThermal)
    {
        double dCpu = -1, dAll = -1;
        for(String[] z : arThermal)
        {
            double v = Double.parseDouble(z[1]);
            dAll = Math.max(dAll, v);
            if(CPU_ZONE.matcher(z[0]).matches()) dCpu = Math.max(dCpu, v);
        }
        return dCpu >= 0 ? dCpu : dAll;
    }

    /** /proc/meminfo → {MemTotal kB, 사용 가능 kB} */
    static long[] parseMemInfo(String strOut)
    {
        long nTotal = 0, nAvail = -1, nFree = 0, nBuffers = 0, nCached = 0;
        for(String strLine : strOut.split("\n"))
        {
            String[] ar = strLine.trim().split("[:\\s]+");
            if(ar.length < 2) continue;
            long v;
            try { v = Long.parseLong(ar[1]); } catch(NumberFormatException e) { continue; }
            switch(ar[0])
            {
                case "MemTotal":     nTotal = v;   break;
                case "MemAvailable": nAvail = v;   break;
                case "MemFree":      nFree = v;    break;
                case "Buffers":      nBuffers = v; break;
                case "Cached":       nCached = v;  break;
            }
        }
        if(nAvail < 0) nAvail = nFree + nBuffers + nCached;    // 오래된 커널
        return new long[]{ nTotal, Math.min(nAvail, nTotal) };
    }

    /**
     * top 출력 → CPU 상위 {PID, CPU%, 이름}. 장치마다 형식이 달라 머리줄에서 PID·CPU 열 위치를 찾는다.
     * (toybox: "PID USER PR NI VIRT RES SHR S[%CPU] %MEM TIME+ ARGS", 예전: "PID PR CPU% S #THR VSS RSS PCY UID Name")
     */
    static List<String[]> parseTop(String strOut, int nMax)
    {
        List<String[]> arResult = new ArrayList<String[]>();
        String[] arLine = strOut.split("\n");
        int nPid = -1, nCpu = -1, nName = -1, nHeader = -1;
        for(int i = 0; i < arLine.length && nHeader < 0; i++)
        {
            String s = arLine[i].replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").trim();   // 색상 코드 제거
            String[] ar = s.split("\\s+");
            int p = -1, c = -1, nShift = 0;
            for(int k = 0; k < ar.length; k++)
            {
                String t = ar[k].toUpperCase();
                if(t.equals("PID")) p = k;
                else if(t.contains("CPU") && c < 0)
                {
                    // toybox는 머리줄에 "S[%CPU]"처럼 상태(S)와 CPU가 붙어 있지만 값 줄에서는 두 칸이다.
                    if(t.startsWith("S[")) { c = k + 1; nShift = 1; }
                    else c = k;
                }
            }
            if(p >= 0 && c >= 0)
            {
                nPid = p; nCpu = c; nName = ar.length - 1 + nShift; nHeader = i;
            }
        }
        if(nHeader < 0) return arResult;
        for(int i = nHeader + 1; i < arLine.length && arResult.size() < nMax; i++)
        {
            String s = arLine[i].replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").trim();
            if(s.length() == 0) continue;
            String[] ar = s.split("\\s+");
            if(ar.length <= Math.max(nPid, nCpu) || !ar[nPid].matches("\\d+")) continue;
            String strCpu = ar[nCpu].replace("%", "");
            if(!strCpu.matches("[0-9.]+")) continue;
            StringBuilder sbName = new StringBuilder();
            for(int k = Math.min(nName, ar.length - 1); k < ar.length; k++)
                sbName.append(sbName.length() > 0 ? " " : "").append(ar[k]);
            arResult.add(new String[]{ ar[nPid], strCpu, sbName.toString() });
        }
        return arResult;
    }
}
