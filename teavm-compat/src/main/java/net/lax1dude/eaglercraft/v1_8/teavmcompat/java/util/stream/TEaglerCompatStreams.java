package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.stream;

import java.util.Spliterator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

/**
 * Support class (materializes as java.util.stream.EaglerCompatStreams) backing
 * the StreamSupportInject donor: teavm-classlib 0.13's TStreamSupport only has
 * the object-stream factory, so the primitive factories are built on top of it
 * (Spliterator.OfInt IS a Spliterator&lt;Integer&gt;).
 */
public final class TEaglerCompatStreams {

	private TEaglerCompatStreams() {
	}

	public static IntStream intStream(Spliterator.OfInt spliterator, boolean parallel) {
		return StreamSupport.stream(spliterator, parallel).mapToInt(Integer::intValue);
	}

	public static LongStream longStream(Spliterator.OfLong spliterator, boolean parallel) {
		return StreamSupport.stream(spliterator, parallel).mapToLong(Long::longValue);
	}

}
