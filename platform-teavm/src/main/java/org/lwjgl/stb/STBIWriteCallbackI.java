package org.lwjgl.stb;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only).
@FunctionalInterface
public interface STBIWriteCallbackI {

	void invoke(long context, long data, int size);
}
