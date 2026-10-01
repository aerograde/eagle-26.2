package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util;

import java.util.Spliterator;

/**
 * java.util.Spliterators$AbstractSpliterator — the nested base class the
 * classlib's TSpliterators does not have; library code subclasses it. As with
 * TConcurrentHashMap$KeySetView, the '$' in this top-level class's name is
 * intentional so its binary name matches what the package mapping expects.
 * trySplit() returns null (no parallelism in the browser runtime).
 */
public abstract class TSpliterators$AbstractSpliterator<T> implements Spliterator<T> {

	private final int characteristics;
	private long estimatedSize;

	protected TSpliterators$AbstractSpliterator(long est, int additionalCharacteristics) {
		this.estimatedSize = est;
		this.characteristics = additionalCharacteristics;
	}

	@Override
	public Spliterator<T> trySplit() {
		return null;
	}

	@Override
	public long estimateSize() {
		return estimatedSize;
	}

	@Override
	public int characteristics() {
		return characteristics;
	}

}
