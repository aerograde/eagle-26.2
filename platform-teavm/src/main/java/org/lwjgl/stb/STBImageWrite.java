package org.lwjgl.stb;

/**
 * Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). PNG ENCODING is
 * not implemented yet — returning 0 makes NativeImage.writeToFile/asPngBytes
 * throw a caught IOException (screenshots/skin export paths only).
 * TODO(3.5+): pure-Java PNG encoder (deflate via java.util.zip exists).
 */
public final class STBImageWrite {

	private STBImageWrite() {
	}

	public static int nstbi_write_png_to_func(final long func, final long context, final int w, final int h, final int comp,
			final long data, final int stride_in_bytes) {
		return 0;
	}
}
