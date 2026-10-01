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

// 26.2 adaptations (Phase 3.1, see report):
//  - ES6 shim status dump removed (ES6 shims are on the DROP list; also drops the
//    guava Collections2 dependency)
//  - TeaVMUtils wrap/unwrap -> 0.13 Int8Array copyToJavaArray/fromJavaArray

package net.lax1dude.eaglercraft.v1_8.sp.server.internal;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.browser.Window;
import org.teavm.jso.core.JSString;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.events.MessageEvent;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.Filesystem;
import net.lax1dude.eaglercraft.v1_8.internal.IClientConfigAdapter;
import net.lax1dude.eaglercraft.v1_8.internal.IEaglerFilesystem;
import net.lax1dude.eaglercraft.v1_8.internal.IPCPacketData;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.MessageChannel;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMBlobURLManager;
import net.lax1dude.eaglercraft.v1_8.internal.teavm.TeaVMClientConfigAdapter;
import net.lax1dude.eaglercraft.v1_8.internal.vfs2.VFile2;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.server.IWASMCrashCallback;

public class ServerPlatformSingleplayer {

	private static final Logger logger = LogManager.getLogger("ServerPlatformSingleplayer");

	private static final LinkedList<IPCPacketData> messageQueue = new LinkedList<>();

	private static boolean immediateContinueSupport = false;
	private static MessageChannel immediateContinueChannel = null;
	private static Runnable currentContinueHack = null;
	private static final LinkedList<Runnable> immediateContinueQueue = new LinkedList<>();
	private static final Object immediateContLock = new Object();
	private static final JSString emptyJSString = JSString.valueOf("");
	private static boolean singleThreadMode = false;
	private static Consumer<IPCPacketData> singleThreadCB = null;

	private static IEaglerFilesystem filesystem = null;

	@JSFunctor
	private static interface WorkerBinaryPacketHandler extends JSObject {
		public void onMessage(String channel, ArrayBuffer buf);
	}

	/**
	 * Browser Scheduling API callbacks must cross the TeaVM boundary as a real JS
	 * function. Keeping the functor type explicit also prevents the handleEvent bridge
	 * mismatch fixed in the WebRTC path.
	 */
	@JSFunctor
	private static interface TickContinueHandler extends JSObject {
		void call();
	}

	private static class WorkerBinaryPacketHandlerImpl implements WorkerBinaryPacketHandler {

		public void onMessage(String channel, ArrayBuffer buf) {
			if(channel == null) {
				logger.error("Recieved IPC packet with null channel");
				return;
			}

			if(buf == null) {
				logger.error("Recieved IPC packet with null buffer");
				return;
			}

			synchronized(messageQueue) {
				messageQueue.add(new IPCPacketData(channel, new Int8Array(buf).copyToJavaArray()));
			}
		}

	}

	// self.__eaglerXOnMessage (NOT a bare global): TeaVM output runs in strict mode, where
	// assigning to an undeclared identifier throws ReferenceError. self-qualify so it becomes an
	// explicit property of the worker global. The reader (ServerWorkerHost.installServerOnMessage)
	// is self-qualified to match.
	@JSBody(params = { "wb" }, script = "self.__eaglerXOnMessage = function(o) { wb(o.data.ch, o.data.dat); };")
	private static native void registerPacketHandler(WorkerBinaryPacketHandler wb);

	public static void register() {
		registerPacketHandler(new WorkerBinaryPacketHandlerImpl());
	}

	public static void initializeContext() {
		singleThreadMode = false;
		singleThreadCB = null;

		// 26.2: upstream dumps ES6ShimStatus here; the ES6 shim subsystem is on the
		// DROP list for this increment (modern browsers only)

		TeaVMBlobURLManager.initialize();

		checkImmediateContinueSupport();

		filesystem = net.lax1dude.eaglercraft.v1_8.internal.teavm.WorkerFilesystemBridge.getWorkerFilesystem();
		if(filesystem == null) {
			filesystem = Filesystem.getHandleFor(getClientConfigAdapter().getWorldsDB());
		}
		VFile2.setPrimaryFilesystem(filesystem);
	}

	public static IEaglerFilesystem getWorldsDatabase() {
		return filesystem;
	}

	public static void initializeContextSingleThread(Consumer<IPCPacketData> packetSendCallback) {
		singleThreadMode = true;
		singleThreadCB = packetSendCallback;
		filesystem = Filesystem.getHandleFor(getClientConfigAdapter().getWorldsDB());
	}

	// self.postMessage (not bare) to match the proven mesh-worker path (MeshWorkerMain) — bare
	// postMessage resolves in a dedicated worker but self-qualifying is the consistent, unambiguous
	// form (c91). Only reached in serverWorker mode (single-thread uses the callback above).
	@JSBody(params = { "ch", "dat" }, script = "self.postMessage({ ch: ch, dat : dat });")
	public static native void sendPacketTeaVM(String channel, ArrayBuffer arr);

	public static void sendPacket(IPCPacketData packet) {
		if(singleThreadMode) {
			singleThreadCB.accept(packet);
		}else {
			sendPacketTeaVM(packet.channel, Int8Array.fromJavaArray(packet.contents).getBuffer());
		}
	}

	// worker -> page vanilla-packet batch, transferring the backing buffer (zero-copy).
	// H2: the ArrayBuffer is neutered after this call, but it is a fresh copy of the Java
	// array (fromJavaArray), so the caller's byte[] is unaffected.
	@JSBody(params = { "ch", "dat" }, script = "self.postMessage({ ch: ch, dat : dat }, [dat]);")
	private static native void sendDataBatchTeaVM(String channel, ArrayBuffer arr);

	/** serverWorker data plane (Phase 3.4 seam c): worker -> page. In single-thread mode
	 *  there is no worker boundary, so route the batch through the normal in-heap queue. */
	public static void sendDataBatch(String channel, byte[] batch) {
		if(batch == null) {
			return;
		}
		if(singleThreadMode) {
			singleThreadCB.accept(new IPCPacketData(channel, batch));
		}else {
			sendDataBatchTeaVM(channel, Int8Array.fromJavaArray(batch).getBuffer());
		}
	}

	public static List<IPCPacketData> recieveAllPacket() {
		synchronized(messageQueue) {
			if(messageQueue.size() == 0) {
				return null;
			}else {
				List<IPCPacketData> ret = new ArrayList<>(messageQueue);
				messageQueue.clear();
				return ret;
			}
		}
	}

	public static IClientConfigAdapter getClientConfigAdapter() {
		return TeaVMClientConfigAdapter.instance;
	}

	private static void checkImmediateContinueSupport() {
		try {
			immediateContinueSupport = false;
			immediateContinueQueue.clear();
			if(!MessageChannel.supported()) {
				logger.error("Fast immediate continue will be disabled for server context due to MessageChannel being unsupported");
				return;
			}
			immediateContinueChannel = MessageChannel.create();
			immediateContinueChannel.getPort1().addEventListener("message", new EventListener<MessageEvent>() {
				@Override
				public void handleEvent(MessageEvent evt) {
					Runnable toRun;
					synchronized(immediateContLock) {
						if(currentContinueHack != null) {
							toRun = currentContinueHack;
							currentContinueHack = null;
						}else {
							toRun = immediateContinueQueue.pollFirst();
						}
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
			currentContinueHack = () -> {
				checkMe[0] = true;
			};
			immediateContinueChannel.getPort2().postMessage(emptyJSString);
			if(checkMe[0]) {
				currentContinueHack = null;
				if(immediateContinueChannel != null) {
					safeShutdownChannel(immediateContinueChannel);
				}
				immediateContinueChannel = null;
				logger.error("Fast immediate continue will be disabled for server context due to actually continuing immediately");
				return;
			}
			EagUtils.sleep(10);
			currentContinueHack = null;
			if(!checkMe[0]) {
				if(immediateContinueChannel != null) {
					safeShutdownChannel(immediateContinueChannel);
				}
				immediateContinueChannel = null;
				logger.error("Fast immediate continue will be disabled for server context due to startup check failing");
			}else {
				immediateContinueSupport = true;
			}
		}catch(Throwable t) {
			logger.error("Fast immediate continue will be disabled for server context due to exceptions");
			immediateContinueSupport = false;
			if(immediateContinueChannel != null) {
				safeShutdownChannel(immediateContinueChannel);
			}
			immediateContinueChannel = null;
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

	public static void immediateContinue() {
		if(singleThreadMode) {
			PlatformRuntime.immediateContinue();
		}else {
			if(immediateContinueSupport) {
				immediateContinueTeaVM();
			}else {
				EagUtils.sleep(0);
			}
		}
	}

	/**
	 * Resume the integrated-server tick at user-blocking priority when the browser
	 * Scheduling API is available. A continuously replenished worldgen MessageChannel
	 * can otherwise leave an ordinary timer callback queued for seconds. Older browsers
	 * retain the exact setTimeout behavior.
	 */
	public static void tickContinue(long millis) {
		tickContinueTeaVM((int)Math.max(0L, Math.min(millis, Integer.MAX_VALUE)));
	}

	@Async
	private static native void tickContinueTeaVM(int millis);

	private static void tickContinueTeaVM(int millis, final AsyncCallback<Void> cb) {
		// scheduler.postTask/setTimeout retains this functor until it runs, keeping the
		// TeaVM callback strongly reachable for the whole delayed interval.
		TickContinueHandler handler = () -> cb.complete(null);
		scheduleTickContinue(handler, millis);
	}

	@JSBody(params = { "handler", "millis" }, script = "var d = Math.max(0, millis | 0);"
			+ "try { var s = globalThis.scheduler;"
			+ " if (s && typeof s.postTask === 'function') {"
			+ "  var p = s.postTask(handler, { priority: 'user-blocking', delay: d });"
			+ "  if (p && typeof p.catch === 'function') p.catch(function(){ globalThis.setTimeout(handler, d); });"
			+ "  return;"
			+ " }"
			+ "} catch (e) {}"
			+ "globalThis.setTimeout(handler, d);")
	private static native void scheduleTickContinue(TickContinueHandler handler, int millis);

	@JSBody(params = {}, script = "return globalThis.__eaglerPerfEnabled === true;")
	public static native boolean isPerfDebugEnabled();

	@JSBody(params = {}, script = "return globalThis.__eaglerChunkUnloadHardCap === true;")
	public static native boolean isChunkUnloadHardCapEnabled();

	@Async
	private static native void immediateContinueTeaVM();

	private static void immediateContinueTeaVM(final AsyncCallback<Void> cb) {
		synchronized(immediateContLock) {
			Runnable continuation = () -> {
				cb.complete(null);
			};
			immediateContinueQueue.addLast(continuation);
			try {
				immediateContinueChannel.getPort2().postMessage(emptyJSString);
			}catch(Throwable t) {
				immediateContinueQueue.remove(continuation);
				logger.error("Caught error posting immediate continue, using setTimeout instead");
				Window.setTimeout(() -> cb.complete(null), 0);
			}
		}
	}

	public static boolean isSingleThreadMode() {
		return singleThreadMode;
	}

	public static void recievePacketSingleThreadTeaVM(IPCPacketData pkt) {
		synchronized(messageQueue) {
			messageQueue.add(pkt);
		}
	}

	public static void setCrashCallbackWASM(IWASMCrashCallback callback) {

	}

	public static boolean isTabAboutToCloseWASM() {
		return false;
	}

}
