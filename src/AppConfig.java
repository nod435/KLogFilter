import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.swing.JFrame;

/**
 * 설정 파일(*.ini) 읽기/쓰기.
 * - LogFilter.ini      : 필터 문자열, 폰트, 창 크기/상태, 컬럼 폭 (이 클래스의 필드)
 * - LogFilterColor.ini : 레벨/하이라이트 색상 (LogColor의 static 값)
 * - LogFilterCmd.ini   : adb 명령 목록 (읽기 전용)
 * 키가 없거나 값이 잘못되면 그 키만 기본값을 쓴다.
 */
public class AppConfig
{
    static final String INI_FILE            = "LogFilter.ini";
    static final String INI_FILE_CMD        = "LogFilterCmd.ini";
    static final String INI_FILE_COLOR      = "LogFilterColor.ini";
    static final String INI_CMD_COUNT       = "CMD_COUNT";
    static final String INI_CMD             = "CMD_";
    static final String INI_FONT_TYPE       = "FONT_TYPE";
    static final String INI_WORD_FIND       = "WORD_FIND";
    static final String INI_WORD_REMOVE     = "WORD_REMOVE";
    static final String INI_TAG_SHOW        = "TAG_SHOW";
    static final String INI_TAG_REMOVE      = "TAG_REMOVE";
    static final String INI_HIGHLIGHT       = "HIGHLIGHT";            // 이전 버전(하이라이트 1개). 읽기만 함
    static final String INI_HIGHLIGHT_      = "HIGHLIGHT_";           // 하이라이트 입력창 n의 문자열
    static final String INI_HIGHLIGHT_COLOR_= "HIGHLIGHT_COLOR_";     // 입력창 n이 쓰는 색상 번호 (0~5)
    static final String INI_HIGHLIGHT_ON_   = "HIGHLIGHT_ON_";        // 입력창 n 사용 여부
    static final String INI_PID_SHOW        = "PID_SHOW";
    static final String INI_TID_SHOW        = "TID_SHOW";
    static final String INI_COLOR_0         = "INI_COLOR_0";
    static final String INI_COLOR_1         = "INI_COLOR_1";
    static final String INI_COLOR_2         = "INI_COLOR_2";
    static final String INI_COLOR_3         = "INI_COLOR_3(E)";
    static final String INI_COLOR_4         = "INI_COLOR_4(W)";
    static final String INI_COLOR_5         = "INI_COLOR_5";
    static final String INI_COLOR_6         = "INI_COLOR_6(I)";
    static final String INI_COLOR_7         = "INI_COLOR_7(D)";
    static final String INI_COLOR_8         = "INI_COLOR_8(F)";
    static final String INI_HIGILIGHT_COUNT = "INI_HIGILIGHT_COUNT";
    static final String INI_HIGILIGHT_      = "INI_HIGILIGHT_";
    static final String INI_WIDTH           = "INI_WIDTH";
    static final String INI_HEIGHT          = "INI_HEIGHT";
    static final String INI_WINDOW_STATE    = "INI_WINDOW_STATE";
    static final String INI_COMUMN          = "INI_COMUMN_";

    static final int    DEFAULT_WIDTH       = 1200;
    static final int    DEFAULT_HEIGHT      = 720;
    static final int    MIN_WIDTH           = 1100;
    static final int    MIN_HEIGHT          = 500;

    // LogFilterCmd.ini가 없거나 비어 있을 때 쓰는 기본 명령 (배포본 ini와 같은 값)
    static final String[] DEFAULT_CMDS = { "logcat -v threadtime", "logcat -v time", "logcat -b radio -v time",
                                           "logcat -b events -v time", "shell cat /proc/kmsg" };

    // ---- LogFilter.ini 값 ----
    String strFontType  = "";
    String strFind      = "";
    String strRemove    = "";
    String strShowTag   = "";
    String strRemoveTag = "";
    String strShowPid   = "";
    String strShowTid   = "";
    // 하이라이트 입력창 6개: 문자열, 색상 번호, 사용 여부 (기본: n번 입력창은 n번 색상, 사용)
    String[]  arHighlight      = new String[LogColor.HIGHLIGHT_COUNT];
    int[]     arHighlightColor = new int[LogColor.HIGHLIGHT_COUNT];
    boolean[] arHighlightOn    = new boolean[LogColor.HIGHLIGHT_COUNT];
    {
        for(int nIndex = 0; nIndex < LogColor.HIGHLIGHT_COUNT; nIndex++)
        {
            arHighlight[nIndex]      = "";
            arHighlightColor[nIndex] = nIndex;
            arHighlightOn[nIndex]    = true;
        }
    }
    int    nWinWidth    = DEFAULT_WIDTH;
    int    nWinHeight   = DEFAULT_HEIGHT;
    int    nWindowState = JFrame.NORMAL;
    int[]  arColumnWidth = new int[LogFilterTableModel.COMUMN_MAX];

    // LogFilter.ini를 읽는다. 파일이 없으면 모두 기본값.
    static AppConfig load()
    {
        AppConfig config = new AppConfig();
        Properties p = loadProperties(INI_FILE);

        config.strFontType  = p.getProperty(INI_FONT_TYPE, "");
        config.strFind      = p.getProperty(INI_WORD_FIND, "");
        config.strRemove    = p.getProperty(INI_WORD_REMOVE, "");
        config.strShowTag   = p.getProperty(INI_TAG_SHOW, "");
        config.strRemoveTag = p.getProperty(INI_TAG_REMOVE, "");
        config.strShowPid   = p.getProperty(INI_PID_SHOW, "");
        config.strShowTid   = p.getProperty(INI_TID_SHOW, "");
        for(int nIndex = 0; nIndex < LogColor.HIGHLIGHT_COUNT; nIndex++)
        {
            // 이전 버전 설정(HIGHLIGHT 하나)은 첫 번째 입력창으로 옮긴다.
            String strDefault = nIndex == 0 ? p.getProperty(INI_HIGHLIGHT, "") : "";
            config.arHighlight[nIndex]      = p.getProperty(INI_HIGHLIGHT_ + nIndex, strDefault);
            int nColor = intOf(p, INI_HIGHLIGHT_COLOR_ + nIndex, nIndex);
            config.arHighlightColor[nIndex] = nColor >= 0 && nColor < LogColor.HIGHLIGHT_COUNT ? nColor : nIndex;
            config.arHighlightOn[nIndex]    = !"false".equalsIgnoreCase(p.getProperty(INI_HIGHLIGHT_ON_ + nIndex, "true").trim());
        }
        config.nWinWidth    = Math.max(MIN_WIDTH,  intOf(p, INI_WIDTH,  DEFAULT_WIDTH));
        config.nWinHeight   = Math.max(MIN_HEIGHT, intOf(p, INI_HEIGHT, DEFAULT_HEIGHT));
        config.nWindowState = intOf(p, INI_WINDOW_STATE, JFrame.NORMAL);
        if(config.nWindowState == JFrame.ICONIFIED)
            config.nWindowState = JFrame.NORMAL;   // 최소화 상태로 시작하지 않도록

        for(int nIndex = 0; nIndex < LogFilterTableModel.COMUMN_MAX; nIndex++)
            config.arColumnWidth[nIndex] = intOf(p, INI_COMUMN + nIndex, LogFilterTableModel.ColWidth[nIndex]);
        return config;
    }

    void save()
    {
        Properties p = new Properties();
        p.setProperty(INI_FONT_TYPE,    strFontType);
        p.setProperty(INI_WORD_FIND,    strFind);
        p.setProperty(INI_WORD_REMOVE,  strRemove);
        p.setProperty(INI_TAG_SHOW,     strShowTag);
        p.setProperty(INI_TAG_REMOVE,   strRemoveTag);
        p.setProperty(INI_PID_SHOW,     strShowPid);
        p.setProperty(INI_TID_SHOW,     strShowTid);
        for(int nIndex = 0; nIndex < LogColor.HIGHLIGHT_COUNT; nIndex++)
        {
            p.setProperty(INI_HIGHLIGHT_ + nIndex,       arHighlight[nIndex] == null ? "" : arHighlight[nIndex]);
            p.setProperty(INI_HIGHLIGHT_COLOR_ + nIndex, "" + arHighlightColor[nIndex]);
            p.setProperty(INI_HIGHLIGHT_ON_ + nIndex,    "" + arHighlightOn[nIndex]);
        }
        p.setProperty(INI_WIDTH,        "" + nWinWidth);
        p.setProperty(INI_HEIGHT,       "" + nWinHeight);
        p.setProperty(INI_WINDOW_STATE, "" + nWindowState);
        for(int nIndex = 0; nIndex < arColumnWidth.length; nIndex++)
            p.setProperty(INI_COMUMN + nIndex, "" + arColumnWidth[nIndex]);
        storeProperties(p, INI_FILE);
    }

    // LogFilterCmd.ini의 명령 목록. 없거나 비어 있으면 DEFAULT_CMDS.
    static List<String> loadCmds()
    {
        List<String> arCmd = new ArrayList<String>();
        Properties p = loadProperties(INI_FILE_CMD);
        int nCount = intOf(p, INI_CMD_COUNT, 0);
        for(int nIndex = 0; nIndex < nCount; nIndex++)
        {
            String strCmd = p.getProperty(INI_CMD + nIndex);
            if(strCmd != null && strCmd.trim().length() > 0)
                arCmd.add(strCmd);
        }
        if(arCmd.isEmpty())
        {
            for(String strCmd : DEFAULT_CMDS)
                arCmd.add(strCmd);
        }
        return arCmd;
    }

    // LogFilterColor.ini → LogColor
    static void loadColors()
    {
        Properties p = loadProperties(INI_FILE_COLOR);

        LogColor.COLOR_0 = hexOf(p, INI_COLOR_0, LogColor.COLOR_0);
        LogColor.COLOR_1 = hexOf(p, INI_COLOR_1, LogColor.COLOR_1);
        LogColor.COLOR_2 = hexOf(p, INI_COLOR_2, LogColor.COLOR_2);
        LogColor.COLOR_ERROR = LogColor.COLOR_3 = hexOf(p, INI_COLOR_3, LogColor.COLOR_3);
        LogColor.COLOR_WARN  = LogColor.COLOR_4 = hexOf(p, INI_COLOR_4, LogColor.COLOR_4);
        LogColor.COLOR_5 = hexOf(p, INI_COLOR_5, LogColor.COLOR_5);
        LogColor.COLOR_INFO  = LogColor.COLOR_6 = hexOf(p, INI_COLOR_6, LogColor.COLOR_6);
        LogColor.COLOR_DEBUG = LogColor.COLOR_7 = hexOf(p, INI_COLOR_7, LogColor.COLOR_7);
        LogColor.COLOR_FATAL = LogColor.COLOR_8 = hexOf(p, INI_COLOR_8, LogColor.COLOR_8);

        // 하이라이트 색상 6개: 빠졌거나 잘못된 값은 기본 색상을 쓴다.
        String[] arHighlight = new String[LogColor.HIGHLIGHT_COUNT];
        for(int nIndex = 0; nIndex < arHighlight.length; nIndex++)
            arHighlight[nIndex] = highlightOf(p.getProperty(INI_HIGILIGHT_ + nIndex), LogColor.DEFAULT_HIGHLIGHT[nIndex]);
        LogColor.COLOR_HIGHLIGHT = arHighlight;
    }

    // "0xFFFF" 같은 값 → "00FFFF". 잘못된 값이면 기본값.
    static String highlightOf(String strValue, String strDefault)
    {
        if(strValue == null) return strDefault;
        strValue = strValue.trim().replace("0x", "").replace("0X", "");
        if(!strValue.matches("[0-9a-fA-F]{1,6}")) return strDefault;
        while(strValue.length() < 6) strValue = "0" + strValue;
        return strValue.toUpperCase();
    }

    // LogColor → LogFilterColor.ini
    static void saveColors()
    {
        Properties p = new Properties();

        p.setProperty(INI_COLOR_0, "0x" + Integer.toHexString(LogColor.COLOR_0).toUpperCase());
        p.setProperty(INI_COLOR_1, "0x" + Integer.toHexString(LogColor.COLOR_1).toUpperCase());
        p.setProperty(INI_COLOR_2, "0x" + Integer.toHexString(LogColor.COLOR_2).toUpperCase());
        p.setProperty(INI_COLOR_3, "0x" + Integer.toHexString(LogColor.COLOR_3).toUpperCase());
        p.setProperty(INI_COLOR_4, "0x" + Integer.toHexString(LogColor.COLOR_4).toUpperCase());
        p.setProperty(INI_COLOR_5, "0x" + Integer.toHexString(LogColor.COLOR_5).toUpperCase());
        p.setProperty(INI_COLOR_6, "0x" + Integer.toHexString(LogColor.COLOR_6).toUpperCase());
        p.setProperty(INI_COLOR_7, "0x" + Integer.toHexString(LogColor.COLOR_7).toUpperCase());
        p.setProperty(INI_COLOR_8, "0x" + Integer.toHexString(LogColor.COLOR_8).toUpperCase());

        p.setProperty(INI_HIGILIGHT_COUNT, "" + LogColor.COLOR_HIGHLIGHT.length);
        for(int nIndex = 0; nIndex < LogColor.COLOR_HIGHLIGHT.length; nIndex++)
            p.setProperty(INI_HIGILIGHT_ + nIndex, "0x" + LogColor.COLOR_HIGHLIGHT[nIndex].toUpperCase());

        storeProperties(p, INI_FILE_COLOR);
    }

    // ---- 공통 ----

    // 설정 파일 읽기. 파일이 없거나 읽을 수 없으면 빈 Properties를 돌려준다(각 키는 기본값 사용).
    static Properties loadProperties(String strFile)
    {
        Properties p = new Properties();
        File file = new File(strFile);
        if(!file.exists()) return p;
        try(FileInputStream in = new FileInputStream(file))
        {
            p.load(in);
        }
        catch(Exception e)
        {
            System.out.println(strFile + " : " + e);
        }
        return p;
    }

    static void storeProperties(Properties p, String strFile)
    {
        try(FileOutputStream out = new FileOutputStream(strFile))
        {
            p.store(out, "done.");
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
    }

    // 키가 없거나 숫자가 아니면 기본값을 쓴다. (키 하나가 잘못돼도 나머지 설정은 그대로 적용)
    static int intOf(Properties p, String strKey, int nDefault)
    {
        try
        {
            String strValue = p.getProperty(strKey);
            return strValue == null ? nDefault : Integer.parseInt(strValue.trim());
        }
        catch(NumberFormatException e)
        {
            System.out.println(strKey + " : " + e);
            return nDefault;
        }
    }

    static int hexOf(Properties p, String strKey, int nDefault)
    {
        try
        {
            String strValue = p.getProperty(strKey);
            return strValue == null ? nDefault : Integer.parseInt(strValue.trim().replace("0x", "").replace("0X", ""), 16);
        }
        catch(NumberFormatException e)
        {
            System.out.println(strKey + " : " + e);
            return nDefault;
        }
    }
}
