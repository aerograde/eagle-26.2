/*
 * Copyright (c) 2024 lax1dude. All Rights Reserved.
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

package net.lax1dude.eaglercraft.v1_8;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.lax1dude.eaglercraft.v1_8.internal.IEaglerFilesystem;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformFilesystem;
import net.lax1dude.eaglercraft.v1_8.internal.RamdiskFilesystemImpl;
import net.lax1dude.eaglercraft.v1_8.internal.VFSFilenameIterator;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.ByteBuffer;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

public class Filesystem {

	private static final Logger logger = LogManager.getLogger("PlatformFilesystem");

	private static final Map<String,FilesystemHandle> openFilesystems = new HashMap<>();

	public static IEaglerFilesystem getHandleFor(String dbName) {
		FilesystemHandle handle = openFilesystems.get(dbName);
		if(handle != null) {
			++handle.refCount;
			return new FilesystemHandleWrapper(handle);
		}
		IEaglerFilesystem handleImpl = null;
		if(!EagRuntime.getConfiguration().isRamdiskMode()) {
			handleImpl = PlatformFilesystem.initializePersist(dbName);
		}
		if(handleImpl == null) {
			handleImpl = new RamdiskFilesystemImpl(dbName);
		}
		if(handleImpl.isRamdisk()) {
			logger.warn("Using RAMDisk filesystem for database \"{}\", data will not be saved to local storage!", dbName);
		}
		handle = new FilesystemHandle(handleImpl);
		openFilesystems.put(dbName, handle);
		return new FilesystemHandleWrapper(handle);
	}

	public static void closeAllHandles() {
		for(FilesystemHandle handle : openFilesystems.values()) {
			handle.refCount = 0;
			handle.handle.closeHandle();
		}
		openFilesystems.clear();
	}

	private static class FilesystemHandle {

		private final IEaglerFilesystem handle;
		private final Map<String, Long> pathRevisions;
		private long revisionCounter;
		private int refCount;
		
		private FilesystemHandle(IEaglerFilesystem handle) {
			this.handle = handle;
			this.pathRevisions = new HashMap<>();
			this.revisionCounter = 0L;
			this.refCount = 1;
		}

		private static String revisionKey(String pathName) {
			if(pathName == null || pathName.isEmpty()) {
				return "";
			}
			int start = pathName.charAt(0) == '/' ? 1 : 0;
			int slash = pathName.indexOf('/', start);
			return slash < 0 ? pathName.substring(start) : pathName.substring(start, slash);
		}

		private void bumpRevision(String pathName) {
			long revision = ++revisionCounter;
			pathRevisions.put(revisionKey(pathName), revision);
		}

		private void bumpRevisions(String[] pathNames) {
			if(pathNames.length == 0) {
				return;
			}
			long revision = ++revisionCounter;
			Set<String> roots = new HashSet<>();
			for(int i = 0; i < pathNames.length; ++i) {
				roots.add(revisionKey(pathNames[i]));
			}
			for(String root : roots) {
				pathRevisions.put(root, revision);
			}
		}

		private long getRevision(String pathName) {
			String key = revisionKey(pathName);
			return key.isEmpty() ? revisionCounter : pathRevisions.getOrDefault(key, 0L);
		}

	}

	private static class FilesystemHandleWrapper implements IEaglerFilesystem {

		private final FilesystemHandle handle;
		private final IEaglerFilesystem handleImpl;
		private boolean closed;

		private FilesystemHandleWrapper(FilesystemHandle handle) {
			this.handle = handle;
			this.handleImpl = handle.handle;
			this.closed = false;
		}

		@Override
		public String getFilesystemName() {
			return handleImpl.getFilesystemName();
		}

		@Override
		public String getInternalDBName() {
			return handleImpl.getInternalDBName();
		}

		@Override
		public boolean isRamdisk() {
			return handleImpl.isRamdisk();
		}

		@Override
		public boolean eaglerDelete(String pathName) {
			boolean ret = handleImpl.eaglerDelete(pathName);
			if(ret) {
				handle.bumpRevision(pathName);
			}
			return ret;
		}

		@Override
		public ByteBuffer eaglerRead(String pathName) {
			return handleImpl.eaglerRead(pathName);
		}

		@Override
		public void eaglerWrite(String pathName, ByteBuffer data) {
			handleImpl.eaglerWrite(pathName, data);
			handle.bumpRevision(pathName);
		}

		@Override
		public void eaglerWriteBatch(String[] pathNames, ByteBuffer[] data) {
			handleImpl.eaglerWriteBatch(pathNames, data);
			handle.bumpRevisions(pathNames);
		}

		@Override
		public void eaglerDeleteBatch(String[] pathNames) {
			handleImpl.eaglerDeleteBatch(pathNames);
			handle.bumpRevisions(pathNames);
		}

		@Override
		public void eaglerDeleteRecursive(String pathName) {
			handleImpl.eaglerDeleteRecursive(pathName);
			handle.bumpRevision(pathName);
		}

		@Override
		public boolean eaglerExists(String pathName) {
			return handleImpl.eaglerExists(pathName);
		}

		@Override
		public boolean eaglerMove(String pathNameOld, String pathNameNew) {
			boolean ret = handleImpl.eaglerMove(pathNameOld, pathNameNew);
			if(ret) {
				handle.bumpRevision(pathNameOld);
				handle.bumpRevision(pathNameNew);
			}
			return ret;
		}

		@Override
		public int eaglerCopy(String pathNameOld, String pathNameNew) {
			int ret = handleImpl.eaglerCopy(pathNameOld, pathNameNew);
			if(ret >= 0) {
				handle.bumpRevision(pathNameNew);
			}
			return ret;
		}

		@Override
		public int eaglerSize(String pathName) {
			return handleImpl.eaglerSize(pathName);
		}

		@Override
		public void eaglerIterate(String pathName, VFSFilenameIterator itr, boolean recursive) {
			handleImpl.eaglerIterate(pathName, itr, recursive);
		}

		@Override
		public void eaglerIterateChildren(String pathName, VFSFilenameIterator itr) {
			// Preserve backend-specific immediate-child iteration. Falling back to the
			// interface default here turns a one-directory probe into a recursive walk
			// of every texture/model in an imported resource pack.
			handleImpl.eaglerIterateChildren(pathName, itr);
		}

		@Override
		public long eaglerGetRevision(String pathName) {
			return handle.getRevision(pathName);
		}

		@Override
		public void closeHandle() {
			if(!closed && handle.refCount > 0) {
				closed = true;
				--handle.refCount;
				if(handle.refCount <= 0) {
					logger.info("Releasing filesystem handle for: \"{}\"", handleImpl.getFilesystemName());
					handleImpl.closeHandle();
					openFilesystems.remove(handleImpl.getFilesystemName());
				}
			}
		}

	}

}
