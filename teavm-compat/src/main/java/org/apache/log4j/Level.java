package org.apache.log4j;

/**
 * Minimal log4j 1.x Level (see Priority).
 */
public class Level extends Priority {

	public static final Level OFF = new Level(OFF_INT, "OFF");
	public static final Level FATAL = new Level(FATAL_INT, "FATAL");
	public static final Level ERROR = new Level(ERROR_INT, "ERROR");
	public static final Level WARN = new Level(WARN_INT, "WARN");
	public static final Level INFO = new Level(INFO_INT, "INFO");
	public static final Level DEBUG = new Level(DEBUG_INT, "DEBUG");
	public static final Level TRACE = new Level(5000, "TRACE");
	public static final Level ALL = new Level(ALL_INT, "ALL");

	protected Level(int level, String name) {
		super(level, name);
	}

	public static Level toLevel(String name) {
		return toLevel(name, DEBUG);
	}

	public static Level toLevel(String name, Level defaultLevel) {
		if (name == null) {
			return defaultLevel;
		}
		switch (name.toUpperCase()) {
		case "OFF":
			return OFF;
		case "FATAL":
			return FATAL;
		case "ERROR":
			return ERROR;
		case "WARN":
			return WARN;
		case "INFO":
			return INFO;
		case "DEBUG":
			return DEBUG;
		case "TRACE":
			return TRACE;
		case "ALL":
			return ALL;
		default:
			return defaultLevel;
		}
	}

}
