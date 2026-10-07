import java.io.InputStream;
import java.util.Properties;

/**
 * 버전 규칙: 큰버전.중간버전.날짜
 * - 큰버전   : 큰 변경이 있을 때 올린다.
 * - 중간버전 : 신규 기능을 추가할 때마다 1 올린다.
 * - 날짜     : 버전을 만든(빌드한) 날짜시간 yyyyMMddHHmmss. build.bat이 version.properties에 넣는다.
 *              빌드 스크립트 없이 실행하면(개발 중) "dev"로 표시된다.
 */
public class AppVersion
{
    static final int    MAJOR = 1;
    static final int    MINOR = 11;
    static final String BUILD = loadBuild();

    static String loadBuild()
    {
        try(InputStream in = AppVersion.class.getResourceAsStream("/version.properties"))
        {
            if(in != null)
            {
                Properties p = new Properties();
                p.load(in);
                String strBuild = p.getProperty("build", "").trim();
                if(strBuild.matches("\\d{14}"))
                    return strBuild;
            }
        }
        catch(Exception e)
        {
            System.out.println("version.properties : " + e);
        }
        return "dev";
    }

    // 예: 1.10.20261007090000
    static String full()
    {
        return MAJOR + "." + MINOR + "." + BUILD;
    }

    // build.bat이 매니페스트에 넣을 버전 문자열을 얻을 때 쓴다.
    public static void main(String[] args)
    {
        System.out.println(full());
    }
}
