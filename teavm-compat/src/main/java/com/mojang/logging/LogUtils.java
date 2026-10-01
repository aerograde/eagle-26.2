package com.mojang.logging;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Replaces com.mojang:logging on the web classpath. The real getLogger()
 * names loggers via StackWalker.getCallerClass(), which TeaVM cannot
 * provide — all game loggers share the "Minecraft" name instead, matching
 * how the lax1dude facade logs upstream.
 */
public class LogUtils {

	public static final String FATAL_MARKER_ID = "FATAL";
	public static final Marker FATAL_MARKER = MarkerFactory.getMarker(FATAL_MARKER_ID);

	public static boolean isLoggerActive() {
		return true;
	}

	public static void configureRootLoggingLevel(org.slf4j.event.Level level) {
		LoggerFactory.setLevel(level);
	}

	public static Object defer(final Supplier<Object> result) {
		return new Object() {
			@Override
			public String toString() {
				return String.valueOf(result.get());
			}
		};
	}

	public static Logger getLogger() {
		return LoggerFactory.getLogger("Minecraft");
	}

}
