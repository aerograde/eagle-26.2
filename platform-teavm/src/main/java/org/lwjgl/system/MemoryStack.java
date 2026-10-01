package org.lwjgl.system;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.PointerBuffer;

/**
 * Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). Not a real bump
 * allocator: every stack allocation is a tracked MemoryUtil.memAlloc (so
 * memAddress() works on stack buffers — NativeImage.read requires it), and
 * close() frees everything taken from this frame. getPointer() reports a huge
 * remaining size so callers prefer the stack path.
 */
public class MemoryStack implements AutoCloseable {

	private static final MemoryStack INSTANCE = new MemoryStack();

	private final List<ByteBuffer> frameAllocations = new ArrayList<>();

	public static MemoryStack stackPush() {
		return new MemoryStack();
	}

	public static MemoryStack stackGet() {
		return INSTANCE;
	}

	public int getPointer() {
		return 0x7FFFFFF0;
	}

	public ByteBuffer malloc(final int size) {
		ByteBuffer buf = MemoryUtil.memAlloc(size);
		this.frameAllocations.add(buf);
		return buf;
	}

	public ByteBuffer calloc(final int size) {
		return this.malloc(size);
	}

	public IntBuffer mallocInt(final int count) {
		return this.malloc(count * 4).asIntBuffer();
	}

	public IntBuffer callocInt(final int count) {
		return this.mallocInt(count);
	}

	public PointerBuffer mallocPointer(final int count) {
		return new PointerBuffer(count);
	}

	@Override
	public void close() {
		for (int i = this.frameAllocations.size() - 1; i >= 0; --i) {
			MemoryUtil.memFree(this.frameAllocations.get(i));
		}
		this.frameAllocations.clear();
	}
}
