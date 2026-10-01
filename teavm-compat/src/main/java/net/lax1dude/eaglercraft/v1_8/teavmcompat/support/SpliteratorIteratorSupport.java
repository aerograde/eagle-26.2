package net.lax1dude.eaglercraft.v1_8.teavmcompat.support;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Spliterator;

/**
 * Real Spliterators.iterator() backing for the web target (see
 * inject.SpliteratorsInject). This lives in the {@code support} package — NOT the
 * {@code teavmcompat.java} mapped hierarchy — so the injector leaves the reference
 * intact instead of renaming it to a JDK name. Being a plain class it may use
 * lambdas/anonymous classes that donor programs cannot.
 */
public final class SpliteratorIteratorSupport {

	private SpliteratorIteratorSupport() {
	}

	public static <T> Iterator<T> iterator(Spliterator<? extends T> spliterator) {
		return new Iterator<T>() {
			private boolean fetched;
			private boolean present;
			private T value;

			private void fetch() {
				if (!fetched) {
					present = spliterator.tryAdvance(v -> value = v);
					fetched = true;
				}
			}

			@Override
			public boolean hasNext() {
				fetch();
				return present;
			}

			@Override
			public T next() {
				fetch();
				if (!present) {
					throw new NoSuchElementException();
				}
				fetched = false;
				T result = value;
				value = null;
				return result;
			}
		};
	}

	public static PrimitiveIterator.OfInt iteratorOfInt(Spliterator.OfInt spliterator) {
		return new PrimitiveIterator.OfInt() {
			private boolean fetched;
			private boolean present;
			private int value;

			private void fetch() {
				if (!fetched) {
					present = spliterator.tryAdvance((int v) -> value = v);
					fetched = true;
				}
			}

			@Override
			public boolean hasNext() {
				fetch();
				return present;
			}

			@Override
			public int nextInt() {
				fetch();
				if (!present) {
					throw new NoSuchElementException();
				}
				fetched = false;
				return value;
			}
		};
	}

}
