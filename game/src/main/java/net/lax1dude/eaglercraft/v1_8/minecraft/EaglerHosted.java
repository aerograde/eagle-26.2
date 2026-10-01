package net.lax1dude.eaglercraft.v1_8.minecraft;

/**
 * 26.2 hosted-mode flag: true when the game runs on the Eagler runtime with the
 * game-owned window/context (set by LWJGLEntryPoint before EagRuntime.create()).
 * Game-facing glue lives in this package, mirroring the upstream workspace layout.
 */
public class EaglerHosted {

	public static final boolean ACTIVE = Boolean.getBoolean("eagler.hosted");

	/**
	 * Direct compile-time hosted marker for shared client sources. TeaVM folds a
	 * direct call to true before dependency analysis; desktop executes the body
	 * and therefore preserves the exact historical {@link #ACTIVE} behavior.
	 */
	@org.teavm.interop.PlatformMarker
	public static boolean isActive() {
		return ACTIVE;
	}

	/**
	 * Do not use {@link #ACTIVE} as the sole browser-runtime discriminator.
	 * TeaVM may initialize this class while the module is being instantiated,
	 * before ClientMain has installed the hosted system property and platform
	 * callbacks. In that case the final field remains false for the lifetime of
	 * the tab even though the client is running in the browser.
	 */
	@org.teavm.interop.PlatformMarker
	public static boolean isBrowserRuntime() {
		return ACTIVE || Boolean.getBoolean("eagler.hosted") || webGpuBackendFactory != null
				|| webAssetMapSupplier != null || webDownloadBytes != null || webDownloadBytesAsync != null;
	}

	/**
	 * When non-null, the web entry point (platform-teavm ClientMain) supplies the
	 * GpuBackend (WebGL2) here and
	 * PreferredGraphicsApi.getBackendsToTry() returns ONLY this backend — the
	 * GlBackend/VulkanBackend desktop paths are never constructed. Null on desktop.
	 */
	public static java.util.function.Supplier<com.mojang.blaze3d.systems.GpuBackend> webGpuBackendFactory = null;

	/**
	 * When non-null, the web entry point supplies the in-RAM EPK asset map
	 * (PlatformAssets: 19,550 entries keyed
	 * "assets/&lt;ns&gt;/..." with no leading slash) and ClientPackSource builds the
	 * built-in pack from {@link EaglerEPKPackResources} instead of the classpath
	 * .mcassetsroot probe (which finds nothing under TeaVM). Null on desktop —
	 * desktop hosted mode keeps the vanilla classpath pack.
	 */
	public static java.util.function.Supplier<java.util.Map<String, byte[]>> webAssetMapSupplier = null;

	/**
	 * When non-null, the web entry point (platform-teavm ClientMain) supplies the
	 * DOM crash overlay here
	 * (ClientMain::showCrashScreen) and Minecraft's crash paths display the full
	 * crash report on the classic Eagler crash page instead of calling
	 * System.exit() — which is a NO-OP on TeaVM (TRuntime.exit is an empty
	 * method), so without this callback a crashed client keeps ticking and
	 * re-crashing every frame until the tab runs out of memory. Null on desktop.
	 */
	public static java.util.function.Consumer<String> webCrashReporter = null;

	/**
	 * Eagler 26.2 heap-stats seam: when non-null, the web entry point supplies real
	 * browser heap numbers as {usedBytes, totalBytes, limitBytes} (Chromium
	 * performance.memory), or returns null when the browser does not expose them.
	 * The F3 'minecraft:memory' entry uses this instead of Runtime.maxMemory/
	 * totalMemory/freeMemory, which TeaVM constant-folds to meaningless values
	 * (512MiB donor cap / Integer.MAX_VALUE / Integer.MAX_VALUE). Null on desktop.
	 */
	public static java.util.function.Supplier<long[]> webHeapStats = null;

	/**
	 * Browser client-allocation seam. Returns
	 * {liveBytes, peakBytes, liveAllocationCount, totalAllocationCount, freeCount}
	 * for the explicit MemoryUtil/NativeImage/vertex allocation pool. These are
	 * logical client allocations backed by browser-managed JavaScript objects;
	 * they are not added to performance.memory because that could double-count
	 * the same storage. Null on desktop.
	 */
	public static java.util.function.Supplier<long[]> webClientAllocationStats = null;

	/**
	 * Browser renderer-memory seam. Returns
	 * {allocatedGpuBytes, reservedGpuBytes, gpuHeapCount, gpuAllocationCount,
	 * stagedAllocationCount}. These are terrain-renderer buffers only; browsers do
	 * not expose total tab/process RSS, so F3 labels them separately.
	 */
	public static java.util.function.Supplier<long[]> webRendererMemoryStats = null;

	/**
	 * Browser CPU-description seam. Browsers intentionally hide CPU model and clock
	 * speed, but expose the logical concurrency available to this tab.
	 */
	public static java.util.function.Supplier<String> webCpuInfo = null;

	/**
	 * Browser fullscreen seam. The DOM implementation lives in platform-teavm;
	 * shared Window code uses these hooks instead of GLFW monitor APIs, which do
	 * not represent browser fullscreen.
	 */
	public static Runnable webToggleFullscreen = null;
	public static java.util.function.BooleanSupplier webIsFullscreen = null;

	/**
	 * Browser HTTP download seam. The TeaVM JDK URLConnection implementation is a
	 * link-only stub, so hosted callers must fetch bytes through PlatformRuntime.
	 * Null means desktop mode; a null result means the browser rejected the request
	 * (HTTP error, CORS, network failure, or cancellation).
	 */
	public static java.util.function.Function<String, byte[]> webDownloadBytes = null;

	/**
	 * Non-blocking browser HTTP download seam. Resource-pack downloads must use this
	 * callback form: waiting on the synchronous compatibility wrapper from a TeaVM
	 * executor can still monopolize the browser thread and, during early connection
	 * setup, could fall through to the link-only URLConnection implementation.
	 */
	public static AsyncByteDownloader webDownloadBytesAsync = null;

	@FunctionalInterface
	public interface AsyncByteDownloader {
		void download(String url, java.util.function.Consumer<byte[]> callback);
	}

}
