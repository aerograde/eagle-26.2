package org.lwjgl.system;

/**
 * Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). Base of the struct
 * buffer types (GLFWVidMode.Buffer etc.); game bytecode calls position(int) with
 * the covariant CustomBuffer return type, so it lives here.
 */
public abstract class CustomBuffer {

	protected int position;
	protected int limit;

	public int position() {
		return this.position;
	}

	public CustomBuffer position(final int position) {
		this.position = position;
		return this;
	}

	public int limit() {
		return this.limit;
	}

	public int remaining() {
		return this.limit - this.position;
	}
}
