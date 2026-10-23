import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.nio.charset.Charset;

/**
 * 로그 입력.
 * - 파일 열기: parseFile() → 줄 위치를 색인하면서 읽은 만큼 바로 표시(부분 로딩)
 * - adb logcat 실시간 수집: startProcess() → adb 출력을 읽는 스레드가 기록 파일에 쓰면서 쓴 위치를 바로
 *   목록(FileLogStore)에 추가한다(LiveFeed). 파일을 다시 읽지 않는다.
 * - 장치 목록: listDevices()
 * 화면 처리는 하지 않고 Listener로 알린다. (Listener 메서드는 어느 스레드에서든 호출될 수 있음)
 */
public class LogSource
{
    public interface Listener
    {
        void onStatus(String strText);
        void onTitle(String strTitle);
        void onProcessStopped();
        void onDevices(List<Object> arItem);
        // 수집 중 장치 재부팅을 감지했다 (1.24)
        default void onReboot(String strBootId, String strInfo) { }
    }

    static final String  DEVICES_CMD = "adb devices";

    // adb devices의 한 줄: 시리얼과 상태(device / offline / unauthorized / ...)
    public static class Device
    {
        final String m_strSerial;
        final String m_strState;

        Device(String strSerial, String strState)
        {
            m_strSerial = strSerial;
            m_strState  = strState;
        }

        boolean isOnline()
        {
            return "device".equals(m_strState);
        }

        // 연결된 장치는 시리얼만, 그 외에는 상태를 함께 보여준다.
        public String toString()
        {
            return isOnline() ? m_strSerial : m_strSerial + "  (" + m_strState + ")";
        }
    }

    final FilterEngine   m_engine;
    final ILogParser     m_parser;
    final Listener       m_listener;

    volatile Process     m_process;
    volatile Thread      m_thProcess;
    volatile boolean     m_bPause;
    String               m_strLogFileName;

    public LogSource(FilterEngine engine, ILogParser parser, Listener listener)
    {
        m_engine   = engine;
        m_parser   = parser;
        m_listener = listener;
    }

    static String makeFilename()
    {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss");
        return "LogFilter_" + format.format(new Date()) + ".txt";
    }

    // ---- 파일 열기 ----

    static Charset charsetOf(boolean bUtf8)
    {
        return bUtf8 ? Charset.forName("UTF-8") : Charset.defaultCharset();
    }

    static final long LOAD_PUBLISH_MS = 200;   // 로딩 중 화면에 알리는 간격

    /**
     * 파일을 연다. 줄 위치만 빠르게 색인하면서 읽은 만큼 바로 화면에 보여주고(부분 로딩),
     * 에러 위치 계산·필터 판정은 FilterEngine이 색인된 줄을 이어서 처리한다.
     */
    void parseFile(final File file, final boolean bUtf8)
    {
        m_listener.onTitle(file.getPath());
        final FileLogStore store = new FileLogStore(file, charsetOf(bUtf8), m_parser, 0);
        m_engine.setStore(store);
        new Thread(new Runnable()
        {
            public void run()
            {
                long nStart       = System.currentTimeMillis();
                long nLastPublish = 0;
                long nFileLen     = Math.max(1, file.length());
                m_listener.onStatus("Loading");
                try
                {
                    // 다른 파일을 열면(목록이 교체되면) 멈춘다.
                    while(m_engine.getStore() == store)
                    {
                        if(store.indexNext(true) < 0)
                            break;
                        long nNow = System.currentTimeMillis();
                        if(nNow - nLastPublish >= LOAD_PUBLISH_MS)
                        {
                            nLastPublish = nNow;
                            m_engine.notifyIndexed();
                            m_listener.onStatus(String.format("Loading %d%% (%,d lines)", store.indexedEnd() * 100 / nFileLen, store.size()));
                        }
                    }
                    if(m_engine.getStore() != store)
                        return;
                    store.m_bComplete = true;
                    boolean bAnalyzing = store.size() >= FilterEngine.PROGRESS_MIN_LINES && m_engine.m_nAnalyzed < store.size();   // 작은 파일은 Ready를 따로 알리지 않음
                    m_listener.onStatus(String.format("Loaded %,d lines (%.1fs)%s", store.size(), (System.currentTimeMillis() - nStart) / 1000.0, bAnalyzing ? " · analyzing" : ""));
                    m_engine.notifyIndexed();     // 분석이 끝나면 FilterEngine이 Ready를 알린다
                }
                catch(Exception e)
                {
                    T.e(e);
                    m_listener.onStatus("Load error : " + e.getMessage());
                }
            }
        }, "LoadFile").start();
    }
    // ---- adb 실시간 수집 ----

    boolean isRunning()
    {
        return m_thProcess != null;
    }

    void setPause(boolean bPause)
    {
        m_bPause = bPause;
        if(!bPause)
        {
            LiveFeed feed = m_feed;
            if(feed != null) feed.publish();     // 멈춘 동안 쌓인 줄을 바로 보여준다
        }
    }

    static final Charset ADB_CHARSET     = Charset.forName("UTF-8");   // adb 출력은 장치에서 UTF-8로 온다
    static final long    LIVE_PUBLISH_MS = 50;      // 출력이 계속 들어오는 동안 화면에 알리는 간격
    static final int     LIVE_BUFFER     = 64 << 10;
    // 기록 파일 하나의 최대 크기 기본값 100MB (1.37: File > 기록 파일 크기에서 바꿈, 이전 1.17은 50MB 고정). 테스트용 -Dklogfilter.rotateBytes가 있으면 그 값.
    static final int     DEFAULT_ROTATE_MB = 100;
    static final long    LIVE_ROTATE_BYTES = Long.getLong("klogfilter.rotateBytes", (long)DEFAULT_ROTATE_MB << 20);
    volatile long        m_nRotateBytes    = LIVE_ROTATE_BYTES;    // 지금 쓰는 최대 크기. 넘으면 다음 파일(이름_2.txt, _3 …)에 이어 쓴다

    void setRotateMb(int nMb) { m_nRotateBytes = Math.max(1, (long)nMb) << 20; }
    int  rotateMb()           { return (int)(m_nRotateBytes >> 20); }

    /**
     * adb 출력 한 줄씩을 기록 파일에 쓰면서, 쓴 위치를 목록(FileLogStore)에 바로 추가한다.
     * 파일을 다시 읽어 색인하지 않고(이전: 50ms마다 감시 스레드가 다시 읽음), 해석한 줄은 캐시에 넣어 화면이 디스크를 읽지 않는다.
     * 줄은 파일에 flush된 뒤에만 목록에 보이게 한다(화면이 아직 기록되지 않은 위치를 읽지 않도록).
     */
    class LiveFeed
    {
        final File         m_file;                                      // 첫 기록 파일 (수집 세션을 가리킴)
        OutputStream       m_out;                                       // 지금 쓰는 파일
        long               m_nWritten;                                  // 모든 기록 파일에 쓴 바이트 수 = 가상 위치 (AdbProcess 스레드만)
        long               m_nFileBytes;                                // 지금 파일에 쓴 바이트 수
        int                m_nFileCount = 1;
        final ArrayList<long[]> m_arBatch   = new ArrayList<long[]>();  // 쓰고 아직 flush하지 않은 줄 {위치, 길이}
        final ArrayList<String> m_arBatchStr = new ArrayList<String>();
        final ArrayList<String> m_arBatchMemo = new ArrayList<String>();   // 표시 줄이면 북마크 메모, 아니면 null
        final ArrayList<long[]> m_arReady   = new ArrayList<long[]>();  // flush했고 화면에 넘길 줄 (Pause 중에는 쌓임)
        final ArrayList<String> m_arReadyStr = new ArrayList<String>();
        final ArrayList<String> m_arReadyMemo = new ArrayList<String>();
        long               m_nLastFlush = System.currentTimeMillis();

        LiveFeed(File file) throws IOException
        {
            m_file = file;
            m_out  = new BufferedOutputStream(new FileOutputStream(file), LIVE_BUFFER);
        }

        // AdbProcess 스레드: 한 줄을 파일 버퍼에 쓴다.
        void write(String strLine) throws IOException
        {
            writeMarked(strLine, null);
        }

        // 표시 줄: strMemo가 있으면 화면에 넘길 때 북마크하고 메모를 단다
        void writeMarked(String strLine, String strMemo) throws IOException
        {
            byte[] arByte = strLine.getBytes(ADB_CHARSET);
            // 이 줄을 쓰면 최대 크기를 넘으면 다음 파일로 (줄 중간에서 나누지 않음)
            if(m_nFileBytes > 0 && m_nFileBytes + arByte.length + 2 > m_nRotateBytes)
                rotate();
            m_out.write(arByte);
            m_out.write('\r');
            m_out.write('\n');
            m_arBatch.add(new long[]{ m_nWritten, arByte.length });
            m_arBatchStr.add(strLine);
            m_arBatchMemo.add(strMemo);
            m_nWritten   += arByte.length + 2;
            m_nFileBytes += arByte.length + 2;
        }

        // 지금 파일을 마무리하고 다음 파일을 연다. 목록에는 다음 파일이 지금 가상 위치부터 이어진다고 알린다.
        void rotate() throws IOException
        {
            flush();
            m_out.close();
            m_nFileCount++;
            File next = rotatedFile(m_file, m_nFileCount);
            m_out = new BufferedOutputStream(new FileOutputStream(next), LIVE_BUFFER);
            m_nFileBytes = 0;
            synchronized(this)
            {
                m_arLogFiles.add(next.getName());
                LogStore current = m_engine.getStore();
                if(current instanceof FileLogStore && ((FileLogStore)current).m_file.equals(m_file))
                    ((FileLogStore)current).addSegment(next, m_nWritten);
            }
            m_listener.onTitle(next.getName());
            m_listener.onStatus(String.format("기록 파일이 %dMB를 넘어 다음 파일에 이어서 기록합니다 : %s", LIVE_ROTATE_BYTES >> 20, next.getName()));
        }

        boolean due()
        {
            return System.currentTimeMillis() - m_nLastFlush >= LIVE_PUBLISH_MS;
        }

        // AdbProcess 스레드: 파일에 flush하고 그 줄들을 화면에 넘긴다.
        void flush() throws IOException
        {
            m_nLastFlush = System.currentTimeMillis();
            if(m_arBatch.isEmpty()) return;
            m_out.flush();
            synchronized(this)
            {
                m_arReady.addAll(m_arBatch);
                m_arReadyStr.addAll(m_arBatchStr);
                m_arReadyMemo.addAll(m_arBatchMemo);
            }
            m_arBatch.clear();
            m_arBatchStr.clear();
            m_arBatchMemo.clear();
            publish();
        }

        // flush된 줄을 현재 목록에 추가한다. (Pause 중이면 쌓아 두고, Pause를 풀 때 다시 호출된다)
        synchronized void publish()
        {
            if(m_bPause || m_arReady.isEmpty()) return;
            // Clear하면 같은 파일의 이후 부분을 보는 새 목록으로 바뀐다. 다른 파일을 열었으면 버린다.
            LogStore current = m_engine.getStore();
            if(current instanceof FileLogStore && ((FileLogStore)current).m_file.equals(m_file))
            {
                FileLogStore store = (FileLogStore)current;
                for(int i = 0; i < m_arReady.size(); i++)
                {
                    long[] ar = m_arReady.get(i);
                    store.appendLine(ar[0], (int)ar[1], m_arReadyStr.get(i));
                    if(m_arReadyMemo.get(i) != null)
                        m_engine.markNewLine(store, store.size() - 1, m_arReadyMemo.get(i));
                }
                m_engine.notifyAppended();
            }
            m_arReady.clear();
            m_arReadyStr.clear();
            m_arReadyMemo.clear();
        }

        void close()
        {
            try
            {
                flush();
            }
            catch(IOException e)
            {
                T.e(e);
            }
            try
            {
                m_out.close();
            }
            catch(IOException e)
            {
                T.e(e);
            }
        }
    }

    volatile LiveFeed    m_feed;
    // 이번 수집의 기록 파일들 (첫 파일부터. 50MB마다 하나씩 늘어남)
    final List<String>   m_arLogFiles = java.util.Collections.synchronizedList(new ArrayList<String>());

    // 첫 파일 LogFilter_날짜_시각.txt → n번째 파일 LogFilter_날짜_시각_n.txt
    static File rotatedFile(File first, int n)
    {
        String strName = first.getName();
        int nDot = strName.lastIndexOf('.');
        String strBase = nDot > 0 ? strName.substring(0, nDot) : strName, strExt = nDot > 0 ? strName.substring(nDot) : "";
        return new File(first.getAbsoluteFile().getParentFile(), strBase + "_" + n + strExt);
    }

    void startProcess(final String strCmd, final boolean bUtf8)
    {
        m_strLogFileName = makeFilename();
        final File file = new File(m_strLogFileName);
        m_arLogFiles.clear();
        m_arLogFiles.add(m_strLogFileName);
        final LiveFeed feed;
        try
        {
            feed = new LiveFeed(file);
        }
        catch(IOException e)
        {
            T.e(e);
            m_listener.onStatus("기록 파일을 만들 수 없습니다 : " + e.getMessage());
            m_listener.onProcessStopped();
            return;
        }
        m_feed = feed;
        // 기록 파일 자체를 목록으로 쓴다(adb 출력은 UTF-8). 줄 위치는 LiveFeed가 바로 추가한다.
        m_engine.setStore(new FileLogStore(file, ADB_CHARSET, m_parser, 0));
        m_listener.onTitle(m_strLogFileName);

        m_thProcess = new Thread(new Runnable()
        {
            public void run()
            {
                final int[]    nLines   = { 0 };
                final String[] lastLine = { "" };
                Process process = null;
                Thread current = Thread.currentThread();
                // adb 명령이면 장치에 따로 물어볼 수 있다 (재연결·재부팅 확인용). adb가 아니면 null.
                final AdbTarget target = AdbTarget.of(strCmd);
                if(target != null)
                {
                    String[] boot = target.bootInfo();
                    m_strBootId = boot[0];
                }
                boolean bDisconnectStop = false;            // 연결이 끊겨 자동으로 멈췄다 (1.38)
                while(true)
                {
                    process = readProcess(strCmd, feed, nLines, lastLine);
                    if(m_thProcess != current)
                        break;                              // Stop
                    // 장치 연결이 끊겨 adb가 끝났는지 본다 (재부팅, 케이블 빠짐 등)
                    if(target == null || !target.disconnected())
                        break;                              // 정상 종료 (예: logcat -d)
                    if(!m_bAutoReconnect)
                    {
                        // 기본: 끊기면 바로 Stop (1.38). '연결 대기'를 켜면 아래처럼 기다렸다가 이어서 수집한다.
                        writeMarker(feed, "-------- ADB device disconnected : logging stopped --------", null);
                        bDisconnectStop = true;
                        break;
                    }
                    writeMarker(feed, "-------- ADB device disconnected : waiting for reconnection --------", null);
                    m_listener.onStatus("ADB device disconnected : waiting for reconnection, logging will continue (press Stop to cancel)");
                    if(!target.waitForDevice(LogSource.this) || m_thProcess != current)
                        break;
                    String[] boot = target.bootInfo();
                    boolean bRebooted = boot[0] != null && m_strBootId != null && !boot[0].equals(m_strBootId);
                    if(bRebooted && !boot[0].equals(m_strMarkedBootId))
                    {
                        m_strMarkedBootId = boot[0];
                        writeRebootMarker(feed, boot);
                    }
                    else if(bRebooted)
                        writePendingMarkers(feed);      // 장치 통계가 먼저 알아채 표시를 넣기로 했다
                    else
                        writeMarker(feed, "-------- ADB device reconnected (no reboot detected) --------", null);
                    if(boot[0] != null) m_strBootId = boot[0];
                    m_listener.onStatus(bRebooted ? "Device reboot detected : logging continues (F2 jumps to the marker)" : "ADB device reconnected : logging continues");
                }
                feed.close();
                // 그 사이 Stop 후 다시 Run 했다면 새 프로세스를 건드리지 않는다.
                if(m_thProcess == current)
                {
                    // 사용자가 Stop하지 않았는데 adb가 끝났다: 오류 메시지(마지막 줄)를 상태 표시줄에 보여준다.
                    if(bDisconnectStop)
                        m_listener.onStatus("ADB device disconnected : logging stopped — check '연결 대기' to keep waiting for reconnection");
                    else
                        reportProcessEnd(process, nLines[0], lastLine[0]);
                    stopProcess();
                }
            }        }, "AdbProcess");
        m_thProcess.start();
    }

    volatile boolean m_bAutoReconnect = false;  // 연결 대기: 장치 연결이 끊기면 다시 연결을 기다려 이어서 수집 (1.24). 기본은 끊기면 Stop (1.38)
    volatile String  m_strBootId;               // 수집 중인 장치의 boot_id (재부팅하면 바뀜)
    volatile String  m_strMarkedBootId;         // 이미 재부팅 표시를 넣은(또는 넣기로 한) boot_id

    // adb 프로세스 하나를 실행해 출력이 끝날 때까지 기록한다. 실행한 프로세스를 돌려준다.
    Process readProcess(String strCmd, LiveFeed feed, int[] nLines, String[] lastLine)
    {
        Process process = null;
        try
        {
            T.d("cmd = " + strCmd);
            // stderr도 함께 받아 기록한다. (adb 오류 메시지 확인 + stderr 버퍼가 차서 멈추는 일 방지)
            ProcessBuilder pb = new ProcessBuilder(strCmd.trim().split("\\s+"));
            pb.redirectErrorStream(true);
            process = pb.start();
            m_process = process;
            // 다시 연결되어 이어서 실행하는 것이면(이미 받은 줄이 있음) 그렇게 알린다 (1.38)
            m_listener.onStatus((nLines[0] > 0 ? "ADB device reconnected, logging continues : " : "adb 실행 중 : ") + strCmd);
            startNoOutputWatch(process, nLines);

            try(BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream(), ADB_CHARSET), LIVE_BUFFER))
            {
                while(true)
                {
                    writePendingMarkers(feed);
                    // 지금 읽을 출력이 없으면(곧 기다리게 되면) 쓴 줄을 먼저 화면에 넘긴다.
                    // 출력이 몰려 올 때는 LIVE_PUBLISH_MS마다 묶어서 넘긴다.
                    if(!stdOut.ready() || feed.due())
                        feed.flush();
                    String s = stdOut.readLine();
                    if(s == null)
                        break;
                    if(s.trim().length() == 0)
                        continue;
                    // adb가 장치를 못 찾으면 이 문구만 출력하고 계속 기다린다. 로그로 세지 않고 바로 알린다.
                    if(s.startsWith("- waiting for device"))
                        m_listener.onStatus("Waiting for device : no device connected or device is offline. Press Stop, then Device OK to check.");
                    else
                        nLines[0]++;
                    lastLine[0] = s;
                    feed.write(s);
                }
            }
            process.waitFor();
        }
        catch(Exception e)
        {
            // Stop하면 프로세스가 끝나 읽기가 실패할 수 있다.
            if(m_process == process)
            {
                T.e("e = " + e);
                m_listener.onStatus("adb error : " + e.getMessage());
            }
        }
        try
        {
            feed.flush();
        }
        catch(IOException e)
        {
            T.e(e);
        }
        return process;
    }

    static final String MARKER_TAG = "KLogFilter";

    // 표시 줄(logcat threadtime 형식)을 기록 파일에 쓰고 바로 화면에 넘긴다. strMemo가 있으면 북마크하고 메모를 단다.
    void writeMarker(LiveFeed feed, String strText, String strMemo)
    {
        String strNow = new SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new Date());
        String strLine = strNow + "     0     0 " + (strMemo != null ? "E" : "W") + " " + MARKER_TAG + ": " + strText;
        try
        {
            feed.writeMarked(strLine, strMemo);
            feed.flush();
        }
        catch(IOException e)
        {
            T.e(e);
        }
    }

    // 재부팅 표시 줄 (부팅 시각 = 지금 - uptime)
    void writeRebootMarker(LiveFeed feed, String[] boot)
    {
        String strBoot = "";
        if(boot[1] != null)
        {
            long nUptimeMs = (long)(Double.parseDouble(boot[1]) * 1000);
            strBoot = String.format(" (booted at about %s, uptime %ds)", new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(System.currentTimeMillis() - nUptimeMs)), nUptimeMs / 1000);
        }
        writeMarker(feed, "======== Device reboot detected" + strBoot + " ========", "Reboot");
        if(boot[0] != null) m_strBootId = boot[0];
        m_listener.onReboot(boot[0], strBoot.trim());
    }

    // 장치 통계가 재부팅을 먼저 알아챈 경우: 수집 중이면 표시 줄을 넣는다 (같은 부팅은 한 번만)
    final List<String[]> m_arPendingMarker = java.util.Collections.synchronizedList(new ArrayList<String[]>());
    void markReboot(String strBootId, String strUptime)
    {
        if(m_feed == null || strBootId == null || strBootId.equals(m_strBootId) || strBootId.equals(m_strMarkedBootId))
            return;
        m_strMarkedBootId = strBootId;
        m_arPendingMarker.add(new String[]{ strBootId, strUptime });
    }

    // 다른 스레드가 요청한 일반 표시 줄 (예: 화면 캡처, 1.40). 수집 중이 아니면 무시한다.
    final List<String> m_arPendingText = java.util.Collections.synchronizedList(new ArrayList<String>());
    boolean addMarker(String strText)
    {
        if(m_feed == null || !isRunning()) return false;
        m_arPendingText.add(strText);
        return true;
    }

    // AdbProcess 스레드: 다른 곳에서 요청한 재부팅·일반 표시를 쓴다
    void writePendingMarkers(LiveFeed feed)
    {
        while(!m_arPendingMarker.isEmpty())
        {
            String[] boot = m_arPendingMarker.remove(0);
            writeRebootMarker(feed, boot);
        }
        while(!m_arPendingText.isEmpty())
            writeMarker(feed, m_arPendingText.remove(0), null);
    }

    /**
     * 수집 명령이 adb일 때, 같은 장치에 따로 물어보는 명령 (adb [-s 시리얼] …).
     * adb 경로는 명령의 첫 단어(또는 -Dklogfilter.adb로 정한 명령, 테스트용).
     */
    static class AdbTarget
    {
        final List<String> m_arPrefix;

        AdbTarget(List<String> arPrefix) { m_arPrefix = arPrefix; }

        static AdbTarget of(String strCmd)
        {
            String strCmdTrim = strCmd.trim();
            List<String> arPrefix = new ArrayList<String>();
            String strRest;
            String strOverride = System.getProperty("klogfilter.adb");
            if(strOverride != null && strCmdTrim.startsWith(strOverride.trim()))
            {
                for(String t : strOverride.trim().split("\\s+")) arPrefix.add(t);
                strRest = strCmdTrim.substring(strOverride.trim().length()).trim();
            }
            else
            {
                String[] ar = strCmdTrim.split("\\s+", 2);
                String strExe = new File(ar[0]).getName().toLowerCase();
                if(!strExe.equals("adb") && !strExe.equals("adb.exe"))
                    return null;
                arPrefix.add(ar[0]);
                strRest = ar.length > 1 ? ar[1] : "";
            }
            String[] arRest = strRest.split("\\s+");
            if(arRest.length >= 2 && arRest[0].equals("-s"))
            {
                arPrefix.add("-s");
                arPrefix.add(arRest[1]);
            }
            return new AdbTarget(arPrefix);
        }

        // 명령 실행 → 출력 (시간 초과·실패면 null)
        String run(long nTimeoutMs, String... arArgs)
        {
            List<String> arCmd = new ArrayList<String>(m_arPrefix);
            for(String s : arArgs) arCmd.add(s);
            Process p = null;
            try
            {
                ProcessBuilder pb = new ProcessBuilder(arCmd);
                pb.redirectErrorStream(true);
                p = pb.start();
                final Process proc = p;
                final StringBuilder sb = new StringBuilder();
                Thread reader = new Thread(new Runnable()
                {
                    public void run()
                    {
                        try(BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), ADB_CHARSET)))
                        {
                            String s;
                            while((s = r.readLine()) != null) synchronized(sb) { sb.append(s).append('\n'); }
                        }
                        catch(IOException e) { }
                    }
                }, "AdbQuery");
                reader.setDaemon(true);
                reader.start();
                if(!p.waitFor(nTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
                {
                    p.destroy();
                    return null;
                }
                reader.join(1000);
                synchronized(sb) { return sb.toString(); }
            }
            catch(Exception e)
            {
                if(p != null) p.destroy();
                return null;
            }
        }

        // 장치가 연결되어 있지 않은지 (adb get-state가 device가 아님). 끊기는 중일 수 있어 잠시 뒤 한 번 더 본다.
        boolean disconnected()
        {
            for(int i = 0; i < 2; i++)
            {
                String s = run(5000, "get-state");
                if(s == null || !s.trim().equals("device"))
                    return true;
                try { Thread.sleep(1500); } catch(InterruptedException e) { return false; }
            }
            return false;
        }

        // 장치가 다시 연결될 때까지 기다린다 (adb wait-for-device). Stop하면 false.
        boolean waitForDevice(LogSource source)
        {
            List<String> arCmd = new ArrayList<String>(m_arPrefix);
            arCmd.add("wait-for-device");
            try
            {
                ProcessBuilder pb = new ProcessBuilder(arCmd);
                pb.redirectErrorStream(true);
                long nStart = System.currentTimeMillis();
                Process p = pb.start();
                source.m_process = p;                 // Stop하면 이 프로세스를 끝낸다
                Thread current = Thread.currentThread();
                try(InputStream in = p.getInputStream()) { while(in.read() >= 0) { } }
                p.waitFor();
                // adb 자체가 없거나 바로 끝나면 잠깐 쉬어 빈 루프를 막는다 (1.38)
                if(System.currentTimeMillis() - nStart < 1000) Thread.sleep(2000);
                return source.m_thProcess == current && p.exitValue() == 0;
            }
            catch(Exception e)
            {
                return false;
            }
        }

        // {boot_id, uptime 초} (읽을 수 없으면 null)
        String[] bootInfo()
        {
            String s = run(5000, "shell", "cat", "/proc/sys/kernel/random/boot_id", "/proc/uptime");
            return parseBootInfo(s);
        }
    }

    static final java.util.regex.Pattern BOOT_ID = java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    static final java.util.regex.Pattern UPTIME  = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s+\\d+(?:\\.\\d+)?$");

    // cat boot_id uptime 출력 → {boot_id, uptime}
    static String[] parseBootInfo(String strOut)
    {
        String[] result = { null, null };
        if(strOut == null) return result;
        for(String strLine : strOut.split("\n"))
        {
            String s = strLine.trim();
            if(BOOT_ID.matcher(s).matches()) result[0] = s.toLowerCase();
            java.util.regex.Matcher m = UPTIME.matcher(s);
            if(m.matches()) result[1] = m.group(1);
        }
        return result;
    }
    static final int NO_OUTPUT_WARN_MS = 5000;

    // adb가 일정 시간 아무것도 출력하지 않으면 경고한다. (offline 장치에서는 adb logcat이 오류 없이 멈춰 있음)
    void startNoOutputWatch(final Process process, final int[] nLines)
    {
        Thread th = new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    Thread.sleep(NO_OUTPUT_WARN_MS);
                }
                catch(InterruptedException e)
                {
                    return;
                }
                if(nLines[0] == 0 && m_process == process)
                    m_listener.onStatus("adb에서 " + NO_OUTPUT_WARN_MS / 1000 + "초 동안 출력이 없습니다. 장치가 offline/unauthorized인지 확인하세요 (Device OK로 목록 갱신).");
            }
        }, "AdbNoOutputWatch");
        th.setDaemon(true);
        th.start();
    }

    // adb가 스스로 끝났을 때 종료 코드와 마지막 출력을 상태 표시줄에 알린다.
    void reportProcessEnd(Process process, int nLines, String strLastLine)
    {
        if(process == null) return;
        int nExit;
        try
        {
            nExit = process.waitFor();
        }
        catch(InterruptedException e)
        {
            return;
        }
        if(nExit != 0)
            m_listener.onStatus("adb 종료 (코드 " + nExit + ") : " + strLastLine.trim());
        else
            m_listener.onStatus("adb 종료 (" + nLines + "줄)");
    }

    void stopProcess()
    {
        Process process   = m_process;
        Thread  thProcess = m_thProcess;
        LiveFeed feed     = m_feed;
        m_process     = null;
        m_thProcess   = null;
        m_feed        = null;
        m_bPause      = false;
        if(feed != null) feed.publish();      // Pause 중에 쌓인 줄도 보여준다
        if(process != null) process.destroy();
        if(thProcess != null) thProcess.interrupt();
        m_listener.onProcessStopped();
    }

    // ---- 장치 목록 ----

    // adb devices를 백그라운드에서 실행해 결과를 onDevices()로 알린다. (실행 중 UI가 멈추지 않도록)
    void listDevices()
    {
        new Thread(new Runnable()
        {
            public void run()
            {
                List<Object> arItem = new ArrayList<Object>();
                try
                {
                    // stderr를 stdout에 합쳐 한 번에 읽는다. (둘을 순서대로 읽다 버퍼가 차서 멈추는 일 방지)
                    ProcessBuilder pb = new ProcessBuilder(DEVICES_CMD.split("\\s+"));
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    try(BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream())))
                    {
                        String s;
                        while ((s = stdOut.readLine()) != null)
                        {
                            s = s.trim();
                            // 머리글과 adb 서버 시작 메시지("* daemon started ...")는 건너뛴다.
                            if(s.length() == 0 || s.startsWith("List of devices attached") || s.startsWith("*"))
                                continue;
                            String[] arToken = s.split("\\s+");
                            arItem.add(new Device(arToken[0], arToken.length > 1 ? arToken[1] : ""));
                        }
                    }
                    System.out.println("Exit Code: " + process.waitFor());
                }
                catch(Exception e)
                {
                    T.e("e = " + e);
                    arItem.add(e);
                }
                m_listener.onDevices(arItem);
            }
        }, "AdbDevices").start();
    }
}
