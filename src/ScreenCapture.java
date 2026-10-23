import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * 장치 화면 캡처 (1.40): adb exec-out screencap -p 로 PNG를 받아 실행 폴더에 Screen_날짜_시각.png 로 저장하고 미리보기 창에 보여 준다.
 * 수집 중이면 로그에 "-------- Screen captured : 파일 --------" 표시 줄을 넣어 로그와 캡처 시각을 맞춰 볼 수 있다.
 */
public class ScreenCapture
{
    static final long TIMEOUT_MS = 20000;

    /** 장치 화면을 PNG 바이트로 받는다. 실패하면 IOException(메시지에 이유). */
    static byte[] capture(String strDevice) throws IOException
    {
        List<String> arCmd = new ArrayList<String>(Arrays.asList(DeviceStats.ADB.trim().split("\\s+")));
        if(strDevice != null && strDevice.length() > 0) { arCmd.add("-s"); arCmd.add(strDevice); }
        arCmd.add("exec-out"); arCmd.add("screencap"); arCmd.add("-p");
        Process process = null;
        try
        {
            ProcessBuilder pb = new ProcessBuilder(arCmd);
            pb.redirectErrorStream(false);
            process = pb.start();
            final Process p = process;
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread thErr = new Thread(() -> { try(InputStream in = p.getErrorStream()) { byte[] b = new byte[4096]; int n; while((n = in.read(b)) > 0) err.write(b, 0, n); } catch(IOException e) { } }, "ScreenCapErr");
            thErr.setDaemon(true);
            thErr.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            try(InputStream in = process.getInputStream())
            {
                byte[] b = new byte[64 << 10];
                int n;
                while((n = in.read(b)) > 0) out.write(b, 0, n);
            }
            if(!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { process.destroy(); throw new IOException("adb 응답 없음"); }
            thErr.join(1000);
            byte[] arPng = out.toByteArray();
            String strErr = new String(err.toByteArray(), "UTF-8").trim();
            if(arPng.length < 8 || (arPng[0] & 0xFF) != 0x89 || arPng[1] != 'P' || arPng[2] != 'N' || arPng[3] != 'G')
            {
                String strOut = new String(arPng, 0, Math.min(arPng.length, 200), "UTF-8").trim();
                String strWhy = DeviceStats.adbError(strErr.length() > 0 ? strErr : strOut);
                throw new IOException(strWhy != null ? strWhy : (strErr.length() > 0 ? strErr : strOut.length() > 0 ? strOut : "장치에서 화면을 받지 못했습니다"));
            }
            return arPng;
        }
        catch(InterruptedException e)
        {
            if(process != null) process.destroy();
            throw new IOException("중단됨");
        }
        catch(IOException e)
        {
            if(process != null) process.destroy();
            if(e.getMessage() != null && e.getMessage().contains("CreateProcess")) throw new IOException("adb를 실행할 수 없습니다 (PATH 확인)");
            throw e;
        }
    }

    static String defaultFileName(long nTime)
    {
        return "Screen_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date(nTime)) + ".png";
    }

    /** 미리보기 창: 캡처한 그림을 화면에 맞게 줄여 보여 주고 다시 캡처·복사·다른 이름으로 저장 */
    static class Dialog extends JDialog
    {
        private static final long serialVersionUID = 1L;
        final LogFilterMain m_main;
        BufferedImage       m_image;
        File                m_file;
        final JLabel        m_lbInfo = new JLabel(" ");
        final ImagePanel    m_panel = new ImagePanel();

        Dialog(LogFilterMain main)
        {
            super(main, "장치 화면 캡처", false);
            m_main = main;
            JButton btnAgain = new JButton("다시 캡처 (F9)");
            btnAgain.addActionListener(e -> main.captureScreen());
            JButton btnCopy = new JButton("클립보드 복사");
            btnCopy.addActionListener(e -> copyToClipboard());
            JButton btnSave = new JButton("다른 이름으로 저장…");
            btnSave.addActionListener(e -> saveAs());
            JButton btnClose = new JButton("닫기");
            btnClose.addActionListener(e -> setVisible(false));
            JPanel jpButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4));
            jpButtons.add(btnAgain); jpButtons.add(btnCopy); jpButtons.add(btnSave); jpButtons.add(btnClose);
            m_lbInfo.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            JScrollPane sp = new JScrollPane(m_panel);
            sp.setBorder(BorderFactory.createEmptyBorder());
            getContentPane().add(m_lbInfo, BorderLayout.NORTH);
            getContentPane().add(sp, BorderLayout.CENTER);
            getContentPane().add(jpButtons, BorderLayout.SOUTH);
            getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "Close");
            getRootPane().getActionMap().put("Close", new javax.swing.AbstractAction() { private static final long serialVersionUID = 1L; public void actionPerformed(java.awt.event.ActionEvent e) { setVisible(false); } });
            setDefaultCloseOperation(HIDE_ON_CLOSE);
        }

        void show(BufferedImage image, File file)
        {
            m_image = image;
            m_file = file;
            m_panel.setImage(image);
            // 창 폭은 그림에 맞춘다: 라벨은 짧게(파일 이름·크기), 전체 경로는 툴팁에
            m_lbInfo.setText(file.getName() + "  —  " + image.getWidth() + " × " + image.getHeight() + " px,  " + (file.length() / 1024) + " KB");
            m_lbInfo.setToolTipText(file.getAbsolutePath());
            m_lbInfo.setPreferredSize(new Dimension(10, m_lbInfo.getPreferredSize().height));
            // 화면의 70%까지만 크게, 그 안에 맞춰 줄여 보인다
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int nMaxW = (int)(screen.width * 0.7), nMaxH = (int)(screen.height * 0.75);
            double dScale = Math.min(1.0, Math.min((double)nMaxW / image.getWidth(), (double)nMaxH / image.getHeight()));
            m_panel.setPreferredSize(new Dimension((int)(image.getWidth() * dScale), (int)(image.getHeight() * dScale)));
            pack();
            if(!isVisible()) setLocationRelativeTo(m_main);
            setVisible(true);
            toFront();
        }

        void copyToClipboard()
        {
            final BufferedImage img = m_image;
            if(img == null) return;
            java.awt.datatransfer.Transferable t = new java.awt.datatransfer.Transferable()
            {
                public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{ java.awt.datatransfer.DataFlavor.imageFlavor }; }
                public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) { return java.awt.datatransfer.DataFlavor.imageFlavor.equals(f); }
                public Object getTransferData(java.awt.datatransfer.DataFlavor f) throws java.awt.datatransfer.UnsupportedFlavorException { if(!isDataFlavorSupported(f)) throw new java.awt.datatransfer.UnsupportedFlavorException(f); return img; }
            };
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(t, null);
            m_main.setStatus("화면 캡처를 클립보드에 복사했습니다");
        }

        void saveAs()
        {
            if(m_image == null) return;
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File(m_file != null ? m_file.getName() : defaultFileName(System.currentTimeMillis())));
            if(chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File file = chooser.getSelectedFile();
            if(!file.getName().toLowerCase().endsWith(".png")) file = new File(file.getPath() + ".png");
            try
            {
                javax.imageio.ImageIO.write(m_image, "png", file);
                m_main.setStatus("화면 캡처를 저장했습니다 : " + file.getPath());
            }
            catch(IOException e)
            {
                JOptionPane.showMessageDialog(this, "저장할 수 없습니다 : " + e.getMessage());
            }
        }
    }

    /** 그림을 패널 크기에 맞춰 줄여 그린다 */
    static class ImagePanel extends JPanel
    {
        private static final long serialVersionUID = 1L;
        BufferedImage m_image;
        void setImage(BufferedImage img) { m_image = img; repaint(); }
        protected void paintComponent(Graphics g0)
        {
            super.paintComponent(g0);
            if(m_image == null) return;
            Graphics2D g = (Graphics2D)g0;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, getWidth(), getHeight());
            double dScale = Math.min((double)getWidth() / m_image.getWidth(), (double)getHeight() / m_image.getHeight());
            int w = (int)(m_image.getWidth() * dScale), h = (int)(m_image.getHeight() * dScale);
            g.drawImage(m_image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
        }
    }

    static BufferedImage decode(byte[] arPng) throws IOException
    {
        BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(arPng));
        if(img == null) throw new IOException("PNG를 읽을 수 없습니다");
        return img;
    }

    /** 테스트용: 창 없이 캡처해 저장만 한다. 저장한 파일을 돌려준다. */
    static File captureToFile(String strDevice, File dir, long nTime) throws IOException
    {
        byte[] arPng = capture(strDevice);
        File file = new File(dir, defaultFileName(nTime));
        java.nio.file.Files.write(file.toPath(), arPng);
        return file;
    }

    static void runLater(Runnable r) { SwingUtilities.invokeLater(r); }
    static JFrame frameOf(LogFilterMain main) { return main; }
}
