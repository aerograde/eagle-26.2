package org.lwjgl.util.freetype;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). No vanilla font uses
// the ttf provider (verified: space/bitmap only), so freetype is linkage-only; the
// error returns make any future ttf provider fail into FontManager.safeLoad's catch.

public class FT_Face {

	protected FT_Face() {
	}

	public static FT_Face create(long address) {
		return new FT_Face();
	}

	public FT_GlyphSlot glyph() {
		return null;
	}
}
