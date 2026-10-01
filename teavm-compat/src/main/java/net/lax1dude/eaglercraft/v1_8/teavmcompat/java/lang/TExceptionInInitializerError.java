package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

/**
 * java.lang.ExceptionInInitializerError — absent from teavm-classlib 0.13.
 */
public class TExceptionInInitializerError extends LinkageError {

	private final Throwable exception;

	public TExceptionInInitializerError() {
		this.exception = null;
	}

	public TExceptionInInitializerError(String message) {
		super(message);
		this.exception = null;
	}

	public TExceptionInInitializerError(Throwable thrown) {
		super(thrown == null ? null : thrown.toString());
		this.exception = thrown;
	}

	public Throwable getException() {
		return exception;
	}

	@Override
	public Throwable getCause() {
		return exception;
	}

}
