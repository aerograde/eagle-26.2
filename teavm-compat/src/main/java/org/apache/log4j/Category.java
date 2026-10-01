package org.apache.log4j;

/**
 * Minimal log4j 1.x Category — the class the 1.x API actually declares the
 * log methods on (Logger extends it), printing to System.out/err. Level
 * filtering: DEBUG and below are suppressed to keep the browser console sane.
 */
public class Category {

	protected final String name;
	protected Level level = Level.INFO;

	protected Category(String name) {
		this.name = name;
	}

	public final String getName() {
		return name;
	}

	public Level getLevel() {
		return level;
	}

	public void setLevel(Level level) {
		this.level = level;
	}

	public boolean isDebugEnabled() {
		return Level.DEBUG.isGreaterOrEqual(level);
	}

	public boolean isInfoEnabled() {
		return Level.INFO.isGreaterOrEqual(level);
	}

	public boolean isEnabledFor(Priority priority) {
		return priority.isGreaterOrEqual(level);
	}

	public void debug(Object message) {
		log(Level.DEBUG, message, null);
	}

	public void debug(Object message, Throwable t) {
		log(Level.DEBUG, message, t);
	}

	public void info(Object message) {
		log(Level.INFO, message, null);
	}

	public void info(Object message, Throwable t) {
		log(Level.INFO, message, t);
	}

	public void warn(Object message) {
		log(Level.WARN, message, null);
	}

	public void warn(Object message, Throwable t) {
		log(Level.WARN, message, t);
	}

	public void error(Object message) {
		log(Level.ERROR, message, null);
	}

	public void error(Object message, Throwable t) {
		log(Level.ERROR, message, t);
	}

	public void fatal(Object message) {
		log(Level.FATAL, message, null);
	}

	public void fatal(Object message, Throwable t) {
		log(Level.FATAL, message, t);
	}

	public void log(Priority priority, Object message) {
		log(priority, message, null);
	}

	/** log4j 1.x "callerFQCN" variant used by logging bridges. */
	public void log(String callerFQCN, Priority priority, Object message, Throwable t) {
		log(priority, message, t);
	}

	public void log(Priority priority, Object message, Throwable t) {
		if (!isEnabledFor(priority)) {
			return;
		}
		String line = "[" + priority + "] (" + name + ") " + message;
		if (priority.toInt() >= Priority.WARN_INT) {
			System.err.println(line);
		} else {
			System.out.println(line);
		}
		if (t != null) {
			t.printStackTrace();
		}
	}

}
