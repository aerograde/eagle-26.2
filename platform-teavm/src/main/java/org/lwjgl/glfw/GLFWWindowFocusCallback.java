package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWWindowFocusCallback implements GLFWWindowFocusCallbackI {

	private final GLFWWindowFocusCallbackI delegate;

	public GLFWWindowFocusCallback(final GLFWWindowFocusCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, boolean focused) {
		if (this.delegate != null) {
			this.delegate.invoke(window, focused);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
