
import javax.swing.table.AbstractTableModel;

public class LogFilterTableModel extends AbstractTableModel
{
    static final int        COMUMN_LINE     = 0;
    static final int        COMUMN_DATE     = 1;
    static final int        COMUMN_TIME     = 2;
    static final int        COMUMN_LOGLV    = 3;
    static final int        COMUMN_PID      = 4;
    static final int        COMUMN_THREAD   = 5;
    static final int        COMUMN_TAG      = 6;
    static final int        COMUMN_BOOKMARK = 7;
    static final int        COMUMN_MESSAGE  = 8;
    public static final int COMUMN_MAX      = 9;

    private static final long serialVersionUID = 1L;

    public static String  ColName[]     = { "Line", "Date", "Time", "LogLV", "Pid", "Thread", "Tag", "Bookmark", "Message" };
    public static int     ColWidth[]    = { 50,     50,     100,    20,      50,    50,       100,   100,        600};
    public static int     DEFULT_WIDTH[]= { 50,     50,     100,    20,      50,    50,       100,   100,        600};
    
    LogList            m_arData;
    // 테이블에 알린(fire) 행 수. 다른 스레드가 m_arData에 줄을 추가해도, EDT에서 syncRowCount()로
    // 알리기 전까지는 늘어나지 않는다. (JTable이 알림 없이 바뀐 행 수를 보지 않도록)
    int                m_nRowCount;

    public static void setColumnWidth(int nColumn, int nWidth)
    {
        T.d("nWidth = " + nWidth);
        if(nWidth >= DEFULT_WIDTH[nColumn])
            ColWidth[nColumn] = nWidth;
    }

    public int getColumnCount()
    {
        return ColName.length;
    }

    public int getRowCount()
    {
        return m_nRowCount;
    }

    // EDT에서만 호출: 리스트의 현재 크기를 행 수로 반영하고 새 행 수를 돌려준다.
    public int syncRowCount()
    {
        m_nRowCount = m_arData != null ? m_arData.size() : 0;
        return m_nRowCount;
    }

    public LogList getData()
    {
        return m_arData;
    }

    public String getColumnName(int col) {
        return ColName[col];
    }
    
    public Object getValueAt(int rowIndex, int columnIndex)
    {
        return m_arData.get(rowIndex).getData(columnIndex);
    }
    
    public LogInfo getRow(int row) {
        return m_arData.get(row);
    }
    
    // EDT에서만 호출 (호출 후 fireTableDataChanged 필요)
    public void setData(LogList arData)
    {
        m_arData = arData;
        syncRowCount();
    }
}
