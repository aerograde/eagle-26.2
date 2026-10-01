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

package net.lax1dude.eaglercraft.v1_8.internal.teavm;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.json.JSONException;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.core.JSArrayReader;
import org.teavm.jso.core.JSError;
import org.teavm.jso.dom.html.HTMLButtonElement;
import org.teavm.jso.dom.html.HTMLDocument;
import org.teavm.jso.dom.html.HTMLElement;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EaglercraftRandom;
import net.lax1dude.eaglercraft.v1_8.EaglercraftVersion;
import net.lax1dude.eaglercraft.v1_8.HString;
import net.lax1dude.eaglercraft.v1_8.internal.ContextLostError;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformApplication;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformIncompatibleException;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.opts.JSEaglercraftXOptsAssetsURI;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.opts.JSEaglercraftXOptsRoot;
import net.lax1dude.eaglercraft.v1_8.log4j.ILogRedirector;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;

/**
 * Upstream: EaglercraftX-1.8-workspace-master src/teavm ClientMain — the browser
 * entry point: reads window.eaglercraftXOpts, installs the DOM crash overlay,
 * runs EagRuntime.create(), then hands off to Minecraft's client main.
 *
 * Phase 3.2a adaptations (docs/phase3-web-toolchain-map.md):
 *  - Logging bridge (item 3): both the lax1dude log4j facade
 *    ({@link LogManager#logRedirector}) AND the slf4j facade
 *    ({@code org.slf4j.LoggerFactory.setEaglerRedirector}) are wired at the very
 *    top of {@link #_main()}, before anything else logs, funnelling every line
 *    into PlatformApplication.addLogMessage (the console/overlay stream).
 *  - Minecraft handoff DEFERRED: instead of {@code net.minecraft.client.main.Main}
 *    we log "eagler: web boot OK" + the asset count and present a black canvas.
 *    // TODO(3.3): net.minecraft.client.main.Main
 *  - Crash / context-lost / incompatible overlays are copied faithfully (the big
 *    red image + full debug dump). GL/ES6/deobfuscator/username/boot-menu/
 *    integrated-server pieces are stubbed or dropped for this increment.
 */
public class ClientMain {

	private static final String crashImage = "data:image/png;base64,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";

	// avoid inlining of constant
	private static String crashImageWrapper() {
		return crashImage.substring(0);
	}

	// Eagler 26.2: TeaVM 0.13 emits strict-mode JS — a bare-global assignment throws,
	// so the upstream guard is rewritten against globalThis
	@JSBody(params = {}, script = "if((typeof globalThis.__isEaglerX188Running === \"string\") && globalThis.__isEaglerX188Running === \"yes\") return true; globalThis.__isEaglerX188Running = \"yes\"; return false;")
	private static native boolean getRunningFlag();

	private static final PrintStream systemOut = System.out;
	private static final PrintStream systemErr = System.err;

	private static JSObject windowErrorHandler = null;

	public static void _main() {
		if(getRunningFlag()) {
			systemErr.println("ClientMain: [ERROR] eaglercraftx is already running!");
			return;
		}
		try {
			// ==== Eagler hosted flag (Phase 3.3b item 2) — MUST precede any :game class ====
			// EaglerHosted.ACTIVE reads Boolean.getBoolean("eagler.hosted") in its clinit;
			// nothing in :game may load before this line (mirrors LWJGLEntryPoint).
			System.setProperty("eagler.hosted", "true");
			// ==== netty allocator (world-load gap #7) — MUST precede any netty class ====
			// netty 4.2's default "adaptive" allocator hard-requires MethodHandles: its
			// ConcurrentSkipListIntObjMultimap.<clinit> throws ExceptionInInitializerError
			// when Lookup.findStatic fails (no graceful fallback), killing the integrated
			// server's LocalChannel bind on TeaVM. Unpooled is also the right choice on a
			// single-threaded green-thread runtime (pooling machinery is pure overhead).
			System.setProperty("io.netty.allocator.type", "unpooled");

			systemOut.println("ClientMain: [INFO] eaglercraftx is starting...");
			emitBootStageJS(5, "Preparing Eaglercraft 26.2…");

			// ==== logging bridge (Phase 3.2a item 3) — wired before anything else logs ====
			// (1) lax1dude log4j facade -> single web log stream.
			LogManager.logRedirector = new ILogRedirector() {
				@Override
				public void log(String txt, boolean err) {
					PlatformApplication.addLogMessage(txt, err);
				}
			};
			// (2) slf4j / com.mojang.logging facade (see teavm-compat, docs §3.0b)
			//     -> same stream, so Minecraft's own loggers land in the overlay too.
			org.slf4j.LoggerFactory.setEaglerRedirector((line, isErr) -> {
				ILogRedirector r = LogManager.logRedirector;
				if(r != null) {
					r.log(line, isErr != null && isErr.booleanValue());
				}
			});

			JSObject opts = getEaglerXOpts();

			if(opts == null) {
				systemErr.println("ClientMain: [ERROR] the \"window.eaglercraftXOpts\" variable is undefined");
				systemErr.println("ClientMain: [ERROR] eaglercraftx cannot start");
				Window.alert("ERROR: game cannot start, the \"window.eaglercraftXOpts\" variable is undefined");
				return;
			}

			try {
				JSEaglercraftXOptsRoot eaglercraftOpts = (JSEaglercraftXOptsRoot)opts;
				crashOnUncaughtExceptions = eaglercraftOpts.getCrashOnUncaughtExceptions(false);
				PlatformRuntime.isDeobfStackTraces = eaglercraftOpts.getDeobfStackTraces(true);

				configRootElementId = eaglercraftOpts.getContainer();
				if(configRootElementId == null) {
					throw new JSONException("window.eaglercraftXOpts.container is undefined!");
				}
				configRootElement = Window.current().getDocument().getElementById(configRootElementId);

				HTMLElement oldContent;
				while(configRootElement != null && (oldContent = configRootElement.querySelector("._eaglercraftX_wrapper_element")) != null) {
					oldContent.delete();
				}

				String epkSingleURL = eaglercraftOpts.getAssetsURI();
				if(epkSingleURL != null) {
					configEPKFiles = new EPKFileEntry[] { new EPKFileEntry(epkSingleURL, "") };
				}else {
					JSArrayReader<JSEaglercraftXOptsAssetsURI> epkURLs = eaglercraftOpts.getAssetsURIArray();
					int len = epkURLs.getLength();
					if(len == 0) {
						throw new JSONException("assetsURI array cannot be empty!");
					}
					configEPKFiles = new EPKFileEntry[len];
					for(int i = 0; i < len; ++i) {
						JSEaglercraftXOptsAssetsURI etr = epkURLs.get(i);
						String url = etr.getURL();
						if(url == null) {
							throw new JSONException("assetsURI is missing a url!");
						}
						configEPKFiles[i] = new EPKFileEntry(url, etr.getPath(""));
					}
				}

				configLocalesFolder = eaglercraftOpts.getLocalesURI("lang");
				if(configLocalesFolder.endsWith("/")) {
					configLocalesFolder = configLocalesFolder.substring(0, configLocalesFolder.length() - 1);
				}

					((TeaVMClientConfigAdapter)TeaVMClientConfigAdapter.instance).loadNative(eaglercraftOpts);
					net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.setEnabled(getPerfDebugFlag());

				systemOut.println("ClientMain: [INFO] configuration was successful");
				emitBootStageJS(15, "Loading configuration…");
			}catch(Throwable t) {
				systemErr.println("ClientMain: [ERROR] the \"window.eaglercraftXOpts\" variable is invalid");
				EagRuntime.debugPrintStackTraceToSTDERR(t);
				systemErr.println("ClientMain: [ERROR] eaglercraftx cannot start");
				Window.alert("ERROR: game cannot start, the \"window.eaglercraftXOpts\" variable is invalid: " + t.toString());
				return;
			}

			if(crashOnUncaughtExceptions) {
				systemOut.println("ClientMain: [INFO] registering crash handlers");

				windowErrorHandler = setWindowErrorHandler(Window.current(), new WindowErrorHandler() {

					@Override
					public void call(String message, String file, int line, int col, JSError error) {
						if(windowErrorHandler != null) {
							error = TeaVMUtils.ensureDefined(error);
							if(error == null) {
								systemErr.println("ClientMain: [ERROR] recieved error event, but the error is null, ignoring");
								return;
							}

							StringBuilder str = new StringBuilder();

							str.append("Native Browser Exception\n");
							str.append("----------------------------------\n");
							str.append("  Line: ").append((file == null ? "unknown" : file) + ":" + line + ":" + col).append('\n');
							str.append("  Type: ").append(error.getName()).append('\n');
							str.append("  Desc: ").append(error.getMessage() == null ? "null" : error.getMessage()).append('\n');

							if(message != null) {
								if(error.getMessage() == null || !message.endsWith(error.getMessage())) {
									str.append("  Desc: ").append(message).append('\n');
								}
							}

							str.append("----------------------------------\n\n");
							// TODO(3.6): TeaVMRuntimeDeobfuscator source-map deobfuscation (DROP)
							String stack = TeaVMUtils.getStackSafe(error);
							str.append(stack == null ? "No stack trace is available" : stack).append('\n');

							showCrashScreen(str.toString());
						}
					}

				});
			}

			systemOut.println("ClientMain: [INFO] initializing eaglercraftx runtime");
			emitBootStageJS(30, "Downloading assets…");

			try {
				EagRuntime.create();
			}catch(ContextLostError ex) {
				systemErr.println("ClientMain: [ERROR] webgl context lost during initialization!");
				try {
					showContextLostScreen(EagRuntime.getStackTrace(ex));
				}catch(Throwable t) {
				}
				return;
			}catch(PlatformIncompatibleException ex) {
				systemErr.println("ClientMain: [ERROR] this browser is incompatible with eaglercraftx!");
				systemErr.println("ClientMain: [ERROR] Reason: " + ex.getMessage());
				try {
					showIncompatibleScreen(ex.getMessage());
				}catch(Throwable t) {
				}
				return;
			}catch(Throwable t) {
				systemErr.println("ClientMain: [ERROR] eaglercraftx's runtime could not be initialized!");
				EagRuntime.debugPrintStackTraceToSTDERR(t);
				showCrashScreen("EaglercraftX's runtime could not be initialized!", t);
				systemErr.println("ClientMain: [ERROR] eaglercraftx cannot start");
				return;
			}

			// ==== Phase 3.2a boot marker (kept as the boot-log spine) ====
			Logger bootLogger = LogManager.getLogger("ClientMain");
			int assetCount = PlatformRuntime.getAssetCountTeaVM();
			bootLogger.info("eagler: web boot OK");
			bootLogger.info("eagler: {} resources loaded from EPK", Integer.valueOf(assetCount));
			systemOut.println("eagler: web boot OK (" + assetCount + " assets)");
			emitBootStageJS(65, "Loaded " + assetCount + " assets");
			PlatformRuntime.fillCanvasBlackTeaVM();

			// GZIP round-trip self-test: verifies the TGZIPOutputStream.flush() Z_BUF_ERROR(-5)
			// classlib fix (GZIPOutputStreamInject) that unblocks NbtIo.writeCompressed —
			// i.e. singleplayer world saves (level.dat) + profile. One-shot boot log.
			try {
				java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
				java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo);
				gz.write("eagler-gzip-selftest-payload".getBytes("UTF-8"));
				gz.close();
				byte[] comp = bo.toByteArray();
				java.util.zip.GZIPInputStream gi = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(comp));
				java.io.ByteArrayOutputStream ro = new java.io.ByteArrayOutputStream();
				byte[] tmp = new byte[64];
				int r;
				while ((r = gi.read(tmp)) > 0) {
					ro.write(tmp, 0, r);
				}
				gi.close();
				String back = new String(ro.toByteArray(), "UTF-8");
				systemOut.println("eagler-gzip-selftest: " + ("eagler-gzip-selftest-payload".equals(back)
						? ("OK (" + comp.length + " bytes compressed, round-trip verified)") : ("MISMATCH: " + back)));
			} catch (Throwable t) {
				systemErr.println("eagler-gzip-selftest: FAILED — world saves will not work: " + t);
				EagRuntime.debugPrintStackTraceToSTDERR(t);
			}

			// ==== Phase 3.2b input self-test (opt-in: eaglercraftXOpts.inputSelfTest) ====
			// Dead code unless the flag is set. Proves DOM keyboard/mouse/wheel events
			// flow through the real PlatformInput backend into the eagler queues and the
			// 26.2 host(GLFW) fields, before the Minecraft client (3.3) exists to pump them.
			if(getInputSelfTestFlag()) {
				try {
					InputSelfTest.run();
				}catch(Throwable t) {
					systemErr.println("eagler-input-selftest: EXCEPTION");
					EagRuntime.debugPrintStackTraceToSTDERR(t);
				}
			}

			// ==== Phase 3.3a WebGL2 backend self-test (opt-in: eaglercraftXOpts.webgl2SelfTest) ====
			// Dead code unless the flag is set. Proves the WebGL2 GpuDeviceBackend core
			// plumbing (context, GLSL-300es shader compile, buffer upload, VAO, draw,
			// readPixels) end to end, before the Minecraft client (3.3b) exists to drive it.
			if(getWebGL2SelfTestFlag()) {
				try {
					WebGL2SelfTest.run();
				}catch(Throwable t) {
					systemErr.println("eagler-webgl2-selftest: EXCEPTION");
					EagRuntime.debugPrintStackTraceToSTDERR(t);
				}
			}

			// ==== WebGPU backend increment-1 self-test (opt-in: eaglercraftXOpts.webgpuSelfTest
			//      or ?webgputest) ====
			// Dead code unless the flag is set. Requests a WebGPU adapter/device, compiles
			// the manifest 'position_color' WGSL from the shader pack, builds a tiny
			// pipeline, renders one red triangle to a 64x64 offscreen texture and reads the
			// centre pixel back — logging [WebGPUSelfTest] PASS/FAIL. Non-blocking to boot.
			if(getWebGPUSelfTestFlag()) {
				try {
					WebGPUSelfTest.run();
				}catch(Throwable t) {
					systemErr.println("[WebGPUSelfTest] EXCEPTION");
					EagRuntime.debugPrintStackTraceToSTDERR(t);
				}
			}

			// ==== Mesh-worker plan Phase A self-test (opt-in: eaglercraftXOpts.meshWorkerSelfTest
			//      or ?meshtest) ====
			// Dead code unless the flag is set. Spawns one mesh worker from the same
			// classes.js Blob, round-trips a synthetic SectionSnapshot through the
			// transferable-ArrayBuffer channel, and asserts byte-parity of the codec +
			// transfer (design doc §6 Phase A). Non-blocking: the echo compare logs
			// asynchronously once the event loop drains, so boot continues normally.
			if(getMeshWorkerSelfTestFlag()) {
				try {
					net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerSelfTest.run();
				}catch(Throwable t) {
					systemErr.println("[MeshWorkerSelfTest] EXCEPTION");
					EagRuntime.debugPrintStackTraceToSTDERR(t);
				}
			}

			// ==== Mesh-worker plan Phase B compile self-test (opt-in:
			//      eaglercraftXOpts.meshWorkerCompileTest or ?meshtest2) ====
			// Arms a poll that, once the world is loaded, byte-parity shadow-compares an
			// inline SectionCompiler bake against a worker bake of one real section
			// (design doc §6 Phase B). Dead code unless the flag is set; non-blocking.
			if(getMeshWorkerCompileTestFlag()) {
				try {
					net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerCompileTest.run();
				}catch(Throwable t) {
					systemErr.println("[MeshWorkerCompileTest] EXCEPTION");
					EagRuntime.debugPrintStackTraceToSTDERR(t);
				}
			}

				// ==== Mesh-worker plan Phase C: real gameplay meshing on a Web Worker pool ====
				// Opt-in: eaglercraftXOpts.meshWorkers === true OR ?meshworkers, disabled by
				// ?nomesh; default OFF this build (default-on flip happens after headless
				// verification). Registering the bridge here (before the 3.3b client handoff)
				// means it exists when LevelRenderer.invalidateCompiledGeometry later broadcasts
				// the baked model table and spawns the workers. Flag off -> no bridge -> the
				// stock inline green-thread dispatcher runs unchanged.
				if(getMeshWorkersFlag()) {
					try {
						net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerRuntime.setBridge(
							new net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerPool(getMeshWorkerVerifyFlag()));
						System.out.println("[MeshWorkerPool] bridge installed (meshWorkers enabled"
							+ (getMeshWorkerVerifyFlag() ? ", verify on)" : ")"));
					}catch(Throwable t) {
						systemErr.println("[MeshWorkerPool] EXCEPTION installing bridge");
						EagRuntime.debugPrintStackTraceToSTDERR(t);
					}
				}

			// ==== Phase 3.3b: the real Minecraft 26.2 client handoff ====
			// Seams injected before any gated :game class initializes deeper:
			//  (1) GPU backend factory — PreferredGraphicsApi.getBackendsToTry()
			//      returns ONLY the WebGL2 backend (context from PlatformRuntime);
			//  (2) vanilla-pack map — ClientPackSource serves the built-in pack
			//      from the EPK RAM map (EaglerEPKPackResources);
			//  (3) titleProbe flag -> WebGL2Surface (120-frame pixel/drawcall log).
			// Worker/SP seams stay unwired (SP join is Phase 3.4; the hosted pump's
			// SingleplayerServerController26.runTick() no-ops without a booted server).
			// Backend selection (WebGPU backend increment 1): default WebGL2. Choose the
			// modern WebGPU blaze3d backend ONLY when navigator.gpu exists AND the opt-in
			// flag is set (eaglercraftXOpts.webgpu === true OR ?webgpu). The default path
			// is untouched, so the existing WebGL2 client keeps booting exactly as before.
			if(getWebGPUFlag() && net.lax1dude.eaglercraft.v1_8.internal.webgpu.WebGPU.isSupported()) {
				systemOut.println("ClientMain: [INFO] WebGPU backend selected (navigator.gpu present + webgpu flag)");
				net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webGpuBackendFactory =
						() -> new net.lax1dude.eaglercraft.v1_8.internal.webgpu.WebGPUBackend();
			} else {
				net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webGpuBackendFactory =
						() -> new net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2Backend();
			}
			net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webAssetMapSupplier =
					PlatformRuntime::getAssetsMapTeaVM;
			net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webToggleFullscreen =
					net.lax1dude.eaglercraft.v1_8.internal.PlatformInput::toggleFullscreen;
			net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webIsFullscreen =
					net.lax1dude.eaglercraft.v1_8.internal.PlatformInput::isFullscreen;
			net.lax1dude.eaglercraft.v1_8.internal.webgl2.WebGL2Surface.titleProbeEnabled = getTitleProbeFlag();
			// (4) crash seam (Phase 3.3e) — System.exit is a NO-OP on TeaVM, so
			//     Minecraft's crash paths hand the finished crash report here: the
			//     classic Eagler crash page goes up, the client loop is stopped, and
			//     the green thread unwinds (see Minecraft.eaglerTerminalCrash). This
			//     is the 26.2 equivalent of 1.8's displayCrashReport ->
			//     PlatformRuntime.writeCrashReport -> ClientMain.showCrashScreen.
			net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webCrashReporter =
					ClientMain::showCrashScreen;
			// (5) heap-stats seam — the F3 'minecraft:memory' entry shows the real JS
			//     heap (performance.memory, Chromium-only) instead of TeaVM's
			//     constant-folded Runtime values (0%/399% nonsense). Null = hide.
				net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webHeapStats = () -> {
					if (!hasPerformanceMemory()) {
						return null;
					}
					return new long[] { (long) perfMemUsed(), (long) perfMemTotal(), (long) perfMemLimit() };
				};
				net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webClientAllocationStats = () ->
						new long[] {
							net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap.getLiveBytes(),
							net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap.getPeakBytes(),
							net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap.getLiveAllocations(),
							net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap.getAllocationCount(),
							net.lax1dude.eaglercraft.v1_8.internal.teavm.EaglerFakeHeap.getFreeCount()
						};
				net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webRendererMemoryStats = () -> {
					double[] rendererStats = rendererMemoryStats();
					if (rendererStats == null || rendererStats.length < 5) {
						return null;
					}
					long[] result = new long[5];
					for (int i = 0; i < result.length; ++i) {
						double value = rendererStats[i];
						result[i] = Double.isFinite(value) && value > 0.0 ? (long) value : 0L;
					}
					return result;
				};
				net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webCpuInfo =
						ClientMain::browserCpuInfo;
				net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webDownloadBytes =
						url -> PlatformRuntime.downloadRemoteURIByteArray(url, false);
				net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webDownloadBytesAsync =
						(url, callback) -> PlatformRuntime.downloadRemoteURIByteArray(url, false, callback);

			// Args mirror target_lwjgl_desktop eaglercraftDebugRuntime, plus
			// --offlineDeveloperMode: the browser must never block boot on the
			// (unreachable) auth services — profile/user futures complete offline.
			String[] mcArgs = new String[] {
					"--version", "26.2-eagler",
					"--gameDir", ".",
					"--assetsDir", "assets",
					// --assetIndex omitted on web: with it, GameConfig.getExternalAssetSource() runs
						// IndexedAssetSource.createIndexFs on assets/indexes/<n>.json (absent in the
						// browser VFS) -> "Can't open the resource index file" ERROR every boot. Web
						// assets come from the in-RAM EPK (webAssetMapSupplier -> EaglerEPKPackResources),
						// so the external object-store index is unused; omitting it returns the inert
						// assets-dir path instead of erroring.
					// --username is NEVER hardcoded: it comes from the Eagler profile
						// store (localStorage "<ns>.username"; the Profile screen will
						// write there once ported), with the classic 1.8 EaglerProfile
						// random default ("YeeishVool1234"-style) for fresh browsers.
					"--username", getEaglerUsername(),
					"--accessToken", "0",
					// --userType omitted: 26.2's arg parser dropped it, so "legacy" only
						// produced a "Completely ignored arguments: [--userType, legacy]" log line.
					"--offlineDeveloperMode"
			};

			// Eagler worker split (single-thread mode): the integrated-server supervisor
			// main/update live in the game module; inject them into the single-thread
			// worker so world creation works without a server Web Worker (Phase 3.4
			// worker data-plane is deferred). Mirrors the desktop LWJGLEntryPoint seam.
			// The Wasm-GC page ships a dedicated server-worker image. Do not pull the
			// entire server graph into the page image a second time for an obsolete
			// single-thread fallback. The JS target keeps its original fallback.
			// Keep this as a DIRECT @PlatformMarker call. TeaVM folds direct marker
			// invocations before dependency analysis, but it does not fold a helper
			// wrapper around PlatformDetector. Using WasmWorkerBootstrap.isWasmGC()
			// here therefore retained both method references below and pulled the
			// complete integrated-server graph into the page Wasm even though the
			// Wasm-GC shell always supplies the dedicated server image.
			if(!EagRuntime.isWasmGC()) {
				net.lax1dude.eaglercraft.v1_8.sp.server.internal.teavm.SingleThreadWorker
						.setSingleThreadServerMain(net.lax1dude.eaglercraft.v1_8.sp.server.EaglerIntegratedServerWorker26::singleThreadMain);
				net.lax1dude.eaglercraft.v1_8.sp.server.internal.teavm.SingleThreadWorker
						.setSingleThreadServerUpdate(net.lax1dude.eaglercraft.v1_8.sp.server.EaglerIntegratedServerWorker26::singleThreadUpdate);
			}

			systemOut.println("ClientMain: [INFO] handing off to net.minecraft.client.main.Main");
			emitBootStageJS(80, "Starting Minecraft — registries, textures, shaders, and block models…");
			// paint yield: Main.main freezes the tab through registry bootstrap +
			// model bake + the (inline-executor) resource reload, so without giving
			// the browser one beat here the 80% status line never paints.
			try {
				Thread.sleep(60);
			} catch (InterruptedException ignored) {
			}
			try {
				net.minecraft.client.main.Main.main(mcArgs);
			}catch(Throwable t) {
				systemErr.println("ClientMain: [ERROR] Minecraft client crashed out of Main.main!");
				EagRuntime.debugPrintStackTraceToSTDERR(t);
				showCrashScreen("Minecraft client crashed out of Main.main!", t);
				return;
			}

		}finally {
			systemErr.println("ClientMain: [INFO] eaglercraftx main thread has exited");
		}
	}

	@JSBody(params = {}, script = "if(typeof eaglercraftXOpts === \"undefined\") {return null;}"
			+ "else if(typeof eaglercraftXOpts === \"string\") {return JSON.parse(eaglercraftXOpts);}"
			+ "else {return eaglercraftXOpts;}")
	private static native JSObject getEaglerXOpts();

	// Phase 3.2b: opt-in flag for the input self-test; tolerates opts being a JSON string.
	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
			+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
			+ "return !!(o && o.inputSelfTest === true);")
	private static native boolean getInputSelfTestFlag();

	// Phase 3.3a: opt-in flag for the WebGL2 backend self-test.
	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
				+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
				+ "var search = (typeof location !== \"undefined\" && location.search) ? location.search : \"\";"
				+ "return !!((o && o.webgl2SelfTest === true) || search.indexOf(\"webgl2test\") >= 0);")
	private static native boolean getWebGL2SelfTestFlag();

	// WebGPU backend increment 1: opt-in flag to select the WebGPU backend over
	// WebGL2. True if eaglercraftXOpts.webgpu === true OR the URL has ?webgpu.
	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
			+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
			+ "var urlFlag = false;"
			+ "if(typeof location !== \"undefined\") { try {"
			+ " var p = new URLSearchParams(location.search || \"\");"
			+ " urlFlag = p.has(\"webgpu\") && p.get(\"webgpu\") !== \"0\" && p.get(\"webgpu\") !== \"false\";"
			+ "} catch(e) {} }"
			+ "return !!((o && o.webgpu === true) || urlFlag);")
	private static native boolean getWebGPUFlag();

	// WebGPU backend increment 1: opt-in flag for the WebGPU backend self-test.
	// True if eaglercraftXOpts.webgpuSelfTest === true OR the URL has ?webgputest.
	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
			+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
			+ "var urlFlag = false;"
			+ "if(typeof location !== \"undefined\") { try {"
			+ " var p = new URLSearchParams(location.search || \"\");"
			+ " urlFlag = p.has(\"webgputest\") && p.get(\"webgputest\") !== \"0\" && p.get(\"webgputest\") !== \"false\";"
			+ "} catch(e) {} }"
			+ "return !!((o && o.webgpuSelfTest === true) || urlFlag);")
	private static native boolean getWebGPUSelfTestFlag();

	// Phase 3.3b: opt-in flag for the title-screen pixel/drawcall probe.
	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
			+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
			+ "return !!(o && o.titleProbe === true);")
	private static native boolean getTitleProbeFlag();

	// Mesh-worker plan Phase A: opt-in flag for the mesh-worker echo self-test.
	// True if eaglercraftXOpts.meshWorkerSelfTest === true OR the URL has ?meshtest
	// (so it can be triggered headlessly without editing opts).
	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
			+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
			+ "var urlFlag = (typeof location !== \"undefined\" && location.search"
			+ " && location.search.indexOf(\"meshtest\") >= 0);"
			+ "return !!((o && o.meshWorkerSelfTest === true) || urlFlag);")
	private static native boolean getMeshWorkerSelfTestFlag();

	// Mesh-worker plan Phase B: opt-in flag for the worker-compile byte-parity self-test.
	// True if eaglercraftXOpts.meshWorkerCompileTest === true OR the URL has ?meshtest2.
	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
			+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
			+ "var urlFlag = (typeof location !== \"undefined\" && location.search"
			+ " && location.search.indexOf(\"meshtest2\") >= 0);"
			+ "return !!((o && o.meshWorkerCompileTest === true) || urlFlag);")
	private static native boolean getMeshWorkerCompileTestFlag();

	// Mesh-worker plan Phase C: master flag for routing real gameplay meshing to the worker
	// pool. Default ON this build: enabled unless explicitly turned off. ?nomesh (the kill
	// switch) always wins; then an explicit eaglercraftXOpts.meshWorkers === false is honored;
	// ?meshworkers force-enables; absent opts default to ON.
	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
			+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
			+ "var search = (typeof location !== \"undefined\" && location.search) ? location.search : \"\";"
			+ "if(search.indexOf(\"nomesh\") >= 0) return false;"
			+ "if(search.indexOf(\"meshworkers\") >= 0) return true;"
			+ "if(o && o.meshWorkers === false) return false;"
			+ "return true;")
	private static native boolean getMeshWorkersFlag();

	// Mesh-worker plan Phase C: the 1-in-32 runtime byte-parity tripwire (a worker result is
	// also compiled inline and byte-compared). This is diagnostic work and is OFF by default;
	// eaglercraftXOpts.meshWorkerVerify === true or ?meshworkerverify explicitly enables it.
	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
			+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
			+ "var search = (typeof location !== \"undefined\" && location.search) ? location.search : \"\";"
			+ "if(search.indexOf(\"meshworkerverify\") >= 0) return true;"
			+ "return !!(o && o.meshWorkerVerify === true);")
	private static native boolean getMeshWorkerVerifyFlag();

	@JSBody(params = {}, script = "var o = (typeof eaglercraftXOpts !== \"undefined\") ? eaglercraftXOpts : null;"
			+ "if(typeof o === \"string\") { try { o = JSON.parse(o); } catch(e) { o = null; } }"
			+ "var search = (typeof location !== \"undefined\" && location.search) ? location.search : \"\";"
			+ "return !!((o && o.perfDebug === true) || search.indexOf(\"perfdebug\") >= 0);")
	private static native boolean getPerfDebugFlag();

	// F3 heap stats: performance.memory is Chromium-only (undefined elsewhere).
	@JSBody(params = {}, script = "try { var m = typeof performance !== \"undefined\" && performance.memory;"
			+ " return !!(m && Number(m.usedJSHeapSize) > 0 && Number(m.totalJSHeapSize) > 0"
			+ " && Number(m.jsHeapSizeLimit) > 0); } catch(e) { return false; }")
	private static native boolean hasPerformanceMemory();

	@JSBody(params = {}, script = "return Number(performance.memory.usedJSHeapSize) || 0;")
	private static native double perfMemUsed();

	@JSBody(params = {}, script = "return Number(performance.memory.totalJSHeapSize) || 0;")
	private static native double perfMemTotal();

	@JSBody(params = {}, script = "return Number(performance.memory.jsHeapSizeLimit) || 0;")
	private static native double perfMemLimit();

	@JSBody(params = {}, script = "try {"
			+ "var p = globalThis.__meshWorkerPool || {};"
			+ "return [p.gpuAllocatedBytes || 0, p.gpuCapacityBytes || 0,"
			+ "p.gpuHeaps || 0, p.gpuAllocations || 0, p.stagedAllocations || 0];"
			+ "} catch(e) { return null; }")
	private static native double[] rendererMemoryStats();

	@JSBody(params = {}, script = "try {"
			+ "var n = (navigator && navigator.hardwareConcurrency) | 0;"
			+ "return n > 0 ? (n + ' logical threads (model/clock hidden)')"
			+ " : 'model/clock hidden by browser';"
			+ "} catch(e) { return 'model/clock hidden by browser'; }")
	private static native String browserCpuInfo();

	// ---- serverWorker (Phase 3.4 seam b): hand the resolved EPK URL(s) to the worker realm ----
	// JS statics are per-realm: a freshly spawned server Web Worker has an empty
	// PlatformAssets.assets and a null configEPKFiles, so ServerPacksSource.createVanillaPackSource
	// would fall through to the desktop classpath path and fail. The client already resolved the
	// assets.epk URL(s) at boot (eaglercraftXOpts.assetsURI), so it packs them into the role:server
	// meta and the worker re-fetches assets.epk itself (same-origin, already HTTP-cached from the
	// client boot — near-zero extra cost) and populates its own PlatformAssets map.
	private static final char EPK_MARK = '\u0001';   // "role:server" <MARK> packed
	private static final char EPK_ENTRY_SEP = '\u0002'; // between EPK entries
	private static final char EPK_FIELD_SEP = '\u0003'; // between url and path

	/** [CLIENT] the role:server meta with the resolved EPK URL(s) appended for the worker realm. */
	public static String buildServerWorkerRoleMeta() {
		// StringBuilder on purpose: a `+` concat here constant-folds EPK_MARK () into the
		// makeConcatWithConstants recipe, and TeaVM 0.13's StringConcatFactorySubstitutor throws
		// IndexOutOfBoundsException on recipes with embedded / constants (link-breaker).
		return new StringBuilder("role:server").append(EPK_MARK).append(packConfigEPKForWorker()).toString();
	}

	/** [CLIENT] pack {@link #configEPKFiles} as url&lt;F&gt;path&lt;E&gt;url&lt;F&gt;path... */
	public static String packConfigEPKForWorker() {
		EPKFileEntry[] files = configEPKFiles;
		if(files == null || files.length == 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for(int i = 0; i < files.length; ++i) {
			// c97 perf (server-start): the integrated-server worker only needs the DATAPACK
			// (data/minecraft/... worldgen/tags/recipes/loot/advancements) + the built-in pack
			// metadata, all of which live in assets.epk (~5.5 MB). sounds.epk (~26 MB) and music.epk
			// (~41 MB) are PURE ogg audio the server never touches — sound events are code-registered
			// (BuiltInRegistries.SOUND_EVENT), sounds.json is a client-only resource. Skipping them
			// saves the worker realm re-fetching + indexing ~66 MB (measured ~3.7 s of the boot path,
			// c96 trace) and ~66 MB of worker RAM (relieves the 3-worker-parse peak). The client realm
			// still loads all three for playback; this only trims what crosses to the server worker.
			String url = files[i].url;
			if(isAudioOnlyEPK(url)) {
				continue;
			}
			if(!first) {
				sb.append(EPK_ENTRY_SEP);
			}
			first = false;
			// Resolve to ABSOLUTE here in the client (page) realm. The deployed assetsURI is a
			// page-relative URL (e.g. "assets.epk?v=cNN"); the worker is spawned from the classes.js
			// Blob, so its self.location is blob:origin/<uuid> and a relative fetch()/XHR inside the
			// worker would misresolve (and EPKDownloadHelper's win.getLocation() fallback NPEs there
			// because PlatformRuntime.win is unset in the worker realm). An absolute URL fetches the
			// same origin-rooted asset the client already cached.
			sb.append(url == null ? "" : resolveAbsoluteURLForWorker(url)).append(EPK_FIELD_SEP)
					.append(files[i].path == null ? "" : files[i].path);
		}
		return sb.toString();
	}

	/** [CLIENT] audio-only EPK (sounds/music) — never needed by the integrated server worker.
	 *  Matched on the URL's file segment so query strings (?v=cNN) don't defeat it. Conservative:
	 *  only the two known audio pack names are skipped; anything else (incl. assets.epk) is kept. */
	private static boolean isAudioOnlyEPK(String url) {
		if(url == null) {
			return false;
		}
		String u = url.toLowerCase(java.util.Locale.ROOT);
		return u.contains("sounds.epk") || u.contains("music.epk");
	}

	// [CLIENT realm] resolve a (possibly page-relative) URL to absolute against the page's base
	// URL. data:/blob:/already-absolute URLs pass through unchanged. Falls back to the raw url if
	// URL construction is unavailable.
	@JSBody(params = { "url" }, script =
			"try {"
			+ " var base = (typeof document !== \"undefined\" && document.baseURI) ? document.baseURI"
			+ "   : ((typeof location !== \"undefined\" && location.href) ? location.href : null);"
			+ " return base ? new URL(url, base).href : url;"
			+ "} catch(e) { return url; }")
	private static native String resolveAbsoluteURLForWorker(String url);

	/** [WORKER] strip the "role:server" prefix + {@link #EPK_MARK} and return the packed EPK payload. */
	public static String extractWorkerEPKPayload(String roleMeta) {
		if(roleMeta == null) {
			return "";
		}
		int i = roleMeta.indexOf(EPK_MARK);
		return i < 0 ? "" : roleMeta.substring(i + 1);
	}

	/**
	 * [WORKER] parse the packed EPK string and fetch the EPK(s) into
	 * {@link net.lax1dude.eaglercraft.v1_8.internal.PlatformAssets#assets}. No version check
	 * (the client already validated the same file at boot). Throws if nothing was passed.
	 */
	public static void downloadWorkerEPK(String packed) {
		if(packed == null || packed.isEmpty()) {
			throw new IllegalStateException(
					"serverWorker: no EPK URL(s) were passed to the worker realm (empty role:server meta)");
		}
		String[] entries = packed.split("\u0002");
		EPKFileEntry[] files = new EPKFileEntry[entries.length];
		for(int i = 0; i < entries.length; ++i) {
			int sep = entries[i].indexOf(EPK_FIELD_SEP);
			String url = sep < 0 ? entries[i] : entries[i].substring(0, sep);
			String path = sep < 0 ? "" : entries[i].substring(sep + 1);
			files[i] = new EPKFileEntry(url, path);
		}
		// PlatformAssets.assets is package-private to net.lax1dude...internal; route the actual
		// map population through PlatformRuntime (same package as PlatformAssets).
		net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime.populateWorkerAssets(files);
	}

	public static String configRootElementId = null;
	public static HTMLElement configRootElement =  null;
	public static EPKFileEntry[] configEPKFiles = null;
	public static String configLocalesFolder = null;
	public static boolean crashOnUncaughtExceptions = false;

	// Ported from 1.8 workspace EaglerProfile: the default-username name pool and
	// generator (two names + 4 digits, retried until <= 16 chars). The chosen name
	// persists in Eagler local storage ("<ns>.username") so it is stable across
	// reloads; the Profile screen will write the same key once it is ported.
	private static final String[] defaultNames = new String[] {
			"Yeeish", "Yeeish", "Yee", "Yee", "Yeer", "Yeeler", "Eagler", "Eagl",
			"Darver", "Darvler", "Vool", "Vigg", "Vigg", "Deev", "Yigg", "Yeeg"
	};

	public static String getEaglerUsername() {
		try {
			byte[] b = PlatformApplication.getLocalStorage("username");
			if(b != null) {
				String s = (new String(b, StandardCharsets.UTF_8)).trim();
				if(!s.isEmpty()) {
					return s.replaceAll("[^A-Za-z0-9]", "_");
				}
			}
		}catch(Throwable t) {
		}
		EaglercraftRandom rand = new EaglercraftRandom();
		String username;
		do {
			username = HString.format("%s%s%04d", defaultNames[rand.nextInt(defaultNames.length)],
					defaultNames[rand.nextInt(defaultNames.length)], rand.nextInt(10000));
		}while(username.length() > 16);
		try {
			PlatformApplication.setLocalStorage("username", username.getBytes(StandardCharsets.UTF_8));
		}catch(Throwable t) {
		}
		return username;
	}

	@JSFunctor
	private static interface WindowErrorHandler extends JSObject {
		void call(String message, String file, int line, int col, JSError error);
	}

	@JSBody(params = { "win", "handler" }, script = "var evtHandler = function(e) { handler("
			+ "(typeof e.message === \"string\") ? e.message : null,"
			+ "(typeof e.filename === \"string\") ? e.filename : null,"
			+ "(typeof e.lineno === \"number\") ? e.lineno : 0,"
			+ "(typeof e.colno === \"number\") ? e.colno : 0,"
			+ "(typeof e.error === \"undefined\") ? null : e.error);}; win.addEventListener(\"error\", evtHandler);"
			+ "return evtHandler;")
	private static native JSObject setWindowErrorHandler(Window win, WindowErrorHandler handler);

	@JSBody(params = { "win", "handler" }, script = "win.removeEventListener(\"error\", handler);")
	private static native void removeWindowErrorHandler(Window win, JSObject handler);

	public static void removeErrorHandler(Window win) {
		if(windowErrorHandler != null) {
			removeWindowErrorHandler(win, windowErrorHandler);
			windowErrorHandler = null;
		}
	}

	private static HTMLElement createToolButtons(HTMLDocument doc) {
		return createToolButtons(doc, null);
	}

	private static HTMLElement createToolButtons(HTMLDocument doc, final String reportText) {
		HTMLButtonElement buttonResetSettings = (HTMLButtonElement) doc.createElement("button");
		buttonResetSettings.setAttribute("style", "margin-left:10px;");
		buttonResetSettings.setInnerText("Reset Settings");
		buttonResetSettings.addEventListener("click", (evt) -> {
			boolean y = false;
			if (Window.confirm("Do you want to reset client settings?")) {
				PlatformApplication.setLocalStorage("g", null);
				PlatformApplication.setLocalStorage("p", null);
				y = true;
			}
			if (Window.confirm("Do you want to reset servers and relays?")) {
				PlatformApplication.setLocalStorage("r", null);
				PlatformApplication.setLocalStorage("s", null);
				y = true;
			}
			if (y) {
				Window.alert("Settings reset.");
			}
		});
		HTMLButtonElement buttonOpenConsole = (HTMLButtonElement) doc.createElement("button");
		buttonOpenConsole.setAttribute("style", "margin-left:10px;");
		buttonOpenConsole.setInnerText("Open Debug Console");
		buttonOpenConsole.addEventListener("click", (evt) -> {
			EagRuntime.showDebugConsole();
		});
		HTMLElement div1 = doc.createElement("div");
		div1.setAttribute("style", "position:absolute;bottom:5px;right:0px;");
		if (reportText != null) {
			HTMLButtonElement buttonCopy = (HTMLButtonElement) doc.createElement("button");
			buttonCopy.setAttribute("style", "margin-left:10px;");
			buttonCopy.setInnerText("Copy Report");
			buttonCopy.addEventListener("click", (evt) -> {
				boolean ok = copyToClipboard(reportText);
				Window.alert(ok ? "Crash report copied to clipboard."
						: "Copy failed — use \"Save Report\" instead.");
			});
			HTMLButtonElement buttonSave = (HTMLButtonElement) doc.createElement("button");
			buttonSave.setAttribute("style", "margin-left:10px;");
			buttonSave.setInnerText("Save Report");
			buttonSave.addEventListener("click", (evt) -> {
				downloadTextFile(reportText, "eaglercraft-crash-report.txt");
			});
			div1.appendChild(buttonCopy);
			div1.appendChild(buttonSave);
		}
		div1.appendChild(buttonResetSettings);
		div1.appendChild(buttonOpenConsole);
		HTMLElement div2 = doc.createElement("div");
		div2.setAttribute("style", "position:relative;");
		div2.appendChild(div1);
		HTMLElement div3 = doc.createElement("div");
		div3.getClassList().add("_eaglercraftX_crash_tools_element");
		div3.setAttribute("style", "z-index:101;position:absolute;top:135px;left:10%;right:10%;height:0px;");
		div3.appendChild(div2);
		return div3;
	}

	public static void showCrashScreen(String message, Throwable t) {
		try {
			showCrashScreen(message + "\n\n" + EagRuntime.getStackTrace(t));
		}catch(Throwable tt) {
		}
	}

	private static boolean isCrashed = false;

	public static void showCrashScreen(String t) {
		StringBuilder strBeforeBuilder = new StringBuilder();
		strBeforeBuilder.append("Game Crashed! I have fallen and I can't get up!\n\n");
		strBeforeBuilder.append(t);
		strBeforeBuilder.append('\n').append('\n');
		String strBefore = strBeforeBuilder.toString();

		HTMLDocument doc = Window.current().getDocument();
		HTMLElement el;
		if(PlatformRuntime.parent != null) {
			el = PlatformRuntime.parent;
		}else {
			if(configRootElement == null) {
				configRootElement = doc.getElementById(configRootElementId);
			}
			el = configRootElement;
		}

		StringBuilder str = new StringBuilder();
		str.append("eaglercraft.version = \"").append(EaglercraftVersion.projectForkVersion).append("\"\n");
		str.append("eaglercraft.minecraft = \"26.2\"\n");
		str.append("eaglercraft.brand = \"" + EaglercraftVersion.projectForkVendor + "\"\n");
		String crashUser;
		try {
			crashUser = getEaglerUsername();
		}catch(Throwable tt) {
			crashUser = "unknown";
		}
		str.append("eaglercraft.username = \"").append(crashUser).append("\"\n");
		str.append('\n');
			str.append(addWebGLToCrash());
			str.append('\n');
			str.append("runtime = ").append(getRuntimeCrashDiagnostics()).append('\n');
			str.append('\n');
			str.append(addShimsToCrash());
		str.append('\n');
		str.append("window.eaglercraftXOpts = ");
		str.append(TeaVMClientConfigAdapter.instance.toString()).append('\n');
		str.append('\n');
		str.append("currentTime = ");
		str.append((new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z")).format(new Date())).append('\n');
		str.append('\n');
		addDebugNav(str, "userAgent");
		addDebugNav(str, "vendor");
		addDebugNav(str, "language");
		addDebugNav(str, "hardwareConcurrency");
		addDebugNav(str, "deviceMemory");
		addDebugNav(str, "platform");
		addDebugNav(str, "product");
		addDebugNavPlugins(str);
		str.append('\n');
		addDebug(str, "localStorage");
		addDebug(str, "sessionStorage");
		addDebug(str, "indexedDB");
		str.append('\n');
		str.append("rootElement.clientWidth = ").append(el == null ? "undefined" : el.getClientWidth()).append('\n');
		str.append("rootElement.clientHeight = ").append(el == null ? "undefined" : el.getClientHeight()).append('\n');
		addDebug(str, "innerWidth");
		addDebug(str, "innerHeight");
		addDebug(str, "outerWidth");
		addDebug(str, "outerHeight");
		addDebug(str, "devicePixelRatio");
		addDebugScreen(str, "availWidth");
		addDebugScreen(str, "availHeight");
		addDebugScreen(str, "colorDepth");
		addDebugScreen(str, "pixelDepth");
		str.append('\n');
		addDebug(str, "minecraftServer");
		str.append('\n');
		addDebugLocation(str, "href");
		str.append('\n');
		String strAfter = str.toString();

		String strFinal = strBefore + strAfter;
		List<String> additionalInfo = new LinkedList<>();
		try {
			TeaVMClientConfigAdapter.instance.getHooks().callCrashReportHook(strFinal, additionalInfo::add);
		}catch(Throwable tt) {
			systemErr.println("Uncaught exception invoking crash report hook!");
			EagRuntime.debugPrintStackTraceToSTDERR(tt);
		}

			persistCrashReportJS(strFinal);

				if(!isCrashed) {
				isCrashed = true;
				try {
					net.minecraft.client.Minecraft.eaglerEmergencyShutdownForNativeCrash();
				}catch(Throwable tt) {
				}
				try {
					net.lax1dude.eaglercraft.v1_8.sp.internal.ClientPlatformSingleplayer.killWorker();
				}catch(Throwable tt) {
				}

				if(additionalInfo.size() > 0) {
				try {
					StringBuilder builderFinal = new StringBuilder();
					builderFinal.append(strBefore);
					builderFinal.append("Got the following messages from the crash report hook registered in eaglercraftXOpts:\n\n");
					for(String str2 : additionalInfo) {
						builderFinal.append("----------[ CRASH HOOK ]----------\n");
						builderFinal.append(str2).append('\n');
						builderFinal.append("----------------------------------\n\n");
					}
					builderFinal.append(strAfter);
					strFinal = builderFinal.toString();
				}catch(Throwable tt) {
					systemErr.println("Uncaught exception concatenating crash report hook messages!");
					EagRuntime.debugPrintStackTraceToSTDERR(tt);
				}
			}

			if(el == null) {
				Window.alert("Root element not found, crash report was printed to console");
				systemErr.println(strFinal);
				return;
			}

			// Tear the crashed game instance DOWN before drawing the crash screen over
			// it: lose the WebGL2 context and remove the canvas so the tab stops holding
			// ~1GB of GL/heap behind the report (Matej: "999MB", "doesn't clear the
			// underlying process"), then drop an OPAQUE full-viewport backdrop so the dead
			// black canvas never shows through the report (Matej: "white bg not a
			// transparent one"). Backdrop is z-index:99 — below the report (100) + tools (101).
			try {
				disposeGameCanvasJS();
			}catch(Throwable tt) {
			}
			HTMLElement backdrop = doc.createElement("div");
			backdrop.setAttribute("style", "z-index:99;position:fixed;top:0px;left:0px;right:0px;bottom:0px;background-color:white;");
			backdrop.getClassList().add("_eaglercraftX_crash_element");
			el.appendChild(backdrop);

			HTMLElement img = doc.createElement("img");
			HTMLElement div = doc.createElement("div");
			img.setAttribute("style", "z-index:100;position:absolute;top:10px;left:calc(50% - 151px);");
			img.setAttribute("src", crashImageWrapper());
			div.setAttribute("style", "z-index:100;position:absolute;top:135px;left:10%;right:10%;bottom:50px;background-color:white;border:1px solid #cccccc;overflow-x:hidden;overflow-y:scroll;overflow-wrap:break-word;white-space:pre-wrap;user-select:text;-webkit-user-select:text;cursor:text;font: 14px monospace;padding:10px;");
			div.getClassList().add("_eaglercraftX_crash_element");
			el.appendChild(img);
			el.appendChild(div);
			el.appendChild(createToolButtons(doc, strFinal));
			div.appendChild(doc.createTextNode(strFinal));

			PlatformRuntime.removeEventHandlers();

		}else {
			systemErr.println();
			systemErr.println("An additional crash report was supressed:");
			String[] s = t.split("[\\r\\n]+");
			for(int i = 0; i < s.length; ++i) {
				systemErr.println("  " + s[i]);
			}
			if(additionalInfo.size() > 0) {
				for(String str2 : additionalInfo) {
					if(str2 != null) {
						systemErr.println();
						systemErr.println("  ----------[ CRASH HOOK ]----------");
						s = str2.split("[\\r\\n]+");
						for(int i = 0; i < s.length; ++i) {
							systemErr.println("  " + s[i]);
						}
						systemErr.println("  ----------------------------------");
					}
				}
			}
		}
	}

	// The GpuDevice graphics info (backend/vendor/renderer/features) is emitted by
	// Minecraft's own system report ("Graphics Backend", etc.), so this line just marks
	// which web backend is active rather than duplicating it.
	private static String addWebGLToCrash() {
		return "webgl = WebGL2 GpuDeviceBackend active (Phase 3.3b)\n";
	}

	// TODO(3.6): ES6 shim status dump (upstream addShimsToCrash); shims DROPPED
	private static String addShimsToCrash() {
		return "eaglercraft.es6shims = n/a\n";
	}

	@JSBody(params = {}, script = "try { return JSON.stringify({"
			+ "memory:(typeof performance !== 'undefined' && performance.memory) ? {"
			+ "usedJSHeapSize:performance.memory.usedJSHeapSize||0,totalJSHeapSize:performance.memory.totalJSHeapSize||0,"
			+ "jsHeapSizeLimit:performance.memory.jsHeapSizeLimit||0}:null,"
			+ "meshWorkers:globalThis.__meshWorkerPool||null,shaderPack:globalThis.__eaglerShaderPack||null,"
			+ "journal:(globalThis.__eaglerCrashJournal&&globalThis.__eaglerCrashJournal.snapshot)"
			+ "?globalThis.__eaglerCrashJournal.snapshot():null}, null, 2);"
			+ "} catch(e) { return '<runtime diagnostics unavailable: '+e+'>'; }")
	private static native String getRuntimeCrashDiagnostics();

	@JSBody(params = { "report" }, script =
			"try { if(typeof globalThis.__eaglerPersistCrashReport === 'function')"
			+ " globalThis.__eaglerPersistCrashReport(report); } catch(e) {}")
	private static native void persistCrashReportJS(String report);

	public static void showIncompatibleScreen(String t) {
		if(!isCrashed) {
			isCrashed = true;

			HTMLDocument doc = Window.current().getDocument();
			HTMLElement el;
			if(PlatformRuntime.parent != null) {
				el = PlatformRuntime.parent;
			}else {
				if(configRootElement == null) {
					configRootElement = doc.getElementById(configRootElementId);
				}
				el = configRootElement;
			}

			if(el == null) {
				Window.alert("Compatibility error: " + t);
				System.err.println("Compatibility error: " + t);
				return;
			}

			String s = el.getAttribute("style");
			el.setAttribute("style", (s == null ? "" : s) + "position:relative;");
			HTMLElement img = doc.createElement("img");
			HTMLElement div = doc.createElement("div");
			img.setAttribute("style", "z-index:100;position:absolute;top:10px;left:calc(50% - 151px);");
			img.setAttribute("src", crashImageWrapper());
			div.setAttribute("style", "z-index:100;position:absolute;top:135px;left:10%;right:10%;bottom:50px;background-color:white;border:1px solid #cccccc;overflow-x:hidden;overflow-y:scroll;font:18px sans-serif;padding:40px;");
			div.getClassList().add("_eaglercraftX_incompatible_element");
			el.appendChild(img);
			el.appendChild(div);
			el.appendChild(createToolButtons(doc));
			div.setInnerHTML("<h2>This device is incompatible with Eaglercraft&ensp;:(</h2>"
					+ "<div style=\"margin-left:40px;\">"
					+ "<p style=\"font-size:1.2em;\"><b style=\"font-size:1.1em;\">Issue:</b> <span style=\"color:#BB0000;\" id=\"_eaglercraftX_crashReason\"></span><br /></p>"
					+ "<p style=\"margin-left:10px;font:0.9em monospace;\" id=\"_eaglercraftX_crashUserAgent\"></p>"
					+ "<p style=\"margin-left:10px;font:0.9em monospace;\">Current Date: " + (new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z")).format(new Date()) + "</p>"
					+ "<p><br /><span style=\"font-size:1.1em;border-bottom:1px dashed #AAAAAA;padding-bottom:5px;\">Things you can try:</span></p>"
					+ "<ol>"
					+ "<li><span style=\"font-weight:bold;\">Just try using Eaglercraft on a different device</span>, it isn't a bug it's common sense</li>"
					+ "<li style=\"margin-top:7px;\">If this screen just appeared randomly, try restarting your browser or device</li>"
					+ "<li style=\"margin-top:7px;\">If you are not using Chrome/Edge, try installing the latest Google Chrome</li>"
					+ "<li style=\"margin-top:7px;\">If your browser is out of date, please update it to the latest version</li>"
					+ "</ol>"
					+ "</div>");

			div.querySelector("#_eaglercraftX_crashReason").appendChild(doc.createTextNode(t));
			div.querySelector("#_eaglercraftX_crashUserAgent").appendChild(doc.createTextNode(getStringNav("userAgent")));

			PlatformRuntime.removeEventHandlers();
		}
	}

	public static void showContextLostScreen(String t) {
		if(!isCrashed) {
			isCrashed = true;

			HTMLDocument doc = Window.current().getDocument();
			HTMLElement el;
			if(PlatformRuntime.parent != null) {
				el = PlatformRuntime.parent;
			}else {
				if(configRootElement == null) {
					configRootElement = doc.getElementById(configRootElementId);
				}
				el = configRootElement;
			}

			if(el == null) {
				Window.alert("WebGL context lost!");
				System.err.println("WebGL context lost: " + t);
				return;
			}

			String s = el.getAttribute("style");
			el.setAttribute("style", (s == null ? "" : s) + "position:relative;");
			HTMLElement img = doc.createElement("img");
			HTMLElement div = doc.createElement("div");
			img.setAttribute("style", "z-index:100;position:absolute;top:10px;left:calc(50% - 151px);");
			img.setAttribute("src", crashImageWrapper());
			div.setAttribute("style", "z-index:100;position:absolute;top:135px;left:10%;right:10%;bottom:50px;background-color:white;border:1px solid #cccccc;overflow-x:hidden;overflow-y:scroll;font:18px sans-serif;padding:40px;");
			div.getClassList().add("_eaglercraftX_context_lost_element");
			el.appendChild(img);
			el.appendChild(div);
			el.appendChild(createToolButtons(doc));
			div.setInnerHTML("<h2>WebGL context lost!</h2>"
					+ "<div style=\"margin-left:40px;\">"
					+ "<p style=\"font-size:1.2em;\">Your browser has forcibly released all of the resources "
					+ "allocated by the game's 3D rendering context. EaglercraftX cannot continue, please refresh "
					+ "the page to restart the game, sorry for the inconvenience.</p>"
					+ "<p style=\"font-size:1.2em;\">This is not a bug, it is usually caused by the browser "
					+ "deciding it no longer has sufficient resources to continue rendering this page. If it "
					+ "happens again, try closing your other browser tabs and windows.</p>"
					+ "<p style=\"overflow-wrap:break-word;white-space:pre-wrap;font:0.75em monospace;margin-top:1.5em;\" id=\"_eaglercraftX_contextLostTrace\"></p>"
					+ "</div>");

			div.querySelector("#_eaglercraftX_contextLostTrace").appendChild(doc.createTextNode(t));
		}
	}

	@JSBody(params = { "v" }, script = "try { return \"\"+window[v]; } catch(e) { return \"<error>\"; }")
	private static native String getString(String var);

	@JSBody(params = { "v" }, script = "try { return \"\"+window.navigator[v]; } catch(e) { return \"<error>\"; }")
	private static native String getStringNav(String var);

	@JSBody(params = { "v" }, script = "try { return \"\"+window.screen[v]; } catch(e) { return \"<error>\"; }")
	private static native String getStringScreen(String var);

	@JSBody(params = { "v" }, script = "try { return \"\"+window.location[v]; } catch(e) { return \"<error>\"; }")
	private static native String getStringLocation(String var);

	/** Copy the crash report to the clipboard (async API, with a legacy execCommand
	 *  fallback for file:// / http where the async clipboard may be blocked). */
	@JSBody(params = { "text" }, script = "try { if (navigator.clipboard && navigator.clipboard.writeText) {"
			+ " navigator.clipboard.writeText(text); return true; } } catch(e) {}"
			+ " try { var ta = document.createElement('textarea'); ta.value = text;"
			+ " ta.style.position = 'fixed'; ta.style.left = '-9999px'; document.body.appendChild(ta);"
			+ " ta.focus(); ta.select(); var ok = document.execCommand('copy'); document.body.removeChild(ta);"
			+ " return ok; } catch(e2) { return false; }")
	private static native boolean copyToClipboard(String text);

	/** Trigger a browser download of the crash report as a .txt file (Blob + <a download>). */
	@JSBody(params = { "text", "filename" }, script = "try { var b = new Blob([text], {type:'text/plain'});"
			+ " var u = URL.createObjectURL(b); var a = document.createElement('a'); a.href = u;"
			+ " a.download = filename; document.body.appendChild(a); a.click(); document.body.removeChild(a);"
			+ " setTimeout(function(){ try { URL.revokeObjectURL(u); } catch(e){} }, 2000); return true; }"
			+ " catch(e) { return false; }")
	private static native boolean downloadTextFile(String text, String filename);

	/** Crash cleanup: force-free the crashed instance's GPU memory + canvas. getContext
	 *  on an already-initialized canvas returns the SAME live context, so
	 *  WEBGL_lose_context.loseContext() releases its textures/buffers/FBOs; then the
	 *  canvas is detached so the compositor drops the backbuffer too. Best-effort. */
	@JSBody(params = { }, script = "try {"
			+ " var cvs = document.getElementsByClassName('_eaglercraftX_canvas_element');"
			+ " for(var i = cvs.length - 1; i >= 0; --i) { var c = cvs[i];"
			+ "  try { var gl = c.getContext('webgl2') || c.getContext('webgl');"
			+ "   if(gl) { var ext = gl.getExtension('WEBGL_lose_context'); if(ext) ext.loseContext(); } } catch(e) {}"
			+ "  try { if(c.parentNode) c.parentNode.removeChild(c); } catch(e) {} }"
			+ "} catch(e) {}")
	private static native void disposeGameCanvasJS();

	/** Drive the index.html eagler boot splash's progress bar + status line so the
	 *  long black parse/EPK/model-bake window shows genuine "unpacking assets" progress
	 *  instead of a black screen (Matej). No-op if the splash already handed off. */
	@JSBody(params = { "pct", "text" }, script =
			"try { if(typeof window.__eaglerBoot === 'function') window.__eaglerBoot(pct, text); } catch(e) {}")
	private static native void emitBootStageJS(int pct, String text);

	@JSBody(params = { }, script = "try { var retObj = new Array; if(typeof navigator.plugins === \"object\")"
			+ "{ var len = navigator.plugins.length; if(len > 0) { for(var idx = 0; idx < len; ++idx) {"
			+ "var thePlugin = navigator.plugins[idx]; retObj.push({ name: thePlugin.name,"
			+ "filename: thePlugin.filename, desc: thePlugin.description }); } } } return JSON.stringify(retObj);"
			+ "} catch(e) { return \"<error>\"; }")
	private static native String getStringNavPlugins();

	private static void addDebug(StringBuilder str, String var) {
		str.append("window.").append(var).append(" = ").append(getString(var)).append('\n');
	}

	private static void addDebugNav(StringBuilder str, String var) {
		str.append("window.navigator.").append(var).append(" = ").append(getStringNav(var)).append('\n');
	}

	private static void addDebugNavPlugins(StringBuilder str) {
		str.append("window.navigator.plugins = ").append(getStringNavPlugins()).append('\n');
	}

	private static void addDebugScreen(StringBuilder str, String var) {
		str.append("window.screen.").append(var).append(" = ").append(getStringScreen(var)).append('\n');
	}

	private static void addDebugLocation(StringBuilder str, String var) {
		str.append("window.location.").append(var).append(" = ").append(getStringLocation(var)).append('\n');
	}

}
