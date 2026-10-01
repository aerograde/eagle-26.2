package org.slf4j;

/**
 * Facade replacing slf4j-api on the web classpath (Phase 3 Eagler method:
 * the real slf4j/log4j2 jars are excluded and all logging funnels through
 * this module — see docs/phase3-web-toolchain-map.md). Full 1.7-style
 * surface so any bytecode reference on the game classpath links.
 */
public interface Logger {

	String ROOT_LOGGER_NAME = "ROOT";

	String getName();

	boolean isTraceEnabled();
	boolean isTraceEnabled(Marker marker);
	void trace(String msg);
	void trace(String format, Object arg);
	void trace(String format, Object arg1, Object arg2);
	void trace(String format, Object... arguments);
	void trace(String msg, Throwable t);
	void trace(Marker marker, String msg);
	void trace(Marker marker, String format, Object arg);
	void trace(Marker marker, String format, Object arg1, Object arg2);
	void trace(Marker marker, String format, Object... arguments);
	void trace(Marker marker, String msg, Throwable t);

	boolean isDebugEnabled();
	boolean isDebugEnabled(Marker marker);
	void debug(String msg);
	void debug(String format, Object arg);
	void debug(String format, Object arg1, Object arg2);
	void debug(String format, Object... arguments);
	void debug(String msg, Throwable t);
	void debug(Marker marker, String msg);
	void debug(Marker marker, String format, Object arg);
	void debug(Marker marker, String format, Object arg1, Object arg2);
	void debug(Marker marker, String format, Object... arguments);
	void debug(Marker marker, String msg, Throwable t);

	boolean isInfoEnabled();
	boolean isInfoEnabled(Marker marker);
	void info(String msg);
	void info(String format, Object arg);
	void info(String format, Object arg1, Object arg2);
	void info(String format, Object... arguments);
	void info(String msg, Throwable t);
	void info(Marker marker, String msg);
	void info(Marker marker, String format, Object arg);
	void info(Marker marker, String format, Object arg1, Object arg2);
	void info(Marker marker, String format, Object... arguments);
	void info(Marker marker, String msg, Throwable t);

	boolean isWarnEnabled();
	boolean isWarnEnabled(Marker marker);
	void warn(String msg);
	void warn(String format, Object arg);
	void warn(String format, Object arg1, Object arg2);
	void warn(String format, Object... arguments);
	void warn(String msg, Throwable t);
	void warn(Marker marker, String msg);
	void warn(Marker marker, String format, Object arg);
	void warn(Marker marker, String format, Object arg1, Object arg2);
	void warn(Marker marker, String format, Object... arguments);
	void warn(Marker marker, String msg, Throwable t);

	boolean isErrorEnabled();
	boolean isErrorEnabled(Marker marker);
	void error(String msg);
	void error(String format, Object arg);
	void error(String format, Object arg1, Object arg2);
	void error(String format, Object... arguments);
	void error(String msg, Throwable t);
	void error(Marker marker, String msg);
	void error(Marker marker, String format, Object arg);
	void error(Marker marker, String format, Object arg1, Object arg2);
	void error(Marker marker, String format, Object... arguments);
	void error(Marker marker, String msg, Throwable t);

}
