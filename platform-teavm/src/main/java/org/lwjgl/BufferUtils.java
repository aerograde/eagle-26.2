package org.lwjgl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Web LWJGL buffer allocation.
 */
public final class BufferUtils {

	private BufferUtils() {
	}

	public static ByteBuffer createByteBuffer(final int capacity) {
		// The GL bridge copies heap buffers at the upload boundary.
		return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
	}
}
