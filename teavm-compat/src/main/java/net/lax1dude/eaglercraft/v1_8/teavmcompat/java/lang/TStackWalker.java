package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * java.lang.StackWalker — inert implementation: walks an empty stack (TeaVM
 * has no cheap stack introspection). Callers in MC 26.2 (TracyZoneFiller,
 * VulkanDebug) and log4j2 only use it for diagnostics decoration.
 */
public final class TStackWalker {

	public enum Option {
		RETAIN_CLASS_REFERENCE,
		SHOW_REFLECT_FRAMES,
		SHOW_HIDDEN_FRAMES
	}

	/** Mirrors java.lang.StackWalker.StackFrame; no instances are produced. */
	public interface StackFrame {

		String getClassName();

		String getMethodName();

		Class<?> getDeclaringClass();

		int getByteCodeIndex();

		String getFileName();

		int getLineNumber();

		boolean isNativeMethod();

		StackTraceElement toStackTraceElement();

	}

	private static final TStackWalker INSTANCE = new TStackWalker();

	private TStackWalker() {
	}

	public static TStackWalker getInstance() {
		return INSTANCE;
	}

	public static TStackWalker getInstance(Option option) {
		return INSTANCE;
	}

	public static TStackWalker getInstance(Set<Option> options) {
		return INSTANCE;
	}

	public static TStackWalker getInstance(Set<Option> options, int estimateDepth) {
		return INSTANCE;
	}

	public <T> T walk(Function<? super Stream<StackFrame>, ? extends T> function) {
		return function.apply(Stream.empty());
	}

	public void forEach(Consumer<? super StackFrame> action) {
	}

	public Class<?> getCallerClass() {
		// inert: no caller information available under TeaVM
		return Object.class;
	}

}
