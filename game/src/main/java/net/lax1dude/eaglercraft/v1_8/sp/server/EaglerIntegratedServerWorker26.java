package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.IPCPacketData;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.SingleplayerServerController26;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket00StartServer;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket01StopServer;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket15Crashed;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket17ConfigureLAN;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket0BPause;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket0DProgressUpdate;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket19Autosave;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket20OptionsSnapshot;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket21TickSamples;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket22LocalWorldReady;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacketBase;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacketFFProcessKeepAlive;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacketManager;
import net.lax1dude.eaglercraft.v1_8.sp.server.internal.ServerPlatformSingleplayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;

/**
 * Supervises the integrated server, handles control messages, and forwards worker
 * data-channel packets. The server keeps its vanilla {@code spin()} thread.
 */
public class EaglerIntegratedServerWorker26 {

	public static final Logger logger = LogManager.getLogger("EaglerIntegratedServerWorker26");

	private static final IPCPacketManager packetManagerInstance = new IPCPacketManager();

	private static IntegratedServer server = null;
	private static boolean announcedReady = false;
	private static long nextTickSamplesAtMillis = 0L;
	private static long tickSamplesWindowStarted = 0L;
	private static long lastTickSampleSequence = 0L;

	// Server end of the vanilla-packet bridge, used only in a dedicated Web Worker.
	// Desktop and single-thread web modes use the in-JVM Netty LocalChannel.
	private static WorkerPacketBridge serverBridge = null;
	private static final Map<String, WorkerPacketBridge> lanBridges = new LinkedHashMap<>();
	private static boolean lanRelayPublished = false;
	private static final java.util.Set<String> lanPausedPeers = new java.util.HashSet<>();

	// True when ServerWorkerHost starts this supervisor in the dedicated server worker.
	private static boolean serverWorkerMode = false;
	private static volatile boolean serverMainLoopAlive = false;

	/** Mark this realm as the dedicated integrated-server Web Worker. */
	public static void enableServerWorkerMode() {
		serverWorkerMode = true;
	}

	public static boolean isServerMainLoopAlive() {
		return serverMainLoopAlive;
	}

	public static void serverMain() {
		serverMainLoopAlive = true;
		try {
			EaglerServerPerf.setEnabled(ServerPlatformSingleplayer.isPerfDebugEnabled());
			logger.info("Eagler integrated server worker (26.2) has started");
			sendIPCPacket(new IPCPacketFFProcessKeepAlive(0xFF));
			while(true) {
				mainLoop();
				EagUtils.sleep(1);
			}
		}catch(Throwable t) {
			String report;
			try {
				report = "The integrated server supervisor stopped unexpectedly.\n\n" + crashText(t);
			}catch(Throwable t2) {
				report = "The integrated server supervisor stopped unexpectedly.\n\n"
						+ t.getClass().getName() + ": " + t.getMessage();
			}
			// Report before formatting the exception for the console. A native JS stack
			// overflow can itself make Throwable.printStackTrace fail recursively.
			sendIPCPacket(new IPCPacket15Crashed(report));
			logger.error("Worker exited with uncaught exception: {}: {}",
					t.getClass().getName(), t.getMessage());
		}finally {
			serverMainLoopAlive = false;
			sendIPCPacket(new IPCPacketFFProcessKeepAlive(IPCPacketFFProcessKeepAlive.EXITED));
		}
	}

	// In single-thread mode the client pumps the supervisor cooperatively instead of this class
	// owning a blocking loop. singleThreadMain() does the one-time startup handshake;
	// singleThreadUpdate() runs one supervisor tick per client frame. The integrated
	// server itself still runs on its vanilla spin() thread (a TeaVM green thread that
	// advances whenever the client main loop yields). Wired in ClientMain._main().
	public static void singleThreadMain() {
		EaglerServerPerf.setEnabled(ServerPlatformSingleplayer.isPerfDebugEnabled());
		singleThreadCrashed = false; // fresh server: clear any prior-world crash latch
		logger.info("Eagler integrated server worker (26.2) has started (single-thread mode)");
		sendIPCPacket(new IPCPacketFFProcessKeepAlive(0xFF));
	}

	private static boolean singleThreadCrashed = false;

	public static void singleThreadUpdate() {
		if(singleThreadCrashed) {
			return; // one crash report only; don't spam the tick every frame
		}
		try {
			supervisorTick(false);
		}catch(Throwable t) {
			singleThreadCrashed = true;
			logger.error("Single-thread integrated server tick failed");
			logger.error(t);
			// Eagler/TeaVM (c82): log the JS stack up front too (the report below also carries
			// it), so the supervisor-tick escape names its real throw site in the console log.
			try {
				logger.error("[EAGDBG] Supervisor-tick JS stack:\n{}",
						net.lax1dude.eaglercraft.v1_8.EagRuntime.getStackTrace(t));
			}catch(Throwable t2) {
			}
			// Route to the client crash screen (the same IPC 0x15 path worker mode uses:
			// SingleplayerServerController26 -> EaglerServerCrashedScreen) instead of
			// leaving the client hung at "Loading terrain" with only a console log.
			try {
				String report = "The integrated server has crashed!\n\n"
						+ net.lax1dude.eaglercraft.v1_8.EagRuntime.getStackTrace(t);
				sendIPCPacket(new IPCPacket15Crashed(report));
			}catch(Throwable t2) {
				logger.error("Failed to report the integrated server crash to the client");
				logger.error(t2);
			}
		}
	}

	private static void mainLoop() {
		supervisorTick(true);
	}

	// sleepWhenIdle: the Web Worker owns its thread, so it sleeps to avoid busy-spin;
	// the single-thread pump must NOT sleep here (the client owns the frame cadence).
	private static void supervisorTick(boolean sleepWhenIdle) {
		// The supervisor remains schedulable while the vanilla server coroutine is
		// blocked in a future/join. Keep this outside serverTick() so a zero-TPS
		// transition still leaves a bounded diagnostic breadcrumb.
		EaglerServerPerf.heartbeat();
		processAsyncMessageQueue();

		java.util.function.Supplier<IntegratedServer> launch = EaglerWorkerHandoff.takeLaunch();
		if(launch != null) {
			reportProgress("singleplayer.busy.startingIntegratedServer", -1.0f);
			startServer(launch);
		}

		if(server != null) {
			if(!announcedReady && server.isReady()) {
				announcedReady = true;
				if(serverWorkerMode) {
					// No client shares this event loop, so bind an EmbeddedChannel bridge
					// instead of a LocalServerChannel. The
					// client mirrors it with its own bridge; bytes cross on the data channel.
					serverBridge = WorkerPacketBridge.createServerSide(server);
					logger.info("Integrated server core is ready (worker packet bridge bound)");
					sendIPCPacket(new IPCPacket22LocalWorldReady("~!worker-bridge"));
				}else {
					java.net.SocketAddress boundAddress = server.getConnection().startMemoryChannel();
					EaglerWorkerHandoff.setLocalSocketAddress(boundAddress);
					logger.info("Integrated server core is ready, local channel: {}", boundAddress);
					sendIPCPacket(new IPCPacket22LocalWorldReady(boundAddress.toString()));
				}
			}
			// drain the server bridge's outbound packets and transfer them to the client
			if(serverBridge != null) {
				byte[] out = serverBridge.drainOutbound();
				if(out != null) {
					ServerPlatformSingleplayer.sendDataBatch(ServerWorkerProtocol.DATA_CHANNEL, out);
				}
			}
			pumpLANBridges();
			pumpTickSamples();
			if(server.isShutdown()) {
				logger.info("Integrated server core has stopped");
				server = null;
				announcedReady = false;
				nextTickSamplesAtMillis = 0L;
				tickSamplesWindowStarted = 0L;
				lastTickSampleSequence = 0L;
				lanRelayPublished = false;
				EaglerServerState.setLANPublished(false);
				if(serverBridge != null) {
					serverBridge.close();
					serverBridge = null;
				}
				closeAllLANBridges();
				EaglerWorkerHandoff.setCurrentServer(null);
				sendIPCPacket(new IPCPacketFFProcessKeepAlive(IPCPacket01StopServer.ID));
			}
		}

		if(sleepWhenIdle && server == null && launch == null) {
			EagUtils.sleep(50);
		}
	}

	/**
	 * The vanilla integrated server writes tick timing into a worker-local logger. Once a
	 * second, forward only newly completed samples plus their wall-time window. Re-sending
	 * the latest 20 entries duplicates old fast ticks on an overloaded server and misses
	 * intervals when no tick completes. The bounded ring also handles sprinted tick rates.
	 */
	private static void pumpTickSamples() {
		if(!serverWorkerMode || server == null || !server.isReady()) {
			return;
		}
		long now = net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis();
		net.minecraft.util.debugchart.LocalSampleLogger samples =
				(net.minecraft.util.debugchart.LocalSampleLogger)server.getTickTimeLoggerForEagler();
		long sequence = samples.totalSamples();
		if(tickSamplesWindowStarted == 0L || sequence < lastTickSampleSequence) {
			tickSamplesWindowStarted = now;
			lastTickSampleSequence = sequence;
			nextTickSamplesAtMillis = now + 1000L;
			return;
		}
		if(now < nextTickSamplesAtMillis) {
			return;
		}
		nextTickSamplesAtMillis = now + 1000L;
		int size = samples.size();
		long completed = sequence - lastTickSampleSequence;
		int width = net.minecraft.util.debugchart.TpsDebugDimensions.values().length;
		int first = size - (int)Math.min(completed, size);
		java.util.ArrayList<long[]> copy = new java.util.ArrayList<>(size - first);
		for(int i = first; i < size; ++i) {
			long[] row = new long[width];
			for(int j = 0; j < width; ++j) {
				row[j] = samples.get(i, j);
			}
			copy.add(row);
		}
		sendIPCPacket(new IPCPacket21TickSamples(copy, completed, now - tickSamplesWindowStarted, server.isPaused()));
		lastTickSampleSequence = sequence;
		tickSamplesWindowStarted = now;
	}

	private static void startServer(java.util.function.Supplier<IntegratedServer> launch) {
		try {
			logger.info("Worker is spinning up the integrated server core");
			server = launch.get();
			announcedReady = false;
			EaglerWorkerHandoff.setCurrentServer(server);
			sendIPCPacket(new IPCPacketFFProcessKeepAlive(0x00));
		}catch(Throwable t) {
			logger.error("Failed to start integrated server core");
			logger.error(t);
			// The log facade prints only ~2 levels of the cause chain, so an ExecutionException
			// (WorldLoader.load future) hides the REAL error under it. Log the FULL unwrapped chain
			// explicitly — class names + messages are not obfuscated, so this stays diagnosable in
			// an obfuscated (production) build. Same summary rides the 0x15 crash report below.
			logger.error("Full cause chain:\n{}", fullCauseChain(t));
			server = null;
			reportCrash(crashText(t));
			sendIPCPacket(new IPCPacketFFProcessKeepAlive(IPCPacketFFProcessKeepAlive.FAILURE));
		}
	}

	/** Walk getCause() and list every level's class name + message (obfuscation-proof — only
	 *  stack FRAMES are obfuscated, not exception class names). Guards against self-referential
	 *  and over-deep chains. */
	public static String fullCauseChain(Throwable t) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for(Throwable c = t; c != null && i < 30; c = c.getCause(), ++i) {
			sb.append("  [").append(i).append("] ").append(c.getClass().getName()).append(": ")
					.append(c.getMessage()).append('\n');
			if(c.getCause() == c) {
				break;
			}
		}
		return sb.toString();
	}

	private static void processAsyncMessageQueue() {
		List<IPCPacketData> queue = ServerPlatformSingleplayer.recieveAllPacket();
		if(queue == null) {
			return;
		}
		EaglerServerPerf.workerMessageBatch(queue.size());
		for(int i = 0, l = queue.size(); i < l; ++i) {
			IPCPacketData data = queue.get(i);
			if(SingleplayerServerController26.IPC_CHANNEL.equals(data.channel)) {
				IPCPacketBase packet;
				try {
					packet = packetManagerInstance.IPCDeserialize(data.contents);
				}catch(Throwable t) {
					logger.error("Failed to deserialize IPC packet");
					logger.error(t);
					continue;
				}
				handleIPCPacket(packet);
			}else if(ServerWorkerProtocol.DATA_CHANNEL.equals(data.channel)) {
				// Forward framed client packets through the server bridge pipeline.
				if(serverBridge != null) {
					serverBridge.writeInboundBatch(data.contents);
				}
			}else if(data.channel != null && data.channel.startsWith(ServerWorkerProtocol.LAN_DATA_PREFIX)) {
				if(server != null && lanRelayPublished) {
					String peerId = data.channel.substring(ServerWorkerProtocol.LAN_DATA_PREFIX.length());
					if(isValidPeerId(peerId)) {
						WorkerPacketBridge bridge = getOrOpenLANBridge(peerId);
						bridge.writeInboundRaw(data.contents);
					}
				}
			}else if(ServerWorkerProtocol.LAN_CONTROL_CHANNEL.equals(data.channel)) {
				handleLANControl(new String(data.contents, StandardCharsets.UTF_8));
			}
			// (on desktop the data plane is the in-JVM netty LocalChannel; unused here)
		}
	}

	private static void handleIPCPacket(IPCPacketBase packet) {
		switch(packet.id()) {
		case IPCPacket00StartServer.ID: {
			// serverWorker mode launch handshake (the closure-based EaglerWorkerHandoff
			// path cannot cross postMessage, so the client sends world name/settings here).
			IPCPacket00StartServer pkt = (IPCPacket00StartServer) packet;
			logger.info("Worker received StartServer for world '{}' (owner '{}', view {}, simulation {})",
					pkt.worldName, pkt.ownerName, pkt.initialViewDistance, pkt.initialSimulationDistance);
			// Apply launch-time distances before WorkerWorldLoader constructs the IntegratedServer.
			// Otherwise IntegratedPlayerList/ServerLevel start at vanilla 10/10 and enqueue a
			// 21x21 chunk area before the first options snapshot can reduce it to the browser's
			// requested 4/4. That stale generation queue starves server ticks for minutes.
			lanRelayPublished = false;
			closeAllLANBridges();
			EaglerServerState.setInitialDistances(pkt.initialViewDistance, pkt.initialSimulationDistance);
			// Build the world from its launch settings and the worker's IndexedDB-backed VFS.
			// The packet bridge is bound after the integrated server becomes ready.
			EaglerWorkerHandoff.submitLaunch(
					WorkerWorldLoader.buildLaunch(pkt.worldName, pkt.ownerName, pkt.demoMode));
			break;
		}
		case IPCPacket01StopServer.ID:
			if(server != null) {
				logger.info("Worker received stop request");
				if(net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
						== net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP) {
					// Desktop hosted mode has a REAL server thread: halt()'s executeBlocking
					// player-removal join() blocks the caller and the server thread completes it.
					server.halt(false);
				}else {
					// Web single-thread mode: the server runs on a green thread pumped
					// cooperatively by the client frame loop, NOT this (client) thread.
					// IntegratedServer.halt() calls executeBlocking(removePlayers).join(); on
					// web that join() runs on the client thread while the future can only
					// complete on the server green thread, so teavm-compat's
					// TCompletableFuture.checkDone() throws ("...would deadlock in the browser
					// runtime"). That escape latches singleThreadCrashed, the stop-complete ACK
					// is never sent, and the client hangs forever on the "Saving world" screen.
					// Instead request a cooperative stop: running=false makes runServer()'s loop
					// fall through to stopServer(), which saves worlds + removes players itself;
					// the supervisorTick isShutdown() check then fires the ACK when the green
					// thread finishes. (Client-side quit loop already pumps runTick+renderFrame.)
					server.eaglerForceStop();
				}
			}else {
				sendIPCPacket(new IPCPacketFFProcessKeepAlive(IPCPacket01StopServer.ID));
			}
			break;
		case IPCPacket0BPause.ID:
			// pause rides the options snapshot in 26.2; packet kept for upstream parity
			break;
		case IPCPacket19Autosave.ID:
			if(server != null) {
				server.execute(() -> server.saveEverything(true, false, false));
			}
			break;
		case IPCPacket20OptionsSnapshot.ID: {
			IPCPacket20OptionsSnapshot pkt = (IPCPacket20OptionsSnapshot) packet;
			EaglerServerState.updateFromSnapshot(pkt.renderDistance, pkt.simulationDistance,
					pkt.entityDistanceScaling, pkt.pauseRequested);
			break;
		}
		case IPCPacket17ConfigureLAN.ID: {
			IPCPacket17ConfigureLAN pkt = (IPCPacket17ConfigureLAN) packet;
			if(server == null) {
				break;
			}
			if(pkt.gamemode == 255) {
				lanRelayPublished = false;
				closeAllLANBridges();
				if(server.isPublished()) {
					server.unpublishServer();
				}
				EaglerServerState.setLANPublished(false);
				logger.info("Browser LAN relay unpublished");
			}else {
				boolean published = server.eaglerPublishRelayServer(GameType.byId(pkt.gamemode), pkt.cheats);
				lanRelayPublished = true;
				EaglerServerState.setLANPublished(true);
				logger.info("Browser LAN relay publish policy {} (mode {}, commands {})",
						published ? "enabled" : "already active", pkt.gamemode, pkt.cheats);
			}
			break;
		}
		default:
			logger.warn("Worker ignored IPC packet 0x{}", Integer.toHexString(packet.id()));
		}
	}

	private static void pumpLANBridges() {
		Iterator<Map.Entry<String, WorkerPacketBridge>> itr = lanBridges.entrySet().iterator();
		while(itr.hasNext()) {
			Map.Entry<String, WorkerPacketBridge> entry = itr.next();
			WorkerPacketBridge bridge = entry.getValue();
			if(lanPausedPeers.contains(entry.getKey())) {
				continue;
			}
			byte[] out = bridge.drainOutboundRaw();
			if(out != null) {
				ServerPlatformSingleplayer.sendDataBatch(ServerWorkerProtocol.LAN_DATA_PREFIX + entry.getKey(), out);
			}
			if(!bridge.isOpen()) {
				bridge.close();
				itr.remove();
			}
		}
	}

	private static void handleLANControl(String command) {
		if("close-all".equals(command)) {
			closeAllLANBridges();
		}else if(command != null && command.startsWith("open:")) {
			String peerId = command.substring(5);
			if(server != null && lanRelayPublished && isValidPeerId(peerId)) {
				getOrOpenLANBridge(peerId);
			}
		}else if(command != null && command.startsWith("close:")) {
			String peerId = command.substring(6);
			lanPausedPeers.remove(peerId);
			WorkerPacketBridge bridge = lanBridges.remove(peerId);
			if(bridge != null) {
				bridge.close();
				logger.info("Closed relay LAN peer {}", peerId);
			}
		}else if(command != null && command.startsWith("pause:")) {
			String peerId = command.substring(6);
			if(isValidPeerId(peerId)) {
				lanPausedPeers.add(peerId);
			}
		}else if(command != null && command.startsWith("resume:")) {
			String peerId = command.substring(7);
			if(isValidPeerId(peerId)) {
				lanPausedPeers.remove(peerId);
			}
		}
	}

	private static WorkerPacketBridge getOrOpenLANBridge(String peerId) {
		WorkerPacketBridge bridge = lanBridges.get(peerId);
		if(bridge == null) {
			bridge = WorkerPacketBridge.createRemoteServerSide(server);
			lanBridges.put(peerId, bridge);
			logger.info("Opened relay LAN peer {}", peerId);
		}
		return bridge;
	}

	private static void closeAllLANBridges() {
		for(WorkerPacketBridge bridge : lanBridges.values()) {
			bridge.close();
		}
		lanBridges.clear();
		lanPausedPeers.clear();
	}

	private static boolean isValidPeerId(String peerId) {
		if(peerId == null || peerId.length() != 16) {
			return false;
		}
		for(int i = 0; i < peerId.length(); ++i) {
			char c = peerId.charAt(i);
			if(!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
				return false;
			}
		}
		return true;
	}

	/** World-load/op progress over IPC 0x0D (thread-safe; the server thread calls
	 * this via EaglerProgressListener during spawn preparation). */
	public static void reportProgress(String message, float progress) {
		sendIPCPacket(new IPCPacket0DProgressUpdate(message, progress));
	}

	/** Send a crash report to the client over IPC 0x15 (thread-safe; the server
	 * thread calls this from IntegratedServer.onServerCrash in hosted mode). */
	public static void reportCrash(String report) {
		sendIPCPacket(new IPCPacket15Crashed(report));
	}

	public static String crashText(Throwable t) {
		java.io.StringWriter sw = new java.io.StringWriter();
		java.io.PrintWriter pw = new java.io.PrintWriter(sw);
		// Explicit cause chain FIRST (obfuscation-proof root-cause naming), then the raw trace.
		pw.println("=== cause chain ===");
		pw.print(fullCauseChain(t));
		pw.println("=== stack trace ===");
		t.printStackTrace(pw);
		pw.flush();
		return sw.toString();
	}

	private static void sendIPCPacket(IPCPacketBase packet) {
		byte[] bytes;
		try {
			bytes = packetManagerInstance.IPCSerialize(packet);
		}catch(Throwable t) {
			logger.error("Failed to serialize IPC packet 0x{}", Integer.toHexString(packet.id()));
			logger.error(t);
			return;
		}
		ServerPlatformSingleplayer.sendPacket(new IPCPacketData(SingleplayerServerController26.IPC_CHANNEL, bytes));
	}

}
