package org.lwjgl.util.freetype;

import org.lwjgl.system.MemoryStack;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). No vanilla font uses
// the ttf provider (verified: space/bitmap only), so freetype is linkage-only; the
// error returns make any future ttf provider fail into FontManager.safeLoad's catch.

public class FT_Vector {

	protected FT_Vector() {
	}

	public static FT_Vector malloc(MemoryStack stack) {
		return new FT_Vector();
	}

	public FT_Vector set(long x, long y) {
		return this;
	}

	public long x() {
		return 0L;
	}

	public long y() {
		return 0L;
	}
}
