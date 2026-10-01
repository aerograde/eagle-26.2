package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security;

import java.security.GeneralSecurityException;

/**
 * java.security.KeyStoreException — absent from teavm-classlib 0.13.
 */
public class TKeyStoreException extends GeneralSecurityException {

	public TKeyStoreException() {
	}

	public TKeyStoreException(String message) {
		super(message);
	}

}
