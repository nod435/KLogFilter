/**
 * 
 */

/**
 * 
 */
public interface INotiEvent
{
    public static int EVENT_CLICK_BOOKMARK              = 0;
    public static int EVENT_CLICK_ERROR                 = 1;
    public static int EVENT_CHANGE_FILTER_SHOW_TAG      = 2;
    public static int EVENT_CHANGE_FILTER_REMOVE_TAG    = 3;

    void notiEvent(EventParam param);
    
    class EventParam
    {
        int nEventId;
        
        public EventParam(int nEventId)
        {
            this.nEventId = nEventId;
        }
    }
}
