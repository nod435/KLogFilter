import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.TreeSet;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableColumnModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

public class LogTable extends JTable implements FocusListener, ActionListener
{
    private static final long             serialVersionUID = 1L;

    LogFilterMain                         m_LogFilterMain;
    FilterEngine                          m_engine;             // 필터 조건(Find/Tag 토큰)과 북마크 위치
    // 하이라이트는 필터가 아니라 표시 설정이므로 테이블이 가진다. (문자열과 소문자 토큰)
    // 하이라이트 입력창 6개: 입력창마다 토큰과 색상 번호(LogColor.COLOR_HIGHLIGHT의 위치). 끈 입력창은 토큰이 비어 있다.
    volatile Highlight[]                  m_arHighlight      = Highlight.emptySet();
    float                                 m_fFontSize;
    boolean                               m_bAltPressed;
    boolean[]                             m_arbShow;

    public LogTable(LogFilterTableModel tablemodel, LogFilterMain filterMain)
    {
        super(tablemodel);
        m_LogFilterMain = filterMain;
        m_arbShow       = new boolean[LogFilterTableModel.COMUMN_MAX];
        init();
        setColumnWidth();
    }

    void setFilterEngine(FilterEngine engine)
    {
        m_engine = engine;
    }

    public void changeSelection( int rowIndex, int columnIndex, boolean toggle, boolean extend )
    {
        if(rowIndex < 0 ) rowIndex = 0;
        if(rowIndex > getRowCount() - 1) rowIndex = getRowCount() - 1;
        super.changeSelection(rowIndex, columnIndex, toggle, extend);
        showRow(rowIndex);
    }

    public void changeSelection( int rowIndex, int columnIndex, boolean toggle, boolean extend, boolean bMove )
    {
        if(rowIndex < 0 ) rowIndex = 0;
        if(rowIndex > getRowCount() - 1) rowIndex = getRowCount() - 1;
        super.changeSelection(rowIndex, columnIndex, toggle, extend);
        if(bMove)
            showRow(rowIndex);
    }

    private void init() {
        KeyStroke copy = KeyStroke.getKeyStroke(KeyEvent.VK_C,ActionEvent.CTRL_MASK,false);
        registerKeyboardAction(this,"Copy",copy,JComponent.WHEN_FOCUSED);

        addFocusListener( this );
        // 남는 폭은 마지막 열(Msg)이 차지한다. 열 폭 합이 화면보다 넓으면 가로 스크롤 (getScrollableTracksViewportWidth)
        setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        m_fFontSize = 12;
        setOpaque(false);
        setAutoscrolls(false);

        setIntercellSpacing(new Dimension(0, 0));
        // turn off grid painting as we'll handle this manually in order to paint
        // grid lines over the entire viewport.
        setShowGrid(false);

        for(int iIndex = 0; iIndex < getColumnCount(); iIndex++)
        {
            getColumnModel().getColumn(iIndex).setCellRenderer(new LogCellRenderer());
        }

        addMouseListener(new MouseAdapter()
        {
            public void mouseClicked( MouseEvent e )
            {
                Point p = e.getPoint();
                int row = rowAtPoint( p );
                if(row < 0) return;
                if ( SwingUtilities.isLeftMouseButton( e ) )
                {
                    if (e.getClickCount() == 2){
                        LogInfo logInfo = ((LogFilterTableModel)getModel()).getRow(row);
                        logInfo.m_bMarked = !logInfo.m_bMarked;
                        m_LogFilterMain.bookmarkItem(row, logInfo.m_nLine - 1, logInfo.m_bMarked);
                     }
                    else if(m_bAltPressed)
                    {
                        // Alt+좌클릭(Tag): Show tag 필터에 추가/제거
                        int colum = columnAtPoint(p);
                        if(colum == LogFilterTableModel.COMUMN_TAG && m_engine != null)
                        {
                            String strTag     = (String)((LogFilterTableModel)getModel()).getRow(row).getData(colum);
                            String strShowTag = m_engine.getShowTag();
                            if(strShowTag.contains("|" + strTag))
                                m_engine.setShowTag(strShowTag.replace("|" + strTag, ""));
                            else if(strShowTag.contains(strTag))
                                m_engine.setShowTag(strShowTag.replace(strTag, ""));
                            else
                                m_engine.setShowTag(strShowTag + "|" + strTag);
                            m_LogFilterMain.notiEvent(new INotiEvent.EventParam(INotiEvent.EVENT_CHANGE_FILTER_SHOW_TAG));
                        }
                    }
                }
                else if ( SwingUtilities.isRightMouseButton( e ))
                {
                    int colum = columnAtPoint(p);
                    if(m_bAltPressed)
                    {
                        // Alt+우클릭(Tag): Remove tag 필터에 추가
                        if(colum == LogFilterTableModel.COMUMN_TAG && m_engine != null)
                        {
                            String strTag = (String)((LogFilterTableModel)getModel()).getRow(row).getData(colum);
                            m_engine.setRemoveTag(m_engine.getRemoveTag() + "|" + strTag);
                            m_LogFilterMain.notiEvent(new INotiEvent.EventParam(INotiEvent.EVENT_CHANGE_FILTER_REMOVE_TAG));
                        }
                    }
                    else
                    {
                        // 우클릭: 그 줄의 로그 전체(원본 그대로)를 커서 위치에 보여준다. 복사 메뉴 포함.
                        changeSelection(row, colum, false, false, false);
                        showFullLog(row, colum, p);
                    }
                }
            }
        });
        getTableHeader().addMouseListener(new ColumnHeaderListener());
    }

    static final int FULL_LOG_MAX_WIDTH  = 900;    // 로그 전체 창의 최대 폭
    static final int FULL_LOG_MAX_HEIGHT = 400;    // 이보다 길면 스크롤

    // 우클릭한 줄의 로그 전체를 줄바꿈해서 보여주는 팝업 (원본 줄, 줄 번호, 복사 메뉴)
    void showFullLog(int nRow, int nColumn, Point point)
    {
        LogFilterTableModel model = (LogFilterTableModel)getModel();
        LogList list = model.getData();
        if(list == null || nRow >= model.getRowCount()) return;
        LogInfo logInfo   = model.getRow(nRow);
        final String strLine = list.rawLine(nRow);
        final String strCell = (String)logInfo.getData(nColumn);

        JTextArea taLine = new JTextArea(strLine);
        taLine.setEditable(false);
        taLine.setLineWrap(true);
        taLine.setWrapStyleWord(true);
        taLine.setFont(getFont().deriveFont(m_fFontSize));
        taLine.setForeground(logInfo.m_TextColor);
        taLine.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        taLine.setCaretPosition(0);

        // 줄바꿈한 높이를 구해 창 크기를 정한다. (짧으면 글자 폭에 맞춤)
        int nTextWidth = taLine.getFontMetrics(taLine.getFont()).stringWidth(strLine.replace("\t", "    ")) + 16;
        int nWidth     = Math.max(240, Math.min(Math.min(FULL_LOG_MAX_WIDTH, getVisibleRect().width - 20), nTextWidth));
        taLine.setSize(nWidth, Short.MAX_VALUE);
        int nHeight    = Math.min(FULL_LOG_MAX_HEIGHT, taLine.getPreferredSize().height);
        JScrollPane spLine = new JScrollPane(taLine, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        spLine.setBorder(BorderFactory.createEmptyBorder());
        spLine.setPreferredSize(new Dimension(nWidth + (nHeight < taLine.getPreferredSize().height ? 18 : 0), nHeight));

        final JPopupMenu popup = new JPopupMenu();
        JLabel jlTitle = new JLabel(" Line " + logInfo.m_nLine);
        jlTitle.setFont(jlTitle.getFont().deriveFont(Font.BOLD));
        popup.add(jlTitle);
        popup.add(spLine);
        popup.addSeparator();
        JMenuItem miCopyLine = new JMenuItem("로그 전체 복사");
        miCopyLine.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e) { copyToClipboard(strLine); }
        });
        JMenuItem miCopyCell = new JMenuItem("셀 값 복사 : " + abbreviate(strCell, 40));
        miCopyCell.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e) { copyToClipboard(strCell); }
        });
        popup.add(miCopyLine);
        popup.add(miCopyCell);
        m_popupFullLog = popup;
        popup.show(this, point.x, point.y);
    }

    JPopupMenu m_popupFullLog;     // 마지막으로 띄운 로그 전체 팝업 (테스트용)

    static String abbreviate(String strText, int nMax)
    {
        if(strText == null) return "";
        return strText.length() <= nMax ? strText : strText.substring(0, nMax) + "…";
    }

    static void copyToClipboard(String strText)
    {
        StringSelection data = new StringSelection(strText == null ? "" : strText);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(data, data);
    }

    public boolean isCellEditable(int row, int column)
    {
        if(column == LogFilterTableModel.COMUMN_BOOKMARK)
            return true;
        return false;
    }

    boolean isInnerRect(Rectangle parent, Rectangle child)
    {
        if(parent.y <= child.y && (parent.y + parent.height) >= (child.y + child.height))
            return true;
        else
            return false;
    }

    // 하이라이트 입력창 하나의 상태 (바꿀 때는 새 객체로 교체: 렌더러가 읽는 중에도 안전)
    static final class Highlight
    {
        final String   m_strText;
        final String[] m_arToken;
        final int      m_nColor;

        Highlight(String strText, int nColor)
        {
            m_strText = FilterEngine.nz(strText);
            m_arToken = FilterToken.split(m_strText);
            m_nColor  = nColor;
        }

        static Highlight[] emptySet()
        {
            Highlight[] ar = new Highlight[LogColor.HIGHLIGHT_COUNT];
            for(int i = 0; i < ar.length; i++)
                ar[i] = new Highlight("", i);
            return ar;
        }
    }

    // nIndex번 하이라이트 입력창의 문자열(끄면 "")과 색상 번호를 정한다. EDT에서 호출.
    void SetHighlight(int nIndex, String strHighlight, int nColor)
    {
        Highlight[] ar = m_arHighlight.clone();
        ar[nIndex] = new Highlight(strHighlight, nColor);
        m_arHighlight = ar;
    }

    Highlight[] GetHighlights() { return m_arHighlight; }
    String[] GetFindTokens()      { return m_engine != null ? m_engine.getFindTokens()    : FilterToken.EMPTY; }
    String[] GetTagShowTokens()   { return m_engine != null ? m_engine.getShowTagTokens() : FilterToken.EMPTY; }

    void gotoNextBookmark()
    {
        gotoBookmark(true);
    }

    void gotoPreBookmark()
    {
        gotoBookmark(false);
    }

    // F2/F3: 표시 중인 목록의 북마크 위치를 정렬해 두고 현재 행의 이전/다음을 찾는다.
    // (모든 행을 훑지 않음) 끝에 닿으면 반대쪽 끝에서 다시 찾는다.
    void gotoBookmark(boolean bNext)
    {
        if(m_engine == null) return;

        int nRowCount = getRowCount();
        TreeSet<Integer> setPos = new TreeSet<Integer>();
        for(Integer nPos : m_engine.getView().hmBookmark.values())
            if(nPos < nRowCount)
                setPos.add(nPos);
        if(setPos.isEmpty()) return;

        int nSelected = getSelectedRow();
        Integer nTarget = bNext ? setPos.higher(nSelected) : setPos.lower(nSelected < 0 ? nRowCount : nSelected);
        if(nTarget == null)
            nTarget = bNext ? setPos.first() : setPos.last();
        if(nTarget == nSelected) return;

        Rectangle parent = getVisibleRect();
        changeSelection(nTarget, 0, false, false);
        int nVisible = nTarget;
        if(!isInnerRect(parent, getCellRect(nTarget, 0, true)))
            nVisible = nTarget + (nTarget > nSelected ? 1 : -1) * getVisibleRowCount() / 2;   // 진행 방향으로 여유를 두고 보이게
        showRow(nVisible);
    }

    int getVisibleRowCount()
    {
        return getVisibleRect().height/getRowHeight();
    }
    public void hideColumn(int nColumn)
    {
        getColumnModel().getColumn(nColumn).setWidth(0);
        getColumnModel().getColumn(nColumn).setMinWidth(0);
        getColumnModel().getColumn(nColumn).setMaxWidth(0);
        getColumnModel().getColumn(nColumn).setPreferredWidth(0);
        getColumnModel().getColumn(nColumn).setResizable(false);
    }

    protected boolean processKeyBinding(KeyStroke ks, KeyEvent e, int condition, boolean pressed)
    {
        m_bAltPressed = e.isAltDown();
        {
            switch(e.getKeyCode())
            {
                case KeyEvent.VK_END:
                    changeSelection(getRowCount() - 1, 0, false, false);
                    return true;
                case KeyEvent.VK_HOME:
                    changeSelection(0, 0, false, false);
                    return true;
                case KeyEvent.VK_F2:
                    if(e.isControlDown() && e.getID() == KeyEvent.KEY_PRESSED)
                    {
                        int[] arSelectedRow = getSelectedRows();
                        for(int nIndex : arSelectedRow)
                        {
                            LogInfo logInfo = ((LogFilterTableModel)getModel()).getRow(nIndex);
                            logInfo.m_bMarked = !logInfo.m_bMarked;
                            m_LogFilterMain.bookmarkItem(nIndex, logInfo.m_nLine - 1, logInfo.m_bMarked);
                        }
                        repaint();
                    }
                    // F2: 다음 북마크, Shift+F2: 이전 북마크 (F3은 메시지 검색에 씀)
                    else if(!e.isControlDown() && e.getID() == KeyEvent.KEY_PRESSED)
                    {
                        if(e.isShiftDown())
                            gotoPreBookmark();
                        else
                            gotoNextBookmark();
                    }
                    return true;
                case KeyEvent.VK_F3:
                    // F3: 다음 찾기, Shift+F3: 이전 찾기 (메시지 검색)
                    if(e.getID() == KeyEvent.KEY_PRESSED)
                        m_LogFilterMain.search(!e.isShiftDown());
                    return true;
                case KeyEvent.VK_F:
                    if(e.getID() == KeyEvent.KEY_PRESSED && ( (e.getModifiers() & InputEvent.CTRL_MASK) == InputEvent.CTRL_MASK))
                    {
                        m_LogFilterMain.setFindFocus();
                        return true;
                    }
                    break;
            }
        }
        return super.processKeyBinding(ks, e, condition, pressed);
    }

    public void packColumn(int vColIndex, int margin) {
        DefaultTableColumnModel colModel = (DefaultTableColumnModel)getColumnModel();
        TableColumn col = colModel.getColumn(vColIndex);
        int width = 0;

        // Get width of column header
        TableCellRenderer renderer = col.getHeaderRenderer();
        if (renderer == null) {
            renderer = getTableHeader().getDefaultRenderer();
        }
        Component comp;

        JViewport viewport = (JViewport)m_LogFilterMain.m_scrollVBar.getViewport();
        Rectangle viewRect = viewport.getViewRect();
        int nFirst = m_LogFilterMain.m_tbLogTable.rowAtPoint(new Point(0, viewRect.y));
        int nLast = m_LogFilterMain.m_tbLogTable.rowAtPoint(new Point(0, viewRect.height - 1));

        if(nLast < 0)
            nLast = m_LogFilterMain.m_tbLogTable.getRowCount();
        // Get maximum width of column data
        for (int r=nFirst; r<nFirst + nLast; r++) {
            renderer = getCellRenderer(r, vColIndex);
            comp = renderer.getTableCellRendererComponent(
                this, getValueAt(r, vColIndex), false, false, r, vColIndex);
            width = Math.max(width, comp.getPreferredSize().width);
        }

        // Add margin
        width += 2*margin;

        // Set the width
        col.setPreferredWidth(width);
    }

    // 열 폭 합이 화면보다 좁으면 화면 폭에 맞춰(남는 공간은 Msg 열로), 넓으면 원래 폭대로 두고 가로 스크롤한다.
    public boolean getScrollableTracksViewportWidth()
    {
        Container parent = getParent();
        return parent instanceof JViewport && getPreferredSize().width < parent.getWidth();
    }

    public float getFontSize()
    {
        return m_fFontSize;
    }
    
    public int getColumnWidth(int nColumn)
    {
        return getColumnModel().getColumn(nColumn).getWidth();
    }

    public void showColumn(int nColumn, boolean bShow)
    {
        m_arbShow[nColumn] = bShow;
        if(bShow)
        {
            getColumnModel().getColumn(nColumn).setResizable(true);
            getColumnModel().getColumn(nColumn).setMaxWidth(LogFilterTableModel.ColWidth[nColumn] * 1000);
            getColumnModel().getColumn(nColumn).setMinWidth(1);
            getColumnModel().getColumn(nColumn).setWidth(LogFilterTableModel.ColWidth[nColumn]);
            getColumnModel().getColumn(nColumn).setPreferredWidth(LogFilterTableModel.ColWidth[nColumn]);
        }
        else
            hideColumn(nColumn);
    }

    public void setColumnWidth()
    {
        for(int iIndex = 0; iIndex < getColumnCount(); iIndex++)
        {
            showColumn(iIndex, true);
        }
        showColumn(LogFilterTableModel.COMUMN_BOOKMARK, false);
//        showColumn(LogFilterTableModel.COMUMN_THREAD, false);
    }


    public void setFontSize(int nFontSize)
    {
        m_fFontSize = nFontSize;
        setRowHeight(nFontSize + 4);
    }

    public void setValueAt(Object aValue, int row, int column)
    {
        LogInfo logInfo = ((LogFilterTableModel)getModel()).getRow(row);
        if(column == LogFilterTableModel.COMUMN_BOOKMARK)
        {
            logInfo.m_strBookmark = (String)aValue;
            m_LogFilterMain.setBookmark(logInfo.m_nLine - 1, (String)aValue);
        }
    }

    public class LogCellRenderer extends DefaultTableCellRenderer
    {
        private static final long serialVersionUID = 1L;
        boolean m_bChanged;

        public Component getTableCellRendererComponent(JTable table,
                                                       Object value,
                                                       boolean isSelected,
                                                       boolean hasFocus,
                                                       int row,
                                                       int column)
        {
            if(value != null)
                value = remakeData(column, (String)value);
            // HTML 문자열이 들어간 상태에서 글꼴·글자색을 바꾸면 Swing이 HTML을 다시 해석한다.
            // 그래서 빈 문자열로 색·글꼴을 먼저 정하고, 문자열은 마지막에 한 번만 넣는다. (스크롤 끊김 원인)
            Component c = super.getTableCellRendererComponent(table,
                                                              "",
                                                              isSelected,
                                                              hasFocus,
                                                              row,
                                                              column);
            LogInfo logInfo = ((LogFilterTableModel)getModel()).getRow(row);
            c.setFont(cellFont());
            c.setForeground(logInfo.m_TextColor);
            if(isSelected)
            {
                if(logInfo.m_bMarked)
                    c.setBackground(bookmarkColor(LogColor.COLOR_BOOKMARK2));
            }
            else if(logInfo.m_bMarked)
                c.setBackground(bookmarkColor(LogColor.COLOR_BOOKMARK));
            else
                c.setBackground(Color.WHITE);
            setValue(value);

            return c;
        }

        // 셀마다 deriveFont()/new Color()를 하지 않도록, 값이 바뀔 때만 다시 만든다.
        Font     m_fontCell;
        Font     m_fontBase;
        float    m_fFontCellSize;
        Color    m_colorBookmark;
        String[] m_arHighlightColor;
        String[] m_arHighlightSrc;

        Font cellFont()
        {
            Font fontBase = getFont();
            if(m_fontCell == null || m_fontBase != fontBase || m_fFontCellSize != m_fFontSize)
            {
                m_fontBase      = fontBase;
                m_fFontCellSize = m_fFontSize;
                m_fontCell      = fontBase.deriveFont(m_fFontSize);
            }
            return m_fontCell;
        }

        Color bookmarkColor(int nRGB)
        {
            if(m_colorBookmark == null || (m_colorBookmark.getRGB() & 0xFFFFFF) != (nRGB & 0xFFFFFF))
                m_colorBookmark = new Color(nRGB);
            return m_colorBookmark;
        }

        // LogColor.COLOR_HIGHLIGHT("RRGGBB" 형식) → "#RRGGBB" 배열. 원본 배열이 바뀔 때만 다시 만든다.
        String[] highlightColors()
        {
            String[] arSrc = LogColor.COLOR_HIGHLIGHT;
            if(m_arHighlightColor == null || m_arHighlightSrc != arSrc)
            {
                m_arHighlightSrc = arSrc;
                if(arSrc != null && arSrc.length > 0)
                {
                    m_arHighlightColor = new String[arSrc.length];
                    for(int i = 0; i < arSrc.length; i++)
                        m_arHighlightColor[i] = "#" + arSrc[i];
                }
                else
                    m_arHighlightColor = new String[] { "#00FF00" };
            }
            return m_arHighlightColor;
        }

        // 일반 텍스트(HTML 아님)로 표시할 때: 탭만 공백 4칸으로 바꾼다. 탭이 없으면 새 문자열을 만들지 않음.
        String plainText(String strText)
        {
            return strText.indexOf('\t') < 0 ? strText : strText.replace("\t", "    ");
        }

        String remakeData(int nIndex, String strText)
        {
            if(nIndex != LogFilterTableModel.COMUMN_MESSAGE && nIndex != LogFilterTableModel.COMUMN_TAG) return strText;

            String[] arFindToken      = nIndex == LogFilterTableModel.COMUMN_MESSAGE ? GetFindTokens() : GetTagShowTokens();
            Highlight[] arHighlight   = GetHighlights();

            // 하이라이트/Find 토큰이 하나도 일치하지 않으면 배열·HTML을 만들지 않고 바로 반환
            boolean bAny = FilterToken.matchAny(strText, arFindToken);
            for(int i = 0; !bAny && i < arHighlight.length; i++)
                bAny = FilterToken.matchAny(strText, arHighlight[i].m_arToken);
            if(!bAny)
                return plainText(strText);

            // 1) 원문에서 일치 구간을 글자 단위로 표시 (대소문자 무시)
            String[] arBackground = new String[strText.length()];
            boolean[] arFind      = new boolean[strText.length()];
            String strLower = strText.toLowerCase();
            m_bChanged = false;
            // 입력창 순서대로 칠한다(겹치면 뒤 입력창 색상). 입력창마다 고른 색상 하나를 쓴다.
            String[] arColor = highlightColors();
            for(Highlight highlight : arHighlight)
                if(highlight.m_arToken.length > 0)
                    markMatch(strLower, highlight.m_arToken, new String[]{ arColor[highlight.m_nColor % arColor.length] }, arBackground, null);
            markMatch(strLower, arFindToken, null, null, arFind);

            if(!m_bChanged)
                return plainText(strText);

            // 2) 구간 정보로 HTML을 한 번에 생성 (원문의 &, <, >는 이스케이프)
            StringBuilder sb = new StringBuilder(strText.length() * 2 + 64);
            sb.append("<html><nobr>");
            String strCurBg = null;
            boolean bCurFind = false;
            for(int i = 0; i < strText.length(); i++)
            {
                if(i == 0 || !equalsColor(strCurBg, arBackground[i]) || bCurFind != arFind[i])
                {
                    closeStyle(sb, strCurBg, bCurFind);
                    strCurBg = arBackground[i];
                    bCurFind = arFind[i];
                    openStyle(sb, strCurBg, bCurFind);
                }
                appendEscaped(sb, strText.charAt(i));
            }
            closeStyle(sb, strCurBg, bCurFind);
            sb.append("</nobr></html>");
            return sb.toString();
        }

        /**
         * 소문자 토큰(arToken)이 strLower(소문자로 바꾼 원문)에 나오는 위치를 표시한다.
         * arBackground가 있으면 토큰별 색상(arColor 순환)을, arFind가 있으면 true를 기록한다.
         */
        void markMatch(String strLower, String[] arToken, String[] arColor, String[] arBackground, boolean[] arFind)
        {
            int nLen = arBackground != null ? arBackground.length : arFind.length;
            int nColor = 0;

            for(String strToken : arToken)
            {
                int nPos = strLower.indexOf(strToken);
                if(nPos < 0) continue;

                String strColor = arColor != null ? arColor[nColor % arColor.length] : null;
                while(nPos >= 0)
                {
                    // 소문자 변환으로 길이가 달라지는 드문 문자에 대비해 배열 범위를 넘지 않게 제한
                    for(int i = nPos; i < Math.min(nPos + strToken.length(), nLen); i++)
                    {
                        if(arBackground != null) arBackground[i] = strColor;
                        if(arFind != null)       arFind[i] = true;
                    }
                    nPos = strLower.indexOf(strToken, nPos + strToken.length());
                }
                m_bChanged = true;
                nColor++;
            }
        }

        boolean equalsColor(String a, String b)
        {
            return a == null ? b == null : a.equals(b);
        }

        void openStyle(StringBuilder sb, String strBackground, boolean bFind)
        {
            if(strBackground != null) sb.append("<span style=\"background-color:").append(strBackground).append("\"><b>");
            if(bFind)                 sb.append("<font color=#FF0000><b>");
        }

        void closeStyle(StringBuilder sb, String strBackground, boolean bFind)
        {
            if(bFind)                 sb.append("</b></font>");
            if(strBackground != null) sb.append("</b></span>");
        }

        void appendEscaped(StringBuilder sb, char c)
        {
            switch(c)
            {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case ' ':  sb.append("&nbsp;"); break;
                case '\t': sb.append("&nbsp;&nbsp;&nbsp;&nbsp;"); break;
                default:   sb.append(c);
            }
        }
    }

    public void showRow(int row)
    {
        if(row < 0 ) row = 0;
        if(row > getRowCount() - 1) row = getRowCount() - 1;

        Rectangle rList = getVisibleRect();
        Rectangle rCell = getCellRect(row, 0, true);
        if(rList != null && rCell != null)
        {
            Rectangle scrollToRect = new Rectangle((int)rList.getX(), (int)rCell.getY(), (int)(rList.getWidth()), (int)rCell.getHeight());
            scrollRectToVisible(scrollToRect);
        }
    }

    public void showRow(int row, boolean bCenter)
    {
        int nLastSelectedIndex = getSelectedRow();

        changeSelection(row, 0, false, false);
        int nVisible = row;
        if(nLastSelectedIndex <= row || nLastSelectedIndex == -1)
            nVisible = row + getVisibleRowCount() / 2;
        else
            nVisible = row - getVisibleRowCount() / 2;
        if(nVisible < 0) nVisible = 0;
        else if(nVisible > getRowCount() - 1) nVisible = getRowCount() - 1;
        showRow(nVisible);
    }

    public class ColumnHeaderListener extends MouseAdapter {
        public void mouseClicked(MouseEvent evt) {

            if ( SwingUtilities.isLeftMouseButton( evt ) && evt.getClickCount() == 2 )
            {
                JTable table = ((JTableHeader)evt.getSource()).getTable();
                TableColumnModel colModel = table.getColumnModel();

                // The index of the column whose header was clicked
                int vColIndex = colModel.getColumnIndexAtX(evt.getX());

                if (vColIndex == -1) {
                    T.d("vColIndex == -1");
                    return;
                }
                packColumn(vColIndex, 1);
            }
        }
    }

    @Override
    public void focusGained( FocusEvent arg0 )
    {
    }

    @Override
    public void focusLost( FocusEvent arg0 )
    {
        m_bAltPressed = false;
    }

    @Override
    public void actionPerformed( ActionEvent arg0 )
    {
        Clipboard system;
        StringBuffer sbf = new StringBuffer();
        int numrows = getSelectedRowCount();
        int[] rowsselected = getSelectedRows();
        int nTagLength = tagLength();

        for ( int i = 0; i < numrows; i++ )
        {
            for ( int j = 0; j < m_arbShow.length; j++ )
            {
                if(!(j == LogFilterTableModel.COMUMN_LINE) && m_arbShow[j])
                {
                    StringBuffer strTemp = new StringBuffer((String)getValueAt( rowsselected[i], j ));
                    if(j == LogFilterTableModel.COMUMN_TAG)
                    {
                        String strTag = strTemp.toString();
                        for(int k = 0; k < nTagLength - strTag.length(); k++)
                            strTemp.append(" ");
                    }
                    else if(j == LogFilterTableModel.COMUMN_THREAD || j == LogFilterTableModel.COMUMN_PID)
                    {
                        String strTag = strTemp.toString();
                        for(int k = 0; k < 8 - strTag.length(); k++)
                            strTemp.append(" ");
                    }
                    strTemp.append(" ");
                    sbf.append( strTemp );
                }
            }
            sbf.append( "\n" );
        }
        StringSelection stsel = new StringSelection( sbf.toString() );
        system = Toolkit.getDefaultToolkit().getSystemClipboard();
        system.setContents(stsel,stsel);
    }
    
    // 복사할 때 Tag 컬럼을 맞출 폭: 지금까지 해석한 줄 중 가장 긴 태그 길이
    int tagLength()
    {
        return m_engine != null ? m_engine.getStore().m_nMaxTagLength : 0;
    }
}
