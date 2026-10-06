import java.util.Arrays;

/** 뒤에 추가만 하는 long 배열. 규칙은 IntList와 같다(쓰는 스레드 하나, 읽는 쪽은 size() 범위 안에서만). */
public class LongList
{
    private volatile long[] m_arValue;
    private volatile int    m_nSize;

    public LongList(int nCapacity)
    {
        m_arValue = new long[Math.max(16, nCapacity)];
    }

    public int size()
    {
        return m_nSize;
    }

    public long get(int nIndex)
    {
        return m_arValue[nIndex];
    }

    public void add(long nValue)
    {
        long[] arValue = m_arValue;
        int    nSize   = m_nSize;
        if(nSize == arValue.length)
        {
            arValue = Arrays.copyOf(arValue, nSize + (nSize >> 1) + 16);
            m_arValue = arValue;
        }
        arValue[nSize] = nValue;
        m_nSize = nSize + 1;
    }
}
