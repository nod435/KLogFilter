/** 필터를 통과한 줄 목록: 전체 목록(LogStore)의 줄 위치만 차례로 가진다. */
public class FilteredList implements LogList
{
    final LogStore  m_store;
    private final IntList m_rows = new IntList(1024);

    public FilteredList(LogStore store)
    {
        m_store = store;
    }

    public int size()
    {
        return m_rows.size();
    }

    public LogInfo get(int nRow)
    {
        return m_store.get(m_rows.get(nRow));
    }

    public int lineIndexOf(int nRow)
    {
        return m_rows.get(nRow);
    }

    // 쓰는 쪽은 FilterEngine 스레드 하나
    void add(int nIndex)
    {
        m_rows.add(nIndex);
    }
}
