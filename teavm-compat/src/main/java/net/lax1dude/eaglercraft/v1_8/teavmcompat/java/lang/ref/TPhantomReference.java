package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.lang.ref;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * java.lang.ref.PhantomReference — extends WeakReference rather than Reference
 * because java.lang.ref.Reference's constructors are package-private (the
 * compat hierarchy cannot compile against them). get() returning null matches
 * the PhantomReference contract; the slightly-off superclass chain is
 * unobservable to the netty/guava cleanup code that reaches this.
 */
public class TPhantomReference<T> extends WeakReference<T> {

	public TPhantomReference(T referent, ReferenceQueue<? super T> q) {
		super(referent, q);
	}

	@Override
	public T get() {
		return null;
	}

}
