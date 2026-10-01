package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.net;

/**
 * java.net.Proxy — value stub. Main.main builds a SOCKS proxy from --proxyHost;
 * with no proxy args it is NO_PROXY, which is the browser default.
 */
public class TProxy {

	public static final TProxy NO_PROXY = new TProxy(TProxy$Type.DIRECT, null);

	private final TProxy$Type type;
	private final TSocketAddress address;

	public TProxy(TProxy$Type type, TSocketAddress address) {
		this.type = type;
		this.address = address;
	}

	public TProxy$Type type() {
		return type;
	}

	public TSocketAddress address() {
		return address;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof TProxy)) return false;
		TProxy other = (TProxy) o;
		return type == other.type && (address == null ? other.address == null : address.equals(other.address));
	}

	@Override
	public int hashCode() {
		return type.hashCode() + (address == null ? 0 : address.hashCode());
	}
}
