/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.sp.server.internal.teavm;

import org.teavm.jso.JSBody;

import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.server.internal.ServerPlatformSingleplayer;

/**
 * INTEGRATED-SERVER WEB WORKER host (worker side), reached when the page posts the
 * first control message {@code {meta:"role:server"}} to a worker spawned from the
 * shared {@code classes.js} Blob (eag26 teardown §1.5/§2.2 — "first meta message
 * decides role"). {@link net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerMain#onMeta}
 * dispatches {@code role:server} here; {@code role:mesh|mip:N} stays in the mesh
 * pipeline. Both roles run the same module.
 *
 * <p>Responsibilities on boot:
 * <ol>
 * <li>Initialize the worker's server platform context (VFS over IndexedDB — verified
 *     worker-safe: {@link net.lax1dude.eaglercraft.v1_8.internal.teavm.IndexedDBFilesystem}
 *     uses the {@code indexedDB} global, never {@code window}/{@code document}).</li>
 * <li>Install the {@code {ch,dat}} inbound router ({@link ServerPlatformSingleplayer#register()}
 *     sets the global {@code __eaglerXOnMessage}) and point {@code self.onmessage} at it,
 *     while still recognising later {@code {meta}} control strings.</li>
 * <li>Run the REAL {@code MinecraftServer} supervisor loop
 *     ({@code EaglerIntegratedServerWorker26.serverMain()}) — a blocking
 *     {@code while(true)} loop that owns this worker's thread. TeaVM green-thread
 *     {@code EagUtils.sleep} yields cooperatively, so {@code self.onmessage} still
 *     delivers control + data batches into the server's message queue between ticks
 *     (online-perf-research §1: "a worker is a real OS thread, so it CAN loop").</li>
 * </ol>
 *
 * <p><b>Channels</b> (both on the existing {@code {ch,dat}} envelope, see
 * {@link net.lax1dude.eaglercraft.v1_8.sp.server.ServerWorkerProtocol}): control on
 * {@code "~!IPC"} (structured-clone), vanilla-packet data on {@code "~!SVDATA"}
 * (transferable batches). The transferable data send is {@link #postDataBatch}.
 */
public class ServerWorkerHost {
	private static final char EPK_MARK = '\u0001';
	private static final char EPK_ENTRY_SEP = '\u0002';
	private static final char EPK_FIELD_SEP = '\u0003';

	private static final Logger logger = LogManager.getLogger("ServerWorkerHost");

	private static boolean booted = false;

	// The full role:server meta (carries the resolved EPK URL(s) after a MARK char), captured
	// from the first control message so runServer() can fetch the datapack assets in-realm.
	private static String roleMeta = null;

	/** Worker -> page: post one vanilla-packet batch on the data channel, transferring
	 *  the backing buffer (zero-copy). H2: {@code buf} is neutered after this call. */
	@JSBody(params = { "ch", "buf" }, script = "self.postMessage({ ch: ch, dat: buf }, [buf]);")
	private static native void postDataBatchTeaVM(String channel, org.teavm.jso.typedarrays.ArrayBuffer buf);

	public static void postDataBatch(String channel, org.teavm.jso.typedarrays.ArrayBuffer buf) {
		postDataBatchTeaVM(channel, buf);
	}

	// self.onmessage router: {meta:string} -> role/control string; else the vanilla
	// {ch,dat} envelope -> self.__eaglerXOnMessage installed by register(). Self-qualified
	// (self.__eaglerXOnMessage) to match the strict-mode-safe assignment in register().
	@JSBody(params = { "metaCb" }, script = "self.onmessage = function(e) {"
			+ " if (e.data && e.data.eaglerFsReply === true) return;"
			+ " if (e.data && typeof e.data.meta === \"string\") { metaCb(e.data.meta); }"
			+ " else if (typeof self.__eaglerXOnMessage === \"function\") { self.__eaglerXOnMessage(e); } };")
	private static native void installServerOnMessage(MetaCallback metaCb);

	@org.teavm.jso.JSFunctor
	private interface MetaCallback extends org.teavm.jso.JSObject {
		void onMeta(String meta);
	}

	/**
	 * Boot the integrated-server worker. Idempotent — a duplicate {@code role:server}
	 * (or a stray control message during boot) is ignored. Called from the mesh
	 * worker's meta dispatcher on the FIRST {@code {meta:"role:server"}}.
	 *
	 * <p>{@code runServer()} MUST run on a real TeaVM green thread, not a {@code setTimeout(0)}
	 * macrotask: {@code boot()} is reached from {@code onMeta} (a {@code self.onmessage} JS
	 * callback), and the worker's {@code main()} green thread already ended when
	 * {@link net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerMain#workerMain()} returned. A bare
	 * event-loop callback is NOT a threading context, so the suspending ({@code @Async}) calls
	 * inside {@code runServer} — the IndexedDB VFS open in {@code initializeContext()}, the
	 * {@code EagUtils.sleep} immediate-continue probe, the EPK fetch, and the {@code WorldLoader}
	 * {@code managedBlock} — would throw "Suspension point reached from non-threading context".
	 * {@code new Thread(...).start()} is the proven green-thread spawn in this port (the same one
	 * {@code MinecraftServer.spin} uses for the server thread), and it is non-suspending so it is
	 * safe to call from the {@code onMeta} callback; the {@code @Async} work then runs inside the
	 * new green thread's proper threading context, exactly like the client main thread's VFS open.
	 */
	public static void boot(String meta) {
		if (booted) {
			return;
		}
		booted = true;
		roleMeta = meta;
		new Thread(ServerWorkerHost::runServer, "IntegratedServerWorker").start();
	}

	private static void runServer() {
		try {
			// 0. HOSTED FLAG FIRST — before ANY net.minecraft / EaglerHosted class-init. The
			//    worker entry (_worker_process_ -> MeshWorkerMain) never sets this, but the
			//    integrated server needs EaglerHosted.ACTIVE==true for the EPK vanilla-pack
			//    seam, the VFS global-saved-data path, the EaglerServerState options reads, and
			//    the IPC crash path. EaglerHosted.ACTIVE reads Boolean.getBoolean("eagler.hosted")
			//    once in its clinit, so this must run before the first EaglerHosted reference.
			System.setProperty("eagler.hosted", "true");
			// c92 fix: the worker realm is a SEPARATE JS context with its own System properties, so it
			// must set the netty allocator to "unpooled" itself (ClientMain does this for the client
			// realm at boot). netty 4.2's default "adaptive" allocator hard-requires MethodHandles —
			// io.netty.buffer.AdaptivePoolingAllocator uses ConcurrentSkipListIntObjMultimap whose
			// <clinit> throws ExceptionInInitializerError (NoSuchMethodException: acquireFenceFallback)
			// when Lookup.findStatic fails on TeaVM, killing the worker mid chunk-gen (spawn-prep).
			// MUST precede any netty class load in this realm. Unpooled is also correct on a
			// single-threaded green-thread runtime (pooling is pure overhead). Was the c91 crash.
			System.setProperty("io.netty.allocator.type", "unpooled");
			logger.info("[ServerWorker] role:server accepted — booting integrated-server worker");
			// Storage belongs to the page that created level.dat. Ask for its actual
			// database/mode before opening anything in this independent worker realm.
			net.lax1dude.eaglercraft.v1_8.internal.teavm.WorkerFilesystemBridge.initializeWorker();
			// 1. worker server platform context (VFS/IndexedDB, immediate-continue channel)
			ServerPlatformSingleplayer.initializeContext();
			logger.info("[ServerWorker] world filesystem: {}", ServerPlatformSingleplayer.getWorldsDatabase().getInternalDBName());
			// 2. install the {ch,dat} inbound router (sets global __eaglerXOnMessage)
			ServerPlatformSingleplayer.register();
			// 3. replace the mesh boot splitter with the server router (still meta-aware)
			installServerOnMessage(ServerWorkerHost::onMeta);
			// 4. SEAM B prerequisite — populate the datapack/asset EPK map in THIS realm and wire
			//    the vanilla-pack seam. The client packed the resolved assets.epk URL(s) into the
			//    role:server meta; re-fetch them here (same-origin, HTTP-cached from client boot).
			String epkPayload = extractWorkerEPKPayload(roleMeta);
			downloadWorkerEPK(epkPayload);
			net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webAssetMapSupplier =
					net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime::getAssetsMapTeaVM;
			logger.info("[ServerWorker] EPK assets loaded in worker realm: {} entries",
					net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime.getAssetCountTeaVM());
			// 5. SEAM B prerequisite — FULL server bootstrap (dispenser/cauldron/gamerule/command
			//    tables + DFU), mirroring the main-thread boot ORDER (tryDetectVersion first). The
			//    mesh worker only runs bootStrapSlim(); the integrated server needs the full table.
			net.minecraft.SharedConstants.tryDetectVersion();
			net.minecraft.server.Bootstrap.bootStrap();
			logger.info("[ServerWorker] Bootstrap.bootStrap() complete");
			// 6. mark this realm as the dedicated server worker so supervisorTick binds the
			//    seam-c EmbeddedChannel bridge (NOT startMemoryChannel) when the world is ready
			net.lax1dude.eaglercraft.v1_8.sp.server.EaglerIntegratedServerWorker26.enableServerWorkerMode();
			postMeta("server-worker:booted");
			// 7. run the real server supervisor loop (blocks; owns this worker's thread)
			net.lax1dude.eaglercraft.v1_8.sp.server.EaglerIntegratedServerWorker26.serverMain();
		} catch (Throwable t) {
			logger.error("[ServerWorker] fatal during boot");
			logger.error(t);
			try {
				postMeta("server-worker:boot-error:" + t);
			} catch (Throwable t2) {
			}
		}
	}

	private static String extractWorkerEPKPayload(String meta) {
		if (meta == null) {
			return "";
		}
		int index = meta.indexOf(EPK_MARK);
		return index < 0 ? "" : meta.substring(index + 1);
	}

	private static void downloadWorkerEPK(String packed) {
		if (packed == null || packed.isEmpty()) {
			throw new IllegalStateException(
					"serverWorker: no EPK URL(s) were passed to the worker realm (empty role:server meta)");
		}
		String[] entries = packed.split(String.valueOf(EPK_ENTRY_SEP));
		net.lax1dude.eaglercraft.v1_8.internal.teavm.EPKFileEntry[] files =
				new net.lax1dude.eaglercraft.v1_8.internal.teavm.EPKFileEntry[entries.length];
		for (int i = 0; i < entries.length; ++i) {
			int separator = entries[i].indexOf(EPK_FIELD_SEP);
			String url = separator < 0 ? entries[i] : entries[i].substring(0, separator);
			String path = separator < 0 ? "" : entries[i].substring(separator + 1);
			files[i] = new net.lax1dude.eaglercraft.v1_8.internal.teavm.EPKFileEntry(url, path);
		}
		net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime.populateWorkerAssets(files);
	}

	private static void onMeta(String meta) {
		if (meta == null) {
			return;
		}
		if (meta.startsWith("server-worker:health-probe:")) {
			// An ErrorEvent in the page does not imply that a dedicated worker died.
			// Require both the event loop and the Java supervisor loop to be alive so
			// an unwound server coroutine cannot be mistaken for a recovery.
			if (net.lax1dude.eaglercraft.v1_8.sp.server.EaglerIntegratedServerWorker26.isServerMainLoopAlive()) {
				postMeta("server-worker:health-ok:"
						+ meta.substring("server-worker:health-probe:".length()));
			} else {
				postMeta("server-worker:fatal:supervisor-loop-stopped");
			}
		} else if (meta.startsWith("role:server")) {
			// already booted; re-ack for the page
			postMeta("server-worker:role-ack");
		} else {
			// reserved for future control metas; the launch/stop/save control plane
			// rides the {ch,dat} "~!IPC" channel, not meta strings.
			logger.info("[ServerWorker] ignoring control meta: {}", meta);
		}
	}

	@JSBody(params = { "str" }, script = "self.postMessage({ meta: str });")
	private static native void postMeta(String str);

}
