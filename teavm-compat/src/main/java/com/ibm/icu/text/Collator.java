package com.ibm.icu.text;

import java.util.Comparator;
import java.util.Locale;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.text.Collator (see
 * {@link com.ibm.icu.lang.UCharacter}). :game (CreateBuffetWorldScreen) only uses
 * it as a {@link Comparator} to sort biome names; a plain lexicographic order is a
 * correct-enough fallback for the web target and avoids ICU's CLDR collation data.
 */
public class Collator implements Comparator<Object> {

	public static Collator getInstance(Locale locale) {
		return new Collator();
	}

	public static Collator getInstance(com.ibm.icu.util.ULocale locale) {
		return new Collator();
	}

	public static Collator getInstance() {
		return new Collator();
	}

	@Override
	public int compare(Object a, Object b) {
		return String.valueOf(a).compareTo(String.valueOf(b));
	}

	public int compare(String a, String b) {
		return a.compareTo(b);
	}

	public void setStrength(int newStrength) {
	}
}
