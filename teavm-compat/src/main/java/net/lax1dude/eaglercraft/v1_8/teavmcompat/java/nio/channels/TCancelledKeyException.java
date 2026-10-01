package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

/**
 * java.nio.channels.CancelledKeyException — absent from teavm-classlib 0.13.
 * Extends IllegalStateException in the real JDK; only a catch-clause type on the
 * netty selector path that never runs.
 */
public class TCancelledKeyException extends IllegalStateException {

	public TCancelledKeyException() {
	}

}
