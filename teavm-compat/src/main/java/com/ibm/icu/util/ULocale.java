package com.ibm.icu.util;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.util.ULocale (see
 * {@link com.ibm.icu.text.Bidi}). Holds the locale id string; that is all
 * :game's LocalTime item model requires.
 */
public class ULocale {

	private final String localeID;

	public ULocale(String localeID) {
		this.localeID = localeID == null ? "" : localeID;
	}

	public String getName() {
		return localeID;
	}

	@Override
	public String toString() {
		return localeID;
	}
}
