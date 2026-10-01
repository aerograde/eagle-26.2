package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.security;

/**
 * java.security.Key — absent from teavm-classlib 0.13; referenced by the
 * PublicKey stub (authlib/Crypt reach these types but the crypto paths are
 * dead in the browser client).
 */
public interface TKey {

	String getAlgorithm();

	String getFormat();

	byte[] getEncoded();

}
