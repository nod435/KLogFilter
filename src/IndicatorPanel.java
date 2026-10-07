import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.Map;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.border.EmptyBorder;

public class IndicatorPanel extends JPanel
{
    private static final long serialVersionUID      = 1L;

    final int                 INDICATRO_BOOK_X_POS  = 5;
    final int                 INDICATRO_WIDTH       = 12;
    final int                 INDICATRO_ERROR_X_POS = 23;
    final int                 INDICATRO_Y_POS       = 22;
    final int                 INDICATRO_Y_GAP       = 5;

    Rectangle                 m_rcBookmark;
    Rectangle                 m_rcError;
    JCheckBox                 m_chBookmark;
    JCheckBox                 m_chError;
    LogList                   m_arLogInfo;
    Map<Integer, Integer>     m_hmBookmark;
    Map<Integer, Integer>     m_hmError;
    LogFilterMain             m_LogFilterMain;
    

    public IndicatorPanel(LogFilterMain logFilterMain)
    {
        super();
        m_LogFilterMain = logFilterMain;
        m_chBookmark = new JCheckBox();
        m_chBookmark.addItemListener(m_itemListener);
        m_chBookmark.setBorder( new EmptyBorder( 0, 0, 0, 0 ) );

        m_chError = new JCheckBox();
        m_chError.addItemListener(m_itemListener);
        m_chError.setBorder( new EmptyBorder( 0, 0, 0, 0 ) );

        m_rcBookmark = new Rectangle();
        m_rcError = new Rectangle();
        add(m_chBookmark);
        add(m_chError);

        addMouseListener(new MouseListener()
        {
            public void mouseReleased(MouseEvent e){}            
            public void mousePressed(MouseEvent e)
            {
                if(m_arLogInfo != null)
                {
                    float fRate = (float)(e.getY() - m_rcBookmark.y)/(float)(m_rcBookmark.height);
                    int nIndex = (int)(m_arLogInfo.size() * fRate);
                    m_LogFilterMain.m_tbLogTable.showRow(nIndex, false);
                }
            }
            
            public void mouseExited(MouseEvent e){}            
            public void mouseEntered(MouseEvent e){}            
            public void mouseClicked(MouseEvent e){}
        });
        addMouseMotionListener(new MouseMotionListener()
        {
            public void mouseMoved(MouseEvent e){}
            public void mouseDragged(MouseEvent e)
            {
                if(m_arLogInfo != null)
                {
                    float fRate = (float)(e.getY() - m_rcBookmark.y)/(float)(m_rcBookmark.height);
                    int nIndex = (int)(m_arLogInfo.size() * fRate);
                    m_LogFilterMain.m_tbLogTable.showRow(nIndex, false);
                }
            }
        });
        addMouseWheelListener(new MouseWheelListener()
        {
            public void mouseWheelMoved(MouseWheelEvent e)
            {
                m_LogFilterMain.m_scrollVBar.dispatchEvent(e);
            }
        });
    }
    
    public void paintComponent(Graphics g)
    {
        super.paintComponent(g);
        m_rcBookmark.setBounds(INDICATRO_BOOK_X_POS, INDICATRO_Y_POS, INDICATRO_WIDTH, getHeight() - INDICATRO_Y_POS - INDICATRO_Y_GAP);
        m_rcError.setBounds(INDICATRO_ERROR_X_POS, INDICATRO_Y_POS, INDICATRO_WIDTH, getHeight() - INDICATRO_Y_POS - INDICATRO_Y_GAP);
        drawIndicator(g);
        drawBookmark(g);
        drawError(g);
        drawPageIndicator(g);
    }
    
    // 픽셀 줄마다 표시할지 미리 계산해 둔 결과. 스크롤할 때는 이것만 그리고, 데이터나 높이가 바뀔 때만 다시 계산한다.
    // (에러가 수만 개면 스크롤할 때마다 전부 그리느라 화면이 멈췄음)
    static class Marks
    {
        Map<Integer, Integer> map;
        int  nMapSize = -1, nTotal = -1, nHeight = -1;
        boolean[] arPixel = new boolean[0];

        boolean[] get(Map<Integer, Integer> hm, int nTotalCount, int nH)
        {
            int nSize = hm.size();
            if(hm == map && nSize == nMapSize && nTotalCount == nTotal && nH == nHeight)
                return arPixel;
            boolean[] ar = new boolean[Math.max(0, nH)];
            if(nH > 0 && nTotalCount > 0)
            {
                // 한 줄이 1픽셀보다 크면 그 높이만큼 칠한다 (원래 그리기와 같음)
                float fRate = (float)nH / (float)nTotalCount;
                int nRowH = nH > nTotalCount ? nH / nTotalCount + 1 : 1;
                for(Integer nPos : hm.values())
                {
                    int nY1 = (int)(nPos * fRate);
                    for(int y = Math.max(0, nY1); y < nY1 + nRowH && y < nH; y++)
                        ar[y] = true;
                }
            }
            map = hm; nMapSize = nSize; nTotal = nTotalCount; nHeight = nH; arPixel = ar;
            return ar;
        }
    }
    final Marks m_marksBookmark = new Marks();
    final Marks m_marksError    = new Marks();

    // 이어진 픽셀은 사각형 하나로 그린다.
    static void fillRuns(Graphics g, boolean[] ar, int nX, int nY, int nWidth)
    {
        for(int y = 0; y < ar.length; )
        {
            if(!ar[y]) { y++; continue; }
            int nStart = y;
            while(y < ar.length && ar[y]) y++;
            g.fillRect(nX, nY + nStart, nWidth, y - nStart);
        }
    }

    void drawIndicator(Graphics g)
    {
        if(m_arLogInfo == null) return;

        int TOTAL_COUNT = m_arLogInfo.size();
        if(TOTAL_COUNT <= 0) return;

        g.setColor(Color.BLUE);
        fillRuns(g, m_marksBookmark.get(m_hmBookmark, TOTAL_COUNT, m_rcBookmark.height), m_rcBookmark.x, INDICATRO_Y_POS, m_rcBookmark.width);
        g.setColor(Color.RED);
        fillRuns(g, m_marksError.get(m_hmError, TOTAL_COUNT, m_rcError.height), m_rcError.x, INDICATRO_Y_POS, m_rcError.width);
    }

    void drawBookmark(Graphics g)
    {
        g.setColor(Color.BLUE);
        g.drawRect(m_rcBookmark.x, m_rcBookmark.y, m_rcBookmark.width, m_rcBookmark.height);
    }

    void drawError(Graphics g)
    {
        g.setColor(Color.RED);
        g.drawRect(m_rcError.x, m_rcError.y, m_rcError.width, m_rcError.height);
    }
    
    int PAGE_INDICATOR_WIDTH = 3;
    int PAGE_INDICATOR_GAP = 2;
    void drawPageIndicator(Graphics g)
    {
        if(m_arLogInfo == null) return;

        int TOTAL_COUNT = m_arLogInfo.size();

        if(TOTAL_COUNT > 0)
        {
            JViewport viewport = (JViewport)m_LogFilterMain.m_scrollVBar.getViewport();
            Rectangle viewRect = viewport.getViewRect();
            
            int nItemHeight = m_LogFilterMain.m_tbLogTable.getRowHeight();
            if(nItemHeight > 0)
            {
                float fRate = (float)m_rcBookmark.height / (float)TOTAL_COUNT;

                int nFirst = m_LogFilterMain.m_tbLogTable.rowAtPoint(new Point(0, viewRect.y));
                int nLast = m_LogFilterMain.m_tbLogTable.rowAtPoint(new Point(0, viewRect.height - 1));
                int nY1 = (int)(m_rcBookmark.y + nFirst * fRate);
                int nH = (int)((nLast + 1) * fRate);
                if(nH <= 0)
                    nH = 1;
                if(nY1 + nH > m_rcBookmark.y + m_rcBookmark.height)
                    nH = m_rcBookmark.y + m_rcBookmark.height - nY1;
                if(nLast == - 1)
                    nH = m_rcBookmark.height;

                g.drawRect(m_rcBookmark.x - PAGE_INDICATOR_WIDTH - PAGE_INDICATOR_GAP, nY1, PAGE_INDICATOR_WIDTH, nH);
                g.drawRect(m_rcError.x + m_rcError.width + PAGE_INDICATOR_GAP, nY1, PAGE_INDICATOR_WIDTH, nH);
            }
        }
    }

    ItemListener m_itemListener = new ItemListener() {
        public void itemStateChanged(ItemEvent itemEvent) {
            if(itemEvent.getSource().equals(m_chBookmark))
            {
                m_LogFilterMain.notiEvent(new INotiEvent.EventParam(INotiEvent.EVENT_CLICK_BOOKMARK));
            }
            else if(itemEvent.getSource().equals(m_chError))
            {
                m_LogFilterMain.notiEvent(new INotiEvent.EventParam(INotiEvent.EVENT_CLICK_ERROR));
            }
        }
    };
    
    // EDT에서만 호출. 맵은 ConcurrentHashMap이라 다른 스레드가 추가하는 중에도 순회할 수 있다.
    public void setData(LogList arLogInfo, Map<Integer, Integer> hmBookmark, Map<Integer, Integer> hmError)
    {
        m_arLogInfo     = arLogInfo;
        m_hmBookmark    = hmBookmark;
        m_hmError       = hmError;
    }
}
