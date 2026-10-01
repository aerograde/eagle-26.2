package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.UnknownHostException;

/**
 * Donor for java.net.Inet6Address (see plugin.JdkMethodInjector). Reached by
 * netty's address parsing on the raw-socket transport, which the browser never
 * uses (networking is websocket-only), so construction throws.
 */
public final class Inet6AddressInject {

	public static Inet6Address getByAddress(String host, byte[] addr, int scopeId) throws UnknownHostException {
		throw new UnsupportedOperationException("Inet6Address.getByAddress is unsupported in the browser runtime");
	}

	public static Inet6Address getByAddress(String host, byte[] addr, NetworkInterface nif) throws UnknownHostException {
		throw new UnsupportedOperationException("Inet6Address.getByAddress is unsupported in the browser runtime");
	}

}
