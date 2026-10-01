package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.net.ssl;

import java.security.NoSuchAlgorithmException;

/**
 * javax.net.ssl.SSLContext — link-only stub; TLS is the browser's job.
 */
public class TSSLContext {

	protected TSSLContext() {
	}

	public static TSSLContext getInstance(String protocol) throws NoSuchAlgorithmException {
		throw new NoSuchAlgorithmException("no TLS in the browser runtime");
	}

	public static TSSLContext getDefault() throws NoSuchAlgorithmException {
		throw new NoSuchAlgorithmException("no TLS in the browser runtime");
	}

	public final void init(TKeyManager[] km, TTrustManager[] tm, java.security.SecureRandom random) {
		// inert: getInstance always throws, so this is unreachable
	}

	public final TSSLSocketFactory getSocketFactory() {
		throw new UnsupportedOperationException("no TLS in the browser runtime");
	}

}
