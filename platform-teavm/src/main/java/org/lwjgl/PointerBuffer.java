package org.lwjgl;

/**
 * Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only; desktop never sees
 * this module). Minimal pointer array used by Window.checkGlfwError,
 * MonitorManager, freetype init, and as a RenderPassBackend signature type.
 */
public class PointerBuffer {

	private final long[] values;
	private int position;
	private int limit;

	public PointerBuffer(final int capacity) {
		this.values = new long[capacity];
		this.limit = capacity;
	}

	public long get() {
		return this.values[this.position++];
	}

	public long get(final int index) {
		return this.values[index];
	}

	public PointerBuffer put(final int index, final long value) {
		this.values[index] = value;
		return this;
	}

	public PointerBuffer put(final long value) {
		this.values[this.position++] = value;
		return this;
	}

	public int limit() {
		return this.limit;
	}

	public PointerBuffer position(final int position) {
		this.position = position;
		return this;
	}

	public int position() {
		return this.position;
	}

	public int remaining() {
		return this.limit - this.position;
	}
}
