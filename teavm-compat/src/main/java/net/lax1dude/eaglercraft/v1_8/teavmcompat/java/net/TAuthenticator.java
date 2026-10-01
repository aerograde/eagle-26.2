package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.net.PasswordAuthentication;

/**
 * java.net.Authenticator — link-only stub. Main.main sets a proxy-auth
 * Authenticator; with no proxy the default authenticator is never consulted in
 * the browser runtime.
 */
public abstract class TAuthenticator {

	private static TAuthenticator theAuthenticator;

	public TAuthenticator() {
	}

	public static synchronized void setDefault(TAuthenticator a) {
		theAuthenticator = a;
	}

	protected PasswordAuthentication getPasswordAuthentication() {
		return null;
	}
}
