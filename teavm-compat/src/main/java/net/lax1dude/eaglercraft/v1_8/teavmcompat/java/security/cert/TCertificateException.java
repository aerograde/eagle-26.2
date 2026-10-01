package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security.cert;

import java.security.GeneralSecurityException;

/**
 * java.security.cert.CertificateException — absent from teavm-classlib 0.13.
 */
public class TCertificateException extends GeneralSecurityException {

	public TCertificateException() {
	}

	public TCertificateException(String message) {
		super(message);
	}

	public TCertificateException(String message, Throwable cause) {
		super(message, cause);
	}

	public TCertificateException(Throwable cause) {
		super(cause);
	}

}
