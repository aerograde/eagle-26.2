package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

/** java.nio.channels.SocketChannel — link-only stub (netty). */
public abstract class TSocketChannel extends TSelectableChannel
		implements ByteChannel, TGatheringByteChannel, TScatteringByteChannel, TNetworkChannel {

	protected TSocketChannel() {
	}

	public static TSocketChannel open() throws IOException {
		throw new IOException("no socket channels in the browser runtime");
	}

	public abstract Socket socket();

	public abstract boolean isConnected();

	public abstract boolean connect(SocketAddress remote) throws IOException;

	public abstract boolean finishConnect() throws IOException;

	public abstract TSocketChannel bind(SocketAddress local) throws IOException;

	public abstract TSocketChannel shutdownInput() throws IOException;

	public abstract TSocketChannel shutdownOutput() throws IOException;

	@Override
	public abstract int read(ByteBuffer dst) throws IOException;

	@Override
	public abstract long read(ByteBuffer[] dsts, int offset, int length) throws IOException;

	@Override
	public abstract int write(ByteBuffer src) throws IOException;

	@Override
	public abstract long write(ByteBuffer[] srcs, int offset, int length) throws IOException;
}
