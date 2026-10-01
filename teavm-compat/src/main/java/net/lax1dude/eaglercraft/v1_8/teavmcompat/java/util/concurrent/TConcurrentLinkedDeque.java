package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent;

import java.util.ArrayDeque;
import java.util.Collection;

/**
 * java.util.concurrent.ConcurrentLinkedDeque — plain ArrayDeque; there is no
 * concurrency to defend against in the browser runtime (like the JDK class,
 * ArrayDeque rejects null elements).
 */
public class TConcurrentLinkedDeque<E> extends ArrayDeque<E> {

	public TConcurrentLinkedDeque() {
	}

	public TConcurrentLinkedDeque(Collection<? extends E> source) {
		super(source);
	}

}
