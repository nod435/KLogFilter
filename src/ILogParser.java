import java.awt.Color;

/**
 * 
 */


/**
 * 
 */
public interface ILogParser
{
    public LogInfo parseLog(String strText);
    public Color   getColor(LogInfo logInfo);
}
