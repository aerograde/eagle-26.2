package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWWindowSizeCallback implements GLFWWindowSizeCallbackI {

	private final GLFWWindowSizeCallbackI delegate;

	public GLFWWindowSizeCallback(final GLFWWindowSizeCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, int width, int height) {
		if (this.delegate != null) {
			this.delegate.invoke(window, width, height);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
