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

package net.lax1dude.eaglercraft.v1_8.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSExceptions;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.core.JSString;
import org.teavm.jso.dom.css.CSSStyleDeclaration;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.MessageEvent;
import org.teavm.jso.dom.html.HTMLCanvasElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;
import org.teavm.jso.dom.xml.Node;
import org.teavm.jso.typedarrays.ArrayBuffer;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EaglercraftUUID;
import net.lax1dude.eaglercraft.v1_8.EaglercraftVersion;
import net.lax1dude.eaglercraft.v1_8.Filesystem;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.ByteBuffer;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.EaglerArrayBufferAllocator;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.FloatBuffer;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.IntBuffer;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.ClientMain;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.DebugConsoleWindow;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.EPKDownloadHelper;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.ImmediateContinue;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.MessageChannel;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMBlobURLManager;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMClientConfigAdapter;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMFetchJS;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMUtils;
import net.lax1dude.eaglercraft.v1_8.internal.vfs2.VFile2;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

/**
 * Upstream: EaglercraftX-1.8-workspace-master src/teavm PlatformRuntime (browser
 * backend). Copied per the reimplementation directive, same FQN as the :platform
 * stub (classpath-order shadowing, mirrors :platform-lwjgl).
 *
 * Phase 3.2a scope (docs/phase3-web-toolchain-map.md increment 3.2): boot the
 * Eagler runtime far enough to download assets.epk into the asset map and show
 * the crash overlay on failure. Deliberately trimmed for this increment:
 *
 *  - GL / WebGL context creation + EarlyLoadScreen GL painting: removed. The
 *    canvas is created and sized but no GL context is acquired.
 *    // TODO(3.3): GpuDeviceBackend on WebGL2 (WebGL2RenderingContext,
 *    //            WebGLBackBuffer, PlatformOpenGL, EarlyLoadScreen).
 *  - PlatformInput (DOM/pointer/keyboard hooks): not copied yet.
 *    // TODO(3.2b): PlatformInput.initHooks / device metrics / pressAnyKeyScreen.
 *  - PlatformAudio: // TODO(3.5): WebAudio graph (blaze3d Library deviation).
 *  - WebRTC / voice / webview / relay / screen-record / boot menu / ES6 shims /
 *    runtime deobfuscator / data-URL manager: DROPPED per the map's verdicts.
 *  - TeaVMUtils @InjectedBy/@GeneratedBy generators: replaced with TeaVM 0.13
 *    JSO typed-array conversions (see TeaVMUtils).
 *  - zlib: implemented on java.util.zip (TeaVM 0.13 classlib provides it),
 *    matching :platform-lwjgl instead of upstream's com.jcraft.jzlib.
 */
public class PlatformRuntime {

	static final Logger logger = LogManager.getLogger("BrowserRuntime");
	private static IEaglerFilesystem gameDirectoryFilesystem;

	/** The authoritative page store, including its RAMDisk fallback. */
	public static IEaglerFilesystem getGameDirectoryFilesystem() {
		return gameDirectoryFilesystem;
	}

	public static Window win = null;
	public static HTMLDocument doc = null;
	public static HTMLElement root = null;
	public static HTMLElement parent = null;
	public static HTMLCanvasElement canvas = null;

	private static String windowMessagePostOrigin = null;
	private static EventListener<MessageEvent> windowMessageListener = null;

	static boolean useDelayOnSwap = false;
	static boolean immediateContinueSupport = false;
	static MessageChannel immediateContinueChannel = null;
	static Runnable currentMsgChannelContinueHack = null;
	static ImmediateContinue currentLegacyContinueHack = null;
	private static final Object immediateContLock = new Object();

	static boolean hasFetchSupport = false;

	public static boolean isDeobfStackTraces = true;

	private static final JSObject steadyTimeFunc = getSteadyTimeFunc();

	@JSBody(params = { }, script = "return ((typeof performance !== \"undefined\") && (typeof performance.now === \"function\"))"
			+ "? performance.now.bind(performance)"
			+ ": (function(epochStart){ return function() { return Date.now() - epochStart; }; })(Date.now());")
	private static native JSObject getSteadyTimeFunc();

	@JSBody(params = { "win" }, script = "return win.devicePixelRatio || 1.0;")
	private static native double getDevicePixelRatio(Window win);

	public static void create() {
		win = Window.current();
		doc = win.getDocument();
		DebugConsoleWindow.initialize(win);
		PlatformApplication.setMCServerWindowGlobal(null);

		// TODO(3.6): ES6 shim status report (EnumES6Shims dropped for now)

		TeaVMBlobURLManager.initialize();

		logger.info("Creating main game canvas");

		root = doc.getElementById(ClientMain.configRootElementId);
		if(root == null) {
			throw new RuntimeInitializationFailureException("Root element \"" + ClientMain.configRootElementId + "\" was not found in this document!");
		}

		root.getClassList().add("_eaglercraftX_root_element");

		Node nodeler;
		while((nodeler = root.getLastChild()) != null && TeaVMUtils.isTruthy(nodeler)) {
			root.removeChild(nodeler);
		}

		CSSStyleDeclaration style = root.getStyle();
		style.setProperty("position", "relative");
		style.setProperty("width", "100%");
		style.setProperty("height", "100%");
		style.setProperty("min-width", "0");
		style.setProperty("min-height", "0");
		style.setProperty("overflow", "hidden");
		style.setProperty("background-color", "black");

		TeaVMClientConfigAdapter teavmCfg = (TeaVMClientConfigAdapter) getClientConfigAdapter();
		// TODO(3.2b): isAutoFixLegacyStyleAttrTeaVM viewport/<body> retro-patch
		//             (upstream path pulls in guava Sets/Iterators, not on the
		//             platform-teavm classpath; deferred with mobile support)

		useDelayOnSwap = teavmCfg.isUseDelayOnSwapTeaVM();

			parent = doc.createElement("div");
			parent.getClassList().add("_eaglercraftX_wrapper_element");
			style = parent.getStyle();
			style.setProperty("position", "absolute");
			style.setProperty("inset", "0");
			style.setProperty("width", "100%");
			style.setProperty("height", "100%");
			style.setProperty("min-width", "0");
			style.setProperty("min-height", "0");
			style.setProperty("overflow", "hidden");
			style.setProperty("background-color", "black");
		root.appendChild(parent);
		ClientMain.configRootElement = parent; // hack

		sleep(10);

		ByteBuffer endiannessTestBytes = allocateByteBuffer(4);
		try {
			endiannessTestBytes.asIntBuffer().put(0x6969420);
			if (((endiannessTestBytes.get(0) & 0xFF) | ((endiannessTestBytes.get(1) & 0xFF) << 8)
					| ((endiannessTestBytes.get(2) & 0xFF) << 16) | ((endiannessTestBytes.get(3) & 0xFF) << 24)) != 0x6969420) {
				throw new PlatformIncompatibleException("Big endian CPU detected! (somehow)");
			}else {
				logger.info("Endianness: this CPU is little endian");
			}
		}finally {
			freeByteBuffer(endiannessTestBytes);
		}

		double r = getDevicePixelRatio(win);
		if(r < 0.01) r = 1.0;
		int iw = parent.getClientWidth();
		int ih = parent.getClientHeight();
		int sw = (int)(r * iw);
		int sh = (int)(r * ih);
		if(sw < 1) sw = 1;
		if(sh < 1) sh = 1;

		canvas = (HTMLCanvasElement) doc.createElement("canvas");

			style = canvas.getStyle();
			canvas.getClassList().add("_eaglercraftX_canvas_element");
			style.setProperty("display", "block");
			style.setProperty("position", "absolute");
			style.setProperty("inset", "0");
			style.setProperty("width", "100%");
			style.setProperty("height", "100%");
		style.setProperty("z-index", "1");
		style.setProperty("image-rendering", "pixelated");
		style.setProperty("touch-action", "pan-x pan-y");
		style.setProperty("-webkit-touch-callout", "none");
		style.setProperty("-webkit-tap-highlight-color", "rgba(255, 255, 255, 0)");

		canvas.setWidth(sw);
		canvas.setHeight(sh);

		parent.appendChild(canvas);

		try {
			win.addEventListener("message", windowMessageListener = new EventListener<MessageEvent>() {
				@Override
				public void handleEvent(MessageEvent evt) {
					handleWindowMessage(evt);
				}
			});
		}catch(Throwable t) {
			throw new RuntimeInitializationFailureException("Exception while registering window message event handlers", t);
		}

		checkImmediateContinueSupport();

		// TODO(3.2b): PlatformInput.initHooks(win, parent, canvas) + window size

		if(teavmCfg.isUseXHRFetchTeaVM()) {
			hasFetchSupport = false;
			logger.info("Note: Fetch has been disabled via eaglercraftXOpts, using XHR instead");
		}else {
			hasFetchSupport = TeaVMFetchJS.checkFetchSupport();
			if(!hasFetchSupport) {
				logger.error("Detected fetch as unsupported, using XHR instead!");
			}
		}

		// Phase 3.3a: WebGL 2.0 CONTEXT CREATION only (see getWebGL2Context below).
		// The rest of the GL half of this method — PlatformOpenGL.setCurrentContext,
		// WebGLBackBuffer.initBackBuffer, EarlyLoadScreen.paintScreen, and the
		// GpuBackend selection seam — is Phase 3.3b. The WebGL2 GpuDeviceBackend
		// itself lives in net.lax1dude...internal.webgl2 and is constructed from
		// the context returned by getWebGL2Context().

		if(PlatformAssets.assets == null || !PlatformAssets.assets.isEmpty()) {
			PlatformAssets.assets = new HashMap<>();
		}

		EPKDownloadHelper.downloadEPKFilesOfVersion(ClientMain.configEPKFiles,
				teavmCfg.isEnableEPKVersionCheckTeaVM() ? EaglercraftVersion.EPKVersionIdentifier : null,
				PlatformAssets.assets);

		logger.info("Loaded {} resources from EPKs", PlatformAssets.assets.size());

		// TODO(3.6): boot menu (BootMenuEntryPoint) + eagtek.png final load screen

		logger.info("Initializing filesystem...");

		IEaglerFilesystem resourcePackFilesystem = Filesystem.getHandleFor(getClientConfigAdapter().getResourcePacksDB());
		VFile2.setPrimaryFilesystem(resourcePackFilesystem);
		// TODO(3.3): EaglerFolderResourcePack.setSupported(true) (class not yet copied)

		// Persistence (Matej: "save worlds, servers and basically all preferences"):
		// TeaVM's default java.io backing is an IN-MEMORY filesystem, so options.txt,
		// servers.dat and every world evaporated on reload. Swap in the IndexedDB-
		// backed adapter (worldsDB database) so the whole game dir tree persists,
		// exactly like 1.8's IndexedDB worlds.
		try {
			IEaglerFilesystem gameFilesystem = Filesystem.getHandleFor(getClientConfigAdapter().getWorldsDB());
			gameDirectoryFilesystem = gameFilesystem;
			org.teavm.runtime.fs.VirtualFileSystemProvider.setInstance(
					new net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerVirtualFilesystem(gameFilesystem));
			logger.info("Game directory filesystem mounted: {}", gameFilesystem.getInternalDBName());
		}catch(Throwable t) {
			logger.error("Failed to mount the persistent filesystem — worlds/options will NOT survive reloads!");
			logger.error(t);
		}

		// TODO(3.5): PlatformAudio.initialize()

		logger.info("Platform initialization complete");
	}

	public static void destroy() {
		logger.fatal("Game tried to destroy the context! Browser runtime can't do that");
	}

	public static EnumPlatformType getPlatformType() {
		return org.teavm.classlib.PlatformDetector.isWebAssemblyGC()
				? EnumPlatformType.WASM_GC : EnumPlatformType.JAVASCRIPT;
	}

	public static EnumPlatformAgent getPlatformAgent() {
		return EnumPlatformAgent.getFromUA(getUserAgentString());
	}

	@JSBody(params = { }, script = "return navigator.userAgent||null;")
	public static native String getUserAgentString();

	public static EnumPlatformOS getPlatformOS() {
		return EnumPlatformOS.getFromUA(getUserAgentString());
	}

	public static void requestANGLE(EnumPlatformANGLE plaf) {
	}

	public static EnumPlatformANGLE getPlatformANGLE() {
		return EnumPlatformANGLE.fromGLRendererString(getGLRenderer());
	}

	/**
	 * Phase 3.3a: acquire a WebGL 2.0 rendering context on the main game canvas
	 * with the attributes the recon prescribes (self-test §5 / work item 5):
	 * antialias off, depth on, alpha off, high-performance. Returns null if the
	 * browser cannot provide a WebGL2 context. This is the ONLY GL piece wired in
	 * 3.3a; backend selection + WebGLBackBuffer are 3.3b.
	 */
	@JSBody(params = { "canvas" }, script = "try { return canvas.getContext(\"webgl2\", { antialias: false, depth: true, "
			+ "alpha: false, powerPreference: \"high-performance\", premultipliedAlpha: false, "
			+ "preserveDrawingBuffer: false, stencil: false }); } catch(e) { return null; }")
	private static native JSObject getWebGL2Context0(HTMLCanvasElement canvas);

	public static net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2RenderingContextExt getWebGL2Context() {
		if (canvas == null) {
			return null;
		}
		JSObject ctx = getWebGL2Context0(canvas);
		if (ctx == null || !TeaVMUtils.isTruthy(ctx)) {
			return null;
		}
		return (net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2RenderingContextExt) ctx;
	}

	public static String getGLVersion() {
		// TODO(3.3): PlatformOpenGL._wglGetString(GL_VERSION) once WebGL2 lands
		return "WebGL 2.0 (deferred to Phase 3.3)";
	}

	public static String getGLRenderer() {
		// TODO(3.3): PlatformOpenGL._wglGetString(GL_RENDERER) once WebGL2 lands
		return "null";
	}

	public static ByteBuffer allocateByteBuffer(int length) {
		return EaglerArrayBufferAllocator.allocateByteBuffer(length);
	}

	public static IntBuffer allocateIntBuffer(int length) {
		return EaglerArrayBufferAllocator.allocateIntBuffer(length);
	}

	public static FloatBuffer allocateFloatBuffer(int length) {
		return EaglerArrayBufferAllocator.allocateFloatBuffer(length);
	}

	public static ByteBuffer castPrimitiveByteArray(byte[] array) {
		return EaglerArrayBufferAllocator.wrapByteBufferTeaVM(TeaVMUtils.unwrapByteArray(array));
	}

	public static IntBuffer castPrimitiveIntArray(int[] array) {
		return EaglerArrayBufferAllocator.wrapIntBufferTeaVM(TeaVMUtils.unwrapIntArray(array));
	}

	public static FloatBuffer castPrimitiveFloatArray(float[] array) {
		return EaglerArrayBufferAllocator.wrapFloatBufferTeaVM(TeaVMUtils.unwrapFloatArray(array));
	}

	public static byte[] castNativeByteBuffer(ByteBuffer buffer) {
		return TeaVMUtils.wrapUnsignedByteArray(EaglerArrayBufferAllocator.getDataView8Unsigned(buffer));
	}

	public static int[] castNativeIntBuffer(IntBuffer buffer) {
		return TeaVMUtils.wrapIntArray(EaglerArrayBufferAllocator.getDataView32(buffer));
	}

	public static float[] castNativeFloatBuffer(FloatBuffer buffer) {
		return TeaVMUtils.wrapFloatArray(EaglerArrayBufferAllocator.getDataView32F(buffer));
	}

	public static void freeByteBuffer(ByteBuffer byteBuffer) {

	}

	public static void freeIntBuffer(IntBuffer intBuffer) {

	}

	public static void freeFloatBuffer(FloatBuffer floatBuffer) {

	}

	public static boolean hasFetchSupportTeaVM() {
		return hasFetchSupport;
	}

	public static void downloadRemoteURIByteArray(String assetPackageURI, final Consumer<byte[]> cb) {
		downloadRemoteURIByteArray(assetPackageURI, false, cb);
	}

	public static void downloadRemoteURIByteArray(String assetPackageURI, boolean useCache, final Consumer<byte[]> cb) {
		downloadRemoteURI(assetPackageURI, useCache, arr -> cb.accept(arr == null ? null : TeaVMUtils.wrapByteArrayBuffer(arr)));
	}

	public static byte[] downloadRemoteURIByteArray(String assetPackageURI) {
		return downloadRemoteURIByteArray(assetPackageURI, true);
	}

	public static byte[] downloadRemoteURIByteArray(String assetPackageURI, boolean forceCache) {
		ArrayBuffer arr = downloadRemoteURI(assetPackageURI, forceCache);
		return arr == null ? null : TeaVMUtils.wrapByteArrayBuffer(arr);
	}

	public static void downloadRemoteURI(String assetPackageURI, final Consumer<ArrayBuffer> cb) {
		downloadRemoteURI(assetPackageURI, false, cb);
	}

	public static void downloadRemoteURI(String assetPackageURI, boolean useCache, final Consumer<ArrayBuffer> cb) {
		if(hasFetchSupport) {
			downloadRemoteURIFetch(assetPackageURI, useCache, new AsyncCallback<ArrayBuffer>() {
				@Override
				public void complete(ArrayBuffer result) {
					cb.accept(result);
				}

				@Override
				public void error(Throwable e) {
					EagRuntime.debugPrintStackTrace(e);
					cb.accept(null);
				}
			});
		}else {
			downloadRemoteURIXHR(assetPackageURI, new AsyncCallback<ArrayBuffer>() {
				@Override
				public void complete(ArrayBuffer result) {
					cb.accept(result);
				}

				@Override
				public void error(Throwable e) {
					EagRuntime.debugPrintStackTrace(e);
					cb.accept(null);
				}
			});
		}
	}

	@Async
	private static native ArrayBuffer downloadRemoteURIXHR(final String assetPackageURI);

	private static void downloadRemoteURIXHR(final String assetPackageURI, final AsyncCallback<ArrayBuffer> cb) {
		// TODO(3.3): data: URL fallback (TeaVMDataURLManager) dropped for now
		TeaVMFetchJS.doXHRDownload(assetPackageURI, cb::complete);
	}

	@Async
	private static native ArrayBuffer downloadRemoteURIFetch(final String assetPackageURI, final boolean forceCache);

	private static void downloadRemoteURIFetch(final String assetPackageURI, final boolean useCache, final AsyncCallback<ArrayBuffer> cb) {
		// TODO(3.3): data: URL fallback (TeaVMDataURLManager) dropped for now
		TeaVMFetchJS.doFetchDownload(assetPackageURI, useCache ? "force-cache" : "no-store", cb::complete);
	}

	public static ArrayBuffer downloadRemoteURI(String assetPackageURI) {
		if(hasFetchSupport) {
			return downloadRemoteURIFetch(assetPackageURI, true);
		}else {
			return downloadRemoteURIXHR(assetPackageURI);
		}
	}

	public static ArrayBuffer downloadRemoteURI(final String assetPackageURI, final boolean forceCache) {
		if(hasFetchSupport) {
			return downloadRemoteURIFetch(assetPackageURI, forceCache);
		}else {
			return downloadRemoteURIXHR(assetPackageURI);
		}
	}

	public static boolean isDebugRuntime() {
		return false;
	}

	public static void writeCrashReport(String crashDump) {
		ClientMain.showCrashScreen(crashDump);
	}

	public static void showContextLostScreen(String crashDump) {
		ClientMain.showContextLostScreen(crashDump);
	}

	@JSBody(params = { "evt", "mainWin" }, script = "return evt.source === mainWin;")
	private static native boolean sourceEquals(MessageEvent evt, Window mainWin);

	protected static void handleWindowMessage(MessageEvent evt) {
		if(sourceEquals(evt, win)) {
			boolean b = false;
			ImmediateContinue cont;
			synchronized(immediateContLock) {
				cont = currentLegacyContinueHack;
				if(cont != null) {
					try {
						b = cont.isValidToken(evt.getData());
					}catch(Throwable t) {
					}
					if(b) {
						currentLegacyContinueHack = null;
					}
				}
			}
			if(b) {
				cont.execute();
			}
		}
		// TODO(3.x): PlatformWebView.onWindowMessageRecieved(evt) (webview dropped)
	}

	public static void swapDelayTeaVM() {
		if(!useDelayOnSwap && immediateContinueSupport) {
			immediateContinueTeaVM0();
		}else {
			sleep(0);
		}
	}

	public static void immediateContinue() {
		if(immediateContinueSupport) {
			immediateContinueTeaVM0();
		}else {
			sleep(0);
		}
	}

	public static boolean immediateContinueSupported() {
		return immediateContinueSupport;
	}

	@Async
	private static native void immediateContinueTeaVM0();

	private static void immediateContinueTeaVM0(final AsyncCallback<Void> cb) {
		synchronized(immediateContLock) {
			if(immediateContinueChannel != null) {
				if(currentMsgChannelContinueHack != null) {
					// Rendering and resource baking can yield from separate cooperative threads.
					// Preserve the channel's owner and resume this waiter independently.
					Window.setTimeout(() -> cb.complete(null), 0);
					return;
				}
				currentMsgChannelContinueHack = () -> {
					cb.complete(null);
				};
				try {
					immediateContinueChannel.getPort2().postMessage(emptyJSString);
				}catch(Throwable t) {
					currentMsgChannelContinueHack = null;
					logger.error("Caught error posting immediate continue, using setTimeout instead");
					Window.setTimeout(() -> cb.complete(null), 0);
				}
			}else {
				if(currentLegacyContinueHack != null) {
					// Do not replace the token belonging to the already pending waiter.
					Window.setTimeout(() -> cb.complete(null), 0);
					return;
				}
				final JSString token = JSString.valueOf(EaglercraftUUID.randomUUID().toString());
				currentLegacyContinueHack = new ImmediateContinue() {

					@Override
					public boolean isValidToken(JSObject someObject) {
						return token == someObject;
					}

					@Override
					public void execute() {
						cb.complete(null);
					}

				};
				try {
					win.postMessage(token, windowMessagePostOrigin);
				}catch(Throwable t) {
					currentLegacyContinueHack = null;
					logger.error("Caught error posting immediate continue, using setTimeout instead");
					Window.setTimeout(() -> cb.complete(null), 0);
				}
			}
		}
	}

	private static void checkImmediateContinueSupport() {
		immediateContinueSupport = false;
		windowMessagePostOrigin = getOriginForPost(win);

		int stat = checkImmediateContinueSupport0();
		if(stat == IMMEDIATE_CONT_SUPPORTED) {
			immediateContinueSupport = true;
			return;
		}else if(stat == IMMEDIATE_CONT_FAILED_NOT_ASYNC) {
			logger.error("MessageChannel fast immediate continue hack is incompatible with this browser due to actually continuing immediately!");
		}else if(stat == IMMEDIATE_CONT_FAILED_NOT_CONT) {
			logger.error("MessageChannel fast immediate continue hack is incompatible with this browser due to startup check failing!");
		}else if(stat == IMMEDIATE_CONT_FAILED_EXCEPTIONS) {
			logger.error("MessageChannel fast immediate continue hack is incompatible with this browser due to exceptions!");
		}
		logger.info("Note: Using legacy fast immediate continue based on window.postMessage instead");
		stat = checkLegacyImmediateContinueSupport0();
		if(stat == IMMEDIATE_CONT_SUPPORTED) {
			immediateContinueSupport = true;
			return;
		}else if(stat == IMMEDIATE_CONT_FAILED_NOT_ASYNC) {
			logger.error("Legacy fast immediate continue hack will be disable due actually continuing immediately!");
			return;
		}
		logger.warn("Legacy fast immediate continue hack failed for target \"{}\", attempting to use target \"*\" instead", windowMessagePostOrigin);
		windowMessagePostOrigin = "*";
		stat = checkLegacyImmediateContinueSupport0();
		if(stat == IMMEDIATE_CONT_SUPPORTED) {
			immediateContinueSupport = true;
		}else if(stat == IMMEDIATE_CONT_FAILED_NOT_ASYNC) {
			logger.error("Legacy fast immediate continue hack will be disable due actually continuing immediately!");
		}else if(stat == IMMEDIATE_CONT_FAILED_NOT_CONT) {
			logger.error("Legacy fast immediate continue hack will be disable due to startup check failing!");
		}else if(stat == IMMEDIATE_CONT_FAILED_EXCEPTIONS) {
			logger.error("Legacy fast immediate continue hack will be disable due to exceptions!");
		}
	}

	private static final JSString emptyJSString = JSString.valueOf("");

	private static final int IMMEDIATE_CONT_SUPPORTED = 0;
	private static final int IMMEDIATE_CONT_FAILED_NOT_ASYNC = 1;
	private static final int IMMEDIATE_CONT_FAILED_NOT_CONT = 2;
	private static final int IMMEDIATE_CONT_FAILED_EXCEPTIONS = 3;

	private static int checkImmediateContinueSupport0() {
		try {
			if(!MessageChannel.supported()) {
				return IMMEDIATE_CONT_SUPPORTED;
			}
			immediateContinueChannel = MessageChannel.create();
			immediateContinueChannel.getPort1().addEventListener("message", new EventListener<MessageEvent>() {
				@Override
				public void handleEvent(MessageEvent evt) {
					Runnable toRun;
					synchronized(immediateContLock) {
						toRun = currentMsgChannelContinueHack;
						currentMsgChannelContinueHack = null;
					}
					if(toRun != null) {
						toRun.run();
					}
				}
			});
			immediateContinueChannel.getPort1().start();
			immediateContinueChannel.getPort2().start();
			final boolean[] checkMe = new boolean[1];
			checkMe[0] = false;
			currentMsgChannelContinueHack = () -> {
				checkMe[0] = true;
			};
			immediateContinueChannel.getPort2().postMessage(emptyJSString);
			if(checkMe[0]) {
				currentMsgChannelContinueHack = null;
				if(immediateContinueChannel != null) {
					safeShutdownChannel(immediateContinueChannel);
				}
				immediateContinueChannel = null;
				return IMMEDIATE_CONT_FAILED_NOT_ASYNC;
			}
			sleep(10);
			currentMsgChannelContinueHack = null;
			if(!checkMe[0]) {
				if(immediateContinueChannel != null) {
					safeShutdownChannel(immediateContinueChannel);
				}
				immediateContinueChannel = null;
				return IMMEDIATE_CONT_FAILED_NOT_CONT;
			}else {
				return IMMEDIATE_CONT_SUPPORTED;
			}
		}catch(Throwable t) {
			currentMsgChannelContinueHack = null;
			if(immediateContinueChannel != null) {
				safeShutdownChannel(immediateContinueChannel);
			}
			immediateContinueChannel = null;
			return IMMEDIATE_CONT_FAILED_EXCEPTIONS;
		}
	}

	private static void safeShutdownChannel(MessageChannel chan) {
		try {
			chan.getPort1().close();
		}catch(Throwable tt) {
		}
		try {
			chan.getPort2().close();
		}catch(Throwable tt) {
		}
	}

	private static int checkLegacyImmediateContinueSupport0() {
		try {
			final JSString token = JSString.valueOf(EaglercraftUUID.randomUUID().toString());
			final boolean[] checkMe = new boolean[1];
			checkMe[0] = false;
			currentLegacyContinueHack = new ImmediateContinue() {

				@Override
				public boolean isValidToken(JSObject someObject) {
					return token == someObject;
				}

				@Override
				public void execute() {
					checkMe[0] = true;
				}

			};
			win.postMessage(token, windowMessagePostOrigin);
			if(checkMe[0]) {
				currentLegacyContinueHack = null;
				return IMMEDIATE_CONT_FAILED_NOT_ASYNC;
			}
			sleep(10);
			currentLegacyContinueHack = null;
			if(!checkMe[0]) {
				return IMMEDIATE_CONT_FAILED_NOT_CONT;
			}else {
				return IMMEDIATE_CONT_SUPPORTED;
			}
		}catch(Throwable t) {
			currentLegacyContinueHack = null;
			return IMMEDIATE_CONT_FAILED_EXCEPTIONS;
		}
	}

	@JSBody(params = { "win" }, script = "if((typeof location.origin === \"string\") && location.origin.length > 0) {"
			+ "var orig = location.origin; if(orig.indexOf(\"file:\") === 0) orig = \"null\"; return orig; }"
			+ "else return \"*\";")
	private static native String getOriginForPost(Window win);

	public static void removeEventHandlers() {
		try {
			immediateContinueSupport = false;
			if(windowMessageListener != null) {
				win.removeEventListener("message", windowMessageListener);
				windowMessageListener = null;
			}
		}catch(Throwable t) {
		}
		// TODO(3.2b): PlatformInput.removeEventHandlers()
	}

	public static void getStackTrace(Throwable t, Consumer<String> ret) {
		JSObject o = JSExceptions.getJSException(t);
		if(o != null && TeaVMUtils.isTruthy(o)) {
			try {
				String stack = TeaVMUtils.getStackSafe(o);
				if(stack != null) {
					// TODO(3.6): TeaVMRuntimeDeobfuscator source-map deobfuscation (DROP)
					String[] stackElements = stack.split("[\\r\\n]+");
					if(stackElements.length > 0) {
						for(int i = 0; i < stackElements.length; ++i) {
							String str = stackElements[i].trim();
							if(str.startsWith("at ")) {
								str = str.substring(3).trim();
							}
							ret.accept(str);
						}
						return;
					}
				}
			}catch(Throwable tt) {
				ret.accept("[ error: " + tt.toString() + " ]");
			}
		}
		getFallbackStackTrace(t, ret);
	}

	private static void getFallbackStackTrace(Throwable t, Consumer<String> ret) {
		StackTraceElement[] el = t.getStackTrace();
		if(el.length > 0) {
			for(int i = 0; i < el.length; ++i) {
				ret.accept(el[i].toString());
			}
		}else {
			ret.accept("[no stack trace]");
		}
	}

	@JSBody(params = { "o" }, script = "console.error(o);")
	public static native void printNativeExceptionToConsoleTeaVM(JSObject o);

	public static boolean printJSExceptionIfBrowser(Throwable t) {
		if(t != null) {
			JSObject o = JSExceptions.getJSException(t);
			if(o != null && TeaVMUtils.isTruthy(o)) {
				printNativeExceptionToConsoleTeaVM(o);
				return true;
			}
		}
		return false;
	}

	public static void exit() {
		logger.fatal("Game is attempting to exit!");
	}

	public static void setThreadName(String string) {
		currentThreadName = string;
	}

	public static long maxMemory() {
		return 1073741824l;
	}

	public static long totalMemory() {
		return 1073741824l;
	}

	public static long freeMemory() {
		return 1073741824l;
	}

	public static String getCallingClass(int backTrace) {
		return null;
	}

	public static OutputStream newDeflaterOutputStream(OutputStream os) throws IOException {
		return new DeflaterOutputStream(os);
	}

	public static int deflateFull(byte[] input, int inputOff, int inputLen, byte[] output, int outputOff,
			int outputLen) throws IOException {
		Deflater df = new Deflater();
		df.setInput(input, inputOff, inputLen);
		df.finish();
		int i = df.deflate(output, outputOff, outputLen);
		df.end();
		return i;
	}

	public static OutputStream newGZIPOutputStream(OutputStream os) throws IOException {
		return new GZIPOutputStream(os);
	}

	public static InputStream newInflaterInputStream(InputStream is) throws IOException {
		return new InflaterInputStream(is);
	}

	public static int inflateFull(byte[] input, int inputOff, int inputLen, byte[] output, int outputOff,
			int outputLen) throws IOException {
		Inflater df = new Inflater();
		int i;
		try {
			df.setInput(input, inputOff, inputLen);
			i = df.inflate(output, outputOff, outputLen);
		}catch(DataFormatException ex) {
			throw new IOException("Failed to inflate!", ex);
		}finally {
			df.end();
		}
		return i;
	}

	public static InputStream newGZIPInputStream(InputStream is) throws IOException {
		return new GZIPInputStream(is);
	}

	@JSBody(params = { }, script = "return location.protocol && location.protocol.toLowerCase() === \"https:\";")
	public static native boolean requireSSL();

	@JSBody(params = { }, script = "return location.protocol && location.protocol.toLowerCase() === \"file:\";")
	public static native boolean isOfflineDownloadURL();

	public static IClientConfigAdapter getClientConfigAdapter() {
		return TeaVMClientConfigAdapter.instance;
	}

	public static long randomSeed() {
		return (long)(Math.random() * 9007199254740991.0);
	}

	private static String currentThreadName = "main";

	public static String currentThreadName() {
		return currentThreadName;
	}

	@JSBody(params = { "steadyTimeFunc" }, script = "return steadyTimeFunc();")
	private static native double steadyTimeMillis0(JSObject steadyTimeFunc);

	public static double steadyTimeMillisTeaVM() {
		return steadyTimeMillis0(steadyTimeFunc);
	}

	public static long steadyTimeMillis() {
		return (long)steadyTimeMillis0(steadyTimeFunc);
	}

	public static long nanoTime() {
		return (long)(steadyTimeMillis0(steadyTimeFunc) * 1000000.0);
	}

	@Async
	public static native void sleep(int millis);

	private static void sleep(int millis, final AsyncCallback<Void> callback) {
		Window.setTimeout(() -> callback.complete(null), millis);
	}

	public static void postCreate() {
		// TODO(3.3): EarlyLoadScreen.paintFinal + destroy once GL lands
		// TODO(3.6): boot menu check
	}

	/** Phase 3.2a: how many resources the EPK download populated. */
	public static int getAssetCountTeaVM() {
		return PlatformAssets.assets == null ? 0 : PlatformAssets.assets.size();
	}

	/**
	 * Phase 3.3b: the raw EPK asset map for the vanilla-pack seam
	 * (EaglerHosted.webAssetMapSupplier -> EaglerEPKPackResources in :game).
	 */
	public static java.util.Map<String, byte[]> getAssetsMapTeaVM() {
		return PlatformAssets.assets;
	}

	/**
	 * serverWorker (Phase 3.4 seam b): populate THIS realm's EPK asset map from the URL(s) the
	 * client packed into the role:server meta. Same download machinery as the client boot (above);
	 * PlatformAssets.assets is package-private, so the worker's ClientMain.downloadWorkerEPK routes
	 * through here. No version check (the client already validated the same cached file).
	 */
	public static void populateWorkerAssets(net.lax1dude.eaglercraft.v1_8.internal.teavm.EPKFileEntry[] files) {
		if(PlatformAssets.assets == null || !PlatformAssets.assets.isEmpty()) {
			PlatformAssets.assets = new HashMap<>();
		}
		EPKDownloadHelper.downloadEPKFilesOfVersion(files, null, PlatformAssets.assets);
	}

	/**
	 * Phase 3.2a boot deliverable: present a black canvas. No GL context is
	 * acquired yet (that is Phase 3.3), so this paints via CSS background rather
	 * than taint the canvas with a 2D context that would later block WebGL2.
	 */
	public static void fillCanvasBlackTeaVM() {
		if(canvas != null) {
			canvas.getStyle().setProperty("background-color", "black");
		}
	}

	public static void setDisplayBootMenuNextRefresh(boolean en) {
		// TODO(3.6): BootMenuEntryPoint.setDisplayBootMenuNextRefresh(win, en)
		logger.warn("setDisplayBootMenuNextRefresh({}) ignored, boot menu is Phase 3.6", en);
	}

}
