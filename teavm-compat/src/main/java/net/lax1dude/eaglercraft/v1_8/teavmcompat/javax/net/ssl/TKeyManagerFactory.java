package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.net.ssl;

import java.security.NoSuchAlgorithmException;

/**
 * javax.net.ssl.KeyManagerFactory — link-only stub (see TSSLContext).
 */
public class TKeyManagerFactory {

	protected TKeyManagerFactory() {
	}

	public static TKeyManagerFactory getInstance(String algorithm) throws NoSuchAlgorithmException {
		throw new NoSuchAlgorithmException("no TLS in the browser runtime");
	}

	public static String getDefaultAlgorithm() {
		return "SunX509";
	}

	public void init(java.security.KeyStore ks, char[] password) {
		// inert: getInstance always throws, so this is unreachable
	}

	public TKeyManager[] getKeyManagers() {
		return new TKeyManager[0];
	}

}
