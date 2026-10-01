package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security.spec;

/**
 * java.security.spec.X509EncodedKeySpec — value-object mirror (see
 * TPKCS8EncodedKeySpec).
 */
public class TX509EncodedKeySpec implements TKeySpec {

	private final byte[] encoded;

	public TX509EncodedKeySpec(byte[] encodedKey) {
		this.encoded = encodedKey.clone();
	}

	public byte[] getEncoded() {
		return encoded.clone();
	}

	public final String getFormat() {
		return "X.509";
	}

}
