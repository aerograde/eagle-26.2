package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.io.IOException;

/**
 * java.net.UnknownHostException — absent from teavm-classlib 0.13.
 */
public class TUnknownHostException extends IOException {

	public TUnknownHostException() {
	}

	public TUnknownHostException(String message) {
		super(message);
	}

}
