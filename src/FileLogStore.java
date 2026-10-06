import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.StandardOpenOption;

/**
 * 파일 기반 줄 목록: 줄 내용은 메모리에 두지 않고 파일 안의 위치(시작 오프셋, 길이)만 기억한다.
 * (줄당 12바이트. 400MB·420만 줄 파일도 약 50MB)
 *
 * - 색인(indexNext): 바이트에서 '\n'만 찾아 줄 위치를 기록한다. 해석하지 않으므로 빠르고, 읽은 만큼 바로 화면에 보일 수 있다.
 * - 표시(get): 필요한 줄만 파일에서 읽어 해석한다(최근 줄은 캐시).
 * - 분석·필터(forEach): 큰 블록 단위로 순차로 읽는다.
 * - 실시간 수집 중에는 파일이 계속 늘어나므로, '\n'으로 끝난 줄까지만 색인하고 나머지는 다음에 읽는다.
 */
public class FileLogStore extends LogStore
{
    static final int READ_BUFFER = 4 << 20;     // 4MB씩 읽는다
    static final int MAX_LINE    = 64 << 20;    // 한 줄이 이보다 길면 잘라서 여러 줄로

    final File      m_file;
    final Charset   m_charset;
    private final LongList m_offsets = new LongList(1 << 16);
    private final IntList  m_lengths = new IntList(1 << 16);
    private volatile long  m_nIndexedEnd;       // 여기까지 색인함(다음 색인을 시작할 위치)
    private FileChannel    m_channel;
    private volatile boolean m_bClosed;         // close() 후에는 다시 열지 않는다 (교체된 목록)
    private byte[]         m_indexBuffer;       // 색인 스레드 전용

    public FileLogStore(File file, Charset charset, ILogParser parser, long nStartOffset)
    {
        super(parser);
        m_file        = file;
        m_charset     = charset;
        m_nIndexedEnd = nStartOffset;
    }

    public int size()
    {
        return m_offsets.size();
    }

    long indexedEnd()
    {
        return m_nIndexedEnd;
    }

    private synchronized FileChannel channel() throws IOException
    {
        if(m_bClosed) throw new ClosedChannelException();
        if(m_channel == null || !m_channel.isOpen())
            m_channel = FileChannel.open(m_file.toPath(), StandardOpenOption.READ);
        return m_channel;
    }

    synchronized void close()
    {
        m_bClosed = true;
        try
        {
            if(m_channel != null) m_channel.close();
        }
        catch(IOException e)
        {
            T.e(e);
        }
        m_channel = null;
    }

    LogStore cleared()
    {
        return new FileLogStore(m_file, m_charset, m_parser, m_nIndexedEnd);
    }

    // nPos부터 nLen바이트를 buf에 채운다. 실제로 읽은 바이트 수를 돌려준다.
    private static int read(FileChannel channel, long nPos, byte[] buf, int nLen) throws IOException
    {
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, nLen);
        int nTotal = 0;
        while(nTotal < nLen)
        {
            int n = channel.read(bb, nPos + nTotal);
            if(n < 0) break;
            nTotal += n;
        }
        return nTotal;
    }

    // 다른 스레드가 읽다가 인터럽트되어 채널이 닫혔으면 다시 열어 한 번 더 읽는다.
    private int readAt(long nPos, byte[] buf, int nLen) throws IOException
    {
        try
        {
            return read(channel(), nPos, buf, nLen);
        }
        catch(ClosedChannelException e)
        {
            if(m_bClosed) throw e;
            return read(channel(), nPos, buf, nLen);
        }
    }

    /**
     * 아직 색인하지 않은 부분을 최대 한 버퍼만큼 읽어 줄 위치를 기록한다. 색인 스레드 하나만 호출한다.
     * @param bIncludeTail true면 파일 끝의 '\n' 없는 마지막 줄도 포함(다 쓴 파일), false면 다음에 읽음(쓰는 중인 파일)
     * @return 새로 추가한 줄 수(빈 줄만 있었으면 0), 지금 더 읽을 것이 없으면 -1
     */
    int indexNext(boolean bIncludeTail) throws IOException
    {
        FileChannel channel = channel();
        long nFileLen = channel.size();
        long nPos     = m_nIndexedEnd;
        if(nPos >= nFileLen) return -1;

        if(m_indexBuffer == null) m_indexBuffer = new byte[READ_BUFFER];
        byte[] buf = m_indexBuffer;
        int nRead = read(channel, nPos, buf, (int)Math.min(buf.length, nFileLen - nPos));
        if(nRead <= 0) return -1;
        boolean bAtEof = nPos + nRead >= nFileLen;

        int nAdded = 0;
        int nLineStart = 0;
        for(int i = 0; i < nRead; i++)
        {
            if(buf[i] == '\n')
            {
                nAdded += addLine(buf, nPos, nLineStart, i);
                nLineStart = i + 1;
            }
        }

        if(nLineStart == 0 && nRead == buf.length)
        {
            // 버퍼보다 긴 줄: 버퍼를 키워 다시 읽는다. 한계를 넘으면 잘라서 한 줄로 넣는다.
            if(buf.length < MAX_LINE)
            {
                m_indexBuffer = new byte[buf.length * 2];
                return 0;
            }
            nAdded += addLine(buf, nPos, 0, nRead);
            nLineStart = nRead;
        }
        else if(bAtEof && bIncludeTail && nLineStart < nRead)
        {
            nAdded += addLine(buf, nPos, nLineStart, nRead);
            nLineStart = nRead;
        }

        if(nLineStart == 0)
            return -1;      // '\n'으로 끝난 줄이 아직 없음 (쓰는 중인 마지막 줄)
        m_nIndexedEnd = nPos + nLineStart;
        return nAdded;
    }

    // buf[nStart, nEnd)를 한 줄로 기록한다. 끝의 '\r'은 빼고, 공백뿐인 줄은 건너뛴다(이전 동작과 같음).
    private int addLine(byte[] buf, long nBase, int nStart, int nEnd)
    {
        if(nEnd > nStart && buf[nEnd - 1] == '\r')
            nEnd--;
        boolean bBlank = true;
        for(int i = nStart; i < nEnd; i++)
        {
            if((buf[i] & 0xFF) > ' ')
            {
                bBlank = false;
                break;
            }
        }
        if(bBlank) return 0;
        m_offsets.add(nBase + nStart);
        m_lengths.add(nEnd - nStart);
        return 1;
    }

    protected String readLine(int nIndex)
    {
        long nOffset = m_offsets.get(nIndex);
        int  nLength = m_lengths.get(nIndex);
        try
        {
            byte[] buf = new byte[nLength];
            int nRead = readAt(nOffset, buf, nLength);
            return new String(buf, 0, nRead, m_charset);
        }
        catch(IOException e)
        {
            if(!m_bClosed) T.e(e);
            return "";
        }
    }

    // 큰 블록 단위로 순차로 읽어 해석한다. (분석·필터용, 캐시에 넣지 않음)
    void forEach(int nFrom, int nTo, Visitor visitor)
    {
        byte[] buf = new byte[READ_BUFFER];
        long nBufStart = -1;
        int  nBufLen   = 0;
        try
        {
            for(int i = nFrom; i < nTo; i++)
            {
                long nOffset = m_offsets.get(i);
                int  nLength = m_lengths.get(i);
                if(nBufStart < 0 || nOffset < nBufStart || nOffset + nLength > nBufStart + nBufLen)
                {
                    if(nLength > buf.length) buf = new byte[nLength];
                    nBufStart = nOffset;
                    nBufLen   = readAt(nOffset, buf, (int)Math.min(buf.length, channel().size() - nOffset));
                }
                String strLine = new String(buf, (int)(nOffset - nBufStart), Math.min(nLength, nBufLen - (int)(nOffset - nBufStart)), m_charset);
                LogInfo logInfo = parse(i, strLine);
                applyState(i, logInfo);
                if(!visitor.visit(i, logInfo))
                    return;
            }
        }
        catch(IOException e)
        {
            if(!m_bClosed) T.e(e);
        }
    }
}
