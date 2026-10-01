package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.net.ssl;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * javax.net.ssl.HttpsURLConnection — link-only stub; no direct HTTP(S) client
 * exists in the browser runtime (fetch lives in platform-teavm).
 */
public abstract class THttpsURLConnection extends HttpURLConnection {

	protected THttpsURLConnection(URL url) {
		super(url);
	}

	public String getCipherSuite() {
		return "";
	}

	public void setHostnameVerifier(THostnameVerifier verifier) {
		// inert: the browser handles TLS
	}

	public void setSSLSocketFactory(TSSLSocketFactory factory) {
		// inert: the browser handles TLS
	}

}
