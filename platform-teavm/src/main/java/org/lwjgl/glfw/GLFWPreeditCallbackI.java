package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees this module).
@FunctionalInterface
public interface GLFWPreeditCallbackI {

	void invoke(long window, int preeditCount, long preeditString, int blockCount, long blockSizes, int focusedBlock, int caret);
}
