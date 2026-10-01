package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.net.NetworkInterface;
import java.net.SocketException;

/**
 * Donor for java.net.NetworkInterface (see plugin.JdkMethodInjector). The
 * browser exposes no network interfaces; getByName returns null (a legal JDK
 * "no such interface" answer) so LAN-scan code degrades quietly.
 */
public final class NetworkInterfaceInject {

	public static NetworkInterface getByName(String name) throws SocketException {
		return null;
	}

}
