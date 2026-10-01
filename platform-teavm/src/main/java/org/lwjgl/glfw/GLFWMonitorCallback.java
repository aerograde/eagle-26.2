package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public class GLFWMonitorCallback implements GLFWMonitorCallbackI {

	private final GLFWMonitorCallbackI delegate;

	public GLFWMonitorCallback(final GLFWMonitorCallbackI delegate) {
		this.delegate = delegate;
	}

	@Override
	public void invoke(long monitor, int event) {
		if (this.delegate != null) {
			this.delegate.invoke(monitor, event);
		}
	}

	public void free() {
	}

	public void close() {
	}
}
