package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.crypto.spec;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.crypto.TSecretKey;

/** Immutable SecretKeySpec value object. */
public class TSecretKeySpec implements TSecretKey {
	private final byte[] key;
	private final String algorithm;
	public TSecretKeySpec(byte[] key, String algorithm) {
		if (key == null || algorithm == null) {
			throw new NullPointerException();
		}
		this.key = key.clone();
		this.algorithm = algorithm;
	}
	public String getAlgorithm() { return algorithm; }
	public String getFormat() { return "RAW"; }
	public byte[] getEncoded() { return key.clone(); }
}
