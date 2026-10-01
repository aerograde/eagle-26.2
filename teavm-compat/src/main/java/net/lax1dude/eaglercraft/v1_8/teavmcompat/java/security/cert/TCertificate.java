package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security.cert;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security.TPublicKey;

/**
 * java.security.cert.Certificate — link-only stub; CertificateFactory throws,
 * so no instance ever exists (certificate verification is dead code in the
 * browser client).
 */
public abstract class TCertificate {

	private final String type;

	protected TCertificate(String type) {
		this.type = type;
	}

	public final String getType() {
		return type;
	}

	public abstract byte[] getEncoded() throws TCertificateException;

	public abstract TPublicKey getPublicKey();

	@Override
	public String toString() {
		return "Certificate(" + type + ")";
	}

}
