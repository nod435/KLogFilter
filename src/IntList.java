import java.util.Arrays;

/**
 * 뒤에 추가만 하는 int 배열.
 * 쓰는 스레드는 하나, 읽는 스레드는 여럿일 수 있다. 읽는 쪽은 size()로 확인한 범위 안에서만 get()한다.
 * (값을 먼저 쓰고 크기를 나중에 volatile로 올리므로, 보이는 크기 안의 값은 항상 채워져 있다)
 */
public class IntList
{
    private volatile int[] m_arValue;
    private volatile int   m_nSize;

    public IntList(int nCapacity)
    {
        m_arValue = new int[Math.max(16, nCapacity)];
    }

    public int size()
    {
        return m_nSize;
    }

    public int get(int nIndex)
    {
        return m_arValue[nIndex];
    }

    public void add(int nValue)
    {
        int[] arValue = m_arValue;
        int   nSize   = m_nSize;
        if(nSize == arValue.length)
        {
            arValue = Arrays.copyOf(arValue, nSize + (nSize >> 1) + 16);
            m_arValue = arValue;
        }
        arValue[nSize] = nValue;
        m_nSize = nSize + 1;
    }
}
