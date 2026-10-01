package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWPreeditCallback implements GLFWPreeditCallbackI {

	private final GLFWPreeditCallbackI delegate;

	public GLFWPreeditCallback(final GLFWPreeditCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long window, int preeditCount, long preeditString, int blockCount, long blockSizes, int focusedBlock, int caret) {
		if (this.delegate != null) {
			this.delegate.invoke(window, preeditCount, preeditString, blockCount, blockSizes, focusedBlock, caret);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
