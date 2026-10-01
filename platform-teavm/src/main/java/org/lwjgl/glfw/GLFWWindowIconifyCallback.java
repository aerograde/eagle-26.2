package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWWindowIconifyCallback implements GLFWWindowIconifyCallbackI {

	private final GLFWWindowIconifyCallbackI delegate;

	public GLFWWindowIconifyCallback(final GLFWWindowIconifyCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, boolean iconified) {
		if (this.delegate != null) {
			this.delegate.invoke(window, iconified);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
