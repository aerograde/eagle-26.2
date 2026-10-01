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

package net.lax1dude.eaglercraft.v1_8.internal;

import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.internal.buffer.ByteBuffer;

public interface IEaglerFilesystem {

	String getFilesystemName();

	String getInternalDBName();

	boolean isRamdisk();

	boolean eaglerDelete(String pathName);

	ByteBuffer eaglerRead(String pathName);

	void eaglerWrite(String pathName, ByteBuffer data);

	/**
	 * Write several files as one storage operation when the backend supports it.
	 * IndexedDB overrides this to use one read/write transaction; the fallback
	 * preserves the existing behavior for desktop and RAM filesystems.
	 */
	default void eaglerWriteBatch(String[] pathNames, ByteBuffer[] data) {
		if(pathNames.length != data.length) {
			throw new IllegalArgumentException("path/data batch length mismatch");
		}
		for(int i = 0; i < pathNames.length; ++i) {
			eaglerWrite(pathNames[i], data[i]);
		}
	}

	/** Delete several files in one backend transaction when possible. */
	default void eaglerDeleteBatch(String[] pathNames) {
		for(int i = 0; i < pathNames.length; ++i) {
			eaglerDelete(pathNames[i]);
		}
	}

	/**
	 * Delete one file or an entire directory tree. IndexedDB overrides this with
	 * a key-range delete so a large resource pack does not require thousands of
	 * Java cursor callbacks before deletion can even start.
	 */
	default void eaglerDeleteRecursive(String pathName) {
		List<String> paths = new ArrayList<>();
		eaglerIterate(pathName, paths::add, true);
		for(int start = 0; start < paths.size(); start += 512) {
			int end = Math.min(paths.size(), start + 512);
			eaglerDeleteBatch(paths.subList(start, end).toArray(new String[0]));
		}
	}

	boolean eaglerExists(String pathName);

	boolean eaglerMove(String pathNameOld, String pathNameNew);

	int eaglerCopy(String pathNameOld, String pathNameNew);

	int eaglerSize(String pathName);

	void eaglerIterate(String pathName, VFSFilenameIterator itr, boolean recursive);

	/**
	 * Visit one representative path for each immediate child of a directory.
	 * Backends with ordered keys can skip whole descendant subtrees; the fallback
	 * retains the recursive behavior expected by callers that derive the first
	 * relative path segment themselves.
	 */
	default void eaglerIterateChildren(String pathName, VFSFilenameIterator itr) {
		eaglerIterate(pathName, itr, true);
	}

	/**
	 * Monotonic revision for the top-level tree containing {@code pathName}.
	 * Shared-handle wrappers override this so read-side indexes can invalidate
	 * after another user of the same filesystem imports, moves, or deletes data.
	 */
	default long eaglerGetRevision(String pathName) {
		return 0L;
	}

	void closeHandle();

}
