package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file;

import java.nio.file.Path;

/**
 * java.nio.file.PathMatcher — absent from teavm-classlib 0.13.
 */
public interface TPathMatcher {

	boolean matches(Path path);

}
