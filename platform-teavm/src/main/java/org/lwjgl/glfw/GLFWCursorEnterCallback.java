package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWCursorEnterCallback implements GLFWCursorEnterCallbackI {

	private final GLFWCursorEnterCallbackI delegate;

	public GLFWCursorEnterCallback(final GLFWCursorEnterCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, boolean entered) {
		if (this.delegate != null) {
			this.delegate.invoke(window, entered);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
