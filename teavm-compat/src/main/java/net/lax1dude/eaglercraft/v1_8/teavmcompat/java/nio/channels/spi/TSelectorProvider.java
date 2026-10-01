package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels.spi;

/**
 * java.nio.channels.spi.SelectorProvider — link-only stub (reached from netty
 * transport probes); provider() throws so no selector machinery is pulled in.
 */
public abstract class TSelectorProvider {

	protected TSelectorProvider() {
	}

	public static TSelectorProvider provider() {
		throw new UnsupportedOperationException("no NIO selectors in the browser runtime");
	}

	// Phase 3.3b: netty transport references these (compile-linked, never called —
	// the data plane rides the IPC pipe, not real sockets).
	public java.nio.channels.ServerSocketChannel openServerSocketChannel() throws java.io.IOException {
		throw new UnsupportedOperationException("no server socket channels in the browser runtime");
	}

	public java.nio.channels.SocketChannel openSocketChannel() throws java.io.IOException {
		throw new UnsupportedOperationException("no socket channels in the browser runtime");
	}

}
