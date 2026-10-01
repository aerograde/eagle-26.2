package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.IOException;
import java.nio.channels.Channel;

/** java.nio.channels.Selector — link-only stub (netty transport, never opened). */
public abstract class TSelector implements Channel {

	protected TSelector() {
	}

	public static TSelector open() throws IOException {
		throw new IOException("no NIO selectors in the browser runtime");
	}

	public abstract boolean isOpen();

	public abstract void close() throws IOException;

	// Additional java.nio.channels.Selector surface reached by the netty NIO
	// transport (never opened in the browser). Signatures reference the real JDK
	// names, which TeaVM maps back to the T-facades at link time.
	public abstract java.util.Set<java.nio.channels.SelectionKey> keys();

	public abstract java.util.Set<java.nio.channels.SelectionKey> selectedKeys();

	public abstract int select(long timeout) throws java.io.IOException;

	public abstract int selectNow() throws java.io.IOException;

	public abstract java.nio.channels.Selector wakeup();
}
