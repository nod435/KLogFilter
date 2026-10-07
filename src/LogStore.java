import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 전체 줄 목록. 줄 내용은 필요할 때 해석(parse)하고, 최근에 본 줄만 캐시에 둔다.
 * 북마크와 메모는 LogInfo 객체가 아니라 여기에 줄 위치로 저장한다(캐시에서 빠져도 유지되도록).
 */
public abstract class LogStore implements LogList
{
    // 화면 표시용 캐시 크기 (한 화면은 수십 줄이므로 넉넉함)
    static final int CACHE_SIZE = 20000;

    // 순차 처리(분석·필터)에서 줄마다 호출된다. false를 돌려주면 중단한다.
    public interface Visitor
    {
        boolean visit(int nIndex, LogInfo logInfo);
    }

    final ILogParser               m_parser;
    private final BitSet           m_bsMarked = new BitSet();
    private final Map<Integer, String> m_hmMemo = new ConcurrentHashMap<Integer, String>();
    volatile int                   m_nMaxTagLength;
    // 모든 줄을 다 읽었는지 (파일 열기: 색인 완료 후 true, 실시간 수집: 계속 false, 메모리 목록: true)
    volatile boolean               m_bComplete;

    protected final LinkedHashMap<Integer, LogInfo> m_hmCache = new LinkedHashMap<Integer, LogInfo>(256, 0.75f, true)
    {
        private static final long serialVersionUID = 1L;
        protected boolean removeEldestEntry(Map.Entry<Integer, LogInfo> eldest)
        {
            return size() > CACHE_SIZE;
        }
    };

    protected LogStore(ILogParser parser)
    {
        m_parser = parser;
    }

    // nIndex번째 줄의 원본 문자열
    protected abstract String readLine(int nIndex);

    // 같은 원본에서 지금 이후의 줄만 담는 빈 목록 (실시간 수집 중 Clear용)
    abstract LogStore cleared();

    // 열어 둔 파일 등을 정리 (목록이 교체될 때)
    void close()
    {
    }

    public int lineIndexOf(int nRow)
    {
        return nRow;
    }

    public String rawLine(int nIndex)
    {
        return readLine(nIndex);
    }

    public LogInfo get(int nIndex)
    {
        LogInfo logInfo;
        synchronized(m_hmCache)
        {
            logInfo = m_hmCache.get(nIndex);
            if(logInfo == null)
                logInfo = load(nIndex);
        }
        applyState(nIndex, logInfo);
        return logInfo;
    }

    // 캐시에 없는 줄을 읽어 캐시에 넣는다(m_hmCache 락 안에서 호출). 하위 클래스는 주변 줄까지 한 번에 읽을 수 있다.
    protected LogInfo load(int nIndex)
    {
        LogInfo logInfo = parse(nIndex, readLine(nIndex));
        m_hmCache.put(nIndex, logInfo);
        return logInfo;
    }

    // [nFrom, nTo) 줄을 차례로 해석해 visitor에 넘긴다. 캐시에 넣지 않는다. 하위 클래스가 더 빠르게 바꿀 수 있다.
    void forEach(int nFrom, int nTo, Visitor visitor)
    {
        for(int i = nFrom; i < nTo; i++)
        {
            LogInfo logInfo = parse(i, readLine(i));
            applyState(i, logInfo);
            if(!visitor.visit(i, logInfo))
                return;
        }
    }

    protected LogInfo parse(int nIndex, String strLine)
    {
        LogInfo logInfo = m_parser.parseLog(strLine);
        logInfo.setLine(nIndex + 1);
        int nTag = logInfo.m_strTag.length();
        if(nTag > m_nMaxTagLength)
            m_nMaxTagLength = nTag;
        return logInfo;
    }

    // 북마크나 메모가 한 번이라도 생겼는지 (없으면 줄마다 조회하지 않는다)
    private volatile boolean m_bHasState;

    // 북마크·메모 상태를 LogInfo에 반영
    protected void applyState(int nIndex, LogInfo logInfo)
    {
        if(!m_bHasState)
        {
            logInfo.m_bMarked = false;
            logInfo.m_strBookmark = "";
            return;
        }
        logInfo.m_bMarked = isMarked(nIndex);
        String strMemo = m_hmMemo.get(nIndex);
        logInfo.m_strBookmark = strMemo == null ? "" : strMemo;
    }

    boolean isMarked(int nIndex)
    {
        synchronized(m_bsMarked)
        {
            return m_bsMarked.get(nIndex);
        }
    }

    void setMarked(int nIndex, boolean bMarked)
    {
        m_bHasState = true;
        synchronized(m_bsMarked)
        {
            m_bsMarked.set(nIndex, bMarked);
        }
    }

    void setMemo(int nIndex, String strMemo)
    {
        m_bHasState = true;
        if(strMemo == null || strMemo.length() == 0)
            m_hmMemo.remove(nIndex);
        else
            m_hmMemo.put(nIndex, strMemo);
    }
}
