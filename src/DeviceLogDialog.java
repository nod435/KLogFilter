import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * 장치 로그 열기 (1.59): 연결된 장치의 /LGU_data/logs 폴더(경로 변경 가능, 설정에 저장)를 보여 주고,
 * 고른 파일을 adb pull로 실행 폴더의 DeviceLogs\시리얼\ 에 받아 로그 화면에 연다. .gz는 여는 쪽(1.41)에서 풀어서 보여 준다.
 * - 폴더는 더블클릭으로 들어가고 [위로]로 나온다. 이름·크기·수정 시각으로 정렬(기본: 최근 것 먼저), 이름 필터
 * - 받는 동안 받은 크기를 보이고 [취소]할 수 있다
 */
public class DeviceLogDialog extends JDialog
{
    private static final long serialVersionUID = 1L;
    static final String DEFAULT_PATH = "/LGU_data/logs";
    static final String NOENT = "KLOG_NOENT";

    static class Entry
    {
        final String m_strName;      // 파일 이름
        final String m_strPath;      // 장치 경로
        final boolean m_bDir;
        final long m_nSize, m_nTime;    // 크기, 수정 시각(초)
        Entry(String name, String path, boolean dir, long size, long time) { m_strName = name; m_strPath = path; m_bDir = dir; m_nSize = size; m_nTime = time; }
    }

    final LogFilterMain m_main;
    final JLabel     m_lbDevice = new JLabel("-");
    final JTextField m_tfPath   = new JTextField(22);
    final JTextField m_tfFilter = new JTextField(12);
    final JLabel     m_lbInfo   = new JLabel(" ");
    final EntryModel m_model    = new EntryModel();
    final JTable     m_table    = new JTable(m_model);
    final JButton    m_btnOpen, m_btnCancel, m_btnUp;
    String           m_strSerial;               // 마지막으로 읽은 장치의 시리얼 (받을 폴더 이름)
    String           m_strListed;               // 지금 목록의 폴더
    volatile boolean m_bListing;
    volatile Process m_pull;                    // 받는 중인 adb pull
    int              m_nSortCol = 2;            // 0 이름, 1 크기, 2 수정 시각
    boolean          m_bSortAsc = false;

    DeviceLogDialog(LogFilterMain main)
    {
        super(main, "장치 로그 열기", false);
        m_main = main;

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        JLabel lbDev = new JLabel("Device : ");
        lbDev.setFont(lbDev.getFont().deriveFont(Font.BOLD));
        m_lbDevice.setFont(m_lbDevice.getFont().deriveFont(Font.BOLD));
        bar.add(lbDev);
        bar.add(m_lbDevice);
        bar.add(new JLabel("   경로 : "));
        m_tfPath.setText(main.m_strLguLogPath == null || main.m_strLguLogPath.length() == 0 ? DEFAULT_PATH : main.m_strLguLogPath);
        m_tfPath.setToolTipText("장치의 로그 폴더 (기본 " + DEFAULT_PATH + "), Enter로 읽기 — 설정에 저장");
        m_tfPath.addActionListener(e -> listTyped());
        bar.add(m_tfPath);
        bar.add(button("읽기", "이 폴더의 파일 목록을 장치에서 다시 읽기 (F5)", e -> listTyped()));
        m_btnUp = button("위로", "상위 폴더로", e -> goUp());
        bar.add(m_btnUp);
        bar.add(new JLabel("   Filter : "));
        m_tfFilter.setToolTipText("파일 이름에 들어 있는 글자 (대소문자 무시, 여러 개는 | 로)");
        m_tfFilter.getDocument().addDocumentListener(new DocumentListener()
        {
            public void insertUpdate(DocumentEvent e)  { m_model.refilter(); updateInfo(null); }
            public void removeUpdate(DocumentEvent e)  { m_model.refilter(); updateInfo(null); }
            public void changedUpdate(DocumentEvent e) { m_model.refilter(); updateInfo(null); }
        });
        bar.add(m_tfFilter);

        m_table.setFillsViewportHeight(true);
        m_table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        m_table.getColumnModel().getColumn(0).setPreferredWidth(420);
        m_table.getColumnModel().getColumn(1).setPreferredWidth(90);
        m_table.getColumnModel().getColumn(2).setPreferredWidth(150);
        m_table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer()
        {
            private static final long serialVersionUID = 1L;
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col)
            {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                Entry en = m_model.row(row);
                setHorizontalAlignment(col == 1 ? RIGHT : LEFT);
                if(!sel) c.setForeground(en != null && en.m_bDir ? new Color(0x1A237E) : Color.BLACK);
                setFont(t.getFont().deriveFont(en != null && en.m_bDir ? Font.BOLD : Font.PLAIN));
                return c;
            }
        });
        // 머리글을 누르면 그 열로 정렬 (다시 누르면 반대로)
        m_table.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter()
        {
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                int c = m_table.columnAtPoint(e.getPoint());
                if(c < 0) return;
                if(c == m_nSortCol) m_bSortAsc = !m_bSortAsc; else { m_nSortCol = c; m_bSortAsc = c == 0; }
                m_model.refilter();
            }
        });
        m_table.addMouseListener(new java.awt.event.MouseAdapter()
        {
            public void mouseClicked(java.awt.event.MouseEvent e) { if(e.getClickCount() == 2) openSelected(); }
        });
        m_table.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "klogOpen");
        m_table.getActionMap().put("klogOpen", new javax.swing.AbstractAction() { private static final long serialVersionUID = 1L; public void actionPerformed(java.awt.event.ActionEvent e) { openSelected(); } });

        JPanel south = new JPanel(new BorderLayout());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        m_btnOpen = button("열기", "고른 파일을 PC로 받아 로그 화면에 열기 (.gz는 풀어서), 폴더면 들어가기 — 더블클릭·Enter", e -> openSelected());
        m_btnOpen.setFont(m_btnOpen.getFont().deriveFont(Font.BOLD));
        m_btnCancel = button("취소", "받는 것을 멈춤", e -> cancelPull());
        m_btnCancel.setEnabled(false);
        btns.add(m_btnOpen);
        btns.add(m_btnCancel);
        btns.add(button("받은 폴더", "받은 파일이 있는 PC 폴더 열기", e -> openLocalFolder()));
        south.add(btns, BorderLayout.WEST);
        m_lbInfo.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        m_lbInfo.setForeground(Color.DARK_GRAY);
        south.add(m_lbInfo, BorderLayout.SOUTH);

        getContentPane().add(bar, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(m_table), BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(new Dimension(900, 540));
        setLocationRelativeTo(main);
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        bindKey(java.awt.event.KeyEvent.VK_F5, 0, "Reload", () -> list(m_tfPath.getText().trim()));
        bindKey(java.awt.event.KeyEvent.VK_BACK_SPACE, 0, "Up", () -> { if(!(getFocusOwner() instanceof JTextField)) goUp(); });
        bindKey(java.awt.event.KeyEvent.VK_ESCAPE, 0, "Close", () -> setVisible(false));
        Runtime.getRuntime().addShutdownHook(new Thread(this::cancelPull, "DeviceLogPullStop"));
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

    String device()
    {
        String s = m_main.m_strSelectedDevice;
        return s == null ? "" : s.trim();
    }

    // 창을 열 때: root로 바꾼 뒤(1.61) 지금 경로를 다시 읽는다
    void showFor()
    {
        setVisible(true);
        toFront();
        rootThenList();
    }

    String m_strRootNote;               // root 결과 (안내 줄 뒤에 붙임)
    volatile boolean m_bRooting;

    /**
     * 1.61: 버튼을 누르면 먼저 adb root. 이미 root면 바로 목록.
     * adb root는 adbd를 다시 시작해 연결이 잠시 끊기므로, 실시간 수집 중이면 먼저 묻고(Root 버튼과 같음) 다시 연결될 때까지 기다린다.
     * 판매용 빌드처럼 root가 안 되면 그 이유를 알리고 그대로 목록을 읽는다.
     */
    void rootThenList()
    {
        if(m_bRooting) return;
        m_bRooting = true;
        final String strDevice = device();
        final String strPath = m_tfPath.getText().trim();
        updateInfo("권한 확인 중…");
        new Thread(() ->
        {
            String strNote = null;
            Boolean bRoot = RootControl.isRoot(strDevice);
            if(bRoot != null && !bRoot)
            {
                final boolean[] bGo = { true };
                if(m_main.m_source.isRunning())
                {
                    try
                    {
                        SwingUtilities.invokeAndWait(() ->
                        {
                            String strWait = m_main.m_source.m_bAutoReconnect ? "'연결 대기'가 켜져 있어 다시 연결되면 이어서 수집합니다." : "'연결 대기'가 꺼져 있어 수집이 멈춥니다 (Stop).";
                            bGo[0] = javax.swing.JOptionPane.showConfirmDialog(this, "장치 로그를 읽기 위해 adb root를 실행합니다.\nadbd가 다시 시작되어 실시간 로그 수집이 잠시 끊깁니다. " + strWait + "\n계속할까요? (아니요: root 없이 목록 읽기)",
                                    "Root", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE) == javax.swing.JOptionPane.YES_OPTION;
                        });
                    }
                    catch(Exception e) { bGo[0] = false; }
                }
                if(bGo[0])
                {
                    SwingUtilities.invokeLater(() -> updateInfo("root로 바꾸는 중… (adb root, 연결이 잠시 끊김)"));
                    String[] r = DevicePropDialog.runAdb(strDevice, 15000, "root");
                    String strOut = (r[1] != null ? r[1] : r[0]).trim();
                    if(r[1] == null && (strOut.contains("restarting") || strOut.length() == 0))
                    {
                        try { Thread.sleep(1500); } catch(InterruptedException e) { }
                        DevicePropDialog.runAdb(strDevice, 20000, "wait-for-device");
                    }
                    Boolean bNow = RootControl.isRoot(strDevice);
                    if(bNow != null && bNow) strNote = "root로 바꿨습니다";
                    else if(strOut.contains("production")) strNote = "root로 바꿀 수 없는 장치입니다 (판매용 빌드) — root 없이 읽음";
                    else strNote = "adb root 실패 : " + strOut + " — root 없이 읽음";
                }
                else strNote = "root 없이 읽음";
            }
            else if(bRoot != null) strNote = "root";
            final String fNote = strNote;
            SwingUtilities.invokeLater(() ->
            {
                m_bRooting = false;
                m_strRootNote = fNote;
                if(fNote != null) m_main.setStatus("장치 로그 : " + fNote);
                list(strPath);
            });
        }, "DeviceLogRoot").start();
    }

    // ---- 목록 ----

    /** 시리얼 + 폴더 안 항목마다 "종류|크기|수정시각|경로" (하위 폴더는 들어가지 않음) */
    static String listCommand(String strPath)
    {
        return DevicePropDialog.SERIAL_ECHO + "; d=" + DevicePropDialog.shq(strPath) + "; if [ -d $d ]; then find $d -mindepth 1 -maxdepth 1 -exec stat -c '%F|%s|%Y|%n' {} +; else echo " + NOENT + "; fi";
    }

    static class ListResult { String m_strSerial, m_strError; List<Entry> m_ar = new ArrayList<Entry>(); }

    static ListResult parseList(String strOut)
    {
        ListResult res = new ListResult();
        StringBuilder sbErr = new StringBuilder();
        for(String strLine : strOut.replace("\r", "").split("\n"))
        {
            String s = strLine.trim();
            if(s.length() == 0) continue;
            if(s.startsWith("KLOG_SN=")) { res.m_strSerial = s.substring(8).trim(); continue; }
            if(s.equals(NOENT)) { res.m_strError = "폴더가 없습니다"; continue; }
            String[] ar = strLine.split("\\|", 4);
            if(ar.length == 4 && ar[1].matches("\\d+") && ar[2].matches("\\d+"))
            {
                String p = ar[3];
                String name = p.substring(p.lastIndexOf('/') + 1);
                res.m_ar.add(new Entry(name, p, ar[0].startsWith("directory"), Long.parseLong(ar[1]), Long.parseLong(ar[2])));
            }
            else sbErr.append(s).append('\n');
        }
        if(res.m_strError == null && res.m_ar.isEmpty() && sbErr.length() > 0)
        {
            String e = sbErr.toString().trim();
            res.m_strError = e + (e.contains("Permission denied") ? " — 권한이 없으면 프로퍼티 창의 Root 버튼으로 adb root 후 다시 읽으세요" : "");
        }
        return res;
    }

    // 경로를 직접 넣고 읽으면 그 경로를 설정에 남긴다 (폴더를 더블클릭해 들어간 것은 남기지 않음)
    void listTyped()
    {
        String s = m_tfPath.getText().trim();
        if(s.length() > 0) m_main.m_strLguLogPath = s;
        list(s);
    }

    void list(final String strPathIn)
    {
        final String strPath = strPathIn.length() > 1 && strPathIn.endsWith("/") ? strPathIn.substring(0, strPathIn.length() - 1) : strPathIn;
        if(strPath.length() == 0) { updateInfo("장치 폴더 경로를 입력하세요 (예: " + DEFAULT_PATH + ")"); return; }
        if(m_bListing) return;
        m_bListing = true;
        m_tfPath.setText(strPath);
        final String strDevice = device();
        m_lbDevice.setText(strDevice.length() > 0 ? strDevice : "(adb 기본 장치)");
        updateInfo("읽는 중… " + strPath);
        new Thread(() ->
        {
            String[] r = DevicePropDialog.runAdb(strDevice, 15000, "shell", listCommand(strPath));
            final ListResult res = r[1] != null ? new ListResult() : parseList(r[0]);
            if(r[1] != null) res.m_strError = r[1];
            SwingUtilities.invokeLater(() ->
            {
                m_bListing = false;
                if(res.m_strSerial != null && res.m_strSerial.length() > 0)
                {
                    m_strSerial = res.m_strSerial;
                    m_lbDevice.setText(strDevice.length() > 0 ? strDevice : "(adb 기본 장치 : " + m_strSerial + ")");
                }
                m_strListed = strPath;
                m_model.set(res.m_ar);
                m_btnUp.setEnabled(strPath.lastIndexOf('/') > 0);
                setTitle("장치 로그 열기 — " + strPath);
                updateInfo(res.m_strError != null ? "읽지 못함 : " + strPath + " — " + res.m_strError : null);
                if(m_table.getRowCount() > 0 && m_table.getSelectedRow() < 0) m_table.setRowSelectionInterval(0, 0);
            });
        }, "DeviceLogList").start();
    }

    void goUp()
    {
        String p = m_strListed != null ? m_strListed : m_tfPath.getText().trim();
        int n = p.lastIndexOf('/');
        if(n > 0) list(p.substring(0, n));
        else if(n == 0 && p.length() > 1) list("/");
    }

    void updateInfo(String strMsg)
    {
        if(strMsg != null) { m_lbInfo.setText(strMsg); return; }
        int nFiles = 0, nDirs = 0;
        long nBytes = 0;
        for(Entry e : m_model.m_all) { if(e.m_bDir) nDirs++; else { nFiles++; nBytes += e.m_nSize; } }
        m_lbInfo.setText((m_strListed != null ? m_strListed : "") + " : 파일 " + nFiles + "개 (" + sizeText(nBytes) + ")" + (nDirs > 0 ? ", 폴더 " + nDirs + "개" : "")
            + (m_model.getRowCount() != nFiles + nDirs ? ", 필터로 " + m_model.getRowCount() + "개 보임" : "")
            + (m_strRootNote != null ? "  [" + m_strRootNote + "]" : "")
            + "  —  더블클릭·Enter : 열기 (PC의 " + localDir().getPath() + " 에 받음)");
    }

    static String sizeText(long n)
    {
        if(n < 1024) return n + " B";
        if(n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        if(n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / 1048576.0);
        return String.format("%.2f GB", n / 1073741824.0);
    }

    // ---- 받아서 열기 ----

    /** 받을 PC 폴더: 실행 폴더\DeviceLogs\시리얼 (모르면 장치 이름) */
    File localDir()
    {
        String s = m_strSerial != null && m_strSerial.length() > 0 ? m_strSerial : device().length() > 0 ? device() : "device";
        return new File(new File(System.getProperty("user.dir"), "DeviceLogs"), s.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    void openSelected()
    {
        int nRow = m_table.getSelectedRow();
        Entry e = nRow >= 0 ? m_model.row(nRow) : null;
        if(e == null) { updateInfo("열 파일을 목록에서 고르세요"); return; }
        if(e.m_bDir) { list(e.m_strPath); return; }
        pull(e);
    }

    void pull(final Entry e)
    {
        if(m_pull != null) { updateInfo("받는 중입니다 — 끝나거나 취소한 뒤에 여세요"); return; }
        final File dir = localDir();
        dir.mkdirs();
        final File local = new File(dir, e.m_strName);
        final String strDevice = device();
        final long nStart = System.currentTimeMillis();
        final Process p;
        try
        {
            ProcessBuilder pb = new ProcessBuilder(DevicePropDialog.adbCommand(strDevice, "pull", e.m_strPath, local.getAbsolutePath()));
            pb.redirectErrorStream(true);
            p = pb.start();
        }
        catch(IOException ex)
        {
            updateInfo("adb를 실행할 수 없습니다 : " + ex.getMessage());
            return;
        }
        m_pull = p;
        m_btnCancel.setEnabled(true);
        m_btnOpen.setEnabled(false);
        // 받은 크기를 0.5초마다 보인다
        final javax.swing.Timer tm = new javax.swing.Timer(500, ev ->
        {
            long n = local.exists() ? local.length() : 0;
            updateInfo("받는 중 : " + e.m_strName + "  " + sizeText(n) + " / " + sizeText(e.m_nSize)
                + (e.m_nSize > 0 ? " (" + Math.min(100, n * 100 / e.m_nSize) + "%)" : "") + "  " + (System.currentTimeMillis() - nStart) / 1000 + "초");
        });
        tm.setInitialDelay(0);
        tm.start();
        new Thread(() ->
        {
            StringBuilder sb = new StringBuilder();
            try(BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), DevicePropDialog.UTF8)))
            {
                String s;
                while((s = r.readLine()) != null) sb.append(s).append('\n');
            }
            catch(IOException ex) { }
            int nExit;
            try { nExit = p.waitFor(); } catch(InterruptedException ex) { nExit = -1; }
            final int nCode = nExit;
            final boolean bCanceled = m_pull != p;
            final boolean bOk = !bCanceled && nExit == 0 && local.isFile();
            final String strOut = sb.toString().trim();
            SwingUtilities.invokeLater(() ->
            {
                tm.stop();
                if(m_pull == p) m_pull = null;
                m_btnCancel.setEnabled(false);
                m_btnOpen.setEnabled(true);
                if(bCanceled) { local.delete(); updateInfo("받기를 취소했습니다 : " + e.m_strName); return; }
                if(!bOk)
                {
                    String err = strOut.length() > 0 ? strOut.substring(strOut.lastIndexOf('\n') + 1) : "종료 코드 " + nCode;
                    updateInfo("받지 못함 : " + e.m_strName + " — " + err + (strOut.contains("Permission denied") ? " — 프로퍼티 창의 Root 버튼으로 adb root 후 다시 하세요" : ""));
                    return;
                }
                local.setLastModified(e.m_nTime * 1000);
                long nSec = Math.max(1, (System.currentTimeMillis() - nStart) / 1000);
                updateInfo("받았습니다 : " + local.getAbsolutePath() + " (" + sizeText(local.length()) + ", " + nSec + "초) — 로그 화면에 엽니다");
                m_main.setStatus("장치 로그 : " + e.m_strPath + " → " + local.getAbsolutePath());
                m_main.parseFile(local);        // .gz는 여기서 풀어서 연다 (1.41)
                m_main.toFront();
            });
        }, "DeviceLogPull").start();
    }

    void cancelPull()
    {
        Process p = m_pull;
        m_pull = null;
        if(p != null) p.destroy();
    }

    void openLocalFolder()
    {
        File dir = localDir();
        dir.mkdirs();
        try { java.awt.Desktop.getDesktop().open(dir); }
        catch(Exception ex) { updateInfo("폴더를 열 수 없습니다 : " + dir.getAbsolutePath()); }
    }

    // ---- 표 ----

    class EntryModel extends AbstractTableModel
    {
        private static final long serialVersionUID = 1L;
        final SimpleDateFormat m_fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        List<Entry> m_all   = new ArrayList<Entry>();
        List<Entry> m_shown = new ArrayList<Entry>();

        public int getRowCount()    { return m_shown.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int c)
        {
            String s = c == 0 ? "이름" : c == 1 ? "크기" : "수정 시각";
            return c == m_nSortCol ? s + (m_bSortAsc ? " ▲" : " ▼") : s;
        }
        public Object getValueAt(int r, int c)
        {
            Entry e = m_shown.get(r);
            return c == 0 ? (e.m_bDir ? e.m_strName + "/" : e.m_strName) : c == 1 ? (e.m_bDir ? "" : sizeText(e.m_nSize)) : m_fmt.format(new Date(e.m_nTime * 1000));
        }
        Entry row(int r) { return r >= 0 && r < m_shown.size() ? m_shown.get(r) : null; }

        void set(List<Entry> ar) { m_all = ar; refilter(); }

        void refilter()
        {
            Entry sel = row(m_table.getSelectedRow());
            List<String> arTok = new ArrayList<String>();
            for(String t : m_tfFilter.getText().toLowerCase().split("\\|")) if(t.trim().length() > 0) arTok.add(t.trim());
            List<Entry> shown = new ArrayList<Entry>();
            for(Entry e : m_all)
            {
                boolean b = arTok.isEmpty();
                for(String t : arTok) if(e.m_strName.toLowerCase().contains(t)) { b = true; break; }
                if(b) shown.add(e);
            }
            // 폴더 먼저, 그다음 고른 열로
            Comparator<Entry> cmp = m_nSortCol == 0 ? Comparator.comparing((Entry e) -> e.m_strName.toLowerCase())
                                  : m_nSortCol == 1 ? Comparator.comparingLong((Entry e) -> e.m_nSize)
                                  : Comparator.comparingLong((Entry e) -> e.m_nTime);
            if(!m_bSortAsc) cmp = cmp.reversed();
            Comparator<Entry> dirsFirst = Comparator.comparing((Entry e) -> !e.m_bDir);
            Collections.sort(shown, dirsFirst.thenComparing(cmp).thenComparing((Entry e) -> e.m_strName));
            m_shown = shown;
            fireTableDataChanged();
            m_table.getTableHeader().repaint();
            for(int i = 0; i < m_table.getColumnCount(); i++) m_table.getColumnModel().getColumn(i).setHeaderValue(getColumnName(i));
            if(sel != null)
                for(int i = 0; i < m_shown.size(); i++)
                    if(m_shown.get(i).m_strPath.equals(sel.m_strPath)) { m_table.setRowSelectionInterval(i, i); break; }
        }
    }
}
