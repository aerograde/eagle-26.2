package net.lax1dude.eaglercraft.v1_8.teavmcompat.javax.net.ssl;

/**
 * javax.net.ssl.HostnameVerifier — interface mirror (see TSSLContext).
 */
public interface THostnameVerifier {

	boolean verify(String hostname, TSSLSession session);

}
