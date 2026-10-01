package org.apache.log4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal log4j 1.x Logger over System.out (see Category for the log surface).
 */
public class Logger extends Category {

	private static final Map<String, Logger> LOGGERS = new HashMap<>();
	private static final Logger ROOT = new Logger("root");

	protected Logger(String name) {
		super(name);
	}

	public static Logger getLogger(String name) {
		Logger logger = LOGGERS.get(name);
		if (logger == null) {
			logger = new Logger(name);
			LOGGERS.put(name, logger);
		}
		return logger;
	}

	public static Logger getLogger(Class<?> clazz) {
		return getLogger(clazz.getName());
	}

	public static Logger getRootLogger() {
		return ROOT;
	}

	public boolean isTraceEnabled() {
		return Level.TRACE.isGreaterOrEqual(level);
	}

	public void trace(Object message) {
		log(Level.TRACE, message, null);
	}

	public void trace(Object message, Throwable t) {
		log(Level.TRACE, message, t);
	}

}
