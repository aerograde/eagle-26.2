package net.minecraft.client.main;

import java.io.File;
import joptsimple.ValueConverter;

/**
 * Eagler 26.2 web-target support: jopt-simple's {@code ofType(Class)} resolves a
 * value converter by REFLECTION (Reflection.findConverter looks up a
 * {@code valueOf(String)} method or a {@code (String)} constructor). TeaVM does
 * not expose those members reflectively, so {@code ofType(File.class)} throws
 * "class java.io.File is not a value type" the moment Main.main parses its args.
 *
 * These explicit converters are the reflection-free equivalent of what
 * {@code ofType} builds internally, so Main.main can bind the File/Integer
 * options without any reflection. Behaviour is identical on desktop.
 */
public final class WebValueConverters {

	private WebValueConverters() {
	}

	public static final ValueConverter<File> FILE = new ValueConverter<File>() {
		@Override
		public File convert(final String value) {
			return new File(value);
		}

		@Override
		public Class<? extends File> valueType() {
			return File.class;
		}

		@Override
		public String valuePattern() {
			return null;
		}
	};

	public static final ValueConverter<Integer> INTEGER = new ValueConverter<Integer>() {
		@Override
		public Integer convert(final String value) {
			return Integer.valueOf(value);
		}

		@Override
		public Class<? extends Integer> valueType() {
			return Integer.class;
		}

		@Override
		public String valuePattern() {
			return null;
		}
	};
}
