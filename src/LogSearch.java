/**
 * 메시지 검색 (필터와 별개): 지금 보이는 목록(전체 줄 또는 필터 결과)에서 단어가 들어 있는 다음/이전 행을 찾는다.
 * 검색어는 필터와 같이 '|'로 나눈 OR 조건, 대소문자 무시, Message 열 기준.
 * 큰 목록도 화면이 멈추지 않도록 백그라운드 스레드에서 호출하고, cancel()로 중단할 수 있다.
 */
public class LogSearch
{
    static final int CHUNK_ROWS = 1 << 16;   // 진행률 확인·중단 단위

    public interface Progress
    {
        // 지금까지 살펴본 행 수 / 전체 행 수
        void onProgress(int nDone, int nTotal);
    }

    private volatile boolean m_bCancel;

    void cancel()
    {
        m_bCancel = true;
    }

    boolean isCancelled()
    {
        return m_bCancel;
    }

    static boolean matches(LogInfo logInfo, String[] arToken)
    {
        return FilterToken.matchAny(logInfo.m_strMessage, arToken);
    }

    /**
     * nStart 다음(앞으로) 또는 이전(뒤로) 행부터 찾는다. 끝에 닿으면 반대쪽 끝부터 nStart까지 이어서 찾는다.
     * @param nSize  검색할 행 수 (화면에 보이는 행 수. 실시간 수집 중에는 목록이 계속 늘어난다)
     * @param nStart 현재 행 (-1이면 앞으로는 처음부터, 뒤로는 끝부터)
     * @return 찾은 행과 처음으로 돌아갔는지, 못 찾았거나 중단하면 null
     */
    Result find(LogList list, int nSize, String[] arToken, int nStart, boolean bForward, Progress progress)
    {
        nSize = Math.min(nSize, list.size());
        if(nSize == 0 || arToken.length == 0)
            return null;
        if(nStart < -1 || nStart >= nSize)
            nStart = -1;
        int nTotal = nSize, nDone = 0;

        if(bForward)
        {
            // [nStart+1, 끝) → [0, nStart]
            int nFirst = nStart + 1;
            int nRow = scan(list, arToken, nFirst, nSize, true, progress, nDone, nTotal);
            if(nRow >= 0) return new Result(nRow, false);
            if(m_bCancel) return null;
            nDone += nSize - nFirst;
            nRow = scan(list, arToken, 0, Math.min(nFirst, nSize), true, progress, nDone, nTotal);
            return nRow >= 0 ? new Result(nRow, nFirst > 0) : null;
        }
        else
        {
            // [0, nStart) 거꾸로 → [nStart, 끝) 거꾸로
            int nEnd = nStart < 0 ? nSize : nStart;
            int nRow = scan(list, arToken, 0, nEnd, false, progress, nDone, nTotal);
            if(nRow >= 0) return new Result(nRow, false);
            if(m_bCancel) return null;
            nDone += nEnd;
            nRow = scan(list, arToken, nEnd, nSize, false, progress, nDone, nTotal);
            return nRow >= 0 ? new Result(nRow, nEnd < nSize) : null;
        }
    }

    static class Result
    {
        final int     m_nRow;
        final boolean m_bWrapped;   // 끝에서 반대쪽 끝으로 돌아가서 찾았는지

        Result(int nRow, boolean bWrapped)
        {
            m_nRow     = nRow;
            m_bWrapped = bWrapped;
        }
    }

    // [nFrom, nTo) 행에서 앞으로는 첫 번째, 뒤로는 마지막 일치 행. 없으면 -1.
    private int scan(LogList list, String[] arToken, int nFrom, int nTo, boolean bForward, Progress progress, int nDoneBase, int nTotal)
    {
        if(nFrom >= nTo) return -1;
        if(bForward)
        {
            for(int nChunk = nFrom; nChunk < nTo && !m_bCancel; nChunk += CHUNK_ROWS)
            {
                int nRow = scanChunk(list, arToken, nChunk, Math.min(nTo, nChunk + CHUNK_ROWS), true);
                if(nRow >= 0) return nRow;
                if(progress != null) progress.onProgress(nDoneBase + Math.min(nTo, nChunk + CHUNK_ROWS) - nFrom, nTotal);
            }
        }
        else
        {
            for(int nChunkEnd = nTo; nChunkEnd > nFrom && !m_bCancel; nChunkEnd -= CHUNK_ROWS)
            {
                int nChunk = Math.max(nFrom, nChunkEnd - CHUNK_ROWS);
                int nRow = scanChunk(list, arToken, nChunk, nChunkEnd, false);
                if(nRow >= 0) return nRow;
                if(progress != null) progress.onProgress(nDoneBase + nTo - nChunk, nTotal);
            }
        }
        return -1;
    }

    // 행 [nFrom, nTo) 한 덩어리. 앞으로면 첫 일치, 뒤로면 마지막 일치.
    private int scanChunk(final LogList list, final String[] arToken, final int nFrom, final int nTo, final boolean bForward)
    {
        final int[] nFound = { -1 };
        if(list instanceof LogStore)
        {
            // 전체 목록: 파일을 큰 블록으로 순차로 읽는다.
            ((LogStore)list).forEach(nFrom, nTo, new LogStore.Visitor()
            {
                public boolean visit(int nIndex, LogInfo logInfo)
                {
                    if(m_bCancel) return false;
                    if(matches(logInfo, arToken))
                    {
                        nFound[0] = nIndex;
                        return !bForward;          // 앞으로면 여기서 멈추고, 뒤로면 마지막 일치까지 계속
                    }
                    return true;
                }
            });
            return nFound[0];
        }

        if(list instanceof FilteredList)
        {
            final FilteredList filtered = (FilteredList)list;
            int nLineFrom = filtered.lineIndexOf(nFrom), nLineTo = filtered.lineIndexOf(nTo - 1) + 1;
            // 필터 결과가 원본에서 촘촘하면 원본 범위를 블록으로 읽고 통과한 줄만 본다. 듬성듬성하면 행마다 읽는다.
            if(nLineTo - nLineFrom <= (nTo - nFrom) * 8)
            {
                final int[] nRowPtr = { nFrom };
                filtered.m_store.forEach(nLineFrom, nLineTo, new LogStore.Visitor()
                {
                    public boolean visit(int nIndex, LogInfo logInfo)
                    {
                        if(m_bCancel) return false;
                        while(nRowPtr[0] < nTo && filtered.lineIndexOf(nRowPtr[0]) < nIndex)
                            nRowPtr[0]++;
                        if(nRowPtr[0] < nTo && filtered.lineIndexOf(nRowPtr[0]) == nIndex && matches(logInfo, arToken))
                        {
                            nFound[0] = nRowPtr[0];
                            return !bForward;
                        }
                        return true;
                    }
                });
                return nFound[0];
            }
        }

        // 그 밖: 행마다 읽는다.
        if(bForward)
        {
            for(int nRow = nFrom; nRow < nTo && !m_bCancel; nRow++)
                if(matches(list.get(nRow), arToken)) return nRow;
        }
        else
        {
            for(int nRow = nTo - 1; nRow >= nFrom && !m_bCancel; nRow--)
                if(matches(list.get(nRow), arToken)) return nRow;
        }
        return -1;
    }
}
