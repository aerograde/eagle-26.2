package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.TConcurrentHashMap$KeySetView;

/**
 * Donor for java.util.concurrent.ConcurrentHashMap (registered with
 * copyConstructors, see plugin.JdkMethodInjector). The KeySetView references
 * — including the newKeySet return type in the method descriptor — are
 * renamed by the injector to java.util.concurrent.ConcurrentHashMap$KeySetView
 * so callers' descriptors match exactly.
 */
public final class ConcurrentHashMapInject {

	/** placeholder: the target already has the (int,float) constructor (never copied) */
	private ConcurrentHashMapInject(int initialCapacity, float loadFactor) {
	}

	public ConcurrentHashMapInject(int initialCapacity, float loadFactor, int concurrencyLevel) {
		this(initialCapacity, loadFactor);
	}

	@SuppressWarnings("rawtypes")
	public static TConcurrentHashMap$KeySetView newKeySet() {
		return new TConcurrentHashMap$KeySetView();
	}

	@SuppressWarnings("rawtypes")
	public static TConcurrentHashMap$KeySetView newKeySet(int initialCapacity) {
		return new TConcurrentHashMap$KeySetView();
	}

}
