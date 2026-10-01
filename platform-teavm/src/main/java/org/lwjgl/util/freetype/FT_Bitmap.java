package org.lwjgl.util.freetype;

import java.nio.ByteBuffer;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). No vanilla font uses
// the ttf provider (verified: space/bitmap only), so freetype is linkage-only; the
// error returns make any future ttf provider fail into FontManager.safeLoad's catch.

public class FT_Bitmap {

	protected FT_Bitmap() {
	}

	public int width() {
		return 0;
	}

	public int rows() {
		return 0;
	}

	public byte pixel_mode() {
		return 0;
	}

	public ByteBuffer buffer(int capacity) {
		return null;
	}

	public int pitch() {
		return 0;
	}
}
