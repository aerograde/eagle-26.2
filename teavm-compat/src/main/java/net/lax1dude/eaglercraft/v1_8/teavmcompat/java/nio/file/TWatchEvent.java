package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file;

/**
 * java.nio.file.WatchEvent — interface facade. There is no filesystem watch
 * service in the browser VFS (see TWatchService), so events are never produced;
 * the nested Kind/Modifier types are out of scope and not modelled.
 */
public interface TWatchEvent<T> {

	int count();

	T context();

	TWatchEvent$Kind<T> kind();

}
