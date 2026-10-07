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

    void exit()
    {
        m_source.stopProcess();
        m_engine.stop();

        saveConfig();
        AppConfig.saveColors();
        System.exit(0);
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

        pane.add(getOptionPanel(), BorderLayout.NORTH);
        pane.add(getBookmarkPanel(), BorderLayout.WEST);
        pane.add(getStatusPanel(), BorderLayout.SOUTH);
        pane.add(getTabPanel(), BorderLayout.CENTER);

        setDnDListener();
        addChangeListener();
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
        for(int nIndex = 0; nIndex < LogColor.HIGHLIGHT_COUNT; nIndex++)
        {
            m_arTfHighlight[nIndex].setText(config.arHighlight[nIndex]);
            m_arCbHighlightColor[nIndex].setSelectedIndex(config.arHighlightColor[nIndex]);
            m_arChkHighlight[nIndex].setSelected(config.arHighlightOn[nIndex]);
            updateHighlight(nIndex);
        }
        for(int nIndex = 0; nIndex < LogFilterTableModel.COMUMN_MAX; nIndex++)
            LogFilterTableModel.setColumnWidth(nIndex, config.arColumnWidth[nIndex]);
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
        for(int nIndex = 0; nIndex < LogColor.HIGHLIGHT_COUNT; nIndex++)
        {
            m_config.arHighlight[nIndex]      = m_arTfHighlight[nIndex].getText();
            m_config.arHighlightColor[nIndex] = m_arCbHighlightColor[nIndex].getSelectedIndex();
            m_config.arHighlightOn[nIndex]    = m_arChkHighlight[nIndex].isSelected();
        }
        for(int nIndex = 0; nIndex < LogFilterTableModel.COMUMN_MAX; nIndex++)
            m_config.arColumnWidth[nIndex] = m_tbLogTable.getColumnWidth(nIndex);
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
        addDesc("F2 : pre bookmark");
        addDesc("F3 : next bookmark");
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
    public void onStatus(String strText)    { setStatus(strText); }
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

        m_chkEnableFind.addItemListener(m_itemListener);
        m_chkEnableRemove.addItemListener(m_itemListener);
        m_chkEnableShowPid.addItemListener(m_itemListener);
        m_chkEnableShowTid.addItemListener(m_itemListener);
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

        // 히스토리 탐색을 시작하기 전에 입력창에 있던 값. ↓로 맨 앞을 지나면 이 값으로 돌아온다.
        final String[] draft = { "" };

        // ↑ : 더 예전 입력값으로 이동
        comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "HistoryPrev");
        comp.getActionMap().put("HistoryPrev", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                if(history.isEmpty())
                    return;
                if(cursor[0] == -1)
                {
                    draft[0] = comp.getText();
                    // 지금 값이 가장 최근 기록과 같으면 그 다음 것부터 보여준다.
                    cursor[0] = history.get(0).equals(draft[0]) && history.size() > 1 ? 1 : 0;
                }
                else if(cursor[0] < history.size() - 1)
                    cursor[0]++;
                comp.setText(history.get(cursor[0]));
                comp.setCaretPosition(comp.getDocument().getLength());
            }
        });

        // ↓ : 더 최근 입력값으로 이동. 맨 앞을 지나면 탐색 전 값으로 돌아간다. (탐색 중이 아니면 아무 동작 안 함)
        comp.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "HistoryNext");
        comp.getActionMap().put("HistoryNext", new AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(ActionEvent e)
            {
                if(cursor[0] == -1)
                    return;
                cursor[0]--;
                comp.setText(cursor[0] == -1 ? draft[0] : history.get(cursor[0]));
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

        // 필터 편집창에 최근 입력값 ↑/↓ 불러오기 기능 추가
        installInputHistory(m_tfFindWord);
        installInputHistory(m_tfRemoveWord);
        installInputHistory(m_tfShowTag);
        installInputHistory(m_tfRemoveTag);
        installInputHistory(m_tfShowPid);
        installInputHistory(m_tfShowTid);

        JPanel jpMain = new JPanel(new BorderLayout());

        JPanel jpWordFilter = new JPanel(new BorderLayout());
        jpWordFilter.setBorder(BorderFactory.createTitledBorder("Word filter"));

        JPanel jpFind = new JPanel(new BorderLayout());
        JLabel find = new JLabel();
        find.setText("Find :  ");
        jpFind.add(checkAndLabel(m_chkEnableFind, find), BorderLayout.WEST);
        jpFind.add(m_tfFindWord, BorderLayout.CENTER);

        JPanel jpRemove = new JPanel(new BorderLayout());
        JLabel remove = new JLabel();
        remove.setText("Remove :  ");
        jpRemove.add(checkAndLabel(m_chkEnableRemove, remove), BorderLayout.WEST);
        jpRemove.add(m_tfRemoveWord, BorderLayout.CENTER);

        jpWordFilter.add(jpFind, BorderLayout.NORTH);
        jpWordFilter.add(jpRemove);

        jpMain.add(jpWordFilter, BorderLayout.NORTH);

        JPanel jpTagFilter = new JPanel(new GridLayout(4, 1));
        jpTagFilter.setBorder(BorderFactory.createTitledBorder("Tag filter"));

        JPanel jpPid = new JPanel(new BorderLayout());
        JLabel pid = new JLabel();
        pid.setText("Pid :  ");
        jpPid.add(checkAndLabel(m_chkEnableShowPid, pid), BorderLayout.WEST);
        jpPid.add(m_tfShowPid, BorderLayout.CENTER);

        JPanel jpTid = new JPanel(new BorderLayout());
        JLabel tid = new JLabel();
        tid.setText("Tid :  ");
        jpTid.add(checkAndLabel(m_chkEnableShowTid, tid), BorderLayout.WEST);
        jpTid.add(m_tfShowTid, BorderLayout.CENTER);

        JPanel jpShow = new JPanel(new BorderLayout());
        JLabel show = new JLabel();
        show.setText("Show :  ");
        jpShow.add(checkAndLabel(m_chkEnableShowTag, show), BorderLayout.WEST);
        jpShow.add(m_tfShowTag, BorderLayout.CENTER);

        JPanel jpRemoveTag = new JPanel(new BorderLayout());
        JLabel removeTag = new JLabel();
        removeTag.setText("Remove :  ");
        jpRemoveTag.add(checkAndLabel(m_chkEnableRemoveTag, removeTag), BorderLayout.WEST);
        jpRemoveTag.add(m_tfRemoveTag, BorderLayout.CENTER);

        // 라벨을 모두 가장 긴 라벨 폭으로 맞추고 오른쪽 정렬해, 입력창이 같은 위치에서 시작하게 한다.
        alignLabels(find, remove, pid, tid, show, removeTag);

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
            installInputHistory(m_arTfHighlight[nIndex]);

            JPanel jpLeft = new JPanel(new BorderLayout());
            jpLeft.add(m_arChkHighlight[nIndex], BorderLayout.WEST);
            jpLeft.add(m_arCbHighlightColor[nIndex], BorderLayout.CENTER);

            JPanel jpRow = new JPanel(new BorderLayout(2, 0));
            jpRow.add(jpLeft, BorderLayout.WEST);
            jpRow.add(m_arTfHighlight[nIndex], BorderLayout.CENTER);
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

        JPanel jpLogFilter = new JPanel();
        jpLogFilter.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
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
        return jpMain;
    }

    Component getOptionFilter()
    {
        JPanel optionFilter = new JPanel(new BorderLayout());

        optionFilter.add(getCmdPanel(), BorderLayout.WEST);
        optionFilter.add(getCheckPanel(), BorderLayout.EAST);
        // 가운데: 왼쪽 Word/Tag filter, 오른쪽 Highlight (둘 다 6줄이라 높이가 맞음)
        JPanel jpCenter = new JPanel(new GridLayout(1, 2));
        jpCenter.add(getFilterPanel());
        jpCenter.add(getHighlightPanel());
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
        m_tmLogTableModel = new LogFilterTableModel();
        m_tmLogTableModel.setData(m_engine.getView().arList);
        m_tbLogTable = new LogTable(m_tmLogTableModel, this);
        m_tbLogTable.setFilterEngine(m_engine);
        m_scrollVBar = new JScrollPane(m_tbLogTable);
        return m_scrollVBar;
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
        m_engine.requestFilter();
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
                m_engine.setFind(strText);
            else if(doc.equals(m_tfRemoveWord.getDocument()) && m_chkEnableRemove.isSelected())
                m_engine.setRemove(strText);
            else if(doc.equals(m_tfShowPid.getDocument()) && m_chkEnableShowPid.isSelected())
                m_engine.setShowPid(strText);
            else if(doc.equals(m_tfShowTid.getDocument()) && m_chkEnableShowTid.isSelected())
                m_engine.setShowTid(strText);
            else if(doc.equals(m_tfShowTag.getDocument()) && m_chkEnableShowTag.isSelected())
                m_engine.setShowTag(strText);
            else if(doc.equals(m_tfRemoveTag.getDocument()) && m_chkEnableRemoveTag.isSelected())
                m_engine.setRemoveTag(strText);

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
