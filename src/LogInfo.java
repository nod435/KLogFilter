import java.awt.Color;

public class LogInfo
{
    public static final int LOG_LV_VERBOSE  = 1;
    public static final int LOG_LV_DEBUG    = LOG_LV_VERBOSE << 1;
    public static final int LOG_LV_INFO     = LOG_LV_DEBUG << 1;
    public static final int LOG_LV_WARN     = LOG_LV_INFO << 1;
    public static final int LOG_LV_ERROR    = LOG_LV_WARN << 1;
    public static final int LOG_LV_FATAL    = LOG_LV_ERROR << 1;
    public static final int LOG_LV_ALL      = LOG_LV_VERBOSE | LOG_LV_DEBUG | LOG_LV_INFO
                                              | LOG_LV_WARN | LOG_LV_ERROR | LOG_LV_FATAL;
    public static final int LOG_LV_NONE     = 0;    // 레벨 없음(커널 로그, 형식 불명 줄): 레벨 필터가 켜지면 숨김

    boolean                 m_bMarked;
    int                     m_nLine;                // 1부터 시작하는 줄 번호 (m_strLine과 같은 값)
    int                     m_nLogLV        = LOG_LV_NONE;
    String                  m_strBookmark   = "";
    String                  m_strDate       = "";
    String                  m_strLine       = "";
    String                  m_strTime       = "";
    String                  m_strLogLV      = "";
    String                  m_strPid        = "";
    String                  m_strThread     = "";
    String                  m_strTag        = "";
    String                  m_strMessage    = "";
    Color                   m_TextColor;
    
    // 레벨 문자열 → LOG_LV_* 비트 (알 수 없으면 LOG_LV_NONE)
    public static int levelOf(String strLogLV)
    {
        if(strLogLV == null) return LOG_LV_NONE;
        if(strLogLV.equals("V") || strLogLV.equals("VERBOSE")) return LOG_LV_VERBOSE;
        if(strLogLV.equals("D") || strLogLV.equals("DEBUG"))   return LOG_LV_DEBUG;
        if(strLogLV.equals("I") || strLogLV.equals("INFO"))    return LOG_LV_INFO;
        if(strLogLV.equals("W") || strLogLV.equals("WARN"))    return LOG_LV_WARN;
        if(strLogLV.equals("E") || strLogLV.equals("ERROR"))   return LOG_LV_ERROR;
        if(strLogLV.equals("F") || strLogLV.equals("FATAL"))   return LOG_LV_FATAL;
        return LOG_LV_NONE;
    }

    public void setLine(int nLine)
    {
        m_nLine   = nLine;
        m_strLine = "" + nLine;
    }

    public boolean isError()
    {
        return m_nLogLV == LOG_LV_ERROR;
    }

    public void display()
    {
        T.d("=============================================");
        T.d("m_bMarked      = " + m_bMarked);
        T.d("m_strBookmark  = " + m_strBookmark);
        T.d("m_strDate      = " + m_strDate);
        T.d("m_strLine      = " + m_strLine);
        T.d("m_strTime      = " + m_strTime);
        T.d("m_strLogLV     = " + m_strLogLV);
        T.d("m_strPid       = " + m_strPid);
        T.d("m_strThread    = " + m_strThread);
        T.d("m_strTag       = " + m_strTag);
        T.d("m_strMessage   = " + m_strMessage);
        T.d("=============================================");
    }
    
    public Object getData(int nColumn)
    {
        switch(nColumn)
        {
            case LogFilterTableModel.COMUMN_LINE:
                return m_strLine;
            case LogFilterTableModel.COMUMN_DATE:
                return m_strDate;
            case LogFilterTableModel.COMUMN_TIME:
                return m_strTime;
            case LogFilterTableModel.COMUMN_LOGLV:
                return m_strLogLV;
            case LogFilterTableModel.COMUMN_PID:
                return m_strPid;
            case LogFilterTableModel.COMUMN_THREAD:
                return m_strThread;
            case LogFilterTableModel.COMUMN_TAG:
                return m_strTag;
            case LogFilterTableModel.COMUMN_BOOKMARK:
                return m_strBookmark;
            case LogFilterTableModel.COMUMN_MESSAGE:
                return m_strMessage;
        }
        return null;
    }
}
