package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.util.concurrent.locks;

/**
 * java.util.concurrent.locks.ReadWriteLock for the single-threaded browser
 * runtime.
 */
public interface TReadWriteLock {

	TLock readLock();

	TLock writeLock();

}
