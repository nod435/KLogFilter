import java.util.ArrayList;
import java.util.StringTokenizer;

/**
 * '|'로 구분된 필터 문자열을 소문자 토큰 배열로 미리 나눠 두고,
 * 대소문자를 무시한 부분 일치(contains)를 문자열 생성 없이 판정한다.
 */
public class FilterToken
{
    public static final String[] EMPTY = new String[0];

    // 필터 문자열 → 소문자 토큰 배열 (빈 문자열이면 EMPTY)
    public static String[] split(String strFilter)
    {
        if(strFilter == null || strFilter.length() == 0) return EMPTY;

        ArrayList<String> arToken = new ArrayList<String>();
        StringTokenizer stk = new StringTokenizer(strFilter, "|", false);
        while(stk.hasMoreElements())
            arToken.add(stk.nextToken().toLowerCase());
        return arToken.toArray(new String[arToken.size()]);
    }

    // 토큰 중 하나라도 strField에 포함되면 true (토큰이 없으면 false)
    public static boolean matchAny(String strField, String[] arToken)
    {
        if(strField == null) return false;
        for(int i = 0; i < arToken.length; i++)
            if(containsIgnoreCase(strField, arToken[i]))
                return true;
        return false;
    }

    // strNeedleLower는 소문자로 변환된 토큰이어야 한다.
    public static boolean containsIgnoreCase(String strHay, String strNeedleLower)
    {
        int nLen = strNeedleLower.length();
        if(nLen == 0) return true;

        char cFirst  = strNeedleLower.charAt(0);
        char cFirstU = Character.toUpperCase(cFirst);
        int  nMax    = strHay.length() - nLen;
        for(int i = 0; i <= nMax; i++)
        {
            char c = strHay.charAt(i);
            if(c != cFirst && c != cFirstU && Character.toLowerCase(c) != cFirst) continue;
            if(strHay.regionMatches(true, i, strNeedleLower, 0, nLen)) return true;
        }
        return false;
    }
}
