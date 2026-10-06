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
    static final String       VERSION                    = "Version 1.8";
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
        m_tfHighlight.setText(config.strHighlight);
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
        m_config.strHighlight = m_tfHighlight.getText();
        for(int nIndex = 0; nIndex < LogFilterTableModel.COMUMN_MAX; nIndex++)
            m_config.arColumnWidth[nIndex] = m_tbLogTable.getColumnWidth(nIndex);
        m_config.save();
    }
    void addDesc(String strMessage)
    {
        LogInfo logInfo = new LogInfo();
        logInfo.m_strMessage = strMessage;
        m_engine.addNext(logInfo);
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
                for(Object item : arItem)
                    listModel.addElement(item);
                m_btnDevice.setEnabled(true);
                setStatus(arItem.isEmpty() ? "No device" : "ready");
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
        m_source.startProcess(getProcessCmd(), "UTF-8".equals(m_comboEncode.getSelectedItem()));
        setProcessBtn(true);
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
                if(selectedItem != null)
                    m_strSelectedDevice = selectedItem.toString().replace("\t", " ").replace("device", "").replace("offline", "");
            }
        });
        jpOptionDevice.add(vbar);

        return jpOptionDevice;
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
        installInputHistory(m_tfHighlight);

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
        if(checkBox.equals(m_chkEnableHighlight))
        {
            // 하이라이트는 표시만 바뀌므로 재필터 없이 다시 그린다.
            m_tbLogTable.SetHighlight(checkBox.isSelected() ? m_tfHighlight.getText() : "");
            m_tbLogTable.repaint();
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
        if(m_lDeviceList.getSelectedIndex() < 0)
            return ADB_CMD_FIRST + m_comboCmd.getSelectedItem();
        else
            return ADB_SELECTED_CMD_FIRST + m_strSelectedDevice + m_comboCmd.getSelectedItem();
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
                    || check.equals(m_chkEnableHighlight))
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
