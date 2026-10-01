package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

/**
 * java.net.InetAddress — link-only stub; there is no raw networking in the
 * browser runtime (WebSocket transport lives in platform-teavm instead).
 */
public class TInetAddress {

	TInetAddress() {
	}

	public static TInetAddress getByName(String host) throws TUnknownHostException {
		throw new TUnknownHostException(host);
	}

	public static TInetAddress getLocalHost() throws TUnknownHostException {
		throw new TUnknownHostException("localhost");
	}

	public static TInetAddress getLoopbackAddress() {
		return new TInetAddress();
	}

	public String getHostAddress() {
		return "127.0.0.1";
	}

	public String getHostName() {
		return "localhost";
	}

	public byte[] getAddress() {
		return new byte[] { 127, 0, 0, 1 };
	}

	public boolean isLoopbackAddress() {
		return true;
	}

	// Phase 3.3b: reached from netty/authlib address construction (compile-linked,
	// never resolves a real host in the browser).
	//
	// Blocker #22: must return the CONCRETE JDK subtype by address length —
	// netty's NetUtilInitializations.createLocalhost4/6 does
	// `(Inet4Address) getByAddress(...)` / `(Inet6Address) getByAddress(...)`.
	// On the JS backend that checkcast is elided (the subtype is never
	// instantiated -> no metadata), but on wasm-gc the cast is ref.test-strict
	// and a bare InetAddress throws ClassCastException (-> memory-channel bind
	// fails). Returning the right subtype matches the JDK contract (4 bytes ->
	// Inet4Address, 16 bytes -> Inet6Address) and is correct on BOTH targets, so
	// this is UNCONDITIONAL (the isWebAssemblyGC() gate does not constant-fold
	// inside teavm-compat facades anyway).
	public static TInetAddress getByAddress(byte[] addr) throws TUnknownHostException {
		return forBytes(addr);
	}

	public static TInetAddress getByAddress(String host, byte[] addr) throws TUnknownHostException {
		return forBytes(addr);
	}

	private static TInetAddress forBytes(byte[] addr) {
		if (addr != null && addr.length == 16) {
			return new TInet6Address();
		}
		return new TInet4Address();
	}

}
