import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.HeadlessException;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
import java.util.Map;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

public class LogFilterMain extends JFrame implements INotiEvent
{
    private static final long serialVersionUID           = 1L;
    
    static final String       LOGFILTER                  = "LogFilter";
    static final String       VERSION                    = "Version 1.8";
    final String              COMBO_ANDROID              = "Android          ";
    final String              COMBO_IOS                  = "ios";
    final String              COMBO_CUSTOM_COMMAND       = "custom command";
    final String              IOS_DEFAULT_CMD            = "adb logcat -v time ";
    final String              IOS_SELECTED_CMD_FIRST     = "adb -s ";
    final String              IOS_SELECTED_CMD_LAST      = " logcat -v time ";
//    final String              ANDROID_DEFAULT_CMD        = "logcat -v time ";
//    final String              ANDROID_THREAD_CMD         = "logcat -v threadtime ";
//    final String              ANDROID_EVENT_CMD          = "logcat -b events -v time ";
//    final String              ANDROID_RADIO_CMD          = "logcat -b radio -v time          ";
//    final String              ANDROID_CUSTOM_CMD         = "logcat ";
    final String              ANDROID_DEFAULT_CMD_FIRST  = "adb ";
    final String              ANDROID_SELECTED_CMD_FIRST = "adb -s ";
//    final String              ANDROID_SELECTED_CMD_LAST  = " logcat -v time ";
    final String[]            DEVICES_CMD                = {"adb devices", "", ""};
    
    static final int          DEFAULT_WIDTH              = 1200;
    static final int          DEFAULT_HEIGHT             = 720;
    static final int          MIN_WIDTH                  = 1100;
    static final int          MIN_HEIGHT                 = 500;
    
    static final int          DEVICES_ANDROID            = 0;
    static final int          DEVICES_IOS                = 1;
    static final int          DEVICES_CUSTOM             = 2;
    
    static final int          STATUS_CHANGE              = 1;
    static final int          STATUS_PARSING             = 2;
    static final int          STATUS_READY               = 4;
    
    final int                 L                          = SwingConstants.LEFT;
    final int                 C                          = SwingConstants.CENTER;
    final int                 R                          = SwingConstants.RIGHT;
    
    JTabbedPane               m_tpTab;
    JTextField                m_tfStatus;
    IndicatorPanel            m_ipIndicator;
    // 아래 리스트/맵은 여러 스레드가 함께 쓴다. 비울 때는 clear() 대신 새 객체로 교체하고(clearData),
    // 화면(테이블 모델·인디케이터)에는 refreshTable()로 EDT에서만 반영한다.
    volatile ArrayList<TagInfo>          m_arTagInfo;
    volatile ArrayList<LogInfo>          m_arLogInfoAll;
    volatile ArrayList<LogInfo>          m_arLogInfoFiltered;
    volatile Map<Integer, Integer>       m_hmBookmarkAll;
    volatile Map<Integer, Integer>       m_hmBookmarkFiltered;
    volatile Map<Integer, Integer>       m_hmErrorAll;
    volatile Map<Integer, Integer>       m_hmErrorFiltered;
    // 인디케이터의 "북마크만/에러만 보기" 체크 상태 (EDT에서 갱신, 필터 스레드에서 읽음)
    volatile boolean                     m_bShowBookmarkOnly;
    volatile boolean                     m_bShowErrorOnly;
    ILogParser                m_iLogParser;
    LogTable                  m_tbLogTable;
//    TagTable                    m_tbTagTable;
    JScrollPane               m_scrollVBar;
//    JScrollPane                 m_scrollVTagBar;
    LogFilterTableModel       m_tmLogTableModel;
//    TagFilterTableModel         m_tmTagTableModel;
    volatile boolean          m_bUserFilter;
    
    //Word Filter, tag filter
    JTextField                m_tfHighlight;
    JTextField                m_tfFindWord;
    JTextField                m_tfRemoveWord;
    JTextField                m_tfShowTag;
    JTextField                m_tfRemoveTag;
    JTextField                m_tfShowPid;
    JTextField                m_tfShowTid;
    
    //Device
    JButton                   m_btnDevice;
    JList                     m_lDeviceList;
    JComboBox                 m_comboDeviceCmd;
    JComboBox                 m_comboCmd;
    JButton                   m_btnSetFont;

    //Log filter enable/disable
    JCheckBox                 m_chkEnableFind;
    JCheckBox                 m_chkEnableRemove;
    JCheckBox                 m_chkEnableShowTag;
    JCheckBox                 m_chkEnableRemoveTag;
    JCheckBox                 m_chkEnableShowPid;
    JCheckBox                 m_chkEnableShowTid;
    JCheckBox                 m_chkEnableHighlight;

    //Log filter
    JCheckBox                 m_chkVerbose;
    JCheckBox                 m_chkDebug;
    JCheckBox                 m_chkInfo;
    JCheckBox                 m_chkWarn;
    JCheckBox                 m_chkError;
    JCheckBox                 m_chkFatal;
    
    //Show column
    JCheckBox                 m_chkClmBookmark;
    JCheckBox                 m_chkClmLine;
    JCheckBox                 m_chkClmDate;
    JCheckBox                 m_chkClmTime;
    JCheckBox                 m_chkClmLogLV;
    JCheckBox                 m_chkClmPid;
    JCheckBox                 m_chkClmThread;
    JCheckBox                 m_chkClmTag;
    JCheckBox                 m_chkClmMessage;
    
    JTextField                m_tfFontSize;
//    JTextField                  m_tfProcessCmd;
    JComboBox                 m_comboEncode;
    JComboBox                 m_jcFontType;
    JButton                   m_btnRun;
    JButton                   m_btnClear;
    JToggleButton             m_tbtnPause;
    JButton                   m_btnStop;
    
    String                    m_strLogFileName;
    String                    m_strSelectedDevice;
//    String                      m_strProcessCmd;
    volatile Process          m_Process;
    volatile Thread           m_thProcess;
    volatile Thread           m_thWatchFile;
    Thread                    m_thFilterParse;
    volatile boolean          m_bPauseADB;
    
    Object                    FILE_LOCK;
    Object                    FILTER_LOCK;
    volatile int              m_nChangedFilter;
    int                       m_nFilterLogLV;
    int                       m_nWinWidth  = DEFAULT_WIDTH;
    int                       m_nWinHeight = DEFAULT_HEIGHT;
    int                       m_nLastWidth;
    int                       m_nLastHeight;
    int                       m_nWindState;
    static RecentFileMenu     m_recentMenu;
//    String                    m_strLastDir;

    public static void main(final String args[])
    {
//        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        final LogFilterMain mainFrame = new LogFilterMain();
        mainFrame.setTitle(LOGFILTER + " " + VERSION);
//        mainFrame.addWindowListener(new WindowEventHandler());

        JMenuBar menubar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.setMnemonic(KeyEvent.VK_F);

        JMenuItem fileOpen = new JMenuItem("Open");
        fileOpen.setMnemonic(KeyEvent.VK_O);
        fileOpen.setAccelerator( KeyStroke.getKeyStroke(KeyEvent.VK_O,
                ActionEvent.ALT_MASK) );
        fileOpen.setToolTipText("Open log file");
        fileOpen.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                mainFrame.openFileBrowser();
            }
        });
        
        m_recentMenu = new RecentFileMenu("RecentFile",10){
            public void onSelectFile(String filePath){
                mainFrame.parseFile(new File(filePath));
            }
        };
        
        file.add(fileOpen);
        file.add(m_recentMenu);

        menubar.add(file);
        mainFrame.setJMenuBar(menubar);
        
        if(args != null && args.length > 0)
        {
            EventQueue.invokeLater(new Runnable()
            {
                public void run()
                {
                    mainFrame.parseFile(new File(args[0]));
                }
            });
        }
    }

    String makeFilename()
    {
        Date now = new Date();
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss");
        return "LogFilter_" + format.format(now) + ".txt";
    }
    
    void exit()
    {
        if(m_Process != null) m_Process.destroy();
        if(m_thProcess != null) m_thProcess.interrupt();
        if(m_thWatchFile != null) m_thWatchFile.interrupt();
        if(m_thFilterParse != null) m_thFilterParse.interrupt();

        saveFilter();
        saveColor();
        System.exit(0);
    }

    /**
     * @throws HeadlessException
     */
    public LogFilterMain()
    {
        super();
        addWindowListener(new WindowAdapter()
        {
            public void windowClosing(WindowEvent e)
            {
                exit();
            }
        });
        initValue();
        createComponent();

        Container pane = getContentPane();
        pane.setLayout(new BorderLayout());

        pane.add(getOptionPanel(), BorderLayout.NORTH);
        pane.add(getBookmarkPanel(), BorderLayout.WEST);
        pane.add(getStatusPanel(), BorderLayout.SOUTH);
        pane.add(getTabPanel(), BorderLayout.CENTER);

        setDnDListener();
        addChangeListener();
        startFilterParse();

        setVisible(true);
        addDesc();
        refreshTable(REFRESH_KEEP);     // 안내 문구 행을 테이블에 반영
        loadFilter();
        loadColor();
        loadCmd();
        m_tbLogTable.setColumnWidth();

//        if(m_nWindState == JFrame.MAXIMIZED_BOTH)
//        else
            setSize(m_nWinWidth, m_nWinHeight);
            setExtendedState( m_nWindState );
        setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
    }
    
    final String INI_FILE           = "LogFilter.ini";
    final String INI_FILE_CMD       = "LogFilterCmd.ini";
    final String INI_FILE_COLOR     = "LogFilterColor.ini";
    final String INI_LAST_DIR       = "LAST_DIR";
    final String INI_CMD_COUNT      = "CMD_COUNT";
    final String INI_CMD            = "CMD_";
    final String INI_FONT_TYPE      = "FONT_TYPE";
    final String INI_WORD_FIND      = "WORD_FIND";
    final String INI_WORD_REMOVE    = "WORD_REMOVE";
    final String INI_TAG_SHOW       = "TAG_SHOW";
    final String INI_TAG_REMOVE     = "TAG_REMOVE";
    final String INI_HIGHLIGHT      = "HIGHLIGHT";
    final String INI_PID_SHOW       = "PID_SHOW";
    final String INI_TID_SHOW       = "TID_SHOW";
    final String INI_COLOR_0        = "INI_COLOR_0";
    final String INI_COLOR_1        = "INI_COLOR_1";
    final String INI_COLOR_2        = "INI_COLOR_2";
    final String INI_COLOR_3        = "INI_COLOR_3(E)";
    final String INI_COLOR_4        = "INI_COLOR_4(W)";
    final String INI_COLOR_5        = "INI_COLOR_5";
    final String INI_COLOR_6        = "INI_COLOR_6(I)";
    final String INI_COLOR_7        = "INI_COLOR_7(D)";
    final String INI_COLOR_8        = "INI_COLOR_8(F)";
    final String INI_HIGILIGHT_COUNT= "INI_HIGILIGHT_COUNT";
    final String INI_HIGILIGHT_=    "INI_HIGILIGHT_";
    final String INI_WIDTH          = "INI_WIDTH";
    final String INI_HEIGHT         = "INI_HEIGHT";
    final String INI_WINDOW_STATE   = "INI_WINDOW_STATE";

    final String INI_COMUMN         = "INI_COMUMN_";
    
    // LogFilterCmd.ini가 없거나 비어 있을 때 쓰는 기본 명령 (배포본 ini와 같은 값)
    static final String[] DEFAULT_CMDS = { "logcat -v threadtime", "logcat -v time", "logcat -b radio -v time",
                                           "logcat -b events -v time", "shell cat /proc/kmsg" };

    // 설정 파일 읽기. 파일이 없거나 읽을 수 없으면 빈 Properties를 돌려준다(각 키는 기본값 사용).
    static Properties loadProperties(String strFile)
    {
        Properties p = new Properties();
        File file = new File(strFile);
        if(!file.exists()) return p;
        try(FileInputStream in = new FileInputStream(file))
        {
            p.load(in);
        }
        catch(Exception e)
        {
            System.out.println(strFile + " : " + e);
        }
        return p;
    }

    static void storeProperties(Properties p, String strFile)
    {
        try(FileOutputStream out = new FileOutputStream(strFile))
        {
            p.store(out, "done.");
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
    }

    // 키가 없거나 숫자가 아니면 기본값을 쓴다. (키 하나가 잘못돼도 나머지 설정은 그대로 적용)
    static int intOf(Properties p, String strKey, int nDefault)
    {
        try
        {
            String strValue = p.getProperty(strKey);
            return strValue == null ? nDefault : Integer.parseInt(strValue.trim());
        }
        catch(NumberFormatException e)
        {
            System.out.println(strKey + " : " + e);
            return nDefault;
        }
    }

    static int hexOf(Properties p, String strKey, int nDefault)
    {
        try
        {
            String strValue = p.getProperty(strKey);
            return strValue == null ? nDefault : Integer.parseInt(strValue.trim().replace("0x", "").replace("0X", ""), 16);
        }
        catch(NumberFormatException e)
        {
            System.out.println(strKey + " : " + e);
            return nDefault;
        }
    }

    void loadCmd()
    {
        Properties p = loadProperties(INI_FILE_CMD);
        int nCount = intOf(p, INI_CMD_COUNT, 0);
        T.d("nCount = " + nCount);
        for(int nIndex = 0; nIndex < nCount; nIndex++)
        {
            String strCmd = p.getProperty(INI_CMD + nIndex);
            if(strCmd != null && strCmd.trim().length() > 0)
                m_comboCmd.addItem(strCmd);
        }
        if(m_comboCmd.getItemCount() == 0)
        {
            for(String strCmd : DEFAULT_CMDS)
                m_comboCmd.addItem(strCmd);
        }
    }
    
    void loadColor()
    {
        Properties p = loadProperties(INI_FILE_COLOR);

        LogColor.COLOR_0 = hexOf(p, INI_COLOR_0, LogColor.COLOR_0);
        LogColor.COLOR_1 = hexOf(p, INI_COLOR_1, LogColor.COLOR_1);
        LogColor.COLOR_2 = hexOf(p, INI_COLOR_2, LogColor.COLOR_2);
        LogColor.COLOR_ERROR = LogColor.COLOR_3 = hexOf(p, INI_COLOR_3, LogColor.COLOR_3);
        LogColor.COLOR_WARN  = LogColor.COLOR_4 = hexOf(p, INI_COLOR_4, LogColor.COLOR_4);
        LogColor.COLOR_5 = hexOf(p, INI_COLOR_5, LogColor.COLOR_5);
        LogColor.COLOR_INFO  = LogColor.COLOR_6 = hexOf(p, INI_COLOR_6, LogColor.COLOR_6);
        LogColor.COLOR_DEBUG = LogColor.COLOR_7 = hexOf(p, INI_COLOR_7, LogColor.COLOR_7);
        LogColor.COLOR_FATAL = LogColor.COLOR_8 = hexOf(p, INI_COLOR_8, LogColor.COLOR_8);

        // 하이라이트 색상: 개수만큼 읽되, 빠졌거나 잘못된 값은 건너뛴다.
        ArrayList<String> arHighlight = new ArrayList<String>();
        int nCount = intOf(p, INI_HIGILIGHT_COUNT, 0);
        for(int nIndex = 0; nIndex < nCount; nIndex++)
        {
            String strValue = p.getProperty(INI_HIGILIGHT_ + nIndex);
            if(strValue == null) continue;
            strValue = strValue.trim().replace("0x", "").replace("0X", "");
            if(strValue.matches("[0-9a-fA-F]{1,6}"))
                arHighlight.add(strValue);
        }
        if(arHighlight.isEmpty())
            arHighlight.add("ffff");
        LogColor.COLOR_HIGHLIGHT = arHighlight.toArray(new String[arHighlight.size()]);
    }
    
    void saveColor()
    {
        Properties p = new Properties();

        p.setProperty(INI_COLOR_0, "0x" + Integer.toHexString(LogColor.COLOR_0).toUpperCase());
        p.setProperty(INI_COLOR_1, "0x" + Integer.toHexString(LogColor.COLOR_1).toUpperCase());
        p.setProperty(INI_COLOR_2, "0x" + Integer.toHexString(LogColor.COLOR_2).toUpperCase());
        p.setProperty(INI_COLOR_3, "0x" + Integer.toHexString(LogColor.COLOR_3).toUpperCase());
        p.setProperty(INI_COLOR_4, "0x" + Integer.toHexString(LogColor.COLOR_4).toUpperCase());
        p.setProperty(INI_COLOR_5, "0x" + Integer.toHexString(LogColor.COLOR_5).toUpperCase());
        p.setProperty(INI_COLOR_6, "0x" + Integer.toHexString(LogColor.COLOR_6).toUpperCase());
        p.setProperty(INI_COLOR_7, "0x" + Integer.toHexString(LogColor.COLOR_7).toUpperCase());
        p.setProperty(INI_COLOR_8, "0x" + Integer.toHexString(LogColor.COLOR_8).toUpperCase());

        if(LogColor.COLOR_HIGHLIGHT != null)
        {
            p.setProperty(INI_HIGILIGHT_COUNT, "" + LogColor.COLOR_HIGHLIGHT.length);
            for(int nIndex = 0; nIndex < LogColor.COLOR_HIGHLIGHT.length; nIndex++)
                p.setProperty(INI_HIGILIGHT_ + nIndex, "0x" + LogColor.COLOR_HIGHLIGHT[nIndex].toUpperCase());
        }

        storeProperties(p, INI_FILE_COLOR);
    }
    
    void loadFilter()
    {
        Properties p = loadProperties(INI_FILE);

        String strFontType = p.getProperty(INI_FONT_TYPE);
        if(strFontType != null && strFontType.length() > 0)
            m_jcFontType.setSelectedItem(strFontType);
        m_tfFindWord.setText(p.getProperty(INI_WORD_FIND, ""));
        m_tfRemoveWord.setText(p.getProperty(INI_WORD_REMOVE, ""));
        m_tfShowTag.setText(p.getProperty(INI_TAG_SHOW, ""));
        m_tfRemoveTag.setText(p.getProperty(INI_TAG_REMOVE, ""));
        m_tfShowPid.setText(p.getProperty(INI_PID_SHOW, ""));
        m_tfShowTid.setText(p.getProperty(INI_TID_SHOW, ""));
        m_tfHighlight.setText(p.getProperty(INI_HIGHLIGHT, ""));
        m_nWinWidth  = Math.max(MIN_WIDTH,  intOf(p, INI_WIDTH,  DEFAULT_WIDTH));
        m_nWinHeight = Math.max(MIN_HEIGHT, intOf(p, INI_HEIGHT, DEFAULT_HEIGHT));
        m_nWindState = intOf(p, INI_WINDOW_STATE, JFrame.NORMAL);
        if(m_nWindState == JFrame.ICONIFIED)
            m_nWindState = JFrame.NORMAL;   // 최소화 상태로 시작하지 않도록

        for(int nIndex = 0; nIndex < LogFilterTableModel.COMUMN_MAX; nIndex++)
        {
            LogFilterTableModel.setColumnWidth(nIndex, intOf(p, INI_COMUMN + nIndex, LogFilterTableModel.ColWidth[nIndex]));
        }
    }
    
    void saveFilter()
    {
        // 창 크기는 최대화가 아닐 때 마지막으로 기록된 값을 쓴다. (한 번도 기록되지 않았으면 기존 값 유지)
        if(m_nLastWidth > 0 && m_nLastHeight > 0)
        {
            m_nWinWidth  = m_nLastWidth;
            m_nWinHeight = m_nLastHeight;
        }
        m_nWindState = getExtendedState();
        T.d("m_nWindState = " + m_nWindState);

        Properties p = new Properties();
        p.setProperty(INI_FONT_TYPE,   (String)m_jcFontType.getSelectedItem());
        p.setProperty(INI_WORD_FIND,   m_tfFindWord.getText());
        p.setProperty(INI_WORD_REMOVE, m_tfRemoveWord.getText());
        p.setProperty(INI_TAG_SHOW,    m_tfShowTag.getText());
        p.setProperty(INI_TAG_REMOVE,  m_tfRemoveTag.getText());
        p.setProperty(INI_PID_SHOW,    m_tfShowPid.getText());
        p.setProperty(INI_TID_SHOW,    m_tfShowTid.getText());
        p.setProperty(INI_HIGHLIGHT,   m_tfHighlight.getText());
        p.setProperty(INI_WIDTH,       "" + m_nWinWidth);
        p.setProperty(INI_HEIGHT,      "" + m_nWinHeight);
        p.setProperty(INI_WINDOW_STATE,"" + m_nWindState);

        for(int nIndex = 0; nIndex < LogFilterTableModel.COMUMN_MAX; nIndex++)
        {
            p.setProperty(INI_COMUMN + nIndex, "" + m_tbLogTable.getColumnWidth(nIndex));
        }
        storeProperties(p, INI_FILE);
    }
        void addDesc(String strMessage)
    {
        LogInfo logInfo = new LogInfo();
        logInfo.setLine(m_arLogInfoAll.size() + 1);
        logInfo.m_strMessage = strMessage;
        m_arLogInfoAll.add(logInfo);
    }

    void addDesc()
    {
        addDesc(VERSION);
        addDesc("");
        addDesc("Version 1.8 : java -jar LogFilter_xx.jar [filename] 추가");
        addDesc("Version 1.7 : copy시 보이는 column만 clipboard에 복사(Line 제외)");
        addDesc("Version 1.6 : cmd콤보박스 길이 고정");
        addDesc("Version 1.5 : Highlight color list추가()");
        addDesc("   - LogFilterColor.ini 에 카운트와 값 넣어 주시면 됩니다.");
        addDesc("   - ex)INI_HIGILIGHT_COUNT=2");
        addDesc("   -    INI_HIGILIGHT_0=0xFFFF");
        addDesc("   -    INI_HIGILIGHT_1=0x00FF");
        addDesc("Version 1.4 : 창크기 저장");
        addDesc("Version 1.3 : recent file 및 open메뉴추가");
        addDesc("Version 1.2 : Tid 필터 추가");
        addDesc("Version 1.1 : Level F 추가");
        addDesc("Version 1.0 : Pid filter 추가");
        addDesc("Version 0.9 : Font type 추가");
        addDesc("Version 0.8 : 필터체크 박스 추가");
        addDesc("Version 0.7 : 커널로그 파싱/LogFilter.ini에 컬러정의(0~7)");
        addDesc("Version 0.6 : 필터 대소문 무시");
        addDesc("Version 0.5 : 명령어 ini파일로 저장");
        addDesc("Version 0.4 : add thread option, filter 저장");
        addDesc("Version 0.3 : 단말 선택 안되는 문제 수정");
        addDesc("");
        addDesc("[Tag]");
        addDesc("Alt+L/R Click : Show/Remove tag");
        addDesc("");
        addDesc("[Bookmark]");
        addDesc("Ctrl+F2/double click: bookmark toggle");
        addDesc("F2 : pre bookmark");
        addDesc("F3 : next bookmark");
        addDesc("");
        addDesc("[Copy]");
        addDesc("Ctrl+c : row copy");
        addDesc("right click : cloumn copy");
        addDesc("");
        addDesc("[New version]");
        addDesc("http://blog.naver.com/iookill/140135139931");
    }

    /**
     * @param nIndex    실제 리스트의 인덱스
     * @param nLine     m_strLine
     * @param bBookmark
     */
    void bookmarkItem(int nIndex, int nLine, boolean bBookmark)
    {
        synchronized(FILTER_LOCK)
        {
            // 화면에 아직 이전 목록이 보이는 동안(clearData 직후) 클릭한 경우 범위를 벗어날 수 있다.
            if(nLine < 0 || nLine >= m_arLogInfoAll.size()) return;
            LogInfo logInfo = m_arLogInfoAll.get(nLine);
            logInfo.m_bMarked = bBookmark;

            if(logInfo.m_bMarked)
            {
                m_hmBookmarkAll.put(nLine, nLine);
                if(m_bUserFilter)
                    m_hmBookmarkFiltered.put(nLine, nIndex);
            }
            else
            {
                m_hmBookmarkAll.remove(nLine);
                if(m_bUserFilter)
                    m_hmBookmarkFiltered.remove(nLine);
            }
        }
        m_ipIndicator.repaint();
    }

    // 모든 로그를 지운다. 화면이 참조 중인 리스트를 clear()하지 않고 새 객체로 교체한 뒤 EDT에서 반영한다.
    void clearData()
    {
        synchronized(FILTER_LOCK)
        {
            m_arTagInfo         = new ArrayList<TagInfo>();
            m_arLogInfoAll      = new ArrayList<LogInfo>();
            m_arLogInfoFiltered = new ArrayList<LogInfo>();
            m_hmBookmarkAll     = new ConcurrentHashMap<Integer, Integer>();
            m_hmBookmarkFiltered= new ConcurrentHashMap<Integer, Integer>();
            m_hmErrorAll        = new ConcurrentHashMap<Integer, Integer>();
            m_hmErrorFiltered   = new ConcurrentHashMap<Integer, Integer>();
        }
        refreshTable(REFRESH_KEEP);
    }

    // ---- 화면 반영 (항상 EDT에서 실행) ----------------------------------------------------
    static final int REFRESH_KEEP        = 0;   // 선택 유지
    static final int REFRESH_FOLLOW_END  = 1;   // 마지막 행을 보고 있었다면 새 마지막 행으로 따라감
    static final int REFRESH_SELECT_LAST = 2;   // 마지막 행을 선택하고 스크롤

    // 지금 스레드가 EDT면 바로, 아니면 EDT에 넘겨 실행한다.
    static void runOnEdt(Runnable runnable)
    {
        if(SwingUtilities.isEventDispatchThread())
            runnable.run();
        else
            SwingUtilities.invokeLater(runnable);
    }

    // 필터 사용 여부에 맞는 목록(All/Filtered)을 테이블과 인디케이터에 반영한다. 어느 스레드에서 불러도 된다.
    void refreshTable(final int nMode)
    {
        runOnEdt(new Runnable()
        {
            public void run()
            {
                ArrayList<LogInfo>    arList;
                Map<Integer, Integer> hmBookmark, hmError;
                if(m_bUserFilter)
                {
                    arList = m_arLogInfoFiltered; hmBookmark = m_hmBookmarkFiltered; hmError = m_hmErrorFiltered;
                }
                else
                {
                    arList = m_arLogInfoAll;      hmBookmark = m_hmBookmarkAll;      hmError = m_hmErrorAll;
                }

                int nOldCount    = m_tmLogTableModel.getRowCount();
                int nSelected    = m_tbLogTable.getSelectedRow();
                boolean bAtEnd   = nSelected == -1 || nSelected == nOldCount - 1;

                if(m_tmLogTableModel.getData() != arList)
                {
                    // 다른 목록으로 교체: 전체 다시 그리기 (선택은 해제됨)
                    m_tmLogTableModel.setData(arList);
                    m_tmLogTableModel.fireTableDataChanged();
                }
                else
                {
                    // 같은 목록에 줄이 추가된 경우: 추가된 행만 알려서 선택을 유지한다.
                    int nNewCount = m_tmLogTableModel.syncRowCount();
                    if(nNewCount > nOldCount)
                        m_tmLogTableModel.fireTableRowsInserted(nOldCount, nNewCount - 1);
                    else if(nNewCount < nOldCount)
                        m_tmLogTableModel.fireTableDataChanged();
                }
                m_ipIndicator.setData(arList, hmBookmark, hmError);

                int nLast = m_tmLogTableModel.getRowCount() - 1;
                if(nLast >= 0 && (nMode == REFRESH_SELECT_LAST || (nMode == REFRESH_FOLLOW_END && bAtEnd)))
                    m_tbLogTable.changeSelection(nLast, 0, false, false, true);
                m_ipIndicator.repaint();
            }
        });
    }

    // 필터 조건을 통과하면 arFiltered에 추가하고 북마크/에러 위치를 기록한다. (addLogInfo와 재필터가 함께 사용)
    void addIfAccepted(LogInfo logInfo, ArrayList<LogInfo> arFiltered, Map<Integer, Integer> hmBookmark, Map<Integer, Integer> hmError)
    {
        boolean bAccept;
        if(m_bShowBookmarkOnly || m_bShowErrorOnly)
            bAccept = (logInfo.m_bMarked && m_bShowBookmarkOnly) || (logInfo.isError() && m_bShowErrorOnly);
        else
            bAccept = checkLogLVFilter(logInfo)
                   && checkPidFilter(logInfo)
                   && checkTidFilter(logInfo)
                   && checkShowTagFilter(logInfo)
                   && checkRemoveTagFilter(logInfo)
                   && checkFindFilter(logInfo)
                   && checkRemoveFilter(logInfo);
        if(!bAccept) return;

        int nPos = arFiltered.size();   // 추가되기 전 크기 = 이 줄이 표시될 행 번호
        if(logInfo.m_bMarked) hmBookmark.put(logInfo.m_nLine - 1, nPos);
        if(logInfo.isError()) hmError.put(logInfo.m_nLine - 1, nPos);
        arFiltered.add(logInfo);
    }
    void createComponent()
    {
    }

    Component getBookmarkPanel()
    {
        JPanel jp = new JPanel();
        jp.setLayout(new BorderLayout());

//        //iookill
//        m_tmTagTableModel = new TagFilterTableModel();
//        m_tmTagTableModel.setData(m_arTagInfo);
//        m_tbTagTable = new TagTable(m_tmTagTableModel, this);
//
//        m_scrollVTagBar = new JScrollPane(m_tbTagTable);
//        m_scrollVTagBar.setPreferredSize(new Dimension(182,50));
//        // show list
//        jp.add(m_scrollVTagBar, BorderLayout.WEST);

        m_ipIndicator = new IndicatorPanel(this);
        m_ipIndicator.setData(m_arLogInfoAll, m_hmBookmarkAll, m_hmErrorAll);
        jp.add(m_ipIndicator, BorderLayout.CENTER);
        return jp;
    }

    Component getCmdPanel()
    {
        JPanel jpOptionDevice = new JPanel();
        jpOptionDevice.setBorder(BorderFactory.createTitledBorder("Device select"));
        jpOptionDevice.setLayout(new BorderLayout());
//        jpOptionDevice.setPreferredSize(new Dimension(200, 100));

        JPanel jpCmd = new JPanel();
        m_comboDeviceCmd = new JComboBox();
        m_comboDeviceCmd.addItem(COMBO_ANDROID);
//        m_comboDeviceCmd.addItem(COMBO_IOS);
//        m_comboDeviceCmd.addItem(CUSTOM_COMMAND);
        m_comboDeviceCmd.addItemListener(new ItemListener()
        {
            public void itemStateChanged(ItemEvent e)
            {
                if(e.getStateChange() != ItemEvent.SELECTED) return;

                DefaultListModel listModel = (DefaultListModel)m_lDeviceList.getModel();
                listModel.clear();
                if (e.getItem().equals(COMBO_CUSTOM_COMMAND)) {
                    m_comboDeviceCmd.setEditable(true);
                } else {
                    m_comboDeviceCmd.setEditable(false);
                }
                setProcessCmd(m_comboDeviceCmd.getSelectedIndex(), m_strSelectedDevice);
            }
        });

        final DefaultListModel listModel = new DefaultListModel();
        m_btnDevice = new JButton("OK");
        m_btnDevice.setMargin(new Insets(0, 0, 0, 0));
        m_btnDevice.addActionListener(m_alButtonListener);

        jpCmd.add(m_comboDeviceCmd);
        jpCmd.add(m_btnDevice);

        jpOptionDevice.add(jpCmd, BorderLayout.NORTH);

        m_lDeviceList = new JList(listModel);
        JScrollPane vbar = new JScrollPane(m_lDeviceList);
        vbar.setPreferredSize(new Dimension(100,50));
        m_lDeviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        m_lDeviceList.addListSelectionListener(new ListSelectionListener()
        {
            public void valueChanged(ListSelectionEvent e)
            {
                JList deviceList = (JList)e.getSource();
                Object selectedItem = (Object)deviceList.getSelectedValue();
                m_strSelectedDevice = "";
                if(selectedItem != null)
                {
                    m_strSelectedDevice = selectedItem.toString();
                    m_strSelectedDevice = m_strSelectedDevice.replace("\t", " ").replace("device", "").replace("offline", "");
                    setProcessCmd(m_comboDeviceCmd.getSelectedIndex(), m_strSelectedDevice);
                }
            }
        });
        jpOptionDevice.add(vbar);

        return jpOptionDevice;
    }

    void addTagList(String strTag)
    {
//        for(TagInfo tagInfo : m_arTagInfo)
//            if(tagInfo.m_strTag.equals(strTag))
//                return;
//        String strRemoveFilter = m_tbLogTable.GetFilterRemoveTag();
//        String strShowFilter = m_tbLogTable.GetFilterShowTag();
//        TagInfo tagInfo = new TagInfo();
//        tagInfo.m_strTag = strTag;
//        if(strRemoveFilter.contains(strTag))
//            tagInfo.m_bRemove = true;
//        if(strShowFilter.contains(strTag))
//            tagInfo.m_bShow = true;
//        m_arTagInfo.add(tagInfo);
//        m_tmTagTableModel.setData(m_arTagInfo);
//
//        m_tmTagTableModel.fireTableRowsUpdated(0, m_tmTagTableModel.getRowCount() - 1);
//        m_scrollVTagBar.validate();
//        m_tbTagTable.invalidate();
//        m_tbTagTable.repaint();
//            m_tbTagTable.changeSelection(0, 0, false, false);
    }

    void addLogInfo(LogInfo logInfo)
    {
        synchronized(FILTER_LOCK)
        {
            m_tbLogTable.setTagLength( logInfo.m_strTag.length() );
            m_arLogInfoAll.add(logInfo);
            if(logInfo.isError())
                m_hmErrorAll.put(logInfo.m_nLine - 1, logInfo.m_nLine - 1);

            if(m_bUserFilter)
                addIfAccepted(logInfo, m_arLogInfoFiltered, m_hmBookmarkFiltered, m_hmErrorFiltered);
        }
    }
    void addChangeListener()
    {
        m_tfHighlight.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfFindWord.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfRemoveWord.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfShowTag.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfRemoveTag.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfShowPid.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfShowTid.getDocument().addDocumentListener(m_dlFilterListener);

        m_chkEnableFind.addItemListener(m_itemListener);
        m_chkEnableRemove.addItemListener(m_itemListener);
        m_chkEnableShowPid.addItemListener(m_itemListener);
        m_chkEnableShowTid.addItemListener(m_itemListener);
        m_chkEnableShowTag.addItemListener(m_itemListener);
        m_chkEnableRemoveTag.addItemListener(m_itemListener);
        m_chkEnableHighlight.addItemListener(m_itemListener);

        m_chkVerbose.addItemListener(m_itemListener);
        m_chkDebug.addItemListener(m_itemListener);
        m_chkInfo.addItemListener(m_itemListener);
        m_chkWarn.addItemListener(m_itemListener);
        m_chkError.addItemListener(m_itemListener);
        m_chkFatal.addItemListener(m_itemListener);
        m_chkClmBookmark.addItemListener(m_itemListener);
        m_chkClmLine.addItemListener(m_itemListener);
        m_chkClmDate.addItemListener(m_itemListener);
        m_chkClmTime.addItemListener(m_itemListener);
        m_chkClmLogLV.addItemListener(m_itemListener);
        m_chkClmPid.addItemListener(m_itemListener);
        m_chkClmThread.addItemListener(m_itemListener);
        m_chkClmTag.addItemListener(m_itemListener);
        m_chkClmMessage.addItemListener(m_itemListener);


        m_scrollVBar.getViewport().addChangeListener(new ChangeListener()
        {
            public void stateChanged(ChangeEvent e)
            {
//                m_ipIndicator.m_bDrawFull = false;
                if(getExtendedState() != JFrame.MAXIMIZED_BOTH)
                {
                    m_nLastWidth  = getWidth();
                    m_nLastHeight = getHeight();
                }
                m_ipIndicator.repaint();
            }
        });
    }

    // 편집창(텍스트 필드)에 Ctrl+Z(실행취소) / Ctrl+Y(다시실행) 백업 기능을 추가한다.
    void installUndoRedo(final JTextComponent comp)
    {
        final UndoManager undoManager = new UndoManager();
        // 편집 내용을 계속 백업(undo 스택에 저장)한다.
        comp.getDocument().addUndoableEditListener(new UndoableEditListener()
        {
            public void undoableEditHappened(UndoableEditEvent e)
            {
                undoManager.addEdit(e.getEdit());
            }
        });

        // Ctrl+Z : 이전 편집 내용으로 되돌리기
        comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "Undo");
        comp.getActionMap().put("Undo", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                try
                {
                    if(undoManager.canUndo())
                        undoManager.undo();
                }
                catch(CannotUndoException ex)
                {
                }
            }
        });

        // Ctrl+Y : 되돌린 내용 다시 실행
        comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "Redo");
        comp.getActionMap().put("Redo", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                try
                {
                    if(undoManager.canRedo())
                        undoManager.redo();
                }
                catch(CannotRedoException ex)
                {
                }
            }
        });
    }

    // 편집창별 최근 입력값 cache 최대 개수
    static final int MAX_INPUT_HISTORY = 10;

    // 편집창(텍스트 필드)에 최근 입력값 cache(최대 10개)와 ↑/↓ 방향키 불러오기 기능을 추가한다.
    void installInputHistory(final JTextComponent comp)
    {
        // 최근 입력값 목록(맨 앞이 가장 최근). Enter 또는 포커스 이동 시 저장된다.
        final List<String> history = new ArrayList<String>();
        // 현재 히스토리 탐색 위치. -1이면 탐색 중이 아님(사용자가 직접 편집 중).
        final int[] cursor = { -1 };

        // 현재 값을 cache에 저장(중복 제거 후 맨 앞으로, 최대 10개 유지)
        final Runnable commit = new Runnable()
        {
            public void run()
            {
                String text = comp.getText();
                if(text == null || text.trim().length() == 0)
                    return;
                history.remove(text);
                history.add(0, text);
                while(history.size() > MAX_INPUT_HISTORY)
                    history.remove(history.size() - 1);
                cursor[0] = -1;
            }
        };

        // Enter : 현재 값을 cache에 저장
        comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "HistoryCommit");
        comp.getActionMap().put("HistoryCommit", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                commit.run();
            }
        });

        // ↑ : 더 예전 입력값으로 이동
        comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "HistoryPrev");
        comp.getActionMap().put("HistoryPrev", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                if(history.isEmpty())
                    return;
                if(cursor[0] < history.size() - 1)
                    cursor[0]++;
                comp.setText(history.get(cursor[0]));
                comp.setCaretPosition(comp.getDocument().getLength());
            }
        });

        // ↓ : 더 최근 입력값으로 이동(맨 앞을 지나면 빈 값으로)
        comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "HistoryNext");
        comp.getActionMap().put("HistoryNext", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                if(cursor[0] <= 0)
                {
                    cursor[0] = -1;
                    comp.setText("");
                    return;
                }
                cursor[0]--;
                comp.setText(history.get(cursor[0]));
                comp.setCaretPosition(comp.getDocument().getLength());
            }
        });

        // 포커스가 벗어날 때도 현재 값을 cache에 저장(Enter를 누르지 않아도 기록)
        comp.addFocusListener(new java.awt.event.FocusAdapter()
        {
            public void focusLost(java.awt.event.FocusEvent e)
            {
                commit.run();
            }
        });
    }

    Component getFilterPanel()
    {
        m_chkEnableFind         = new JCheckBox();
        m_chkEnableRemove       = new JCheckBox();
        m_chkEnableShowTag      = new JCheckBox();
        m_chkEnableRemoveTag    = new JCheckBox();
        m_chkEnableShowPid      = new JCheckBox();
        m_chkEnableShowTid      = new JCheckBox();
        m_chkEnableFind.setSelected(true);
        m_chkEnableRemove.setSelected(true);
        m_chkEnableShowTag.setSelected(true);
        m_chkEnableRemoveTag.setSelected(true);
        m_chkEnableShowPid.setSelected(true);
        m_chkEnableShowTid.setSelected(true);

        m_tfFindWord    = new JTextField();
        m_tfRemoveWord  = new JTextField();
        m_tfShowTag     = new JTextField();
        m_tfRemoveTag   = new JTextField();
        m_tfShowPid     = new JTextField();
        m_tfShowTid     = new JTextField();

        // 필터 편집창에 Ctrl+Z 백업(실행취소) 기능 추가
        installUndoRedo(m_tfFindWord);
        installUndoRedo(m_tfRemoveWord);
        installUndoRedo(m_tfShowTag);
        installUndoRedo(m_tfRemoveTag);
        installUndoRedo(m_tfShowPid);
        installUndoRedo(m_tfShowTid);

        JPanel jpMain = new JPanel(new BorderLayout());

        JPanel jpWordFilter = new JPanel(new BorderLayout());
        jpWordFilter.setBorder(BorderFactory.createTitledBorder("Word filter"));

        JPanel jpFind = new JPanel(new BorderLayout());
        JLabel find = new JLabel();
        find.setText("        Find : ");
        jpFind.add(find, BorderLayout.WEST);
        jpFind.add(m_tfFindWord, BorderLayout.CENTER);
        jpFind.add(m_chkEnableFind, BorderLayout.EAST);

        JPanel jpRemove = new JPanel(new BorderLayout());
        JLabel remove = new JLabel();
        remove.setText("Remove : ");
        jpRemove.add(remove, BorderLayout.WEST);
        jpRemove.add(m_tfRemoveWord, BorderLayout.CENTER);
        jpRemove.add(m_chkEnableRemove, BorderLayout.EAST);

        jpWordFilter.add(jpFind, BorderLayout.NORTH);
        jpWordFilter.add(jpRemove);

        jpMain.add(jpWordFilter, BorderLayout.NORTH);

        JPanel jpTagFilter = new JPanel(new GridLayout(4, 1));
        jpTagFilter.setBorder(BorderFactory.createTitledBorder("Tag filter"));

        JPanel jpPid = new JPanel(new BorderLayout());
        JLabel pid = new JLabel();
        pid.setText("         Pid : ");
        jpPid.add(pid, BorderLayout.WEST);
        jpPid.add(m_tfShowPid, BorderLayout.CENTER);
        jpPid.add(m_chkEnableShowPid, BorderLayout.EAST);

        JPanel jpTid = new JPanel(new BorderLayout());
        JLabel tid = new JLabel();
        tid.setText("         Tid : ");
        jpTid.add(tid, BorderLayout.WEST);
        jpTid.add(m_tfShowTid, BorderLayout.CENTER);
        jpTid.add(m_chkEnableShowTid, BorderLayout.EAST);

        JPanel jpShow = new JPanel(new BorderLayout());
        JLabel show = new JLabel();
        show.setText("     Show : ");
        jpShow.add(show, BorderLayout.WEST);
        jpShow.add(m_tfShowTag, BorderLayout.CENTER);
        jpShow.add(m_chkEnableShowTag, BorderLayout.EAST);

        JPanel jpRemoveTag = new JPanel(new BorderLayout());
        JLabel removeTag = new JLabel();
        removeTag.setText("Remove : ");
        jpRemoveTag.add(removeTag, BorderLayout.WEST);
        jpRemoveTag.add(m_tfRemoveTag, BorderLayout.CENTER);
        jpRemoveTag.add(m_chkEnableRemoveTag, BorderLayout.EAST);

        jpTagFilter.add(jpPid);
        jpTagFilter.add(jpTid);
        jpTagFilter.add(jpShow);
        jpTagFilter.add(jpRemoveTag);

        jpMain.add(jpTagFilter, BorderLayout.CENTER);

        return jpMain;
    }

    Component getHighlightPanel()
    {
        m_chkEnableHighlight   = new JCheckBox();
        m_chkEnableHighlight.setSelected(true);

        m_tfHighlight   = new JTextField();
        m_tfHighlight.setPreferredSize(new Dimension(300, 25));
        installUndoRedo(m_tfHighlight);

        JPanel jpMain = new JPanel(new BorderLayout());
        jpMain.setBorder(BorderFactory.createTitledBorder("Highlight"));

        JPanel jpLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        JLabel jlHighlight = new JLabel();
        jlHighlight.setText("Highlight : ");
        jpLeft.add(jlHighlight);
        jpLeft.add(m_tfHighlight);

        jpMain.add(jpLeft, BorderLayout.WEST);
        jpMain.add(m_chkEnableHighlight, BorderLayout.EAST);

        return jpMain;
    }

    Component getCheckPanel()
    {
        m_chkVerbose    = new JCheckBox();
        m_chkDebug      = new JCheckBox();
        m_chkInfo       = new JCheckBox();
        m_chkWarn       = new JCheckBox();
        m_chkError      = new JCheckBox();
        m_chkFatal      = new JCheckBox();

        m_chkClmBookmark= new JCheckBox();
        m_chkClmLine    = new JCheckBox();
        m_chkClmDate    = new JCheckBox();
        m_chkClmTime    = new JCheckBox();
        m_chkClmLogLV   = new JCheckBox();
        m_chkClmPid     = new JCheckBox();
        m_chkClmThread  = new JCheckBox();
        m_chkClmTag     = new JCheckBox();
        m_chkClmMessage = new JCheckBox();

        JPanel jpMain = new JPanel(new BorderLayout());

        JPanel jpLogFilter = new JPanel();
        jpLogFilter.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
        jpLogFilter.setBorder(BorderFactory.createTitledBorder("Log filter"));
        m_chkVerbose.setText("Verbose");
        m_chkVerbose.setSelected(true);
        m_chkDebug.setText("Debug");
        m_chkDebug.setSelected(true);
        m_chkInfo.setText("Info");
        m_chkInfo.setSelected(true);
        m_chkWarn.setText("Warn");
        m_chkWarn.setSelected(true);
        m_chkError.setText("Error");
        m_chkError.setSelected(true);
        m_chkFatal.setText("Fatal");
        m_chkFatal.setSelected(true);
        jpLogFilter.add(m_chkVerbose);
        jpLogFilter.add(m_chkDebug);
        jpLogFilter.add(m_chkInfo);
        jpLogFilter.add(m_chkWarn);
        jpLogFilter.add(m_chkError);
        jpLogFilter.add(m_chkFatal);

        jpMain.add(jpLogFilter, BorderLayout.NORTH);

        JPanel jpShowColumn = new JPanel();
        jpShowColumn.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
        jpShowColumn.setBorder(BorderFactory.createTitledBorder("Show column"));
        m_chkClmBookmark.setText("Mark");
        m_chkClmBookmark.setToolTipText("Bookmark");
        m_chkClmLine.setText("Line");
        m_chkClmLine.setSelected(true);
        m_chkClmDate.setText("Date");
        m_chkClmDate.setSelected(true);
        m_chkClmTime.setText("Time");
        m_chkClmTime.setSelected(true);
        m_chkClmLogLV.setText("LogLV");
        m_chkClmLogLV.setSelected(true);
        m_chkClmPid.setText("Pid");
        m_chkClmPid.setSelected(true);
        m_chkClmThread.setText("Thread");
        m_chkClmThread.setSelected(true);
        m_chkClmTag.setText("Tag");
        m_chkClmTag.setSelected(true);
        m_chkClmMessage.setText("Msg");
        m_chkClmMessage.setSelected(true);
        jpShowColumn.add(m_chkClmBookmark);
        jpShowColumn.add(m_chkClmLine);
        jpShowColumn.add(m_chkClmDate);
        jpShowColumn.add(m_chkClmTime);
        jpShowColumn.add(m_chkClmLogLV);
        jpShowColumn.add(m_chkClmPid);
        jpShowColumn.add(m_chkClmThread);
        jpShowColumn.add(m_chkClmTag);
        jpShowColumn.add(m_chkClmMessage);

        jpMain.add(jpShowColumn, BorderLayout.CENTER);
        jpMain.add(getHighlightPanel(), BorderLayout.SOUTH);
        return jpMain;
    }

    Component getOptionFilter()
    {
        JPanel optionFilter = new JPanel(new BorderLayout());

        optionFilter.add(getCmdPanel(), BorderLayout.WEST);
        optionFilter.add(getCheckPanel(), BorderLayout.EAST);
        optionFilter.add(getFilterPanel(), BorderLayout.CENTER);

        return optionFilter;
    }

    Component getOptionMenu()
    {
        JPanel optionMenu = new JPanel(new BorderLayout());
        JPanel optionWest = new JPanel();

        JLabel jlFontType = new JLabel("Font Type : ");
        m_jcFontType = new JComboBox();
        String fonts[] = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        m_jcFontType.addItem("Dialog");
        for ( int i = 0; i < fonts.length; i++ )
        {
            m_jcFontType.addItem(fonts[i]);
        }
        m_jcFontType.addActionListener(m_alButtonListener);


        JLabel jlFont = new JLabel("Font Size : ");
        m_tfFontSize = new JTextField(2);
        m_tfFontSize.setHorizontalAlignment(SwingConstants.RIGHT);
        m_tfFontSize.setText("12");

        m_btnSetFont = new JButton("OK");
        m_btnSetFont.setMargin(new Insets(0, 0, 0, 0));
        m_btnSetFont.addActionListener(m_alButtonListener);

        JLabel jlEncode = new JLabel("Text Encode : ");
        m_comboEncode = new JComboBox();
        m_comboEncode.addItem("UTF-8");
        m_comboEncode.addItem("Local");

        JLabel jlGoto = new JLabel("Goto : ");
        final JTextField tfGoto = new JTextField(6);
        tfGoto.setHorizontalAlignment(SwingConstants.RIGHT);
        tfGoto.addCaretListener(new CaretListener(){
            public void caretUpdate(CaretEvent e)
            {
                try
                {
                    int nIndex = Integer.parseInt(tfGoto.getText()) - 1;
                    m_tbLogTable.showRow(nIndex, false);
                }
                catch(Exception err)
                {
                }
            }
        });

        JLabel jlProcessCmd = new JLabel("Cmd : ");
        m_comboCmd = new JComboBox();
        m_comboCmd.setPreferredSize( new Dimension( 180, 25) );
//        m_comboCmd.setMaximumSize( m_comboCmd.getPreferredSize()  );
//        m_comboCmd.setSize( 20000, m_comboCmd.getHeight() );
//        m_comboCmd.addItem(ANDROID_THREAD_CMD);
//        m_comboCmd.addItem(ANDROID_DEFAULT_CMD);
//        m_comboCmd.addItem(ANDROID_RADIO_CMD);
//        m_comboCmd.addItem(ANDROID_EVENT_CMD);
//        m_comboCmd.addItem(ANDROID_CUSTOM_CMD);
//        m_comboCmd.addItemListener(new ItemListener()
//        {
//            public void itemStateChanged(ItemEvent e)
//            {
//                if(e.getStateChange() != ItemEvent.SELECTED) return;
//
//                if (e.getItem().equals(ANDROID_CUSTOM_CMD)) {
//                    m_comboCmd.setEditable(true);
//                } else {
//                    m_comboCmd.setEditable(false);
//                }
////                setProcessCmd(m_comboDeviceCmd.getSelectedIndex(), m_strSelectedDevice);
//            }
//        });

        m_btnClear = new JButton("Clear");
        m_btnClear.setMargin(new Insets(0, 0, 0, 0));
        m_btnClear.setEnabled(false);
        m_btnRun = new JButton("Run");
        m_btnRun.setMargin(new Insets(0, 0, 0, 0));

        m_tbtnPause = new JToggleButton("Pause");
        m_tbtnPause.setMargin(new Insets(0, 0, 0, 0));
        m_tbtnPause.setEnabled(false);
        m_btnStop = new JButton("Stop");
        m_btnStop.setMargin(new Insets(0, 0, 0, 0));
        m_btnStop.setEnabled(false);
        m_btnRun.addActionListener(m_alButtonListener);
        m_btnStop.addActionListener(m_alButtonListener);
        m_btnClear.addActionListener(m_alButtonListener);
        m_tbtnPause.addActionListener(m_alButtonListener);

        optionWest.add(jlFontType);
        optionWest.add(m_jcFontType);
        optionWest.add(jlFont);
        optionWest.add(m_tfFontSize);
        optionWest.add(m_btnSetFont);
        optionWest.add(jlEncode);
        optionWest.add(m_comboEncode);
        optionWest.add(jlGoto);
        optionWest.add(tfGoto);
        optionWest.add(jlProcessCmd);
        optionWest.add(m_comboCmd);
        optionWest.add(m_btnClear);
        optionWest.add(m_btnRun);
        optionWest.add(m_tbtnPause);
        optionWest.add(m_btnStop);

        optionMenu.add(optionWest, BorderLayout.WEST);
        return optionMenu;
    }

    Component getOptionPanel()
    {
        JPanel optionMain = new JPanel(new BorderLayout());

        optionMain.add(getOptionFilter(), BorderLayout.CENTER);
        optionMain.add(getOptionMenu(), BorderLayout.SOUTH);

        return optionMain;
    }

    Component getStatusPanel()
    {
        m_tfStatus = new JTextField("ready");
        m_tfStatus.setEditable(false);
        return m_tfStatus;
    }

    Component getTabPanel()
    {
        m_tpTab = new JTabbedPane();
        m_tmLogTableModel = new LogFilterTableModel();
        m_tmLogTableModel.setData(m_arLogInfoAll);
        m_tbLogTable = new LogTable(m_tmLogTableModel, this);
        m_iLogParser = new LogCatParser();
        m_tbLogTable.setLogParser(m_iLogParser);

        m_scrollVBar = new JScrollPane(m_tbLogTable);

        m_tpTab.addTab("Log", m_scrollVBar);

        return m_scrollVBar;
    }

    void initValue()
    {
        m_bPauseADB         = false;
        FILE_LOCK           = new Object();
        FILTER_LOCK         = new Object();
        m_nChangedFilter    = STATUS_READY;
        m_nFilterLogLV      = LogInfo.LOG_LV_ALL;

        m_arTagInfo         = new ArrayList<TagInfo>();
        m_arLogInfoAll      = new ArrayList<LogInfo>();
        m_arLogInfoFiltered = new ArrayList<LogInfo>();
        m_hmBookmarkAll     = new ConcurrentHashMap<Integer, Integer>();
        m_hmBookmarkFiltered= new ConcurrentHashMap<Integer, Integer>();
        m_hmErrorAll        = new ConcurrentHashMap<Integer, Integer>();
        m_hmErrorFiltered   = new ConcurrentHashMap<Integer, Integer>();

        m_strLogFileName = makeFilename();
//        m_strProcessCmd     = ANDROID_DEFAULT_CMD + m_strLogFileName;
    }

    // 파일 파싱 세대 번호. 새 파일을 열면 증가하고, 이전 파싱 스레드는 번호가 바뀐 것을 보고 멈춘다.
    final AtomicInteger m_nParseGeneration = new AtomicInteger();

    void parseFile(final File file)
    {
        if(file == null)
        {
            T.e("file == null");
            return;
        }

        setTitle(file.getPath());
        // Swing 컴포넌트 값은 호출한 스레드(보통 EDT)에서 미리 읽어 둔다.
        final boolean bUtf8 = "UTF-8".equals(m_comboEncode.getSelectedItem());
        final int nGeneration = m_nParseGeneration.incrementAndGet();
        new Thread(new Runnable()
        {
            public void run()
            {
                int nIndex = 1;
                try(BufferedReader br = new BufferedReader(bUtf8 ? new InputStreamReader(new FileInputStream(file), "UTF-8")
                                                                 : new InputStreamReader(new FileInputStream(file))))
                {
                    String strLine;

                    setStatus("Parsing");
                    clearData();
                    while ((strLine = br.readLine()) != null)
                    {
                        if(!"".equals(strLine.trim()))
                        {
                            LogInfo logInfo = m_iLogParser.parseLog(strLine);
                            logInfo.setLine(nIndex++);
                            // 세대 확인과 추가를 같은 락 안에서 해야, 새 파일의 clearData() 뒤에 이전 줄이 섞이지 않는다.
                            synchronized(FILTER_LOCK)
                            {
                                if(nGeneration != m_nParseGeneration.get())
                                    return;     // 그 사이 다른 파일을 열었음
                                addLogInfo(logInfo);
                            }
                        }
                    }
                    runFilter();
                    setStatus("Parse complete");
                } catch(Exception ioe) {
                    T.e(ioe);
                    setStatus("Parse error : " + ioe.getMessage());
                }
            }
        }, "ParseFile").start();
    }
    void pauseProcess()
    {
        if(m_tbtnPause.isSelected())
        {
            m_bPauseADB = true;
            m_tbtnPause.setText("Resume");
        }
        else
        {
            m_bPauseADB = false;
            m_tbtnPause.setText("Pause");
        }
    }

    void setBookmark(int nLine, String strBookmark)
    {
        ArrayList<LogInfo> arAll = m_arLogInfoAll;
        if(nLine < 0 || nLine >= arAll.size()) return;
        arAll.get(nLine).m_strBookmark = strBookmark;
    }

    // adb devices를 백그라운드에서 실행하고 결과를 EDT에서 목록에 넣는다. (실행 중 UI가 멈추지 않도록)
    void setDeviceList()
    {
        m_strSelectedDevice = "";
        final DefaultListModel listModel = (DefaultListModel)m_lDeviceList.getModel();
        listModel.clear();

        String strCommand = DEVICES_CMD[m_comboDeviceCmd.getSelectedIndex()];
        if(m_comboDeviceCmd.getSelectedIndex() == DEVICES_CUSTOM)
            strCommand = (String)m_comboDeviceCmd.getSelectedItem();
        final String strCmd = strCommand;

        m_btnDevice.setEnabled(false);
        setStatus("adb devices ...");
        new Thread(new Runnable()
        {
            public void run()
            {
                final ArrayList<Object> arItem = new ArrayList<Object>();
                try
                {
                    // stderr를 stdout에 합쳐 한 번에 읽는다. (둘을 순서대로 읽다 버퍼가 차서 멈추는 일 방지)
                    ProcessBuilder pb = new ProcessBuilder(strCmd.trim().split("\\s+"));
                    pb.redirectErrorStream(true);
                    Process oProcess = pb.start();
                    try(BufferedReader stdOut = new BufferedReader(new InputStreamReader(oProcess.getInputStream())))
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
                    System.out.println("Exit Code: " + oProcess.waitFor());
                }
                catch(Exception e)
                {
                    T.e("e = " + e);
                    arItem.add(e);
                }

                SwingUtilities.invokeLater(new Runnable()
                {
                    public void run()
                    {
                        for(Object item : arItem)
                            listModel.addElement(item);
                        m_btnDevice.setEnabled(true);
                        setStatus(arItem.isEmpty() ? "No device" : "ready");
                    }
                });
            }
        }, "AdbDevices").start();
    }
    public void setFindFocus()
    {
        m_tfFindWord.requestFocus();
    }

    void setDnDListener()
    {

        new DropTarget(this, DnDConstants.ACTION_COPY_OR_MOVE, new DropTargetListener()
        {
            public void dropActionChanged(DropTargetDragEvent dtde) {}
            public void dragOver(DropTargetDragEvent dtde)          {}
            public void dragExit(DropTargetEvent dte)               {}
            public void dragEnter(DropTargetDragEvent event)        {}

            public void drop(DropTargetDropEvent event)
            {
                try
                {
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = event.getTransferable();
                    List<?> list = (List<?>)(t.getTransferData(DataFlavor.javaFileListFlavor));
                    Iterator<?> i = list.iterator();
                    if(i.hasNext())
                    {
                        File file = (File)i.next();
                        setTitle(file.getPath());

                        stopProcess();
                        parseFile(file);
                    }
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        });
    }

    void setLogLV(int nLogLV, boolean bChecked)
    {
        if(bChecked)
            m_nFilterLogLV |= nLogLV;
        else
            m_nFilterLogLV &= ~nLogLV;
        m_nChangedFilter = STATUS_CHANGE;
        runFilter();
    }

    void useFilter(JCheckBox checkBox)
    {
        if(checkBox.equals(m_chkEnableFind))
            m_tbLogTable.setFilterFind(checkBox.isSelected() ? m_tfFindWord.getText() : "");
        if(checkBox.equals(m_chkEnableRemove))
            m_tbLogTable.SetFilterRemove(checkBox.isSelected() ? m_tfRemoveWord.getText() : "");
        if(checkBox.equals(m_chkEnableShowPid))
            m_tbLogTable.SetFilterShowPid(checkBox.isSelected() ? m_tfShowPid.getText() : "");
        if(checkBox.equals(m_chkEnableShowTid))
            m_tbLogTable.SetFilterShowTid(checkBox.isSelected() ? m_tfShowTid.getText() : "");
        if(checkBox.equals(m_chkEnableShowTag))
            m_tbLogTable.SetFilterShowTag(checkBox.isSelected() ? m_tfShowTag.getText() : "");
        if(checkBox.equals(m_chkEnableRemoveTag))
            m_tbLogTable.SetFilterRemoveTag(checkBox.isSelected() ? m_tfRemoveTag.getText() : "");
        if(checkBox.equals(m_chkEnableHighlight))
        {
            // 하이라이트는 표시만 바뀌므로 재필터 없이 다시 그린다.
            m_tbLogTable.SetHighlight(checkBox.isSelected() ? m_tfHighlight.getText() : "");
            m_tbLogTable.repaint();
            return;
        }
        m_nChangedFilter = STATUS_CHANGE;
        runFilter();
    }

    void setProcessBtn(boolean bStart)
    {
        if(bStart)
        {
            m_btnRun.setEnabled(false);
            m_btnStop.setEnabled(true);
            m_btnClear.setEnabled(true);
            m_tbtnPause.setEnabled(true);
        }
        else
        {
            m_btnRun.setEnabled(true);
            m_btnStop.setEnabled(false);
            m_btnClear.setEnabled(false);
            m_tbtnPause.setEnabled(false);
            m_tbtnPause.setSelected(false);
            m_tbtnPause.setText("Pause");
        }
    }

    String getProcessCmd()
    {
        if(m_lDeviceList.getSelectedIndex() < 0)
            return ANDROID_DEFAULT_CMD_FIRST + m_comboCmd.getSelectedItem();
//            return ANDROID_DEFAULT_CMD_FIRST + m_comboCmd.getSelectedItem() + makeFilename();
        else
            return ANDROID_SELECTED_CMD_FIRST + m_strSelectedDevice + m_comboCmd.getSelectedItem();
    }

    void setProcessCmd(int nType, String strSelectedDevice)
    {
//        m_comboCmd.removeAllItems();

        m_strLogFileName = makeFilename();
//        if(strSelectedDevice != null)
//        {
//            strSelectedDevice = strSelectedDevice.replace("\t", " ").replace("device", "").replace("offline", "");
//            T.d("strSelectedDevice = " + strSelectedDevice);
//        }

        if(nType == DEVICES_ANDROID)
        {
            if(strSelectedDevice != null && strSelectedDevice.length() > 0)
            {
//                m_comboCmd.addItem(ANDROID_SELECTED_CMD_FIRST + strSelectedDevice + ANDROID_SELECTED_CMD_LAST);
//                m_strProcessCmd = ANDROID_SELECTED_CMD_FIRST + strSelectedDevice + ANDROID_SELECTED_CMD_LAST;
            }
            else
            {
//                m_comboCmd.addItem(ANDROID_DEFAULT_CMD);
//                m_strProcessCmd = ANDROID_DEFAULT_CMD;
            }
        }
        else if(nType == DEVICES_IOS)
        {
            if(strSelectedDevice != null && strSelectedDevice.length() > 0)
            {
//                m_comboCmd.addItem(ANDROID_SELECTED_CMD_FIRST + strSelectedDevice + ANDROID_SELECTED_CMD_LAST);
//                m_strProcessCmd = IOS_SELECTED_CMD_FIRST + strSelectedDevice + IOS_SELECTED_CMD_LAST;
            }
          else
          {
//              m_comboCmd.addItem(IOS_DEFAULT_CMD);
//              m_strProcessCmd = IOS_DEFAULT_CMD;
          }
        }
        else
        {
//            m_comboCmd.addItem(ANDROID_DEFAULT_CMD);
        }
    }

    void setStatus(final String strText)
    {
        runOnEdt(new Runnable()
        {
            public void run()
            {
                m_tfStatus.setText(strText);
            }
        });
    }

    public void setTitle(final String strTitle)
    {
        runOnEdt(new Runnable()
        {
            public void run()
            {
                LogFilterMain.super.setTitle(strTitle);
            }
        });
    }

    void stopProcess()
    {
        runOnEdt(new Runnable()
        {
            public void run()
            {
                setProcessBtn(false);
            }
        });
        Process process = m_Process;
        Thread  thProcess = m_thProcess, thWatchFile = m_thWatchFile;
        m_Process = null;
        m_thProcess = null;
        m_thWatchFile = null;
        m_bPauseADB = false;
        if(process != null) process.destroy();
        if(thProcess != null) thProcess.interrupt();
        if(thWatchFile != null) thWatchFile.interrupt();
    }

    // adb 출력이 기록되는 파일을 50ms마다 이어 읽어 화면에 반영한다.
    void startFileParse(final String strFile, final boolean bUtf8)
    {
        m_thWatchFile = new Thread(new Runnable()
        {
            public void run()
            {
                setTitle(strFile);
                try(BufferedReader br = new BufferedReader(bUtf8 ? new InputStreamReader(new FileInputStream(strFile), "UTF-8")
                                                                 : new InputStreamReader(new FileInputStream(strFile))))
                {
                    String strLine;
                    while(true)
                    {
                        Thread.sleep(50);

                        if(m_nChangedFilter == STATUS_CHANGE || m_nChangedFilter == STATUS_PARSING)
                            continue;
                        if(m_bPauseADB) continue;

                        int nAddCount = 0;
                        synchronized(FILE_LOCK)
                        {
                            while (!m_bPauseADB && (strLine = br.readLine()) != null)
                            {
                                if(!"".equals(strLine.trim()))
                                {
                                    LogInfo logInfo = m_iLogParser.parseLog(strLine);
                                    synchronized(FILTER_LOCK)
                                    {
                                        // 줄 번호는 추가 직전에 정한다. (Clear로 목록이 바뀌어도 번호와 위치가 맞도록)
                                        logInfo.setLine(m_arLogInfoAll.size() + 1);
                                        addLogInfo(logInfo);
                                    }
                                    nAddCount++;
                                }
                            }
                        }
                        if(nAddCount > 0)
                            refreshTable(REFRESH_FOLLOW_END);
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
                System.out.println("End m_thWatchFile thread");
            }
        }, "WatchFile");
        m_thWatchFile.start();
    }

    // 재필터 요청 표시. notify()가 필터 스레드의 wait() 직전에 와도 요청을 잃지 않도록 플래그로 남긴다.
    boolean m_bFilterRequested;

    void runFilter()
    {
        checkUseFilter();
        while(m_nChangedFilter == STATUS_PARSING)
            try
            {
                Thread.sleep(100);
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        synchronized(FILTER_LOCK)
        {
            m_bFilterRequested = true;
            FILTER_LOCK.notify();
        }
    }

    void startFilterParse()
    {
        m_thFilterParse = new Thread(new Runnable()
        {
            public void run()
            {
                try {
                    while(true)
                    {
                        synchronized(FILTER_LOCK)
                        {
                            m_nChangedFilter = STATUS_READY;
                            while(!m_bFilterRequested)
                                FILTER_LOCK.wait();
                            m_bFilterRequested = false;

                            m_nChangedFilter = STATUS_PARSING;

                            if(m_bUserFilter == false)
                            {
                                m_nChangedFilter = STATUS_READY;
                                refreshTable(REFRESH_SELECT_LAST);
                                continue;
                            }
                            setStatus("Parsing");

                            // 새 리스트에 채운다. 완료될 때까지 화면은 이전 결과를 그대로 보여준다.
                            ArrayList<LogInfo>    arAll      = m_arLogInfoAll;
                            ArrayList<LogInfo>    arFiltered = new ArrayList<LogInfo>();
                            Map<Integer, Integer> hmBookmark = new ConcurrentHashMap<Integer, Integer>();
                            Map<Integer, Integer> hmError    = new ConcurrentHashMap<Integer, Integer>();

                            int nRowCount = arAll.size();
                            for(int nIndex = 0; nIndex < nRowCount; nIndex++)
                            {
                                if(m_nChangedFilter == STATUS_CHANGE)
                                    break;
                                addIfAccepted(arAll.get(nIndex), arFiltered, hmBookmark, hmError);
                            }
                            if(m_nChangedFilter == STATUS_PARSING)
                            {
                                m_arLogInfoFiltered  = arFiltered;
                                m_hmBookmarkFiltered = hmBookmark;
                                m_hmErrorFiltered    = hmError;
                                m_nChangedFilter = STATUS_READY;
                                refreshTable(REFRESH_SELECT_LAST);
                                setStatus("Complete");
                            }
                        }
                    }
                } catch(InterruptedException e) {
                    // 종료
                } catch(Exception e) {
                    T.e(e);
                    e.printStackTrace();
                }
                System.out.println("End m_thFilterParse thread");
            }
        }, "FilterParse");
        m_thFilterParse.start();
    }

    void startProcess()
    {
        clearData();
        // Swing 값은 EDT에서 미리 읽어 둔다.
        final boolean bUtf8   = "UTF-8".equals(m_comboEncode.getSelectedItem());
        final String  strCmd  = getProcessCmd();
        m_strLogFileName      = makeFilename();
        final String  strFile = m_strLogFileName;

        m_thProcess = new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    T.d("getProcessCmd() = " + strCmd);
                    // stderr도 함께 받아 기록한다. (adb 오류 메시지 확인 + stderr 버퍼가 차서 멈추는 일 방지)
                    ProcessBuilder pb = new ProcessBuilder(strCmd.trim().split("\\s+"));
                    pb.redirectErrorStream(true);
                    Process process = pb.start();
                    m_Process = process;

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
                    setStatus("adb error : " + e.getMessage());
                }
                // 그 사이 Stop 후 다시 Run 했다면 새 프로세스를 건드리지 않는다.
                if(m_thProcess == Thread.currentThread())
                    stopProcess();
            }
        }, "AdbProcess");
        m_thProcess.start();
        setProcessBtn(true);
    }
    // 레벨은 파싱할 때 m_nLogLV(비트)로 계산해 두었으므로 비트 연산으로 판정한다.
    // 레벨이 없는 줄(LOG_LV_NONE)은 모든 레벨이 선택된 경우에만 보인다.
    boolean checkLogLVFilter(LogInfo logInfo)
    {
        if(m_nFilterLogLV == LogInfo.LOG_LV_ALL)
            return true;
        return (m_nFilterLogLV & logInfo.m_nLogLV) != 0;
    }

    // Show 계열: 토큰이 없으면 통과, 있으면 하나라도 포함돼야 통과
    static boolean matchShow(String strField, String[] arToken)
    {
        return arToken.length == 0 || FilterToken.matchAny(strField, arToken);
    }

    // Remove 계열: 토큰이 없으면 통과, 하나라도 포함되면 제외
    static boolean matchRemove(String strField, String[] arToken)
    {
        return arToken.length == 0 || !FilterToken.matchAny(strField, arToken);
    }

    boolean checkPidFilter(LogInfo logInfo)
    {
        return matchShow(logInfo.m_strPid, m_tbLogTable.GetPidShowTokens());
    }

    boolean checkTidFilter(LogInfo logInfo)
    {
        return matchShow(logInfo.m_strThread, m_tbLogTable.GetTidShowTokens());
    }

    boolean checkFindFilter(LogInfo logInfo)
    {
        return matchShow(logInfo.m_strMessage, m_tbLogTable.GetFindTokens());
    }

    boolean checkRemoveFilter(LogInfo logInfo)
    {
        return matchRemove(logInfo.m_strMessage, m_tbLogTable.GetRemoveTokens());
    }

    boolean checkShowTagFilter(LogInfo logInfo)
    {
        return matchShow(logInfo.m_strTag, m_tbLogTable.GetTagShowTokens());
    }

    boolean checkRemoveTagFilter(LogInfo logInfo)
    {
        return matchRemove(logInfo.m_strTag, m_tbLogTable.GetTagRemoveTokens());
    }

    boolean checkUseFilter()
    {
        if(!m_bShowBookmarkOnly
            && !m_bShowErrorOnly
            && checkLogLVFilter(new LogInfo())
            && (m_tbLogTable.GetFilterShowPid().length() == 0   || !m_chkEnableShowPid.isSelected())
            && (m_tbLogTable.GetFilterShowTid().length() == 0   || !m_chkEnableShowTid.isSelected())
            && (m_tbLogTable.GetFilterShowTag().length() == 0   || !m_chkEnableShowTag.isSelected())
            && (m_tbLogTable.GetFilterRemoveTag().length() == 0 || !m_chkEnableRemoveTag.isSelected())
            && (m_tbLogTable.GetFilterFind().length() == 0      || !m_chkEnableFind.isSelected())
            && (m_tbLogTable.GetFilterRemove().length() == 0    || !m_chkEnableRemove.isSelected()))
        {
            m_bUserFilter = false;
        }
        else m_bUserFilter = true;
        return m_bUserFilter;
    }

    ActionListener m_alButtonListener = new ActionListener()
    {
        public void actionPerformed(ActionEvent e)
        {
            if(e.getSource().equals(m_btnDevice))
                setDeviceList();
            else if(e.getSource().equals(m_btnSetFont))
            {
                m_tbLogTable.setFontSize(fontSizeOf(m_tfFontSize.getText()));
                m_tbLogTable.repaint();
            }
            else if(e.getSource().equals(m_btnRun))
            {
                startProcess();
            }
            else if(e.getSource().equals(m_btnStop))
            {
                stopProcess();
            }
            else if(e.getSource().equals(m_btnClear))
            {
                boolean bBackup = m_bPauseADB;
                m_bPauseADB = true;
                clearData();
                m_bPauseADB = bBackup;
            }
            else if(e.getSource().equals(m_tbtnPause))
                pauseProcess();
            else if(e.getSource().equals(m_jcFontType))
            {
                T.d("font = " + m_tbLogTable.getFont());
                
                m_tbLogTable.setFont(new Font((String)m_jcFontType.getSelectedItem(), Font.PLAIN, 12));
                m_tbLogTable.setFontSize(fontSizeOf(m_tfFontSize.getText()));
            }
        }
    };

    // 글꼴 크기 입력값. 숫자가 아니거나 범위를 벗어나면 기본 12로 (잘못 입력해도 예외로 멈추지 않도록)
    static int fontSizeOf(String strText)
    {
        try
        {
            int nSize = Integer.parseInt(strText.trim());
            return nSize >= 6 && nSize <= 72 ? nSize : 12;
        }
        catch(NumberFormatException e)
        {
            return 12;
        }
    }

    public void notiEvent(EventParam param)
    {
        switch(param.nEventId)
        {
            case EVENT_CLICK_BOOKMARK:
            case EVENT_CLICK_ERROR:
                m_bShowBookmarkOnly = m_ipIndicator.m_chBookmark.isSelected();
                m_bShowErrorOnly    = m_ipIndicator.m_chError.isSelected();
                m_nChangedFilter = STATUS_CHANGE;
                runFilter();
                break;
            case EVENT_CHANGE_FILTER_SHOW_TAG:
                m_tfShowTag.setText(m_tbLogTable.GetFilterShowTag());
                break;
            case EVENT_CHANGE_FILTER_REMOVE_TAG:
                m_tfRemoveTag.setText(m_tbLogTable.GetFilterRemoveTag());
                break;
        }
    }

    // 필터 입력이 멈춘 뒤 이 시간(ms)이 지나면 재필터한다. (키를 누를 때마다 전체 재필터하지 않도록)
    static final int FILTER_DEBOUNCE_MS = 250;

    final javax.swing.Timer m_tmFilterDebounce = createFilterDebounceTimer();

    javax.swing.Timer createFilterDebounceTimer()
    {
        javax.swing.Timer timer = new javax.swing.Timer(FILTER_DEBOUNCE_MS, new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                runFilter();
            }
        });
        timer.setRepeats(false);
        return timer;
    }

    // 필터/하이라이트 입력창 내용이 바뀌면 호출된다.
    void onFilterTextChanged(DocumentEvent event)
    {
        try
        {
            javax.swing.text.Document doc = event.getDocument();
            String strText = doc.getText(0, doc.getLength());

            // 하이라이트는 표시만 바뀌므로 재필터 없이 다시 그린다.
            if(doc.equals(m_tfHighlight.getDocument()))
            {
                if(m_chkEnableHighlight.isSelected())
                {
                    m_tbLogTable.SetHighlight(strText);
                    m_tbLogTable.repaint();
                }
                return;
            }

            if(doc.equals(m_tfFindWord.getDocument()) && m_chkEnableFind.isSelected())
                m_tbLogTable.setFilterFind(strText);
            else if(doc.equals(m_tfRemoveWord.getDocument()) && m_chkEnableRemove.isSelected())
                m_tbLogTable.SetFilterRemove(strText);
            else if(doc.equals(m_tfShowPid.getDocument()) && m_chkEnableShowPid.isSelected())
                m_tbLogTable.SetFilterShowPid(strText);
            else if(doc.equals(m_tfShowTid.getDocument()) && m_chkEnableShowTid.isSelected())
                m_tbLogTable.SetFilterShowTid(strText);
            else if(doc.equals(m_tfShowTag.getDocument()) && m_chkEnableShowTag.isSelected())
                m_tbLogTable.SetFilterShowTag(strText);
            else if(doc.equals(m_tfRemoveTag.getDocument()) && m_chkEnableRemoveTag.isSelected())
                m_tbLogTable.SetFilterRemoveTag(strText);

            // 진행 중인 재필터는 바로 중단시키고, 새 재필터는 입력이 멈춘 뒤 시작한다.
            m_nChangedFilter = STATUS_CHANGE;
            m_tmFilterDebounce.restart();
        }
        catch(Exception e)
        {
            T.e(e);
        }
    }

    DocumentListener m_dlFilterListener = new DocumentListener()
    {
        public void changedUpdate(DocumentEvent arg0) { onFilterTextChanged(arg0); }
        public void insertUpdate(DocumentEvent arg0)  { onFilterTextChanged(arg0); }
        public void removeUpdate(DocumentEvent arg0)  { onFilterTextChanged(arg0); }
    };

    ItemListener m_itemListener = new ItemListener() {
        public void itemStateChanged(ItemEvent itemEvent) {
            JCheckBox check = (JCheckBox)itemEvent.getSource();

            if(check.equals(m_chkVerbose))
                setLogLV(LogInfo.LOG_LV_VERBOSE, check.isSelected());
            else if(check.equals(m_chkDebug))
                setLogLV(LogInfo.LOG_LV_DEBUG, check.isSelected());
            else if(check.equals(m_chkInfo))
                setLogLV(LogInfo.LOG_LV_INFO, check.isSelected());
            else if(check.equals(m_chkWarn))
                setLogLV(LogInfo.LOG_LV_WARN, check.isSelected());
            else if(check.equals(m_chkError))
                setLogLV(LogInfo.LOG_LV_ERROR, check.isSelected());
            else if(check.equals(m_chkFatal))
                setLogLV(LogInfo.LOG_LV_FATAL, check.isSelected());
            else if(check.equals(m_chkClmBookmark))
                m_tbLogTable.showColumn(LogFilterTableModel.COMUMN_BOOKMARK, check.isSelected());
            else if(check.equals(m_chkClmLine))
                m_tbLogTable.showColumn(LogFilterTableModel.COMUMN_LINE, check.isSelected());
            else if(check.equals(m_chkClmDate))
                m_tbLogTable.showColumn(LogFilterTableModel.COMUMN_DATE, check.isSelected());
            else if(check.equals(m_chkClmTime))
                m_tbLogTable.showColumn(LogFilterTableModel.COMUMN_TIME, check.isSelected());
            else if(check.equals(m_chkClmLogLV))
                m_tbLogTable.showColumn(LogFilterTableModel.COMUMN_LOGLV, check.isSelected());
            else if(check.equals(m_chkClmPid))
                m_tbLogTable.showColumn(LogFilterTableModel.COMUMN_PID, check.isSelected());
            else if(check.equals(m_chkClmThread))
                m_tbLogTable.showColumn(LogFilterTableModel.COMUMN_THREAD, check.isSelected());
            else if(check.equals(m_chkClmTag))
                m_tbLogTable.showColumn(LogFilterTableModel.COMUMN_TAG, check.isSelected());
            else if(check.equals(m_chkClmMessage))
                m_tbLogTable.showColumn(LogFilterTableModel.COMUMN_MESSAGE, check.isSelected());
            else if(check.equals(m_chkEnableFind)
                    || check.equals(m_chkEnableRemove)
                    || check.equals(m_chkEnableShowPid)
                    || check.equals(m_chkEnableShowTid)
                    || check.equals(m_chkEnableShowTag)
                    || check.equals(m_chkEnableRemoveTag)
                    || check.equals(m_chkEnableHighlight))
                useFilter(check);
        }
    };
    
    public void openFileBrowser()
    {
        FileDialog fd = new FileDialog(this, "File open", FileDialog.LOAD); 
//        fd.setDirectory( m_strLastDir );
        fd.setVisible( true );
        if (fd.getFile() != null)
        {
            parseFile(new File(fd.getDirectory() + fd.getFile()));
            m_recentMenu.addEntry( fd.getDirectory() + fd.getFile() );
        }

        //In response to a button click:
//        final JFileChooser fc = new JFileChooser(m_strLastDir);
//        int returnVal = fc.showOpenDialog(this);
//        if (returnVal == JFileChooser.APPROVE_OPTION)
//        {
//            File file = fc.getSelectedFile();
//            m_strLastDir = fc.getCurrentDirectory().getAbsolutePath();
//            T.d("file = " + file.getAbsolutePath());
//            parseFile(file);
//            m_recentMenu.addEntry( file.getAbsolutePath() );
//        }
    }
}

