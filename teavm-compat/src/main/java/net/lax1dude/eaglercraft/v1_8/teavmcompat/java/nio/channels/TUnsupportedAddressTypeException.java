package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

/**
 * java.nio.channels.UnsupportedAddressTypeException — absent from
 * teavm-classlib 0.13. Extends IllegalArgumentException in the real JDK; only a
 * catch-clause type on the netty bind path that never runs.
 */
public class TUnsupportedAddressTypeException extends IllegalArgumentException {

	public TUnsupportedAddressTypeException() {
	}

}
