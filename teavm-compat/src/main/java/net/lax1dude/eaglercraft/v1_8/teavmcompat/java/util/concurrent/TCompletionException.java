package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

/**
 * java.util.concurrent.CompletionException — absent from teavm-classlib 0.13.
 * Thrown by CompletableFuture.join() on exceptional completion.
 */
public class TCompletionException extends RuntimeException {

	protected TCompletionException() {
	}

	protected TCompletionException(String message) {
		super(message);
	}

	public TCompletionException(String message, Throwable cause) {
		super(message, cause);
	}

	public TCompletionException(Throwable cause) {
		super(cause == null ? null : cause.toString(), cause);
	}

}
