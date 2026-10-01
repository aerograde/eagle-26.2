/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.teavm.runtime.fs.VirtualFile;
import org.teavm.runtime.fs.VirtualFileAccessor;
import org.teavm.runtime.fs.VirtualFileSystem;

import net.lax1dude.eaglercraft.v1_8.internal.IEaglerFilesystem;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.ByteBuffer;

/**
 * Persistence for "basically all preferences" (Matej): TeaVM backs java.io/java.nio
 * with a pluggable {@link VirtualFileSystem}; the DEFAULT is in-memory, so
 * options.txt, servers.dat and every world written through LevelStorageSource
 * evaporated on page reload. This adapter implements the TeaVM VFS on top of the
 * eagler {@link IEaglerFilesystem} (IndexedDB, synchronous-over-async on the green
 * thread — the classic 1.8 persistence model), so the whole game directory tree
 * lives in the browser's IndexedDB and survives reloads.
 *
 * <p>The store is flat (path -&gt; bytes). Directories are implicit (any key below
 * the path) plus an explicit 1-byte marker {@code <dir>/.eaglerfsdir} so freshly
 * created empty directories exist; markers are hidden from listings. Open files are
 * RAM-buffered per accessor and flushed to IndexedDB on flush()/close() (region
 * files are kept open by MC and flushed on save).</p>
 */
public class EaglerVirtualFilesystem implements VirtualFileSystem {

	static final String DIR_MARKER = ".eaglerfsdir";

	final IEaglerFilesystem fs;
	final Map<String, Long> modTimes = new HashMap<>();
	private long resourcePackIndexRevision = Long.MIN_VALUE;
	private Set<String> resourcePackFiles = Collections.emptySet();
	private Set<String> resourcePackDirectories = Collections.emptySet();
	private Map<String, String[]> resourcePackChildren = Collections.emptyMap();

	public EaglerVirtualFilesystem(final IEaglerFilesystem fs) {
		this.fs = fs;
	}

	@Override
	public String getUserDir() {
		return "/";
	}

	@Override
	public boolean isWindows() {
		return false;
	}

	@Override
	public String[] getRoots() {
		return new String[]{"/"};
	}

	@Override
	public String canonicalize(final String path) {
		return normalize(path);
	}

	@Override
	public VirtualFile getFile(final String path) {
		return new EaglerVirtualFile(this, normalize(path));
	}

	static String normalize(final String path) {
		final String[] parts = path.replace('\\', '/').split("/");
		final List<String> out = new ArrayList<>(parts.length);
		for (final String p : parts) {
			if (p.isEmpty() || ".".equals(p)) {
				continue;
			}
			if ("..".equals(p)) {
				if (!out.isEmpty()) {
					out.remove(out.size() - 1);
				}
				continue;
			}
			out.add(p);
		}
		return "/" + String.join("/", out);
	}

	/** store key for an absolute normalized path ("" for the root) */
	static String key(final String normalizedPath) {
		return normalizedPath.length() <= 1 ? "" : normalizedPath.substring(1);
	}

	byte[] readAll(final String storeKey) {
		if (!fs.eaglerExists(storeKey)) {
			return null;
		}
		final ByteBuffer buf = fs.eaglerRead(storeKey);
		if (buf == null) {
			return null;
		}
		final byte[] cast = PlatformRuntime.castNativeByteBuffer(buf);
		if (cast != null) {
			return cast;
		}
		try {
			final byte[] copy = new byte[buf.remaining()];
			buf.get(copy);
			return copy;
		} finally {
			PlatformRuntime.freeByteBuffer(buf);
		}
	}

	void writeAll(final String storeKey, final byte[] data, final int len) {
		final ByteBuffer buf = PlatformRuntime.allocateByteBuffer(len);
		try {
			buf.put(data, 0, len);
			buf.flip();
			fs.eaglerWrite(storeKey, buf);
		} finally {
			PlatformRuntime.freeByteBuffer(buf);
		}
		modTimes.put(storeKey, System.currentTimeMillis());
	}

	/** immediate children names of a directory key ("" = root), markers hidden */
	String[] listChildren(final String dirKey) {
		if (isResourcePackKey(dirKey)) {
			ensureResourcePackIndex();
			final String[] children = resourcePackChildren.get(dirKey);
			return children == null ? new String[0] : children;
		}
		final String prefix = dirKey.isEmpty() ? "" : dirKey + "/";
		final Set<String> names = new LinkedHashSet<>();
		fs.eaglerIterateChildren(dirKey, entry -> {
			String rel = entry;
			if (!prefix.isEmpty()) {
				if (!rel.startsWith(prefix)) {
					return;
				}
				rel = rel.substring(prefix.length());
			}
			if (rel.isEmpty()) {
				return;
			}
			final int slash = rel.indexOf('/');
			final String first = slash < 0 ? rel : rel.substring(0, slash);
			if (!DIR_MARKER.equals(first)) {
				names.add(first);
			}
		});
		return names.toArray(new String[0]);
	}

	boolean hasAnyChild(final String dirKey) {
		return listChildren(dirKey).length > 0;
	}

	boolean isFile(final String storeKey) {
		if (isResourcePackKey(storeKey)) {
			ensureResourcePackIndex();
			return resourcePackFiles.contains(storeKey);
		}
		return !storeKey.isEmpty() && fs.eaglerExists(storeKey);
	}

	boolean isDirectory(final String storeKey) {
		if (storeKey.isEmpty()) {
			return true;
		}
		if (isResourcePackKey(storeKey)) {
			ensureResourcePackIndex();
			return resourcePackDirectories.contains(storeKey);
		}
		if (fs.eaglerExists(storeKey)) {
			return false;
		}
		return fs.eaglerExists(storeKey + "/" + DIR_MARKER) || hasAnyChild(storeKey);
	}

	private static boolean isResourcePackKey(final String storeKey) {
		return "resourcepacks".equals(storeKey) || storeKey.startsWith("resourcepacks/");
	}

	private void ensureResourcePackIndex() {
		final long revision = fs.eaglerGetRevision("resourcepacks");
		if (revision == resourcePackIndexRevision) {
			return;
		}
		final Set<String> files = new HashSet<>();
		final Set<String> directories = new HashSet<>();
		final Map<String, LinkedHashSet<String>> children = new HashMap<>();
		fs.eaglerIterate("resourcepacks", entry -> {
			if (!entry.startsWith("resourcepacks/")) {
				return;
			}
			directories.add("resourcepacks");
			final String relative = entry.substring("resourcepacks/".length());
			if (relative.isEmpty()) {
				return;
			}
			final String[] parts = relative.split("/");
			final boolean marker = DIR_MARKER.equals(parts[parts.length - 1]);
			final int visibleParts = marker ? parts.length - 1 : parts.length;
			String parent = "resourcepacks";
			for (int i = 0; i < visibleParts; ++i) {
				final String child = parts[i];
				children.computeIfAbsent(parent, key -> new LinkedHashSet<>()).add(child);
				final String path = parent + "/" + child;
				if (marker || i < visibleParts - 1) {
					directories.add(path);
					parent = path;
				} else {
					files.add(path);
				}
			}
		}, true);
		final Map<String, String[]> packedChildren = new HashMap<>();
		for (final Map.Entry<String, LinkedHashSet<String>> entry : children.entrySet()) {
			packedChildren.put(entry.getKey(), entry.getValue().toArray(new String[0]));
		}
		resourcePackFiles = files;
		resourcePackDirectories = directories;
		resourcePackChildren = packedChildren;
		resourcePackIndexRevision = revision;
	}
}

/**
 * A path handle on the {@link EaglerVirtualFilesystem}. Existence/type is resolved
 * against the store on each query (cheap key lookups; directory checks may scan).
 */
class EaglerVirtualFile implements VirtualFile {

	private final EaglerVirtualFilesystem vfs;
	private final String path; // normalized, "/"-rooted
	private final String storeKey;

	EaglerVirtualFile(final EaglerVirtualFilesystem vfs, final String path) {
		this.vfs = vfs;
		this.path = path;
		this.storeKey = EaglerVirtualFilesystem.key(path);
	}

	private String childKey(final String name) {
		return storeKey.isEmpty() ? name : storeKey + "/" + name;
	}

	@Override
	public String getName() {
		final int slash = path.lastIndexOf('/');
		return slash < 0 ? path : path.substring(slash + 1);
	}

	@Override
	public boolean isDirectory() {
		return vfs.isDirectory(storeKey);
	}

	@Override
	public boolean isFile() {
		return vfs.isFile(storeKey);
	}

	@Override
	public String[] listFiles() {
		return isDirectory() ? vfs.listChildren(storeKey) : null;
	}

	@Override
	public VirtualFileAccessor createAccessor(final boolean readable, final boolean writable, final boolean append) {
		if (isDirectory()) {
			return null;
		}
		final byte[] existing = vfs.readAll(storeKey);
		if (existing == null && !writable) {
			return null;
		}
		return new EaglerVirtualFileAccessor(vfs, storeKey, existing, append);
	}

	@Override
	public boolean createFile(final String fileName) throws IOException {
		final String k = childKey(fileName);
		if (vfs.fs.eaglerExists(k)) {
			return false;
		}
		vfs.writeAll(k, new byte[0], 0);
		return true;
	}

	@Override
	public boolean createDirectory(final String fileName) {
		final String k = childKey(fileName);
		if (vfs.fs.eaglerExists(k)) {
			return false; // exists as a file
		}
		vfs.writeAll(k + "/" + EaglerVirtualFilesystem.DIR_MARKER, new byte[0], 0);
		return true;
	}

	@Override
	public boolean delete() {
		if (isFile()) {
			return vfs.fs.eaglerDelete(storeKey);
		}
		if (isDirectory()) {
			// java.io semantics: only delete EMPTY directories
			if (vfs.hasAnyChild(storeKey)) {
				return false;
			}
			vfs.fs.eaglerDelete(storeKey + "/" + EaglerVirtualFilesystem.DIR_MARKER);
			return true;
		}
		return false;
	}

	@Override
	public boolean adopt(final VirtualFile file, final String fileName) {
		// rename `file` to be this directory's child named fileName
		if (!(file instanceof EaglerVirtualFile)) {
			return false;
		}
		final EaglerVirtualFile src = (EaglerVirtualFile) file;
		final String dstKey = childKey(fileName);
		if (src.isFile()) {
			return vfs.fs.eaglerMove(src.storeKey, dstKey);
		}
		if (src.isDirectory()) {
			// flat store: move every key under the source prefix
			final String prefix = src.storeKey + "/";
			final List<String> keys = new ArrayList<>();
			vfs.fs.eaglerIterate(src.storeKey, keys::add, true);
			boolean ok = true;
			for (final String k : keys) {
				if (k.startsWith(prefix)) {
					ok &= vfs.fs.eaglerMove(k, dstKey + "/" + k.substring(prefix.length()));
				}
			}
			return ok;
		}
		return false;
	}

	@Override
	public boolean canRead() {
		return exists();
	}

	@Override
	public boolean canWrite() {
		return true;
	}

	@Override
	public long lastModified() {
		final Long t = vfs.modTimes.get(storeKey);
		return t != null ? t : 0L;
	}

	@Override
	public boolean setLastModified(final long lastModified) {
		vfs.modTimes.put(storeKey, lastModified);
		return true;
	}

	@Override
	public boolean setReadOnly(final boolean readOnly) {
		return false;
	}

	@Override
	public int length() {
		final int size = vfs.fs.eaglerSize(storeKey);
		return Math.max(size, 0);
	}
}

/**
 * RAM-buffered random-access handle: the whole file is held in a growable array;
 * writes mark it dirty and flush()/close() persist to IndexedDB (matches how MC
 * uses RandomAccessFile for region files: long-lived handles, explicit saves).
 */
class EaglerVirtualFileAccessor implements VirtualFileAccessor {

	private final EaglerVirtualFilesystem vfs;
	private final String storeKey;
	private byte[] data;
	private int size;
	private int pos;
	private boolean dirty;
	private boolean closed;

	EaglerVirtualFileAccessor(final EaglerVirtualFilesystem vfs, final String storeKey, final byte[] existing,
			final boolean append) {
		this.vfs = vfs;
		this.storeKey = storeKey;
		this.data = existing != null ? existing : new byte[0];
		this.size = this.data.length;
		this.pos = append ? this.size : 0;
		if (existing == null) {
			this.dirty = true; // brand-new file: persist even if nothing is written
		}
	}

	private void ensureCapacity(final int cap) {
		if (cap > data.length) {
			int n = Math.max(64, data.length);
			while (n < cap) {
				n <<= 1;
			}
			final byte[] grown = new byte[n];
			System.arraycopy(data, 0, grown, 0, size);
			data = grown;
		}
	}

	@Override
	public int read(final byte[] buffer, final int offset, final int limit) throws IOException {
		if (pos >= size) {
			return -1;
		}
		final int n = Math.min(limit, size - pos);
		System.arraycopy(data, pos, buffer, offset, n);
		pos += n;
		return n;
	}

	@Override
	public void write(final byte[] buffer, final int offset, final int limit) throws IOException {
		ensureCapacity(pos + limit);
		System.arraycopy(buffer, offset, data, pos, limit);
		pos += limit;
		if (pos > size) {
			size = pos;
		}
		dirty = true;
	}

	@Override
	public int tell() throws IOException {
		return pos;
	}

	@Override
	public void seek(final int target) throws IOException {
		pos = Math.max(0, target);
	}

	@Override
	public void skip(final int amount) throws IOException {
		pos += amount;
	}

	@Override
	public int size() throws IOException {
		return size;
	}

	@Override
	public void resize(final int newSize) throws IOException {
		if (newSize < size) {
			size = Math.max(0, newSize);
		} else if (newSize > size) {
			ensureCapacity(newSize);
			java.util.Arrays.fill(data, size, newSize, (byte) 0);
			size = newSize;
		}
		if (pos > size) {
			pos = size;
		}
		dirty = true;
	}

	@Override
	public void flush() throws IOException {
		if (dirty && !closed) {
			vfs.writeAll(storeKey, data, size);
			dirty = false;
		}
	}

	@Override
	public void close() throws IOException {
		if (!closed) {
			flush();
			closed = true;
		}
	}
}
