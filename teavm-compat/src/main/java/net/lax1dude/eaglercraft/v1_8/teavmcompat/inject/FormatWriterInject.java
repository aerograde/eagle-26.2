package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.io.IOException;
import java.util.UnknownFormatConversionException;

/**
 * REPLACE-mode donor for java.util.Formatter$FormatWriter (TeaVM's
 * TFormatter$FormatWriter). TeaVM 0.13's write() runs configureFormat() —
 * which consumes {@code defaultArgumentIndex++} — before it knows the
 * conversion, so the no-argument {@code %%} conversion eats an argument slot
 * and every later conversion reads one past its argument. Past the end of
 * the args array that read is JS {@code undefined}, which slips through the
 * {@code arg == null} guard and dies in getClass() ("Cannot read properties
 * of undefined (reading 'constructor')") — that killed the F3
 * 'minecraft:memory' entry ("Mem: %2d%% %03d/%03dMiB"). Replacing
 * formatValue lets the '%' case give the slot back. Abstract members are
 * javac placeholders resolved against the real class after reference
 * renaming; they are never copied.
 */
public abstract class FormatWriterInject {

	Appendable out;
	int defaultArgumentIndex;

	abstract void formatBoolean(char specifier, boolean upperCase) throws IOException;

	abstract void formatHex(char specifier, boolean upperCase) throws IOException;

	abstract void formatString(char specifier, boolean upperCase) throws IOException;

	abstract void formatChar(char specifier, boolean upperCase) throws IOException;

	abstract void formatDecimalInt(char specifier, boolean upperCase) throws IOException;

	abstract void formatRadixInt(char specifier, int radixLog2, boolean upperCase) throws IOException;

	abstract void formatFloat(char specifier, boolean upperCase) throws IOException;

	void formatValue(char specifier) throws IOException {
		switch (specifier) {
			case 'b':
				formatBoolean(specifier, false);
				break;
			case 'B':
				formatBoolean(specifier, true);
				break;
			case 'h':
				formatHex(specifier, false);
				break;
			case 'H':
				formatHex(specifier, true);
				break;
			case 's':
				formatString(specifier, false);
				break;
			case 'S':
				formatString(specifier, true);
				break;
			case 'c':
				formatChar(specifier, false);
				break;
			case 'C':
				formatChar(specifier, true);
				break;
			case 'd':
				formatDecimalInt(specifier, false);
				break;
			case 'D':
				formatDecimalInt(specifier, true);
				break;
			case 'o':
				formatRadixInt(specifier, 3, false);
				break;
			case 'O':
				formatRadixInt(specifier, 3, true);
				break;
			case 'x':
				formatRadixInt(specifier, 4, false);
				break;
			case 'X':
				formatRadixInt(specifier, 4, true);
				break;
			case 'f':
				formatFloat(specifier, false);
				break;
			case '%':
				// %% takes no argument: undo the slot configureFormat() consumed
				defaultArgumentIndex--;
				out.append("%");
				break;
			default:
				throw new UnknownFormatConversionException(String.valueOf(specifier));
		}
	}

}
