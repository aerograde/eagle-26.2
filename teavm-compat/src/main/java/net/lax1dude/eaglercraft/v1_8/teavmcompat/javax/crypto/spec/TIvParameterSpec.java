package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.crypto.spec;

import java.security.spec.AlgorithmParameterSpec;

/** Immutable IvParameterSpec value object. */
public class TIvParameterSpec implements AlgorithmParameterSpec {
	private final byte[] iv;
	public TIvParameterSpec(byte[] iv) {
		this(iv, 0, iv.length);
	}
	public TIvParameterSpec(byte[] iv, int offset, int len) {
		if (offset < 0 || len < 0 || offset + len > iv.length) {
			throw new IllegalArgumentException("invalid IV range");
		}
		this.iv = new byte[len];
		System.arraycopy(iv, offset, this.iv, 0, len);
	}
	public byte[] getIV() { return iv.clone(); }
}
