package com.ibm.icu.text;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.text.ArabicShaping (see
 * {@link com.ibm.icu.lang.UCharacter} for why real icu4j is excluded). Latin text
 * needs no Arabic joining, so shape() is identity; the option flags are ignored.
 */
public final class ArabicShaping {

	public ArabicShaping(int options) {
	}

	public String shape(String text) throws ArabicShapingException {
		return text;
	}

	public String shape(char[] source, int sourceStart, int sourceLength) throws ArabicShapingException {
		return new String(source, sourceStart, sourceLength);
	}
}
