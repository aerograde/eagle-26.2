package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.awt;

/**
 * java.awt.Font — fields-only stub (constants + trivial accessors); reached by
 * library code that never renders AWT text in the browser runtime.
 */
public class TFont {

	public static final int PLAIN = 0;
	public static final int BOLD = 1;
	public static final int ITALIC = 2;
	public static final int ROMAN_BASELINE = 0;
	public static final int CENTER_BASELINE = 1;
	public static final int HANGING_BASELINE = 2;

	public static final String DIALOG = "Dialog";
	public static final String DIALOG_INPUT = "DialogInput";
	public static final String SANS_SERIF = "SansSerif";
	public static final String SERIF = "Serif";
	public static final String MONOSPACED = "Monospaced";

	protected String name;
	protected int style;
	protected int size;

	public TFont(String name, int style, int size) {
		this.name = name == null ? "Default" : name;
		this.style = style;
		this.size = size;
	}

	public String getName() {
		return name;
	}

	public int getStyle() {
		return style;
	}

	public int getSize() {
		return size;
	}

	public boolean isPlain() {
		return style == PLAIN;
	}

	public boolean isBold() {
		return (style & BOLD) != 0;
	}

	public boolean isItalic() {
		return (style & ITALIC) != 0;
	}

	public TFont deriveFont(int style) {
		return new TFont(name, style, size);
	}

	public TFont deriveFont(float size) {
		return new TFont(name, style, (int) size);
	}

}
