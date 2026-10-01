package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWMouseButtonCallback implements GLFWMouseButtonCallbackI {

	private final GLFWMouseButtonCallbackI delegate;

	public GLFWMouseButtonCallback(final GLFWMouseButtonCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, int button, int action, int mods) {
		if (this.delegate != null) {
			this.delegate.invoke(window, button, action, mods);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
