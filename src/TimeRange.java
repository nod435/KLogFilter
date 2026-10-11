import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 시간 범위 필터: 시작·끝 시각 사이의 줄만 통과시킨다. 한쪽은 비워 둘 수 있다.
 * 입력 형식: "12:30", "12:30:15", "12:30:15.500", 날짜를 붙이면 "10-07 12:30" (월-일).
 * - 날짜 없이 쓰면 시각만 비교하고, 시작이 끝보다 늦으면(23:00 ~ 01:00) 자정을 넘는 범위로 본다.
 * - 시각이 없는 줄(kernel, brief 형식 등)은 시간 필터를 켜면 보이지 않는다.
 */
public final class TimeRange
{
    private static final Pattern INPUT = Pattern.compile("^\\s*(?:(\\d{1,2})-(\\d{1,2})\\s+)?(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2})(?:\\.(\\d{1,3}))?)?\\s*$");

    final String  m_strFrom;    // 비교용 키 ("MM-dd HH:mm:ss.SSS" 또는 "HH:mm:ss.SSS"), 없으면 null
    final String  m_strTo;
    final boolean m_bFromDate;  // 시작에 날짜가 있는지
    final boolean m_bToDate;

    private TimeRange(String strFrom, boolean bFromDate, String strTo, boolean bToDate)
    {
        m_strFrom = strFrom; m_bFromDate = bFromDate;
        m_strTo   = strTo;   m_bToDate   = bToDate;
    }

    /** 두 입력이 모두 비었으면 null(필터 안 함). 형식이 틀리면 IllegalArgumentException(메시지에 이유). */
    static TimeRange parse(String strFrom, String strTo)
    {
        String[] from = key(strFrom, false, "시작");
        String[] to   = key(strTo, true, "끝");
        if(from == null && to == null)
            return null;
        return new TimeRange(from == null ? null : from[0], from != null && from[1] != null,
                             to == null ? null : to[0], to != null && to[1] != null);
    }

    // 입력 → {키, 날짜가 있으면 "d"}. 빈 입력이면 null. 끝(bEnd)은 생략한 초·밀리초를 최댓값으로 채운다.
    private static String[] key(String strText, boolean bEnd, String strWhich)
    {
        if(strText == null || strText.trim().length() == 0)
            return null;
        Matcher m = INPUT.matcher(strText);
        if(!m.matches())
            throw new IllegalArgumentException(strWhich + " 시각 형식이 맞지 않습니다: \"" + strText.trim() + "\" (예: 12:30, 12:30:15.500, 10-07 12:30)");
        int nHour = Integer.parseInt(m.group(3)), nMin = Integer.parseInt(m.group(4));
        int nSec  = m.group(5) != null ? Integer.parseInt(m.group(5)) : (bEnd ? 59 : 0);
        String strMs = m.group(6) != null ? (m.group(6) + "00").substring(0, 3) : (bEnd ? "999" : "000");
        if(nHour > 23 || nMin > 59 || nSec > 59)
            throw new IllegalArgumentException(strWhich + " 시각 값이 맞지 않습니다: \"" + strText.trim() + "\"");
        String strTime = String.format("%02d:%02d:%02d.%s", nHour, nMin, nSec, strMs);
        if(m.group(1) == null)
            return new String[]{ strTime, null };
        int nMonth = Integer.parseInt(m.group(1)), nDay = Integer.parseInt(m.group(2));
        if(nMonth < 1 || nMonth > 12 || nDay < 1 || nDay > 31)
            throw new IllegalArgumentException(strWhich + " 날짜 값이 맞지 않습니다: \"" + strText.trim() + "\"");
        return new String[]{ String.format("%02d-%02d %s", nMonth, nDay, strTime), "d" };
    }

    // 줄의 날짜("10-07" 또는 "2026-10-07")와 시각("12:00:00.000…")이 범위 안인지
    boolean accept(String strDate, String strTime)
    {
        String strLineTime = lineTime(strTime);
        if(strLineTime == null)
            return false;       // 시각이 없는 줄
        String strLineDate = lineDate(strDate);
        boolean bAfterFrom = m_strFrom == null || compare(strLineDate, strLineTime, m_strFrom, m_bFromDate) >= 0;
        boolean bBeforeTo  = m_strTo   == null || compare(strLineDate, strLineTime, m_strTo,   m_bToDate)   <= 0;
        // 날짜 없이 시작 > 끝이면 자정을 넘는 범위 (예: 23:00 ~ 01:00)
        if(m_strFrom != null && m_strTo != null && !m_bFromDate && !m_bToDate && m_strFrom.compareTo(m_strTo) > 0)
            return bAfterFrom || bBeforeTo;
        return bAfterFrom && bBeforeTo;
    }

    private static int compare(String strLineDate, String strLineTime, String strBound, boolean bBoundDate)
    {
        if(bBoundDate)
        {
            if(strLineDate == null)
                return strLineTime.compareTo(strBound.substring(6));    // 날짜 없는 줄은 시각만 비교
            return (strLineDate + " " + strLineTime).compareTo(strBound);
        }
        return strLineTime.compareTo(strBound);
    }

    // "12:00:00.000123" → "12:00:00.000", "9:05:01" → "09:05:01.000". 시각이 아니면 null.
    static String lineTime(String strTime)
    {
        if(strTime == null) return null;
        String s = strTime.trim();
        int nColon = s.indexOf(':');
        if(nColon < 1 || nColon > 2) return null;
        if(nColon == 1) s = "0" + s;
        if(s.length() < 8 || s.charAt(5) != ':') return null;
        if(s.length() == 8) return s + ".000";
        if(s.charAt(8) != '.') return s.substring(0, 8) + ".000";
        return s.length() >= 12 ? s.substring(0, 12) : (s + "000").substring(0, 12);
    }

    // "10-07" 또는 "2026-10-07" → "10-07". 날짜가 아니면 null.
    static String lineDate(String strDate)
    {
        if(strDate == null) return null;
        String s = strDate.trim();
        if(s.length() >= 5 && s.charAt(s.length() - 3) == '-')
            return s.substring(s.length() - 5);
        return null;
    }
}
