import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
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
    static final Color COLOR_TEMP = new Color(0xEF6C00);     // 온도 (1.26)

    final DeviceStats      m_stats;
    final JLabel           m_lbSummary = new JLabel(" ");
    final JLabel           m_lbMem     = new JLabel(" ");
    final JLabel           m_lbReboot  = new JLabel("재부팅: 감지된 것 없음");
    final JLabel           m_lbTemp    = new JLabel("온도: -");
    final JLabel           m_lbPeriod  = new JLabel("측정 시간: -");         // 1.27
    final javax.swing.Timer m_tmPeriod = new javax.swing.Timer(1000, e -> updatePeriod());   // 남은 시간을 1초마다
    final GraphPanel       m_graph     = new GraphPanel();
    final JPanel           m_jpGraph   = new JPanel(new BorderLayout());        // 그래프 + 가로 스크롤 바 (제목 테두리) (1.31)
    final javax.swing.JScrollBar m_sbTime = new javax.swing.JScrollBar(javax.swing.JScrollBar.HORIZONTAL);
    final javax.swing.JComboBox<String> m_cbPx = new javax.swing.JComboBox<String>();   // 측정 하나의 가로 픽셀
    boolean                m_bSyncingScroll;
    final CorePanel        m_cores     = new CorePanel();
    final javax.swing.JComboBox<String> m_cbInterval = new javax.swing.JComboBox<String>();
    final javax.swing.JCheckBox m_chkCores = new javax.swing.JCheckBox("코어별");    // 그래프에 코어별 선 (1.29)
    // CPU·메모리·온도 표시 켜고 끄기: 그래프 선과 그 안내 줄(메모리·온도 줄, CPU는 코어별 막대) (1.30)
    final javax.swing.JCheckBox m_chkCpu  = new javax.swing.JCheckBox("CPU", true);
    final javax.swing.JCheckBox m_chkMem  = new javax.swing.JCheckBox("메모리", true);
    final javax.swing.JCheckBox m_chkTemp = new javax.swing.JCheckBox("온도", true);
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
        // 표시 항목 줄 (1.30): CPU·메모리·온도, 코어별 선(1.29). 요약 줄이 잘리지 않게 따로 한 줄
        JPanel jpShow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        jpShow.add(new JLabel("표시 :"));
        m_chkCpu.setToolTipText("CPU 사용률: 그래프의 파란 선과 코어별 CPU 막대");
        m_chkMem.setToolTipText("메모리: 그래프의 초록 선과 메모리 줄");
        m_chkTemp.setToolTipText("CPU 온도: 그래프의 주황 선과 온도 줄");
        m_chkCores.setToolTipText("그래프에 cpu0, cpu1, … 코어별 사용률 선도 그리기");
        for(javax.swing.JCheckBox chk : new javax.swing.JCheckBox[]{ m_chkCpu, m_chkMem, m_chkTemp, m_chkCores })
        {
            chk.setMargin(new java.awt.Insets(0, 0, 0, 0));
            chk.addActionListener(e -> applyShow(true));
            jpShow.add(chk);
        }
        JPanel jpInterval = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        jpInterval.add(new JLabel("주기 :"));
        jpInterval.add(m_cbInterval);
        // 측정 시간 (1.27)
        javax.swing.JButton btnDuration = new javax.swing.JButton("측정 시간…");
        btnDuration.setMargin(new java.awt.Insets(1, 6, 1, 6));
        btnDuration.setToolTipText("장치 통계를 몇 시간 동안 측정할지 정하기");
        btnDuration.addActionListener(e -> { if(getOwner() instanceof LogFilterMain) ((LogFilterMain)getOwner()).showStatsDurationDialog(); });
        jpInterval.add(btnDuration);
        JPanel jpLine1 = new JPanel(new BorderLayout());
        jpLine1.add(m_lbSummary, BorderLayout.CENTER);
        jpLine1.add(jpInterval, BorderLayout.EAST);

        m_lbReboot.setForeground(Color.GRAY);
        // 세로 상자: 메모리·온도 줄을 숨기면 그 자리가 없어진다 (GridLayout은 숨겨도 칸이 남음)
        JPanel jpTop = new JPanel();
        jpTop.setLayout(new javax.swing.BoxLayout(jpTop, javax.swing.BoxLayout.Y_AXIS));
        int nLine = Math.max(jpInterval.getPreferredSize().height, m_lbPeriod.getPreferredSize().height + 4);
        for(java.awt.Component c : new java.awt.Component[]{ jpLine1, m_lbPeriod, m_lbMem, m_lbTemp, m_lbReboot, jpShow })
        {
            if(c instanceof javax.swing.JComponent)
            {
                ((javax.swing.JComponent)c).setAlignmentX(LEFT_ALIGNMENT);
                // 안내 줄은 높이를 맞추고, 요약 줄·표시 줄(패널)은 폭을 내용대로 두어 요약이 잘리지 않게 한다
                if(c instanceof JLabel) c.setPreferredSize(new Dimension(520, nLine));
                c.setMaximumSize(new Dimension(Integer.MAX_VALUE, nLine));
            }
            jpTop.add(c);
        }
        jpTop.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));

        // 그래프 아래 가로 스크롤 바: 전체 기록에서 보이는 구간을 옮긴다 (1.31)
        m_sbTime.setToolTipText("측정 전체 기록에서 보이는 구간 옮기기 (그래프 위에서 마우스 휠도 됨). 맨 오른쪽에 두면 새 측정을 따라갑니다");
        m_sbTime.addAdjustmentListener(e -> { if(!m_bSyncingScroll) m_graph.setOffset(e.getValue()); });
        m_graph.m_onScroll = this::syncScroll;
        m_jpGraph.add(m_graph, BorderLayout.CENTER);
        m_jpGraph.add(m_sbTime, BorderLayout.SOUTH);
        m_jpGraph.setPreferredSize(new Dimension(520, 210));
        for(int nPx : GraphPanel.PX_CHOICES) m_cbPx.addItem(nPx + "px");
        m_cbPx.setSelectedIndex(2);
        m_cbPx.setToolTipText("측정 하나를 몇 픽셀로 그릴지 (작을수록 긴 시간이 한 화면에)");
        m_cbPx.addActionListener(e -> { if(m_cbPx.getSelectedIndex() >= 0) m_graph.setPx(GraphPanel.PX_CHOICES[m_cbPx.getSelectedIndex()]); });
        jpShow.add(new JLabel("   간격 :"));
        jpShow.add(m_cbPx);
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
        jpCenter.add(m_jpGraph, BorderLayout.NORTH);
        jpCenter.add(m_cores, BorderLayout.CENTER);
        jpCenter.add(spTop, BorderLayout.SOUTH);

        // 아래: 통계를 이미지로 복사·저장 (1.25)
        javax.swing.JButton btnCopy = new javax.swing.JButton("이미지로 복사");
        btnCopy.setToolTipText("지금 보이는 통계를 이미지로 클립보드에 복사 (Ctrl+C) — 메일·메신저·문서에 붙여넣기");
        btnCopy.addActionListener(new java.awt.event.ActionListener()
        {
            public void actionPerformed(java.awt.event.ActionEvent e) { copyImage(); }
        });
        javax.swing.JButton btnSave = new javax.swing.JButton("이미지 저장…");
        btnSave.setToolTipText("지금 보이는 통계를 PNG 파일로 저장");
        btnSave.addActionListener(new java.awt.event.ActionListener()
        {
            public void actionPerformed(java.awt.event.ActionEvent e) { saveImage(); }
        });
        // 측정 전체 기록을 엑셀 파일로 (1.28)
        javax.swing.JButton btnExcel = new javax.swing.JButton("엑셀로 저장…");
        btnExcel.setToolTipText("측정을 켠 뒤의 전체 기록을 엑셀 파일(.xlsx)로 저장 — 시각·CPU·메모리·온도·코어별·상위 프로세스");
        btnExcel.addActionListener(new java.awt.event.ActionListener()
        {
            public void actionPerformed(java.awt.event.ActionEvent e) { saveExcel(); }
        });
        JPanel jpBottom = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4));
        jpBottom.add(btnExcel);
        jpBottom.add(btnCopy);
        jpBottom.add(btnSave);
        getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, java.awt.event.InputEvent.CTRL_DOWN_MASK), "CopyImage");
        getRootPane().getActionMap().put("CopyImage", new javax.swing.AbstractAction()
        {
            private static final long serialVersionUID = 1L;
            public void actionPerformed(java.awt.event.ActionEvent e) { copyImage(); }
        });

        m_jpCapture = new JPanel(new BorderLayout());
        m_jpCapture.add(jpTop, BorderLayout.NORTH);
        m_jpCapture.add(jpCenter, BorderLayout.CENTER);
        getContentPane().add(m_jpCapture, BorderLayout.CENTER);
        getContentPane().add(jpBottom, BorderLayout.SOUTH);
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
        if(b) { updatePeriod(); m_tmPeriod.start(); }
        else  m_tmPeriod.stop();
        super.setVisible(b);
    }

    // 측정 시간 줄: 남은 시간 / 경과 / 완료 (1.27)
    void updatePeriod()
    {
        m_lbPeriod.setText(m_stats.periodText());
        m_lbPeriod.setForeground(m_stats.finishTime() > 0 ? new Color(0x2E7D32) : Color.DARK_GRAY);
    }

    JPanel m_jpCapture;     // 이미지로 만들 영역 (버튼 줄 제외)

    // 통계 영역을 이미지로 (아래에 만든 시각을 적는다)
    java.awt.image.BufferedImage captureImage()
    {
        int w = m_jpCapture.getWidth(), h = m_jpCapture.getHeight();
        int nFooter = 18;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.max(1, w), Math.max(1, h + nFooter), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(getBackground());
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        m_jpCapture.paint(g);
        g.setColor(Color.GRAY);
        g.setFont(m_lbMem.getFont());
        g.drawString("KLogFilter 장치 통계 · " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()), 8, h + 13);
        g.dispose();
        return img;
    }

    void copyImage()
    {
        final java.awt.image.BufferedImage img = captureImage();
        java.awt.datatransfer.Transferable t = new java.awt.datatransfer.Transferable()
        {
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{ java.awt.datatransfer.DataFlavor.imageFlavor }; }
            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) { return java.awt.datatransfer.DataFlavor.imageFlavor.equals(f); }
            public Object getTransferData(java.awt.datatransfer.DataFlavor f) throws java.awt.datatransfer.UnsupportedFlavorException
            {
                if(!isDataFlavorSupported(f)) throw new java.awt.datatransfer.UnsupportedFlavorException(f);
                return img;
            }
        };
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(t, null);
        setTitle("장치 통계 — 이미지를 클립보드에 복사했습니다");
    }

    // 저장할 파일 이름 (테스트에서 대화상자 없이 쓰려면 지정)
    java.io.File m_fileSaveOverride;

    void saveImage()
    {
        java.io.File file = m_fileSaveOverride;
        if(file == null)
        {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setSelectedFile(new java.io.File("DeviceStats_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".png"));
            if(chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
            file = chooser.getSelectedFile();
            if(!file.getName().toLowerCase().endsWith(".png")) file = new java.io.File(file.getPath() + ".png");
        }
        try
        {
            javax.imageio.ImageIO.write(captureImage(), "png", file);
            setTitle("장치 통계 — 저장했습니다 : " + file.getName());
        }
        catch(java.io.IOException e)
        {
            javax.swing.JOptionPane.showMessageDialog(this, "이미지를 저장할 수 없습니다 : " + e.getMessage());
        }
    }

    List<String> m_arReboot = new ArrayList<String>();     // 정보 시트에 넣을 재부팅 기록

    // 측정 전체 기록을 엑셀 파일로 저장 (1.28). 주 창이 있으면 주 창의 저장(상태 표시줄 안내)으로.
    void saveExcel()
    {
        if(getOwner() instanceof LogFilterMain) { ((LogFilterMain)getOwner()).saveStatsExcel(this); return; }
        java.io.File file = m_fileSaveOverride;
        if(file == null)
        {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setSelectedFile(new java.io.File(StatsExcel.defaultFileName(System.currentTimeMillis())));
            if(chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
            file = StatsExcel.withExt(chooser.getSelectedFile());
        }
        try
        {
            int n = StatsExcel.write(file, m_stats, m_arReboot);
            setTitle("장치 통계 — 엑셀로 저장했습니다 (" + n + "회 측정) : " + file.getName());
        }
        catch(java.io.IOException e)
        {
            javax.swing.JOptionPane.showMessageDialog(this, "엑셀 파일을 저장할 수 없습니다 : " + e.getMessage());
        }
    }

    // 재부팅 기록 (최근 3개, 전체는 툴팁)
    void setReboots(List<String> arReboot)
    {
        List<String> ar;
        synchronized(arReboot) { ar = new ArrayList<String>(arReboot); }
        m_arReboot = ar;
        if(ar.isEmpty()) { m_lbReboot.setText("재부팅: 감지된 것 없음"); m_lbReboot.setForeground(Color.GRAY); m_lbReboot.setToolTipText(null); return; }
        m_lbReboot.setForeground(new Color(0xC62828));
        StringBuilder sb = new StringBuilder("재부팅 " + ar.size() + "회 : ");
        for(int i = Math.max(0, ar.size() - 3); i < ar.size(); i++) sb.append(i > Math.max(0, ar.size() - 3) ? " / " : "").append(ar.get(i));
        m_lbReboot.setText(sb.toString());
        m_lbReboot.setToolTipText("<html>" + String.join("<br>", ar) + "</html>");
    }

    // 그래프에 코어별 선을 그릴지 (1.29)
    void setShowCores(boolean b, boolean bNotify)
    {
        m_chkCores.setSelected(b);
        applyShow(bNotify);
    }

    // 표시 항목을 한 번에 정한다 (주 창이 설정에서 읽어 넘김, 1.30)
    void setShow(boolean bCpu, boolean bMem, boolean bTemp, boolean bCores)
    {
        m_chkCpu.setSelected(bCpu);
        m_chkMem.setSelected(bMem);
        m_chkTemp.setSelected(bTemp);
        m_chkCores.setSelected(bCores);
        applyShow(false);
    }

    // 체크박스 상태를 그래프·안내 줄·코어별 막대에 적용한다. bNotify면 주 창에 알려 설정에 저장한다. (1.30)
    void applyShow(boolean bNotify)
    {
        boolean bCpu = m_chkCpu.isSelected(), bMem = m_chkMem.isSelected(), bTemp = m_chkTemp.isSelected(), bCores = m_chkCores.isSelected();
        m_graph.m_bCpu = bCpu;
        m_graph.m_bMem = bMem;
        m_graph.m_bTemp = bTemp;
        m_graph.setShowCores(bCores);
        m_lbMem.setVisible(bMem);
        m_lbTemp.setVisible(bTemp);
        m_cores.setVisible(bCpu);
        m_jpGraph.setPreferredSize(new Dimension(520, bCores ? 230 : 210));    // 범례가 두 줄이 되는 만큼
        syncInterval();
        if(bNotify && getOwner() instanceof LogFilterMain)
        {
            LogFilterMain main = (LogFilterMain)getOwner();
            main.m_bStatsGraphCpu = bCpu;
            main.m_bStatsGraphMem = bMem;
            main.m_bStatsGraphTemp = bTemp;
            main.m_bStatsGraphCores = bCores;
        }
        if(isVisible()) pack();
    }

    // 측정 주기에 맞춰 콤보 선택과 그래프 제목(최근 60회 = 주기 × 60)을 바꾼다
    void syncInterval()
    {
        long nSec = m_stats.interval() / 1000;
        m_bSyncing = true;
        for(int i = 0; i < DeviceStats.INTERVALS_SEC.length; i++)
            if(DeviceStats.INTERVALS_SEC[i] == nSec) m_cbInterval.setSelectedIndex(i);
        m_bSyncing = false;
        // 범례 글자는 각 선과 같은 색. 그래프는 측정 전체 기록 (1.31)
        String strSpan = "측정 전체";
        List<String> arLegend = new ArrayList<String>();
        if(m_graph.m_bCpu)  arLegend.add(legend(COLOR_CPU, "파랑: CPU %"));
        if(m_graph.m_bMem)  arLegend.add(legend(COLOR_MEM, "초록: 메모리 %"));
        if(m_graph.m_bTemp) arLegend.add(legend(COLOR_TEMP, "주황: CPU 온도 °C"));
        if(arLegend.isEmpty() && !m_graph.m_bCores) arLegend.add("표시할 항목 없음");
        StringBuilder sb = new StringBuilder("<html>" + strSpan + " (" + nSec + "초마다"
            + (arLegend.isEmpty() ? "" : ", " + String.join(", ", arLegend)) + ")");
        if(m_graph.m_bCores)
        {
            int nCores = m_graph.coreCount();
            sb.append("<br>코어별: ");
            for(int i = 0; i < nCores; i++)
                sb.append(i > 0 ? ", " : "").append(legend(coreColor(i), "cpu" + i));
            if(nCores == 0) sb.append("(측정 대기 중)");
        }
        m_jpGraph.setBorder(BorderFactory.createTitledBorder(sb.append("</html>").toString()));
    }

    // 그래프의 보이는 구간에 맞춰 스크롤 바를 놓는다 (1.31)
    void syncScroll()
    {
        m_bSyncingScroll = true;
        int nVisible = m_graph.visibleCount(), nAll = m_graph.m_arAll.size();
        m_sbTime.setValues(m_graph.m_nOffset, nVisible, 0, Math.max(nAll, nVisible));
        m_sbTime.setUnitIncrement(Math.max(1, nVisible / 10));
        m_sbTime.setBlockIncrement(Math.max(1, nVisible * 9 / 10));
        m_sbTime.setEnabled(nAll > nVisible);
        m_bSyncingScroll = false;
    }

    // 코어별 선 색 (코어가 많으면 되풀이)
    static final Color[] COLOR_CORES = { new Color(0x8E24AA), new Color(0x00897B), new Color(0xD81B60), new Color(0x6D4C41),
                                         new Color(0x3949AB), new Color(0x827717), new Color(0x0097A7), new Color(0x546E7A) };
    static Color coreColor(int i) { return COLOR_CORES[i % COLOR_CORES.length]; }

    static String legend(Color c, String strText)
    {
        return String.format("<b><font color='#%06X'>%s</font></b>", c.getRGB() & 0xFFFFFF, strText);
    }

    // EDT에서 호출
    void update(DeviceStats.Sample sample)
    {
        m_lbSummary.setText(sample.m_strError != null ? sample.m_strError
                            : (sample.m_strDevice.length() > 0 ? sample.m_strDevice + " — " : "") + sample.summary());
        if(sample.m_strError == null && sample.m_nMemTotalKb > 0)
            m_lbMem.setText(String.format("메모리: 사용 %,d MB / 전체 %,d MB, 사용 가능 %,d MB",
                (sample.m_nMemTotalKb - sample.m_nMemAvailKb) / 1024, sample.m_nMemTotalKb / 1024, sample.m_nMemAvailKb / 1024));
        int nCoresBefore = m_graph.coreCount();
        m_graph.setSamples(m_stats.record());       // 전체 기록 (1.31)
        if(m_graph.m_bCores && m_graph.coreCount() != nCoresBefore)
            syncInterval();         // 코어 수를 알게 되면 범례에 cpu0, cpu1 … 표시
        updatePeriod();
        if(sample.m_strError == null)
        {
            if(sample.m_arThermal.isEmpty())
                m_lbTemp.setText("온도: 센서를 읽을 수 없습니다");
            else
            {
                StringBuilder sb = new StringBuilder(String.format("온도: CPU %.1f°C   (", sample.m_dTemp));
                for(int i = 0; i < sample.m_arThermal.size() && i < 6; i++)
                    sb.append(i > 0 ? ", " : "").append(sample.m_arThermal.get(i)[0]).append(' ').append(sample.m_arThermal.get(i)[1]).append("°C");
                if(sample.m_arThermal.size() > 6) sb.append(" …");
                m_lbTemp.setText(sb.append(')').toString());
                StringBuilder sbTip = new StringBuilder("<html>");
                for(String[] z : sample.m_arThermal) sbTip.append(z[0]).append(" : ").append(z[1]).append("°C<br>");
                m_lbTemp.setToolTipText(sbTip.append("</html>").toString());
            }
        }
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
    /**
     * CPU·메모리·온도 그래프. 측정을 켠 뒤의 전체 기록을 시간 축으로 그리고, 가로 스크롤로 구간을 옮긴다 (1.31).
     * 한 측정이 m_nPx 픽셀. 맨 끝을 보고 있으면 새 측정이 올 때 따라간다(m_bFollow).
     */
    static class GraphPanel extends JPanel
    {
        private static final long serialVersionUID = 1L;
        static final int X0 = 40, RIGHT = 14, BOTTOM = 30;      // 왼쪽 % 눈금, 오른쪽 여백, 아래 시간 눈금
        static final int[] PX_CHOICES = { 1, 2, 4, 8 };
        List<DeviceStats.Sample> m_arAll = new ArrayList<DeviceStats.Sample>();   // 전체 기록 (오류 측정 포함)
        boolean m_bCores;       // 코어별 선도 그린다 (1.29)
        boolean m_bCpu = true, m_bMem = true, m_bTemp = true;   // 어떤 선을 그릴지 (1.30)
        int     m_nPx = 4;      // 측정 하나의 가로 픽셀
        int     m_nOffset;      // 보이는 구간의 첫 측정 번호
        boolean m_bFollow = true;   // 맨 끝을 따라간다 (스크롤을 뒤로 돌리면 false)
        int     m_nCores;       // 기록에 있는 가장 많은 코어 수
        Runnable m_onScroll;    // 보이는 구간이 바뀌면 스크롤 바를 맞추라고 알림

        GraphPanel()
        {
            addComponentListener(new java.awt.event.ComponentAdapter()
            {
                public void componentResized(java.awt.event.ComponentEvent e) { clampOffset(); }
            });
            addMouseWheelListener(e -> setOffset(m_nOffset + e.getWheelRotation() * Math.max(1, visibleCount() / 4)));
        }

        void setSamples(List<DeviceStats.Sample> ar)
        {
            m_arAll = ar;
            int n = 0;
            for(DeviceStats.Sample s : ar) n = Math.max(n, s.m_arCore.length);
            m_nCores = n;
            clampOffset();
        }
        void setShowCores(boolean b) { m_bCores = b; repaint(); }
        int  coreCount() { return m_nCores; }

        void setPx(int nPx)
        {
            // 보이는 구간의 가운데를 유지한 채 간격을 바꾼다
            int nCenter = m_nOffset + visibleCount() / 2;
            m_nPx = Math.max(1, nPx);
            m_nOffset = nCenter - visibleCount() / 2;
            clampOffset();
        }

        int plotWidth()    { return Math.max(1, getWidth() - X0 - RIGHT); }
        int visibleCount() { return Math.max(1, plotWidth() / m_nPx + 1); }   // 한 화면에 들어가는 측정 수
        int maxOffset()    { return Math.max(0, m_arAll.size() - visibleCount()); }

        // 스크롤 바가 움직였다
        void setOffset(int nOffset)
        {
            m_nOffset = nOffset;
            m_bFollow = nOffset >= maxOffset();
            clampOffset();
        }

        // 따라가는 중이면 맨 끝으로, 아니면 범위 안으로. 스크롤 바도 맞춘다.
        void clampOffset()
        {
            if(m_bFollow) m_nOffset = maxOffset();
            m_nOffset = Math.max(0, Math.min(maxOffset(), m_nOffset));
            if(m_onScroll != null) m_onScroll.run();
            repaint();
        }

        static String clock(long nTime) { return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(nTime)); }

        protected void paintComponent(Graphics g0)
        {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D)g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x0 = X0, y0 = getInsets().top + 18, w = plotWidth(), h = Math.max(10, getHeight() - y0 - BOTTOM);
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
            List<DeviceStats.Sample> ar = m_arAll;
            int nFirst = m_nOffset, nLast = Math.min(ar.size(), m_nOffset + visibleCount());
            // 위: 전체 범위와 보이는 구간
            g.setColor(Color.GRAY);
            g.setFont(g.getFont().deriveFont(11f));
            if(!ar.isEmpty())
                g.drawString("전체 " + ar.size() + "회  " + clock(ar.get(0).m_nTime) + " ~ " + clock(ar.get(ar.size() - 1).m_nTime)
                    + "   ·   보이는 구간 " + clock(ar.get(nFirst).m_nTime) + " ~ " + clock(ar.get(nLast - 1).m_nTime) + " (" + (nFirst + 1) + "~" + nLast + ")", x0, y0 - 5);
            else
                g.drawString("측정 기록 없음", x0, y0 - 5);
            // 아래: 시간 눈금 (약 110픽셀마다)
            int nStep = Math.max(1, 110 / m_nPx);
            for(int i = nFirst - nFirst % nStep; i < nLast; i += nStep)
            {
                if(i < nFirst) continue;
                int x = x0 + (i - nFirst) * m_nPx;
                g.setColor(new Color(0xE8E8E8));
                g.drawLine(x, y0, x, y0 + h);
                g.setColor(Color.GRAY);
                String s = clock(ar.get(i).m_nTime);
                int sw = g.getFontMetrics().stringWidth(s);
                g.drawString(s, Math.min(x - sw / 2, x0 + w - sw), y0 + h + 14);
                g.drawLine(x, y0 + h, x, y0 + h + 3);
            }
            // 재부팅·오류 표시 (1.24)
            for(int i = nFirst; i < nLast; i++)
            {
                DeviceStats.Sample s = ar.get(i);
                int x = x0 + (i - nFirst) * m_nPx;
                if(s.m_strError != null)
                {
                    g.setColor(new Color(0xFFF0F0));
                    g.fillRect(x, y0, Math.max(1, m_nPx), h);
                }
                if(s.m_bReboot)
                {
                    g.setColor(new Color(0xC62828));
                    g.drawLine(x, y0, x, y0 + h);
                    g.drawString("재부팅", Math.min(x + 2, x0 + w - 36), y0 + 12);
                }
            }
            // 코어별 선은 가늘게 먼저 그려 전체 CPU·메모리·온도 선이 위에 오게 한다
            if(m_bCores)
            {
                java.awt.Stroke stroke = g.getStroke();
                g.setStroke(new java.awt.BasicStroke(1f));
                for(int i = 0; i < m_nCores; i++)
                    drawLine(g, x0, y0, w, h, 3 + i, nFirst, nLast);
                g.setStroke(stroke);
            }
            if(m_bCpu)  drawLine(g, x0, y0, w, h, 0, nFirst, nLast);
            if(m_bMem)  drawLine(g, x0, y0, w, h, 1, nFirst, nLast);
            if(m_bTemp) drawLine(g, x0, y0, w, h, 2, nFirst, nLast);
        }

        // nKind: 0 CPU %, 1 메모리 %, 2 온도 °C (같은 0~100 눈금), 3 이상은 코어 (nKind - 3) 사용률 %. [nFirst, nLast) 구간만 그린다.
        void drawLine(Graphics2D g, int x0, int y0, int w, int h, int nKind, int nFirst, int nLast)
        {
            g.setColor(nKind == 0 ? COLOR_CPU : nKind == 1 ? COLOR_MEM : nKind == 2 ? COLOR_TEMP : coreColor(nKind - 3));
            int nPrevX = -1, nPrevY = -1;
            List<DeviceStats.Sample> ar = m_arAll;
            for(int i = nFirst; i < nLast; i++)
            {
                DeviceStats.Sample s = ar.get(i);
                double v;
                if(s.m_strError != null) v = -1;                                   // 오류 측정은 끊김
                else if(nKind >= 3)
                    v = nKind - 3 < s.m_arCore.length ? s.m_arCore[nKind - 3] : -1;   // 꺼진 코어·없는 코어는 끊김
                else
                    v = nKind == 0 ? s.m_dCpu : nKind == 1 ? s.memUsedPercent() : Math.min(100, s.m_dTemp);
                if(v < 0) { nPrevX = -1; continue; }
                int x = x0 + (i - nFirst) * m_nPx;
                int y = y0 + h - (int)(h * v / 100);
                if(nPrevX >= 0) g.drawLine(nPrevX, nPrevY, x, y);
                else if(m_nPx >= 2) g.fillOval(x - 2, y - 2, 4, 4);
                else g.drawLine(x, y, x, y);
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
