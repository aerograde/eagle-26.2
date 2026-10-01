package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.AbstractSet;
import java.util.HashSet;
import java.util.Iterator;

/**
 * java.util.concurrent.ConcurrentHashMap$KeySetView — the nested class the
 * classlib's TConcurrentHashMap does not have. The '$' in this class's NAME is
 * intentional: a top-level class whose binary name matches the nested-class
 * binary name the package mapping expects
 * (net...teavmcompat.java.util.concurrent.TConcurrentHashMap$KeySetView →
 * java.util.concurrent.ConcurrentHashMap$KeySetView). Instances are created by
 * the ConcurrentHashMapInject donor for ConcurrentHashMap.newKeySet(); a plain
 * HashSet backs it, which is safe in the single-threaded browser runtime.
 */
public class TConcurrentHashMap$KeySetView<K, V> extends AbstractSet<K> {

	private final HashSet<K> backing = new HashSet<>();

	public TConcurrentHashMap$KeySetView() {
	}

	@Override
	public boolean add(K key) {
		return backing.add(key);
	}

	@Override
	public boolean remove(Object key) {
		return backing.remove(key);
	}

	@Override
	public boolean contains(Object key) {
		return backing.contains(key);
	}

	@Override
	public Iterator<K> iterator() {
		return backing.iterator();
	}

	@Override
	public int size() {
		return backing.size();
	}

	@Override
	public boolean isEmpty() {
		return backing.isEmpty();
	}

	@Override
	public void clear() {
		backing.clear();
	}

	public V getMappedValue() {
		return null;
	}

}
