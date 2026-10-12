import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
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
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
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
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.UndoableEditEvent;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

/**
 * 메인 프레임: 화면 구성과 이벤트 연결만 맡는다.
 * - 로그 데이터·필터 조건·재필터 스레드 : FilterEngine
 * - 파일 열기·adb 실시간 수집·장치 목록 : LogSource
 * - 설정 파일(*.ini)                    : AppConfig
 * 위 객체들이 보내는 알림(Listener)은 어느 스레드에서든 올 수 있으므로, 화면 변경은 runOnEdt()로 EDT에서 한다.
 */
public class LogFilterMain extends JFrame implements INotiEvent, FilterEngine.Listener, LogSource.Listener
{
    private static final long serialVersionUID           = 1L;

    static final String       LOGFILTER                  = "LogFilter";
    // 버전 규칙(큰버전.중간버전.날짜)은 AppVersion 참고. 예: "Version 1.9.20261006153000"
    static final String       VERSION                    = "Version " + AppVersion.full();
    static final String       COMBO_ANDROID              = "Android          ";
    static final String       ADB_CMD_FIRST              = "adb ";
    static final String       ADB_SELECTED_CMD_FIRST     = "adb -s ";

    final FilterEngine        m_engine;
    final ILogParser          m_iLogParser;
    final LogSource           m_source;
    AppConfig                 m_config;

    JTextField                m_tfStatus;
    IndicatorPanel            m_ipIndicator;
    LogTable                  m_tbLogTable;
    JScrollPane               m_scrollVBar;
    LogFilterTableModel       m_tmLogTableModel;

    //Word Filter, tag filter
    // 하이라이트 입력창 6개와 각각의 사용 체크박스·색상 선택
    final JTextField[]        m_arTfHighlight      = new JTextField[LogColor.HIGHLIGHT_COUNT];
    final JCheckBox[]         m_arChkHighlight     = new JCheckBox[LogColor.HIGHLIGHT_COUNT];
    @SuppressWarnings("unchecked")
    final JComboBox<Integer>[] m_arCbHighlightColor = new JComboBox[LogColor.HIGHLIGHT_COUNT];
    JTextField                m_tfFindWord;
    JTextField                m_tfRemoveWord;
    JTextField                m_tfShowTag;
    JTextField                m_tfRemoveTag;
    JTextField                m_tfShowPid;
    JTextField                m_tfShowTid;
    JTextField                m_tfTimeFrom;         // 시간 범위 필터 시작·끝 (1.18)
    JTextField                m_tfTimeTo;

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
    JCheckBox                 m_chkEnableTime;
    JToggleButton             m_tbFindRegex;        // Find·Remove 정규식 모드 (1.19)
    JToggleButton             m_tbRemoveRegex;

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
    JComboBox                 m_comboEncode;
    JComboBox                 m_jcFontType;
    JButton                   m_btnRun;
    JButton                   m_btnClear;
    JToggleButton             m_tbtnPause;
    JButton                   m_btnStop;

    String                    m_strSelectedDevice;
    int                       m_nLastWidth;
    int                       m_nLastHeight;
    static RecentFileMenu     m_recentMenu;

    public static void main(final String args[])
    {
        final LogFilterMain mainFrame = new LogFilterMain();
        mainFrame.setTitle(LOGFILTER + " " + VERSION);

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

        // 강제 종료: 백그라운드 작업(필터·검색·adb)을 기다리지 않고 바로 끝낸다.
        JMenuItem fileForceExit = new JMenuItem("강제 종료");
        fileForceExit.setMnemonic(KeyEvent.VK_X);
        fileForceExit.setToolTipText("adb 수집을 멈추고, 진행 중인 작업을 기다리지 않고 바로 종료");
        fileForceExit.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                mainFrame.forceExit(true);
            }
        });

        // 수집 중 장치 연결이 끊기면(재부팅 등) 다시 연결을 기다려 이어서 수집하고 재부팅을 표시 (1.24)
        mainFrame.m_miAutoReconnect = new javax.swing.JCheckBoxMenuItem("장치 연결이 끊기면 자동 다시 연결 (재부팅 표시)");
        mainFrame.m_miAutoReconnect.setSelected(mainFrame.m_source.m_bAutoReconnect);
        mainFrame.m_miAutoReconnect.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) { mainFrame.m_source.m_bAutoReconnect = mainFrame.m_miAutoReconnect.isSelected(); }
        });

        file.add(fileOpen);
        file.add(m_recentMenu);
        file.addSeparator();
        file.add(mainFrame.m_miAutoReconnect);
        file.addSeparator();
        file.add(fileForceExit);

        menubar.add(file);

        // View: Full(필터·설정 화면까지) / Compact(로그 화면만), F12로 전환
        JMenu view = new JMenu("View");
        view.setMnemonic(KeyEvent.VK_V);
        javax.swing.ButtonGroup groupView = new javax.swing.ButtonGroup();
        mainFrame.m_miFullView    = new javax.swing.JRadioButtonMenuItem("Full view (필터·설정 표시)");
        mainFrame.m_miCompactView = new javax.swing.JRadioButtonMenuItem("Compact view (로그 화면만)");
        groupView.add(mainFrame.m_miFullView);
        groupView.add(mainFrame.m_miCompactView);
        mainFrame.m_miFullView.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) { mainFrame.setCompactView(false); }
        });
        mainFrame.m_miCompactView.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) { mainFrame.setCompactView(true); }
        });
        // F12는 창 전체 키(installSearchKeys)로 처리한다. 메뉴 단축키로도 두면 한 번에 두 번 전환되므로 이름에만 표시.
        JMenuItem viewToggle = new JMenuItem("Full / Compact 전환    F12");
        viewToggle.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) { mainFrame.toggleView(); }
        });
        view.add(mainFrame.m_miFullView);
        view.add(mainFrame.m_miCompactView);
        view.addSeparator();
        view.add(viewToggle);
        mainFrame.syncViewMenu();
        view.addSeparator();
        mainFrame.m_miDeviceStats = new javax.swing.JCheckBoxMenuItem("장치 통계 표시 (CPU·메모리)");
        mainFrame.m_miDeviceStats.setSelected(mainFrame.m_bDeviceStats);
        mainFrame.m_miDeviceStats.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) { mainFrame.setDeviceStats(mainFrame.m_miDeviceStats.isSelected()); }
        });
        JMenuItem viewStats = new JMenuItem("장치 통계 자세히…");
        viewStats.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) { mainFrame.showStatsDialog(); }
        });
        // 측정 주기 (1.23)
        JMenu viewInterval = new JMenu("장치 통계 주기");
        javax.swing.ButtonGroup groupInterval = new javax.swing.ButtonGroup();
        for(final int nSec : DeviceStats.INTERVALS_SEC)
        {
            final javax.swing.JRadioButtonMenuItem mi = new javax.swing.JRadioButtonMenuItem(nSec + "초마다");
            mi.setSelected(mainFrame.m_deviceStats.interval() == nSec * 1000L);
            mi.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent event) { mainFrame.setStatsInterval(nSec); }
            });
            groupInterval.add(mi);
            viewInterval.add(mi);
            mainFrame.m_arMiInterval.add(mi);
        }
        view.add(mainFrame.m_miDeviceStats);
        view.add(viewInterval);
        view.add(viewStats);
        menubar.add(view);
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

    // ---- 화면 모드 (1.20) ----
    Component                           m_optionPanel;      // 위쪽 필터·설정 영역 (Compact에서 숨김)
    boolean                             m_bCompactView;
    javax.swing.JRadioButtonMenuItem    m_miFullView;       // View 메뉴 (main()에서 만듦)
    javax.swing.JRadioButtonMenuItem    m_miCompactView;

    // Compact: 로그 화면만(위쪽 필터·설정 영역을 숨김). Full: 기존 화면. 필터 조건은 그대로 적용된다.
    void setCompactView(boolean bCompact)
    {
        m_bCompactView = bCompact;
        if(m_optionPanel != null)
            m_optionPanel.setVisible(!bCompact);
        syncViewMenu();
        getContentPane().revalidate();
        getContentPane().repaint();
        // 숨겨진 입력창에 포커스가 남지 않도록 로그 테이블로
        if(bCompact && m_tbLogTable != null)
            m_tbLogTable.requestFocusInWindow();
        // Compact에서는 필터 입력이 안 보이므로 필터가 걸려 있으면 알려 준다.
        setStatus(bCompact ? "Compact view" + (m_engine.m_bUserFilter ? " — 필터 적용 중" : "") + " (F12: Full view)" : "Full view (F12: Compact view)");
    }

    void toggleView()
    {
        setCompactView(!m_bCompactView);
    }

    void syncViewMenu()
    {
        if(m_miFullView != null)    m_miFullView.setSelected(!m_bCompactView);
        if(m_miCompactView != null) m_miCompactView.setSelected(m_bCompactView);
    }

    void exit()
    {
        m_source.stopProcess();
        m_engine.stop();

        saveConfig();
        AppConfig.saveColors();
        System.exit(0);
    }

    static final long FORCE_EXIT_FLUSH_MS = 1000;   // 강제 종료 때 adb 기록 파일을 마무리할 최대 시간

    /**
     * 강제 종료: adb 프로세스를 끝내고(기록 파일은 최대 1초 마무리), 설정은 저장을 시도한 뒤,
     * 필터·검색 같은 백그라운드 작업이 멈춰 있어도 기다리지 않고 JVM을 바로 끝낸다(halt).
     */
    void forceExit(boolean bConfirm)
    {
        if(bConfirm)
        {
            int nAnswer = javax.swing.JOptionPane.showConfirmDialog(this,
                "진행 중인 작업을 기다리지 않고 바로 종료합니다.\n(adb 수집 중이면 멈추고, 기록 파일은 그때까지 저장됩니다)",
                "강제 종료", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
            if(nAnswer != javax.swing.JOptionPane.OK_OPTION)
                return;
        }
        Thread thCapture = m_source.m_thProcess;
        m_source.stopProcess();
        if(thCapture != null)
        {
            try
            {
                thCapture.join(FORCE_EXIT_FLUSH_MS);
            }
            catch(InterruptedException e)
            {
                // 그대로 종료
            }
        }
        try
        {
            saveConfig();
            AppConfig.saveColors();
        }
        catch(Throwable t)
        {
            T.e(t);     // 저장에 실패해도 종료는 한다
        }
        Runtime.getRuntime().halt(0);
    }

    public LogFilterMain()
    {
        super();
        m_engine     = new FilterEngine(this);
        m_iLogParser = new LogCatParser();
        m_source     = new LogSource(m_engine, m_iLogParser, this);

        addWindowListener(new WindowAdapter()
        {
            public void windowClosing(WindowEvent e)
            {
                exit();
            }
        });

        Container pane = getContentPane();
        pane.setLayout(new BorderLayout());

        m_optionPanel = getOptionPanel();
        pane.add(m_optionPanel, BorderLayout.NORTH);
        pane.add(getBookmarkPanel(), BorderLayout.WEST);
        pane.add(getStatusPanel(), BorderLayout.SOUTH);
        pane.add(getTabPanel(), BorderLayout.CENTER);

        setDnDListener();
        addChangeListener();
        installSearchKeys();
        initDeviceStats();
        m_engine.start();

        setVisible(true);
        addDesc();
        refreshTable(FilterEngine.REFRESH_KEEP);     // 안내 문구 행을 테이블에 반영

        m_config = AppConfig.load();
        applyConfig(m_config);
        AppConfig.loadColors();
        for(String strCmd : AppConfig.loadCmds())
            m_comboCmd.addItem(strCmd);
        m_tbLogTable.setColumnWidth();

        setSize(m_config.nWinWidth, m_config.nWinHeight);
        setExtendedState(m_config.nWindowState);
        setMinimumSize(new Dimension(AppConfig.MIN_WIDTH, AppConfig.MIN_HEIGHT));
    }

    // ---- 설정 (AppConfig ↔ 화면) ----

    void applyConfig(AppConfig config)
    {
        if(config.strFontType.length() > 0)
            m_jcFontType.setSelectedItem(config.strFontType);
        m_tfFindWord.setText(config.strFind);
        m_tfRemoveWord.setText(config.strRemove);
        m_tfShowTag.setText(config.strShowTag);
        m_tfRemoveTag.setText(config.strRemoveTag);
        m_tfShowPid.setText(config.strShowPid);
        m_tfShowTid.setText(config.strShowTid);
        m_tbFindRegex.setSelected(config.bFindRegex);
        m_tbRemoveRegex.setSelected(config.bRemoveRegex);
        applyRegexMode();
        if(config.bCompactView)
            setCompactView(true);
        m_deviceStats.setInterval(config.nStatsIntervalSec * 1000L);
        setDeviceStats(config.bDeviceStats);
        m_source.m_bAutoReconnect = config.bAutoReconnect;
        m_tfTimeFrom.setText(config.strTimeFrom);
        m_tfTimeTo.setText(config.strTimeTo);
        for(int nIndex = 0; nIndex < LogColor.HIGHLIGHT_COUNT; nIndex++)
        {
            m_arTfHighlight[nIndex].setText(config.arHighlight[nIndex]);
            m_arCbHighlightColor[nIndex].setSelectedIndex(config.arHighlightColor[nIndex]);
            m_arChkHighlight[nIndex].setSelected(config.arHighlightOn[nIndex]);
            updateHighlight(nIndex);
        }
        for(int nIndex = 0; nIndex < LogFilterTableModel.COMUMN_MAX; nIndex++)
            LogFilterTableModel.setColumnWidth(nIndex, config.arColumnWidth[nIndex]);
        // 입력창별 최근 입력 기록 (이전 실행에서 저장한 것)
        for(InputHistory history : m_mapHistory.values())
            history.setItems(config.mapHistory.get(history.m_strKey));
    }

    void saveConfig()
    {
        // 창 크기는 최대화가 아닐 때 마지막으로 기록된 값을 쓴다. (한 번도 기록되지 않았으면 기존 값 유지)
        if(m_nLastWidth > 0 && m_nLastHeight > 0)
        {
            m_config.nWinWidth  = m_nLastWidth;
            m_config.nWinHeight = m_nLastHeight;
        }
        m_config.nWindowState = getExtendedState();
        m_config.strFontType  = (String)m_jcFontType.getSelectedItem();
        m_config.strFind      = m_tfFindWord.getText();
        m_config.strRemove    = m_tfRemoveWord.getText();
        m_config.strShowTag   = m_tfShowTag.getText();
        m_config.strRemoveTag = m_tfRemoveTag.getText();
        m_config.strShowPid   = m_tfShowPid.getText();
        m_config.strShowTid   = m_tfShowTid.getText();
        m_config.bFindRegex   = m_tbFindRegex.isSelected();
        m_config.bRemoveRegex = m_tbRemoveRegex.isSelected();
        m_config.bCompactView = m_bCompactView;
        m_config.bDeviceStats = m_bDeviceStats;
        m_config.bAutoReconnect = m_source.m_bAutoReconnect;
        m_config.nStatsIntervalSec = (int)(m_deviceStats.interval() / 1000);
        m_config.strTimeFrom  = m_tfTimeFrom.getText();
        m_config.strTimeTo    = m_tfTimeTo.getText();
        for(int nIndex = 0; nIndex < LogColor.HIGHLIGHT_COUNT; nIndex++)
        {
            m_config.arHighlight[nIndex]      = m_arTfHighlight[nIndex].getText();
            m_config.arHighlightColor[nIndex] = m_arCbHighlightColor[nIndex].getSelectedIndex();
            m_config.arHighlightOn[nIndex]    = m_arChkHighlight[nIndex].isSelected();
        }
        for(int nIndex = 0; nIndex < LogFilterTableModel.COMUMN_MAX; nIndex++)
            m_config.arColumnWidth[nIndex] = m_tbLogTable.getColumnWidth(nIndex);
        // 최근 입력 기록 (지금 입력창에 있는 값도 맨 앞에 넣어 저장)
        m_config.mapHistory.clear();
        for(InputHistory history : m_mapHistory.values())
        {
            history.commit();
            m_config.mapHistory.put(history.m_strKey, history.items());
        }
        m_config.save();
    }
    // 시작 안내 문구를 담는 메모리 목록
    MemoryLogStore m_descStore;

    void addDesc(String strMessage)
    {
        if(m_descStore == null)
        {
            m_descStore = new MemoryLogStore(m_iLogParser);
            m_engine.setStore(m_descStore);
        }
        m_descStore.addMessage(strMessage);
    }

    void addDesc()
    {
        addDesc(VERSION);
        addDesc("");
        addDesc("Version 1.25 : 장치 통계를 이미지로 복사·저장 (자세히 창의 버튼, Ctrl+C)");
        addDesc("Version 1.24 : 수집 중 장치 재부팅 표시 (연결이 끊기면 다시 연결해 이어서 수집, 재부팅 시점에 북마크된 표시 줄)");
        addDesc("Version 1.23 : 장치 통계 주기 선택 (1·2·5·10·30·60초, View 메뉴 또는 자세히 창)");
        addDesc("Version 1.22 : Time 범위에 날짜 (날짜 버튼으로 월-일 시:분:초 선택, 오른쪽 클릭 '이 줄 시각을 Time 시작/끝으로')");
        addDesc("Version 1.21 : 안드로이드 장치 통계 (상태 표시줄 오른쪽 CPU·메모리, 누르면 그래프·코어별·상위 프로세스)");
        addDesc("Version 1.20 : View 메뉴 Full / Compact 화면 (F12 전환, Compact는 로그 화면만)");
        addDesc("Version 1.19 : Find·Remove 정규식 모드 (입력창 오른쪽 .* 버튼, 대소문자 무시)");
        addDesc("Version 1.18 : 시간 범위 필터 (Time : 시작 ~ 끝, 예: 12:30 ~ 12:45:30, 10-07 23:00 ~ 10-08 01:00)");
        addDesc("Version 1.17 : adb 기록 파일이 50MB를 넘으면 다음 파일(이름_2.txt, _3 …)에 이어서 기록, 화면은 끊김 없이 이어서 표시");
        addDesc("Version 1.16 : 입력창별 최근 입력 10개 저장(다음 실행에도 유지), ▼ 버튼·Alt+↓로 골라 쓰기");
        addDesc("Version 1.15 : File > 강제 종료 (진행 중인 작업을 기다리지 않고 바로 종료)");
        addDesc("Version 1.14 : 메시지 검색 (Ctrl+F, F3 다음 / Shift+F3 이전), 북마크 이동은 F2 / Shift+F2");
        addDesc("Version 1.13 : 오른쪽 클릭 → 그 줄의 로그 전체 보기 (줄바꿈, 로그 전체/셀 값 복사)");
        addDesc("Version 1.12 : Highlight 입력창 6개, 입력창마다 색상(6가지) 선택");
        addDesc("   - 색상은 LogFilterColor.ini의 INI_HIGILIGHT_0~5 (0xRRGGBB)");
        addDesc("Version 1.11 : adb 출력을 바로 읽어 표시 (실시간 표시 지연·디스크 사용 감소)");
        addDesc("Version 1.10 : 대용량 파일 지원 (읽는 즉시 표시, 메모리 사용 감소), 스크롤 끊김 개선");
        addDesc("Version 1.9 : 필터 입력 히스토리(↑/↓), Ctrl+Z/Y 되돌리기, 드래그&드롭·실행 인자로 연 파일 Recent 추가");
        addDesc("   - logcat 형식 확장: year, uid, brief, process, tag, dmesg");
        addDesc("   - 대용량 로그 성능 개선, 버그·안정성 수정");
        addDesc("   - 버전 규칙: 큰버전.중간버전.빌드날짜(yyyyMMddHHmmss)");
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
        addDesc("F2 : next bookmark, Shift+F2 : pre bookmark");
        addDesc("");
        addDesc("[Search] (필터와 별개로 메시지에서 찾기)");
        addDesc("Ctrl+F : 검색창으로, Enter/F3 : 다음, Shift+Enter/Shift+F3 : 이전");
        addDesc("");
        addDesc("[Copy]");
        addDesc("Ctrl+c : row copy");
        addDesc("right click : 로그 전체 보기 (로그 전체 복사 / 셀 값 복사)");
    }

    // ---- 화면 반영 (항상 EDT에서 실행) ----

    // 지금 스레드가 EDT면 바로, 아니면 EDT에 넘겨 실행한다.
    static void runOnEdt(Runnable runnable)
    {
        if(SwingUtilities.isEventDispatchThread())
            runnable.run();
        else
            SwingUtilities.invokeLater(runnable);
    }

    // FilterEngine이 알려 준 현재 목록(All/Filtered)을 테이블과 인디케이터에 반영한다. nMode = FilterEngine.REFRESH_*
    void refreshTable(final int nMode)
    {
        runOnEdt(new Runnable()
        {
            public void run()
            {
                FilterEngine.View view = m_engine.getView();

                int nOldCount    = m_tmLogTableModel.getRowCount();
                int nSelected    = m_tbLogTable.getSelectedRow();
                boolean bAtEnd   = nSelected == -1 || nSelected == nOldCount - 1;

                if(m_tmLogTableModel.getData() != view.arList)
                {
                    // 다른 목록으로 교체: 전체 다시 그리기 (선택은 해제됨)
                    m_tmLogTableModel.setData(view.arList);
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
                m_ipIndicator.setData(view.arList, view.hmBookmark, view.hmError);

                int nLast = m_tmLogTableModel.getRowCount() - 1;
                if(nLast >= 0 && (nMode == FilterEngine.REFRESH_SELECT_LAST || (nMode == FilterEngine.REFRESH_FOLLOW_END && bAtEnd)))
                    m_tbLogTable.changeSelection(nLast, 0, false, false, true);
                m_ipIndicator.repaint();
            }
        });
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

    // ---- FilterEngine.Listener / LogSource.Listener ----

    public void onDataChanged(int nMode)    { refreshTable(nMode); }
    // 필터·로딩이 끝났다는 알림에 입력 오류(틀린 정규식·시각)를 덧붙여, 오류가 "Complete"에 가려지지 않게 한다.
    // LogSource.Listener: 수집 중 재연결하면서 재부팅을 확인했다
    public void onReboot(String strBootId, String strInfo)
    {
        addRebootRecord(strBootId, strInfo);
    }

    public void onStatus(String strText)
    {
        String strError = inputErrors();
        if(strError != null && strText != null && (strText.startsWith("Complete") || strText.startsWith("Ready")))
            strText = strText + "   ⚠ " + strError;
        setStatus(strText);
    }

    // 지금 입력창에 남아 있는 오류 (없으면 null)
    volatile String m_strTimeError;
    String inputErrors()
    {
        String strFind = m_engine.getFindRegexError(), strRemove = m_engine.getRemoveRegexError(), strTime = m_strTimeError;
        StringBuilder sb = new StringBuilder();
        for(String s : new String[]{ strFind, strRemove, strTime })
            if(s != null) sb.append(sb.length() > 0 ? " / " : "").append(s);
        return sb.length() > 0 ? sb.toString() : null;
    }
    public void onTitle(String strTitle)    { setTitle(strTitle); }

    public void onProcessStopped()
    {
        runOnEdt(new Runnable()
        {
            public void run()
            {
                setProcessBtn(false);
                // 실행 중 안내만 지운다 (adb 오류 메시지는 남겨 둠)
                if(m_tfStatus.getText().startsWith("adb 실행 중"))
                    setStatus("Stopped");
            }
        });
    }

    public void onDevices(final List<Object> arItem)
    {
        runOnEdt(new Runnable()
        {
            public void run()
            {
                DefaultListModel listModel = (DefaultListModel)m_lDeviceList.getModel();
                int nOnline = 0;
                LogSource.Device online = null;
                for(Object item : arItem)
                {
                    listModel.addElement(item);
                    if(item instanceof LogSource.Device && ((LogSource.Device)item).isOnline())
                    {
                        nOnline++;
                        online = (LogSource.Device)item;
                    }
                }
                m_btnDevice.setEnabled(true);
                // 연결된 장치가 하나뿐이면 바로 선택해 둔다.
                if(nOnline == 1)
                    m_lDeviceList.setSelectedValue(online, true);
                if(arItem.isEmpty())
                    setStatus("장치 없음 : USB로 연결하거나 adb connect <IP>:5555 실행 후 다시 OK를 누르세요.");
                else if(nOnline == 0)
                    setStatus("연결된(device 상태) 장치가 없습니다. 목록의 상태를 확인하세요.");
                else
                    setStatus("장치 " + arItem.size() + "개 중 연결됨 " + nOnline + "개" + (nOnline > 1 ? " : 사용할 장치를 선택하세요." : ""));
            }
        });
    }

    // ---- 동작 (FilterEngine / LogSource에 위임) ----

    void bookmarkItem(int nIndex, int nLine, boolean bBookmark)
    {
        m_engine.bookmarkItem(nIndex, nLine, bBookmark);
        m_ipIndicator.repaint();
    }

    void setBookmark(int nLine, String strBookmark)
    {
        m_engine.setBookmark(nLine, strBookmark);
    }

    // Open · Recent · 드래그&드롭 · 실행 인자로 연 파일이 모두 여기로 온다. 연 파일은 Recent 맨 위에 올린다.
    void parseFile(File file)
    {
        if(file == null)
        {
            T.e("file == null");
            return;
        }
        m_source.parseFile(file, "UTF-8".equals(m_comboEncode.getSelectedItem()));
        if(m_recentMenu != null)
            m_recentMenu.addEntry(file.getAbsolutePath());
    }

    void startProcess()
    {
        String strProblem = checkDeviceBeforeRun();
        if(strProblem != null)
        {
            setStatus(strProblem);
            return;
        }
        m_source.startProcess(getProcessCmd(), "UTF-8".equals(m_comboEncode.getSelectedItem()));
        setProcessBtn(true);
    }

    // Run 전에 장치 상태를 확인한다. 실행하면 안 되는 경우 그 이유를, 괜찮으면 null을 돌려준다.
    // (offline 장치에 adb logcat을 실행하면 오류 없이 멈춰 있어서, 화면이 비어 있는 이유를 알기 어렵다)
    String checkDeviceBeforeRun()
    {
        Object selected = m_lDeviceList.getSelectedValue();
        if(selected instanceof LogSource.Device)
        {
            LogSource.Device device = (LogSource.Device)selected;
            if(device.isOnline())
                return null;
            return deviceStateMessage(device);
        }

        // 선택한 장치가 없을 때: 목록에서 연결된 장치를 센다. (목록을 한 번도 불러오지 않았으면 adb에 맡긴다)
        ListModel model = m_lDeviceList.getModel();
        if(model.getSize() == 0)
            return null;
        LogSource.Device online = null;
        int nOnline = 0;
        for(int i = 0; i < model.getSize(); i++)
        {
            Object item = model.getElementAt(i);
            if(item instanceof LogSource.Device && ((LogSource.Device)item).isOnline())
            {
                nOnline++;
                online = (LogSource.Device)item;
            }
        }
        if(nOnline == 0)
            return "연결된(device 상태) 장치가 없습니다. 장치를 다시 연결한 뒤 OK로 목록을 갱신하세요.";
        if(nOnline > 1)
            return "연결된 장치가 " + nOnline + "개입니다. 목록에서 사용할 장치를 선택하세요.";
        m_lDeviceList.setSelectedValue(online, true);   // 하나뿐이면 그 장치로 실행
        return null;
    }

    static String deviceStateMessage(LogSource.Device device)
    {
        if("offline".equals(device.m_strState))
            return device.m_strSerial + " 은(는) offline 상태입니다. 장치를 다시 연결(adb connect 등)한 뒤 OK로 목록을 갱신하세요.";
        if("unauthorized".equals(device.m_strState))
            return device.m_strSerial + " 은(는) unauthorized 상태입니다. 장치 화면에서 USB 디버깅을 허용하세요.";
        return device.m_strSerial + " 의 상태가 " + device.m_strState + " 입니다. 연결된(device) 장치를 선택하세요.";
    }

    void setDeviceList()
    {
        m_strSelectedDevice = "";
        ((DefaultListModel)m_lDeviceList.getModel()).clear();
        m_btnDevice.setEnabled(false);
        setStatus("adb devices ...");
        m_source.listDevices();
    }

    void pauseProcess()
    {
        m_source.setPause(m_tbtnPause.isSelected());
        m_tbtnPause.setText(m_tbtnPause.isSelected() ? "Resume" : "Pause");
    }

    Component getBookmarkPanel()
    {
        JPanel jp = new JPanel();
        jp.setLayout(new BorderLayout());

        m_ipIndicator = new IndicatorPanel(this);
        FilterEngine.View view = m_engine.getView();
        m_ipIndicator.setData(view.arList, view.hmBookmark, view.hmError);
        jp.add(m_ipIndicator, BorderLayout.CENTER);
        return jp;
    }

    Component getCmdPanel()
    {
        JPanel jpOptionDevice = new JPanel();
        jpOptionDevice.setBorder(BorderFactory.createTitledBorder("Device select"));
        jpOptionDevice.setLayout(new BorderLayout());

        JPanel jpCmd = new JPanel();
        m_comboDeviceCmd = new JComboBox();
        m_comboDeviceCmd.addItem(COMBO_ANDROID);
        m_comboDeviceCmd.addItemListener(new ItemListener()
        {
            public void itemStateChanged(ItemEvent e)
            {
                if(e.getStateChange() != ItemEvent.SELECTED) return;
                ((DefaultListModel)m_lDeviceList.getModel()).clear();
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
                Object selectedItem = deviceList.getSelectedValue();
                m_strSelectedDevice = "";
                if(selectedItem instanceof LogSource.Device)
                {
                    LogSource.Device device = (LogSource.Device)selectedItem;
                    m_strSelectedDevice = device.m_strSerial;
                    setStatus(device.isOnline() ? "선택한 장치 : " + device.m_strSerial : deviceStateMessage(device));
                }
                if(m_deviceStats != null)
                    m_deviceStats.setDevice(m_strSelectedDevice);     // 장치 통계도 선택한 장치로
            }
        });
        jpOptionDevice.add(vbar);

        return jpOptionDevice;
    }
    void addChangeListener()
    {
        for(int nIndex = 0; nIndex < LogColor.HIGHLIGHT_COUNT; nIndex++)
        {
            final int nSlot = nIndex;
            m_arTfHighlight[nIndex].getDocument().addDocumentListener(m_dlFilterListener);
            m_arChkHighlight[nIndex].addItemListener(m_itemListener);
            m_arCbHighlightColor[nIndex].addActionListener(new ActionListener()
            {
                public void actionPerformed(ActionEvent e) { updateHighlight(nSlot); }
            });
        }
        m_tfFindWord.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfRemoveWord.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfShowTag.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfRemoveTag.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfShowPid.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfShowTid.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfTimeFrom.getDocument().addDocumentListener(m_dlFilterListener);
        m_tfTimeTo.getDocument().addDocumentListener(m_dlFilterListener);

        m_chkEnableFind.addItemListener(m_itemListener);
        m_chkEnableRemove.addItemListener(m_itemListener);
        m_chkEnableShowPid.addItemListener(m_itemListener);
        m_chkEnableShowTid.addItemListener(m_itemListener);
        m_chkEnableTime.addItemListener(m_itemListener);
        m_chkEnableShowTag.addItemListener(m_itemListener);
        m_chkEnableRemoveTag.addItemListener(m_itemListener);

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

    // 입력창별 최근 입력 기록 (이름 → 기록). 설정 파일에 저장되어 다음 실행에도 남는다.
    final java.util.Map<String, InputHistory> m_mapHistory = new java.util.LinkedHashMap<String, InputHistory>();

    // 편집창에 최근 입력 기록(최대 10개)과 ↑/↓·Alt+↓ 불러오기를 붙인다.
    InputHistory installInputHistory(JTextComponent comp, String strKey)
    {
        InputHistory history = new InputHistory(strKey, comp);
        m_mapHistory.put(strKey, history);
        comp.putClientProperty(InputHistory.class, history);
        return history;
    }

    // [입력창][▼]: ▼를 누르면 최근 입력 목록을 띄워 골라 쓴다.
    static JPanel withHistory(final JTextComponent comp)
    {
        return withHistory(comp, null);
    }

    // [입력창][extra][▼]: extra(예: 정규식 토글)는 ▼ 앞에 둔다. ▼는 오른쪽 끝에 맞춰진다.
    static JPanel withHistory(final JTextComponent comp, Component extra)
    {
        final JButton btn = new JButton("▼");
        btn.setMargin(new Insets(0, 2, 0, 2));
        btn.setFocusable(false);
        btn.setToolTipText("최근 입력 목록 (최대 10개, Alt+↓)");
        btn.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                InputHistory history = (InputHistory)comp.getClientProperty(InputHistory.class);
                if(history != null) history.showPopup(comp);
            }
        });
        JPanel jp = new JPanel(new BorderLayout());
        jp.add(comp, BorderLayout.CENTER);
        if(extra == null)
            jp.add(btn, BorderLayout.EAST);
        else
        {
            JPanel jpEast = new JPanel(new BorderLayout());
            jpEast.add(extra, BorderLayout.WEST);
            jpEast.add(btn, BorderLayout.EAST);
            jp.add(jpEast, BorderLayout.EAST);
        }
        return jp;
    }

    // 정규식 모드 토글 ".*" (켜면 그 입력창을 정규식으로)
    JToggleButton createRegexToggle(final String strWhich)
    {
        final JToggleButton tb = new JToggleButton(".*");
        tb.setMargin(new Insets(0, 3, 0, 3));
        tb.setFocusable(false);
        tb.setToolTipText(strWhich + " 정규식 모드: 켜면 입력을 정규식으로 Message에서 찾습니다 (대소문자 무시). 끄면 | 로 나눈 단어 포함 검사");
        tb.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e) { applyRegexMode(); }
        });
        return tb;
    }

    // 정규식 토글 상태를 엔진에 반영하고 재필터
    void applyRegexMode()
    {
        m_engine.setFindRegex(m_tbFindRegex.isSelected());
        m_engine.setRemoveRegex(m_tbRemoveRegex.isSelected());
        showRegexErrors();
        m_engine.requestFilter();
        m_tbLogTable.repaint();
    }

    // 틀린 정규식이면 그 입력창을 붉게, 상태 표시줄에 이유 (그 필터는 거르지 않음)
    void showRegexErrors()
    {
        String strFindError = m_engine.getFindRegexError(), strRemoveError = m_engine.getRemoveRegexError();
        m_tfFindWord.setBackground(strFindError != null ? COLOR_INPUT_ERROR : Color.WHITE);
        m_tfRemoveWord.setBackground(strRemoveError != null ? COLOR_INPUT_ERROR : Color.WHITE);
        m_tfFindWord.setToolTipText(strFindError);
        m_tfRemoveWord.setToolTipText(strRemoveError);
        if(strFindError != null || strRemoveError != null)
            setStatus(strFindError != null ? strFindError : strRemoveError);
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
        m_chkEnableTime = new JCheckBox();
        m_chkEnableTime.setSelected(true);

        m_tfFindWord    = new JTextField();
        m_tfRemoveWord  = new JTextField();
        m_tfShowTag     = new JTextField();
        m_tfRemoveTag   = new JTextField();
        m_tfShowPid     = new JTextField();
        m_tfShowTid     = new JTextField();
        m_tfTimeFrom    = new JTextField();
        m_tfTimeTo      = new JTextField();
        String strTimeTip = "시간 범위 (비우면 제한 없음). 예: 12:30, 12:30:15.500, 10-07 12:30, 2026-10-07 12:30 — 날짜 버튼으로 날짜·시각 선택, 로그 줄 오른쪽 클릭으로도 설정. 날짜 없이 시작이 끝보다 늦으면 자정을 넘는 범위";
        m_tfTimeFrom.setToolTipText("시작 시각 — " + strTimeTip);
        m_tfTimeTo.setToolTipText("끝 시각 — " + strTimeTip);
        installUndoRedo(m_tfTimeFrom);
        installUndoRedo(m_tfTimeTo);

        // 필터 편집창에 Ctrl+Z 백업(실행취소) 기능 추가
        installUndoRedo(m_tfFindWord);
        installUndoRedo(m_tfRemoveWord);
        installUndoRedo(m_tfShowTag);
        installUndoRedo(m_tfRemoveTag);
        installUndoRedo(m_tfShowPid);
        installUndoRedo(m_tfShowTid);

        // 필터 편집창에 최근 입력값 ↑/↓ 불러오기 기능 추가
        installInputHistory(m_tfFindWord,   "FIND");
        installInputHistory(m_tfRemoveWord, "REMOVE");
        installInputHistory(m_tfShowTag,    "SHOW_TAG");
        installInputHistory(m_tfRemoveTag,  "REMOVE_TAG");
        installInputHistory(m_tfShowPid,    "PID");
        installInputHistory(m_tfShowTid,    "TID");
        installInputHistory(m_tfTimeFrom,   "TIME_FROM");
        installInputHistory(m_tfTimeTo,     "TIME_TO");

        JPanel jpMain = new JPanel(new BorderLayout());

        JPanel jpWordFilter = new JPanel(new GridLayout(3, 1));
        jpWordFilter.setBorder(BorderFactory.createTitledBorder("Word filter"));

        JPanel jpFind = new JPanel(new BorderLayout());
        JLabel find = new JLabel();
        find.setText("Find :  ");
        jpFind.add(checkAndLabel(m_chkEnableFind, find), BorderLayout.WEST);
        m_tbFindRegex   = createRegexToggle("Find");
        m_tbRemoveRegex = createRegexToggle("Remove");
        jpFind.add(withHistory(m_tfFindWord, m_tbFindRegex), BorderLayout.CENTER);

        JPanel jpRemove = new JPanel(new BorderLayout());
        JLabel remove = new JLabel();
        remove.setText("Remove :  ");
        jpRemove.add(checkAndLabel(m_chkEnableRemove, remove), BorderLayout.WEST);
        jpRemove.add(withHistory(m_tfRemoveWord, m_tbRemoveRegex), BorderLayout.CENTER);

        // 시간 범위: [체크박스][Time :][시작 ▼] ~ [끝 ▼]
        JPanel jpTime = new JPanel(new BorderLayout());
        JLabel time = new JLabel();
        time.setText("Time :  ");
        jpTime.add(checkAndLabel(m_chkEnableTime, time), BorderLayout.WEST);
        JPanel jpTimeTo = new JPanel(new BorderLayout());
        jpTimeTo.add(new JLabel(" ~ "), BorderLayout.WEST);
        jpTimeTo.add(withHistory(m_tfTimeTo, createTimePickerButton(m_tfTimeTo, false)), BorderLayout.CENTER);
        JPanel jpTimeFields = new JPanel(new GridLayout(1, 2, 2, 0));
        jpTimeFields.add(withHistory(m_tfTimeFrom, createTimePickerButton(m_tfTimeFrom, true)));
        jpTimeFields.add(jpTimeTo);
        jpTime.add(jpTimeFields, BorderLayout.CENTER);

        jpWordFilter.add(jpFind);
        jpWordFilter.add(jpRemove);
        jpWordFilter.add(jpTime);

        jpMain.add(jpWordFilter, BorderLayout.NORTH);

        JPanel jpTagFilter = new JPanel(new GridLayout(4, 1));
        jpTagFilter.setBorder(BorderFactory.createTitledBorder("Tag filter"));

        JPanel jpPid = new JPanel(new BorderLayout());
        JLabel pid = new JLabel();
        pid.setText("Pid :  ");
        jpPid.add(checkAndLabel(m_chkEnableShowPid, pid), BorderLayout.WEST);
        jpPid.add(withHistory(m_tfShowPid), BorderLayout.CENTER);

        JPanel jpTid = new JPanel(new BorderLayout());
        JLabel tid = new JLabel();
        tid.setText("Tid :  ");
        jpTid.add(checkAndLabel(m_chkEnableShowTid, tid), BorderLayout.WEST);
        jpTid.add(withHistory(m_tfShowTid), BorderLayout.CENTER);

        JPanel jpShow = new JPanel(new BorderLayout());
        JLabel show = new JLabel();
        show.setText("Show :  ");
        jpShow.add(checkAndLabel(m_chkEnableShowTag, show), BorderLayout.WEST);
        jpShow.add(withHistory(m_tfShowTag), BorderLayout.CENTER);

        JPanel jpRemoveTag = new JPanel(new BorderLayout());
        JLabel removeTag = new JLabel();
        removeTag.setText("Remove :  ");
        jpRemoveTag.add(checkAndLabel(m_chkEnableRemoveTag, removeTag), BorderLayout.WEST);
        jpRemoveTag.add(withHistory(m_tfRemoveTag), BorderLayout.CENTER);

        // 라벨을 모두 가장 긴 라벨 폭으로 맞추고 오른쪽 정렬해, 입력창이 같은 위치에서 시작하게 한다.
        alignLabels(find, remove, time, pid, tid, show, removeTag);

        jpTagFilter.add(jpPid);
        jpTagFilter.add(jpTid);
        jpTagFilter.add(jpShow);
        jpTagFilter.add(jpRemoveTag);

        jpMain.add(jpTagFilter, BorderLayout.CENTER);

        return jpMain;
    }

    // 필터 한 줄의 왼쪽: [사용 체크박스][라벨]
    static JPanel checkAndLabel(JCheckBox checkBox, JLabel label)
    {
        JPanel jp = new JPanel(new BorderLayout());
        jp.add(checkBox, BorderLayout.WEST);
        jp.add(label, BorderLayout.CENTER);
        return jp;
    }

    // 라벨들을 가장 넓은 라벨의 폭으로 맞추고 오른쪽 정렬한다.
    static void alignLabels(JLabel... arLabel)
    {
        int nWidth = 0;
        for(JLabel label : arLabel)
            nWidth = Math.max(nWidth, label.getPreferredSize().width);
        for(JLabel label : arLabel)
        {
            label.setHorizontalAlignment(SwingConstants.RIGHT);
            label.setPreferredSize(new Dimension(nWidth, label.getPreferredSize().height));
        }
    }

    // 하이라이트 입력창 6개: 한 줄마다 [사용 체크박스][색상 선택][입력창 → 패널 오른쪽 끝까지]
    Component getHighlightPanel()
    {
        JPanel jpMain = new JPanel(new GridLayout(LogColor.HIGHLIGHT_COUNT, 1, 0, 2));
        jpMain.setBorder(BorderFactory.createTitledBorder("Highlight"));

        for(int nIndex = 0; nIndex < LogColor.HIGHLIGHT_COUNT; nIndex++)
        {
            m_arChkHighlight[nIndex] = new JCheckBox();
            m_arChkHighlight[nIndex].setSelected(true);
            m_arChkHighlight[nIndex].setToolTipText("하이라이트 " + (nIndex + 1) + " 사용");

            m_arCbHighlightColor[nIndex] = createHighlightColorCombo(nIndex);

            m_arTfHighlight[nIndex] = new JTextField();
            m_arTfHighlight[nIndex].setToolTipText("하이라이트할 단어 (여러 개는 | 로 구분, 대소문자 무시)");
            installUndoRedo(m_arTfHighlight[nIndex]);
            installInputHistory(m_arTfHighlight[nIndex], "HIGHLIGHT_" + nIndex);

            JPanel jpLeft = new JPanel(new BorderLayout());
            jpLeft.add(m_arChkHighlight[nIndex], BorderLayout.WEST);
            jpLeft.add(m_arCbHighlightColor[nIndex], BorderLayout.CENTER);

            JPanel jpRow = new JPanel(new BorderLayout(2, 0));
            jpRow.add(jpLeft, BorderLayout.WEST);
            jpRow.add(withHistory(m_arTfHighlight[nIndex]), BorderLayout.CENTER);
            jpMain.add(jpRow);
        }
        return jpMain;
    }

    // 하이라이트 색상 6개 중 하나를 고르는 콤보 (색상 견본 + 번호)
    JComboBox<Integer> createHighlightColorCombo(int nDefault)
    {
        Integer[] arItem = new Integer[LogColor.HIGHLIGHT_COUNT];
        for(int i = 0; i < arItem.length; i++)
            arItem[i] = i;
        JComboBox<Integer> combo = new JComboBox<Integer>(arItem);
        combo.setSelectedIndex(nDefault);
        combo.setToolTipText("하이라이트 색상 (LogFilterColor.ini의 INI_HIGILIGHT_0~5)");
        combo.setRenderer(new DefaultListCellRenderer()
        {
            private static final long serialVersionUID = 1L;
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
            {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                final int nColor = value == null ? 0 : (Integer)value;
                setText(String.valueOf(nColor + 1));
                setIcon(new Icon()
                {
                    public int getIconWidth()  { return 22; }
                    public int getIconHeight() { return 12; }
                    public void paintIcon(Component c, Graphics g, int x, int y)
                    {
                        g.setColor(new Color(Integer.parseInt(LogColor.COLOR_HIGHLIGHT[nColor], 16)));
                        g.fillRect(x, y, 22, 12);
                        g.setColor(Color.GRAY);
                        g.drawRect(x, y, 21, 11);
                    }
                });
                return this;
            }
        });
        return combo;
    }

    // nIndex번 하이라이트 입력창의 현재 상태를 테이블에 반영한다. (재필터 없이 다시 그리기만)
    void updateHighlight(int nIndex)
    {
        String strText = m_arChkHighlight[nIndex].isSelected() ? m_arTfHighlight[nIndex].getText() : "";
        m_tbLogTable.SetHighlight(nIndex, strText, m_arCbHighlightColor[nIndex].getSelectedIndex());
        m_tbLogTable.repaint();
    }

    // 하이라이트 입력창·체크박스·색상 콤보가 몇 번째 것인지 (아니면 -1)
    int highlightIndexOf(Object source)
    {
        for(int i = 0; i < LogColor.HIGHLIGHT_COUNT; i++)
        {
            if(source == m_arChkHighlight[i] || source == m_arCbHighlightColor[i] || source == m_arTfHighlight[i].getDocument())
                return i;
        }
        return -1;
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

        // Log filter / Show column은 2줄로 배치해 폭을 줄인다.
        JPanel jpLogFilter = new JPanel(new GridLayout(2, 1, 0, 0));
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
        jpLogFilter.add(checkRow(m_chkVerbose, m_chkDebug, m_chkInfo));
        jpLogFilter.add(checkRow(m_chkWarn, m_chkError, m_chkFatal));

        jpMain.add(jpLogFilter, BorderLayout.NORTH);

        JPanel jpShowColumn = new JPanel(new GridLayout(2, 1, 0, 0));
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
        jpShowColumn.add(checkRow(m_chkClmBookmark, m_chkClmLine, m_chkClmDate, m_chkClmTime, m_chkClmLogLV));
        jpShowColumn.add(checkRow(m_chkClmPid, m_chkClmThread, m_chkClmTag, m_chkClmMessage));

        // 두 묶음 모두 위쪽에 붙인다 (남는 높이에 줄 간격이 벌어지지 않도록)
        JPanel jpNorth = new JPanel(new BorderLayout());
        jpNorth.add(jpMain.getComponent(0), BorderLayout.NORTH);
        jpNorth.add(jpShowColumn, BorderLayout.CENTER);
        jpMain.add(jpNorth, BorderLayout.NORTH);
        return jpMain;
    }

    // 체크박스 한 줄 (왼쪽 정렬)
    static JPanel checkRow(JCheckBox... arCheck)
    {
        JPanel jp = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for(JCheckBox check : arCheck)
            jp.add(check);
        return jp;
    }

    // 가운데 영역을 정해진 비율로 나누기 위한 칸: 내용의 선호 폭을 무시하고 비율(weightx)대로만 폭을 받는다.
    static JPanel ratioCell(Component comp)
    {
        JPanel jp = new JPanel(new BorderLayout())
        {
            private static final long serialVersionUID = 1L;
            public Dimension getPreferredSize() { return new Dimension(1, super.getPreferredSize().height); }
            public Dimension getMinimumSize()   { return new Dimension(1, super.getMinimumSize().height); }
        };
        jp.add(comp, BorderLayout.CENTER);
        return jp;
    }

    Component getOptionFilter()
    {
        JPanel optionFilter = new JPanel(new BorderLayout());

        optionFilter.add(getCmdPanel(), BorderLayout.WEST);
        optionFilter.add(getCheckPanel(), BorderLayout.EAST);
        // 가운데: 왼쪽 Word/Tag filter 3, 오른쪽 Highlight 1 비율 (둘 다 6줄이라 높이가 맞음)
        JPanel jpCenter = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.fill    = java.awt.GridBagConstraints.BOTH;
        gc.weighty = 1;
        gc.weightx = 3;
        jpCenter.add(ratioCell(getFilterPanel()), gc);
        gc.weightx = 1;
        jpCenter.add(ratioCell(getHighlightPanel()), gc);
        optionFilter.add(jpCenter, BorderLayout.CENTER);

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
        optionMenu.add(getSearchPanel(), BorderLayout.CENTER);
        return optionMenu;
    }

    // ---- 메시지 검색 (필터와 별개: 지금 보이는 목록에서 다음/이전 일치 행으로 이동) ----

    JTextField m_tfSearch;
    JButton    m_btnSearchPrev;
    JButton    m_btnSearchNext;
    LogSearch  m_search;            // 진행 중인 검색 (새 검색을 시작하면 이전 것은 중단)

    // [Search : ][입력창 → 오른쪽 끝까지][▲][▼]
    Component getSearchPanel()
    {
        m_tfSearch = new JTextField();
        m_tfSearch.setToolTipText("메시지 검색 (여러 개는 | 로 구분, 대소문자 무시) — Enter/F3: 다음, Shift+Enter/Shift+F3: 이전, Ctrl+F: 여기로");
        installUndoRedo(m_tfSearch);
        installInputHistory(m_tfSearch, "SEARCH");
        // 입력하는 대로 검색어를 테이블에 빨간색으로 표시 (재필터 없이 다시 그리기만)
        m_tfSearch.getDocument().addDocumentListener(new DocumentListener()
        {
            void update()
            {
                m_tbLogTable.SetSearch(m_tfSearch.getText());
                m_tbLogTable.repaint();
            }
            public void insertUpdate(DocumentEvent e)  { update(); }
            public void removeUpdate(DocumentEvent e)  { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
        });
        // Enter: 히스토리에 저장하고 다음 찾기, Shift+Enter: 이전 찾기
        final javax.swing.Action commit = m_tfSearch.getActionMap().get("HistoryCommit");
        m_tfSearch.getActionMap().put("HistoryCommit", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                commit.actionPerformed(e);
                search(true);
            }
        });
        m_tfSearch.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "SearchPrev");
        m_tfSearch.getActionMap().put("SearchPrev", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e) { search(false); }
        });

        m_btnSearchPrev = new JButton("▲");
        m_btnSearchPrev.setMargin(new Insets(0, 4, 0, 4));
        m_btnSearchPrev.setToolTipText("이전 찾기 (Shift+F3)");
        m_btnSearchPrev.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e) { search(false); }
        });
        m_btnSearchNext = new JButton("▼");
        m_btnSearchNext.setMargin(new Insets(0, 4, 0, 4));
        m_btnSearchNext.setToolTipText("다음 찾기 (F3)");
        m_btnSearchNext.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e) { search(true); }
        });

        JPanel jpButtons = new JPanel(new GridLayout(1, 2, 2, 0));
        jpButtons.add(m_btnSearchPrev);
        jpButtons.add(m_btnSearchNext);

        JPanel jpSearch = new JPanel(new BorderLayout(4, 0));
        jpSearch.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 4));
        jpSearch.add(new JLabel("Search : "), BorderLayout.WEST);
        jpSearch.add(withHistory(m_tfSearch), BorderLayout.CENTER);
        jpSearch.add(jpButtons, BorderLayout.EAST);
        return jpSearch;
    }

    // 창 어디서든 Ctrl+F(검색창으로), F3(다음), Shift+F3(이전)
    void installSearchKeys()
    {
        javax.swing.InputMap  im = getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap am = getRootPane().getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "SearchFocus");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "SearchNext");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK), "SearchPrevKey");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0), "ToggleView");
        am.put("ToggleView", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e) { toggleView(); }
        });
        am.put("SearchFocus", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e) { setFindFocus(); }
        });
        am.put("SearchNext", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e) { search(true); }
        });
        am.put("SearchPrevKey", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e) { search(false); }
        });
    }

    static final long SEARCH_PROGRESS_MS = 200;

    /**
     * 지금 보이는 목록에서 검색어가 Message에 들어 있는 다음(bForward) / 이전 행을 찾아 선택한다.
     * 선택된 행 다음부터 찾고, 끝에 닿으면 처음부터(이전 찾기는 끝부터) 이어서 찾는다. 백그라운드에서 찾는다.
     */
    void search(final boolean bForward)
    {
        final String strText = m_tfSearch.getText();
        final String[] arToken = FilterToken.split(strText);
        if(arToken.length == 0)
        {
            setStatus("검색어를 입력하세요 (Ctrl+F)");
            m_tfSearch.requestFocusInWindow();
            return;
        }
        LogSearch old = m_search;
        if(old != null) old.cancel();
        final LogSearch search = new LogSearch();
        m_search = search;

        final LogList list   = m_tmLogTableModel.getData();
        final int     nRows  = m_tmLogTableModel.getRowCount();
        final int     nStart = m_tbLogTable.getSelectedRow();
        if(list == null || nRows == 0)
        {
            setStatus("'" + strText + "' : 찾을 줄이 없습니다");
            return;
        }
        Thread th = new Thread(new Runnable()
        {
            long m_nLastProgress = System.currentTimeMillis();

            public void run()
            {
                final LogSearch.Result result = search.find(list, nRows, arToken, nStart, bForward, new LogSearch.Progress()
                {
                    public void onProgress(int nDone, int nTotal)
                    {
                        long nNow = System.currentTimeMillis();
                        if(nNow - m_nLastProgress >= SEARCH_PROGRESS_MS && m_search == search)
                        {
                            m_nLastProgress = nNow;
                            setStatus(String.format("'%s' 검색 중 %d%%", strText, (int)((long)nDone * 100 / Math.max(1, nTotal))));
                        }
                    }
                });
                runOnEdt(new Runnable()
                {
                    public void run()
                    {
                        // 그 사이 새 검색을 시작했거나 목록이 바뀌었으면 결과를 버린다.
                        if(search.isCancelled() || m_search != search || m_tmLogTableModel.getData() != list)
                            return;
                        m_search = null;
                        if(result == null)
                        {
                            setStatus("'" + strText + "' : 찾을 수 없습니다");
                            return;
                        }
                        int nRow = result.m_nRow;
                        m_tbLogTable.changeSelection(nRow, 0, false, false, false);
                        m_tbLogTable.showRow(nRow, true);
                        String strWrap = !result.m_bWrapped ? "" : bForward ? " (끝까지 찾아서 처음부터 다시 찾음)" : " (처음까지 찾아서 끝부터 다시 찾음)";
                        setStatus(String.format("'%s' : Line %d (%,d / %,d행)%s", strText, list.get(nRow).m_nLine, nRow + 1, nRows, strWrap));
                    }
                });
            }
        }, "LogSearch");
        th.setDaemon(true);
        th.start();
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

        // 오른쪽: 장치 통계 요약 (누르면 자세히)
        m_lbStats = new JLabel(" ");
        m_lbStats.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        m_lbStats.setToolTipText("안드로이드 장치 CPU·메모리 (2초마다). 누르면 자세히 — View > 장치 통계 표시로 끄고 켜기");
        m_lbStats.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        m_lbStats.addMouseListener(new java.awt.event.MouseAdapter()
        {
            public void mouseClicked(java.awt.event.MouseEvent e) { showStatsDialog(); }
        });

        JPanel jp = new JPanel(new BorderLayout());
        jp.add(m_tfStatus, BorderLayout.CENTER);
        jp.add(m_lbStats, BorderLayout.EAST);
        return jp;
    }

    // ---- 안드로이드 장치 통계 (1.21) ----
    JLabel                          m_lbStats;
    DeviceStats                     m_deviceStats;
    StatsDialog                     m_dlgStats;
    boolean                         m_bDeviceStats;
    javax.swing.JCheckBoxMenuItem   m_miDeviceStats;    // View 메뉴 (main()에서 만듦)
    javax.swing.JCheckBoxMenuItem   m_miAutoReconnect;  // File 메뉴 (1.24)
    volatile DeviceStats.Sample     m_lastStats;

    void initDeviceStats()
    {
        m_deviceStats = new DeviceStats(new DeviceStats.Listener()
        {
            public void onStats(final DeviceStats.Sample sample)
            {
                m_lastStats = sample;
                runOnEdt(new Runnable()
                {
                    public void run()
                    {
                        if(!m_bDeviceStats) return;
                        m_lbStats.setText(sample.summary());
                        m_lbStats.setForeground(sample.m_strError != null ? Color.GRAY : Color.BLACK);
                        if(m_dlgStats != null && m_dlgStats.isVisible())
                            m_dlgStats.update(sample);
                    }
                });
            }

            // 장치 통계가 재부팅을 알아챘다: 기록하고, 수집 중이면 로그에 표시 줄을 넣는다
            public void onReboot(DeviceStats.Sample sample)
            {
                String strInfo = "";
                try
                {
                    long nUptimeMs = (long)(Double.parseDouble(sample.m_strUptime) * 1000);
                    strInfo = "(부팅 시각 약 " + new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date(System.currentTimeMillis() - nUptimeMs)) + ")";
                }
                catch(Exception e) { }
                addRebootRecord(sample.m_strBootId, strInfo);
                m_source.markReboot(sample.m_strBootId, sample.m_strUptime);
            }
        });
    }

    // 재부팅 기록 (자세히 창에 보임)
    final List<String> m_arReboot = java.util.Collections.synchronizedList(new ArrayList<String>());
    final java.util.Set<String> m_setRebootId = new java.util.HashSet<String>();

    void addRebootRecord(String strBootId, final String strInfo)
    {
        // 수집과 장치 통계가 같은 재부팅을 둘 다 알릴 수 있다: 같은 boot_id는 한 번만
        synchronized(m_arReboot)
        {
            if(strBootId != null && !m_setRebootId.add(strBootId)) return;
            m_arReboot.add("감지 " + new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date()) + "  " + strInfo);
        }
        runOnEdt(new Runnable()
        {
            public void run()
            {
                setStatus("장치 재부팅 감지 " + strInfo + (m_source.isRunning() ? " : 로그에 표시했습니다 (F2로 이동)" : ""));
                if(m_dlgStats != null) m_dlgStats.setReboots(m_arReboot);
            }
        });
    }

    // 장치 통계 켜기/끄기 (켜면 2초마다 adb로 읽는다)
    void setDeviceStats(boolean bOn)
    {
        m_bDeviceStats = bOn;
        if(m_miDeviceStats != null) m_miDeviceStats.setSelected(bOn);
        m_deviceStats.setDevice(m_strSelectedDevice);
        if(bOn)
        {
            m_lbStats.setText("장치 통계 읽는 중…");
            m_lbStats.setForeground(Color.GRAY);
            m_deviceStats.start();
        }
        else
        {
            m_deviceStats.stop();
            m_lbStats.setText(" ");
            if(m_dlgStats != null) m_dlgStats.setVisible(false);
        }
    }

    final List<javax.swing.JRadioButtonMenuItem> m_arMiInterval = new ArrayList<javax.swing.JRadioButtonMenuItem>();   // View > 장치 통계 주기

    // 장치 통계 측정 주기(초). 메뉴·자세히 창의 선택도 맞춘다.
    void setStatsInterval(int nSec)
    {
        m_deviceStats.setInterval(nSec * 1000L);
        for(int i = 0; i < m_arMiInterval.size() && i < DeviceStats.INTERVALS_SEC.length; i++)
            m_arMiInterval.get(i).setSelected(DeviceStats.INTERVALS_SEC[i] == nSec);
        if(m_dlgStats != null)
            m_dlgStats.syncInterval();
        setStatus("장치 통계 주기 : " + nSec + "초");
    }

    void showStatsDialog()
    {
        if(!m_bDeviceStats)
            setDeviceStats(true);
        if(m_dlgStats == null)
            m_dlgStats = new StatsDialog(this, m_deviceStats);
        m_dlgStats.syncInterval();
        m_dlgStats.setReboots(m_arReboot);
        if(m_lastStats != null)
            m_dlgStats.update(m_lastStats);
        m_dlgStats.setVisible(true);
        m_dlgStats.toFront();
    }

    Component getTabPanel()
    {
        m_tmLogTableModel = new LogFilterTableModel();
        m_tmLogTableModel.setData(m_engine.getView().arList);
        m_tbLogTable = new LogTable(m_tmLogTableModel, this);
        m_tbLogTable.setFilterEngine(m_engine);
        m_scrollVBar = new JScrollPane(m_tbLogTable);
        return m_scrollVBar;
    }
    // Ctrl+F: 메시지 검색창으로 (입력된 검색어는 전체 선택해 바로 바꿔 칠 수 있게)
    public void setFindFocus()
    {
        if(m_bCompactView)
            setCompactView(false);      // 검색창이 보이도록
        m_tfSearch.requestFocus();
        m_tfSearch.selectAll();
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
                        m_source.stopProcess();
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
        m_engine.setLogLevel(nLogLV, bChecked);
        m_engine.requestFilter();
    }

    // 필터 사용 체크박스: 끄면 그 필터 문자열을 비운 것과 같다.
    void useFilter(JCheckBox checkBox)
    {
        int nHighlight = highlightIndexOf(checkBox);
        if(nHighlight >= 0)
        {
            // 하이라이트는 표시만 바뀌므로 재필터 없이 다시 그린다.
            updateHighlight(nHighlight);
            return;
        }
        if(checkBox.equals(m_chkEnableFind))
            m_engine.setFind(checkBox.isSelected() ? m_tfFindWord.getText() : "");
        else if(checkBox.equals(m_chkEnableRemove))
            m_engine.setRemove(checkBox.isSelected() ? m_tfRemoveWord.getText() : "");
        else if(checkBox.equals(m_chkEnableShowPid))
            m_engine.setShowPid(checkBox.isSelected() ? m_tfShowPid.getText() : "");
        else if(checkBox.equals(m_chkEnableShowTid))
            m_engine.setShowTid(checkBox.isSelected() ? m_tfShowTid.getText() : "");
        else if(checkBox.equals(m_chkEnableShowTag))
            m_engine.setShowTag(checkBox.isSelected() ? m_tfShowTag.getText() : "");
        else if(checkBox.equals(m_chkEnableRemoveTag))
            m_engine.setRemoveTag(checkBox.isSelected() ? m_tfRemoveTag.getText() : "");
        else if(checkBox.equals(m_chkEnableTime))
            applyTimeRange();
        if(checkBox.equals(m_chkEnableFind) || checkBox.equals(m_chkEnableRemove))
            showRegexErrors();
        m_engine.requestFilter();
    }

    static final Color COLOR_INPUT_ERROR = new Color(0xFFD6D6);   // 형식이 틀린 입력창 배경

    // ---- 시간 범위의 날짜·시각 고르기 (1.22) ----
    static final java.util.regex.Pattern TIME_INPUT = java.util.regex.Pattern.compile(
        "^\\s*(?:(?:\\d{4}[-/.])?(\\d{1,2})[-/.](\\d{1,2})\\s+)?(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?.*$");

    // 시간 입력창 옆 "날짜" 버튼: 누르면 월-일 시:분:초를 고르는 창
    JButton createTimePickerButton(final JTextField field, final boolean bFrom)
    {
        final JButton btn = new JButton("날짜");
        btn.setMargin(new Insets(0, 3, 0, 3));
        btn.setFocusable(false);
        btn.setToolTipText((bFrom ? "시작" : "끝") + " 날짜·시각 고르기 (선택한 줄 또는 " + (bFrom ? "첫" : "마지막") + " 줄의 날짜·시각으로 시작)");
        btn.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e) { showTimePicker(field, bFrom, btn); }
        });
        return btn;
    }

    javax.swing.JPopupMenu m_popupTimePicker;     // 마지막으로 띄운 날짜·시각 고르기 (테스트용)
    javax.swing.JSpinner   m_spinTimePicker;

    void showTimePicker(final JTextField field, final boolean bFrom, Component anchor)
    {
        java.util.Calendar cal = initialPickerTime(field.getText(), bFrom);
        final javax.swing.JSpinner spin = new javax.swing.JSpinner(new javax.swing.SpinnerDateModel(cal.getTime(), null, null, java.util.Calendar.MINUTE));
        spin.setEditor(new javax.swing.JSpinner.DateEditor(spin, "MM-dd HH:mm:ss"));
        final javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
        JButton btnApply = new JButton("적용");
        btnApply.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.setTime((java.util.Date)spin.getValue());
                field.setText(String.format("%02d-%02d %02d:%02d:%02d", c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                    c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND)));
                m_chkEnableTime.setSelected(true);
                popup.setVisible(false);
            }
        });
        JButton btnClear = new JButton("지우기");
        btnClear.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e) { field.setText(""); popup.setVisible(false); }
        });
        JPanel jp = new JPanel(new BorderLayout(4, 4));
        jp.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        jp.add(new JLabel((bFrom ? "시작" : "끝") + " 날짜·시각 (월-일 시:분:초, ↑/↓로 바꾸기)"), BorderLayout.NORTH);
        jp.add(spin, BorderLayout.CENTER);
        JPanel jpButtons = new JPanel(new GridLayout(1, 2, 4, 0));
        jpButtons.add(btnApply);
        jpButtons.add(btnClear);
        jp.add(jpButtons, BorderLayout.SOUTH);
        popup.add(jp);
        m_popupTimePicker = popup;
        m_spinTimePicker  = spin;
        popup.show(anchor, 0, anchor.getHeight());
        spin.requestFocusInWindow();
    }

    // 고르기 창의 처음 값: 입력창 값(날짜가 없으면 줄의 날짜) → 선택한 줄 → 첫(시작)/마지막(끝) 줄 → 지금
    java.util.Calendar initialPickerTime(String strText, boolean bFrom)
    {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.MILLISECOND, 0);
        LogInfo row = pickerRow(bFrom);
        if(row != null)
            setFromLog(cal, TimeRange.lineDate(row.m_strDate), TimeRange.lineTime(row.m_strTime));
        java.util.regex.Matcher m = TIME_INPUT.matcher(strText == null ? "" : strText);
        if(m.matches())
        {
            if(m.group(1) != null)
            {
                cal.set(java.util.Calendar.MONTH, Integer.parseInt(m.group(1)) - 1);
                cal.set(java.util.Calendar.DAY_OF_MONTH, Integer.parseInt(m.group(2)));
            }
            cal.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(m.group(3)));
            cal.set(java.util.Calendar.MINUTE, Integer.parseInt(m.group(4)));
            cal.set(java.util.Calendar.SECOND, m.group(5) != null ? Integer.parseInt(m.group(5)) : (bFrom ? 0 : 59));
        }
        return cal;
    }

    LogInfo pickerRow(boolean bFrom)
    {
        int nRows = m_tmLogTableModel.getRowCount();
        if(nRows == 0) return null;
        int nRow = m_tbLogTable.getSelectedRow();
        if(nRow < 0 || nRow >= nRows) nRow = bFrom ? 0 : nRows - 1;
        return m_tmLogTableModel.getRow(nRow);
    }

    static void setFromLog(java.util.Calendar cal, String strDate, String strTime)
    {
        if(strDate != null)
        {
            cal.set(java.util.Calendar.MONTH, Integer.parseInt(strDate.substring(0, 2)) - 1);
            cal.set(java.util.Calendar.DAY_OF_MONTH, Integer.parseInt(strDate.substring(3, 5)));
        }
        if(strTime != null)
        {
            cal.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(strTime.substring(0, 2)));
            cal.set(java.util.Calendar.MINUTE, Integer.parseInt(strTime.substring(3, 5)));
            cal.set(java.util.Calendar.SECOND, Integer.parseInt(strTime.substring(6, 8)));
        }
    }

    // 오른쪽 클릭한 줄의 날짜·시각을 Time 시작(bFrom)/끝으로 넣는다
    void setTimeFromLog(LogInfo logInfo, boolean bFrom)
    {
        String strTime = TimeRange.lineTime(logInfo.m_strTime);
        if(strTime == null)
        {
            setStatus("이 줄에는 시각이 없습니다");
            return;
        }
        String strDate = TimeRange.lineDate(logInfo.m_strDate);
        (bFrom ? m_tfTimeFrom : m_tfTimeTo).setText((strDate != null ? strDate + " " : "") + strTime);
        m_chkEnableTime.setSelected(true);
    }

    // 시간 범위 입력을 엔진에 반영한다. 형식이 틀리면 그 입력창을 붉게 표시하고 상태 표시줄에 이유를 보여주며, 시간 필터는 끈다.
    void applyTimeRange()
    {
        m_tfTimeFrom.setBackground(Color.WHITE);
        m_tfTimeTo.setBackground(Color.WHITE);
        m_strTimeError = null;
        if(!m_chkEnableTime.isSelected())
        {
            m_engine.clearTimeRange();
            return;
        }
        try
        {
            m_engine.setTimeRange(m_tfTimeFrom.getText(), m_tfTimeTo.getText());
        }
        catch(IllegalArgumentException e)
        {
            m_engine.clearTimeRange();
            for(JTextField field : new JTextField[]{ m_tfTimeFrom, m_tfTimeTo })
            {
                try
                {
                    TimeRange.parse(field.getText(), "");
                }
                catch(IllegalArgumentException bad)
                {
                    field.setBackground(COLOR_INPUT_ERROR);
                }
            }
            m_strTimeError = e.getMessage();
            setStatus(e.getMessage());
        }
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
        if(m_strSelectedDevice == null || m_strSelectedDevice.length() == 0)
            return ADB_CMD_FIRST + m_comboCmd.getSelectedItem();
        else
            return ADB_SELECTED_CMD_FIRST + m_strSelectedDevice + " " + m_comboCmd.getSelectedItem();
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
                startProcess();
            else if(e.getSource().equals(m_btnStop))
                m_source.stopProcess();
            else if(e.getSource().equals(m_btnClear))
            {
                boolean bBackup = m_source.m_bPause;
                m_source.setPause(true);
                m_engine.clearData();
                m_source.setPause(bBackup);
            }
            else if(e.getSource().equals(m_tbtnPause))
                pauseProcess();
            else if(e.getSource().equals(m_jcFontType))
            {
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
                m_engine.setShowBookmarkOnly(m_ipIndicator.m_chBookmark.isSelected());
                m_engine.setShowErrorOnly(m_ipIndicator.m_chError.isSelected());
                m_engine.requestFilter();
                break;
            case EVENT_CHANGE_FILTER_SHOW_TAG:
                m_tfShowTag.setText(m_engine.getShowTag());
                break;
            case EVENT_CHANGE_FILTER_REMOVE_TAG:
                m_tfRemoveTag.setText(m_engine.getRemoveTag());
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
                m_engine.requestFilter();
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
            int nHighlight = highlightIndexOf(doc);
            if(nHighlight >= 0)
            {
                updateHighlight(nHighlight);
                return;
            }

            if(doc.equals(m_tfFindWord.getDocument()) && m_chkEnableFind.isSelected())
            {
                m_engine.setFind(strText);
                showRegexErrors();
            }
            else if(doc.equals(m_tfRemoveWord.getDocument()) && m_chkEnableRemove.isSelected())
            {
                m_engine.setRemove(strText);
                showRegexErrors();
            }
            else if(doc.equals(m_tfShowPid.getDocument()) && m_chkEnableShowPid.isSelected())
                m_engine.setShowPid(strText);
            else if(doc.equals(m_tfShowTid.getDocument()) && m_chkEnableShowTid.isSelected())
                m_engine.setShowTid(strText);
            else if(doc.equals(m_tfShowTag.getDocument()) && m_chkEnableShowTag.isSelected())
                m_engine.setShowTag(strText);
            else if(doc.equals(m_tfRemoveTag.getDocument()) && m_chkEnableRemoveTag.isSelected())
                m_engine.setRemoveTag(strText);
            else if(doc.equals(m_tfTimeFrom.getDocument()) || doc.equals(m_tfTimeTo.getDocument()))
                applyTimeRange();

            // 진행 중인 재필터는 바로 중단시키고, 새 재필터는 입력이 멈춘 뒤 시작한다.
            m_engine.markChanged();
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
                    || check.equals(m_chkEnableTime)
                    || check.equals(m_chkEnableShowTag)
                    || check.equals(m_chkEnableRemoveTag)
                    || highlightIndexOf(check) >= 0)
                useFilter(check);
        }
    };
    
    public void openFileBrowser()
    {
        FileDialog fd = new FileDialog(this, "File open", FileDialog.LOAD);
        fd.setVisible( true );
        if (fd.getFile() != null)
        {
            parseFile(new File(fd.getDirectory() + fd.getFile()));
        }
    }
}
