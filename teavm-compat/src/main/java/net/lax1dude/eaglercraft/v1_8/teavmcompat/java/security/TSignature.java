package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security;

import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;

/** java.security.Signature — link-only stub (chat signing is dead on the SP client). */
public abstract class TSignature {
	private static final String MSG = "no Signature support in the browser runtime";
	protected TSignature() {}
	public static TSignature getInstance(String algorithm) throws NoSuchAlgorithmException { throw new NoSuchAlgorithmException(MSG); }
	public final void initSign(PrivateKey privateKey) { throw new UnsupportedOperationException(MSG); }
	public final void initVerify(PublicKey publicKey) { throw new UnsupportedOperationException(MSG); }
	public final void update(byte[] data) { throw new UnsupportedOperationException(MSG); }
	public final byte[] sign() { throw new UnsupportedOperationException(MSG); }
	public final boolean verify(byte[] signature) { throw new UnsupportedOperationException(MSG); }
}
