import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * .gz 로그 파일 자동 풀기 (1.41).
 * 로그 목록은 파일 위치로 줄을 읽으므로 압축을 푼 실제 파일이 필요하다.
 * - 푼 파일 이름: abc.log.gz → abc.log, abc.gz → abc, 확장자 없는 gzip(logcat.10) → logcat.10 그대로 (같은 폴더, 쓸 수 없으면 임시 폴더의 KLogFilter_gz)
 * - 이미 푼 파일이 .gz보다 새것이면 다시 풀지 않고 그대로 쓴다. .gz가 바뀌면(더 새것) 다시 풀어 갱신한다.
 * - 여러 조각이 이어 붙은 gz(로그 회전으로 cat한 파일)도 끝까지 푼다.
 * - 1.42: 다 풀면 푼 파일이 원본이 된다(.gz 삭제, 수정 시각은 .gz와 같게). 임시 폴더에 푼 경우는 .gz를 남긴다.
 */
public class GzLog
{
    public interface Progress
    {
        /** 진행 알림. false를 돌려주면 중단한다. */
        boolean onProgress(long nRead, long nTotal, long nWritten);
    }

    /** 확장자가 .gz이거나 파일 앞 2바이트가 gzip 표식(1F 8B)이면 true */
    static boolean isGzip(File file)
    {
        if(file == null || !file.isFile()) return false;
        if(file.getName().toLowerCase().endsWith(".gz")) return true;
        try(InputStream in = new FileInputStream(file))
        {
            return in.read() == 0x1F && in.read() == 0x8B;
        }
        catch(IOException e)
        {
            return false;
        }
    }

    /** 푼 파일을 둘 위치 (같은 폴더 우선) */
    static File targetFor(File gz)
    {
        String strName = gz.getName();
        // abc.log.gz → abc.log, abc.gz → abc, logcat.10(확장자 없는 gzip) → 일단 logcat.10.unzipped로 풀고 replaceOriginal에서 logcat.10으로 바꾼다
        String strOut = strName.toLowerCase().endsWith(".gz") && strName.length() > 3 ? strName.substring(0, strName.length() - 3) : strName + ".unzipped";
        File dir = gz.getAbsoluteFile().getParentFile();
        if(dir == null || !dir.canWrite())
        {
            dir = new File(System.getProperty("java.io.tmpdir"), "KLogFilter_gz");
            dir.mkdirs();
        }
        return new File(dir, strOut);
    }

    /** 이미 푼 파일을 그대로 쓸 수 있는가 (.gz보다 새것이고 비어 있지 않음) */
    static boolean upToDate(File gz, File out)
    {
        return out.isFile() && out.length() > 0 && out.lastModified() >= gz.lastModified();
    }

    /**
     * 푼 파일을 원본으로 바꾼다 (1.42, 1.43): 푼 파일이 원본과 같은 폴더에 있고 비어 있지 않으면
     * 수정 시각을 원본과 같게 맞추고 원본(gzip)을 지운다.
     * 원본 이름이 .gz로 끝나지 않으면(예: logcat.10) 푼 파일(logcat.10.unzipped)을 원본 이름으로 바꾼다.
     * 최종 파일을 돌려주고, 바꾸지 못했으면(임시 폴더에 푼 경우, 지울 수 없음) null.
     */
    static File replaceOriginal(File gz, File out)
    {
        File gzAbs = gz.getAbsoluteFile();
        File gzDir = gzAbs.getParentFile(), outDir = out.getAbsoluteFile().getParentFile();
        if(gzDir == null || !gzDir.equals(outDir) || !out.isFile() || out.length() == 0) return null;
        long nTime = gzAbs.lastModified();
        boolean bKeepName = !gzAbs.getName().toLowerCase().endsWith(".gz");
        if(!gzAbs.delete()) return null;
        File result = out;
        if(bKeepName)
        {
            if(!out.renameTo(gzAbs)) return out;        // 이름은 못 바꿨지만 원본은 지워졌다: 푼 파일을 그대로 쓴다
            result = gzAbs;
        }
        result.setLastModified(nTime);
        return result;
    }

    /** 압축을 풀어 푼 파일을 돌려준다. 이미 최신이면 풀지 않는다. */
    static File extract(File gz, Progress progress) throws IOException
    {
        File out = targetFor(gz);
        // 1.42: 풀고 나면 .gz를 지우므로 기존 파일을 재사용하지 않고 항상 새로 푼다 (다른 내용의 같은 이름 파일 때문에 .gz 내용을 잃지 않도록)
        File tmp = new File(out.getPath() + ".part");
        final long nTotal = gz.length();
        final long[] nRead = { 0 };
        boolean bDone = false;
        try(InputStream raw = new FilterInputStream(new FileInputStream(gz))
            {
                public int read() throws IOException { int c = super.read(); if(c >= 0) nRead[0]++; return c; }
                public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if(n > 0) nRead[0] += n; return n; }
            };
            InputStream in = new GZIPInputStream(raw, 64 << 10);
            OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 256 << 10))
        {
            byte[] buf = new byte[256 << 10];
            long nWritten = 0, nLast = 0;
            int n;
            while((n = in.read(buf)) > 0)
            {
                os.write(buf, 0, n);
                nWritten += n;
                if(progress != null && nWritten - nLast >= (8 << 20))
                {
                    nLast = nWritten;
                    if(!progress.onProgress(nRead[0], nTotal, nWritten)) throw new IOException("중단됨");
                }
            }
            bDone = true;
        }
        finally
        {
            if(!bDone) tmp.delete();
        }
        if(out.exists() && !out.delete()) throw new IOException("기존 파일을 바꿀 수 없습니다 : " + out.getName());
        if(!tmp.renameTo(out)) throw new IOException("파일 이름을 바꿀 수 없습니다 : " + tmp.getName());
        return out;
    }
}
