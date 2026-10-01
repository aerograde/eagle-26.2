package com.ibm.icu.lang;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.lang.UCharacter.
 *
 * The real icu4j jar is excluded from the TeaVM classpath (target_teavm
 * build.gradle.kts): its Format/BreakIterator subclasses extend java.text.Format
 * and get swept into the reachable graph by String.format's polymorphic dispatch,
 * whose ICU NumberFormat.getShim() does an unconstrained Class.newInstance() that
 * pulls in the entire no-arg-constructable universe (netty NIO/epoll, blaze3d
 * monitor, ...) — exhausting the TeaVM optimizer's heap. This facade supplies only
 * the tiny surface :game touches (FormattedBidiReorder), with identity behaviour
 * that is correct for LTR (en_us) text; RTL shaping/reordering is never invoked at
 * runtime because Font.isBidirectional() == Language.isDefaultRightToLeft() is
 * false for the default locale.
 */
public final class UCharacter {

	private UCharacter() {
	}

	/** LTR text is not mirrored; return the codepoint unchanged. */
	public static int getMirror(int codePoint) {
		return codePoint;
	}
}
