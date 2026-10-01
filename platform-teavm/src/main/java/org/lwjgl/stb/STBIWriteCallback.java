package org.lwjgl.stb;

import java.nio.ByteBuffer;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap;

/**
 * Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). Subclassed by
 * NativeImage.WriteCallback; PNG writing is stubbed out (STBImageWrite returns
 * failure) so invoke never fires, but the shape must link.
 */
public abstract class STBIWriteCallback implements STBIWriteCallbackI {

	@Override
	public abstract void invoke(long context, long data, int size);

	public static ByteBuffer getData(final long data, final int size) {
		return EaglerFakeHeap.slice(data, size);
	}

	public long address() {
		return 0L;
	}

	public void free() {
	}

	public void close() {
	}
}
