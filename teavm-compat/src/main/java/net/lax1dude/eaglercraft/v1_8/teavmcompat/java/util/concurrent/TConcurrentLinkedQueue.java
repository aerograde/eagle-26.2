package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;

/**
 * java.util.concurrent.ConcurrentLinkedQueue — ArrayDeque-backed; there is no
 * concurrency to defend against in the browser runtime.
 */
public class TConcurrentLinkedQueue<E> extends AbstractQueue<E> {

	private final ArrayDeque<E> deque;

	public TConcurrentLinkedQueue() {
		deque = new ArrayDeque<>();
	}

	public TConcurrentLinkedQueue(Collection<? extends E> source) {
		deque = new ArrayDeque<>(source);
	}

	@Override
	public boolean offer(E e) {
		if (e == null) {
			throw new NullPointerException();
		}
		return deque.offer(e);
	}

	@Override
	public E poll() {
		return deque.poll();
	}

	@Override
	public E peek() {
		return deque.peek();
	}

	@Override
	public Iterator<E> iterator() {
		return deque.iterator();
	}

	@Override
	public int size() {
		return deque.size();
	}

	@Override
	public boolean isEmpty() {
		return deque.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		return deque.contains(o);
	}

	@Override
	public boolean remove(Object o) {
		return deque.remove(o);
	}

	@Override
	public void clear() {
		deque.clear();
	}

}
