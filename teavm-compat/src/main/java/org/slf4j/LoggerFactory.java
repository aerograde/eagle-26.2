package org.slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import org.slf4j.event.Level;

/**
 * Provider-free LoggerFactory: no ServiceLoader binding pass (the real one
 * pulled in log4j-core, whose init dies on TeaVM's regex engine). Loggers
 * print to System.out/err; platform-teavm later installs a redirector via
 * {@link #setEaglerRedirector} to mirror lines into the lax1dude LogManager
 * pipeline (browser console overlay / boot menu log).
 */
public final class LoggerFactory {

	private static final Map<String, Logger> loggers = new HashMap<>();

	static volatile Level threshold = Level.INFO;
	static volatile BiConsumer<String, Boolean> eaglerRedirector = null;

	private static final ILoggerFactory factory = LoggerFactory::getLogger;

	private LoggerFactory() {
	}

	public static Logger getLogger(String name) {
		synchronized (loggers) {
			Logger ret = loggers.get(name);
			if (ret == null) {
				ret = new EaglerSlf4jLogger(name);
				loggers.put(name, ret);
			}
			return ret;
		}
	}

	public static Logger getLogger(Class<?> clazz) {
		return getLogger(clazz.getName());
	}

	public static ILoggerFactory getILoggerFactory() {
		return factory;
	}

	public static void setLevel(Level level) {
		if (level != null) {
			threshold = level;
		}
	}

	/** Hook for platform-teavm: receives each formatted line + isErr flag. */
	public static void setEaglerRedirector(BiConsumer<String, Boolean> redirector) {
		eaglerRedirector = redirector;
	}

}
