package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security;

import java.security.NoSuchAlgorithmException;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security.spec.TX509EncodedKeySpec;
import net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security.spec.TKeySpec;

/**
 * Minimal RSA KeyFactory used by the Minecraft login protocol.
 */
public class TKeyFactory {

	private final String algorithm;

	protected TKeyFactory() {
		this.algorithm = "RSA";
	}

	private TKeyFactory(String algorithm) {
		this.algorithm = algorithm;
	}

	public static TKeyFactory getInstance(String algorithm) throws NoSuchAlgorithmException {
		if (!"RSA".equalsIgnoreCase(algorithm)) {
			throw new NoSuchAlgorithmException("unsupported browser KeyFactory: " + algorithm);
		}
		return new TKeyFactory("RSA");
	}

	public final TPublicKey generatePublic(TKeySpec keySpec) {
		if (!(keySpec instanceof TX509EncodedKeySpec)) {
			throw new IllegalArgumentException("RSA public keys must use X509EncodedKeySpec");
		}
		return new EncodedPublicKey(algorithm, ((TX509EncodedKeySpec)keySpec).getEncoded());
	}

	public final TPrivateKey generatePrivate(TKeySpec keySpec) {
		throw new UnsupportedOperationException("no KeyFactory support in the browser runtime");
	}

	private static final class EncodedPublicKey implements TPublicKey {
		private final String algorithm;
		private final byte[] encoded;

		private EncodedPublicKey(String algorithm, byte[] encoded) {
			this.algorithm = algorithm;
			this.encoded = encoded.clone();
		}

		public String getAlgorithm() {
			return algorithm;
		}

		public String getFormat() {
			return "X.509";
		}

		public byte[] getEncoded() {
			return encoded.clone();
		}
	}
}
