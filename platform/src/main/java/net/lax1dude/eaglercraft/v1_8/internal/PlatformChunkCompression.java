package net.lax1dude.eaglercraft.v1_8.internal;

/**
 * Web-only chunk-compression seam. The browser gzips/gunzips chunk NBT natively via the
 * Compression Streams API ({@code CompressionStream}/{@code DecompressionStream}, backed by the
 * engine's real zlib), which — unlike the TeaVM 0.13 JZlib {@code Inflater} — does not truncate
 * streams that inflate past the 32 KiB deflate window (Eagler/TeaVM gap #14). A full chunk is
 * ~53 KiB inflated, so this is what lets web chunk blobs be gzip-compressed again instead of
 * stored raw (the c78 interim, ~6.6x larger in IndexedDB and in the IOWorker RAM cache).
 *
 * <p>Desktop compresses/decompresses chunks with the JVM's {@code java.util.zip} directly in
 * {@code EaglerVFSChunkStorage} and never calls this — the stub exists only so {@code :game}
 * compiles against the platform contract. The real impl lives in {@code :platform-teavm} and
 * shadows this by FQN on the web classpath (same mechanism as the other platform native stubs,
 * e.g. {@link PlatformAudioDecode}).</p>
 *
 * <p>Both {@link #gzip} and {@link #gunzip} are green-thread blocking on web (implemented with
 * TeaVM {@code @Async} over the Streams API promises), so they may ONLY be called from a
 * suspendable green thread. In {@code EaglerVFSChunkStorage} the read/write paths already suspend
 * on the async IndexedDB VFS ({@code VFile2.getAllBytes}/{@code setAllBytes}), so calling these
 * from there adds no new constraint.</p>
 */
public final class PlatformChunkCompression {

	private PlatformChunkCompression() {
	}

	/**
	 * @return true only on web when the Compression Streams API is available. Desktop and old
	 *         browsers return false; callers must fall back (desktop: java.util.zip; old browser:
	 *         store uncompressed).
	 */
	public static boolean isSupported() {
		return false;
	}

	/** gzip {@code raw} (standard 1f 8b stream). Returns null on failure. Web-only. */
	public static byte[] gzip(byte[] raw) {
		throw new UnsupportedOperationException("PlatformChunkCompression is web-only");
	}

	/** gunzip a standard gzip stream. Returns null on failure. Web-only. */
	public static byte[] gunzip(byte[] gz) {
		throw new UnsupportedOperationException("PlatformChunkCompression is web-only");
	}
}
