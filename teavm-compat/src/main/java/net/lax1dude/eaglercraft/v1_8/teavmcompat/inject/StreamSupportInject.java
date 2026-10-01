package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.util.Spliterator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.stream.TEaglerCompatStreams;

/**
 * Donor for java.util.stream.StreamSupport (see plugin.JdkMethodInjector).
 */
public final class StreamSupportInject {

	public static IntStream intStream(Spliterator.OfInt spliterator, boolean parallel) {
		return TEaglerCompatStreams.intStream(spliterator, parallel);
	}

	public static LongStream longStream(Spliterator.OfLong spliterator, boolean parallel) {
		return TEaglerCompatStreams.longStream(spliterator, parallel);
	}

}
