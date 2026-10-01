package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.net.ssl;

/**
 * javax.net.ssl.SSLSocketFactory — link-only stub (see TSSLContext; no
 * instance ever exists).
 */
public abstract class TSSLSocketFactory {

	protected TSSLSocketFactory() {
	}

	public static TSSLSocketFactory getDefault() {
		throw new UnsupportedOperationException("no TLS in the browser runtime");
	}

	public abstract String[] getDefaultCipherSuites();

	public abstract String[] getSupportedCipherSuites();

}
