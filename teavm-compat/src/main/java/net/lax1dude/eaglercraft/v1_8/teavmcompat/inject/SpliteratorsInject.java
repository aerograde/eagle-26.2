package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.util.Iterator;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.TEaglerCompatSpliterators;
import net.lax1dude.eaglercraft.v1_8.teavmcompat.support.SpliteratorIteratorSupport;

/**
 * Donor for java.util.Spliterators (see plugin.JdkMethodInjector). The
 * TEaglerCompatSpliterators reference is renamed by the injector to its
 * virtual name (java.util.EaglerCompatSpliterators), keeping one identity.
 */
public final class SpliteratorsInject {

	public static <T> Spliterator<T> emptySpliterator() {
		return Spliterators.spliterator(new Object[0], 0);
	}

	public static Spliterator.OfDouble spliterator(double[] array, int fromIndex, int toIndex,
			int additionalCharacteristics) {
		return TEaglerCompatSpliterators.ofDouble(array, fromIndex, toIndex, additionalCharacteristics);
	}

	public static <T> Iterator<T> iterator(Spliterator<? extends T> spliterator) {
		return SpliteratorIteratorSupport.iterator(spliterator);
	}

	public static PrimitiveIterator.OfInt iterator(Spliterator.OfInt spliterator) {
		return SpliteratorIteratorSupport.iteratorOfInt(spliterator);
	}

}
