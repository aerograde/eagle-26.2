package com.ibm.icu.util;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.util.Calendar (see
 * {@link com.ibm.icu.text.Bidi} note on why real icu4j is excluded). Only used to
 * back the clock/compass item model's date formatter, which is not driven at the
 * title screen.
 */
public class Calendar {

	public static Calendar getInstance(ULocale locale) {
		return new Calendar();
	}

	public static Calendar getInstance(TimeZone zone, ULocale locale) {
		return new Calendar();
	}
}
