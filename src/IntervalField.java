import java.awt.Color;
import java.awt.FlowLayout;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;

/**
 * 자동 갱신 주기 입력 (1.46): [v 자동 갱신] [10] 초
 * 사용자가 초를 입력하고 Enter를 누르거나 다른 곳으로 옮기면 그 주기로 onTick을 부른다. 1 ~ 3600초, 잘못된 값이면 빨간색으로 알리고 이전 값 유지.
 * 창이 보일 때만 돌도록 start()/stop()은 창이 부른다.
 */
public class IntervalField extends JPanel
{
    private static final long serialVersionUID = 1L;
    static final int MIN_SEC = 1, MAX_SEC = 3600, DEFAULT_SEC = 10;

    final JCheckBox  m_chk;
    final JTextField m_tf = new JTextField(3);
    final Timer      m_timer;
    int              m_nSec;
    boolean          m_bActive;          // 창이 보이는 중 (start/stop)
    Runnable         m_onChange;         // 주기·켜기가 바뀌었을 때 (설정 저장용)

    IntervalField(String strLabel, int nSec, boolean bOn, Runnable onTick)
    {
        super(new FlowLayout(FlowLayout.LEFT, 3, 0));
        m_chk = new JCheckBox(strLabel, bOn);
        m_nSec = clamp(nSec);
        m_tf.setText("" + m_nSec);
        m_tf.setHorizontalAlignment(JTextField.RIGHT);
        m_tf.setToolTipText("갱신 주기 (초, " + MIN_SEC + " ~ " + MAX_SEC + ") — 입력하고 Enter");
        m_chk.setToolTipText("정한 주기마다 자동으로 다시 읽기");
        m_timer = new Timer(m_nSec * 1000, e -> onTick.run());
        m_timer.setRepeats(true);
        m_chk.addActionListener(e -> { update(); changed(); });
        m_tf.addActionListener(e -> apply());
        m_tf.addFocusListener(new java.awt.event.FocusAdapter() { public void focusLost(java.awt.event.FocusEvent e) { apply(); } });
        add(m_chk);
        add(m_tf);
        add(new JLabel("초"));
    }

    static int clamp(int n) { return Math.max(MIN_SEC, Math.min(MAX_SEC, n <= 0 ? DEFAULT_SEC : n)); }

    int seconds()     { return m_nSec; }
    boolean isOn()    { return m_chk.isSelected(); }
    boolean running() { return m_timer.isRunning(); }

    // 입력한 값을 주기로 쓴다
    void apply()
    {
        String s = m_tf.getText().trim();
        int n;
        try { n = Integer.parseInt(s); } catch(NumberFormatException e) { n = -1; }
        if(n < MIN_SEC || n > MAX_SEC)
        {
            m_tf.setBackground(new Color(0xFFCDD2));
            m_tf.setToolTipText(MIN_SEC + " ~ " + MAX_SEC + " 사이의 초를 넣으세요 (지금 주기 " + m_nSec + "초 유지)");
            return;
        }
        m_tf.setBackground(Color.WHITE);
        m_tf.setToolTipText("갱신 주기 (초, " + MIN_SEC + " ~ " + MAX_SEC + ") — 입력하고 Enter");
        if(n != m_nSec) { m_nSec = n; update(); changed(); }
    }

    void setSeconds(int n) { m_nSec = clamp(n); m_tf.setText("" + m_nSec); m_tf.setBackground(Color.WHITE); update(); }
    void setOn(boolean b)  { m_chk.setSelected(b); update(); }

    void start() { m_bActive = true;  update(); }
    void stop()  { m_bActive = false; update(); }

    void update()
    {
        m_tf.setEnabled(m_chk.isSelected());
        m_timer.setDelay(m_nSec * 1000);
        m_timer.setInitialDelay(m_nSec * 1000);
        if(m_bActive && m_chk.isSelected()) m_timer.restart();
        else m_timer.stop();
    }

    void changed() { if(m_onChange != null) m_onChange.run(); }
}
