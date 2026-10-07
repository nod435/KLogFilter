import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 로그 데이터(전체 목록 LogStore와 필터 결과 FilteredList), 필터 조건, 분석·재필터 스레드를 관리한다.
 *
 * 처리 방식
 * - 전체 목록(LogStore)은 줄 위치만 가지고 있고, 줄 내용은 필요할 때 해석한다.
 * - 분석(에러 위치 계산)과 필터 판정은 이 클래스의 스레드 하나가 순차로 처리한다(필터 결과·에러 맵을 쓰는 쪽은 이 스레드뿐).
 *   - requestAnalysis(): 새로 색인된 줄만 이어서 처리 (파일 로딩 중, 실시간 수집)
 *   - requestFilter()  : 조건이 바뀌어 처음부터 다시 판정 (새 목록을 채우면서 바로 화면에 보임)
 * - 화면 반영은 직접 하지 않고 Listener.onDataChanged()로 알린다(받는 쪽이 EDT에서 처리).
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

    static final long PUBLISH_INTERVAL_MS = 300; // 긴 작업 중 화면에 중간 결과를 알리는 간격
    static final int  PROGRESS_MIN_LINES  = 200000; // 이보다 많은 줄을 처리할 때만 진행률을 표시

    final Object                    LOCK = new Object();
    final Listener                  m_listener;

    volatile LogStore               m_store;
    volatile FilteredList           m_filtered;          // 필터 결과 (필터를 쓰지 않으면 null)
    volatile Map<Integer, Integer>  m_hmBookmarkAll      = new ConcurrentHashMap<Integer, Integer>();
    volatile Map<Integer, Integer>  m_hmBookmarkFiltered = new ConcurrentHashMap<Integer, Integer>();
    volatile Map<Integer, Integer>  m_hmErrorAll         = new ConcurrentHashMap<Integer, Integer>();
    volatile Map<Integer, Integer>  m_hmErrorFiltered    = new ConcurrentHashMap<Integer, Integer>();
    volatile int                    m_nAnalyzed;         // 전체 목록에서 에러 위치를 계산한 줄 수
    volatile int                    m_nFilteredUpTo;     // m_filtered가 판정을 마친 줄 수
    volatile boolean                m_bReadyReported;    // 현재 목록에 대해 Ready를 알렸는지

    volatile int                    m_nChangedFilter     = STATUS_READY;
    volatile boolean                m_bUserFilter;
    boolean                         m_bFilterRequested;  // LOCK 안에서만 접근
    boolean                         m_bAnalyzeRequested; // LOCK 안에서만 접근
    int                             m_nAnalyzeMode;      // LOCK 안에서만 접근
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
        m_store    = new MemoryLogStore(new LogCatParser());
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

    LogStore getStore()
    {
        return m_store;
    }

    // 전체 목록을 바꾼다(파일 열기, 실시간 수집 시작, Clear). 이전 목록의 분석·필터 결과는 버린다.
    void setStore(LogStore store)
    {
        LogStore old;
        synchronized(LOCK)
        {
            old = m_store;
            m_store              = store;
            m_hmBookmarkAll      = new ConcurrentHashMap<Integer, Integer>();
            m_hmBookmarkFiltered = new ConcurrentHashMap<Integer, Integer>();
            m_hmErrorAll         = new ConcurrentHashMap<Integer, Integer>();
            m_hmErrorFiltered    = new ConcurrentHashMap<Integer, Integer>();
            m_filtered           = m_bUserFilter ? new FilteredList(store) : null;
            m_nAnalyzed          = 0;
            m_nFilteredUpTo      = 0;
            m_bReadyReported     = false;
        }
        if(old != null && old != store)
            old.close();
        m_listener.onDataChanged(REFRESH_KEEP);
        requestAnalysis(REFRESH_KEEP);
    }

    // 모든 로그를 지운다. (실시간 수집 중이면 같은 파일의 지금 이후 부분만 보게 된다)
    void clearData()
    {
        setStore(m_store.cleared());
    }

    /**
     * @param nIndex 화면(표시 목록)의 행 번호
     * @param nLine  원본 위치 (m_nLine - 1)
     */
    void bookmarkItem(int nIndex, int nLine, boolean bBookmark)
    {
        LogStore store = m_store;
        // 화면에 아직 이전 목록이 보이는 동안 클릭한 경우 범위를 벗어날 수 있다.
        if(nLine < 0 || nLine >= store.size()) return;
        store.setMarked(nLine, bBookmark);
        if(bBookmark)
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

    void setBookmark(int nLine, String strBookmark)
    {
        LogStore store = m_store;
        if(nLine < 0 || nLine >= store.size()) return;
        store.setMemo(nLine, strBookmark);
    }

    // 지금 화면에 보여야 할 목록과 그 북마크/에러 위치
    static class View
    {
        final LogList               arList;
        final Map<Integer, Integer> hmBookmark;
        final Map<Integer, Integer> hmError;

        View(LogList arList, Map<Integer, Integer> hmBookmark, Map<Integer, Integer> hmError)
        {
            this.arList = arList; this.hmBookmark = hmBookmark; this.hmError = hmError;
        }
    }

    // EDT에서 호출된다. 긴 작업 중에도 멈추지 않도록 LOCK 없이 volatile 필드만 읽는다.
    View getView()
    {
        FilteredList filtered = m_filtered;
        if(m_bUserFilter && filtered != null)
            return new View(filtered, m_hmBookmarkFiltered, m_hmErrorFiltered);
        return new View(m_store, m_hmBookmarkAll, m_hmErrorAll);
    }

    // 재필터 중이면 true
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

    // ---- 요청 ----

    // 조건이 바뀌었음을 표시해 진행 중인 재필터를 바로 멈추게 한다. (새 재필터는 requestFilter()로)
    void markChanged()
    {
        m_nChangedFilter = STATUS_CHANGE;
    }

    // 조건이 바뀌어 처음부터 다시 판정한다. 진행 중인 판정은 중단된다.
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

    // 새로 색인된 줄을 이어서 분석·판정한다. 끝나면 nMode로 화면에 알린다.
    void requestAnalysis(int nMode)
    {
        synchronized(LOCK)
        {
            m_bAnalyzeRequested = true;
            if(nMode > m_nAnalyzeMode) m_nAnalyzeMode = nMode;
            LOCK.notify();
        }
    }

    // 파일 로딩 중 줄이 색인됐다: 색인된 줄은 바로 화면에 보이고, 분석·필터 판정은 뒤이어 한다.
    void notifyIndexed()
    {
        m_listener.onDataChanged(REFRESH_KEEP);
        requestAnalysis(REFRESH_KEEP);
    }

    // 실시간 수집으로 줄이 색인됐다: 이어서 분석하고 마지막 행을 따라가게 한다.
    void notifyAppended()
    {
        requestAnalysis(REFRESH_FOLLOW_END);
    }

    // ---- 분석·재필터 스레드 ----

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
                        boolean bFull;
                        int     nMode;
                        synchronized(LOCK)
                        {
                            if(!m_bFilterRequested && !m_bAnalyzeRequested)
                                m_nChangedFilter = STATUS_READY;
                            while(!m_bFilterRequested && !m_bAnalyzeRequested)
                                LOCK.wait();
                            bFull = m_bFilterRequested;
                            nMode = m_nAnalyzeMode;
                            m_bFilterRequested  = false;
                            m_bAnalyzeRequested = false;
                            m_nAnalyzeMode      = REFRESH_KEEP;
                            if(bFull)
                                m_nChangedFilter = STATUS_PARSING;
                        }
                        if(bFull)
                            runFullFilter();
                        else
                            runAnalysis(nMode);
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

    // 조건이 바뀌었을 때: 새 필터 결과를 처음부터 채운다. 채우는 동안에도 화면에 보인다.
    private void runFullFilter()
    {
        final LogStore store = m_store;
        if(!m_bUserFilter)
        {
            synchronized(LOCK)
            {
                if(store == m_store)
                {
                    m_filtered           = null;
                    m_hmBookmarkFiltered = new ConcurrentHashMap<Integer, Integer>();
                    m_hmErrorFiltered    = new ConcurrentHashMap<Integer, Integer>();
                    m_nFilteredUpTo      = 0;
                }
            }
            m_listener.onDataChanged(REFRESH_SELECT_LAST);
            runAnalysis(REFRESH_KEEP);     // 아직 분석하지 않은 줄이 있으면 이어서
            return;
        }

        FilteredList list = new FilteredList(store);
        synchronized(LOCK)
        {
            if(store != m_store) return;
            m_filtered           = list;
            m_hmBookmarkFiltered = new ConcurrentHashMap<Integer, Integer>();
            m_hmErrorFiltered    = new ConcurrentHashMap<Integer, Integer>();
            m_nFilteredUpTo      = 0;
        }
        m_listener.onDataChanged(REFRESH_KEEP);
        if(process(store, list, 0, true, "Filtering") && store == m_store)
        {
            m_listener.onDataChanged(REFRESH_SELECT_LAST);
            m_listener.onStatus("Complete");
        }
    }

    // 새로 색인된 줄을 이어서 처리한다.
    private void runAnalysis(int nMode)
    {
        LogStore store = m_store;
        FilteredList list = m_bUserFilter ? m_filtered : null;
        int nFrom = list != null ? Math.min(m_nAnalyzed, m_nFilteredUpTo) : m_nAnalyzed;
        int nTo   = store.size();
        if(nFrom < nTo)
            process(store, list, nFrom, false, "Analyzing");
        // 파일을 다 읽었고 분석도 끝까지 했으면 알린다 (로딩 중 중간 결과에는 Ready를 띄우지 않음)
        if(store == m_store && store.m_bComplete && !m_bReadyReported && m_nAnalyzed >= store.size() && store.size() >= PROGRESS_MIN_LINES)
        {
            m_bReadyReported = true;
            m_listener.onStatus(String.format("Ready : %,d lines", store.size()));
        }
        if(store == m_store)
            m_listener.onDataChanged(nMode);
    }

    /**
     * store의 [nFrom, 현재 크기) 줄을 순차로 해석해 에러 위치를 계산하고, list가 있으면 필터 판정해 추가한다.
     * 조건이 바뀌거나(STATUS_CHANGE) 목록이 교체되면 중단한다. 긴 작업이면 중간 결과와 진행률을 알린다.
     * @return 끝까지 처리했으면 true
     */
    private boolean process(final LogStore store, final FilteredList list, int nFrom, final boolean bFull, final String strWork)
    {
        // 많은 줄은 여러 코어로 나눠 처리하고, 실시간 수집처럼 조금씩 늘어나는 경우는 바로 순차 처리한다.
        if(store.size() - nFrom >= CHUNK_LINES * 2 && WORKERS > 1)
            return processParallel(store, list, nFrom, bFull, strWork);

        final int nTo       = store.size();
        final int nAnalyzed = m_nAnalyzed;
        final int nFiltered = list == null ? Integer.MAX_VALUE : (bFull ? 0 : m_nFilteredUpTo);
        final Map<Integer, Integer> hmErrorAll  = m_hmErrorAll;
        final Map<Integer, Integer> hmBookmarkF = m_hmBookmarkFiltered;
        final Map<Integer, Integer> hmErrorF    = m_hmErrorFiltered;
        // 진행률은 재필터이거나, 다 읽은 파일을 분석할 때만 (로딩 중에는 LogSource가 Loading %를 표시)
        final boolean bProgress = nTo - nFrom >= PROGRESS_MIN_LINES && (bFull || store.m_bComplete);
        final long[]  nLastPublish = { System.currentTimeMillis() };
        final int[]   nReached = { nFrom };

        store.forEach(nFrom, nTo, new LogStore.Visitor()
        {
            public boolean visit(int i, LogInfo logInfo)
            {
                if(store != m_store || m_nChangedFilter == STATUS_CHANGE)
                    return false;
                if(i >= nAnalyzed && logInfo.isError())
                    hmErrorAll.put(i, i);
                if(list != null && i >= nFiltered && accept(logInfo))
                {
                    int nPos = list.size();      // 추가되기 전 크기 = 이 줄이 표시될 행 번호
                    if(logInfo.m_bMarked) hmBookmarkF.put(i, nPos);
                    if(logInfo.isError()) hmErrorF.put(i, nPos);
                    list.add(i);
                }
                nReached[0] = i + 1;

                long nNow = System.currentTimeMillis();
                if(nNow - nLastPublish[0] >= PUBLISH_INTERVAL_MS)
                {
                    nLastPublish[0] = nNow;
                    commit(store, list, nReached[0]);
                    m_listener.onDataChanged(REFRESH_KEEP);
                    if(bProgress)
                        m_listener.onStatus(strWork + " " + (int)((long)(i + 1) * 100 / nTo) + "%");
                }
                return true;
            }
        });
        commit(store, list, nReached[0]);
        return nReached[0] >= nTo;
    }

    // ---- 병렬 처리 ----

    static final int CHUNK_LINES = 1 << 16;    // 한 작업 단위(줄 수)
    static final int WORKERS     = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

    // 해석·판정을 나눠 맡는 작업 스레드 (데몬: 앱 종료를 막지 않음)
    private static final ExecutorService POOL = Executors.newFixedThreadPool(WORKERS, new ThreadFactory()
    {
        private final AtomicInteger m_nCount = new AtomicInteger();
        public Thread newThread(Runnable runnable)
        {
            Thread th = new Thread(runnable, "FilterWorker-" + m_nCount.incrementAndGet());
            th.setDaemon(true);
            // 코어를 거의 다 쓰므로, 화면(EDT)이 밀리지 않도록 우선순위를 낮춘다.
            th.setPriority(Thread.NORM_PRIORITY - 2);
            return th;
        }
    });

    // 한 작업 단위의 결과: 에러 줄, 필터를 통과한 줄과 그 줄의 북마크/에러 여부
    private static class ChunkResult
    {
        final IntList errors   = new IntList(64);
        final IntList accepted = new IntList(256);
        final IntList flags    = new IntList(256);   // accepted와 같은 순서. bit0 = 북마크, bit1 = 에러
        int nEnd;                                    // 이 작업의 끝 (줄 위치, 포함하지 않음)
        int nReached;                                // 여기까지 처리함 (중단되면 nEnd보다 작음)
    }

    /**
     * [nFrom, 현재 크기)를 CHUNK_LINES씩 나눠 작업 스레드들이 동시에 해석·판정하고,
     * 결과는 이 스레드가 순서대로 합친다. 그래서 필터 결과·에러 위치는 순차 처리와 똑같고, 합치는 동안에도 화면에 보인다.
     */
    private boolean processParallel(final LogStore store, final FilteredList list, int nFrom, boolean bFull, String strWork)
    {
        final int nTo       = store.size();
        final int nAnalyzed = m_nAnalyzed;
        final int nFiltered = list == null ? Integer.MAX_VALUE : (bFull ? 0 : m_nFilteredUpTo);
        Map<Integer, Integer> hmErrorAll  = m_hmErrorAll;
        Map<Integer, Integer> hmBookmarkF = m_hmBookmarkFiltered;
        Map<Integer, Integer> hmErrorF    = m_hmErrorFiltered;
        boolean bProgress = nTo - nFrom >= PROGRESS_MIN_LINES && (bFull || store.m_bComplete);
        long    nLastPublish = System.currentTimeMillis();

        ArrayDeque<Future<ChunkResult>> window = new ArrayDeque<Future<ChunkResult>>();
        int nNext = nFrom;
        int nReached = nFrom;
        try
        {
            while(true)
            {
                // 동시에 처리할 작업을 채운다 (결과가 쌓여 메모리를 쓰지 않도록 작업 수를 제한)
                while(nNext < nTo && window.size() < WORKERS * 2)
                {
                    final int nChunkFrom = nNext;
                    final int nChunkTo   = Math.min(nTo, nNext + CHUNK_LINES);
                    nNext = nChunkTo;
                    window.add(POOL.submit(new Callable<ChunkResult>()
                    {
                        public ChunkResult call()
                        {
                            final ChunkResult result = new ChunkResult();
                            result.nEnd     = nChunkTo;
                            result.nReached = nChunkFrom;
                            store.forEach(nChunkFrom, nChunkTo, new LogStore.Visitor()
                            {
                                public boolean visit(int i, LogInfo logInfo)
                                {
                                    if(store != m_store || m_nChangedFilter == STATUS_CHANGE)
                                        return false;
                                    if(i >= nAnalyzed && logInfo.isError())
                                        result.errors.add(i);
                                    if(list != null && i >= nFiltered && accept(logInfo))
                                    {
                                        result.accepted.add(i);
                                        result.flags.add((logInfo.m_bMarked ? 1 : 0) | (logInfo.isError() ? 2 : 0));
                                    }
                                    result.nReached = i + 1;
                                    return true;
                                }
                            });
                            return result;
                        }
                    }));
                }
                if(window.isEmpty())
                    break;

                // 가장 앞 작업의 결과를 순서대로 합친다
                ChunkResult result = window.poll().get();
                for(int k = 0; k < result.errors.size(); k++)
                {
                    int i = result.errors.get(k);
                    hmErrorAll.put(i, i);
                }
                for(int k = 0; k < result.accepted.size(); k++)
                {
                    int i = result.accepted.get(k), nFlag = result.flags.get(k);
                    int nPos = list.size();
                    if((nFlag & 1) != 0) hmBookmarkF.put(i, nPos);
                    if((nFlag & 2) != 0) hmErrorF.put(i, nPos);
                    list.add(i);
                }
                nReached = result.nReached;
                // 작업이 도중에 멈췄으면(조건 변경·목록 교체) 그 뒤 결과는 합치지 않는다. 처리한 앞부분까지만 기록된다.
                if(result.nReached < result.nEnd || store != m_store || m_nChangedFilter == STATUS_CHANGE)
                    break;

                long nNow = System.currentTimeMillis();
                if(nNow - nLastPublish >= PUBLISH_INTERVAL_MS)
                {
                    nLastPublish = nNow;
                    commit(store, list, nReached);
                    m_listener.onDataChanged(REFRESH_KEEP);
                    if(bProgress)
                        m_listener.onStatus(strWork + " " + (int)((long)nReached * 100 / nTo) + "%");
                }
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch(ExecutionException e)
        {
            T.e(e);
        }
        finally
        {
            // 인터럽트하지 않는다: FileChannel은 읽는 중 인터럽트되면 닫혀서 다른 스레드의 읽기까지 실패한다.
            // 작업은 조건 변경·목록 교체를 스스로 확인해 멈춘다.
            for(Future<ChunkResult> f : window)
                f.cancel(false);
        }
        commit(store, list, nReached);
        return nReached >= nTo;
    }

    // 처리한 줄 수를 기록한다. 목록이 그 사이 교체됐으면 기록하지 않는다.
    private void commit(LogStore store, FilteredList list, int nReached)
    {
        synchronized(LOCK)
        {
            if(store != m_store) return;
            if(nReached > m_nAnalyzed)
                m_nAnalyzed = nReached;
            if(list != null && list == m_filtered && nReached > m_nFilteredUpTo)
                m_nFilteredUpTo = nReached;
        }
    }
}
