package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWDropCallback implements GLFWDropCallbackI {

	private final GLFWDropCallbackI delegate;

	public GLFWDropCallback(final GLFWDropCallbackI delegate) {
		this.delegate = delegate;
	}

	/** Reads the idx-th dropped path name; no native drop events exist on web. */
	public static String getName(final long names, final int index) {
		return "";
	}

	@Override
	public void invoke(final long window, final int count, final long names) {
		if (this.delegate != null) {
			this.delegate.invoke(window, count, names);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
