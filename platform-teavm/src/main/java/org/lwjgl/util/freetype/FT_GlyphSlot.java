package org.lwjgl.util.freetype;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). No vanilla font uses
// the ttf provider (verified: space/bitmap only), so freetype is linkage-only; the
// error returns make any future ttf provider fail into FontManager.safeLoad's catch.

public class FT_GlyphSlot {

	protected FT_GlyphSlot() {
	}

	public FT_Vector advance() {
		return null;
	}

	public FT_Bitmap bitmap() {
		return null;
	}

	public int bitmap_left() {
		return 0;
	}

	public int bitmap_top() {
		return 0;
	}
}
