package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security.spec;

/**
 * java.security.spec.PKCS8EncodedKeySpec — value-object mirror (key parsing
 * itself is dead code in the browser client).
 */
public class TPKCS8EncodedKeySpec implements TKeySpec {

	private final byte[] encoded;

	public TPKCS8EncodedKeySpec(byte[] encodedKey) {
		this.encoded = encodedKey.clone();
	}

	public byte[] getEncoded() {
		return encoded.clone();
	}

	public final String getFormat() {
		return "PKCS#8";
	}

}
