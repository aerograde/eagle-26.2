package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.util.Base64;

/**
 * Donor for java.util.Base64 (see plugin.JdkMethodInjector).
 */
public final class Base64Inject {

	/**
	 * Returns the plain encoder: line wrapping is dropped (the classlib
	 * encoder has no MIME mode). Unwrapped base64 remains parseable by every
	 * consumer MC 26.2 has (Crypt's MIME_ENCODER output is decoded, not
	 * compared textually).
	 */
	public static Base64.Encoder getMimeEncoder(int lineLength, byte[] lineSeparator) {
		return Base64.getEncoder();
	}

	public static Base64.Encoder getMimeEncoder() {
		return Base64.getEncoder();
	}

	public static Base64.Decoder getMimeDecoder() {
		return Base64.getDecoder();
	}

}
