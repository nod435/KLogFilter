import java.util.ArrayList;

/** 메모리에 있는 짧은 줄 목록 (시작 안내 문구 등). 파일 없이 문자열을 그대로 메시지로 보여준다. */
public class MemoryLogStore extends LogStore
{
    private final ArrayList<String> m_arLine = new ArrayList<String>();
    private volatile int m_nSize;

    public MemoryLogStore(ILogParser parser)
    {
        super(parser);
        m_bComplete = true;
    }

    // 한 줄 추가 (해석하지 않고 메시지로만 표시)
    synchronized void addMessage(String strMessage)
    {
        m_arLine.add(strMessage == null ? "" : strMessage);
        m_nSize = m_arLine.size();
    }

    public int size()
    {
        return m_nSize;
    }

    protected synchronized String readLine(int nIndex)
    {
        return m_arLine.get(nIndex);
    }

    protected LogInfo parse(int nIndex, String strLine)
    {
        LogInfo logInfo = new LogInfo();
        logInfo.m_strMessage = strLine;
        logInfo.setLine(nIndex + 1);
        return logInfo;
    }

    LogStore cleared()
    {
        return new MemoryLogStore(m_parser);
    }
}
