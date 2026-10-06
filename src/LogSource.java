import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.nio.charset.Charset;

/**
 * 로그 입력.
 * - 파일 열기: parseFile() → 줄 위치를 색인하면서 읽은 만큼 바로 표시(부분 로딩)
 * - adb logcat 실시간 수집: startProcess() → adb 출력을 파일에 기록하고(프로세스 스레드),
 *   그 파일을 전체 목록(FileLogStore)으로 써서 50ms마다 늘어난 부분을 색인한다(파일 감시 스레드).
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
    final Object         FILE_LOCK = new Object();      // 기록 파일 쓰기/읽기 동기화

    volatile Process     m_process;
    volatile Thread      m_thProcess;
    volatile Thread      m_thWatchFile;
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
    }

    void startProcess(final String strCmd, final boolean bUtf8)
    {
        m_engine.setStore(new MemoryLogStore(m_parser));
        m_strLogFileName = makeFilename();
        final String strFile = m_strLogFileName;

        m_thProcess = new Thread(new Runnable()
        {
            public void run()
            {
                final int[]    nLines   = { 0 };
                final String[] lastLine = { "" };
                Process process = null;
                try
                {
                    T.d("cmd = " + strCmd);
                    // stderr도 함께 받아 기록한다. (adb 오류 메시지 확인 + stderr 버퍼가 차서 멈추는 일 방지)
                    ProcessBuilder pb = new ProcessBuilder(strCmd.trim().split("\\s+"));
                    pb.redirectErrorStream(true);
                    process = pb.start();
                    m_process = process;
                    m_listener.onStatus("adb 실행 중 : " + strCmd);
                    startNoOutputWatch(process, nLines);

                    try(BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                        Writer fileOut = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(strFile), "UTF-8")))
                    {
                        startFileParse(strFile, bUtf8);

                        String s;
                        while ((s = stdOut.readLine()) != null)
                        {
                            if(!"".equals(s.trim()))
                            {
                                // adb가 장치를 못 찾으면 이 문구만 출력하고 계속 기다린다. 로그로 세지 않고 바로 알린다.
                                if(s.startsWith("- waiting for device"))
                                    m_listener.onStatus("장치를 기다리는 중 : 장치가 연결되어 있지 않거나 offline 상태입니다. Stop 후 Device OK로 상태를 확인하세요.");
                                else
                                    nLines[0]++;
                                lastLine[0] = s;
                                synchronized(FILE_LOCK)
                                {
                                    fileOut.write(s);
                                    fileOut.write("\r\n");
                                    fileOut.flush();
                                }
                            }
                        }
                    }
                }
                catch(Exception e)
                {
                    T.e("e = " + e);
                    m_listener.onStatus("adb error : " + e.getMessage());
                }
                // 그 사이 Stop 후 다시 Run 했다면 새 프로세스를 건드리지 않는다.
                if(m_thProcess == Thread.currentThread())
                {
                    // 사용자가 Stop하지 않았는데 adb가 끝났다: 오류 메시지(마지막 줄)를 상태 표시줄에 보여준다.
                    reportProcessEnd(process, nLines[0], lastLine[0]);
                    stopProcess();
                }
            }
        }, "AdbProcess");
        m_thProcess.start();
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

    // adb 출력이 기록되는 파일을 전체 목록으로 쓰고, 50ms마다 늘어난 부분을 색인한다.
    // ('\n'으로 끝난 줄까지만 색인하므로 기록 중인 마지막 줄은 다음에 읽는다)
    void startFileParse(final String strFile, final boolean bUtf8)
    {
        final File file = new File(strFile);
        m_engine.setStore(new FileLogStore(file, charsetOf(bUtf8), m_parser, 0));
        m_thWatchFile = new Thread(new Runnable()
        {
            public void run()
            {
                m_listener.onTitle(strFile);
                try
                {
                    while(true)
                    {
                        Thread.sleep(50);
                        if(m_bPause)
                            continue;
                        // Clear하면 같은 파일의 이후 부분을 보는 새 목록으로 바뀐다. 다른 파일을 열었으면 건드리지 않는다.
                        LogStore current = m_engine.getStore();
                        if(!(current instanceof FileLogStore) || !((FileLogStore)current).m_file.equals(file))
                            continue;
                        FileLogStore store = (FileLogStore)current;
                        int nAdded = 0, n;
                        while(!m_bPause && (n = store.indexNext(false)) >= 0)
                            nAdded += n;
                        if(nAdded > 0)
                            m_engine.notifyAppended();
                    }
                }
                catch(InterruptedException e)
                {
                    // Stop
                }
                catch(Exception e)
                {
                    T.e(e);
                    e.printStackTrace();
                }
                System.out.println("End WatchFile thread");
            }
        }, "WatchFile");
        m_thWatchFile.start();
    }
    void stopProcess()
    {
        Process process   = m_process;
        Thread  thProcess = m_thProcess, thWatchFile = m_thWatchFile;
        m_process     = null;
        m_thProcess   = null;
        m_thWatchFile = null;
        m_bPause      = false;
        if(process != null) process.destroy();
        if(thProcess != null) thProcess.interrupt();
        if(thWatchFile != null) thWatchFile.interrupt();
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
