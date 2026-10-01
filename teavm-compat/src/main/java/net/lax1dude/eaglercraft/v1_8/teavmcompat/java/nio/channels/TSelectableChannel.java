package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

import java.io.IOException;
import java.nio.channels.Channel;

/** java.nio.channels.SelectableChannel — link-only abstract stub (netty). */
public abstract class TSelectableChannel implements Channel {

	protected TSelectableChannel() {
	}

	public abstract boolean isOpen();

	public abstract void close() throws IOException;

	public TSelectableChannel configureBlocking(boolean block) throws IOException {
		if (block) {
			throw new UnsupportedOperationException("blocking NIO channels are unavailable in the browser runtime");
		}
		return this;
	}

	// Dead path: no selector is ever opened in the browser runtime, so a channel
	// is never registered and has no key for any selector.
	public TSelectionKey keyFor(TSelector sel) {
		return null;
	}

	// Dead path: netty NIO transport never registers in the browser runtime.
	public TSelectionKey register(TSelector sel, int ops, Object att) throws java.nio.channels.ClosedChannelException {
		throw new UnsupportedOperationException("no NIO selectors in the browser runtime");
	}
}
