package net.lax1dude.eaglercraft.v1_8.internal;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;

import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMUtils;

/**
 * Web impl of the chunk-compression seam ({@link net.lax1dude.eaglercraft.v1_8.internal.PlatformChunkCompression}
 * stub in :platform). Gzips/gunzips chunk NBT with the browser-native Compression Streams API,
 * whose zlib does not have the TeaVM 0.13 JZlib {@code Inflater} 32 KiB-window truncation bug
 * (Eagler/TeaVM gap #14) — so full ~53 KiB chunks round-trip correctly and web chunk blobs can be
 * gzip-compressed again instead of stored raw. Shadows the :platform stub by FQN on the web
 * classpath.
 *
 * <p>The Streams API is promise-based, so {@link #gzip}/{@link #gunzip} bridge it to a blocking
 * green-thread call via TeaVM {@code @Async}. They must only run on a suspendable green thread;
 * {@code EaglerVFSChunkStorage}'s callers already suspend on the async IndexedDB VFS, so that
 * holds. Output of {@link #gzip} is a standard gzip stream (1f 8b magic), byte-compatible with the
 * desktop {@code java.util.zip} path and with legacy gzip chunks from older builds.</p>
 */
public final class PlatformChunkCompression {

	private PlatformChunkCompression() {
	}

	private static byte supportCache = 0; // 0 = unknown, 1 = yes, 2 = no

	public static boolean isSupported() {
		if (supportCache == 0) {
			supportCache = (byte) (hasCompressionStream() ? 1 : 2);
		}
		return supportCache == 1;
	}

	@JSBody(params = {}, script = "return (typeof CompressionStream !== \"undefined\") && (typeof DecompressionStream !== \"undefined\");")
	private static native boolean hasCompressionStream();

	public static byte[] gzip(byte[] raw) {
		if (raw == null) {
			return null;
		}
		ArrayBuffer out = gzipAsync(TeaVMUtils.unwrapArrayBuffer(raw));
		return out == null ? null : TeaVMUtils.wrapByteArrayBuffer(out);
	}

	public static byte[] gunzip(byte[] gz) {
		if (gz == null) {
			return null;
		}
		ArrayBuffer out = gunzipAsync(TeaVMUtils.unwrapArrayBuffer(gz));
		return out == null ? null : TeaVMUtils.wrapByteArrayBuffer(out);
	}

	@Async
	private static native ArrayBuffer gzipAsync(ArrayBuffer input);

	private static void gzipAsync(ArrayBuffer input, final AsyncCallback<ArrayBuffer> cb) {
		gzip0(input, cb::complete);
	}

	@Async
	private static native ArrayBuffer gunzipAsync(ArrayBuffer input);

	private static void gunzipAsync(ArrayBuffer input, final AsyncCallback<ArrayBuffer> cb) {
		gunzip0(input, cb::complete);
	}

	@JSFunctor
	private static interface ArrayBufferCallback extends JSObject {
		void call(ArrayBuffer buf);
	}

	@JSBody(params = { "input", "callback" }, script =
			"try {"
			+ "new Response(new Blob([input]).stream().pipeThrough(new CompressionStream(\"gzip\"))).arrayBuffer()"
			+ ".then(function(ab){ callback(ab); }, function(e){ callback(null); });"
			+ "} catch(e) { callback(null); }")
	private static native void gzip0(ArrayBuffer input, ArrayBufferCallback callback);

	@JSBody(params = { "input", "callback" }, script =
			"try {"
			+ "new Response(new Blob([input]).stream().pipeThrough(new DecompressionStream(\"gzip\"))).arrayBuffer()"
			+ ".then(function(ab){ callback(ab); }, function(e){ callback(null); });"
			+ "} catch(e) { callback(null); }")
	private static native void gunzip0(ArrayBuffer input, ArrayBufferCallback callback);
}
