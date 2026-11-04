import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * 프로세스 상태(PS) 창 (1.45): 연결된 장치의 ps 결과를 표로 보여 주고, 고른 프로세스의 정보를 adb로 읽어 본다.
 * - Refresh: ps (Thread를 켜면 스레드까지)
 * - Kill: kill PID
 * - Status / Info / Stat / Maps / Stack / FD: /proc/PID/status, dumpsys meminfo PID, /proc/PID/stat, /proc/PID/maps,
 *   debuggerd -b PID(안 되면 /proc/PID/task/(tid)/stack), ls -l /proc/PID/fd 결과를 별도 창에
 * - Filter: Name / PID / User
 */
public class ProcessDialog extends JDialog
{
    private static final long serialVersionUID = 1L;

    final LogFilterMain m_main;
    final JCheckBox     m_chkThread = new JCheckBox("Thread");
    final JTextField    m_tfName = new JTextField(10), m_tfPid = new JTextField(6), m_tfUser = new JTextField(7);
    final JLabel        m_lbInfo = new JLabel(" ");
    final PsModel       m_model = new PsModel();
    final JTable        m_table = new JTable(m_model);
    final TableRowSorter<PsModel> m_sorter = new TableRowSorter<PsModel>(m_model);
    volatile boolean    m_bLoading;
    int                 m_nRequest;          // 늦게 끝난 이전 읽기가 새 결과를 덮지 않도록 (EDT에서만)
    IntervalField       m_interval;          // 자동 갱신 주기 (1.46)
    RootControl         m_root;              // root 실행 버튼 (1.48)

    ProcessDialog(LogFilterMain main)
    {
        super(main, "Process State (PS)", false);
        m_main = main;

        // ---- 툴바 ----
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        JButton btnRefresh = button("Refresh", "ps로 프로세스 목록 다시 읽기 (F5)", e -> refresh());
        btnRefresh.setFont(btnRefresh.getFont().deriveFont(Font.BOLD));
        bar.add(btnRefresh);
        bar.add(button("Kill", "선택한 프로세스 종료 (kill PID)", e -> kill()));
        bar.add(button("Status", "상태 : cat /proc/[PID]/status", e -> showInfo("Status", "cat /proc/%d/status")));
        bar.add(button("Info", "메모리 정보 : dumpsys meminfo [PID]", e -> showInfo("Info", "dumpsys meminfo %d")));
        bar.add(button("Stat", "통계 : cat /proc/[PID]/stat", e -> showInfo("Stat", "cat /proc/%d/stat")));
        bar.add(button("Maps", "메모리 맵 : cat /proc/[PID]/maps", e -> showInfo("Maps", "cat /proc/%d/maps")));
        bar.add(button("Stack", "스택 : debuggerd -b [PID] (안 되면 cat /proc/[PID]/task/*/stack)", e -> showInfo("Stack",
            "debuggerd -b %1$d 2>&1 || for t in /proc/%1$d/task/*; do echo \"== $t\"; cat $t/comm $t/stack 2>&1; done")));
        bar.add(button("FD", "열린 파일 : ls -l /proc/[PID]/fd", e -> showInfo("FD", "ls -l /proc/%d/fd")));
        // root 실행 (1.48): root면 다른 사용자 프로세스의 Maps·Stack·FD·Kill도 된다. adb root 후 다시 읽는다
        m_root = new RootControl(main, this, () -> refresh(true), s -> { m_strNotice = s; m_lbInfo.setText(s); });
        bar.add(m_root);

        JLabel lbTitle = new JLabel("Process State (PS)");
        lbTitle.setFont(lbTitle.getFont().deriveFont(Font.BOLD, 13f));
        m_chkThread.setToolTipText("스레드까지 보기 (ps -T)");
        m_chkThread.addActionListener(e -> refresh());
        JPanel jpTitle = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        jpTitle.add(lbTitle);
        jpTitle.add(m_chkThread);
        // 자동 갱신 (1.46): 기본 10초, 사용자가 입력한 주기로
        m_interval = new IntervalField("자동 갱신", main.m_nPsIntervalSec, main.m_bPsAuto, () -> { if(isVisible()) refresh(true); });
        m_interval.m_onChange = () -> { main.m_nPsIntervalSec = m_interval.seconds(); main.m_bPsAuto = m_interval.isOn(); updateInfo(); };
        jpTitle.add(m_interval);

        JPanel jpFilter = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        jpFilter.setBorder(BorderFactory.createTitledBorder("Filter"));
        jpFilter.add(new JLabel("Name:")); jpFilter.add(m_tfName);
        jpFilter.add(new JLabel("PID:"));  jpFilter.add(m_tfPid);
        jpFilter.add(new JLabel("User:")); jpFilter.add(m_tfUser);
        m_tfName.setToolTipText("프로세스 이름에 들어 있는 글자 (대소문자 무시, 여러 개는 | 로)");
        m_tfPid.setToolTipText("PID 또는 PPID (여러 개는 | 로)");
        m_tfUser.setToolTipText("사용자 (예: system, root, u0_a)");
        DocumentListener dl = new DocumentListener()
        {
            public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        };
        m_tfName.getDocument().addDocumentListener(dl);
        m_tfPid.getDocument().addDocumentListener(dl);
        m_tfUser.getDocument().addDocumentListener(dl);
        JPanel jpFilterHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        jpFilterHolder.add(jpFilter);

        JPanel north = new JPanel();
        north.setLayout(new javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS));
        for(JPanel p : new JPanel[]{ bar, jpTitle, jpFilterHolder }) { p.setAlignmentX(LEFT_ALIGNMENT); north.add(p); }

        // ---- 표 ----
        m_table.setRowSorter(m_sorter);
        for(int c = 1; c < COLUMNS.length; c++)
            if(c != 3 && c != 6) m_sorter.setComparator(c, NUMBER_ORDER);      // 숫자 열은 숫자 순서로
        int[] arWidth = { 420, 60, 60, 80, 80, 70, 140 };
        for(int c = 0; c < arWidth.length; c++) m_table.getColumnModel().getColumn(c).setPreferredWidth(arWidth[c]);
        m_table.setFillsViewportHeight(true);
        m_table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer()
        {
            private static final long serialVersionUID = 1L;
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col)
            {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                Ps p = m_model.row(t.convertRowIndexToModel(row));
                if(!sel) c.setForeground(p != null && p.m_bError ? new Color(0xC62828) : p != null && p.m_bHelp ? Color.DARK_GRAY : p != null && p.m_bThread ? new Color(0x546E7A) : Color.BLACK);
                setHorizontalAlignment(col >= 1 && col <= 5 && col != 3 ? RIGHT : LEFT);
                // 필터 입력과 일치하는 부분을 노란 바탕으로 (1.47): Name → Process 열, PID → PID·PPID 열(같은 값이면 칸 전체)과 TID, User → User 열
                if(p != null && !p.m_bHelp && !p.m_bError && v != null)
                {
                    String s = v.toString(), strHtml = null;
                    String[] arName = tokens(m_tfName.getText()), arPid = tokens(m_tfPid.getText()), arUser = tokens(m_tfUser.getText());
                    if(col == 0)
                    {
                        List<String> ar = new ArrayList<String>(Arrays.asList(arName));
                        if(p.m_strTid != null && any(arPid, p.m_strTid, true)) ar.add("tid " + p.m_strTid + ")");
                        if(!ar.isEmpty()) strHtml = DevicePropDialog.markMatches(s, ar.toArray(new String[0]));
                    }
                    else if((col == 1 || col == 2) && arPid.length > 0 && any(arPid, s.trim(), true))
                        strHtml = DevicePropDialog.markMatches(s, new String[]{ s.trim().toLowerCase() });
                    else if(col == 3 && arUser.length > 0)
                        strHtml = DevicePropDialog.markMatches(s, arUser);
                    if(strHtml != null) setText(strHtml);
                }
                return c;
            }
        });
        m_table.addMouseListener(new java.awt.event.MouseAdapter()
        {
            public void mouseClicked(java.awt.event.MouseEvent e) { if(e.getClickCount() == 2) showInfo("Status", "cat /proc/%d/status"); }
        });
        m_table.setToolTipText("Process state 표시창 — 더블클릭하면 Status");

        m_lbInfo.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        m_lbInfo.setForeground(Color.DARK_GRAY);
        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(m_table), BorderLayout.CENTER);
        getContentPane().add(m_lbInfo, BorderLayout.SOUTH);
        setSize(new Dimension(1120, 680));
        setLocationRelativeTo(main);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        bindKey(java.awt.event.KeyEvent.VK_F5, 0, "Refresh", this::refresh);
        bindKey(java.awt.event.KeyEvent.VK_ESCAPE, 0, "Close", () -> setVisible(false));
        bindKey(java.awt.event.KeyEvent.VK_DELETE, 0, "Kill", this::kill);

        m_model.setHelp();
    }

    static final Comparator<Object> NUMBER_ORDER = (a, b) -> Long.compare(num(a), num(b));
    static long num(Object o) { try { return Long.parseLong(String.valueOf(o).trim()); } catch(NumberFormatException e) { return Long.MIN_VALUE; } }

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

    String device()
    {
        String s = m_main.m_strSelectedDevice;
        return s == null ? "" : s.trim();
    }

    String deviceText()
    {
        String s = device();
        return s.length() > 0 ? s : "adb 기본 장치";
    }

    // 창을 연다. 목록이 아직 없으면 사용법을 보여 준다(이미지처럼). Refresh로 읽는다.
    void showFor()
    {
        setTitle("Process State (PS) — " + deviceText());
        setVisible(true);
        toFront();
        m_root.checkState();
        // 자동 갱신이 켜져 있으면 첫 주기를 기다리지 않고 바로 읽는다 (꺼져 있으면 사용법을 보이고 Refresh를 기다림)
        if(m_interval.isOn() && m_model.m_bInfoRows) refresh();
    }

    // 창이 보일 때만 자동 갱신한다
    public void setVisible(boolean b)
    {
        super.setVisible(b);
        if(m_interval == null) return;
        if(b) m_interval.start(); else m_interval.stop();
    }

    // ---- ps ----

    String m_strNotice;     // Kill 결과처럼 다시 읽은 뒤에도 보여 줄 안내 (사용자가 다시 Refresh하면 지움)

    void refresh()
    {
        refresh(false);
    }

    void refresh(boolean bKeepNotice)
    {
        if(!bKeepNotice) m_strNotice = null;
        if(m_bLoading) return;
        final String strDevice = device();
        final boolean bThread = m_chkThread.isSelected();
        setTitle("Process State (PS) — " + deviceText());
        m_bLoading = true;
        final int nRequest = ++m_nRequest;
        m_lbInfo.setText("ps 읽는 중… (" + deviceText() + ")");
        new Thread(() ->
        {
            // toybox ps(Android 8 이상)는 -A가 있어야 전체, 예전 ps는 -A를 모른다: 결과가 이상하면 예전 방식으로 다시
            String[] r = DevicePropDialog.runAdb(strDevice, 15000, "shell", bThread ? "ps -A -T" : "ps -A");
            List<Ps> list = r[1] == null ? parse(r[0], bThread) : null;
            if(r[1] == null && (list == null || list.size() < 3))
            {
                r = DevicePropDialog.runAdb(strDevice, 15000, "shell", bThread ? "ps -t" : "ps");
                list = r[1] == null ? parse(r[0], bThread) : null;
            }
            final String[] fr = r;
            final List<Ps> fl = list;
            SwingUtilities.invokeLater(() ->
            {
                if(nRequest != m_nRequest) return;
                m_bLoading = false;
                if(fr[1] != null) { m_model.setError(fr[1]); m_lbInfo.setText("ps를 읽지 못했습니다 — 장치 연결을 확인하고 Refresh 하세요"); return; }
                if(fl == null || fl.isEmpty()) { m_model.setError(fr[0].trim().length() > 0 ? fr[0].trim() : "ps 결과가 없습니다"); m_lbInfo.setText("ps 결과를 해석하지 못했습니다"); return; }
                m_model.setRows(fl);
                applyFilter();
            });
        }, "PsRefresh").start();
    }

    /**
     * ps 출력 → 행 목록. 머리줄에서 열 위치를 찾는다.
     * toybox: USER PID PPID VSZ RSS WCHAN ADDR S NAME  (-T: USER PID TID PPID VSZ RSS WCHAN ADDR S CMD)
     * 예전:   USER PID PPID VSIZE RSS WCHAN PC NAME  (값 줄에는 PC 뒤에 상태 S가 하나 더 있음)
     */
    static List<Ps> parse(String strOut, boolean bThread)
    {
        String[] arLine = strOut.replace("\r", "").split("\n");
        int h = -1;
        for(int i = 0; i < arLine.length && h < 0; i++)
            if(arLine[i].trim().startsWith("USER") && arLine[i].contains("PID")) h = i;
        if(h < 0) return null;
        String[] head = arLine[h].trim().split("\\s+");
        int iUser = idx(head, "USER"), iPid = idx(head, "PID"), iTid = idx(head, "TID"), iPpid = idx(head, "PPID"),
            iVsz = Math.max(idx(head, "VSZ"), idx(head, "VSIZE")), iRss = idx(head, "RSS"), iWchan = idx(head, "WCHAN"),
            iName = Math.max(idx(head, "NAME"), Math.max(idx(head, "CMD"), idx(head, "ARGS")));
        boolean bOldPs = idx(head, "PC") >= 0 && idx(head, "S") < 0;     // 예전 ps: 값 줄에 상태 칸이 하나 더
        if(iPid < 0 || iName < 0) return null;
        List<Ps> list = new ArrayList<Ps>();
        Ps lastProc = null;
        for(int i = h + 1; i < arLine.length; i++)
        {
            String[] t = arLine[i].trim().split("\\s+");
            if(t.length < head.length) continue;
            int nNameAt = iName + (bOldPs ? t.length - head.length : 0);
            if(nNameAt >= t.length) continue;
            StringBuilder sb = new StringBuilder();
            for(int k = nNameAt; k < t.length; k++) sb.append(k > nNameAt ? " " : "").append(t[k]);
            Ps p = new Ps();
            p.m_strUser  = get(t, iUser);
            p.m_strPid   = get(t, iPid);
            p.m_strPpid  = get(t, iPpid);
            p.m_strVsize = get(t, iVsz);
            p.m_strRss   = get(t, iRss);
            p.m_strWchan = get(t, iWchan);
            p.m_strName  = sb.toString();
            if(!p.m_strPid.matches("\\d+")) continue;
            // 스레드 보기: toybox는 TID 열, 예전 ps -t는 프로세스 줄 아래에 스레드 줄(PPID = 프로세스 PID)
            if(bThread && iTid >= 0)
            {
                p.m_strTid = get(t, iTid);
                p.m_bThread = !p.m_strTid.equals(p.m_strPid);
            }
            else if(bThread && lastProc != null && p.m_strPpid.equals(lastProc.m_strPid) && !iThreadParentIsProcess(p, lastProc))
            {
                p.m_bThread = true;
                p.m_strTid = p.m_strPid;
                p.m_strPid = lastProc.m_strPid;
            }
            if(!p.m_bThread) lastProc = p;
            list.add(p);
        }
        return list;
    }

    // 예전 ps -t에서 진짜 자식 프로세스(이름이 다른 별도 프로세스)와 스레드를 구별하기 어렵다: 스레드는 RSS·VSIZE가 프로세스와 같다
    static boolean iThreadParentIsProcess(Ps p, Ps parent)
    {
        return !(p.m_strVsize.equals(parent.m_strVsize) && p.m_strRss.equals(parent.m_strRss));
    }

    static int idx(String[] a, String s) { for(int i = 0; i < a.length; i++) if(a[i].equalsIgnoreCase(s)) return i; return -1; }
    static String get(String[] t, int i) { return i >= 0 && i < t.length ? t[i] : ""; }

    void applyFilter()
    {
        final String[] arName = tokens(m_tfName.getText()), arPid = tokens(m_tfPid.getText()), arUser = tokens(m_tfUser.getText());
        if(m_model.m_bInfoRows || (arName.length == 0 && arPid.length == 0 && arUser.length == 0))
            m_sorter.setRowFilter(null);
        else
            m_sorter.setRowFilter(new javax.swing.RowFilter<PsModel, Integer>()
            {
                public boolean include(Entry<? extends PsModel, ? extends Integer> e)
                {
                    Ps p = m_model.row(e.getIdentifier());
                    if(p == null) return false;
                    return any(arName, p.m_strName.toLowerCase(), false)
                        && (arPid.length == 0 || any(arPid, p.m_strPid, true) || any(arPid, p.m_strPpid, true) || (p.m_strTid != null && any(arPid, p.m_strTid, true)))
                        && any(arUser, p.m_strUser.toLowerCase(), false);
                }
            });
        updateInfo();
    }

    static String[] tokens(String s)
    {
        List<String> ar = new ArrayList<String>();
        for(String t : s.toLowerCase().split("\\|")) if(t.trim().length() > 0) ar.add(t.trim());
        return ar.toArray(new String[0]);
    }

    static boolean any(String[] ar, String v, boolean bExact)
    {
        if(ar.length == 0) return true;
        for(String t : ar) if(bExact ? v.equals(t) : v.contains(t)) return true;
        return false;
    }

    void updateInfo()
    {
        if(m_model.m_bInfoRows) return;
        int nAll = m_model.getRowCount(), nShown = m_table.getRowCount(), nProc = 0;
        for(Ps p : m_model.m_rows) if(!p.m_bThread) nProc++;
        m_lbInfo.setText((m_strNotice != null ? m_strNotice + "   |   " : "") + deviceText() + " : 프로세스 " + nProc + "개" + (m_chkThread.isSelected() ? ", 스레드 포함 " + nAll + "줄" : "")
            + (nShown != nAll ? ", 필터로 " + nShown + "줄 보임" : "") + ",  " + new SimpleDateFormat("HH:mm:ss").format(new Date()) + " 읽음"
            + (m_interval != null && m_interval.isOn() ? " (" + m_interval.seconds() + "초마다 자동 갱신)" : ""));
    }

    Ps selected()
    {
        int nView = m_table.getSelectedRow();
        if(nView < 0) return null;
        Ps p = m_model.row(m_table.convertRowIndexToModel(nView));
        return p == null || p.m_bHelp || p.m_bError ? null : p;
    }

    // ---- Kill ----

    void kill()
    {
        final Ps p = selected();
        if(p == null) { JOptionPane.showMessageDialog(this, "종료할 프로세스를 표에서 선택하세요 (먼저 Refresh)."); return; }
        if(JOptionPane.showConfirmDialog(this, p.m_strName + " (PID " + p.m_strPid + ") 를 종료할까요?\nadb shell kill " + p.m_strPid + "\n(권한이 없으면 종료되지 않습니다)", "Kill",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        doKill(p);
    }

    // 확인 없이 kill (확인은 kill()에서)
    void doKill(final Ps p)
    {
        final String strDevice = device();
        new Thread(() ->
        {
            String[] r = DevicePropDialog.runAdb(strDevice, 8000, "shell", "kill " + p.m_strPid);
            String[] chk = DevicePropDialog.runAdb(strDevice, 8000, "shell", "ls -d /proc/" + p.m_strPid);
            boolean bGone = chk[1] == null && chk[0].contains("No such file");
            final String strMsg = r[1] != null ? "Kill 실패 : " + r[1]
                : bGone ? p.m_strName + " (PID " + p.m_strPid + ") 를 종료했습니다"
                : "Kill : " + (r[0].trim().length() > 0 ? r[0].trim() : "프로세스가 아직 있습니다") + " — 권한이 없거나 바로 다시 시작됐을 수 있습니다";
            SwingUtilities.invokeLater(() -> { m_strNotice = strMsg; m_lbInfo.setText(strMsg); m_main.setStatus("PS : " + strMsg); refresh(true); });
        }, "PsKill").start();
    }

    // ---- 프로세스 정보 (Status / Info / Stat / Maps / Stack / FD) ----

    void showInfo(final String strWhat, final String strCmdFormat)
    {
        final Ps p = selected();
        if(p == null) { JOptionPane.showMessageDialog(this, "프로세스를 표에서 선택하세요 (먼저 Refresh)."); return; }
        final int nPid = Integer.parseInt(p.m_strPid);
        final String strCmd = String.format(strCmdFormat, nPid);
        final String strTitle = strWhat + " — " + p.m_strName + " (PID " + nPid + ")";
        final InfoDialog dlg = new InfoDialog(this, strTitle, strCmd, device());
        dlg.load();
        dlg.setVisible(true);
    }

    /** 명령 결과를 보여 주는 창: 다시 읽기, 복사, 저장 */
    static class InfoDialog extends JDialog
    {
        private static final long serialVersionUID = 1L;
        final String m_strCmd, m_strDevice;
        final JTextArea m_ta = new JTextArea();
        final JLabel m_lb = new JLabel(" ");

        InfoDialog(JDialog owner, String strTitle, String strCmd, String strDevice)
        {
            super(owner, strTitle, false);
            m_strCmd = strCmd;
            m_strDevice = strDevice;
            m_ta.setEditable(false);
            m_ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
            JButton b1 = new JButton("다시 읽기"); b1.addActionListener(e -> load());
            JButton b2 = new JButton("복사"); b2.addActionListener(e -> { m_ta.selectAll(); m_ta.copy(); m_ta.select(0, 0); m_lb.setText("클립보드에 복사했습니다"); });
            JButton b3 = new JButton("저장…"); b3.addActionListener(e -> save());
            bar.add(b1); bar.add(b2); bar.add(b3);
            bar.add(new JLabel("   adb shell " + strCmd));
            m_lb.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            getContentPane().add(bar, BorderLayout.NORTH);
            getContentPane().add(new JScrollPane(m_ta), BorderLayout.CENTER);
            getContentPane().add(m_lb, BorderLayout.SOUTH);
            setSize(820, 600);
            setLocationRelativeTo(owner);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "Close");
            getRootPane().getActionMap().put("Close", new javax.swing.AbstractAction() { private static final long serialVersionUID = 1L; public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
        }

        void load()
        {
            m_ta.setText("읽는 중…");
            new Thread(() ->
            {
                String[] r = DevicePropDialog.runAdb(m_strDevice, 30000, "shell", m_strCmd);
                final String s = r[1] != null ? r[1] : r[0];
                final int nLines = s.split("\n").length;
                SwingUtilities.invokeLater(() ->
                {
                    m_ta.setText(s.trim().length() > 0 ? s : "(출력 없음 — 프로세스가 이미 끝났거나 권한이 없을 수 있습니다)");
                    m_ta.setCaretPosition(0);
                    m_lb.setText(nLines + "줄,  " + new SimpleDateFormat("HH:mm:ss").format(new Date()) + " 읽음" + (r[1] != null ? "  (오류)" : ""));
                });
            }, "PsInfo").start();
        }

        void save()
        {
            JFileChooser ch = new JFileChooser();
            ch.setSelectedFile(new File(getTitle().replaceAll("[\\\\/:*?\"<>| ]+", "_") + ".txt"));
            if(ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try(Writer w = new OutputStreamWriter(new FileOutputStream(ch.getSelectedFile()), DevicePropDialog.UTF8))
            {
                w.write(m_ta.getText());
                m_lb.setText("저장했습니다 : " + ch.getSelectedFile().getAbsolutePath());
            }
            catch(IOException e)
            {
                JOptionPane.showMessageDialog(this, "저장할 수 없습니다 : " + e.getMessage());
            }
        }

        String text() { return m_ta.getText(); }
    }

    // ---- 표 ----

    static class Ps
    {
        String m_strName = "", m_strPid = "", m_strPpid = "", m_strUser = "", m_strVsize = "", m_strRss = "", m_strWchan = "", m_strTid;
        boolean m_bThread, m_bHelp, m_bError;
        static Ps text(String s, boolean bError) { Ps p = new Ps(); p.m_strName = s; p.m_bHelp = !bError; p.m_bError = bError; return p; }
    }

    class PsModel extends AbstractTableModel
    {
        private static final long serialVersionUID = 1L;
        List<Ps> m_rows = new ArrayList<Ps>();
        boolean m_bInfoRows;

        public int getRowCount()    { return m_rows.size(); }
        public int getColumnCount() { return COLUMNS.length; }
        public String getColumnName(int c) { return COLUMNS[c]; }
        public Object getValueAt(int r, int c)
        {
            Ps p = m_rows.get(r);
            switch(c)
            {
                case 0: return p.m_bThread ? "    └ " + p.m_strName + "  (TID " + p.m_strTid + ")" : p.m_strName;
                case 1: return p.m_strPid;
                case 2: return p.m_strPpid;
                case 3: return p.m_strUser;
                case 4: return p.m_strVsize;
                case 5: return p.m_strRss;
                default: return p.m_strWchan;
            }
        }
        Ps row(int r) { return r >= 0 && r < m_rows.size() ? m_rows.get(r) : null; }

        // 처음 열었을 때: 사용법 (이미지처럼)
        void setHelp()
        {
            List<Ps> ar = new ArrayList<Ps>();
            for(String s : new String[]{ "[프로세스 리스트]", "- Refresh : 갱신 'ps'", "- Kill : 종료 'kill [PID]'", "- Status : 상태 'cat /proc/[PID]/status'",
                    "- Info : 메모리 정보 'dumpsys meminfo [PID]'", "- Stat : 통계 'cat /proc/[PID]/stat'", "- Maps : 메모리 맵 'cat /proc/[PID]/maps'",
                    "- Stack : 스택 'debuggerd -b [PID]'", "- FD : 열린 파일 'ls -l /proc/[PID]/fd'", "",
                    "Refresh(F5)를 누르면 목록을 읽습니다. 행을 고르고 버튼을 누르세요 (더블클릭 = Status, Delete = Kill)." })
                ar.add(Ps.text(s, false));
            m_rows = ar;
            m_bInfoRows = true;
            fireTableDataChanged();
        }

        void setError(String s)
        {
            List<Ps> ar = new ArrayList<Ps>();
            for(String t : s.split("\n")) if(t.trim().length() > 0) ar.add(Ps.text(t.trim(), true));
            ar.add(Ps.text("Try Again.", true));
            m_rows = ar;
            m_bInfoRows = true;
            m_sorter.setRowFilter(null);
            fireTableDataChanged();
        }

        void setRows(List<Ps> ar)
        {
            // 선택한 프로세스를 유지한다
            Ps sel = selected();
            String strSel = sel != null ? sel.m_strPid + "/" + sel.m_strTid : null;
            m_rows = ar;
            m_bInfoRows = false;
            fireTableDataChanged();
            if(strSel != null)
                for(int i = 0; i < ar.size(); i++)
                    if((ar.get(i).m_strPid + "/" + ar.get(i).m_strTid).equals(strSel))
                    {
                        // 자동 갱신 중에는 스크롤 위치를 건드리지 않고 선택만 유지한다
                        int v = m_table.convertRowIndexToView(i);
                        if(v >= 0) m_table.setRowSelectionInterval(v, v);
                        break;
                    }
        }
    }

    static final String[] COLUMNS = { "Process", "PID", "PPID", "User", "VSIZE", "RSS", "WCHAN" };
}
