import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * 장치 통계를 몇 시간 동안 측정할지 정하는 창 (1.27).
 * 계속 측정하거나, 정한 시간(시간·분)이 지나면 측정을 멈춘다. 확인하면 지금부터 다시 센다.
 */
public class StatsDurationDialog extends JDialog
{
    private static final long serialVersionUID = 1L;

    static final int   MAX_HOURS = 720;                       // 30일
    static final int[] PRESET_HOURS = { 1, 3, 6, 12, 24 };

    final JRadioButton m_rbForever = new JRadioButton("계속 측정 (시간 제한 없음)");
    final JRadioButton m_rbLimited = new JRadioButton("정한 시간 동안 측정하고 멈춤 :");
    final JSpinner     m_spHours   = new JSpinner(new SpinnerNumberModel(3, 0, MAX_HOURS, 1));
    final JSpinner     m_spMinutes = new JSpinner(new SpinnerNumberModel(0, 0, 59, 5));
    final JLabel       m_lbNow     = new JLabel(" ");
    int                m_nResultMin = -1;                     // 확인하면 분 (0 = 계속), 취소하면 -1

    /** @param nCurMin 지금 설정 (0 = 계속), strNow 지금 측정 상태 */
    StatsDurationDialog(JFrame owner, int nCurMin, String strNow)
    {
        super(owner, "장치 통계 측정 시간", true);
        ButtonGroup group = new ButtonGroup();
        group.add(m_rbForever);
        group.add(m_rbLimited);
        if(nCurMin > 0)
        {
            m_rbLimited.setSelected(true);
            m_spHours.setValue(Math.min(MAX_HOURS, nCurMin / 60));
            m_spMinutes.setValue(nCurMin % 60);
        }
        else
            m_rbForever.setSelected(true);

        JPanel jpTime = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        jpTime.add(m_rbLimited);
        jpTime.add(m_spHours);
        jpTime.add(new JLabel("시간"));
        jpTime.add(m_spMinutes);
        jpTime.add(new JLabel("분"));

        // 자주 쓰는 시간
        JPanel jpPreset = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        jpPreset.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 0));
        for(final int nH : PRESET_HOURS)
        {
            JButton btn = new JButton(nH + "시간");
            btn.setMargin(new java.awt.Insets(1, 6, 1, 6));
            btn.addActionListener(e -> { m_rbLimited.setSelected(true); m_spHours.setValue(nH); m_spMinutes.setValue(0); });
            jpPreset.add(btn);
        }
        // 시간을 고치면 "정한 시간"을 고른 것으로
        m_spHours.addChangeListener(e -> m_rbLimited.setSelected(true));
        m_spMinutes.addChangeListener(e -> m_rbLimited.setSelected(true));

        m_lbNow.setText("<html>지금: " + strNow + "<br>확인을 누르면 지금부터 측정합니다 (장치 통계가 꺼져 있으면 켭니다).<br>"
                      + "시간이 끝나면 측정을 멈추고 상태 표시줄에 알립니다. 마지막 통계는 자세히 창에서 볼 수 있습니다.</html>");
        m_lbNow.setForeground(java.awt.Color.DARK_GRAY);

        JPanel jpCenter = new JPanel(new GridLayout(0, 1, 0, 4));
        jpCenter.add(m_rbForever);
        jpCenter.add(jpTime);
        jpCenter.add(jpPreset);
        JPanel jpMain = new JPanel(new BorderLayout(0, 10));
        jpMain.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        jpMain.add(jpCenter, BorderLayout.CENTER);
        jpMain.add(m_lbNow, BorderLayout.SOUTH);

        JButton btnOk = new JButton("확인");
        JButton btnCancel = new JButton("취소");
        btnOk.addActionListener(e -> onOk());
        btnCancel.addActionListener(e -> dispose());
        JPanel jpButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        jpButtons.add(btnOk);
        jpButtons.add(btnCancel);

        getContentPane().add(jpMain, BorderLayout.CENTER);
        getContentPane().add(jpButtons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(btnOk);
        getRootPane().registerKeyboardAction(e -> dispose(), javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    // 고른 값 (분, 0 = 계속). 정한 시간인데 0분이면 -1
    int selectedMinutes()
    {
        if(m_rbForever.isSelected()) return 0;
        int nMin = ((Number)m_spHours.getValue()).intValue() * 60 + ((Number)m_spMinutes.getValue()).intValue();
        return nMin > 0 ? nMin : -1;
    }

    void onOk()
    {
        int nMin = selectedMinutes();
        if(nMin < 0)
        {
            JOptionPane.showMessageDialog(this, "측정 시간을 1분 이상으로 정하세요.", getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        m_nResultMin = nMin;
        dispose();
    }

    /** 창을 띄우고 고른 값(분, 0 = 계속)을 돌려준다. 취소하면 -1 */
    static int ask(JFrame owner, int nCurMin, String strNow)
    {
        StatsDurationDialog dlg = new StatsDurationDialog(owner, nCurMin, strNow);
        dlg.setVisible(true);
        return dlg.m_nResultMin;
    }
}
