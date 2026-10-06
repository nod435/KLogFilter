import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 로그 입력.
 * - 파일 열기: parseFile()
 * - adb logcat 실시간 수집: startProcess() → adb 출력을 파일에 기록하고(프로세스 스레드),
 *   그 파일을 50ms마다 이어 읽어 FilterEngine에 추가한다(파일 감시 스레드).
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

    final FilterEngine   m_engine;
    final ILogParser     m_parser;
    final Listener       m_listener;
    final Object         FILE_LOCK = new Object();      // 기록 파일 쓰기/읽기 동기화

    // 파일 파싱 세대 번호. 새 파일을 열면 증가하고, 이전 파싱 스레드는 번호가 바뀐 것을 보고 멈춘다.
    final AtomicInteger  m_nParseGeneration = new AtomicInteger();

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

    static BufferedReader openReader(String strFile, boolean bUtf8) throws Exception
    {
        return new BufferedReader(bUtf8 ? new InputStreamReader(new FileInputStream(strFile), "UTF-8")
                                        : new InputStreamReader(new FileInputStream(strFile)));
    }

    // ---- 파일 열기 ----

    void parseFile(final File file, final boolean bUtf8)
    {
        m_listener.onTitle(file.getPath());
        final int nGeneration = m_nParseGeneration.incrementAndGet();
        new Thread(new Runnable()
        {
            public void run()
            {
                try(BufferedReader br = openReader(file.getPath(), bUtf8))
                {
                    String strLine;

                    m_listener.onStatus("Parsing");
                    m_engine.clearData();
                    while ((strLine = br.readLine()) != null)
                    {
                        if("".equals(strLine.trim())) continue;
                        LogInfo logInfo = m_parser.parseLog(strLine);
                        // 세대 확인과 추가를 같은 락 안에서 해야, 새 파일의 clearData() 뒤에 이전 줄이 섞이지 않는다.
                        synchronized(m_engine.LOCK)
                        {
                            if(nGeneration != m_nParseGeneration.get())
                                return;     // 그 사이 다른 파일을 열었음
                            m_engine.addNext(logInfo);
                        }
                    }
                    m_engine.requestFilter();
                    m_listener.onStatus("Parse complete");
                }
                catch(Exception e)
                {
                    T.e(e);
                    m_listener.onStatus("Parse error : " + e.getMessage());
                }
            }
        }, "ParseFile").start();
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
        m_engine.clearData();
        m_strLogFileName = makeFilename();
        final String strFile = m_strLogFileName;

        m_thProcess = new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    T.d("cmd = " + strCmd);
                    // stderr도 함께 받아 기록한다. (adb 오류 메시지 확인 + stderr 버퍼가 차서 멈추는 일 방지)
                    ProcessBuilder pb = new ProcessBuilder(strCmd.trim().split("\\s+"));
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    m_process = process;

                    try(BufferedReader stdOut = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
                        Writer fileOut = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(strFile), "UTF-8")))
                    {
                        startFileParse(strFile, bUtf8);

                        String s;
                        while ((s = stdOut.readLine()) != null)
                        {
                            if(!"".equals(s.trim()))
                            {
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
                    stopProcess();
            }
        }, "AdbProcess");
        m_thProcess.start();
    }

    // adb 출력이 기록되는 파일을 50ms마다 이어 읽어 FilterEngine에 추가한다.
    void startFileParse(final String strFile, final boolean bUtf8)
    {
        m_thWatchFile = new Thread(new Runnable()
        {
            public void run()
            {
                m_listener.onTitle(strFile);
                try(BufferedReader br = openReader(strFile, bUtf8))
                {
                    String strLine;
                    while(true)
                    {
                        Thread.sleep(50);
                        if(m_engine.isBusy() || m_bPause)
                            continue;

                        int nAddCount = 0;
                        synchronized(FILE_LOCK)
                        {
                            while (!m_bPause && (strLine = br.readLine()) != null)
                            {
                                if("".equals(strLine.trim())) continue;
                                m_engine.addNext(m_parser.parseLog(strLine));
                                nAddCount++;
                            }
                        }
                        if(nAddCount > 0)
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
                            if(s.trim().length() == 0 || s.startsWith("List of devices attached"))
                                continue;
                            s = s.replace("\t", " ");
                            s = s.replace("device", "");
                            arItem.add(s);
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
