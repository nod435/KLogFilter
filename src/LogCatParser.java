import java.awt.Color;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * logcat / 커널 로그 한 줄을 LogInfo로 바꾼다.
 *
 * 지원 형식 (adb logcat -v ...)
 *   threadtime      04-20 12:06:02.125   146   179 D BatteryService: update start
 *   time            04-17 09:01:18.910 D/LightsService(  139): BKL : 106
 *   + year          2026-04-20 12:06:02.125 ...        (위 두 형식 앞에 연도)
 *   + uid           04-20 12:06:02.125  1000   146   179 D Tag: msg   /   D/Tag( 1000:  139): msg
 *   brief           D/LightsService(  139): BKL : 106
 *   process         D(  139) BKL : 106  (LightsService)
 *   tag             D/LightsService: BKL : 106
 *   kernel          <4>[19553.494855] msg   /   [19553.494855] msg (dmesg)
 * 어느 형식에도 맞지 않으면 줄 전체를 메시지로 쓴다.
 */
public class LogCatParser implements ILogParser
{
    // 레벨 문자: Verbose, Debug, Info, Warn, Error, Fatal, Assert, Silent
    static final String LV = "([VDIWEFAS])";
    // 날짜·시간: [YYYY-]MM-DD HH:MM:SS.mmm (밀리초 3~6자리)
    static final String DATE_TIME = "(?:(\\d{4})-)?(\\d\\d-\\d\\d)\\s+(\\d\\d:\\d\\d:\\d\\d\\.\\d{3,6})\\s+";

    // threadtime: [uid] pid tid L Tag : msg   (태그는 첫 ':' 앞까지, 오른쪽 공백은 코드에서 제거)
    // uid 그룹은 '??'(없는 경우 먼저 시도)로 해서 흔한 형식에서 되돌아가기(backtracking)를 줄인다.
    static final Pattern THREADTIME = Pattern.compile("^" + DATE_TIME + "(?:(\\S+)\\s+)??(\\d+)\\s+(\\d+)\\s+" + LV + "\\s+([^:]*):\\s?(.*)$");
    // time: L/Tag( [uid:] pid): msg
    static final Pattern TIME       = Pattern.compile("^" + DATE_TIME + LV + "/(.*?)\\(\\s*(?:[^:()]+:\\s*)?(\\d+)\\):\\s?(.*)$");
    static final Pattern BRIEF      = Pattern.compile("^" + LV + "/(.*?)\\(\\s*(?:[^:()]+:\\s*)?(\\d+)\\):\\s?(.*)$");
    static final Pattern PROCESS    = Pattern.compile("^" + LV + "\\(\\s*(\\d+)\\)\\s(.*?)\\s+\\((.*)\\)\\s*$");
    static final Pattern TAG        = Pattern.compile("^" + LV + "/([^:]*):\\s?(.*)$");
    // 커널: <레벨>[시간] msg  또는 dmesg의 [시간] msg
    static final Pattern KERNEL     = Pattern.compile("^(?:<([0-7])>)?\\[\\s*(\\d+\\.\\d+)\\]\\s?(.*)$");

    // 줄마다 Color를 새로 만들지 않도록 RGB 값별로 재사용 (파싱 스레드가 여럿일 수 있어 ConcurrentHashMap)
    final ConcurrentHashMap<Integer, Color> m_hmColor = new ConcurrentHashMap<Integer, Color>();

    Color colorOf(int nRGB)
    {
        Color color = m_hmColor.get(nRGB);
        if(color == null)
        {
            color = new Color(nRGB);
            m_hmColor.put(nRGB, color);
        }
        return color;
    }

    public Color getColor(LogInfo logInfo)
    {
        if(logInfo.m_strLogLV == null) return Color.BLACK;

        if(logInfo.m_strLogLV.equals("FATAL") || logInfo.m_strLogLV.equals("F") || logInfo.m_strLogLV.equals("A"))
            return colorOf(LogColor.COLOR_FATAL);
        if(logInfo.m_strLogLV.equals("ERROR") || logInfo.m_strLogLV.equals("E") || logInfo.m_strLogLV.equals("3"))
            return colorOf(LogColor.COLOR_ERROR);
        else if(logInfo.m_strLogLV.equals("WARN") || logInfo.m_strLogLV.equals("W") || logInfo.m_strLogLV.equals("4"))
            return colorOf(LogColor.COLOR_WARN);
        else if(logInfo.m_strLogLV.equals("INFO") || logInfo.m_strLogLV.equals("I") || logInfo.m_strLogLV.equals("6"))
            return colorOf(LogColor.COLOR_INFO);
        else if(logInfo.m_strLogLV.equals("DEBUG") || logInfo.m_strLogLV.equals("D") || logInfo.m_strLogLV.equals("7"))
            return colorOf(LogColor.COLOR_DEBUG);
        else if(logInfo.m_strLogLV.equals("0"))
            return colorOf(LogColor.COLOR_0);
        else if(logInfo.m_strLogLV.equals("1"))
            return colorOf(LogColor.COLOR_1);
        else if(logInfo.m_strLogLV.equals("2"))
            return colorOf(LogColor.COLOR_2);
        else if(logInfo.m_strLogLV.equals("5"))
            return colorOf(LogColor.COLOR_5);
        else
            return Color.BLACK;
    }

    static String nz(String str)
    {
        return str == null ? "" : str;
    }

    // 연도가 있으면 "YYYY-MM-DD", 없으면 "MM-DD"
    static String dateOf(Matcher m)
    {
        return m.group(1) != null ? m.group(1) + "-" + m.group(2) : m.group(2);
    }

    static boolean isLevel(char c)
    {
        return "VDIWEFAS".indexOf(c) >= 0;
    }

    static int skipSpaces(String s, int i)
    {
        while(i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    static int skipDigits(String s, int i)
    {
        while(i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return i;
    }

    // threadtime 기본형("MM-DD HH:MM:SS.mmm  pid  tid L Tag: msg")을 문자 단위로 직접 나눈다.
    // 연도·uid가 붙은 변형이나 모양이 다르면 null을 돌려 정규식으로 넘긴다.
    static LogInfo parseThreadTimeFast(String s)
    {
        int n = s.length();
        if(n < 24 || s.charAt(2) != '-' || s.charAt(5) != ' ' || s.charAt(8) != ':' || s.charAt(11) != ':' || s.charAt(14) != '.')
            return null;
        int nTimeEnd = skipDigits(s, 15);
        if(nTimeEnd - 15 < 3 || nTimeEnd >= n || s.charAt(nTimeEnd) != ' ') return null;

        int nPid = skipSpaces(s, nTimeEnd), nPidEnd = skipDigits(s, nPid);
        if(nPidEnd == nPid || nPidEnd >= n || s.charAt(nPidEnd) != ' ') return null;
        int nTid = skipSpaces(s, nPidEnd), nTidEnd = skipDigits(s, nTid);
        if(nTidEnd == nTid || nTidEnd >= n || s.charAt(nTidEnd) != ' ') return null;
        int nLv = skipSpaces(s, nTidEnd);
        if(nLv + 1 >= n || !isLevel(s.charAt(nLv)) || s.charAt(nLv + 1) != ' ') return null;
        int nColon = s.indexOf(':', nLv + 2);
        if(nColon < 0) return null;

        LogInfo logInfo = new LogInfo();
        logInfo.m_strDate    = s.substring(0, 5);
        logInfo.m_strTime    = s.substring(6, nTimeEnd);
        logInfo.m_strPid     = s.substring(nPid, nPidEnd);
        logInfo.m_strThread  = s.substring(nTid, nTidEnd);
        logInfo.m_strLogLV   = s.substring(nLv, nLv + 1);
        logInfo.m_strTag     = s.substring(nLv + 2, nColon).trim();
        int nMsg = nColon + 1;
        if(nMsg < n && s.charAt(nMsg) == ' ') nMsg++;
        logInfo.m_strMessage = s.substring(nMsg);
        return logInfo;
    }

    // 형식별 파싱. 맞지 않으면 null.
    LogInfo parseFormatted(String strText)
    {
        if(strText.isEmpty()) return null;
        char c = strText.charAt(0);
        Matcher m;
        LogInfo logInfo = new LogInfo();

        if(Character.isDigit(c))
        {
            // 가장 흔한 threadtime은 정규식 없이 먼저 시도
            LogInfo fast = parseThreadTimeFast(strText);
            if(fast != null)
                return fast;
            // threadtime / time (+year, +uid)
            if((m = THREADTIME.matcher(strText)).matches())
            {
                logInfo.m_strDate    = dateOf(m);
                logInfo.m_strTime    = m.group(3);
                logInfo.m_strPid     = m.group(5);
                logInfo.m_strThread  = m.group(6);
                logInfo.m_strLogLV   = m.group(7);
                logInfo.m_strTag     = m.group(8).trim();
                logInfo.m_strMessage = nz(m.group(9));
                return logInfo;
            }
            if((m = TIME.matcher(strText)).matches())
            {
                logInfo.m_strDate    = dateOf(m);
                logInfo.m_strTime    = m.group(3);
                logInfo.m_strLogLV   = m.group(4);
                logInfo.m_strTag     = m.group(5).trim();
                logInfo.m_strPid     = m.group(6);
                logInfo.m_strMessage = nz(m.group(7));
                return logInfo;
            }
            return null;
        }
        if(c == '<' || c == '[')
        {
            if((m = KERNEL.matcher(strText)).matches())
            {
                logInfo.m_strLogLV   = nz(m.group(1));
                logInfo.m_strTime    = m.group(2);
                logInfo.m_strMessage = m.group(3);
                return logInfo;
            }
            return null;
        }
        if(strText.length() > 1 && "VDIWEFAS".indexOf(c) >= 0)
        {
            char c2 = strText.charAt(1);
            if(c2 == '/' && (m = BRIEF.matcher(strText)).matches())
            {
                logInfo.m_strLogLV   = m.group(1);
                logInfo.m_strTag     = m.group(2).trim();
                logInfo.m_strPid     = m.group(3);
                logInfo.m_strMessage = nz(m.group(4));
                return logInfo;
            }
            if(c2 == '(' && (m = PROCESS.matcher(strText)).matches())
            {
                logInfo.m_strLogLV   = m.group(1);
                logInfo.m_strPid     = m.group(2);
                logInfo.m_strMessage = m.group(3);
                logInfo.m_strTag     = m.group(4).trim();
                return logInfo;
            }
            if(c2 == '/' && (m = TAG.matcher(strText)).matches())
            {
                logInfo.m_strLogLV   = m.group(1);
                logInfo.m_strTag     = m.group(2).trim();
                logInfo.m_strMessage = nz(m.group(3));
                return logInfo;
            }
        }
        return null;
    }

    public LogInfo parseLog(String strText)
    {
        LogInfo logInfo = parseFormatted(strText);
        if(logInfo == null)
        {
            logInfo = new LogInfo();
            logInfo.m_strMessage = strText;
        }
        logInfo.m_TextColor = getColor(logInfo);
        // 필터 판정 때 문자열 비교 대신 비트 연산을 쓰도록 레벨을 미리 계산
        logInfo.m_nLogLV = LogInfo.levelOf(logInfo.m_strLogLV);
        return logInfo;
    }
}
