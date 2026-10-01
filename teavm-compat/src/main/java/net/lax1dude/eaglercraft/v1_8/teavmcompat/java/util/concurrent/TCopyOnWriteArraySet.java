package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * java.util.concurrent.CopyOnWriteArraySet — absent from teavm-classlib 0.13.
 * The single-threaded browser runtime never sees concurrent mutation, so a
 * plain LinkedHashSet (which preserves insertion order, like the real class)
 * backs it. Real behavior for a single thread.
 */
public class TCopyOnWriteArraySet<E> extends AbstractSet<E> {

	private final LinkedHashSet<E> backing;

	public TCopyOnWriteArraySet() {
		this.backing = new LinkedHashSet<>();
	}

	public TCopyOnWriteArraySet(Collection<? extends E> c) {
		this.backing = new LinkedHashSet<>(c);
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
	public boolean contains(Object o) {
		return backing.contains(o);
	}

	@Override
	public Iterator<E> iterator() {
		return backing.iterator();
	}

	@Override
	public boolean add(E e) {
		return backing.add(e);
	}

	@Override
	public boolean remove(Object o) {
		return backing.remove(o);
	}

	@Override
	public boolean addAll(Collection<? extends E> c) {
		return backing.addAll(c);
	}

	@Override
	public boolean removeAll(Collection<?> c) {
		return backing.removeAll(c);
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		return backing.retainAll(c);
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		return backing.containsAll(c);
	}

	@Override
	public void clear() {
		backing.clear();
	}

	@Override
	public Object[] toArray() {
		return backing.toArray();
	}

	@Override
	public <T> T[] toArray(T[] a) {
		return backing.toArray(a);
	}
}
