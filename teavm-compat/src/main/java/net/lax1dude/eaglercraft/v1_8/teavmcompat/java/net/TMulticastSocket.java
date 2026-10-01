package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

import java.io.IOException;

/**
 * java.net.MulticastSocket — link-only stub (see TDatagramSocket). Multicast
 * group membership is impossible in the browser; join/leave throw.
 */
public class TMulticastSocket extends TDatagramSocket {

	private static final String MSG = "no multicast sockets in the browser runtime";

	public TMulticastSocket() throws IOException {
	}

	public TMulticastSocket(int port) throws IOException {
	}

	public TMulticastSocket(TSocketAddress bindaddr) throws IOException {
	}

	public void joinGroup(TInetAddress mcastaddr) throws IOException {
		throw new IOException(MSG);
	}

	public void leaveGroup(TInetAddress mcastaddr) throws IOException {
		throw new IOException(MSG);
	}

	public void setTimeToLive(int ttl) throws IOException {
	}

	public int getTimeToLive() throws IOException {
		return 1;
	}

	public void setLoopbackMode(boolean disable) throws TSocketException {
	}

}
