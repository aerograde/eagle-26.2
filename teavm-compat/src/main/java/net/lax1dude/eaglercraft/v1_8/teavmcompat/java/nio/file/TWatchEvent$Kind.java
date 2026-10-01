package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file;

/**
 * java.nio.file.WatchEvent$Kind — interface facade. There is no filesystem watch
 * service in the browser VFS (see TWatchService), so kinds are never produced;
 * the type exists so TWatchEvent.kind()'s return type resolves at link time.
 */
public interface TWatchEvent$Kind<T> {

	String name();

	Class<T> type();

}
