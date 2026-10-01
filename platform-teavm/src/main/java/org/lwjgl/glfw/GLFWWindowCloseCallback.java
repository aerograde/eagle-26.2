package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWWindowCloseCallback implements GLFWWindowCloseCallbackI {

	private final GLFWWindowCloseCallbackI delegate;

	public GLFWWindowCloseCallback(final GLFWWindowCloseCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window) {
		if (this.delegate != null) {
			this.delegate.invoke(window);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
