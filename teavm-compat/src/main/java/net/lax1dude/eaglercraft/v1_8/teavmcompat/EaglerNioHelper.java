package net.lax1dude.eaglercraft.v1_8.teavmcompat;

import java.nio.file.Path;

/**
 * Runtime helpers backing the JdkMethodInjector's real (non-throwing) method bodies for
 * java.nio.file gaps in TeaVM's classlib.
 */
public final class EaglerNioHelper {

	private EaglerNioHelper() {
	}

	/**
	 * Real body for {@code Path.resolve(String first, String... more)} (TeaVM leaves it
	 * abstract, so it was a link-only throwing stub — reached by {@code Path.of}/
	 * {@code Paths.get}, which world loading uses). Chains the single-arg {@code resolve},
	 * which TeaVM implements independently (resolve(Path)), so no recursion.
	 */
	public static Path resolveVarargs(final Path base, final String first, final String[] more) {
		Path p = base.resolve(first);
		if (more != null) {
			for (final String m : more) {
				p = p.resolve(m);
			}
		}
		return p;
	}
}
