package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees
// this module). GLFWErrorScope compares wrappers by address() identity, so each
// instance carries a unique fake address.
public class GLFWErrorCallback implements GLFWErrorCallbackI {

	private static long nextAddress = 0x10000L;

	private final GLFWErrorCallbackI delegate;
	private final long address;

	protected GLFWErrorCallback(final GLFWErrorCallbackI delegate) {
		this.delegate = delegate;
		synchronized (GLFWErrorCallback.class) {
			this.address = nextAddress++;
		}
	}

	public static GLFWErrorCallback create(final GLFWErrorCallbackI delegate) {
		if (delegate instanceof GLFWErrorCallback cb) {
			return cb;
		}
		return new GLFWErrorCallback(delegate);
	}

	@Override
	public void invoke(final int error, final long description) {
		if (this.delegate != null) {
			this.delegate.invoke(error, description);
		}
	}

	public long address() {
		return this.address;
	}

	public void free() {
	}

	public void close() {
	}
}
