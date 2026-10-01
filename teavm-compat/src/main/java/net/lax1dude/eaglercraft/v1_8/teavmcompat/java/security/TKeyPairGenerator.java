package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * java.security.KeyPairGenerator — link-only stub (see TKeyFactory; asymmetric
 * keypair generation is dead code in the browser client). getInstance throws so
 * no generator ever exists. generateKeyPair returns java.security.KeyPair, which
 * is out of scope for this batch, so it is omitted — only the reachable
 * getInstance/initialize surface is modelled.
 */
public abstract class TKeyPairGenerator {

	private static final String MSG = "no KeyPairGenerator support in the browser runtime";

	protected TKeyPairGenerator() {
	}

	public static TKeyPairGenerator getInstance(String algorithm) throws NoSuchAlgorithmException {
		throw new NoSuchAlgorithmException(MSG);
	}

	public String getAlgorithm() {
		return null;
	}

	public void initialize(int keysize) {
		throw new UnsupportedOperationException(MSG);
	}

	public void initialize(int keysize, SecureRandom random) {
		throw new UnsupportedOperationException(MSG);
	}

	public TKeyPair generateKeyPair() {
		throw new UnsupportedOperationException(MSG);
	}

}
