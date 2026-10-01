package org.lwjgl.glfw;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryStack;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees
// this module). Window.setIcon's GLFW path is dead on web (platform "null"), so
// this is linkage-only.
public class GLFWImage {

	public static Buffer malloc(final int count, final MemoryStack stack) {
		return new Buffer();
	}

	// Extends CustomBuffer so the inherited position(int) carries the covariant
	// CustomBuffer return type the game bytecode expects (mirrors GLFWVidMode.Buffer).
	public static class Buffer extends org.lwjgl.system.CustomBuffer {

		public Buffer width(final int width) {
			return this;
		}

		public Buffer height(final int height) {
			return this;
		}

		public Buffer pixels(final ByteBuffer pixels) {
			return this;
		}
	}
}
