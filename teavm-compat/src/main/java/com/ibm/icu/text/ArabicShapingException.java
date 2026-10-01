package com.ibm.icu.text;

/**
 * Eagler 26.2 web-target facade for com.ibm.icu.text.ArabicShapingException
 * (see {@link com.ibm.icu.lang.UCharacter}). Never thrown by the facade shaper,
 * but :game's Font.bidirectionalShaping catches it explicitly, so the type must
 * exist with the same checked-exception shape.
 */
public class ArabicShapingException extends Exception {

	public ArabicShapingException() {
		super();
	}

	public ArabicShapingException(String message) {
		super(message);
	}
}
