import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

/**
 * 장치 프로퍼티 창 (1.44): adb shell getprop 결과를 Name / Value 표로 보여 준다.
 * - 실시간: 정한 주기마다 다시 읽고, 이전에 읽은 값과 달라진 줄은 다음 갱신까지 연두색으로 칠하고
 *   마지막 Changed 열에 바뀐 시각과 '이전 값 → 새 값'을 보인다 (1.50). 바뀐 줄은 표 맨 위에 모은다 (1.51). 필터와 일치하는 글자는 노란 바탕 (1.47).
 * - UP File 탭 (1.52): 장치의 폴더(기본 /LGU_data/UP, 경로 입력·설정에 저장) 안 파일들을 읽어 파일 이름 = 값(key=value 파일은 파일 / key)으로 같은 표에 보여 주고,
 *   프로퍼티 탭과 같은 주기로 다시 읽어 바뀐 줄을 연두색·맨 위·Changed 열로 보인다.
 * - Reload · 저장(파일) · 열기(저장한 파일 보기) · Edit(선택한 프로퍼티 값 바꾸기, setprop) · Add(새 프로퍼티, setprop) · Filter
 * - adb 오류(장치 없음 등)는 표에 그대로 보여 준다.
 */
public class DevicePropDialog extends JDialog
{
    private static final long serialVersionUID = 1L;
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final Pattern PROP_LINE = Pattern.compile("^\\[(.*?)\\]: \\[(.*)\\]\\s*$");
    static final Color   COLOR_CHANGED = new Color(0xCCFF90);     // 마지막 갱신에서 바뀐 값 (연두색)
    static final String  MARK_BG       = "#FFEB3B";               // 필터 일치 표시 (노랑)

    /**
     * 글자에서 필터 단어(소문자, 대소문자 무시)와 일치하는 부분을 노란 바탕으로 칠한 HTML. 일치가 없으면 null.
     * 겹치거나 붙은 일치는 하나로 합친다.
     */
    static String markMatches(String s, String[] arToken)
    {
        String lower = s.toLowerCase();
        boolean[] mark = new boolean[s.length()];
        boolean bAny = false;
        for(String t : arToken)
        {
            if(t.length() == 0) continue;
            for(int i = lower.indexOf(t); i >= 0; i = lower.indexOf(t, i + 1))
            {
                for(int k = i; k < i + t.length() && k < mark.length; k++) mark[k] = true;
                bAny = true;
            }
        }
        if(!bAny) return null;
        StringBuilder sb = new StringBuilder("<html><nobr>");
        boolean bIn = false;
        for(int i = 0; i < s.length(); i++)
        {
            if(mark[i] && !bIn) { sb.append("<span style='background-color:").append(MARK_BG).append("'>"); bIn = true; }
            if(!mark[i] && bIn) { sb.append("</span>"); bIn = false; }
            char c = s.charAt(i);
            switch(c)
            {
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '&':  sb.append("&amp;");  break;
                case '"':  sb.append("&quot;"); break;
                case ' ':  sb.append(i > 0 && s.charAt(i - 1) == ' ' ? "&nbsp;" : " "); break;
                case '\n': sb.append(' '); break;
                default:   sb.append(c);
            }
        }
        if(bIn) sb.append("</span>");
        return sb.append("</nobr></html>").toString();
    }

    final LogFilterMain m_main;
    final JLabel        m_lbDevice  = new JLabel("-");
    final JLabel        m_lbSource  = new JLabel("[ Current Device ]");
    final JLabel        m_lbInfo    = new JLabel(" ");
    final JTextField    m_tfFilter  = new JTextField(14);
    final IntervalField m_interval;                  // 실시간 갱신 주기 (1.46: 기본 10초, 사용자가 입력)
    final RootControl   m_root;                      // root 실행 버튼 (1.48)
    final PropModel     m_model     = new PropModel();
    final JTable        m_table     = new JTable(m_model);
    // UP 파일 탭 (1.52)
    final PropModel     m_upModel   = new PropModel();
    final JTable        m_upTable   = new JTable(m_upModel);
    final JTextField    m_tfUpPath  = new JTextField(30);
    final javax.swing.JTabbedPane m_tabs = new javax.swing.JTabbedPane();
    final JPanel        m_jpUp      = new JPanel(new BorderLayout());     // UP File 탭 (/LGU_data가 있을 때만 붙음)
    volatile boolean    m_bUpLoading;
    int                 m_nUpRequest;               // 늦게 끝난 이전 읽기(경로를 바꾸기 전)를 버리기 위해 (EDT에서만)
    String              m_strUpRead;                // 마지막으로 읽은 경로
    Map<String, UpRef>  m_upRefs = new HashMap<String, UpRef>();   // 줄 이름 → 장치 파일·키 (수정용, 1.54)
    String              m_strUpMsg;                 // UP 탭 안내 줄 뒤에 붙이는 마지막 수정 결과
    long                m_nUpReadAt;
    JButton             m_btnOpen, m_btnEdit, m_btnAdd;
    final Timer         m_upWatch;
    final Timer         m_timer;
    volatile boolean    m_bLoading;
    File                m_fileShown;                 // 파일을 열어 보는 중이면 그 파일 (null이면 장치)

    DevicePropDialog(LogFilterMain main)
    {
        super(main, "장치 프로퍼티", false);
        m_main = main;

        // ---- 툴바 ----
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        JLabel lbDev = new JLabel("Device : ");
        lbDev.setFont(lbDev.getFont().deriveFont(Font.BOLD));
        m_lbDevice.setFont(m_lbDevice.getFont().deriveFont(Font.BOLD));
        bar.add(lbDev);
        bar.add(m_lbDevice);
        bar.add(button("Reload", "장치에서 다시 읽기 (F5)", e -> reload()));
        bar.add(button("저장…", "지금 보이는 프로퍼티를 파일로 저장", e -> save()));
        bar.add(m_btnOpen = button("열기…", "저장한 프로퍼티 파일 보기 (실시간 꺼짐)", e -> open()));
        bar.add(m_btnEdit = button("Edit", "선택한 프로퍼티 값 바꾸기 (adb shell setprop)", e -> edit()));
        bar.add(m_btnAdd  = button("Add", "새 프로퍼티 추가 (adb shell setprop)", e -> add()));
        // root 실행 (1.48): adb root 후 다시 읽는다
        m_root = new RootControl(main, this, () -> reload(), s -> m_lbInfo.setText(s));
        bar.add(m_root);
        bar.add(new JLabel("  Filter : "));
        m_tfFilter.setToolTipText("이름이나 값에 들어 있는 글자 (대소문자 무시, 여러 개는 | 로)");
        m_tfFilter.getDocument().addDocumentListener(new DocumentListener()
        {
            public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        bar.add(m_tfFilter);
        bar.add(new JLabel("   "));
        m_interval = new IntervalField("자동 갱신", main.m_nPropIntervalSec, main.m_bPropAuto, () -> { if(isVisible() && m_fileShown == null) reload(); });
        m_interval.m_chk.setToolTipText("정한 주기마다 장치에서 다시 읽기 (바뀐 줄은 다음 갱신까지 연두색, Changed 열에 이전 값 → 새 값)");
        m_interval.m_onChange = () -> { main.m_nPropIntervalSec = m_interval.seconds(); main.m_bPropAuto = m_interval.isOn(); updateInfo(); };

        m_lbSource.setFont(m_lbSource.getFont().deriveFont(Font.BOLD, 13f));
        m_lbSource.setForeground(new Color(0x1A237E));
        m_lbSource.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        // 제목 줄: [ Current Device ]  [v 자동 갱신] [10] 초  (프로세스 창의 제목 줄과 같은 배치)
        JPanel jpTitle = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        jpTitle.add(m_lbSource);
        jpTitle.add(m_interval);
        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.NORTH);
        north.add(jpTitle, BorderLayout.SOUTH);

        // ---- 표 ----
        m_model.m_tbl = m_table;
        m_upModel.m_tbl = m_upTable;
        setupTable(m_table, m_model);
        setupTable(m_upTable, m_upModel);
        m_table.addMouseListener(new java.awt.event.MouseAdapter()
        {
            public void mouseClicked(java.awt.event.MouseEvent e) { if(e.getClickCount() == 2) edit(); }
        });
        m_upTable.addMouseListener(new java.awt.event.MouseAdapter()
        {
            public void mouseClicked(java.awt.event.MouseEvent e) { if(e.getClickCount() == 2) editUp(); }
        });
        JScrollPane sp = new JScrollPane(m_table);

        // ---- UP 탭 (1.52): 장치의 폴더(기본 /LGU_data/UP) 안 파일들(또는 파일 하나)을 프로퍼티 탭과 같은 표로 ----
        JPanel jpUpPath = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        JLabel lbUp = new JLabel("장치 경로 : ");
        lbUp.setFont(lbUp.getFont().deriveFont(Font.BOLD));
        jpUpPath.add(lbUp);
        m_tfUpPath.setText(main.m_strUpPath == null ? UP_DEFAULT_PATH : main.m_strUpPath);
        m_tfUpPath.setToolTipText("장치의 폴더(기본 " + UP_DEFAULT_PATH + ") 또는 파일 경로, Enter로 읽기 — 설정에 저장");
        m_tfUpPath.addActionListener(e -> applyUpPath());
        jpUpPath.add(m_tfUpPath);
        jpUpPath.add(button("읽기", "수정 시간을 확인해 바뀐 파일만 장치에서 다시 읽기", e -> applyUpPath()));
        JLabel lbUpHint = new JLabel("폴더면 안의 파일 모두 (파일 = 값, key=value 파일은 파일 / key)");
        lbUpHint.setToolTipText("값 하나짜리 파일은 파일 이름 = 내용, 두 줄 이상 key=value 파일은 파일 / key 줄마다. 64KB보다 큰 파일은 크기만. 자동 갱신이 켜져 있으면 1초마다 수정 시간을 확인해 바뀐 파일만 다시 읽음");
        lbUpHint.setForeground(Color.GRAY);
        jpUpPath.add(lbUpHint);
        m_jpUp.add(jpUpPath, BorderLayout.NORTH);
        m_jpUp.add(new JScrollPane(m_upTable), BorderLayout.CENTER);

        // UP File 탭은 장치에 /LGU_data 폴더가 있는 것을 확인한 뒤에 붙인다 (reloadUp)
        m_tabs.addTab("Property", sp);
        m_tabs.addChangeListener(e -> { updateButtons(); updateInfo(); });

        m_lbInfo.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        m_lbInfo.setForeground(Color.DARK_GRAY);

        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(m_tabs, BorderLayout.CENTER);
        getContentPane().add(m_lbInfo, BorderLayout.SOUTH);
        setSize(new Dimension(980, 640));
        setLocationRelativeTo(main);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        // F5 다시 읽기, Ctrl+F 필터, Esc 닫기, Ctrl+C 선택 줄 복사
        bindKey(java.awt.event.KeyEvent.VK_F5, 0, "Reload", this::reload);
        bindKey(java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK, "Filter", () -> { m_tfFilter.requestFocusInWindow(); m_tfFilter.selectAll(); });
        bindKey(java.awt.event.KeyEvent.VK_ESCAPE, 0, "Close", () -> setVisible(false));
        // Home/End: 표에 포커스가 없어도 (버튼·탭 등) 지금 탭의 첫·마지막 줄로. 입력창에서는 입력창의 Home/End (1.55)
        bindKey(java.awt.event.KeyEvent.VK_HOME, 0, "FirstRow", () -> goRow(currentTable(), 0));
        bindKey(java.awt.event.KeyEvent.VK_END, 0, "LastRow", () -> goRow(currentTable(), currentTable().getRowCount() - 1));

        // 1초마다 다시 그린다 (진행 표시 등)
        m_timer = new Timer(1000, e -> { if(isVisible()) { m_table.repaint(); m_upTable.repaint(); } });
        m_timer.setRepeats(true);
        // UP 파일 변경 확인 (1.55): 자동 갱신이 켜져 있고 UP 탭이 있으면 1초마다 수정 시간 확인
        // 1.56: 장치에서 0.2초마다 확인하는 감시(adb shell 하나)를 띄우고, 안 되면 1초마다 직접 확인
        m_upWatch = new Timer(UP_WATCH_MS, e -> upWatchTick());
        m_upWatch.setInitialDelay(0);
        m_upWatch.setRepeats(true);
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopUpWatcher, "UpWatchStop"));     // 프로그램이 끝나면 adb도 끝낸다
        updateButtons();
    }

    // 프로퍼티 탭·UP 파일 탭 표 공통: 열 너비, 연두색(바뀐 줄), Changed 열, 필터 일치 노란 바탕
    void setupTable(JTable table, final PropModel model)
    {
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        table.getColumnModel().getColumn(1).setPreferredWidth(420);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);     // Changed (1.50)
        table.setDefaultRenderer(Object.class, new WrapRenderer(model));
        // Home / End : 첫 줄 / 마지막 줄로 (Ctrl+Home·End도 같음, 1.55)
        javax.swing.InputMap im = table.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        for(int nMod : new int[]{ 0, java.awt.event.InputEvent.CTRL_DOWN_MASK })
        {
            im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_HOME, nMod), "klogFirstRow");
            im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_END, nMod), "klogLastRow");
        }
        table.getActionMap().put("klogFirstRow", new javax.swing.AbstractAction() { private static final long serialVersionUID = 1L; public void actionPerformed(java.awt.event.ActionEvent e) { goRow(table, 0); } });
        table.getActionMap().put("klogLastRow", new javax.swing.AbstractAction() { private static final long serialVersionUID = 1L; public void actionPerformed(java.awt.event.ActionEvent e) { goRow(table, table.getRowCount() - 1); } });
        // 열 너비가 바뀌면 줄 높이를 다시 맞춘다 (1.53)
        table.getColumnModel().addColumnModelListener(new javax.swing.event.TableColumnModelListener()
        {
            public void columnMarginChanged(javax.swing.event.ChangeEvent e) { fitRowHeightsLater(table); }
            public void columnAdded(javax.swing.event.TableColumnModelEvent e) { }
            public void columnRemoved(javax.swing.event.TableColumnModelEvent e) { }
            public void columnMoved(javax.swing.event.TableColumnModelEvent e) { }
            public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) { }
        });
    }

    // 표의 그 줄을 선택하고 보이게 (Home/End)
    static void goRow(JTable t, int nRow)
    {
        if(nRow < 0 || nRow >= t.getRowCount()) return;
        t.setRowSelectionInterval(nRow, nRow);
        t.scrollRectToVisible(t.getCellRect(nRow, 0, true));
        t.requestFocusInWindow();
    }

    JTable currentTable() { return upSelected() ? m_upTable : m_table; }

    static final int ROW_MAX_LINES = 40;     // 한 줄(행)이 너무 커지지 않도록 이 줄 수까지만 높인다

    /**
     * 셀 렌더러 (1.53): 칸 너비에 맞춰 줄바꿈해서 값 전체를 보인다 (여러 줄 값은 줄마다).
     * 바뀐 줄 연두색 (1.50), 필터 일치 노란 바탕 (1.47, Name·Value 열), 바뀐 내용 툴팁.
     */
    class WrapRenderer extends javax.swing.JTextArea implements javax.swing.table.TableCellRenderer
    {
        private static final long serialVersionUID = 1L;
        final PropModel m_m;
        final javax.swing.text.Highlighter.HighlightPainter m_paint = new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(Color.decode(MARK_BG));

        WrapRenderer(PropModel model)
        {
            m_m = model;
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        }

        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col)
        {
            Prop p = m_m.row(row);
            String s = v == null ? "" : v.toString();
            setFont(t.getFont());
            setText(s);
            if(sel) { setBackground(t.getSelectionBackground()); setForeground(t.getSelectionForeground()); }
            else
            {
                setBackground(p != null && p.m_nChanged > 0 ? COLOR_CHANGED : Color.WHITE);
                setForeground(p != null && p.m_bError ? new Color(0xC62828) : col == 2 ? new Color(0x33691E) : Color.BLACK);
            }
            setToolTipText(p == null || p.m_nChanged == 0 ? null : changeText(p));
            getHighlighter().removeAllHighlights();
            if(col < 2 && p != null && !p.m_bError && m_m.m_arFilter.length > 0)
            {
                String lower = s.toLowerCase();
                for(String tok : m_m.m_arFilter)
                    if(tok.length() > 0)
                        for(int i = lower.indexOf(tok); i >= 0; i = lower.indexOf(tok, i + 1))
                            try { getHighlighter().addHighlight(i, i + tok.length(), m_paint); } catch(javax.swing.text.BadLocationException e) { }
            }
            return this;
        }
    }

    // 줄마다 가장 높은 칸에 맞춰 높이를 정한다 (값이 칸에 다 보이도록, 최대 ROW_MAX_LINES 줄)
    static void fitRowHeights(JTable t)
    {
        int nLine = t.getFontMetrics(t.getFont()).getHeight();
        int nBase = nLine + 2, nMax = nLine * ROW_MAX_LINES + 2;
        int nCols = t.getColumnCount();
        for(int r = 0; r < t.getRowCount(); r++)
        {
            int h = nBase;
            for(int c = 0; c < nCols; c++)
            {
                int w = t.getColumnModel().getColumn(c).getWidth();
                if(w <= 0) continue;
                Component comp = t.prepareRenderer(t.getCellRenderer(r, c), r, c);
                comp.setSize(w, Short.MAX_VALUE);
                h = Math.max(h, comp.getPreferredSize().height);
            }
            h = Math.min(h, nMax);
            if(t.getRowHeight(r) != h) t.setRowHeight(r, h);
        }
    }

    final java.util.Set<JTable> m_fitPending = new java.util.HashSet<JTable>();

    void fitRowHeightsLater(final JTable t)
    {
        if(!m_fitPending.add(t)) return;
        SwingUtilities.invokeLater(() -> { m_fitPending.remove(t); fitRowHeights(t); });
    }

    boolean upSelected() { return m_tabs.getSelectedComponent() == m_jpUp; }

    // Edit·Add·열기는 프로퍼티 탭에서만
    void updateButtons()
    {
        boolean b = !upSelected();
        m_btnOpen.setEnabled(b);
        m_btnAdd.setEnabled(b);
        // Edit는 두 탭 모두 (UP File 탭은 장치 파일 수정, 1.54)
        m_btnEdit.setToolTipText(b ? "선택한 프로퍼티 값 바꾸기 (adb shell setprop)" : "선택한 줄의 값을 장치 파일에 고쳐 쓰기 (값 하나짜리 파일은 파일 내용 전체, key=value 파일은 그 줄의 값)");
    }

    void applyFilter()
    {
        m_model.setFilter(m_tfFilter.getText());
        m_upModel.setFilter(m_tfFilter.getText());
        updateInfo();
    }

    JButton button(String strText, String strTip, java.awt.event.ActionListener l)
    {
        JButton b = new JButton(strText);
        b.setMargin(new java.awt.Insets(1, 6, 1, 6));
        b.setToolTipText(strTip);
        b.addActionListener(l);
        return b;
    }

    void bindKey(int nKey, int nMod, String strName, Runnable r)
    {
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(nKey, nMod), strName);
        getRootPane().getActionMap().put(strName, new javax.swing.AbstractAction() { private static final long serialVersionUID = 1L; public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); } });
    }

    // 창을 연다: 장치를 다시 읽고 실시간 갱신을 시작한다
    void showFor()
    {
        m_fileShown = null;
        m_lbSource.setText("[ Current Device ]");
        // 이 장치에 /LGU_data가 있다고 알고 있으면 UP File 탭을 바로 붙인다 (확인은 reload에서 다시)
        Boolean bLgu = m_lguKnown.get(device().length() > 0 ? device() : m_strSerial == null ? "" : m_strSerial);
        if(bLgu != null) setUpTabVisible(bLgu);
        setVisible(true);
        toFront();
        reload();
        applyLive();
        m_root.checkState();
    }

    public void setVisible(boolean b)
    {
        super.setVisible(b);
        if(m_interval == null) return;
        if(b) { m_timer.start(); m_upWatch.start(); } else { m_timer.stop(); m_upWatch.stop(); stopUpWatcher(); }
        applyLive();
    }

    // 창이 보이고 장치를 보는 중일 때만 정한 주기로 다시 읽는다 (파일을 보는 동안은 멈춤)
    void applyLive()
    {
        if(isVisible() && m_fileShown == null) m_interval.start();
        else m_interval.stop();
    }

    String device()
    {
        String s = m_main.m_strSelectedDevice;
        return s == null ? "" : s.trim();
    }

    // ---- 읽기 ----

    int m_nRequest;     // 읽기 요청 번호: 늦게 끝난 이전 읽기 결과가 새 화면(파일 보기 등)을 덮지 않도록 (EDT에서만)

    void reload()
    {
        if(m_fileShown != null)
        {
            // 파일을 보고 있었으면 장치 보기로 돌아온다 (읽는 중이어도 바로)
            m_fileShown = null;
            m_lbSource.setText("[ Current Device ]");
            m_model.clearChanged();
            applyLive();
            m_bLoading = false;
        }
        reloadUp();                     // UP 파일 탭도 같은 주기로 (1.52)
        if(m_bLoading) return;          // 실시간 갱신이 아직 읽는 중이면 그 결과를 쓴다
        final String strDevice = device();
        m_lbDevice.setText(strDevice.length() > 0 ? strDevice : "(adb 기본 장치" + (m_strSerial != null ? " : " + m_strSerial : "") + ")");
        m_bLoading = true;
        final int nRequest = ++m_nRequest;
        new Thread(() ->
        {
            final String[] r = runAdb(strDevice, 8000, "shell", "getprop");
            SwingUtilities.invokeLater(() ->
            {
                if(nRequest != m_nRequest || m_fileShown != null) return;     // 그 사이 파일을 열었거나 새로 읽기 시작함
                m_bLoading = false;
                if(r[1] != null)
                    m_model.setError(r[1]);
                else
                {
                    Map<String, String> props = parse(r[0]);
                    if(props.isEmpty())
                        m_model.setError(r[0].trim().length() > 0 ? r[0].trim() : "프로퍼티를 읽지 못했습니다");
                    else
                    {
                        onSerial(serialOf(props), true);     // 다른 장치면 이전 장치 값과 비교하지 않는다 (1.57)
                        m_model.update(props, true);
                    }
                }
                updateInfo();
            });
        }, "DeviceProp").start();
    }

    // ---- UP 파일 탭 (1.52) ----
    // 1.55: 수정 시간·크기로 바뀐 파일만 다시 읽음, 1초마다 확인해 바뀌면 바로 표시, 탭은 창을 열 때 바로

    static class UpFile
    {
        final long   m_nTime, m_nSize;      // 장치의 수정 시각(초)·크기
        final String m_strContent;          // 내용 (큰 파일은 "")
        UpFile(long t, long s, String c) { m_nTime = t; m_nSize = s; m_strContent = c; }
    }

    // 읽기 결과 (스레드 → EDT)
    static class UpResult
    {
        String               m_strError;
        String               m_strSerial;    // 읽은 장치 (1.57)
        boolean              m_bChanged;     // 표를 바꿀 내용이 있음
        int                  m_nRead;        // 이번에 내용을 읽은 파일 수
        Map<String, String>  m_map = new LinkedHashMap<String, String>();
        Map<String, UpRef>   m_refs = new HashMap<String, UpRef>();
    }

    // 아래 캐시는 읽기 스레드만 쓴다 (m_bUpLoading으로 한 번에 하나)
    final Map<String, UpFile> m_upCache = new HashMap<String, UpFile>();
    String  m_strUpCacheKey = "";          // 캐시가 어느 장치·경로 것인지
    long    m_nUpPrevNow;                  // 직전 목록을 읽은 장치 시각(초): 같은 초 안에 또 바뀐 파일을 놓치지 않도록
    final java.util.Set<String> m_upDirty = java.util.concurrent.ConcurrentHashMap.newKeySet();    // 고쳐 쓴 파일: 다음 읽기에서 꼭 다시 읽음
    volatile boolean m_bUpReset;           // 경로를 바꿈·root 아님·오류: 다음 읽기에서 모두 다시
    boolean m_bUpAgain;                    // 읽는 중에 다시 읽기를 요청함
    final Map<String, Boolean> m_lguKnown = new HashMap<String, Boolean>();   // 장치별 /LGU_data 있음 — 창을 열 때 탭을 바로 보이게
    static final Pattern STAT_LINE = Pattern.compile("^(\\d+) (\\d+) (.+)$");
    static final int UP_WATCH_MS = 1000;   // UP 파일 변경 확인 주기

    // 경로 입력 Enter·읽기 버튼: 경로를 설정에 남기고 읽는다. 같은 경로면 수정 시간이 바뀐 파일만 다시 읽음
    void applyUpPath()
    {
        String strPath = m_tfUpPath.getText().trim();
        if(!strPath.equals(m_strUpRead))
        {
            m_upModel.m_all = new ArrayList<Prop>();
            m_upModel.m_bErrorState = false;
            m_upModel.m_nLastChanged = 0;
            m_bUpReset = true;
        }
        m_main.m_strUpPath = strPath;
        reloadUp();
    }


    // 장치의 UP 파일을 adb shell cat으로 읽어 표에 (바뀐 줄 연두색·맨 위, 프로퍼티 탭과 같음)
    // 장치에 /LGU_data 폴더가 있을 때만 UP File 탭을 보인다 (1.52). 읽을 때마다 확인 (장치가 바뀌거나 재부팅해도 맞게)
    static final String LGU_DIR = "/LGU_data";
    static final String LGU_CHECK = "[ -d " + LGU_DIR + " ] && echo KLOG_LGU_YES || echo KLOG_LGU_NO";

    void setUpTabVisible(boolean b)
    {
        int n = m_tabs.indexOfComponent(m_jpUp);
        if(b && n < 0) m_tabs.addTab("UP File", m_jpUp);
        else if(!b && n >= 0) m_tabs.remove(n);         // 보고 있었으면 Property 탭으로
    }

    boolean upTabVisible() { return m_tabs.indexOfComponent(m_jpUp) >= 0; }

    void reloadUp()
    {
        if(m_bUpLoading) { m_bUpAgain = true; return; }     // 읽는 중이면 끝난 뒤 한 번 더
        m_bUpLoading = true;
        final String strPath = m_tfUpPath.getText().trim();
        final String strDevice = device();
        final boolean bReset = m_bUpReset;
        m_bUpReset = false;
        new Thread(() ->
        {
            // 1) /LGU_data 있음·권한(id -u)·파일 수정 시간을 adb 한 번에 (1.56) → 탭은 바로 붙이고 권한 표시도 바로
            //    1.57: 실제로 연결된 장치의 시리얼도 함께 → 장치가 바뀌면 이전 장치의 캐시·표를 쓰지 않는다
            String[] c = runAdb(strDevice, 10000, "shell", LGU_CHECK + "; id -u; " + SERIAL_ECHO
                + (strPath.length() > 0 ? "; if [ -d " + LGU_DIR + " ]; then " + upStatCommand(strPath) + "; fi" : ""));
            int nStat = c[1] == null ? c[0].indexOf("KLOG_NOW ") : -1;
            final String strStat = nStat >= 0 ? c[0].substring(nStat) : null;
            if(nStat >= 0) c[0] = c[0].substring(0, nStat);
            final boolean bLgu = c[1] == null && c[0].contains("KLOG_LGU_YES");
            Boolean bRoot = null;
            String sn = null;
            if(c[1] == null)
                for(String l : c[0].split("\n"))
                {
                    String s = l.trim();
                    if(s.matches("\\d+")) bRoot = Boolean.valueOf(s.equals("0"));
                    else if(s.startsWith("KLOG_SN=")) sn = s.substring(8).trim();
                }
            final Boolean fRoot = bRoot;
            final String strSerial = sn;
            SwingUtilities.invokeLater(() ->
            {
                onSerial(strSerial, false);
                m_lguKnown.put(strSerial != null && strSerial.length() > 0 ? strSerial : strDevice, bLgu);
                setUpTabVisible(bLgu);
                if(fRoot != null) m_root.setState(fRoot);
            });
            // 2) 수정 시간이 바뀐 파일만 읽는다 (캐시는 실제 장치 시리얼 기준)
            final UpResult res = bLgu && strPath.length() > 0 ? readUpChanged(strDevice, strSerial, strPath, bReset, strStat) : null;
            if(res != null) res.m_strSerial = strSerial;
            SwingUtilities.invokeLater(() ->
            {
                m_bUpLoading = false;
                if(res != null && !sameSerial(res.m_strSerial, m_strSerial)) m_bUpAgain = true;     // 그 사이 장치가 또 바뀜: 버리고 다시
                else if(res != null && strPath.equals(m_tfUpPath.getText().trim()))
                    applyUpResult(strPath, res);
                else if(bLgu && strPath.length() == 0)
                {
                    m_upModel.setNotice("장치 폴더나 파일 경로를 입력하고 Enter (예: " + UP_DEFAULT_PATH + ")");
                    m_strUpRead = "";
                    if(upSelected()) updateInfo();      // 프로퍼티 탭의 안내(setprop 결과 등)를 덮지 않게
                }
                if(m_bUpAgain) { m_bUpAgain = false; reloadUp(); }
            });
        }, "DeviceUpFile").start();
    }

    // ---- 연결된 장치가 바뀌었는지 (1.57) ----
    // Device select에서 고르지 않으면(adb 기본 장치) 케이블만 바꿔도 같은 장치로 보이므로, 읽을 때마다 실제 시리얼을 받아 비교한다

    static final String SERIAL_ECHO = "s=$(getprop ro.serialno); [ -z $s ] && s=$(getprop ro.boot.serialno); echo KLOG_SN=$s";
    String m_strSerial;                 // 지금 표에 보이는 장치의 시리얼 (EDT)
    String m_strDevNotice;              // "장치가 바뀌었습니다" 안내
    long   m_nDevNoticeAt;
    static final long DEV_NOTICE_MS = 15000;

    static boolean sameSerial(String a, String b) { return a == null || b == null || a.length() == 0 || b.length() == 0 || a.equals(b); }

    /** getprop 결과에서 시리얼 */
    static String serialOf(Map<String, String> props)
    {
        String s = props.get("ro.serialno");
        if(s == null || s.length() == 0) s = props.get("ro.boot.serialno");
        return s;
    }

    // 읽은 장치의 시리얼: 이전과 다르면 이전 장치의 표·캐시·감시를 버린다
    void onSerial(String sn, boolean bFromProps)
    {
        if(sn == null || sn.length() == 0) return;
        if(m_strSerial != null && !m_strSerial.equals(sn))
        {
            // 새 장치를 처음 읽은 것처럼: 바뀐 줄 표시 없이 다시 채운다
            m_model.m_all = new ArrayList<Prop>();
            m_model.m_bErrorState = false;
            m_model.m_nLastChanged = 0;
            m_model.refilter();
            m_upModel.m_all = new ArrayList<Prop>();
            m_upModel.m_bErrorState = false;
            m_upModel.m_nLastChanged = 0;
            m_upModel.refilter();
            m_upRefs = new HashMap<String, UpRef>();
            m_strUpMsg = null;
            m_strUpRead = null;
            m_bUpReset = true;              // UP 캐시는 시리얼 기준이라 어차피 새로 읽지만, 확실히
            stopUpWatcher();                // 새 장치로 감시를 다시 띄운다 (다음 1초 타이머)
            m_strDevNotice = "장치가 바뀌었습니다 (" + m_strSerial + " → " + sn + ") — 새 장치에서 다시 읽음";
            m_nDevNoticeAt = System.currentTimeMillis();
            m_main.setStatus("장치 프로퍼티 : " + m_strDevNotice);
            m_bUpAgain = m_bUpLoading;      // 읽는 중이던 UP 결과는 이전 장치 것일 수 있다
            if(!m_bUpLoading) reloadUp();
            if(!bFromProps && m_fileShown == null) { m_bLoading = false; m_nRequest++; reload(); }     // UP 쪽에서 먼저 알았으면 프로퍼티도 새 장치로
        }
        m_strSerial = sn;
        String strDev = device();
        m_lbDevice.setText(strDev.length() > 0 ? strDev : "(adb 기본 장치 : " + sn + ")");
    }

    String devNotice() { return m_strDevNotice != null && System.currentTimeMillis() - m_nDevNoticeAt < DEV_NOTICE_MS ? "   —   " + m_strDevNotice : ""; }

    // ---- UP 폴더 변경 감시 (1.56) ----
    // adb shell 하나를 열어 두고 장치에서 0.2초마다 파일의 시각·크기·md5를 비교, 바뀌면 KLOG_CHANGED 한 줄 → 바로 다시 읽기.
    // 스크립트는 표준 입력으로 보낸다 (Windows에서 adb 인자로 넘기면 큰따옴표를 쓸 수 없으므로)

    static final String UP_CHANGED      = "KLOG_CHANGED";
    static final long   UP_SAFETY_MS    = 5000;        // 감시 중에도 이 간격으로는 직접 확인 (놓친 변경 대비)
    static final long   UP_WATCH_RETRY_MS = 30000;     // 감시가 바로 끝나 버리면(장치에 명령이 없는 등) 이 시간 동안은 1초 확인만
    volatile Process    m_upWatchProc;
    String              m_strUpWatchKey;
    long                m_nUpWatchStart, m_nUpWatchRetryAt, m_nUpLastPoll;

    static String upWatchScript(String strPath)
    {
        return "d=" + shq(strPath) + "\n"
             + "p=\n"
             + "while :; do\n"
             + "  c=$(find $d -type f -exec stat -c '%Y %s %n' {} + 2>&1; find $d -type f -exec md5sum {} + 2>/dev/null)\n"
             + "  if [ \"$c\" != \"$p\" ]; then echo " + UP_CHANGED + "; p=$c; fi\n"
             + "  sleep 0.2 2>/dev/null || sleep 1\n"
             + "done\n";
    }

    boolean upWatching() { Process p = m_upWatchProc; return p != null && p.isAlive(); }

    // 1초 타이머: 감시가 필요하면 켜고(장치·경로가 바뀌면 새로), 필요 없으면 끈다. 감시가 없으면 1초마다 직접 확인
    void upWatchTick()
    {
        String strPath = m_tfUpPath.getText().trim();
        boolean bNeed = isVisible() && m_fileShown == null && m_interval.isOn() && upTabVisible();
        String strKey = device() + "#" + m_strSerial + "|" + strPath;      // 실제 장치가 바뀌면 감시도 새로 (1.57)
        long now = System.currentTimeMillis();
        if(!bNeed || strPath.length() == 0) { stopUpWatcher(); if(bNeed) reloadUp(); return; }
        if(!strKey.equals(m_strUpWatchKey) || !upWatching())
        {
            // 감시가 금방 끝났으면 (스크립트가 장치에서 안 돎) 한동안 다시 띄우지 않는다
            if(m_upWatchProc != null && !m_upWatchProc.isAlive() && strKey.equals(m_strUpWatchKey) && now - m_nUpWatchStart < 3000) m_nUpWatchRetryAt = now + UP_WATCH_RETRY_MS;
            stopUpWatcher();
            if(now >= m_nUpWatchRetryAt) startUpWatcher(device(), strPath, strKey);
        }
        if(!upWatching() || now - m_nUpLastPoll >= UP_SAFETY_MS) { m_nUpLastPoll = now; reloadUp(); }
    }

    void startUpWatcher(String strDevice, String strPath, String strKey)
    {
        m_strUpWatchKey = strKey;
        m_nUpWatchStart = System.currentTimeMillis();
        try
        {
            ProcessBuilder pb = new ProcessBuilder(adbCommand(strDevice, "shell", "sh"));
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            m_upWatchProc = p;
            p.getOutputStream().write(upWatchScript(strPath).getBytes(UTF8));
            p.getOutputStream().flush();
            Thread t = new Thread(() ->
            {
                try(BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), UTF8)))
                {
                    String s;
                    while((s = r.readLine()) != null)
                        if(s.trim().equals(UP_CHANGED)) SwingUtilities.invokeLater(() -> { if(m_upWatchProc == p) { m_nUpLastPoll = System.currentTimeMillis(); reloadUp(); } });
                }
                catch(IOException e) { }
            }, "UpWatch");
            t.setDaemon(true);
            t.start();
        }
        catch(IOException e)
        {
            m_upWatchProc = null;
            m_nUpWatchRetryAt = System.currentTimeMillis() + UP_WATCH_RETRY_MS;
        }
    }

    void stopUpWatcher()
    {
        Process p = m_upWatchProc;
        m_upWatchProc = null;
        if(p != null) p.destroy();
    }

    void applyUpResult(String strPath, UpResult res)
    {
        m_strUpRead = strPath;
        m_nUpReadAt = System.currentTimeMillis();
        if(res.m_strError != null)
        {
            m_upModel.setError(res.m_strError);
            m_upRefs = new HashMap<String, UpRef>();
            m_bUpReset = true;                  // 다음엔 처음부터
        }
        else if(res.m_bChanged)
        {
            m_upRefs = res.m_refs;
            if(res.m_map.isEmpty()) m_upModel.setNotice("폴더에 파일이 없거나 파일이 비어 있습니다 : " + strPath);
            else m_upModel.update(res.m_map, true);
        }
        // 바뀐 것이 없으면 표는 그대로 (바뀐 줄 표시는 다음 변경까지 유지)
        if(upSelected()) updateInfo();
    }

    /** 장치 시각 + 파일마다 "수정시각 크기 경로" (폴더면 하위 폴더까지, 파일이면 그 파일 하나) */
    static String upStatCommand(String strPath)
    {
        return "d=" + shq(strPath) + "; echo KLOG_NOW $(date +%s); if [ -d $d ]; then find $d -type f -exec stat -c '%Y %s %n' {} +; "
             + "elif [ -e $d ]; then stat -c '%Y %s %n' $d; else echo KLOG_NOENT; fi";
    }

    /** 고른 파일들의 내용: 파일마다 "@@KLOGFILE 크기 경로" + 내용 (큰 파일은 내용 없이) */
    static final int UP_CMD_MAX = 6000;     // 한 번에 보내는 경로 길이 합 (Windows 명령 줄 32K 제한 안)

    /**
     * 여러 파일 내용을 프로세스 하나로 (1.58): head는 파일이 둘 이상이면 파일마다 "==> 경로 <==" 머리줄을 붙인다.
     * 파일이 하나여도 머리줄이 나오도록 /dev/null을 덧붙인다. 읽지 못한 파일은 머리줄이 없다 (오류 문구는 버림)
     */
    static String upHeadCommand(List<String> arPath)
    {
        StringBuilder sb = new StringBuilder("head -n 1000000");
        for(String p : arPath) sb.append(' ').append(shq(p));
        return sb.append(" /dev/null 2>/dev/null").toString();
    }

    /** head 출력 → 경로 → 내용 (요청한 경로의 머리줄만 파일 경계로 본다, 끝 줄바꿈 정리) */
    static Map<String, String> splitHeadOutput(String strOut, List<String> arPath)
    {
        java.util.Set<String> want = new java.util.HashSet<String>(arPath);
        want.add("/dev/null");
        Map<String, String> map = new LinkedHashMap<String, String>();
        String strFile = null;
        StringBuilder sb = new StringBuilder();
        for(String strLine : (strOut + "\n==> /dev/null <==").split("\n", -1))
        {
            if(strLine.startsWith("==> ") && strLine.endsWith(" <==") && want.contains(strLine.substring(4, strLine.length() - 4)))
            {
                if(strFile != null && !strFile.equals("/dev/null")) map.put(strFile, sb.toString().replaceAll("\n+$", ""));
                strFile = strLine.substring(4, strLine.length() - 4);
                sb.setLength(0);
                continue;
            }
            if(strFile != null) sb.append(strLine).append('\n');
        }
        return map;
    }

    // 캐시에 넣고 내용이 바뀌었으면 표시할 것이 있다고 남긴다 (읽기 스레드)
    void putUpFile(UpResult res, String strPath, long[] st, String strContent)
    {
        UpFile old = m_upCache.get(strPath);
        if(old == null || !old.m_strContent.equals(strContent) || old.m_nSize != st[1]) res.m_bChanged = true;
        m_upCache.put(strPath, new UpFile(st[0], st[1], strContent));
    }

    /**
     * 수정 시각·크기를 먼저 보고, 처음 보거나 바뀐 파일(직전 확인과 같은 초에 수정된 파일, 고쳐 쓴 파일 포함)만 내용을 읽는다.
     * 읽기 스레드에서만 부른다 (캐시를 씀).
     */
    UpResult readUpChanged(String strDevice, String strSerial, String strPath, boolean bReset, String strStatOut)
    {
        UpResult res = new UpResult();
        String strKey = strDevice + "#" + (strSerial == null ? "" : strSerial) + "|" + strPath;     // 실제 장치(시리얼)가 바뀌면 캐시를 버린다 (1.57)
        if(bReset || !strKey.equals(m_strUpCacheKey)) { m_upCache.clear(); m_strUpCacheKey = strKey; m_nUpPrevNow = 0; res.m_bChanged = true; }
        String[] l = strStatOut != null ? new String[]{ strStatOut, null } : runAdb(strDevice, 10000, "shell", upStatCommand(strPath));
        if(l[1] != null) { res.m_strError = l[1]; return res; }
        long nNow = 0;
        Map<String, long[]> list = new java.util.TreeMap<String, long[]>();
        StringBuilder sbErr = new StringBuilder();
        for(String strLine : l[0].replace("\r", "").split("\n"))
        {
            if(strLine.startsWith("KLOG_NOW ")) { try { nNow = Long.parseLong(strLine.substring(9).trim()); } catch(NumberFormatException e) { } continue; }
            if(strLine.equals("KLOG_NOENT")) { res.m_strError = strPath + ": No such file or directory"; m_upCache.clear(); return res; }
            Matcher m = STAT_LINE.matcher(strLine);
            if(m.matches()) list.put(m.group(3), new long[]{ Long.parseLong(m.group(1)), Long.parseLong(m.group(2)) });
            else if(strLine.trim().length() > 0) sbErr.append(strLine.trim()).append('\n');
        }
        if(list.isEmpty() && sbErr.length() > 0)
        {
            String s = sbErr.toString().trim();
            res.m_strError = s + (s.contains("Permission denied") ? "\n권한이 없습니다 — Root 버튼으로 adb root 후 다시 읽으세요" : "");
            return res;
        }
        // 없어진 파일
        if(m_upCache.keySet().retainAll(list.keySet())) res.m_bChanged = true;
        // 읽을 파일: 처음 봄 / 시각·크기 다름 / 직전 확인과 같은 초 이후 수정 / 고쳐 씀
        List<String> arRead = new ArrayList<String>();
        for(Map.Entry<String, long[]> e : list.entrySet())
        {
            UpFile u = m_upCache.get(e.getKey());
            boolean bDirty = m_upDirty.remove(e.getKey());
            if(bDirty || u == null || u.m_nTime != e.getValue()[0] || u.m_nSize != e.getValue()[1] || e.getValue()[0] >= m_nUpPrevNow - 1)
                arRead.add(e.getKey());
        }
        // 1.58: 크기는 목록에 있으므로 큰 파일은 읽지 않고, 나머지는 head 하나로 여러 파일을 한 번에 (파일마다 프로세스를 띄우지 않음)
        List<String> arCat = new ArrayList<String>();
        for(String p : arRead)
        {
            long[] st = list.get(p);
            if(st[1] > UP_FILE_MAX) putUpFile(res, p, st, "");
            else arCat.add(p);
        }
        int nFrom = 0;
        while(nFrom < arCat.size())
        {
            // Windows 명령 줄 길이 제한 안에서 최대한 많이
            int nTo = nFrom, nLen = 0;
            while(nTo < arCat.size() && (nTo == nFrom || nLen + arCat.get(nTo).length() + 3 < UP_CMD_MAX)) nLen += arCat.get(nTo++).length() + 3;
            List<String> arPart = arCat.subList(nFrom, nTo);
            String[] r = runAdb(strDevice, 15000, "shell", upHeadCommand(arPart));
            if(r[1] != null) { res.m_strError = r[1]; return res; }
            Map<String, String> got = splitHeadOutput(r[0].replace("\r", ""), arPart);
            for(String p : arPart)
            {
                String c = got.get(p);
                putUpFile(res, p, list.get(p), c != null ? c : "cat: " + p + ": 권한이 없거나 읽을 수 없음");
            }
            nFrom = nTo;
        }
        res.m_nRead = arRead.size();
        m_nUpPrevNow = nNow;
        if(res.m_bChanged)
        {
            boolean bFile = list.size() == 1 && list.containsKey(strPath);
            String strBase = strPath.endsWith("/") ? strPath : strPath + "/";
            for(Map.Entry<String, UpFile> e : new java.util.TreeMap<String, UpFile>(m_upCache).entrySet())
            {
                UpFile u = e.getValue();
                if(bFile && u.m_nSize <= UP_FILE_MAX && !u.m_strContent.startsWith("cat:"))
                {
                    // 파일 하나: 줄마다 key=value
                    for(Map.Entry<String, String> kv : parseUp(u.m_strContent).entrySet())
                    {
                        res.m_map.put(kv.getKey(), kv.getValue());
                        res.m_refs.put(kv.getKey(), new UpRef(strPath, kv.getKey(), null));
                    }
                }
                else
                    addUpFile(res.m_map, res.m_refs, e.getKey(), e.getKey().startsWith(strBase) ? e.getKey().substring(strBase.length()) : e.getKey(), u.m_nSize, u.m_strContent);
            }
        }
        return res;
    }

    /** "@@KLOGFILE 크기 경로" + 내용 출력 → 경로 → 내용 (끝 줄바꿈 정리 전) */
    static Map<String, String> splitUpOutput(String strOut)
    {
        Map<String, String> map = new LinkedHashMap<String, String>();
        String strFile = null;
        StringBuilder sb = new StringBuilder();
        for(String strLine : (strOut + "\n" + UP_FILE_MARK + "0 ").split("\n"))
        {
            if(!strLine.startsWith(UP_FILE_MARK))
            {
                if(strFile != null) sb.append(strLine).append('\n');
                continue;
            }
            if(strFile != null && strFile.length() > 0) map.put(strFile, sb.toString().replaceAll("\n+$", ""));
            String[] ar = strLine.substring(UP_FILE_MARK.length()).split(" ", 2);
            strFile = ar.length > 1 ? ar[1].trim() : null;
            sb.setLength(0);
        }
        return map;
    }

    static final String UP_DEFAULT_PATH = "/LGU_data/UP";
    static final String UP_FILE_MARK    = "@@KLOGFILE ";       // 폴더 읽기 출력에서 파일 하나의 시작: @@KLOGFILE <크기> <경로>
    static final int    UP_FILE_MAX     = 65536;               // 이보다 큰 파일은 내용을 읽지 않고 크기만


    /**
     * 폴더 읽기 출력 → 맵. 파일마다:
     * - 값 하나짜리 파일(한 줄이거나 key=value 형식이 아님) → 이름 = 폴더 안 상대 경로, 값 = 내용
     * - 두 줄 이상이 모두 key=value / key: value 형식 → "상대 경로 / key" 줄마다
     * - 너무 크면 크기만, 읽지 못하면 (읽지 못함: 이유)
     */
    static Map<String, String> parseUpDir(String strDir, String strOut) { return parseUpDir(strDir, strOut, null); }

    static Map<String, String> parseUpDir(String strDir, String strOut, Map<String, UpRef> refs)
    {
        Map<String, String> map = new LinkedHashMap<String, String>();
        String strBase = strDir.endsWith("/") ? strDir : strDir + "/";
        String strFile = null;
        long nSize = 0;
        StringBuilder sb = new StringBuilder();
        for(String strLine : (strOut + "\n" + UP_FILE_MARK + "0 ").split("\n"))
        {
            if(!strLine.startsWith(UP_FILE_MARK))
            {
                if(strFile != null) sb.append(strLine).append('\n');
                continue;
            }
            if(strFile != null && strFile.length() > 0) addUpFile(map, refs, strFile, strFile.startsWith(strBase) ? strFile.substring(strBase.length()) : strFile, nSize, sb.toString());
            String[] ar = strLine.substring(UP_FILE_MARK.length()).split(" ", 2);
            try { nSize = Long.parseLong(ar[0].trim()); } catch(NumberFormatException e) { nSize = 0; }
            strFile = ar.length > 1 ? ar[1].trim() : null;
            sb.setLength(0);
        }
        return map;
    }

    static void addUpFile(Map<String, String> map, Map<String, UpRef> refs, String strPath, String strName, long nSize, String strContent)
    {
        String s = strContent.replaceAll("\n+$", "");
        if(nSize > UP_FILE_MAX)
        {
            map.put(strName, "(" + nSize + " bytes — " + (UP_FILE_MAX / 1024) + "KB보다 커서 읽지 않음)");
            if(refs != null) refs.put(strName, new UpRef(strPath, null, "파일이 커서(" + nSize + " bytes) 여기서 수정할 수 없습니다"));
            return;
        }
        if(s.startsWith("cat:"))
        {
            map.put(strName, "(읽지 못함: " + s.substring(s.lastIndexOf(':') + 1).trim() + ")");
            if(refs != null) refs.put(strName, new UpRef(strPath, null, "파일을 읽지 못해 수정할 수 없습니다 — 권한이 없으면 Root 후 다시 읽으세요"));
            return;
        }
        // 두 줄 이상이고 모두 key=value / key: value 면 키별로
        List<String> arLines = new ArrayList<String>();
        for(String l : s.split("\n")) { String t = l.trim(); if(t.length() > 0 && !t.startsWith("#") && !t.startsWith(";") && !t.startsWith("//")) arLines.add(t); }
        boolean bKv = arLines.size() >= 2;
        for(String t : arLines) if(!(t.indexOf('=') > 0 || t.indexOf(':') > 0 || (t.startsWith("[") && t.endsWith("]")))) { bKv = false; break; }
        if(bKv)
            for(Map.Entry<String, String> e : parseUp(s).entrySet())
            {
                map.put(strName + " / " + e.getKey(), e.getValue());
                if(refs != null) refs.put(strName + " / " + e.getKey(), new UpRef(strPath, e.getKey(), null));
            }
        else
        {
            map.put(strName, s);
            if(refs != null) refs.put(strName, new UpRef(strPath, null, null));
        }
    }

    // UP 탭 한 줄이 장치의 어느 파일·키인지 (수정용, 1.54)
    static class UpRef
    {
        final String m_strFile;     // 장치 파일 경로
        final String m_strKey;      // 파일 안 키 (null이면 파일 전체가 값)
        final String m_strReason;   // 수정할 수 없으면 이유
        UpRef(String f, String k, String r) { m_strFile = f; m_strKey = k; m_strReason = r; }
    }

    /**
     * UP 파일 내용 → 순서 그대로의 맵. getprop 형식([key]: [value])이면 그대로,
     * 아니면 줄마다 key=value 또는 key: value. 빈 줄·#·; 주석은 건너뛰고, [section] 아래 키는 section.key,
     * 구분자가 없는 줄은 "(N번째 줄)"을 이름으로. 같은 이름이 또 나오면 뒤에 (N번째 줄)을 붙인다.
     */
    static Map<String, String> parseUp(String strOut) { return parseUp(strOut, null); }

    /**
     * pos가 있으면 키마다 {줄 번호(0부터), 값 앞부분 길이}를 남긴다 — 수정할 때 그 줄의 값만 바꾸기 위해 (1.54).
     * 값 앞부분 길이가 -1이면 getprop 형식 줄([key]: [value]).
     */
    static Map<String, String> parseUp(String strOut, Map<String, int[]> pos)
    {
        Map<String, String> getprop = parse(strOut);
        String[] arLines = strOut.replace("\r", "").split("\n");
        if(!getprop.isEmpty())
        {
            if(pos != null)
                for(int i = 0; i < arLines.length; i++)
                {
                    Matcher m = PROP_LINE.matcher(arLines[i]);
                    if(m.matches() && !pos.containsKey(m.group(1))) pos.put(m.group(1), new int[]{ i, -1 });
                }
            return getprop;
        }
        Map<String, String> map = new LinkedHashMap<String, String>();
        String strSection = "";
        for(int i = 0; i < arLines.length; i++)
        {
            int nLine = i + 1;
            String strLine = arLines[i];
            String s = strLine.trim();
            if(s.length() == 0 || s.startsWith("#") || s.startsWith(";") || s.startsWith("//")) continue;
            if(s.startsWith("[") && s.endsWith("]") && s.indexOf('=') < 0) { strSection = s.substring(1, s.length() - 1).trim(); continue; }
            int nLead = strLine.indexOf(s);
            int nEq = s.indexOf('='), nColon = s.indexOf(':');
            int nSep = nEq > 0 ? nEq : nColon > 0 ? nColon : -1;
            String strKey, strValue;
            int nPrefix;
            if(nSep > 0)
            {
                strKey = s.substring(0, nSep).trim(); strValue = s.substring(nSep + 1).trim();
                nPrefix = nSep + 1;
                while(nPrefix < s.length() && (s.charAt(nPrefix) == ' ' || s.charAt(nPrefix) == '\t')) nPrefix++;
                nPrefix += nLead;
            }
            else { strKey = "(" + nLine + "번째 줄)"; strValue = s; nPrefix = nLead; }
            if(nSep > 0 && strSection.length() > 0) strKey = strSection + "." + strKey;
            if(map.containsKey(strKey)) strKey += " (" + nLine + "번째 줄)";
            map.put(strKey, strValue);
            if(pos != null) pos.put(strKey, new int[]{ i, nPrefix });
        }
        return map;
    }

    /**
     * 수정한 파일 내용 (1.54). strKey가 null이면 파일 전체를 strNew로(끝 줄바꿈 유무는 원래대로),
     * 아니면 그 키의 줄에서 값 부분만 바꾸고 나머지 줄·주석·줄바꿈(\r\n)은 그대로. 키를 못 찾으면 null.
     */
    static String applyUpEdit(String strOrig, String strKey, String strNew)
    {
        String nl = strOrig.contains("\r\n") ? "\r\n" : "\n";
        String strNorm = strOrig.replace("\r\n", "\n");
        if(strKey == null)
            return strNew.replace("\r\n", "\n").replace("\n", nl) + (strNorm.endsWith("\n") ? nl : "");
        Map<String, int[]> pos = new HashMap<String, int[]>();
        parseUp(strNorm, pos);
        int[] at = pos.get(strKey);
        String[] arLines = strNorm.split("\n", -1);
        if(at == null || at[0] >= arLines.length) return null;
        String strLine = arLines[at[0]];
        arLines[at[0]] = at[1] < 0 ? "[" + strKey + "]: [" + strNew + "]" : strLine.substring(0, Math.min(at[1], strLine.length())) + strNew;
        return String.join(nl, arLines);
    }

    // getprop 출력 → 이름 순서 그대로의 맵 ([name]: [value], 여러 줄 값은 다음 줄을 이어 붙임)
    static Map<String, String> parse(String strOut)
    {
        Map<String, String> map = new LinkedHashMap<String, String>();
        String strKey = null;
        StringBuilder sbVal = null;
        for(String strLine : strOut.replace("\r", "").split("\n"))
        {
            Matcher m = PROP_LINE.matcher(strLine);
            if(m.matches())
            {
                if(strKey != null) map.put(strKey, sbVal.toString());
                strKey = m.group(1);
                sbVal = new StringBuilder(m.group(2));
            }
            else if(strLine.startsWith("[") && strLine.contains("]: [") )
            {
                // 값이 여러 줄인 프로퍼티의 첫 줄: [name]: [value… (닫는 ]가 다음 줄에)
                if(strKey != null) map.put(strKey, sbVal.toString());
                int n = strLine.indexOf("]: [");
                strKey = strLine.substring(1, n);
                sbVal = new StringBuilder(strLine.substring(n + 4));
            }
            else if(strKey != null && sbVal != null && strLine.length() > 0)
            {
                String s = strLine.endsWith("]") ? strLine.substring(0, strLine.length() - 1) : strLine;
                sbVal.append('\n').append(s);
            }
        }
        if(strKey != null) map.put(strKey, sbVal.toString());
        return map;
    }

    static List<String> adbCommand(String strDevice, String... args)
    {
        List<String> arCmd = new ArrayList<String>(Arrays.asList(DeviceStats.ADB.trim().split("\\s+")));
        if(strDevice != null && strDevice.length() > 0) { arCmd.add("-s"); arCmd.add(strDevice); }
        arCmd.addAll(Arrays.asList(args));
        return arCmd;
    }

    /** adb [-s 장치] <args>. {출력, 오류 메시지(정상이면 null)} */
    static String[] runAdb(String strDevice, long nTimeoutMs, String... args) { return runAdb(false, strDevice, nTimeoutMs, args); }

    /** bRaw면 출력을 바이트 그대로 (끝 줄바꿈·\r 유지, 파일 내용을 고쳐 쓸 때, 1.54) */
    static String[] runAdb(boolean bRaw, String strDevice, long nTimeoutMs, String... args)
    {
        List<String> arCmd = adbCommand(strDevice, args);
        Process p = null;
        try
        {
            ProcessBuilder pb = new ProcessBuilder(arCmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            // 출력은 따로 읽고 시간 제한은 여기서 (adb가 멈춰도 제한 시간 뒤에는 돌아오도록, 1.56)
            final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            final java.io.InputStream in = p.getInputStream();
            Thread tRead = new Thread(() ->
            {
                byte[] buf = new byte[8192];
                int n;
                try { while((n = in.read(buf)) > 0) synchronized(bo) { bo.write(buf, 0, n); } } catch(IOException e) { }
            }, "AdbRead");
            tRead.setDaemon(true);
            tRead.start();
            if(!p.waitFor(nTimeoutMs, TimeUnit.MILLISECONDS)) { p.destroy(); return new String[]{ "", "adb 응답 없음" }; }
            tRead.join(2000);
            String strOut;
            synchronized(bo) { strOut = new String(bo.toByteArray(), UTF8); }
            if(!bRaw)
            {
                // 줄 단위로 읽던 때와 같게: \r\n·\r → \n, 끝 줄바꿈
                strOut = strOut.replace("\r\n", "\n").replace('\r', '\n');
                if(strOut.length() > 0 && !strOut.endsWith("\n")) strOut += "\n";
            }
            String strErr = DeviceStats.adbError(strOut);
            if(strErr != null)
            {
                // 이미지처럼 adb의 원래 문구도 함께 보인다
                String strRaw = strOut.trim();
                return new String[]{ strOut, strRaw.length() > 0 && !strRaw.equals(strErr) ? strRaw + "\n" + strErr : strErr };
            }
            return new String[]{ strOut, null };
        }
        catch(Exception e)
        {
            if(p != null) p.destroy();
            return new String[]{ "", "adb를 실행할 수 없습니다 (" + e.getMessage() + ")" };
        }
    }

    void updateInfo()
    {
        String strWhen = new SimpleDateFormat("HH:mm:ss").format(new Date());
        if(upSelected())
        {
            // UP 파일 탭
            String strPath = m_strUpRead == null || m_strUpRead.length() == 0 ? m_tfUpPath.getText().trim() : m_strUpRead;
            int nAllUp = m_upModel.allCount(), nShownUp = m_upModel.getRowCount();
            String strAt = m_nUpReadAt > 0 ? new SimpleDateFormat("HH:mm:ss").format(new Date(m_nUpReadAt)) : "-";
            if(strPath.length() == 0)
                m_lbInfo.setText("UP 파일 : 경로를 입력하세요");
            else if(m_upModel.m_bErrorState)
                m_lbInfo.setText("UP 파일 " + strPath + " : 읽지 못함 (" + strAt + ")");
            else
                m_lbInfo.setText("UP 파일 " + strPath + " : 항목 " + nAllUp + "개"
                    + (nShownUp != nAllUp ? ", 필터로 " + nShownUp + "개 보임" : "")
                    + ",  마지막 확인 " + strAt + (m_interval.isOn() && m_fileShown == null ? (upWatching() ? " (장치에서 바로 변경 감지)" : " (" + (UP_WATCH_MS / 1000) + "초마다 수정 시간 확인)") : "")
                    + (m_upModel.m_nLastChanged > 0 ? ",  바뀐 값 " + m_upModel.m_nLastChanged + "개" : "")
                    + (m_strUpMsg != null ? "   —   " + m_strUpMsg : "") + devNotice());
            return;
        }
        int nAll = m_model.allCount(), nShown = m_model.getRowCount();
        if(m_model.m_bErrorState)
            m_lbInfo.setText("읽지 못함 (" + strWhen + ") — 장치 연결을 확인하고 Reload 하세요");
        else
            m_lbInfo.setText((m_fileShown != null ? "파일 " + m_fileShown.getName() : "장치") + " : 프로퍼티 " + nAll + "개"
                + (nShown != nAll ? ", 필터로 " + nShown + "개 보임" : "")
                + (m_fileShown == null ? ",  마지막 읽음 " + strWhen + (m_interval.isOn() ? " (" + m_interval.seconds() + "초마다 자동 갱신)" : "") + (m_model.m_nLastChanged > 0 ? ",  바뀐 값 " + m_model.m_nLastChanged + "개" : "") + devNotice() : ""));
    }

    // ---- 저장·열기 ----

    void save()
    {
        // UP 파일 탭이면 그 표를 key=value 줄로 (1.52)
        final boolean bUp = upSelected();
        PropModel model = bUp ? m_upModel : m_model;
        if(model.allCount() == 0 || model.m_bErrorState) { JOptionPane.showMessageDialog(this, bUp ? "저장할 UP 파일 내용이 없습니다." : "저장할 프로퍼티가 없습니다."); return; }
        JFileChooser ch = new JFileChooser();
        String strDev = device().replaceAll("[^A-Za-z0-9._-]", "_");
        ch.setSelectedFile(new File((bUp ? "DeviceUp_" : "DeviceProp_") + (strDev.length() > 0 ? strDev + "_" : "") + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".txt"));
        if(ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = ch.getSelectedFile();
        try(Writer w = new OutputStreamWriter(new FileOutputStream(file), UTF8))
        {
            for(Prop p : model.m_all) w.write(bUp ? p.m_strName + "=" + p.m_strValue + "\n" : "[" + p.m_strName + "]: [" + p.m_strValue + "]\n");
            m_main.setStatus((bUp ? "UP 파일 내용을" : "장치 프로퍼티를") + " 저장했습니다 : " + file.getAbsolutePath());
            m_lbInfo.setText("저장했습니다 : " + file.getAbsolutePath());
        }
        catch(IOException e)
        {
            JOptionPane.showMessageDialog(this, "저장할 수 없습니다 : " + e.getMessage());
        }
    }

    void open()
    {
        JFileChooser ch = new JFileChooser();
        if(ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        openFile(ch.getSelectedFile());
    }

    void openFile(File file)
    {
        try
        {
            byte[] ar = java.nio.file.Files.readAllBytes(file.toPath());
            Map<String, String> props = parse(new String(ar, UTF8));
            if(props.isEmpty()) { JOptionPane.showMessageDialog(this, "getprop 형식([이름]: [값])의 줄이 없습니다 : " + file.getName()); return; }
            m_fileShown = file;
            m_nRequest++;       // 읽는 중이던 장치 결과는 버린다
            m_bLoading = false;
            applyLive();        // 파일을 보는 동안은 실시간 갱신을 멈춘다
            m_lbSource.setText("[ File : " + file.getName() + " ]   (Reload를 누르면 장치로 돌아감)");
            m_model.clearChanged();
            m_model.update(props, false);
            updateInfo();
        }
        catch(IOException e)
        {
            JOptionPane.showMessageDialog(this, "열 수 없습니다 : " + e.getMessage());
        }
    }

    // ---- Edit·Add (setprop) ----

    void edit()
    {
        if(upSelected()) { editUp(); return; }      // UP File 탭 (1.54)
        int nRow = m_table.getSelectedRow();
        Prop p = nRow >= 0 ? m_model.row(nRow) : null;
        if(p == null || p.m_bError) { JOptionPane.showMessageDialog(this, "값을 바꿀 프로퍼티를 표에서 선택하세요."); return; }
        if(m_fileShown != null) { JOptionPane.showMessageDialog(this, "파일을 보는 중에는 바꿀 수 없습니다. Reload로 장치 보기로 돌아오세요."); return; }
        Object v = JOptionPane.showInputDialog(this, p.m_strName + " 의 새 값\n(ro.* 처럼 읽기 전용 프로퍼티는 바뀌지 않을 수 있습니다)", "Edit property", JOptionPane.PLAIN_MESSAGE, null, null, p.m_strValue);
        if(v == null) return;
        setprop(p.m_strName, v.toString());
    }

    void add()
    {
        if(m_fileShown != null) { JOptionPane.showMessageDialog(this, "파일을 보는 중에는 추가할 수 없습니다. Reload로 장치 보기로 돌아오세요."); return; }
        JTextField tfName = new JTextField(28), tfValue = new JTextField(28);
        JPanel jp = new JPanel(new java.awt.GridLayout(4, 1, 2, 2));
        jp.add(new JLabel("이름 (예: debug.myapp.level)")); jp.add(tfName);
        jp.add(new JLabel("값")); jp.add(tfValue);
        if(JOptionPane.showConfirmDialog(this, jp, "Add property", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String strName = tfName.getText().trim();
        if(!strName.matches("[A-Za-z0-9._:@-]+")) { JOptionPane.showMessageDialog(this, "이름에는 영문·숫자·. _ - : @ 만 쓸 수 있습니다."); return; }
        setprop(strName, tfValue.getText());
    }

    // ---- UP File 탭 수정 (1.54) ----

    static final String UP_TMP = "/data/local/tmp/klogfilter_up.tmp";

    void editUp()
    {
        int nRow = m_upTable.getSelectedRow();
        Prop p = nRow >= 0 ? m_upModel.row(nRow) : null;
        if(p == null || p.m_bError) { JOptionPane.showMessageDialog(this, "고칠 줄을 표에서 선택하세요."); return; }
        UpRef ref = m_upRefs.get(p.m_strName);
        if(ref == null) { JOptionPane.showMessageDialog(this, "이 줄은 고칠 수 없습니다."); return; }
        if(ref.m_strReason != null) { JOptionPane.showMessageDialog(this, ref.m_strReason); return; }
        String strNew;
        if(ref.m_strKey == null)
        {
            // 파일 전체가 값: 여러 줄도 쓸 수 있게
            javax.swing.JTextArea ta = new javax.swing.JTextArea(p.m_strValue, Math.min(12, Math.max(3, p.m_strValue.split("\n").length + 1)), 50);
            ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JPanel jp = new JPanel(new BorderLayout(0, 4));
            jp.add(new JLabel(ref.m_strFile + " 의 새 내용"), BorderLayout.NORTH);
            jp.add(new JScrollPane(ta), BorderLayout.CENTER);
            if(JOptionPane.showConfirmDialog(this, jp, "Edit UP file", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            strNew = ta.getText();
        }
        else
        {
            Object v = JOptionPane.showInputDialog(this, ref.m_strFile + "\n" + ref.m_strKey + " 의 새 값", "Edit UP file", JOptionPane.PLAIN_MESSAGE, null, null, p.m_strValue);
            if(v == null) return;
            strNew = v.toString();
            if(strNew.contains("\n")) { JOptionPane.showMessageDialog(this, "key=value 줄의 값에는 줄바꿈을 넣을 수 없습니다."); return; }
        }
        if(strNew.equals(p.m_strValue)) return;
        writeUp(p.m_strName, ref, strNew);
    }

    // 장치 파일을 지금 내용으로 다시 읽어 그 줄(또는 전체)만 바꾼 뒤 써 넣고, 다시 읽어 확인한다
    void writeUp(final String strRow, final UpRef ref, final String strNew)
    {
        final String strDevice = device();
        m_strUpMsg = strRow + " 쓰는 중…";
        updateInfo();
        new Thread(() ->
        {
            String strMsg;
            String[] r = runAdb(true, strDevice, 8000, "shell", "cat " + shq(ref.m_strFile));
            String strOrig = r[0];
            if(r[1] != null) strMsg = "읽기 실패 : " + r[1];
            else if(strOrig.startsWith("cat:")) strMsg = "읽기 실패 : " + strOrig.trim();
            else
            {
                String strContent = applyUpEdit(strOrig, ref.m_strKey, strNew);
                if(strContent == null) strMsg = "파일에서 " + ref.m_strKey + " 줄을 찾지 못했습니다 (그 사이 파일이 바뀌었을 수 있습니다)";
                else
                {
                    String strErr = writeDeviceFile(strDevice, ref.m_strFile, strContent);
                    if(strErr != null) strMsg = "쓰기 실패 : " + strErr + (strErr.contains("Permission denied") || strErr.contains("Read-only") ? " — Root 버튼으로 adb root 후 다시 하세요" : "");
                    else
                    {
                        String[] c = runAdb(true, strDevice, 8000, "shell", "cat " + shq(ref.m_strFile));
                        boolean bOk = c[1] == null && c[0].equals(strContent);
                        strMsg = bOk ? strRow + " = " + strNew.replace("\n", " ⏎ ") + " 로 바꿨습니다"
                                     : strRow + " 을(를) 썼지만 다시 읽은 내용이 다릅니다 — 장치가 파일을 다시 썼을 수 있습니다";
                    }
                }
            }
            final String strFinal = strMsg;
            SwingUtilities.invokeLater(() ->
            {
                m_strUpMsg = strFinal;
                m_main.setStatus("UP File : " + strFinal);
                m_upDirty.add(ref.m_strFile);       // 수정 시각이 같은 초여도 그 파일은 꼭 다시 읽는다
                reloadUp();
                updateInfo();
            });
        }, "WriteUpFile").start();
    }

    static String shq(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /**
     * 장치 파일에 내용을 쓴다: 로컬 임시 파일 → adb push (/data/local/tmp) → cat 임시 > 대상.
     * 대상 파일에 덮어쓰므로 소유자·권한은 그대로. 실패하면 이유, 성공하면 null.
     */
    static String writeDeviceFile(String strDevice, String strPath, String strContent)
    {
        File tmp = null;
        try
        {
            tmp = File.createTempFile("klogfilter_up", ".tmp");
            java.nio.file.Files.write(tmp.toPath(), strContent.getBytes(UTF8));
            String[] r = runAdb(strDevice, 20000, "push", tmp.getAbsolutePath(), UP_TMP);
            if(r[1] != null) return r[1];
            if(r[0].contains("error") || r[0].contains("failed")) return r[0].trim();
            String[] w = runAdb(strDevice, 8000, "shell", "cat " + UP_TMP + " > " + shq(strPath) + "; echo KLOG_RC=$?; rm -f " + UP_TMP);
            if(w[1] != null) return w[1];
            String strOut = w[0].replace("\r", "");
            int n = strOut.lastIndexOf("KLOG_RC=");
            if(n < 0) return strOut.trim().length() > 0 ? strOut.trim() : "결과를 알 수 없습니다";
            String strRc = strOut.substring(n + 8).trim();
            if(strRc.equals("0")) return null;
            String strErr = strOut.substring(0, n).trim();
            return strErr.length() > 0 ? strErr : "종료 코드 " + strRc;
        }
        catch(IOException e)
        {
            return e.getMessage();
        }
        finally
        {
            if(tmp != null) tmp.delete();
        }
    }

    // adb shell setprop name 'value' 후 그 값을 다시 읽어 바뀌었는지 알려 준다
    void setprop(final String strName, final String strValue)
    {
        final String strDevice = device();
        final String strQuoted = "'" + strValue.replace("'", "'\\''") + "'";
        new Thread(() ->
        {
            String[] r = runAdb(strDevice, 8000, "shell", "setprop " + strName + " " + strQuoted);
            String[] g = runAdb(strDevice, 8000, "shell", "getprop " + strName);
            final String strNow = g[1] == null ? g[0].replace("\r", "").replaceAll("\n$", "") : null;
            final String strMsg;
            if(r[1] != null) strMsg = "setprop 실패 : " + r[1];
            else if(r[0].trim().length() > 0) strMsg = "setprop : " + r[0].trim();
            else if(strNow != null && strNow.equals(strValue)) strMsg = strName + " = " + strValue + " 로 바꿨습니다";
            else strMsg = strName + " 값이 바뀌지 않았습니다 (지금 값: " + strNow + ") — 읽기 전용이거나 권한이 없을 수 있습니다";
            SwingUtilities.invokeLater(() -> { m_lbInfo.setText(strMsg); m_main.setStatus("장치 프로퍼티 : " + strMsg); reload(); });
        }, "SetProp").start();
    }

    // ---- 표 모델 ----

    static class Prop
    {
        final String m_strName;
        String       m_strValue;
        long         m_nChanged;     // 마지막 갱신에서 값이 바뀐 시각 (0 = 마지막 갱신에서 그대로)
        String       m_strPrev;      // 바뀌기 전 값 (새로 생긴 프로퍼티면 null)
        boolean      m_bNew;         // 마지막 갱신에서 새로 생김
        boolean      m_bError;
        Prop(String n, String v) { m_strName = n; m_strValue = v; }
    }

    // Changed 열·툴팁 문구: 마지막 갱신에서 바뀐 내용 (1.50)
    static String changeText(Prop p)
    {
        String strTime = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(p.m_nChanged));
        return p.m_bNew ? strTime + "  새로 생김" : strTime + "  " + (p.m_strPrev == null ? "" : p.m_strPrev) + " → " + p.m_strValue;
    }

    class PropModel extends AbstractTableModel
    {
        private static final long serialVersionUID = 1L;
        List<Prop> m_all   = new ArrayList<Prop>();
        List<Prop> m_shown = new ArrayList<Prop>();
        String[]   m_arFilter = new String[0];
        boolean    m_bErrorState;
        int        m_nLastChanged;
        JTable     m_tbl;          // 이 모델을 보여 주는 표 (프로퍼티 탭 / UP 파일 탭, 1.52)

        public int getRowCount()    { return m_shown.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int c) { return c == 0 ? "Name" : c == 1 ? "Value" : "Changed"; }
        public Object getValueAt(int r, int c)
        {
            Prop p = m_shown.get(r);
            return c == 0 ? p.m_strName : c == 1 ? p.m_strValue : p.m_nChanged > 0 ? changeText(p) : "";     // 여러 줄 값은 줄을 늘려 그대로 (1.53)
        }
        Prop row(int r) { return r >= 0 && r < m_shown.size() ? m_shown.get(r) : null; }
        int allCount() { return m_bErrorState ? 0 : m_all.size(); }

        void clearChanged() { for(Prop p : m_all) { p.m_nChanged = 0; p.m_strPrev = null; p.m_bNew = false; } m_nLastChanged = 0; }

        // 새로 읽은 값으로 바꾼다. bMarkChanges면 이전과 달라진 값(새로 생긴 것 포함)에 바뀐 시각과 이전 값을 남기고,
        // 이번에 그대로인 값은 표시를 지운다 → 바뀐 줄은 다음 갱신 때까지 연두색 (1.50)
        void update(Map<String, String> props, boolean bMarkChanges)
        {
            Map<String, Prop> old = new HashMap<String, Prop>();
            if(!m_bErrorState) for(Prop p : m_all) old.put(p.m_strName, p);
            boolean bFirst = old.isEmpty();
            long now = System.currentTimeMillis();
            List<Prop> all = new ArrayList<Prop>(props.size());
            int nChanged = 0;
            for(Map.Entry<String, String> e : props.entrySet())
            {
                Prop p = old.get(e.getKey());
                if(p == null)
                {
                    p = new Prop(e.getKey(), e.getValue());
                    if(bMarkChanges && !bFirst) { p.m_nChanged = now; p.m_bNew = true; nChanged++; }
                }
                else if(!p.m_strValue.equals(e.getValue()))
                {
                    if(bMarkChanges) { p.m_strPrev = p.m_strValue; p.m_bNew = false; p.m_nChanged = now; nChanged++; }
                    p.m_strValue = e.getValue();
                }
                else if(bMarkChanges)
                {
                    p.m_nChanged = 0; p.m_strPrev = null; p.m_bNew = false;     // 이번엔 그대로: 표시 지움
                }
                all.add(p);
            }
            m_all = all;
            m_bErrorState = false;
            if(bMarkChanges) m_nLastChanged = nChanged;
            refilter();
            // 바뀐 값이 있으면 맨 위(바뀐 줄)가 보이게
            if(bMarkChanges && nChanged > 0 && m_tbl.getRowCount() > 0)
                m_tbl.scrollRectToVisible(m_tbl.getCellRect(0, 0, true));
        }

        // adb 오류를 표에 보여 준다 (이미지처럼 오류 문구 + Try Again.)
        void setError(String strError)
        {
            List<Prop> all = new ArrayList<Prop>();
            for(String s : strError.split("\n")) if(s.trim().length() > 0) { Prop p = new Prop(s.trim(), ""); p.m_bError = true; all.add(p); }
            Prop t = new Prop("Try Again.", ""); t.m_bError = true; all.add(t);
            m_all = all;
            m_bErrorState = true;
            refilter();
        }

        // 안내 한 줄 (UP 파일 경로가 없을 때 등, Try Again. 없이)
        void setNotice(String s)
        {
            List<Prop> all = new ArrayList<Prop>();
            Prop p = new Prop(s, ""); p.m_bError = true; all.add(p);
            m_all = all;
            m_bErrorState = true;
            m_nLastChanged = 0;
            refilter();
        }

        void setFilter(String s)
        {
            List<String> ar = new ArrayList<String>();
            for(String t : s.toLowerCase().split("\\|")) if(t.trim().length() > 0) ar.add(t.trim());
            m_arFilter = ar.toArray(new String[0]);
            refilter();
        }

        void refilter()
        {
            // 선택한 프로퍼티를 다시 선택하기 위해 이름을 기억한다
            int nSel = m_tbl.getSelectedRow();
            String strSel = nSel >= 0 && nSel < m_shown.size() ? m_shown.get(nSel).m_strName : null;
            // 마지막 갱신에서 바뀐 줄을 맨 위에, 나머지는 읽은 순서대로 (1.51)
            List<Prop> shown = new ArrayList<Prop>(), rest = new ArrayList<Prop>();
            for(Prop p : m_all)
            {
                boolean bShow = m_bErrorState || m_arFilter.length == 0;
                if(!bShow)
                {
                    String n = p.m_strName.toLowerCase(), v = p.m_strValue.toLowerCase();
                    for(String t : m_arFilter) if(n.contains(t) || v.contains(t)) { bShow = true; break; }
                }
                if(bShow) (p.m_nChanged > 0 && !m_bErrorState ? shown : rest).add(p);
            }
            shown.addAll(rest);
            m_shown = shown;
            fireTableDataChanged();
            if(m_tbl != null) fitRowHeights(m_tbl);     // 값이 칸에 다 보이게 줄 높이 (1.53)
            if(strSel != null)
                for(int i = 0; i < m_shown.size(); i++)
                    if(m_shown.get(i).m_strName.equals(strSel)) { m_tbl.setRowSelectionInterval(i, i); break; }
        }
    }

    /** 테스트용: 파일 읽기와 같은 방식으로 문자열을 표에 넣는다 */
    void loadForTest(String strGetpropOutput, boolean bMarkChanges)
    {
        m_model.update(parse(strGetpropOutput), bMarkChanges);
        updateInfo();
    }
}
