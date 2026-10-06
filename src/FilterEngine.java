import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로그 데이터(전체/필터 결과), 필터 조건, 재필터 스레드를 관리한다.
 *
 * 스레드 규칙
 * - 리스트/맵은 여러 스레드가 함께 쓴다. 추가·변경은 LOCK 안에서 하고, 비울 때는 clear() 대신 새 객체로 교체한다.
 * - 화면 반영은 직접 하지 않고 Listener.onDataChanged()로 알린다. (받는 쪽이 EDT에서 처리)
 */
public class FilterEngine
{
    public interface Listener
    {
        // 표시할 목록이 바뀌었다. nMode = REFRESH_* (어느 스레드에서든 호출될 수 있음)
        void onDataChanged(int nMode);
        void onStatus(String strText);
    }

    static final int STATUS_CHANGE       = 1;   // 조건이 바뀌어 진행 중인 재필터를 중단해야 함
    static final int STATUS_PARSING      = 2;   // 재필터 중
    static final int STATUS_READY        = 4;

    static final int REFRESH_KEEP        = 0;   // 선택 유지
    static final int REFRESH_FOLLOW_END  = 1;   // 마지막 행을 보고 있었다면 새 마지막 행으로 따라감
    static final int REFRESH_SELECT_LAST = 2;   // 마지막 행을 선택하고 스크롤

    final Object                    LOCK = new Object();
    final Listener                  m_listener;

    volatile ArrayList<LogInfo>     m_arLogInfoAll       = new ArrayList<LogInfo>();
    volatile ArrayList<LogInfo>     m_arLogInfoFiltered  = new ArrayList<LogInfo>();
    volatile Map<Integer, Integer>  m_hmBookmarkAll      = new ConcurrentHashMap<Integer, Integer>();
    volatile Map<Integer, Integer>  m_hmBookmarkFiltered = new ConcurrentHashMap<Integer, Integer>();
    volatile Map<Integer, Integer>  m_hmErrorAll         = new ConcurrentHashMap<Integer, Integer>();
    volatile Map<Integer, Integer>  m_hmErrorFiltered    = new ConcurrentHashMap<Integer, Integer>();

    volatile int                    m_nChangedFilter     = STATUS_READY;
    volatile boolean                m_bUserFilter;
    boolean                         m_bFilterRequested;  // LOCK 안에서만 접근
    Thread                          m_thFilter;

    // ---- 필터 조건 (문자열과, 소문자로 나눠 둔 토큰) ----
    volatile int                    m_nFilterLogLV       = LogInfo.LOG_LV_ALL;
    volatile boolean                m_bShowBookmarkOnly;
    volatile boolean                m_bShowErrorOnly;
    volatile String                 m_strFind            = "";
    volatile String                 m_strRemove          = "";
    volatile String                 m_strShowTag         = "";
    volatile String                 m_strRemoveTag       = "";
    volatile String                 m_strShowPid         = "";
    volatile String                 m_strShowTid         = "";
    volatile String[]               m_arFindToken        = FilterToken.EMPTY;
    volatile String[]               m_arRemoveToken      = FilterToken.EMPTY;
    volatile String[]               m_arShowTagToken     = FilterToken.EMPTY;
    volatile String[]               m_arRemoveTagToken   = FilterToken.EMPTY;
    volatile String[]               m_arShowPidToken     = FilterToken.EMPTY;
    volatile String[]               m_arShowTidToken     = FilterToken.EMPTY;

    public FilterEngine(Listener listener)
    {
        m_listener = listener;
    }

    // ---- 필터 조건 설정 (토큰을 먼저 만들고 문자열을 나중에 넣는다) ----
    void setFind(String str)      { m_arFindToken      = FilterToken.split(str); m_strFind      = nz(str); }
    void setRemove(String str)    { m_arRemoveToken    = FilterToken.split(str); m_strRemove    = nz(str); }
    void setShowTag(String str)   { m_arShowTagToken   = FilterToken.split(str); m_strShowTag   = nz(str); }
    void setRemoveTag(String str) { m_arRemoveTagToken = FilterToken.split(str); m_strRemoveTag = nz(str); }
    void setShowPid(String str)   { m_arShowPidToken   = FilterToken.split(str); m_strShowPid   = nz(str); }
    void setShowTid(String str)   { m_arShowTidToken   = FilterToken.split(str); m_strShowTid   = nz(str); }

    String getShowTag()   { return m_strShowTag; }
    String getRemoveTag() { return m_strRemoveTag; }

    String[] getFindTokens()    { return m_arFindToken; }
    String[] getShowTagTokens() { return m_arShowTagToken; }

    void setLogLevel(int nLogLV, boolean bChecked)
    {
        if(bChecked)
            m_nFilterLogLV |= nLogLV;
        else
            m_nFilterLogLV &= ~nLogLV;
    }

    void setShowBookmarkOnly(boolean b) { m_bShowBookmarkOnly = b; }
    void setShowErrorOnly(boolean b)    { m_bShowErrorOnly = b; }

    static String nz(String str) { return str == null ? "" : str; }

    // ---- 데이터 ----

    // 줄 번호를 (현재 개수 + 1)로 정해 추가한다. 번호와 목록 위치가 항상 맞는다.
    void addNext(LogInfo logInfo)
    {
        synchronized(LOCK)
        {
            logInfo.setLine(m_arLogInfoAll.size() + 1);
            m_arLogInfoAll.add(logInfo);
            if(logInfo.isError())
                m_hmErrorAll.put(logInfo.m_nLine - 1, logInfo.m_nLine - 1);

            if(m_bUserFilter)
                addIfAccepted(logInfo, m_arLogInfoFiltered, m_hmBookmarkFiltered, m_hmErrorFiltered);
        }
    }

    // 모든 로그를 지운다. 화면이 참조 중인 리스트를 clear()하지 않고 새 객체로 교체한다.
    void clearData()
    {
        synchronized(LOCK)
        {
            m_arLogInfoAll       = new ArrayList<LogInfo>();
            m_arLogInfoFiltered  = new ArrayList<LogInfo>();
            m_hmBookmarkAll      = new ConcurrentHashMap<Integer, Integer>();
            m_hmBookmarkFiltered = new ConcurrentHashMap<Integer, Integer>();
            m_hmErrorAll         = new ConcurrentHashMap<Integer, Integer>();
            m_hmErrorFiltered    = new ConcurrentHashMap<Integer, Integer>();
        }
        m_listener.onDataChanged(REFRESH_KEEP);
    }

    /**
     * @param nIndex 화면(표시 목록)의 행 번호
     * @param nLine  원본 목록의 위치 (m_nLine - 1)
     */
    void bookmarkItem(int nIndex, int nLine, boolean bBookmark)
    {
        synchronized(LOCK)
        {
            // 화면에 아직 이전 목록이 보이는 동안(clearData 직후) 클릭한 경우 범위를 벗어날 수 있다.
            if(nLine < 0 || nLine >= m_arLogInfoAll.size()) return;
            LogInfo logInfo = m_arLogInfoAll.get(nLine);
            logInfo.m_bMarked = bBookmark;

            if(logInfo.m_bMarked)
            {
                m_hmBookmarkAll.put(nLine, nLine);
                if(m_bUserFilter)
                    m_hmBookmarkFiltered.put(nLine, nIndex);
            }
            else
            {
                m_hmBookmarkAll.remove(nLine);
                if(m_bUserFilter)
                    m_hmBookmarkFiltered.remove(nLine);
            }
        }
    }

    void setBookmark(int nLine, String strBookmark)
    {
        ArrayList<LogInfo> arAll = m_arLogInfoAll;
        if(nLine < 0 || nLine >= arAll.size()) return;
        arAll.get(nLine).m_strBookmark = strBookmark;
    }

    // 지금 화면에 보여야 할 목록과 그 북마크/에러 위치. (필터 사용 여부를 한 번만 읽어 셋이 서로 맞도록)
    static class View
    {
        final ArrayList<LogInfo>    arList;
        final Map<Integer, Integer> hmBookmark;
        final Map<Integer, Integer> hmError;

        View(ArrayList<LogInfo> arList, Map<Integer, Integer> hmBookmark, Map<Integer, Integer> hmError)
        {
            this.arList = arList; this.hmBookmark = hmBookmark; this.hmError = hmError;
        }
    }

    // EDT에서 호출된다. 재필터 중 LOCK을 오래 잡고 있으므로 LOCK 없이 volatile 필드만 읽는다.
    // (교체 순간 목록과 맵이 잠깐 어긋날 수 있지만, 교체 직후 onDataChanged로 다시 반영된다)
    View getView()
    {
        if(m_bUserFilter)
            return new View(m_arLogInfoFiltered, m_hmBookmarkFiltered, m_hmErrorFiltered);
        return new View(m_arLogInfoAll, m_hmBookmarkAll, m_hmErrorAll);
    }

    // 실시간 수집으로 줄이 추가됐음을 화면에 알린다.
    void notifyAppended()
    {
        m_listener.onDataChanged(REFRESH_FOLLOW_END);
    }

    // 재필터 중이면 true (실시간 수집은 이 동안 잠시 쉰다)
    boolean isBusy()
    {
        int nStatus = m_nChangedFilter;
        return nStatus == STATUS_CHANGE || nStatus == STATUS_PARSING;
    }

    // ---- 필터 판정 ----

    boolean checkLogLVFilter(LogInfo logInfo)
    {
        int nFilter = m_nFilterLogLV;
        if(nFilter == LogInfo.LOG_LV_ALL)
            return true;
        return (nFilter & logInfo.m_nLogLV) != 0;   // 레벨이 없는 줄(LOG_LV_NONE)은 모든 레벨이 선택된 경우에만 보임
    }

    // Show 계열: 토큰이 없으면 통과, 있으면 하나라도 포함돼야 통과
    static boolean matchShow(String strField, String[] arToken)
    {
        return arToken.length == 0 || FilterToken.matchAny(strField, arToken);
    }

    // Remove 계열: 토큰이 없으면 통과, 하나라도 포함되면 제외
    static boolean matchRemove(String strField, String[] arToken)
    {
        return arToken.length == 0 || !FilterToken.matchAny(strField, arToken);
    }

    boolean accept(LogInfo logInfo)
    {
        if(m_bShowBookmarkOnly || m_bShowErrorOnly)
            return (logInfo.m_bMarked && m_bShowBookmarkOnly) || (logInfo.isError() && m_bShowErrorOnly);

        return checkLogLVFilter(logInfo)
            && matchShow(logInfo.m_strPid, m_arShowPidToken)
            && matchShow(logInfo.m_strThread, m_arShowTidToken)
            && matchShow(logInfo.m_strTag, m_arShowTagToken)
            && matchRemove(logInfo.m_strTag, m_arRemoveTagToken)
            && matchShow(logInfo.m_strMessage, m_arFindToken)
            && matchRemove(logInfo.m_strMessage, m_arRemoveToken);
    }

    // 필터를 통과하면 arFiltered에 추가하고 북마크/에러 위치를 기록한다. (addNext와 재필터가 함께 사용)
    void addIfAccepted(LogInfo logInfo, ArrayList<LogInfo> arFiltered, Map<Integer, Integer> hmBookmark, Map<Integer, Integer> hmError)
    {
        if(!accept(logInfo)) return;

        int nPos = arFiltered.size();   // 추가되기 전 크기 = 이 줄이 표시될 행 번호
        if(logInfo.m_bMarked) hmBookmark.put(logInfo.m_nLine - 1, nPos);
        if(logInfo.isError()) hmError.put(logInfo.m_nLine - 1, nPos);
        arFiltered.add(logInfo);
    }

    // 필터 조건이 하나라도 켜져 있으면 true (하이라이트는 표시만 바꾸므로 제외)
    boolean checkUseFilter()
    {
        m_bUserFilter = m_bShowBookmarkOnly || m_bShowErrorOnly
                     || m_nFilterLogLV != LogInfo.LOG_LV_ALL
                     || m_strShowPid.length() > 0 || m_strShowTid.length() > 0
                     || m_strShowTag.length() > 0 || m_strRemoveTag.length() > 0
                     || m_strFind.length() > 0    || m_strRemove.length() > 0;
        return m_bUserFilter;
    }

    // ---- 재필터 ----

    // 조건이 바뀌었음을 표시해 진행 중인 재필터를 바로 멈추게 한다. (새 재필터는 requestFilter()로)
    void markChanged()
    {
        m_nChangedFilter = STATUS_CHANGE;
    }

    // 재필터를 요청한다. 진행 중인 재필터는 중단되고, 요청 플래그가 남아 있으므로 반드시 다시 실행된다.
    void requestFilter()
    {
        checkUseFilter();
        m_nChangedFilter = STATUS_CHANGE;
        synchronized(LOCK)
        {
            m_bFilterRequested = true;
            LOCK.notify();
        }
    }

    void start()
    {
        m_thFilter = new Thread(new Runnable()
        {
            public void run()
            {
                try
                {
                    while(true)
                    {
                        synchronized(LOCK)
                        {
                            m_nChangedFilter = STATUS_READY;
                            while(!m_bFilterRequested)
                                LOCK.wait();
                            m_bFilterRequested = false;
                            m_nChangedFilter = STATUS_PARSING;

                            if(!m_bUserFilter)
                            {
                                m_nChangedFilter = STATUS_READY;
                                m_listener.onDataChanged(REFRESH_SELECT_LAST);
                                continue;
                            }
                            m_listener.onStatus("Parsing");

                            // 새 리스트에 채운다. 완료될 때까지 화면은 이전 결과를 그대로 보여준다.
                            ArrayList<LogInfo>    arAll      = m_arLogInfoAll;
                            ArrayList<LogInfo>    arFiltered = new ArrayList<LogInfo>();
                            Map<Integer, Integer> hmBookmark = new ConcurrentHashMap<Integer, Integer>();
                            Map<Integer, Integer> hmError    = new ConcurrentHashMap<Integer, Integer>();

                            int nRowCount = arAll.size();
                            for(int nIndex = 0; nIndex < nRowCount; nIndex++)
                            {
                                if(m_nChangedFilter == STATUS_CHANGE)
                                    break;
                                addIfAccepted(arAll.get(nIndex), arFiltered, hmBookmark, hmError);
                            }
                            if(m_nChangedFilter == STATUS_PARSING)
                            {
                                m_arLogInfoFiltered  = arFiltered;
                                m_hmBookmarkFiltered = hmBookmark;
                                m_hmErrorFiltered    = hmError;
                                m_nChangedFilter     = STATUS_READY;
                                m_listener.onDataChanged(REFRESH_SELECT_LAST);
                                m_listener.onStatus("Complete");
                            }
                        }
                    }
                }
                catch(InterruptedException e)
                {
                    // 종료
                }
                catch(Exception e)
                {
                    T.e(e);
                    e.printStackTrace();
                }
                System.out.println("End FilterEngine thread");
            }
        }, "FilterEngine");
        m_thFilter.start();
    }

    void stop()
    {
        if(m_thFilter != null) m_thFilter.interrupt();
    }
}
