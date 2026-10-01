package org.slf4j.event;

/** Constant order matches real slf4j — game code switches over ordinal(). */
public enum Level {

	ERROR(40, "ERROR"),
	WARN(30, "WARN"),
	INFO(20, "INFO"),
	DEBUG(10, "DEBUG"),
	TRACE(0, "TRACE");

	private final int levelInt;
	private final String levelStr;

	Level(int levelInt, String levelStr) {
		this.levelInt = levelInt;
		this.levelStr = levelStr;
	}

	public int toInt() {
		return levelInt;
	}

	@Override
	public String toString() {
		return levelStr;
	}

}
