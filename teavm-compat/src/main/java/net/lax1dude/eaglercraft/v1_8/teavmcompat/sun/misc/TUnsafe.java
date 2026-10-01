package net.lax1dude.eaglercraft.v1_8.teavmcompat.sun.misc;

import java.lang.reflect.Field;

/**
 * sun.misc.Unsafe — link-only stub. guava/netty/fastutil probe for Unsafe
 * inside try/catch at runtime; getUnsafe() throwing SecurityException (the
 * same thing the real JDK does for untrusted callers) pushes them all onto
 * their safe fallback paths.
 */
public final class TUnsafe {

	private TUnsafe() {
	}

	public static TUnsafe getUnsafe() {
		throw new SecurityException("no sun.misc.Unsafe in the browser runtime");
	}

	public int arrayBaseOffset(Class<?> arrayClass) {
		return 0;
	}

	public int arrayIndexScale(Class<?> arrayClass) {
		return 1;
	}

	public int addressSize() {
		return 4;
	}

	public int pageSize() {
		return 4096;
	}

	public long objectFieldOffset(Field f) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public long allocateMemory(long bytes) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void freeMemory(long address) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public byte getByte(long address) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putByte(long address, byte x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public Object getObject(Object o, long offset) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putObject(Object o, long offset, Object x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void copyMemory(long srcAddress, long destAddress, long bytes) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void copyMemory(Object srcBase, long srcOffset, Object destBase, long destOffset, long bytes) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public boolean compareAndSwapObject(Object o, long offset, Object expected, Object x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public Object getAndSetObject(Object o, long offset, Object newValue) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public float getFloat(Object o, long offset) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putFloat(Object o, long offset, float x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public int getInt(long address) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public int getInt(Object o, long offset) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public int getIntVolatile(Object o, long offset) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putInt(Object o, long offset, int x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public long getLong(long address) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public long getLong(Object o, long offset) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putLong(long address, long x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putLong(Object o, long offset, long x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putOrderedLong(Object o, long offset, long x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putOrderedObject(Object o, long offset, Object x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	// Phase 3.3b cycle 5: additional accessors reached (netty/fastutil/guava);
	// getUnsafe() throws above, so no instance is ever obtained and none of these
	// are actually invoked — they only need to link.
	public boolean compareAndSwapInt(Object o, long offset, int expected, int x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public boolean compareAndSwapLong(Object o, long offset, long expected, long x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public int getAndAddInt(Object o, long offset, int delta) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public byte getByte(Object o, long offset) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public char getChar(Object o, long offset) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public long getLongVolatile(Object o, long offset) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public Object getObjectVolatile(Object o, long offset) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public short getShort(long address) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putByte(Object o, long offset, byte x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putInt(long address, int x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void putOrderedInt(Object o, long offset, int x) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public long reallocateMemory(long address, long bytes) {
		throw new UnsupportedOperationException("no sun.misc.Unsafe in the browser runtime");
	}

	public void storeFence() {
		// fences are no-ops on a single thread
	}

	public void loadFence() {
	}

	public void fullFence() {
	}

}
