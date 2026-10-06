import java.text.SimpleDateFormat;
import java.util.Date;

/*
***************************************************************************
**          WiseStone Co. Ltd. CONFIDENTIAL AND PROPRIETARY
**        This source is the sole property of WiseStone Co. Ltd.
**      Reproduction or utilization of this source in whole or in part 
**    is forbidden without the written consent of WiseStone Co. Ltd.
***************************************************************************
**                 Copyright (c) 2007 WiseStone Co. Ltd.
**                           All Rights Reserved
***************************************************************************
** Revision History:
** Author                 Date          Version      Description of Changes
** ------------------------------------------------------------------------
** dhwoo     2010. 3. 12.        1.0              Created
*/

public class T
{
	private final static String POSTFIX = "[iookill]";
	private static volatile boolean misEnabled = true;

	// SimpleDateFormat은 스레드에 안전하지 않으므로 스레드마다 하나씩 쓴다.
	private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT = new ThreadLocal<SimpleDateFormat>()
	{
		protected SimpleDateFormat initialValue()
		{
			return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		}
	};

	public static void enable( Boolean isEnable )
	{
		misEnabled = isEnable;
	}

	public static void e()               { log(null); }
	public static void e( Object strMsg ) { log(strMsg); }
	public static void w()               { log(null); }
	public static void w( Object strMsg ) { log(strMsg); }
	public static void i()               { log(null); }
	public static void i( Object strMsg ) { log(strMsg); }
	public static void d()               { log(null); }
	public static void d( Object strMsg ) { log(strMsg); }

	// "시각[iookill][파일:메서드:줄]메시지" 형식으로 출력한다. 호출 위치는 스택에서 두 단계 위(T.x()를 부른 곳).
	private static void log( Object strMsg )
	{
		if ( !misEnabled )
			return;

		StackTraceElement callerElement = new Exception().getStackTrace()[2];
		System.out.println( getCurrentTime() +
				POSTFIX + "[" +
				callerElement.getFileName() + ":" +
				callerElement.getMethodName() + ":" +
				callerElement.getLineNumber() + "]" +
				(strMsg == null ? "" : strMsg) );
	}

	public static String getCurrentTime()
	{
		return DATE_FORMAT.get().format(new Date());
	}
}