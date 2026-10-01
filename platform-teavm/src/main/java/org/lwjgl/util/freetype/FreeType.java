package org.lwjgl.util.freetype;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;

// Eagler 26.2 Phase 3.3b LWJGL linkage stub (web build only). No vanilla font uses
// the ttf provider (verified: space/bitmap only), so freetype is linkage-only; the
// error returns make any future ttf provider fail into FontManager.safeLoad's catch.

public final class FreeType {

	public static final int FT_ENCODING_UNICODE = 0x756E6963;

	private FreeType() {
	}

	public static int FT_Init_FreeType(PointerBuffer alibrary) {
		return 1;
	}

	public static int FT_Done_Library(long library) {
		return 0;
	}

	public static int FT_New_Memory_Face(long library, ByteBuffer file_base, long face_index, PointerBuffer aface) {
		return 1;
	}

	public static String FT_Get_Font_Format(FT_Face face) {
		return null;
	}

	public static int FT_Select_Charmap(FT_Face face, int encoding) {
		return 1;
	}

	public static int FT_Done_Face(FT_Face face) {
		return 0;
	}

	public static int FT_Set_Pixel_Sizes(FT_Face face, int pixel_width, int pixel_height) {
		return 1;
	}

	public static void FT_Set_Transform(FT_Face face, FT_Matrix matrix, FT_Vector delta) {
	}

	public static long FT_Get_First_Char(FT_Face face, IntBuffer agindex) {
		return 0L;
	}

	public static long FT_Get_Next_Char(FT_Face face, long char_code, IntBuffer agindex) {
		return 0L;
	}

	public static int FT_Load_Glyph(FT_Face face, int glyph_index, int load_flags) {
		return 1;
	}

	public static String FT_Error_String(int error_code) {
		return "FreeType is not available in the browser";
	}
}
