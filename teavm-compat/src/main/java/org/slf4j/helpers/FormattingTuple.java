package org.slf4j.helpers;

public class FormattingTuple {

	public static final FormattingTuple NULL = new FormattingTuple(null);

	private final String message;
	private final Object[] argArray;
	private final Throwable throwable;

	public FormattingTuple(String message) {
		this(message, null, null);
	}

	public FormattingTuple(String message, Object[] argArray, Throwable throwable) {
		this.message = message;
		this.argArray = argArray;
		this.throwable = throwable;
	}

	public String getMessage() {
		return message;
	}

	public Object[] getArgArray() {
		return argArray;
	}

	public Throwable getThrowable() {
		return throwable;
	}

}
