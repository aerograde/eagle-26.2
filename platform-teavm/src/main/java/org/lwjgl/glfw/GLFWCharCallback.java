package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWCharCallback implements GLFWCharCallbackI {

	private final GLFWCharCallbackI delegate;

	public GLFWCharCallback(final GLFWCharCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, int codepoint) {
		if (this.delegate != null) {
			this.delegate.invoke(window, codepoint);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
