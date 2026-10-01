package org.lwjgl.glfw;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees
// this module). The browser reports a single synthetic 60 Hz mode at canvas size.
public class GLFWVidMode {

	private final int width;
	private final int height;
	private final int refreshRate;

	GLFWVidMode(final int width, final int height, final int refreshRate) {
		this.width = width;
		this.height = height;
		this.refreshRate = refreshRate;
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	public int redBits() {
		return 8;
	}

	public int greenBits() {
		return 8;
	}

	public int blueBits() {
		return 8;
	}

	public int refreshRate() {
		return this.refreshRate;
	}

	/**
	 * Struct-buffer shape used by Monitor.tryCreate; never populated on web
	 * (glfwGetVideoModes returns null). Extends CustomBuffer so position(int)
	 * carries the covariant return type the game bytecode calls.
	 */
	public static class Buffer extends org.lwjgl.system.CustomBuffer {

		public int width() {
			return 0;
		}

		public int height() {
			return 0;
		}

		public int redBits() {
			return 0;
		}

		public int greenBits() {
			return 0;
		}

		public int blueBits() {
			return 0;
		}

		public int refreshRate() {
			return 0;
		}
	}
}
