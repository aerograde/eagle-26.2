package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security.cert;

import java.util.Date;

/**
 * java.security.cert.X509Certificate — link-only stub (see TCertificate; no
 * instance ever exists).
 */
public abstract class TX509Certificate extends TCertificate {

	protected TX509Certificate() {
		super("X.509");
	}

	public abstract void checkValidity() throws TCertificateException;

	public abstract void checkValidity(Date date) throws TCertificateException;

	public abstract Date getNotBefore();

	public abstract Date getNotAfter();

	public abstract byte[] getSignature();

}
