package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security;

/**
 * java.security.KeyPair — value stub (see TKeyPairGenerator). Asymmetric keypair
 * generation is dead code in the browser client, so no pair is ever produced;
 * present so KeyPairGenerator.generateKeyPair()'s return type resolves at link
 * time. Holds whatever public/private keys it is constructed with.
 */
public final class TKeyPair {

	private final TPublicKey publicKey;
	private final TPrivateKey privateKey;

	public TKeyPair(TPublicKey publicKey, TPrivateKey privateKey) {
		this.publicKey = publicKey;
		this.privateKey = privateKey;
	}

	public TPublicKey getPublic() {
		return publicKey;
	}

	public TPrivateKey getPrivate() {
		return privateKey;
	}
}
