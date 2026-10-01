package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.crypto;

import java.security.NoSuchAlgorithmException;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.crypto.spec.TSecretKeySpec;
import net.lax1dude.eaglercraft.v1_8.teavmcompat.support.crypto.MinecraftCrypto;

/** Minimal AES KeyGenerator used by the Minecraft login protocol. */
public class TKeyGenerator {
	private int keysize = 128;
	protected TKeyGenerator() {}
	public static TKeyGenerator getInstance(String algorithm) throws NoSuchAlgorithmException {
		if (!"AES".equalsIgnoreCase(algorithm)) {
			throw new NoSuchAlgorithmException("unsupported browser KeyGenerator: " + algorithm);
		}
		return new TKeyGenerator();
	}
	public final void init(int keysize) {
		if (keysize != 128) {
			throw new IllegalArgumentException("Minecraft browser AES only supports 128-bit keys");
		}
		this.keysize = keysize;
	}
	public final TSecretKey generateKey() {
		return new TSecretKeySpec(MinecraftCrypto.randomBytes(keysize >>> 3), "AES");
	}
}
