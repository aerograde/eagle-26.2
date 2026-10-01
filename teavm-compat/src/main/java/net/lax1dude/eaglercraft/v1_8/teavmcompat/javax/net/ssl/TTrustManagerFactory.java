package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.net.ssl;

import java.security.NoSuchAlgorithmException;

/**
 * javax.net.ssl.TrustManagerFactory — link-only stub (see TSSLContext).
 */
public class TTrustManagerFactory {

	protected TTrustManagerFactory() {
	}

	public static TTrustManagerFactory getInstance(String algorithm) throws NoSuchAlgorithmException {
		throw new NoSuchAlgorithmException("no TLS in the browser runtime");
	}

	public static String getDefaultAlgorithm() {
		return "PKIX";
	}

	public void init(java.security.KeyStore ks) {
		// inert: getInstance always throws, so this is unreachable
	}

	public TTrustManager[] getTrustManagers() {
		return new TTrustManager[0];
	}

}
