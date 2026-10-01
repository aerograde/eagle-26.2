package com.ibm.icu.text;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.text.Bidi (see
 * {@link com.ibm.icu.lang.UCharacter} for why real icu4j is excluded).
 *
 * NOTE: deliberately does NOT extend java.text.Format / any ICU base — that is
 * exactly what re-introduces the polymorphic sweep we are eliminating.
 *
 * Behaviour is single-run left-to-right, which is what :game needs for the
 * default (en_us) locale: Font.prepareText only calls into bidi when
 * Language.isDefaultRightToLeft() is true, which never happens for LTR languages,
 * so these methods are compiled-but-not-invoked at the title screen.
 */
public final class Bidi {

	private final String text;

	public Bidi(String paragraph, int paragraphLevel) {
		this.text = paragraph == null ? "" : paragraph;
	}

	public void setReorderingMode(int reorderingMode) {
	}

	public int countRuns() {
		return 1;
	}

	public BidiRun getVisualRun(int runIndex) {
		return new BidiRun(0, text.length(), false);
	}

	/** No reordering for LTR text — return the source unchanged. */
	public String writeReordered(int options) {
		return text;
	}

	public boolean isLeftToRight() {
		return true;
	}

	public boolean isMixed() {
		return false;
	}

	public int getLength() {
		return text.length();
	}
}
