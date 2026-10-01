package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.net.InetAddress (see plugin.JdkMethodInjector). netty's address
 * classifiers reach these; in the browser there is only the loopback identity of
 * a websocket peer, so the special-range predicates are all false.
 */
public final class InetAddressInject {

	public boolean isMulticastAddress() {
		return false;
	}

	public boolean isAnyLocalAddress() {
		return false;
	}

	public boolean isLinkLocalAddress() {
		return false;
	}

	public boolean isSiteLocalAddress() {
		return false;
	}

}
