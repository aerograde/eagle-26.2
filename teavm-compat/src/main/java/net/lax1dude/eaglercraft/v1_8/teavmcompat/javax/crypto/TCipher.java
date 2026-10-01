package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.crypto;

import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.spec.IvParameterSpec;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.support.crypto.MinecraftCrypto;

/** Protocol-specific RSA and AES/CFB8 Cipher implementation. */
public class TCipher {
	public static final int ENCRYPT_MODE = 1;
	public static final int DECRYPT_MODE = 2;
	private static final int RSA = 1;
	private static final int AES_CFB8 = 2;
	private final int kind;
	private int opmode;
	private Key key;
	private MinecraftCrypto.AesCfb8 aes;

	protected TCipher(int kind) {
		this.kind = kind;
	}

	public static TCipher getInstance(String transformation) throws NoSuchAlgorithmException {
		if ("RSA".equalsIgnoreCase(transformation) || "RSA/ECB/PKCS1Padding".equalsIgnoreCase(transformation)) {
			return new TCipher(RSA);
		}
		if ("AES/CFB8/NoPadding".equalsIgnoreCase(transformation)) {
			return new TCipher(AES_CFB8);
		}
		throw new NoSuchAlgorithmException("unsupported browser Cipher: " + transformation);
	}

	public final void init(int opmode, Key key) {
		this.opmode = opmode;
		this.key = key;
		if (kind == AES_CFB8) {
			byte[] encoded = key.getEncoded();
			this.aes = new MinecraftCrypto.AesCfb8(encoded, encoded, opmode == ENCRYPT_MODE);
		}
	}

	public final void init(int opmode, Key key, AlgorithmParameterSpec params) {
		this.opmode = opmode;
		this.key = key;
		if (kind != AES_CFB8 || !(params instanceof IvParameterSpec)) {
			throw new IllegalArgumentException("unsupported browser cipher parameters");
		}
		this.aes = new MinecraftCrypto.AesCfb8(key.getEncoded(), ((IvParameterSpec)params).getIV(),
				opmode == ENCRYPT_MODE);
	}

	public final byte[] doFinal(byte[] input) {
		if (kind == RSA) {
			if (opmode != ENCRYPT_MODE || key == null || !"RSA".equalsIgnoreCase(key.getAlgorithm())) {
				throw new UnsupportedOperationException("browser RSA supports public-key encryption only");
			}
			return MinecraftCrypto.rsaEncryptX509(key.getEncoded(), input);
		}
		byte[] result = new byte[input.length];
		update(input, 0, input.length, result, 0);
		return result;
	}

	public final int getOutputSize(int inputLen) {
		return inputLen;
	}

	public final int update(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) {
		if (kind != AES_CFB8 || aes == null) {
			throw new IllegalStateException("cipher is not initialized for streaming");
		}
		return aes.update(input, inputOffset, inputLen, output, outputOffset);
	}

	public final int update(byte[] input, int inputOffset, int inputLen, byte[] output) {
		return update(input, inputOffset, inputLen, output, 0);
	}

	public final int getBlockSize() {
		return kind == AES_CFB8 ? 16 : 0;
	}
}
