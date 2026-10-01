package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

/**
 * java.util.concurrent.TimeoutException — absent from teavm-classlib 0.13.
 */
public class TTimeoutException extends Exception {

	public TTimeoutException() {
	}

	public TTimeoutException(String message) {
		super(message);
	}

}
