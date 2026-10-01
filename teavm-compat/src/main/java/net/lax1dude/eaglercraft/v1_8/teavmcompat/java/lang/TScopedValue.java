package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * java.lang.ScopedValue for the single-threaded browser runtime. With exactly
 * one (green) thread, scoped bindings degenerate to save/set/restore around
 * the Carrier.run/call body — which is semantically exact here. MC 26.2 uses
 * this in net.minecraft.util.filefix (ScopedValue.newInstance / where / run).
 */
public final class TScopedValue<T> {

	private T value;
	private boolean bound;

	private TScopedValue() {
	}

	public static <T> TScopedValue<T> newInstance() {
		return new TScopedValue<>();
	}

	public T get() {
		if (!bound) {
			throw new NoSuchElementException("ScopedValue not bound");
		}
		return value;
	}

	public boolean isBound() {
		return bound;
	}

	public T orElse(T other) {
		return bound ? value : other;
	}

	public static <T> Carrier where(TScopedValue<T> key, T value) {
		return new Carrier().with(key, value);
	}

	public static <T> void runWhere(TScopedValue<T> key, T value, Runnable op) {
		where(key, value).run(op);
	}

	/** Mirrors java.lang.ScopedValue.CallableOp (Java 23+). */
	public interface CallableOp<V, X extends Throwable> {

		V call() throws X;

	}

	/** Mirrors java.lang.ScopedValue.Carrier. */
	public static final class Carrier {

		private final List<TScopedValue<Object>> keys = new ArrayList<>();
		private final List<Object> values = new ArrayList<>();

		Carrier() {
		}

		@SuppressWarnings("unchecked")
		public <T> Carrier where(TScopedValue<T> key, T value) {
			return with(key, value);
		}

		@SuppressWarnings("unchecked")
		<T> Carrier with(TScopedValue<T> key, T value) {
			keys.add((TScopedValue<Object>) key);
			values.add(value);
			return this;
		}

		public void run(Runnable op) {
			Object[] savedValues = bind();
			boolean[] savedBound = savedBoundStates();
			try {
				op.run();
			} finally {
				restore(savedValues, savedBound);
			}
		}

		public <V, X extends Throwable> V call(CallableOp<V, X> op) throws X {
			Object[] savedValues = bind();
			boolean[] savedBound = savedBoundStates();
			try {
				return op.call();
			} finally {
				restore(savedValues, savedBound);
			}
		}

		private boolean[] savedBoundStates() {
			boolean[] saved = new boolean[keys.size()];
			for (int i = 0; i < keys.size(); ++i) {
				saved[i] = keys.get(i).bound;
			}
			return saved;
		}

		private Object[] bind() {
			Object[] saved = new Object[keys.size()];
			for (int i = 0; i < keys.size(); ++i) {
				TScopedValue<Object> key = keys.get(i);
				saved[i] = key.value;
				key.value = values.get(i);
				key.bound = true;
			}
			return saved;
		}

		private void restore(Object[] savedValues, boolean[] savedBound) {
			for (int i = 0; i < keys.size(); ++i) {
				TScopedValue<Object> key = keys.get(i);
				key.value = savedValues[i];
				key.bound = savedBound[i];
			}
		}

	}

}
