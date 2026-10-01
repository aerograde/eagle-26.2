package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

/**
 * java.net.InetSocketAddress — value stub. Reached from Main.main proxy setup
 * and netty bind/connect addresses; holds host+port but never resolves a real
 * address in the browser runtime.
 */
public class TInetSocketAddress extends TSocketAddress {

	private final String hostname;
	private final int port;

	public TInetSocketAddress(int port) {
		this.hostname = "0.0.0.0";
		this.port = port;
	}

	public TInetSocketAddress(String hostname, int port) {
		this.hostname = hostname;
		this.port = port;
	}

	public TInetSocketAddress(TInetAddress addr, int port) {
		this.hostname = addr == null ? "0.0.0.0" : addr.getHostAddress();
		this.port = port;
	}

	public static TInetSocketAddress createUnresolved(String host, int port) {
		return new TInetSocketAddress(host, port);
	}

	public int getPort() {
		return port;
	}

	public TInetAddress getAddress() {
		return null;
	}

	public String getHostName() {
		return hostname;
	}

	public String getHostString() {
		return hostname;
	}

	public boolean isUnresolved() {
		return true;
	}

	@Override
	public String toString() {
		return hostname + ":" + port;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof TInetSocketAddress)) return false;
		TInetSocketAddress other = (TInetSocketAddress) o;
		return port == other.port && hostname.equals(other.hostname);
	}

	@Override
	public int hashCode() {
		return hostname.hashCode() * 31 + port;
	}
}
