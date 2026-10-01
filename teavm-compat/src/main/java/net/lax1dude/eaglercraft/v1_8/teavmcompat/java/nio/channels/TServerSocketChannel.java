package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;

/** java.nio.channels.ServerSocketChannel — link-only stub (netty). */
public abstract class TServerSocketChannel extends TSelectableChannel implements TNetworkChannel {

	protected TServerSocketChannel() {
	}

	public static TServerSocketChannel open() throws IOException {
		throw new IOException("no server socket channels in the browser runtime");
	}

	public abstract ServerSocket socket();

	public abstract TSocketChannel accept() throws IOException;

	public abstract TServerSocketChannel bind(SocketAddress local, int backlog) throws IOException;
}
