import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * root 실행·재부팅 버튼 (1.48, 1.49): 프로퍼티 창·프로세스 창 툴바에 들어간다.
 * - Reboot: 확인 후 adb reboot → 다시 연결될 때까지 → sys.boot_completed=1이 될 때까지(최대 3분) 기다린 뒤 onDone. 진행 중 경과 시간 표시.
 * - 지금 권한을 [권한: root] / [권한: shell]로 보이고, shell이면 "Root" 버튼으로 adb root. 이미 root면 버튼을 끈다 (unroot는 없음).
 * - adb root는 장치의 adbd를 다시 시작하므로 연결이 잠시 끊긴다: 다시 연결될 때까지 기다린 뒤 onDone을 부른다.
 * - 실시간 로그 수집 중이면 끊길 수 있다고 먼저 묻는다.
 * - 일반 판매용(user) 빌드는 root가 안 된다: adb의 문구("adbd cannot run as root in production builds")를 그대로 알린다.
 */
public class RootControl extends JPanel
{
    private static final long serialVersionUID = 1L;

    final LogFilterMain m_main;
    final java.awt.Component m_owner;
    final JButton m_btn = new JButton("Root");
    final JLabel  m_lb  = new JLabel("권한: ?");
    final Runnable m_onDone;            // root 바꾼 뒤 (다시 읽기)
    final java.util.function.Consumer<String> m_onMessage;    // 창 아래 안내
    volatile boolean m_bBusy;
    Boolean m_bRoot;                    // null = 모름
    RootControl(LogFilterMain main, java.awt.Component owner, Runnable onDone, java.util.function.Consumer<String> onMessage)
    {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        m_main = main;
        m_owner = owner;
        m_onDone = onDone;
        m_onMessage = onMessage;
        m_btn.setMargin(new java.awt.Insets(1, 6, 1, 6));
        m_btn.setFont(m_btn.getFont().deriveFont(Font.BOLD));
        m_btn.setToolTipText("adb root : 장치의 adbd를 root 권한으로 다시 시작 (userdebug·eng 빌드만, 연결이 잠시 끊김)");
        m_btn.addActionListener(e -> toggle());
        m_lb.setToolTipText("adb shell id -u 결과 (0이면 root)");
        // 재부팅 (1.49)
        m_btnReboot.setMargin(new java.awt.Insets(1, 6, 1, 6));
        m_btnReboot.setFont(m_btnReboot.getFont().deriveFont(Font.BOLD));
        m_btnReboot.setForeground(new Color(0xB71C1C));
        m_btnReboot.setToolTipText("adb reboot : 장치 재부팅 (다시 연결되고 부팅이 끝나면 목록을 새로 읽음)");
        m_btnReboot.addActionListener(e -> confirmReboot());
        m_tmElapsed = new javax.swing.Timer(1000, e -> m_lb.setText(m_strWork + " (" + (System.currentTimeMillis() - m_nWorkStart) / 1000 + "초)"));
        add(m_btn);
        add(m_btnReboot);
        add(m_lb);
    }

    // ---- 재부팅 (1.49) ----
    final JButton m_btnReboot = new JButton("Reboot");
    final javax.swing.Timer m_tmElapsed;          // 재부팅 중 경과 시간 표시
    String m_strWork = "";
    long   m_nWorkStart;
    static final long REBOOT_WAIT_MS = 180000;     // 다시 연결·부팅 완료를 기다리는 최대 시간

    void confirmReboot()
    {
        if(m_bBusy) return;
        String strDev = device().length() > 0 ? device() : "adb 기본 장치";
        String strMsg = "장치(" + strDev + ")를 재부팅할까요?";
        if(m_main.m_source.isRunning())
            strMsg += "\n\n지금 실시간 로그 수집 중입니다. " + (m_main.m_source.m_bAutoReconnect
                ? "'연결 대기'가 켜져 있어 재부팅 뒤 다시 연결되면 이어서 수집하고 재부팅 표시 줄을 넣습니다."
                : "'연결 대기'가 꺼져 있어 재부팅하면 수집이 멈춥니다 (Stop).");
        if(m_bRoot != null && m_bRoot) strMsg += "\n\n재부팅하면 root 권한은 풀립니다.";
        if(JOptionPane.showConfirmDialog(m_owner, strMsg, "Reboot", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        reboot();
    }

    // 확인 없이 재부팅 (테스트에서도 씀): adb reboot → 다시 연결될 때까지 → sys.boot_completed=1 될 때까지 → onDone
    void reboot()
    {
        final String strDevice = device();
        setBusy(true, "재부팅 중…");
        new Thread(() ->
        {
            long nStart = System.currentTimeMillis();
            String[] r = DevicePropDialog.runAdb(strDevice, 15000, "reboot");
            String strMsg;
            if(r[1] != null)
                strMsg = "재부팅 실패 : " + r[1].trim();
            else
            {
                // 장치가 내려갈 시간을 준 뒤 다시 보일 때까지 기다린다
                try { Thread.sleep(3000); } catch(InterruptedException e) { }
                setWork("재부팅 중… 장치를 기다리는 중");
                String[] w = DevicePropDialog.runAdb(strDevice, REBOOT_WAIT_MS, "wait-for-device");
                boolean bBooted = false;
                if(w[1] == null)
                {
                    setWork("재부팅 중… 부팅 완료를 기다리는 중");
                    while(System.currentTimeMillis() - nStart < REBOOT_WAIT_MS)
                    {
                        String[] b = DevicePropDialog.runAdb(strDevice, 8000, "shell", "getprop sys.boot_completed");
                        if(b[1] == null && b[0].trim().equals("1")) { bBooted = true; break; }
                        try { Thread.sleep(2000); } catch(InterruptedException e) { break; }
                    }
                }
                long nSec = (System.currentTimeMillis() - nStart) / 1000;
                strMsg = bBooted ? "재부팅을 마쳤습니다 (" + nSec + "초)"
                       : w[1] != null ? "재부팅했지만 장치가 " + (REBOOT_WAIT_MS / 1000) + "초 안에 다시 연결되지 않았습니다 — 네트워크 장치면 adb connect로 다시 연결하세요"
                       : "장치는 다시 연결됐지만 부팅이 아직 끝나지 않았습니다 (" + nSec + "초) — 잠시 뒤 Refresh 하세요";
            }
            final Boolean bNow = isRoot(strDevice);
            final String strFinal = strMsg;
            SwingUtilities.invokeLater(() ->
            {
                setBusy(false, null);
                setState(bNow);
                if(m_onMessage != null) m_onMessage.accept(strFinal);
                m_main.setStatus("Reboot : " + strFinal);
                if(m_onDone != null) m_onDone.run();
            });
        }, "AdbReboot").start();
    }

    void setWork(final String s) { SwingUtilities.invokeLater(() -> m_strWork = s); }

    void setBusy(boolean b, String strWork)
    {
        m_bBusy = b;
        m_btnReboot.setEnabled(!b);
        if(b)
        {
            m_btn.setEnabled(false);
            m_strWork = strWork;
            m_nWorkStart = System.currentTimeMillis();
            m_lb.setText(strWork);
            m_lb.setForeground(new Color(0xB71C1C));
            m_tmElapsed.start();
        }
        else
            m_tmElapsed.stop();
    }

    String device()
    {
        String s = m_main.m_strSelectedDevice;
        return s == null ? "" : s.trim();
    }

    // 지금 권한을 읽어 버튼과 표시를 맞춘다 (창을 열 때)
    void checkState()
    {
        final String strDevice = device();
        new Thread(() ->
        {
            final Boolean b = isRoot(strDevice);
            SwingUtilities.invokeLater(() -> setState(b));
        }, "RootCheck").start();
    }

    // adb shell id -u → 0이면 root. 읽지 못하면 null
    static Boolean isRoot(String strDevice)
    {
        String[] r = DevicePropDialog.runAdb(strDevice, 8000, "shell", "id -u");
        if(r[1] != null) return null;
        String s = r[0].trim();
        return s.matches("\\d+") ? Boolean.valueOf(s.equals("0")) : null;
    }

    void setState(Boolean b)
    {
        m_bRoot = b;
        if(b == null)      { m_lb.setText("권한: ?");     m_lb.setForeground(Color.GRAY); }
        else if(b)         { m_lb.setText("권한: root");  m_lb.setForeground(new Color(0xC62828)); }
        else               { m_lb.setText("권한: shell"); m_lb.setForeground(new Color(0x2E7D32)); }
        // 이미 root면 누를 필요가 없다 (unroot는 없음)
        m_btn.setEnabled(!m_bBusy && (b == null || !b));
        m_btn.setToolTipText(b != null && b ? "이미 root 권한으로 실행 중입니다"
                                            : "adb root : 장치의 adbd를 root 권한으로 다시 시작 (userdebug·eng 빌드만, 연결이 잠시 끊김)");    }

    void toggle()
    {
        if(m_bBusy || (m_bRoot != null && m_bRoot)) return;
        // 실시간 수집 중이면 adbd가 다시 시작되면서 수집이 끊긴다
        if(m_main.m_source.isRunning())
        {
            String strWait = m_main.m_source.m_bAutoReconnect ? "'연결 대기'가 켜져 있어 다시 연결되면 이어서 수집합니다." : "'연결 대기'가 꺼져 있어 수집이 멈춥니다 (Stop).";
            if(JOptionPane.showConfirmDialog(m_owner, "adb root는 장치의 adbd를 다시 시작합니다.\n지금 실시간 로그 수집이 잠시 끊깁니다. " + strWait + "\n계속할까요?",
                    "Root", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        }
        run();
    }

    // 확인 없이 adb root 실행 (테스트에서도 씀)
    void run()
    {
        final String strDevice = device();
        setBusy(true, "root로 바꾸는 중…");
        new Thread(() ->
        {
            String[] r = DevicePropDialog.runAdb(strDevice, 15000, "root");
            String strOut = (r[1] != null ? r[1] : r[0]).trim();
            // adbd가 다시 시작되면 잠시 장치가 사라진다: 다시 보일 때까지 기다린다
            boolean bRestart = r[1] == null && (strOut.contains("restarting") || strOut.length() == 0);
            if(bRestart)
            {
                try { Thread.sleep(1500); } catch(InterruptedException e) { }
                DevicePropDialog.runAdb(strDevice, 20000, "wait-for-device");
            }
            final Boolean bNow = isRoot(strDevice);
            final String strMsg;
            if(r[1] != null) strMsg = "adb root 실패 : " + strOut;
            else if(bNow != null && bNow) strMsg = "root 권한으로 실행 중입니다" + (strOut.contains("already") ? " (이미 root)" : "");
            else if(strOut.contains("production")) strMsg = "이 장치는 root로 실행할 수 없습니다 (일반 판매용 빌드) : " + strOut;
            else if(bNow == null) strMsg = "adb root : " + strOut + " — 장치가 아직 다시 연결되지 않았습니다. 네트워크 장치면 adb connect로 다시 연결하세요";
            else strMsg = "adb root : " + (strOut.length() > 0 ? strOut : "권한이 바뀌지 않았습니다");
            SwingUtilities.invokeLater(() ->
            {
                setBusy(false, null);
                setState(bNow);
                if(m_onMessage != null) m_onMessage.accept(strMsg);
                m_main.setStatus("Root : " + strMsg);
                if(m_onDone != null) m_onDone.run();
            });
        }, "AdbRoot").start();
    }
}
