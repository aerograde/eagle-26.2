package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWWindowPosCallback implements GLFWWindowPosCallbackI {

	private final GLFWWindowPosCallbackI delegate;

	public GLFWWindowPosCallback(final GLFWWindowPosCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, int xpos, int ypos) {
		if (this.delegate != null) {
			this.delegate.invoke(window, xpos, ypos);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
