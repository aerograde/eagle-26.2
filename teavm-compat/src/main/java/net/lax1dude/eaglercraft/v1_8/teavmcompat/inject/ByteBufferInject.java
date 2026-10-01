package net.lax1dude.eaglercraft.v1_8.teavmcompat.inject;

import java.nio.ByteBuffer;

/**
 * Donor for java.nio.ByteBuffer (see plugin.JdkMethodInjector). The absolute
 * slice(index,length) overload (Java 13+) is missing from TeaVM 0.13; build it
 * from the duplicate/position/limit/slice primitives the classlib already has.
 */
public final class ByteBufferInject {

	public ByteBuffer slice(int index, int length) {
		ByteBuffer dup = duplicate();
		dup.position(index);
		dup.limit(index + length);
		return dup.slice();
	}

	/** placeholder: the target already has duplicate() (never copied) */
	public ByteBuffer duplicate() {
		return null;
	}

}
