package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWCursorPosCallback implements GLFWCursorPosCallbackI {

	private final GLFWCursorPosCallbackI delegate;

	public GLFWCursorPosCallback(final GLFWCursorPosCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, double xpos, double ypos) {
		if (this.delegate != null) {
			this.delegate.invoke(window, xpos, ypos);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
