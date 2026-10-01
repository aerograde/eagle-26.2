/*
 * Copyright (c) 2022-2024 lax1dude. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 */

// 26.2 adaptation (Phase 3.1): TeaVMUtils.addEventListener/isNotTruthy replaced with
// local JSBody copies (TeaVMUtils is a later increment), TODO(3.2)

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.indexeddb.EventHandler;
import org.teavm.jso.indexeddb.IDBCountRequest;
import org.teavm.jso.indexeddb.IDBCursor;
import org.teavm.jso.indexeddb.IDBCursorRequest;
import org.teavm.jso.indexeddb.IDBDatabase;
import org.teavm.jso.indexeddb.IDBFactory;
import org.teavm.jso.indexeddb.IDBGetRequest;
import org.teavm.jso.indexeddb.IDBObjectStoreParameters;
	import org.teavm.jso.indexeddb.IDBOpenDBRequest;
	import org.teavm.jso.indexeddb.IDBObjectStore;
import org.teavm.jso.indexeddb.IDBRequest;
import org.teavm.jso.indexeddb.IDBTransaction;
import org.teavm.jso.indexeddb.IDBVersionChangeEvent;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

import net.lax1dude.eaglercraft.v1_8.internal.IEaglerFilesystem;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformFilesystem.FilesystemDatabaseInitializationException;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformFilesystem.FilesystemDatabaseLockedException;
import net.lax1dude.eaglercraft.v1_8.internal.VFSFilenameIterator;
import net.lax1dude.eaglercraft.v1_8.internal.VFSFilenameIteratorNonRecursive;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.ByteBuffer;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.EaglerArrayBufferAllocator;
import net.lax1dude.eaglercraft.v1_8.internal.vfs2.EaglerFileSystemException;
import net.lax1dude.eaglercraft.v1_8.internal.vfs2.VFSIterator2;

public class IndexedDBFilesystem implements IEaglerFilesystem {

	// TODO(3.2): local copies of TeaVMUtils.addEventListener / isNotTruthy
	@JSBody(params = { "obj", "name", "handler" }, script = "obj.addEventListener(name, handler);")
	static native void addEventListener(JSObject obj, String name, JSObject handler);

	@JSBody(params = { "obj" }, script = "return !obj;")
	static native boolean isNotTruthy(JSObject object);

	public static IEaglerFilesystem createFilesystem(String dbName) {
		String filesystemDB = "_net_lax1dude_eaglercraft_v1_8_internal_PlatformFilesystem_1_8_8_" + dbName;
		DatabaseOpen dbOpen = AsyncHandlers.openDB(filesystemDB);

		if(dbOpen.failedLocked) {
			throw new FilesystemDatabaseLockedException(dbOpen.failedError);
		}

		if(dbOpen.failedInit) {
			throw new FilesystemDatabaseInitializationException(dbOpen.failedError);
		}

		if(dbOpen.database == null) {
			throw new NullPointerException("IDBDatabase is null!");
		}

		return new IndexedDBFilesystem(dbName, filesystemDB, dbOpen.database);
	}

	private final String name;
	private final String indexedDBName;
	private IDBDatabase database;

	private IndexedDBFilesystem(String name, String indexedDBName, IDBDatabase database) {
		this.name = name;
		this.indexedDBName = indexedDBName;
		this.database = database;
	}

	@Override
	public String getFilesystemName() {
		return name;
	}

	@Override
	public String getInternalDBName() {
		return "indexeddb:" + indexedDBName;
	}

	@Override
	public boolean isRamdisk() {
		return false;
	}

	@Override
	public boolean eaglerDelete(String pathName) {
		return AsyncHandlers.deleteFile(database, pathName).bool;
	}

	@Override
	public ByteBuffer eaglerRead(String pathName) {
		ArrayBuffer ar = AsyncHandlers.readWholeFile(database, pathName);
		if(ar == null) {
			return null;
		}
		return EaglerArrayBufferAllocator.wrapByteBufferTeaVM(Int8Array.create(ar));
	}

	@Override
	public void eaglerWrite(String pathName, ByteBuffer data) {
		if(!AsyncHandlers.writeWholeFile(database, pathName, EaglerArrayBufferAllocator.getDataView8Unsigned(data).getBuffer()).bool) {
			throw new EaglerFileSystemException("Failed to write " + data.remaining() + " byte file to indexeddb table: " + pathName);
		}
	}

	@Override
	public void eaglerWriteBatch(String[] pathNames, ByteBuffer[] data) {
		if(pathNames.length != data.length) {
			throw new IllegalArgumentException("path/data batch length mismatch");
		}
		ArrayBuffer[] buffers = new ArrayBuffer[data.length];
		for(int i = 0; i < data.length; ++i) {
			buffers[i] = EaglerArrayBufferAllocator.getDataView8Unsigned(data[i]).getBuffer();
		}
		if(!AsyncHandlers.writeWholeFiles(database, pathNames, buffers).bool) {
			throw new EaglerFileSystemException("Failed to write " + data.length + " file batch to indexeddb table");
		}
	}

	@Override
	public void eaglerDeleteBatch(String[] pathNames) {
		if(!AsyncHandlers.deleteFiles(database, pathNames).bool) {
			throw new EaglerFileSystemException("Failed to delete " + pathNames.length + " file batch from indexeddb table");
		}
	}

	@Override
	public void eaglerDeleteRecursive(String pathName) {
		if(!AsyncHandlers.deletePrefix(database, pathName).bool) {
			throw new EaglerFileSystemException("Failed to recursively delete indexeddb path: " + pathName);
		}
	}

	@Override
	public boolean eaglerExists(String pathName) {
		return AsyncHandlers.fileExists(database, pathName).bool;
	}

	@Override
	public boolean eaglerMove(String pathNameOld, String pathNameNew) {
		ArrayBuffer old = AsyncHandlers.readWholeFile(database, pathNameOld);
		return old != null && AsyncHandlers.writeWholeFile(database, pathNameNew, old).bool && AsyncHandlers.deleteFile(database, pathNameOld).bool;
	}

	@Override
	public int eaglerCopy(String pathNameOld, String pathNameNew) {
		ArrayBuffer old = AsyncHandlers.readWholeFile(database, pathNameOld);
		if(old != null && AsyncHandlers.writeWholeFile(database, pathNameNew, old).bool) {
			return old.getByteLength();
		}else {
			return -1;
		}
	}

	@Override
	public int eaglerSize(String pathName) {
		ArrayBuffer old = AsyncHandlers.readWholeFile(database, pathName);
		return old == null ? -1 : old.getByteLength();
	}

	@Override
	public void eaglerIterate(String pathName, VFSFilenameIterator itr, boolean recursive) {
		if(recursive) {
			AsyncHandlers.iterateFiles(database, pathName, false, itr);
		}else {
			AsyncHandlers.iterateFiles(database, pathName, false, new VFSFilenameIteratorNonRecursive(itr, VFSFilenameIteratorNonRecursive.countSlashes(pathName) + 1));
		}
	}

	@Override
	public void eaglerIterateChildren(String pathName, VFSFilenameIterator itr) {
		AsyncHandlers.iterateChildren(database, pathName, itr);
	}

	@Override
	public void closeHandle() {
		if(database != null) {
			database.close();
			database = null;
		}
	}

	protected static class DatabaseOpen {

		protected final boolean failedInit;
		protected final boolean failedLocked;
		protected final String failedError;

		protected final IDBDatabase database;

		protected DatabaseOpen(boolean init, boolean locked, String error, IDBDatabase db) {
			failedInit = init;
			failedLocked = locked;
			failedError = error;
			database = db;
		}

	}

	@JSBody(script = "return ((typeof indexedDB) !== 'undefined') ? indexedDB : null;")
	protected static native IDBFactory createIDBFactory();

	@JSFunctor
	protected static interface OpenErrorCallback extends JSObject {
		void call(String str);
	}

	@JSBody(params = { "factory", "name", "ii", "errCB" }, script = "try { return factory.open(name, ii); } catch(err) { errCB(\"\" + err); return null; }")
	protected static native IDBOpenDBRequest safeOpen(IDBFactory factory, String name, int i, OpenErrorCallback errCB);

	protected static class AsyncHandlers {

		@Async
		protected static native DatabaseOpen openDB(String name);

		private static void openDB(String name, final AsyncCallback<DatabaseOpen> cb) {
			IDBFactory i = createIDBFactory();
			if(i == null) {
				cb.complete(new DatabaseOpen(true, false, "window.indexedDB was null or undefined", null));
				return;
			}
			final String[] errorHolder = new String[] { null };
			final IDBOpenDBRequest f = safeOpen(i, name, 1, (e) -> errorHolder[0] = e);
			if(f == null || isNotTruthy(f)) {
				cb.complete(new DatabaseOpen(true, false, errorHolder[0] != null ? errorHolder[0] : "database open request was null or undefined", null));
				return;
			}
			addEventListener(f, "blocked", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(new DatabaseOpen(false, true, "database locked", null));
				}
			});
			addEventListener(f, "success", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(new DatabaseOpen(false, false, null, f.getResult()));
				}
			});
			addEventListener(f, "error", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(new DatabaseOpen(true, false, "open error", null));
				}
			});
			addEventListener(f, "upgradeneeded", new EventListener<IDBVersionChangeEvent>() {
				@Override
				public void handleEvent(IDBVersionChangeEvent evt) {
					f.getResult().createObjectStore("filesystem", IDBObjectStoreParameters.create().keyPath("path"));
				}
			});
		}

		@Async
		protected static native BooleanResult deleteFile(IDBDatabase db, String name);

		private static void deleteFile(IDBDatabase db, String name, final AsyncCallback<BooleanResult> cb) {
			IDBTransaction tx = db.transaction("filesystem", "readwrite");
			final IDBRequest r = tx.objectStore("filesystem").delete(makeTheFuckingKeyWork(name));
			addEventListener(r, "success", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(BooleanResult.TRUE);
				}
			});
			addEventListener(r, "error", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(BooleanResult.FALSE);
				}
			});
		}

		@Async
		protected static native BooleanResult deleteFiles(IDBDatabase db, String[] names);

		private static void deleteFiles(IDBDatabase db, String[] names, final AsyncCallback<BooleanResult> cb) {
			if(names.length == 0) {
				cb.complete(BooleanResult.TRUE);
				return;
			}
			IDBTransaction tx = db.transaction("filesystem", "readwrite");
			final boolean[] finished = new boolean[1];
			for(int i = 0; i < names.length; ++i) {
				tx.objectStore("filesystem").delete(makeTheFuckingKeyWork(names[i]));
			}
			addEventListener(tx, "complete", new EventHandler() {
				@Override
				public void handleEvent() {
					if(!finished[0]) {
						finished[0] = true;
						cb.complete(BooleanResult.TRUE);
					}
				}
			});
			EventHandler fail = new EventHandler() {
				@Override
				public void handleEvent() {
					if(!finished[0]) {
						finished[0] = true;
						cb.complete(BooleanResult.FALSE);
					}
				}
			};
			addEventListener(tx, "error", fail);
			addEventListener(tx, "abort", fail);
		}

		@JSBody(params = { "store", "prefix" }, script =
				"store.delete([prefix]);"
				+ "var p = prefix.endsWith('/') ? prefix : prefix + '/';"
				+ "store.delete(IDBKeyRange.bound([p], [p + '\\uffff']));")
		private static native void issuePrefixDelete(IDBObjectStore store, String prefix);

		@Async
		protected static native BooleanResult deletePrefix(IDBDatabase db, String prefix);

		private static void deletePrefix(IDBDatabase db, String prefix, final AsyncCallback<BooleanResult> cb) {
			IDBTransaction tx = db.transaction("filesystem", "readwrite");
			final boolean[] finished = new boolean[1];
			issuePrefixDelete(tx.objectStore("filesystem"), prefix);
			addEventListener(tx, "complete", new EventHandler() {
				@Override
				public void handleEvent() {
					if(!finished[0]) {
						finished[0] = true;
						cb.complete(BooleanResult.TRUE);
					}
				}
			});
			EventHandler fail = new EventHandler() {
				@Override
				public void handleEvent() {
					if(!finished[0]) {
						finished[0] = true;
						cb.complete(BooleanResult.FALSE);
					}
				}
			};
			addEventListener(tx, "error", fail);
			addEventListener(tx, "abort", fail);
		}

		@JSBody(params = { "obj" }, script = "return (typeof obj === \"undefined\") ? null : ((typeof obj.data === \"undefined\") ? null : obj.data);")
		protected static native ArrayBuffer readRow(JSObject obj);

		@JSBody(params = { "obj" }, script = "return [obj];")
		private static native JSObject makeTheFuckingKeyWork(String k);

		@Async
		protected static native ArrayBuffer readWholeFile(IDBDatabase db, String name);

		private static void readWholeFile(IDBDatabase db, String name, final AsyncCallback<ArrayBuffer> cb) {
			IDBTransaction tx = db.transaction("filesystem", "readonly");
			final IDBGetRequest r = tx.objectStore("filesystem").get(makeTheFuckingKeyWork(name));
			addEventListener(r, "success", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(readRow(r.getResult()));
				}
			});
			addEventListener(r, "error", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(null);
				}
			});

		}

		@JSBody(params = { "k" }, script = "return ((typeof k) === \"string\") ? k : (((typeof k) === \"undefined\") ? null : (((typeof k[0]) === \"string\") ? k[0] : null));")
		private static native String readKey(JSObject k);

		@Async
		protected static native Integer iterateFiles(IDBDatabase db, final String prefix, boolean rw, final VFSFilenameIterator itr);

		@JSBody(params = { "store", "prefix" }, script =
				"if(!prefix) return store.openCursor();"
				+ "var p = prefix.endsWith('/') ? prefix : prefix + '/';"
				// This database's compatibility key representation is the one-element
				// array produced by makeTheFuckingKeyWork(). Plain string bounds compare
				// in a different IndexedDB key domain and therefore returned an empty
				// cursor even when every requested file existed.
				+ "return store.openCursor(IDBKeyRange.bound([p], [p + '\\uffff']));" )
		private static native IDBCursorRequest openPrefixCursor(IDBObjectStore store, String prefix);

		@JSBody(params = { "cursor", "key" }, script = "cursor.continue([key]);")
		private static native void continueAt(IDBCursor cursor, String key);

		@Async
		protected static native Integer iterateChildren(IDBDatabase db, final String prefix,
				final VFSFilenameIterator itr);

		private static void iterateChildren(IDBDatabase db, final String prefix,
				final VFSFilenameIterator itr, final AsyncCallback<Integer> cb) {
			IDBTransaction tx = db.transaction("filesystem", "readonly");
			final IDBCursorRequest r = openPrefixCursor(tx.objectStore("filesystem"), prefix);
			final int[] res = new int[1];
			final String dirPrefix = prefix.isEmpty() ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
			addEventListener(r, "success", new EventHandler() {
				@Override
				public void handleEvent() {
					IDBCursor c = r.getResult();
					if(c == null || c.getKey() == null) {
						cb.complete(res[0]);
						return;
					}
					String key = readKey(c.getKey());
					if(key == null || !key.startsWith(dirPrefix)) {
						c.doContinue();
						return;
					}
					String relative = key.substring(dirPrefix.length());
					int slash = relative.indexOf('/');
					String child = slash < 0 ? key : dirPrefix + relative.substring(0, slash);
					try {
						itr.next(child);
						++res[0];
					}catch(VFSIterator2.BreakLoop ex) {
						cb.complete(res[0]);
						return;
					}
					if(slash < 0) {
						c.doContinue();
					}else {
						// Jump past every key below this immediate child instead of invoking
						// one TeaVM callback per texture/model in a large resource pack.
						continueAt(c, child + "/\uffff");
					}
				}
			});
			addEventListener(r, "error", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(res[0] > 0 ? res[0] : -1);
				}
			});
		}

		private static void iterateFiles(IDBDatabase db, final String prefix, boolean rw, final VFSFilenameIterator itr, final AsyncCallback<Integer> cb) {
			IDBTransaction tx = db.transaction("filesystem", rw ? "readwrite" : "readonly");
			// Do not walk the entire database and discard unrelated keys in Java. A
			// resource pack with ~2,000 files made every directory probe pay ~2,000
			// IndexedDB cursor callbacks. Restrict the cursor to this directory's
			// key range; the boundary slash also excludes same-prefix siblings.
			final IDBCursorRequest r = openPrefixCursor(tx.objectStore("filesystem"), prefix);
			final int[] res = new int[1];
			final boolean b = prefix.length() == 0;
			// Directory-boundary prefix: a key belongs to directory <prefix> only if it IS
			// <prefix> or lives under "<prefix>/". A bare startsWith(prefix) also matched
			// SIBLINGS that merely share the name prefix ("worlds/New World" wrongly matched
			// "worlds/New World (1)/..."), so deleting one world purged every "New World (N)"
			// duplicate — the whole worlds list. Mirrors JDBCFilesystem's "<path>/%" query.
			final String dirPrefix = (b || prefix.endsWith("/")) ? prefix : prefix + "/";
			addEventListener(r, "success", new EventHandler() {
				@Override
				public void handleEvent() {
					IDBCursor c = r.getResult();
					if(c == null || c.getKey() == null) {
						cb.complete(res[0]);
						return;
					}
					String k = readKey(c.getKey());
					if(k != null) {
						if(b || k.equals(prefix) || k.startsWith(dirPrefix)) {
							int ci = res[0]++;
							try {
								itr.next(k);
							}catch(VFSIterator2.BreakLoop ex) {
								cb.complete(res[0]);
								return;
							}
						}
					}
					c.doContinue();
				}
			});
			addEventListener(r, "error", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(res[0] > 0 ? res[0] : -1);
				}
			});
		}

		@Async
		protected static native BooleanResult fileExists(IDBDatabase db, String name);

		private static void fileExists(IDBDatabase db, String name, final AsyncCallback<BooleanResult> cb) {
			IDBTransaction tx = db.transaction("filesystem", "readonly");
			final IDBCountRequest r = tx.objectStore("filesystem").count(makeTheFuckingKeyWork(name));
			addEventListener(r, "success", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(BooleanResult._new(r.getResult() > 0));
				}
			});
			addEventListener(r, "error", new EventHandler() {
				@Override
				public void handleEvent() {
					cb.complete(BooleanResult.FALSE);
				}
			});
		}

		@JSBody(params = { "pat", "dat" }, script = "return { path: pat, data: dat };")
		protected static native JSObject writeRow(String name, ArrayBuffer data);

		@Async
		protected static native BooleanResult writeWholeFile(IDBDatabase db, String name, ArrayBuffer data);

		private static void writeWholeFile(IDBDatabase db, String name, ArrayBuffer data, final AsyncCallback<BooleanResult> cb) {
			IDBTransaction tx = db.transaction("filesystem", "readwrite");
			final boolean[] finished = new boolean[1];
			tx.objectStore("filesystem").put(writeRow(name, data));
			// A successful put request only means IndexedDB accepted the request into the
			// transaction. The transaction can still abort afterward (for example when the
			// origin is out of quota). Save callers must not resume until the commit boundary.
			addEventListener(tx, "complete", new EventHandler() {
				@Override
				public void handleEvent() {
					if(!finished[0]) {
						finished[0] = true;
						cb.complete(BooleanResult.TRUE);
					}
				}
			});
			EventHandler fail = new EventHandler() {
				@Override
				public void handleEvent() {
					if(!finished[0]) {
						finished[0] = true;
						cb.complete(BooleanResult.FALSE);
					}
				}
			};
			addEventListener(tx, "error", fail);
			addEventListener(tx, "abort", fail);
		}

		@Async
		protected static native BooleanResult writeWholeFiles(IDBDatabase db, String[] names, ArrayBuffer[] data);

		private static void writeWholeFiles(IDBDatabase db, String[] names, ArrayBuffer[] data,
				final AsyncCallback<BooleanResult> cb) {
			if(names.length != data.length) {
				cb.complete(BooleanResult.FALSE);
				return;
			}
			if(names.length == 0) {
				cb.complete(BooleanResult.TRUE);
				return;
			}
			IDBTransaction tx = db.transaction("filesystem", "readwrite");
			final boolean[] finished = new boolean[1];
			for(int i = 0; i < names.length; ++i) {
				tx.objectStore("filesystem").put(writeRow(names[i], data[i]));
			}
			addEventListener(tx, "complete", new EventHandler() {
				@Override
				public void handleEvent() {
					if(!finished[0]) {
						finished[0] = true;
						cb.complete(BooleanResult.TRUE);
					}
				}
			});
			EventHandler fail = new EventHandler() {
				@Override
				public void handleEvent() {
					if(!finished[0]) {
						finished[0] = true;
						cb.complete(BooleanResult.FALSE);
					}
				}
			};
			addEventListener(tx, "error", fail);
			addEventListener(tx, "abort", fail);
		}

	}

}
