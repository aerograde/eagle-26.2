package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * java.net.Socket — link-only stub. Raw TCP is impossible in the browser;
 * connect and the stream accessors throw. netty/authlib reach these types for
 * type resolution only — the real transport is a WebSocket in platform-teavm.
 */
public class TSocket implements Closeable {

	private static final String MSG = "no raw TCP sockets in the browser runtime";

	public TSocket() {
	}

	public TSocket(String host, int port) throws TUnknownHostException, IOException {
		throw new IOException(MSG);
	}

	public TSocket(TInetAddress address, int port) throws IOException {
		throw new IOException(MSG);
	}

	public TSocket(String host, int port, TInetAddress localAddr, int localPort) throws IOException {
		throw new IOException(MSG);
	}

	public void connect(TSocketAddress endpoint) throws IOException {
		throw new IOException(MSG);
	}

	public void connect(TSocketAddress endpoint, int timeout) throws IOException {
		throw new IOException(MSG);
	}

	public void bind(TSocketAddress bindpoint) throws IOException {
		throw new IOException(MSG);
	}

	public InputStream getInputStream() throws IOException {
		throw new IOException(MSG);
	}

	public OutputStream getOutputStream() throws IOException {
		throw new IOException(MSG);
	}

	public TInetAddress getInetAddress() {
		return null;
	}

	public TInetAddress getLocalAddress() {
		return null;
	}

	public int getPort() {
		return 0;
	}

	public int getLocalPort() {
		return -1;
	}

	public TSocketAddress getRemoteSocketAddress() {
		return null;
	}

	public TSocketAddress getLocalSocketAddress() {
		return null;
	}

	public boolean isConnected() {
		return false;
	}

	public boolean isBound() {
		return false;
	}

	public boolean isClosed() {
		return true;
	}

	public boolean isInputShutdown() {
		return true;
	}

	public boolean isOutputShutdown() {
		return true;
	}

	public void setTcpNoDelay(boolean on) throws TSocketException {
	}

	public void setSoTimeout(int timeout) throws TSocketException {
	}

	public void setKeepAlive(boolean on) throws TSocketException {
	}

	public void setReuseAddress(boolean on) throws TSocketException {
	}

	public void setSendBufferSize(int size) throws TSocketException {
	}

	public void setReceiveBufferSize(int size) throws TSocketException {
	}

	public int getSoLinger() throws TSocketException {
		return -1;
	}

	public void setSoLinger(boolean on, int linger) throws TSocketException {
	}

	public void setTrafficClass(int tc) throws TSocketException {
	}

	public int getTrafficClass() throws TSocketException {
		return 0;
	}

	public int getReceiveBufferSize() throws TSocketException {
		return 0;
	}

	public int getSendBufferSize() throws TSocketException {
		return 0;
	}

	public boolean getTcpNoDelay() throws TSocketException {
		return false;
	}

	public boolean getKeepAlive() throws TSocketException {
		return false;
	}

	public boolean getReuseAddress() throws TSocketException {
		return false;
	}

	public void shutdownInput() throws IOException {
	}

	public void shutdownOutput() throws IOException {
	}

	@Override
	public void close() throws IOException {
	}

}
