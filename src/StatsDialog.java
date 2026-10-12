import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/** 장치 통계 자세히: CPU·메모리 그래프(최근 2분), 코어별 사용률, 메모리, CPU 상위 프로세스 (1.21) */
public class StatsDialog extends JDialog
{
    private static final long serialVersionUID = 1L;
    static final Color COLOR_CPU = new Color(0x1E6FD9);
    static final Color COLOR_MEM = new Color(0x2E9E44);

    final DeviceStats      m_stats;
    final JLabel           m_lbSummary = new JLabel(" ");
    final JLabel           m_lbMem     = new JLabel(" ");
    final GraphPanel       m_graph     = new GraphPanel();
    final CorePanel        m_cores     = new CorePanel();
    final javax.swing.JComboBox<String> m_cbInterval = new javax.swing.JComboBox<String>();
    boolean                m_bSyncing;
    final DefaultTableModel m_modelTop = new DefaultTableModel(new Object[]{ "PID", "CPU %", "프로세스" }, 0)
    {
        private static final long serialVersionUID = 1L;
        public boolean isCellEditable(int row, int column) { return false; }
    };

    StatsDialog(JFrame owner, DeviceStats stats)
    {
        super(owner, "장치 통계", false);
        m_stats = stats;
        m_lbSummary.setFont(m_lbSummary.getFont().deriveFont(Font.BOLD, 13f));

        // 측정 주기 고르기 (1.23)
        for(int nSec : DeviceStats.INTERVALS_SEC)
            m_cbInterval.addItem(nSec + "초");
        m_cbInterval.setToolTipText("장치 통계를 읽는 주기");
        m_cbInterval.addActionListener(new java.awt.event.ActionListener()
        {
            public void actionPerformed(java.awt.event.ActionEvent e)
            {
                if(m_bSyncing || m_cbInterval.getSelectedIndex() < 0) return;
                int nSec = DeviceStats.INTERVALS_SEC[m_cbInterval.getSelectedIndex()];
                if(getOwner() instanceof LogFilterMain) ((LogFilterMain)getOwner()).setStatsInterval(nSec);
                else m_stats.setInterval(nSec * 1000L);
            }
        });
        JPanel jpInterval = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        jpInterval.add(new JLabel("주기 :"));
        jpInterval.add(m_cbInterval);
        JPanel jpLine1 = new JPanel(new BorderLayout());
        jpLine1.add(m_lbSummary, BorderLayout.CENTER);
        jpLine1.add(jpInterval, BorderLayout.EAST);

        JPanel jpTop = new JPanel(new GridLayout(2, 1));
        jpTop.add(jpLine1);
        jpTop.add(m_lbMem);
        jpTop.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));

        m_graph.setPreferredSize(new Dimension(520, 160));
        syncInterval();
        m_cores.setBorder(BorderFactory.createTitledBorder("코어별 CPU"));

        JTable tbTop = new JTable(m_modelTop);
        tbTop.getColumnModel().getColumn(0).setPreferredWidth(70);
        tbTop.getColumnModel().getColumn(1).setPreferredWidth(60);
        tbTop.getColumnModel().getColumn(2).setPreferredWidth(380);
        JScrollPane spTop = new JScrollPane(tbTop);
        spTop.setPreferredSize(new Dimension(520, 200));
        spTop.setBorder(BorderFactory.createTitledBorder("CPU 상위 프로세스 (top)"));

        JPanel jpCenter = new JPanel(new BorderLayout());
        jpCenter.add(m_graph, BorderLayout.NORTH);
        jpCenter.add(m_cores, BorderLayout.CENTER);
        jpCenter.add(spTop, BorderLayout.SOUTH);

        getContentPane().add(jpTop, BorderLayout.NORTH);
        getContentPane().add(jpCenter, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(owner);

        // 열려 있을 때만 top을 읽는다 (adb 부하를 줄이려고)
        addWindowListener(new WindowAdapter()
        {
            public void windowOpened(WindowEvent e)  { m_stats.setWantTop(true); }
            public void windowClosed(WindowEvent e)  { m_stats.setWantTop(false); }
            public void windowClosing(WindowEvent e) { m_stats.setWantTop(false); }
        });
        setDefaultCloseOperation(HIDE_ON_CLOSE);
    }

    public void setVisible(boolean b)
    {
        m_stats.setWantTop(b);
        super.setVisible(b);
    }

    // 측정 주기에 맞춰 콤보 선택과 그래프 제목(최근 60회 = 주기 × 60)을 바꾼다
    void syncInterval()
    {
        long nSec = m_stats.interval() / 1000;
        m_bSyncing = true;
        for(int i = 0; i < DeviceStats.INTERVALS_SEC.length; i++)
            if(DeviceStats.INTERVALS_SEC[i] == nSec) m_cbInterval.setSelectedIndex(i);
        m_bSyncing = false;
        long nSpan = nSec * DeviceStats.HISTORY;
        String strSpan = nSpan >= 3600 ? (nSpan / 3600) + "시간" : nSpan >= 60 ? (nSpan / 60) + "분" : nSpan + "초";
        m_graph.setBorder(BorderFactory.createTitledBorder("최근 " + strSpan + " (" + nSec + "초마다, 파랑: CPU, 초록: 메모리)"));
    }

    // EDT에서 호출
    void update(DeviceStats.Sample sample)
    {
        m_lbSummary.setText(sample.m_strError != null ? sample.m_strError
                            : (sample.m_strDevice.length() > 0 ? sample.m_strDevice + " — " : "") + sample.summary());
        if(sample.m_strError == null && sample.m_nMemTotalKb > 0)
            m_lbMem.setText(String.format("메모리: 사용 %,d MB / 전체 %,d MB, 사용 가능 %,d MB",
                (sample.m_nMemTotalKb - sample.m_nMemAvailKb) / 1024, sample.m_nMemTotalKb / 1024, sample.m_nMemAvailKb / 1024));
        m_graph.setHistory(m_stats.history());
        if(sample.m_strError == null && sample.m_dCpu >= 0)     // 첫 측정은 이전 값이 없어 코어 값도 없음
        {
            m_cores.setCores(sample.m_arCore);
            // 코어 수에 맞게 창 높이를 늘린다 (처음 한 번)
            if(m_cores.getPreferredSize().height > m_cores.getHeight())
                pack();
        }
        if(sample.m_strError == null && !sample.m_arTop.isEmpty())
        {
            m_modelTop.setRowCount(0);
            for(String[] ar : sample.m_arTop)
                m_modelTop.addRow(new Object[]{ ar[0], ar[1], ar[2] });
        }
    }

    /** CPU·메모리 사용률 그래프 */
    static class GraphPanel extends JPanel
    {
        private static final long serialVersionUID = 1L;
        List<DeviceStats.Sample> m_arHistory = new ArrayList<DeviceStats.Sample>();

        void setHistory(List<DeviceStats.Sample> ar) { m_arHistory = ar; repaint(); }

        protected void paintComponent(Graphics g0)
        {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D)g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x0 = 40, y0 = 30, w = getWidth() - x0 - 14, h = getHeight() - y0 - 14;
            g.setColor(Color.WHITE);
            g.fillRect(x0, y0, w, h);
            g.setColor(new Color(0xDDDDDD));
            for(int p = 0; p <= 100; p += 25)
            {
                int y = y0 + h - h * p / 100;
                g.drawLine(x0, y, x0 + w, y);
                g.setColor(Color.GRAY);
                g.drawString(p + "%", 4, y + 4);
                g.setColor(new Color(0xDDDDDD));
            }
            drawLine(g, x0, y0, w, h, true);
            drawLine(g, x0, y0, w, h, false);
        }

        void drawLine(Graphics2D g, int x0, int y0, int w, int h, boolean bCpu)
        {
            g.setColor(bCpu ? COLOR_CPU : COLOR_MEM);
            int nPrevX = -1, nPrevY = -1;
            int n = DeviceStats.HISTORY;
            List<DeviceStats.Sample> ar = m_arHistory;
            for(int i = 0; i < ar.size(); i++)
            {
                double v = bCpu ? ar.get(i).m_dCpu : ar.get(i).memUsedPercent();
                if(v < 0) { nPrevX = -1; continue; }
                int x = x0 + w * (n - ar.size() + i) / (n - 1);
                int y = y0 + h - (int)(h * v / 100);
                if(nPrevX >= 0) g.drawLine(nPrevX, nPrevY, x, y);
                else g.fillOval(x - 2, y - 2, 4, 4);
                nPrevX = x; nPrevY = y;
            }
        }
    }

    /** 코어별 사용률 막대 */
    static class CorePanel extends JPanel
    {
        private static final long serialVersionUID = 1L;
        double[] m_arCore = new double[0];

        static final int COLS = 2;

        void setCores(double[] ar) { m_arCore = ar; setPreferredSize(new Dimension(520, 30 + ((ar.length + COLS - 1) / COLS) * 20)); revalidate(); repaint(); }

        protected void paintComponent(Graphics g)
        {
            super.paintComponent(g);
            int nCols = COLS, cw = (getWidth() - 20) / nCols;
            for(int i = 0; i < m_arCore.length; i++)
            {
                int x = 10 + (i % nCols) * cw, y = 20 + (i / nCols) * 20;
                double v = m_arCore[i];
                g.setColor(Color.DARK_GRAY);
                g.drawString("cpu" + i, x, y + 12);
                int bx = x + 44, bw = cw - 100;
                g.setColor(new Color(0xEEEEEE));
                g.fillRect(bx, y + 2, bw, 12);
                if(v >= 0)
                {
                    g.setColor(COLOR_CPU);
                    g.fillRect(bx, y + 2, (int)(bw * v / 100), 12);
                }
                g.setColor(Color.DARK_GRAY);
                g.drawString(v < 0 ? "꺼짐" : String.format("%.0f%%", v), bx + bw + 4, y + 12);
            }
        }
    }
}
