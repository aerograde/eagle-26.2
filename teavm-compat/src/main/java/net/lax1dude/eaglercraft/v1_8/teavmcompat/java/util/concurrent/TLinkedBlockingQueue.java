package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.LinkedBlockingQueue — ArrayDeque-backed for the
 * single-threaded browser runtime. Non-blocking operations behave normally;
 * take() on an empty queue would deadlock (nothing can offer concurrently)
 * and throws UnsupportedOperationException instead, and timed poll() returns
 * immediately.
 */
public class TLinkedBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {

	private final ArrayDeque<E> deque;
	private final int capacity;

	public TLinkedBlockingQueue() {
		this(Integer.MAX_VALUE);
	}

	public TLinkedBlockingQueue(int capacity) {
		this.deque = new ArrayDeque<>();
		this.capacity = capacity;
	}

	public TLinkedBlockingQueue(Collection<? extends E> source) {
		this.deque = new ArrayDeque<>(source);
		this.capacity = Integer.MAX_VALUE;
	}

	@Override
	public boolean offer(E e) {
		if (e == null) {
			throw new NullPointerException();
		}
		if (deque.size() >= capacity) {
			return false;
		}
		return deque.offer(e);
	}

	@Override
	public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
		return offer(e);
	}

	@Override
	public void put(E e) throws InterruptedException {
		if (!offer(e)) {
			throw new IllegalStateException("Queue full");
		}
	}

	@Override
	public E poll() {
		return deque.poll();
	}

	@Override
	public E poll(long timeout, TimeUnit unit) throws InterruptedException {
		return poll();
	}

	@Override
	public E take() throws InterruptedException {
		E value = deque.poll();
		if (value == null) {
			// blocking take() cannot be satisfied: no other thread can offer
			throw new UnsupportedOperationException(
					"LinkedBlockingQueue.take() on an empty queue would deadlock in the browser runtime");
		}
		return value;
	}

	@Override
	public E peek() {
		return deque.peek();
	}

	@Override
	public int remainingCapacity() {
		return capacity - deque.size();
	}

	@Override
	public int drainTo(Collection<? super E> c) {
		return drainTo(c, Integer.MAX_VALUE);
	}

	@Override
	public int drainTo(Collection<? super E> c, int maxElements) {
		int n = 0;
		E value;
		while (n < maxElements && (value = deque.poll()) != null) {
			c.add(value);
			++n;
		}
		return n;
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
