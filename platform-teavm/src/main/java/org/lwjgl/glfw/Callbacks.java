package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
public final class Callbacks {

	private Callbacks() {
	}

	public static void glfwFreeCallbacks(final long window) {
		GLFW.eaglerClearWindowCallbacks();
	}
}
