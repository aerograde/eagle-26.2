package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

/**
 * java.nio.channels.SelectionKey — link-only abstract stub. netty's NIO selector
 * loop references these keys, but no selector is ever opened in the browser
 * runtime (see TSelector), so no key is ever created.
 */
public abstract class TSelectionKey {

	public static final int OP_READ = 1 << 0;
	public static final int OP_WRITE = 1 << 2;
	public static final int OP_CONNECT = 1 << 3;
	public static final int OP_ACCEPT = 1 << 4;

	private Object attachment;

	protected TSelectionKey() {
	}

	public abstract TSelectableChannel channel();

	public abstract TSelector selector();

	public abstract boolean isValid();

	public abstract void cancel();

	public abstract int interestOps();

	public abstract TSelectionKey interestOps(int ops);

	public abstract int readyOps();

	public final Object attach(Object ob) {
		Object prev = attachment;
		attachment = ob;
		return prev;
	}

	public final Object attachment() {
		return attachment;
	}

	public final boolean isReadable() {
		return (readyOps() & OP_READ) != 0;
	}

	public final boolean isWritable() {
		return (readyOps() & OP_WRITE) != 0;
	}

	public final boolean isConnectable() {
		return (readyOps() & OP_CONNECT) != 0;
	}

	public final boolean isAcceptable() {
		return (readyOps() & OP_ACCEPT) != 0;
	}

}
