package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util;

import java.util.Spliterator;
import java.util.function.DoubleConsumer;

/**
 * Support class (materializes as java.util.EaglerCompatSpliterators) backing
 * the SpliteratorsInject donor: teavm-classlib 0.13 has int[]/long[] array
 * spliterators but no double[] variant and no Spliterator.OfDouble impl.
 */
public final class TEaglerCompatSpliterators {

	private TEaglerCompatSpliterators() {
	}

	public static Spliterator.OfDouble ofDouble(double[] array, int fromIndex, int toIndex,
			int additionalCharacteristics) {
		return new DoubleArraySpliterator(array, fromIndex, toIndex, additionalCharacteristics);
	}

	static final class DoubleArraySpliterator implements Spliterator.OfDouble {

		private final double[] array;
		private final int fence;
		private final int characteristics;
		private int index;

		DoubleArraySpliterator(double[] array, int origin, int fence, int additionalCharacteristics) {
			this.array = array;
			this.index = origin;
			this.fence = fence;
			this.characteristics = additionalCharacteristics | Spliterator.SIZED | Spliterator.SUBSIZED;
		}

		@Override
		public Spliterator.OfDouble trySplit() {
			int lo = index;
			int mid = (lo + fence) >>> 1;
			if (lo >= mid) {
				return null;
			}
			index = mid;
			return new DoubleArraySpliterator(array, lo, mid, characteristics);
		}

		@Override
		public boolean tryAdvance(DoubleConsumer action) {
			if (index >= 0 && index < fence) {
				action.accept(array[index++]);
				return true;
			}
			return false;
		}

		@Override
		public void forEachRemaining(DoubleConsumer action) {
			while (index < fence) {
				action.accept(array[index++]);
			}
		}

		@Override
		public long estimateSize() {
			return (long) (fence - index);
		}

		@Override
		public int characteristics() {
			return characteristics;
		}

	}

}
