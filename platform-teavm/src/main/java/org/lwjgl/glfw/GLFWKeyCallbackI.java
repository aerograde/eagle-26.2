package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
@FunctionalInterface
public interface GLFWKeyCallbackI {

	void invoke(long window, int key, int scancode, int action, int mods);
}
