package net.lax1dude.eaglercraft.v1_8.teavmcompat.replace;

import java.net.SocketOption;

/**
 * Browser link surface for java.net.StandardSocketOptions. Raw sockets are not
 * available, so option identity is never observed; fields only need the JDK
 * signatures for netty's disabled NIO transport.
 */
public final class TStandardSocketOptions {

	public static final SocketOption<Boolean> SO_BROADCAST = null;
	public static final SocketOption<Boolean> SO_KEEPALIVE = null;
	public static final SocketOption<Integer> SO_SNDBUF = null;
	public static final SocketOption<Integer> SO_RCVBUF = null;
	public static final SocketOption<Boolean> SO_REUSEADDR = null;
	public static final SocketOption<Integer> SO_LINGER = null;
	public static final SocketOption<Integer> IP_TOS = null;
	public static final SocketOption<Boolean> TCP_NODELAY = null;

	private TStandardSocketOptions() {
	}
}
