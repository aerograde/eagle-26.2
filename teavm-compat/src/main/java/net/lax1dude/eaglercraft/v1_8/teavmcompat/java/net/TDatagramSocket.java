package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.io.Closeable;
import java.io.IOException;

/**
 * java.net.DatagramSocket — link-only stub. Raw UDP is impossible in the
 * browser; send/receive throw. Constructors are inert so catch/finally around
 * them (netty datagram probes) still links, but no socket is ever bound.
 */
public class TDatagramSocket implements Closeable {

	private static final String MSG = "no UDP sockets in the browser runtime";

	public TDatagramSocket() throws TSocketException {
	}

	public TDatagramSocket(int port) throws TSocketException {
	}

	public TDatagramSocket(int port, TInetAddress laddr) throws TSocketException {
	}

	public TDatagramSocket(TSocketAddress bindaddr) throws TSocketException {
	}

	public void send(TDatagramPacket p) throws IOException {
		throw new IOException(MSG);
	}

	public void receive(TDatagramPacket p) throws IOException {
		throw new IOException(MSG);
	}

	public void connect(TInetAddress address, int port) {
	}

	public void connect(TSocketAddress addr) throws TSocketException {
	}

	public void disconnect() {
	}

	public void bind(TSocketAddress addr) throws TSocketException {
	}

	public TInetAddress getInetAddress() {
		return null;
	}

	public int getPort() {
		return -1;
	}

	public int getLocalPort() {
		return -1;
	}

	public boolean isBound() {
		return false;
	}

	public boolean isConnected() {
		return false;
	}

	public boolean isClosed() {
		return true;
	}

	public void setSoTimeout(int timeout) throws TSocketException {
	}

	@Override
	public void close() {
	}

}
