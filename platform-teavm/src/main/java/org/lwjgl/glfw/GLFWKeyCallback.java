package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWKeyCallback implements GLFWKeyCallbackI {

	private final GLFWKeyCallbackI delegate;

	public GLFWKeyCallback(final GLFWKeyCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, int key, int scancode, int action, int mods) {
		if (this.delegate != null) {
			this.delegate.invoke(window, key, scancode, action, mods);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
