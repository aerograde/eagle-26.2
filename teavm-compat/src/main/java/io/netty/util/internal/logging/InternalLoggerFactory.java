package io.netty.util.internal.logging;

/**
 * Same-FQN shadow of netty-common's InternalLoggerFactory (teavm-compat.jar
 * precedes netty-common.jar on the TeaVM classpath). Netty's original
 * newDefaultFactory falls back slf4j -> log4j2 -> jdk, keeping the
 * Log4J2Logger adapter reachable — which links against the real log4j-api
 * we exclude from the web classpath. This shadow pins slf4j (our facade)
 * unconditionally so the log4j2 branch is never reachable.
 */
public abstract class InternalLoggerFactory {

	private static volatile InternalLoggerFactory defaultFactory;

	public static InternalLoggerFactory getDefaultFactory() {
		InternalLoggerFactory f = defaultFactory;
		if (f == null) {
			f = Slf4JLoggerFactory.INSTANCE;
			defaultFactory = f;
		}
		return f;
	}

	public static void setDefaultFactory(InternalLoggerFactory factory) {
		if (factory == null) {
			throw new NullPointerException("defaultFactory");
		}
		defaultFactory = factory;
	}

	public static InternalLogger getInstance(Class<?> clazz) {
		return getInstance(clazz.getName());
	}

	public static InternalLogger getInstance(String name) {
		return getDefaultFactory().newInstance(name);
	}

	protected abstract InternalLogger newInstance(String name);

}
