package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWScrollCallback implements GLFWScrollCallbackI {

	private final GLFWScrollCallbackI delegate;

	public GLFWScrollCallback(final GLFWScrollCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, double xoffset, double yoffset) {
		if (this.delegate != null) {
			this.delegate.invoke(window, xoffset, yoffset);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
