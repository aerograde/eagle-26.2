package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * REPLACE-mode donor for io.netty.util.ResourceLeakDetector (see
 * plugin.JdkMethodInjector). netty classes call
 * {@code ResourceLeakDetector.addExclusions(SomeClass.class, "method")} in their
 * static initializers (io.netty.buffer.AbstractByteBufAllocator excludes
 * "toLeakAwareBuffer"). The real impl iterates {@code clz.getDeclaredMethods()}
 * and throws {@code IllegalArgumentException("Can't find '[toLeakAwareBuffer]'
 * in ...")} when the name is absent. TeaVM only retains reflection metadata for
 * methods it sees reflected on a class LITERAL; addExclusions' {@code clz} is a
 * parameter, so the allocator's methods are dropped from metadata and every call
 * throws — which fails AbstractByteBufAllocator's static init, hence the
 * integrated server's LocalChannel buffer allocation / bind. The excluded methods
 * only affect leak-trace formatting, and leak detection is DISABLED on web
 * (SharedConstants.NETTY_LEAK_DETECTION), so make addExclusions a no-op.
 *
 * Abstract members are javac placeholders resolved against the real class after
 * reference renaming; they are never copied.
 */
public abstract class ResourceLeakDetectorInject {

	public static void addExclusions(Class<?> clz, String... methodNames) {
		// no-op on TeaVM (see class javadoc)
	}

}
