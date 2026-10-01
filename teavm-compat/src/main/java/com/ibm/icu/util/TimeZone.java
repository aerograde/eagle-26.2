package com.ibm.icu.util;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.util.TimeZone (see
 * {@link com.ibm.icu.text.Bidi}). Enough surface for LocalTime's time-zone codec
 * to compile and round-trip an id; the real tz database is not shipped to web.
 */
public class TimeZone {

	public static final TimeZone UNKNOWN_ZONE = new TimeZone("Etc/Unknown");

	private final String id;

	public TimeZone(String id) {
		this.id = id == null ? "" : id;
	}

	public static TimeZone getTimeZone(String id) {
		return new TimeZone(id);
	}

	public String getID() {
		return id;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof TimeZone && ((TimeZone) o).id.equals(this.id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}
}
