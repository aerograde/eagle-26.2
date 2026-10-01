package org.lwjgl.system;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap;

public class MemoryUtilTypedAllocationTest {

	private static void require(boolean value, String message) {
		if (!value) {
			throw new AssertionError(message);
		}
	}

	private static void requireHeap(long bytes, int allocations, String message) {
		require(EaglerFakeHeap.getLiveBytes() == bytes, message + " bytes");
		require(EaglerFakeHeap.getLiveAllocations() == allocations, message + " allocations");
	}

	public static void main(String[] args) {
		long initialBytes = EaglerFakeHeap.getLiveBytes();
		int initialAllocations = EaglerFakeHeap.getLiveAllocations();
		long initialFrees = EaglerFakeHeap.getFreeCount();

		IntBuffer ints = MemoryUtil.memAllocInt(7);
		requireHeap(initialBytes + 28L, initialAllocations + 1, "typed allocation");
		for (int i = 0; i < ints.capacity(); ++i) {
			ints.put(i, 100 + i);
		}
		for (int i = 0; i < ints.capacity(); ++i) {
			require(ints.get(i) == 100 + i, "typed contents " + i);
		}
		MemoryUtil.memFree(ints);
		requireHeap(initialBytes, initialAllocations, "typed free");
		require(EaglerFakeHeap.getFreeCount() == initialFrees + 1L, "typed free count");
		MemoryUtil.memFree(ints);
		requireHeap(initialBytes, initialAllocations, "typed double free");
		require(EaglerFakeHeap.getFreeCount() == initialFrees + 1L, "typed double free count");

		IntBuffer untracked = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		MemoryUtil.memFree(untracked);
		requireHeap(initialBytes, initialAllocations, "untracked view");

		long rawAddress = MemoryUtil.nmemAlloc(12L);
		IntBuffer rawView = MemoryUtil.memIntBuffer(rawAddress, 3);
		MemoryUtil.memFree(rawView);
		requireHeap(initialBytes + 12L, initialAllocations + 1, "raw-address view");
		MemoryUtil.nmemFree(rawAddress);
		requireHeap(initialBytes, initialAllocations, "raw-address free");

		IntBuffer original = MemoryUtil.memAllocInt(4);
		IntBuffer slice = original.duplicate();
		slice.position(1);
		slice = slice.slice();
		MemoryUtil.memFree(slice);
		requireHeap(initialBytes + 16L, initialAllocations + 1, "typed slice");
		MemoryUtil.memFree(original);
		requireHeap(initialBytes, initialAllocations, "typed owner after slice");

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer stackInts = stack.mallocInt(5);
			requireHeap(initialBytes + 20L, initialAllocations + 1, "stack allocation");
			MemoryUtil.memFree(stackInts);
			requireHeap(initialBytes + 20L, initialAllocations + 1, "stack view free");
		}
		requireHeap(initialBytes, initialAllocations, "stack close");

		ByteBuffer bytes = MemoryUtil.memAlloc(12);
		requireHeap(initialBytes + 12L, initialAllocations + 1, "byte allocation");
		MemoryUtil.memFree(bytes);
		requireHeap(initialBytes, initialAllocations, "byte free");
	}
}
