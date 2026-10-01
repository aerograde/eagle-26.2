package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.io.IOException;

/**
 * java.net.SocketException — absent from teavm-classlib 0.13 (referenced by
 * catch clauses in netty/JNA network probes).
 */
public class TSocketException extends IOException {

	public TSocketException() {
	}

	public TSocketException(String message) {
		super(message);
	}

}
