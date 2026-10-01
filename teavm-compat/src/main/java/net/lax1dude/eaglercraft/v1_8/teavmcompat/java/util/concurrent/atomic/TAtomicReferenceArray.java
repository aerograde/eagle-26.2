package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.atomic;

import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * java.util.concurrent.atomic.AtomicReferenceArray — plain Object[] wrapper;
 * atomicity is inherent in the single-threaded browser runtime.
 */
public class TAtomicReferenceArray<E> {

	private final Object[] array;

	public TAtomicReferenceArray(int length) {
		this.array = new Object[length];
	}

	public TAtomicReferenceArray(E[] source) {
		this.array = source.clone();
	}

	public final int length() {
		return array.length;
	}

	@SuppressWarnings("unchecked")
	public final E get(int i) {
		return (E) array[i];
	}

	public final void set(int i, E newValue) {
		array[i] = newValue;
	}

	public final void lazySet(int i, E newValue) {
		array[i] = newValue;
	}

	@SuppressWarnings("unchecked")
	public final E getAndSet(int i, E newValue) {
		Object old = array[i];
		array[i] = newValue;
		return (E) old;
	}

	public final boolean compareAndSet(int i, E expect, E update) {
		if (array[i] == expect) {
			array[i] = update;
			return true;
		}
		return false;
	}

	public final boolean weakCompareAndSet(int i, E expect, E update) {
		return compareAndSet(i, expect, update);
	}

	@SuppressWarnings("unchecked")
	public final E updateAndGet(int i, UnaryOperator<E> updateFunction) {
		E next = updateFunction.apply((E) array[i]);
		array[i] = next;
		return next;
	}

	@SuppressWarnings("unchecked")
	public final E getAndUpdate(int i, UnaryOperator<E> updateFunction) {
		E old = (E) array[i];
		array[i] = updateFunction.apply(old);
		return old;
	}

	@SuppressWarnings("unchecked")
	public final E accumulateAndGet(int i, E x, BinaryOperator<E> accumulatorFunction) {
		E next = accumulatorFunction.apply((E) array[i], x);
		array[i] = next;
		return next;
	}

	@Override
	public String toString() {
		return java.util.Arrays.toString(array);
	}

}
