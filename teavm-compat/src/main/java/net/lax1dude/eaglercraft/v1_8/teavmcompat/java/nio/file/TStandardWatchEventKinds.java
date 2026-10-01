package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.file;

import java.nio.file.Path;

/**
 * java.nio.file.StandardWatchEventKinds — the well-known kind constants. There
 * is no filesystem watch service in the browser VFS (PackSelectionScreen's
 * directory watcher never fires), but the constants are read at link time, so
 * they exist as inert Kind instances.
 */
public final class TStandardWatchEventKinds {

	public static final TWatchEvent$Kind<Path> ENTRY_CREATE = new Kind<>("ENTRY_CREATE", Path.class);
	public static final TWatchEvent$Kind<Path> ENTRY_DELETE = new Kind<>("ENTRY_DELETE", Path.class);
	public static final TWatchEvent$Kind<Path> ENTRY_MODIFY = new Kind<>("ENTRY_MODIFY", Path.class);
	public static final TWatchEvent$Kind<Object> OVERFLOW = new Kind<>("OVERFLOW", Object.class);

	private TStandardWatchEventKinds() {
	}

	private static final class Kind<T> implements TWatchEvent$Kind<T> {

		private final String name;
		private final Class<T> type;

		Kind(String name, Class<T> type) {
			this.name = name;
			this.type = type;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public Class<T> type() {
			return type;
		}
	}
}
