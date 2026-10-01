package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

/**
 * java.util.concurrent.RejectedExecutionException — absent from
 * teavm-classlib 0.13. Standard RuntimeException subclass. Inline executors in
 * this module never actually reject, but the type must exist to link.
 */
public class TRejectedExecutionException extends RuntimeException {

	public TRejectedExecutionException() {
	}

	public TRejectedExecutionException(String message) {
		super(message);
	}

	public TRejectedExecutionException(String message, Throwable cause) {
		super(message, cause);
	}

	public TRejectedExecutionException(Throwable cause) {
		super(cause);
	}
}
