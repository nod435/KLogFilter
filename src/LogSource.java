import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

    /**
     * adb 출력 한 줄씩을 기록 파일에 쓰면서, 쓴 위치를 목록(FileLogStore)에 바로 추가한다.
     * 파일을 다시 읽어 색인하지 않고(이전: 50ms마다 감시 스레드가 다시 읽음), 해석한 줄은 캐시에 넣어 화면이 디스크를 읽지 않는다.
     * 줄은 파일에 flush된 뒤에만 목록에 보이게 한다(화면이 아직 기록되지 않은 위치를 읽지 않도록).
     */
    class LiveFeed
    {
        final File         m_file;
        final OutputStream m_out;
        long               m_nWritten;                                  // 파일에 쓴 바이트 수 (AdbProcess 스레드만)
        final ArrayList<long[]> m_arBatch   = new ArrayList<long[]>();  // 쓰고 아직 flush하지 않은 줄 {위치, 길이}
        final ArrayList<String> m_arBatchStr = new ArrayList<String>();
        final ArrayList<long[]> m_arReady   = new ArrayList<long[]>();  // flush했고 화면에 넘길 줄 (Pause 중에는 쌓임)
        final ArrayList<String> m_arReadyStr = new ArrayList<String>();
        long               m_nLastFlush = System.currentTimeMillis();

        LiveFeed(File file) throws IOException
        {
            m_file = file;
            m_out  = new BufferedOutputStream(new FileOutputStream(file), LIVE_BUFFER);
        }

        // AdbProcess 스레드: 한 줄을 파일 버퍼에 쓴다.
        void write(String strLine) throws IOException
        {
            byte[] arByte = strLine.getBytes(ADB_CHARSET);
            m_out.write(arByte);
            m_out.write('\r');
            m_out.write('\n');
            m_arBatch.add(new long[]{ m_nWritten, arByte.length });
            m_arBatchStr.add(strLine);
            m_nWritten += arByte.length + 2;
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
            }
            m_arBatch.clear();
            m_arBatchStr.clear();
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
                }
                m_engine.notifyAppended();
            }
            m_arReady.clear();
            m_arReadyStr.clear();
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

    void startProcess(final String strCmd, final boolean bUtf8)
    {
        m_strLogFileName = makeFilename();
        final File file = new File(m_strLogFileName);
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

                    try(BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream(), ADB_CHARSET), LIVE_BUFFER))
                    {
                        while(true)
                        {
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
                                m_listener.onStatus("장치를 기다리는 중 : 장치가 연결되어 있지 않거나 offline 상태입니다. Stop 후 Device OK로 상태를 확인하세요.");
                            else
                                nLines[0]++;
                            lastLine[0] = s;
                            feed.write(s);
                        }
                    }
                }
                catch(Exception e)
                {
                    // Stop하면 프로세스가 끝나 읽기가 실패할 수 있다.
                    if(m_thProcess == Thread.currentThread())
                    {
                        T.e("e = " + e);
                        m_listener.onStatus("adb error : " + e.getMessage());
                    }
                }
                feed.close();
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
