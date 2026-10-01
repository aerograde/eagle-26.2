package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security;

import java.security.NoSuchAlgorithmException;
import java.nio.ByteBuffer;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.support.crypto.GeneralDigest;
import net.lax1dude.eaglercraft.v1_8.teavmcompat.support.crypto.MD5Digest;
import net.lax1dude.eaglercraft.v1_8.teavmcompat.support.crypto.SHA1Digest;
import net.lax1dude.eaglercraft.v1_8.teavmcompat.support.crypto.SHA256Digest;

/**
 * java.security.MessageDigest backed by the pure-java bouncy castle digests
 * ported from the EaglercraftX 1.8 workspace (support.crypto). Supports MD5,
 * SHA-1 and SHA-256 — everything MC 26.2's non-network paths use.
 */
public class TMessageDigest {

	private final GeneralDigest impl;
	private final String algorithm;

	private TMessageDigest(GeneralDigest impl, String algorithm) {
		this.impl = impl;
		this.algorithm = algorithm;
	}

	public static TMessageDigest getInstance(String algorithm) throws NoSuchAlgorithmException {
		switch (algorithm.toUpperCase()) {
		case "MD5":
			return new TMessageDigest(new MD5Digest(), "MD5");
		case "SHA":
		case "SHA1":
		case "SHA-1":
			return new TMessageDigest(new SHA1Digest(), "SHA-1");
		case "SHA256":
		case "SHA-256":
			return new TMessageDigest(new SHA256Digest(), "SHA-256");
		default:
			throw new NoSuchAlgorithmException(algorithm);
		}
	}

	public String getAlgorithm() {
		return algorithm;
	}

	public int getDigestLength() {
		return impl.getDigestSize();
	}

	public void update(byte input) {
		impl.update(input);
	}

	public void update(byte[] input) {
		impl.update(input, 0, input.length);
	}

	public void update(byte[] input, int offset, int len) {
		impl.update(input, offset, len);
	}

	public void update(ByteBuffer input) {
		while (input.hasRemaining()) {
			impl.update(input.get());
		}
	}

	public byte[] digest() {
		byte[] out = new byte[impl.getDigestSize()];
		impl.doFinal(out, 0);
		return out;
	}

	public byte[] digest(byte[] input) {
		update(input);
		return digest();
	}

	public int digest(byte[] buf, int offset, int len) {
		int size = impl.getDigestSize();
		if (len < size) {
			throw new IllegalArgumentException("output buffer region too small");
		}
		impl.doFinal(buf, offset);
		return size;
	}

	public void reset() {
		impl.reset();
	}

	public static boolean isEqual(byte[] digesta, byte[] digestb) {
		if (digesta == digestb) {
			return true;
		}
		if (digesta == null || digestb == null || digesta.length != digestb.length) {
			return false;
		}
		int result = 0;
		for (int i = 0; i < digesta.length; ++i) {
			result |= digesta[i] ^ digestb[i];
		}
		return result == 0;
	}

	@Override
	public String toString() {
		return algorithm + " Message Digest (eagler teavm-compat)";
	}

}
