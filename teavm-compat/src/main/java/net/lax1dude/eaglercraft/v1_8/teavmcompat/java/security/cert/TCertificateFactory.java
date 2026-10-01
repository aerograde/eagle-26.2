package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security.cert;

import java.io.InputStream;
import java.util.Collection;

/**
 * java.security.cert.CertificateFactory — link-only stub; getInstance throws
 * (X.509 parsing is dead code in the browser client).
 */
public class TCertificateFactory {

	protected TCertificateFactory() {
	}

	public static TCertificateFactory getInstance(String type) throws TCertificateException {
		throw new TCertificateException("no certificate support in the browser runtime: " + type);
	}

	public final TCertificate generateCertificate(InputStream inStream) throws TCertificateException {
		throw new TCertificateException("no certificate support in the browser runtime");
	}

	public final Collection<? extends TCertificate> generateCertificates(InputStream inStream)
			throws TCertificateException {
		throw new TCertificateException("no certificate support in the browser runtime");
	}

}
