package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security;

/**
 * java.security.KeyStore — link-only stub; getInstance throws (msal/authlib
 * TLS plumbing is dead code in the browser client).
 */
public class TKeyStore {

	protected TKeyStore() {
	}

	public static TKeyStore getInstance(String type) throws TKeyStoreException {
		throw new TKeyStoreException("no KeyStore support in the browser runtime");
	}

	public static String getDefaultType() {
		return "jks";
	}

	public void load(java.io.InputStream stream, char[] password) {
		throw new UnsupportedOperationException("no KeyStore support in the browser runtime");
	}

}
