package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

/**
 * Donor for java.util.concurrent.atomic.AtomicReferenceArray (see
 * plugin.JdkMethodInjector). compareAndExchange is atomic-trivial on a single
 * JS thread: read the slot, swap only on identity match, and return the prior
 * value either way.
 */
public final class AtomicReferenceArrayInject {

	public Object compareAndExchange(int i, Object expectedValue, Object newValue) {
		Object current = get(i);
		if (current == expectedValue) {
			set(i, newValue);
		}
		return current;
	}

	/** placeholder: the target already has get(int) (never copied) */
	public Object get(int i) {
		return null;
	}

	/** placeholder: the target already has set(int, Object) (never copied) */
	public void set(int i, Object newValue) {
	}

}
