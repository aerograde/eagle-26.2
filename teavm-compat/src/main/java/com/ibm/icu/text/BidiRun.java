package com.ibm.icu.text;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.text.BidiRun (see
 * {@link com.ibm.icu.lang.UCharacter}). Describes one visual run produced by
 * {@link Bidi#getVisualRun(int)}.
 */
public final class BidiRun {

	private final int start;
	private final int length;
	private final boolean oddRun;

	BidiRun(int start, int length, boolean oddRun) {
		this.start = start;
		this.length = length;
		this.oddRun = oddRun;
	}

	public int getStart() {
		return start;
	}

	public int getLength() {
		return length;
	}

	public int getLimit() {
		return start + length;
	}

	public boolean isOddRun() {
		return oddRun;
	}
}
