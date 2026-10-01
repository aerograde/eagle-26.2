package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.io.Closeable;
import java.io.IOException;

/**
 * java.net.ServerSocket — link-only stub. There is no listening TCP socket in
 * the browser runtime; bind/accept throw. Real inbound traffic (if any) comes
 * through the WebSocket transport in platform-teavm.
 */
public class TServerSocket implements Closeable {

	private static final String MSG = "no server sockets in the browser runtime";

	public TServerSocket() throws IOException {
	}

	public TServerSocket(int port) throws IOException {
		throw new IOException(MSG);
	}

	public TServerSocket(int port, int backlog) throws IOException {
		throw new IOException(MSG);
	}

	public TServerSocket(int port, int backlog, TInetAddress bindAddr) throws IOException {
		throw new IOException(MSG);
	}

	public void bind(TSocketAddress endpoint) throws IOException {
		throw new IOException(MSG);
	}

	public void bind(TSocketAddress endpoint, int backlog) throws IOException {
		throw new IOException(MSG);
	}

	public TSocket accept() throws IOException {
		throw new IOException(MSG);
	}

	public TInetAddress getInetAddress() {
		return null;
	}

	public int getLocalPort() {
		return -1;
	}

	public TSocketAddress getLocalSocketAddress() {
		return null;
	}

	public boolean isBound() {
		return false;
	}

	public boolean isClosed() {
		return true;
	}

	public void setSoTimeout(int timeout) throws TSocketException {
	}

	@Override
	public void close() throws IOException {
	}

}
