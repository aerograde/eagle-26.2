package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

/**
 * java.util.concurrent.ThreadFactory — absent from teavm-classlib 0.13.
 */
public interface TThreadFactory {

	Thread newThread(Runnable r);

}
