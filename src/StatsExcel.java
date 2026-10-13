import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 장치 통계 기록을 엑셀 파일(.xlsx)로 만든다. (1.28)
 * - "통계" 시트      : 측정마다 한 줄 — 시각, 경과, CPU %, 메모리, CPU 온도, 코어별 %, 센서별 온도, 비고(재부팅·오류)
 * - "상위 프로세스" : 자세히 창이 열려 있어 top을 읽은 측정의 CPU 상위 프로세스
 * - "정보" 시트      : 장치, 측정 시작·끝, 주기, 측정 시간, 측정 횟수, 재부팅 기록
 * 값을 못 읽은 칸(첫 측정의 CPU, 꺼진 코어, 센서 없음)은 비워 두어 엑셀에서 그래프를 그릴 때 끊기게 둔다.
 */
public class StatsExcel
{
    static final String EXT = ".xlsx";

    // 기본 파일 이름: DeviceStats_20261013_153000.xlsx
    static String defaultFileName(long nTime)
    {
        return "DeviceStats_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date(nTime)) + EXT;
    }

    static File withExt(File file)
    {
        return file.getName().toLowerCase().endsWith(EXT) ? file : new File(file.getPath() + EXT);
    }

    static final String SHEET_STATS = "통계";
    // 차트 색 (자세히 창과 같은 색)
    static final String COLOR_CPU = "1E6FD9", COLOR_MEM = "2E9E44", COLOR_TEMP = "EF6C00";
    static final String[] COLOR_CORES = { "8E24AA", "00897B", "D81B60", "6D4C41", "3949AB", "827717", "0097A7", "546E7A" };

    static int write(File file, DeviceStats stats, List<String> arReboot) throws IOException
    {
        return write(file, stats, arReboot, true);
    }

    /**
     * 기록을 엑셀 파일로 쓴다. 쓴 측정 줄 수를 돌려준다 (기록이 없어도 정보 시트만 있는 파일을 만든다).
     * bCoreChart면 "그래프" 시트에 코어별 차트도 넣는다 (1.35).
     */
    static int write(File file, DeviceStats stats, List<String> arReboot, boolean bCoreChart) throws IOException
    {
        List<DeviceStats.Sample> arSample = stats.record();
        List<String> arRebootCopy = new ArrayList<String>();
        if(arReboot != null)
            synchronized(arReboot) { arRebootCopy.addAll(arReboot); }
        XlsxWriter xlsx = build(arSample, stats, arRebootCopy, bCoreChart);
        xlsx.write(file);
        return arSample.size();
    }

    static XlsxWriter build(List<DeviceStats.Sample> arSample, DeviceStats stats, List<String> arReboot, boolean bCoreChart)
    {
        // 코어 수와 센서 이름은 측정마다 다를 수 있다 (꺼진 코어, 센서 추가): 전체에서 가장 많은 것으로 열을 만든다
        int nCores = 0;
        Set<String> setSensor = new LinkedHashSet<String>();
        for(DeviceStats.Sample s : arSample)
        {
            nCores = Math.max(nCores, s.m_arCore.length);
            for(String[] z : s.m_arThermal) setSensor.add(z[0]);
        }
        List<String> arSensor = new ArrayList<String>(setSensor);

        XlsxWriter xlsx = new XlsxWriter();

        // ---- 통계 ----
        List<Object> arHeader = new ArrayList<Object>();
        arHeader.add("시각"); arHeader.add("경과(초)"); arHeader.add("CPU %");
        arHeader.add("메모리 %"); arHeader.add("메모리 사용 MB"); arHeader.add("메모리 전체 MB"); arHeader.add("메모리 사용 가능 MB");
        arHeader.add("CPU 온도 °C");
        for(int i = 0; i < nCores; i++) arHeader.add("cpu" + i + " %");
        for(String strSensor : arSensor) arHeader.add(strSensor + " °C");
        arHeader.add("비고");
        XlsxWriter.Sheet sheet = xlsx.addSheet(SHEET_STATS, arHeader.toArray());
        double[] arWidth = new double[arHeader.size()];
        arWidth[0] = 20; arWidth[1] = 10;
        for(int i = 2; i < arWidth.length - 1; i++) arWidth[i] = 13;
        arWidth[arWidth.length - 1] = 40;
        sheet.setWidths(arWidth);

        long nFirst = arSample.isEmpty() ? 0 : arSample.get(0).m_nTime;
        for(DeviceStats.Sample s : arSample)
        {
            List<Object> ar = new ArrayList<Object>(arHeader.size());
            ar.add(new Date(s.m_nTime));
            ar.add(round1((s.m_nTime - nFirst) / 1000.0));
            boolean bOk = s.m_strError == null;
            ar.add(bOk && s.m_dCpu >= 0 ? round1(s.m_dCpu) : null);
            boolean bMem = bOk && s.m_nMemTotalKb > 0;
            ar.add(bMem ? round1(s.memUsedPercent()) : null);
            ar.add(bMem ? Long.valueOf((s.m_nMemTotalKb - s.m_nMemAvailKb) / 1024) : null);
            ar.add(bMem ? Long.valueOf(s.m_nMemTotalKb / 1024) : null);
            ar.add(bMem ? Long.valueOf(s.m_nMemAvailKb / 1024) : null);
            ar.add(bOk && s.m_dTemp >= 0 ? round1(s.m_dTemp) : null);
            for(int i = 0; i < nCores; i++)
                ar.add(bOk && i < s.m_arCore.length && s.m_arCore[i] >= 0 ? round1(s.m_arCore[i]) : null);
            for(String strSensor : arSensor)
                ar.add(bOk ? sensorValue(s, strSensor) : null);
            StringBuilder sbNote = new StringBuilder();
            if(s.m_bReboot) sbNote.append("재부팅");
            if(!bOk) sbNote.append(sbNote.length() > 0 ? " / " : "").append("오류: ").append(s.m_strError);
            ar.add(sbNote.length() > 0 ? sbNote.toString() : null);
            sheet.addRow(ar.toArray());
        }

        // ---- 그래프 (1.35): 통계 시트의 셀을 참조하는 엑셀 꺾은선 차트 ----
        if(!arSample.isEmpty())
        {
            XlsxWriter.Sheet sheetGraph = xlsx.addSheet("그래프");
            int nRow1 = 2, nRow2 = arSample.size() + 1;            // 머리줄 다음부터
            String strCat = XlsxWriter.ref(SHEET_STATS, 0, nRow1, nRow2);
            XlsxWriter.Chart chart = sheetGraph.addChart("CPU % · 메모리 % · CPU 온도 °C", 0, 22, 16).setCategories(strCat);
            chart.addSeries("CPU %", XlsxWriter.ref(SHEET_STATS, 2, nRow1, nRow2), COLOR_CPU);
            chart.addSeries("메모리 %", XlsxWriter.ref(SHEET_STATS, 3, nRow1, nRow2), COLOR_MEM);
            chart.addSeries("CPU 온도 °C", XlsxWriter.ref(SHEET_STATS, 7, nRow1, nRow2), COLOR_TEMP);
            if(bCoreChart && nCores > 0)
            {
                XlsxWriter.Chart chartCores = sheetGraph.addChart("코어별 CPU %", 24, 22, 16).setCategories(strCat);
                for(int i = 0; i < nCores; i++)
                    chartCores.addSeries("cpu" + i, XlsxWriter.ref(SHEET_STATS, 8 + i, nRow1, nRow2), COLOR_CORES[i % COLOR_CORES.length]);
            }
        }

        // ---- 상위 프로세스 ----
        XlsxWriter.Sheet sheetTop = xlsx.addSheet("상위 프로세스", "시각", "PID", "CPU %", "프로세스");
        sheetTop.setWidths(20, 10, 10, 50);
        for(DeviceStats.Sample s : arSample)
        {
            if(s.m_strError != null) continue;
            for(String[] t : s.m_arTop)
                sheetTop.addRow(new Date(s.m_nTime), number(t[0]), number(t[1]), t[2]);
        }

        // ---- 정보 ----
        XlsxWriter.Sheet sheetInfo = xlsx.addSheet("정보", "항목", "값");
        sheetInfo.setWidths(18, 60);
        int nError = 0, nRebootRow = 0;
        for(DeviceStats.Sample s : arSample)
        {
            if(s.m_strError != null) nError++;
            if(s.m_bReboot) nRebootRow++;
        }
        long nLast = arSample.isEmpty() ? 0 : arSample.get(arSample.size() - 1).m_nTime;
        long nStart = stats.startTime() > 0 ? stats.startTime() : nFirst;
        long nEnd = stats.finishTime() > 0 ? stats.finishTime() : nLast;
        String strDevice = stats.device();
        if(strDevice.length() == 0 && !arSample.isEmpty() && arSample.get(0).m_strDevice != null)
            strDevice = arSample.get(0).m_strDevice;
        sheetInfo.addRow("프로그램", "KLogFilter " + AppVersion.full());
        sheetInfo.addRow("장치", strDevice.length() > 0 ? strDevice : "(adb 기본 장치)");
        sheetInfo.addRow("측정 시작", nStart > 0 ? new Date(nStart) : null);
        sheetInfo.addRow(stats.finishTime() > 0 ? "측정 완료" : "마지막 측정", nEnd > 0 ? new Date(nEnd) : null);
        sheetInfo.addRow("측정 주기(초)", Long.valueOf(stats.interval() / 1000));
        sheetInfo.addRow("측정 시간 설정", stats.duration() > 0 ? DeviceStats.durationText(stats.duration()) : "계속");
        sheetInfo.addRow("측정 횟수", Integer.valueOf(arSample.size()));
        sheetInfo.addRow("오류 횟수", Integer.valueOf(nError));
        sheetInfo.addRow("재부팅 횟수", Integer.valueOf(Math.max(nRebootRow, arReboot.size())));
        for(String strReboot : arReboot)
            sheetInfo.addRow("재부팅 기록", strReboot);
        if(arSample.size() >= DeviceStats.RECORD_MAX)
            sheetInfo.addRow("주의", "기록이 " + DeviceStats.RECORD_MAX + "개를 넘어 오래된 측정은 빠졌습니다");
        sheetInfo.addRow("저장 시각", new Date());
        return xlsx;
    }

    static Double round1(double v)
    {
        return Double.valueOf(Math.round(v * 10) / 10.0);
    }

    static Double sensorValue(DeviceStats.Sample s, String strSensor)
    {
        for(String[] z : s.m_arThermal)
            if(z[0].equals(strSensor))
                try { return Double.valueOf(Double.parseDouble(z[1])); } catch(NumberFormatException e) { return null; }
        return null;
    }

    // 숫자면 숫자 셀, 아니면 글자 그대로
    static Object number(String s)
    {
        try
        {
            if(s.matches("\\d+")) return Long.valueOf(s);
            return Double.valueOf(Double.parseDouble(s));
        }
        catch(NumberFormatException e)
        {
            return s;
        }
    }
}
