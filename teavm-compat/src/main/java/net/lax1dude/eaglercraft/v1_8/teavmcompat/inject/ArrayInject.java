package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.lang.reflect.Array;

/**
 * Donor for java.lang.reflect.Array (see plugin.JdkMethodInjector).
 */
public final class ArrayInject {

	public static Object newInstance(Class<?> componentType, int[] dimensions) {
		if (dimensions.length == 1) {
			return Array.newInstance(componentType, dimensions[0]);
		}
		// Multi-dimensional allocation (TeaVM lowers `new T[a][b]...` to this). Build it
		// from the working 1-D primitives: the outer array holds arrays with one fewer
		// dimension, filled by recursing. World gen (noise/heightmaps) needs this.
		int len = dimensions[0];
		// element type = an array of componentType with (dimensions.length - 1) dimensions
		Class<?> elementType = componentType;
		for (int d = 0; d < dimensions.length - 1; d++) {
			elementType = Array.newInstance(elementType, 0).getClass();
		}
		Object[] array = (Object[]) Array.newInstance(elementType, len);
		int[] sub = new int[dimensions.length - 1];
		System.arraycopy(dimensions, 1, sub, 0, sub.length);
		for (int i = 0; i < len; i++) {
			array[i] = Array.newInstance(componentType, sub); // recurse (self, when injected)
		}
		return array;
	}

}
