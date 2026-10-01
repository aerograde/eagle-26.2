package org.slf4j;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.slf4j.event.Level;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import com.mojang.logging.LogListeners;

/**
 * The single Logger implementation behind the org.slf4j facade. Format
 * mirrors the lax1dude log4j facade: [HH:mm:ss][Thread/LEVEL][name]: msg
 */
final class EaglerSlf4jLogger implements Logger {

	private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
	private static final Date dateInstance = new Date();
	private static final Object logLock = new Object();

	// Captured before Bootstrap.wrapStreams() replaces System.out/err with
	// LoggedPrintStream (which routes back into this logger — printing to
	// the live streams would recurse infinitely). Mirrors how log4j's
	// console appender holds the real fd.
	private static final PrintStream stdout = System.out;
	private static final PrintStream stderr = System.err;

	private final String name;

	EaglerSlf4jLogger(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return name;
	}

	private boolean enabled(Level level) {
		return level.toInt() >= LoggerFactory.threshold.toInt();
	}

	private void dispatch(Level level, String msg, Throwable t) {
		if (!enabled(level)) {
			return;
		}
		boolean isErr = level.toInt() >= Level.WARN.toInt();
		PrintStream stream = isErr ? stderr : stdout;
		synchronized (logLock) {
			dateInstance.setTime(System.currentTimeMillis());
			String line = "[" + fmt.format(dateInstance) + "][" + Thread.currentThread().getName() + "/"
					+ level.toString() + "][" + name + "]: " + msg;
			stream.println(line);
			if (t != null) {
				t.printStackTrace(stream);
			}
			LogListeners.fireLogEvent(line, level);
			if (LoggerFactory.eaglerRedirector != null) {
				LoggerFactory.eaglerRedirector.accept(line, isErr);
				if (t != null) {
					LoggerFactory.eaglerRedirector.accept(t.toString(), isErr);
				}
			}
		}
	}

	private void fmt(Level level, String format, Object... args) {
		if (!enabled(level)) {
			return;
		}
		FormattingTuple tuple = MessageFormatter.arrayFormat(format, args);
		dispatch(level, tuple.getMessage(), tuple.getThrowable());
	}

	@Override
	public boolean isTraceEnabled() {
		return enabled(Level.TRACE);
	}

	@Override
	public boolean isTraceEnabled(Marker marker) {
		return enabled(Level.TRACE);
	}

	@Override
	public void trace(String msg) {
		dispatch(Level.TRACE, msg, null);
	}

	@Override
	public void trace(String format, Object arg) {
		fmt(Level.TRACE, format, arg);
	}

	@Override
	public void trace(String format, Object arg1, Object arg2) {
		fmt(Level.TRACE, format, arg1, arg2);
	}

	@Override
	public void trace(String format, Object... arguments) {
		fmt(Level.TRACE, format, arguments);
	}

	@Override
	public void trace(String msg, Throwable t) {
		dispatch(Level.TRACE, msg, t);
	}

	@Override
	public void trace(Marker marker, String msg) {
		dispatch(Level.TRACE, msg, null);
	}

	@Override
	public void trace(Marker marker, String format, Object arg) {
		fmt(Level.TRACE, format, arg);
	}

	@Override
	public void trace(Marker marker, String format, Object arg1, Object arg2) {
		fmt(Level.TRACE, format, arg1, arg2);
	}

	@Override
	public void trace(Marker marker, String format, Object... arguments) {
		fmt(Level.TRACE, format, arguments);
	}

	@Override
	public void trace(Marker marker, String msg, Throwable t) {
		dispatch(Level.TRACE, msg, t);
	}

	@Override
	public boolean isDebugEnabled() {
		return enabled(Level.DEBUG);
	}

	@Override
	public boolean isDebugEnabled(Marker marker) {
		return enabled(Level.DEBUG);
	}

	@Override
	public void debug(String msg) {
		dispatch(Level.DEBUG, msg, null);
	}

	@Override
	public void debug(String format, Object arg) {
		fmt(Level.DEBUG, format, arg);
	}

	@Override
	public void debug(String format, Object arg1, Object arg2) {
		fmt(Level.DEBUG, format, arg1, arg2);
	}

	@Override
	public void debug(String format, Object... arguments) {
		fmt(Level.DEBUG, format, arguments);
	}

	@Override
	public void debug(String msg, Throwable t) {
		dispatch(Level.DEBUG, msg, t);
	}

	@Override
	public void debug(Marker marker, String msg) {
		dispatch(Level.DEBUG, msg, null);
	}

	@Override
	public void debug(Marker marker, String format, Object arg) {
		fmt(Level.DEBUG, format, arg);
	}

	@Override
	public void debug(Marker marker, String format, Object arg1, Object arg2) {
		fmt(Level.DEBUG, format, arg1, arg2);
	}

	@Override
	public void debug(Marker marker, String format, Object... arguments) {
		fmt(Level.DEBUG, format, arguments);
	}

	@Override
	public void debug(Marker marker, String msg, Throwable t) {
		dispatch(Level.DEBUG, msg, t);
	}

	@Override
	public boolean isInfoEnabled() {
		return enabled(Level.INFO);
	}

	@Override
	public boolean isInfoEnabled(Marker marker) {
		return enabled(Level.INFO);
	}

	@Override
	public void info(String msg) {
		dispatch(Level.INFO, msg, null);
	}

	@Override
	public void info(String format, Object arg) {
		fmt(Level.INFO, format, arg);
	}

	@Override
	public void info(String format, Object arg1, Object arg2) {
		fmt(Level.INFO, format, arg1, arg2);
	}

	@Override
	public void info(String format, Object... arguments) {
		fmt(Level.INFO, format, arguments);
	}

	@Override
	public void info(String msg, Throwable t) {
		dispatch(Level.INFO, msg, t);
	}

	@Override
	public void info(Marker marker, String msg) {
		dispatch(Level.INFO, msg, null);
	}

	@Override
	public void info(Marker marker, String format, Object arg) {
		fmt(Level.INFO, format, arg);
	}

	@Override
	public void info(Marker marker, String format, Object arg1, Object arg2) {
		fmt(Level.INFO, format, arg1, arg2);
	}

	@Override
	public void info(Marker marker, String format, Object... arguments) {
		fmt(Level.INFO, format, arguments);
	}

	@Override
	public void info(Marker marker, String msg, Throwable t) {
		dispatch(Level.INFO, msg, t);
	}

	@Override
	public boolean isWarnEnabled() {
		return enabled(Level.WARN);
	}

	@Override
	public boolean isWarnEnabled(Marker marker) {
		return enabled(Level.WARN);
	}

	@Override
	public void warn(String msg) {
		dispatch(Level.WARN, msg, null);
	}

	@Override
	public void warn(String format, Object arg) {
		fmt(Level.WARN, format, arg);
	}

	@Override
	public void warn(String format, Object arg1, Object arg2) {
		fmt(Level.WARN, format, arg1, arg2);
	}

	@Override
	public void warn(String format, Object... arguments) {
		fmt(Level.WARN, format, arguments);
	}

	@Override
	public void warn(String msg, Throwable t) {
		dispatch(Level.WARN, msg, t);
	}

	@Override
	public void warn(Marker marker, String msg) {
		dispatch(Level.WARN, msg, null);
	}

	@Override
	public void warn(Marker marker, String format, Object arg) {
		fmt(Level.WARN, format, arg);
	}

	@Override
	public void warn(Marker marker, String format, Object arg1, Object arg2) {
		fmt(Level.WARN, format, arg1, arg2);
	}

	@Override
	public void warn(Marker marker, String format, Object... arguments) {
		fmt(Level.WARN, format, arguments);
	}

	@Override
	public void warn(Marker marker, String msg, Throwable t) {
		dispatch(Level.WARN, msg, t);
	}

	@Override
	public boolean isErrorEnabled() {
		return enabled(Level.ERROR);
	}

	@Override
	public boolean isErrorEnabled(Marker marker) {
		return enabled(Level.ERROR);
	}

	@Override
	public void error(String msg) {
		dispatch(Level.ERROR, msg, null);
	}

	@Override
	public void error(String format, Object arg) {
		fmt(Level.ERROR, format, arg);
	}

	@Override
	public void error(String format, Object arg1, Object arg2) {
		fmt(Level.ERROR, format, arg1, arg2);
	}

	@Override
	public void error(String format, Object... arguments) {
		fmt(Level.ERROR, format, arguments);
	}

	@Override
	public void error(String msg, Throwable t) {
		dispatch(Level.ERROR, msg, t);
	}

	@Override
	public void error(Marker marker, String msg) {
		dispatch(Level.ERROR, msg, null);
	}

	@Override
	public void error(Marker marker, String format, Object arg) {
		fmt(Level.ERROR, format, arg);
	}

	@Override
	public void error(Marker marker, String format, Object arg1, Object arg2) {
		fmt(Level.ERROR, format, arg1, arg2);
	}

	@Override
	public void error(Marker marker, String format, Object... arguments) {
		fmt(Level.ERROR, format, arguments);
	}

	@Override
	public void error(Marker marker, String msg, Throwable t) {
		dispatch(Level.ERROR, msg, t);
	}

}
