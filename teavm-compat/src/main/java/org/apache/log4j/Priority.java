package org.apache.log4j;

/**
 * Minimal log4j 1.x Priority — part of the thin System.out facade this module
 * ships for library code that still links against the ancient log4j 1.x API
 * (plain classes, not java.*, so no TeaVM mapping tricks are needed).
 */
public class Priority {

	public static final int OFF_INT = Integer.MAX_VALUE;
	public static final int FATAL_INT = 50000;
	public static final int ERROR_INT = 40000;
	public static final int WARN_INT = 30000;
	public static final int INFO_INT = 20000;
	public static final int DEBUG_INT = 10000;
	public static final int ALL_INT = Integer.MIN_VALUE;

	private final int level;
	private final String name;

	protected Priority(int level, String name) {
		this.level = level;
		this.name = name;
	}

	public final int toInt() {
		return level;
	}

	public final boolean isGreaterOrEqual(Priority other) {
		return level >= other.level;
	}

	@Override
	public final String toString() {
		return name;
	}

	@Override
	public final boolean equals(Object o) {
		return o instanceof Priority && ((Priority) o).level == level;
	}

	@Override
	public final int hashCode() {
		return level;
	}

}
