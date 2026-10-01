package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.Delayed — absent from teavm-classlib 0.13.
 */
public interface TDelayed extends Comparable<TDelayed> {

	long getDelay(TimeUnit unit);

}
