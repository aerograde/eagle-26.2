package net.lax1dude.eaglercraft.v1_8.sp;

import java.util.List;
import java.util.function.Supplier;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.IPCPacketData;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerRuntime;
import net.lax1dude.eaglercraft.v1_8.sp.internal.ClientPlatformSingleplayer;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket00StartServer;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket01StopServer;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket0DProgressUpdate;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket15Crashed;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket17ConfigureLAN;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket19Autosave;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket20OptionsSnapshot;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket21TickSamples;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacket22LocalWorldReady;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacketBase;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacketFFProcessKeepAlive;
import net.lax1dude.eaglercraft.v1_8.sp.ipc.IPCPacketManager;
import net.lax1dude.eaglercraft.v1_8.sp.server.EaglerWorkerHandoff;
import net.lax1dude.eaglercraft.v1_8.sp.server.ServerWorkerProtocol;
import net.lax1dude.eaglercraft.v1_8.sp.server.WorkerPacketBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;

/**
 * Client-side state machine for starting and stopping integrated singleplayer
 * servers. It exchanges control messages with the server worker and is updated
 * once per frame from {@code Minecraft.runTick}.
 */
public class SingleplayerServerController26 {

	public static final String IPC_CHANNEL = "~!IPC";
	public static final String PLAYER_CHANNEL = "~!LOCAL_PLAYER";

	public static final int STATE_NONE = 0;
	public static final int STATE_WORKER_BOOTING = 1;
	public static final int STATE_WORKER_IDLE = 2;
	public static final int STATE_STARTING = 3;
	public static final int STATE_READY = 4;
	public static final int STATE_STOPPING = 5;
	public static final int STATE_FAILED = 6;

	private static final Logger logger = LogManager.getLogger("SingleplayerServerController26");
	private static final IPCPacketManager packetManagerInstance = new IPCPacketManager();

	private static volatile int state = STATE_NONE;
	private static volatile String pendingCrashReport = null;
	private static volatile String worldStatusString = "";
	private static volatile float worldStatusProgress = -1.0f;
	private static String pendingLocalAddress = null;

	// Client end of the vanilla-packet bridge used when the server runs in a Web Worker.
	// Single-thread and desktop modes use the in-JVM Netty LocalChannel.
	private static WorkerPacketBridge clientBridge = null;

	// serverWorker boot handshake: the worker installs its {ch,dat} IPC router only after it
	// finishes booting (ServerWorkerHost.runServer -> serverMain posts boot-OK 0xFF). Until then
	// its self.onmessage is the mesh splitter, which DROPS {ch,dat} envelopes — so a StartServer
	// (0x00) sent before boot-OK is lost and the world never builds. We hold the StartServer here
	// and flush it the moment boot-OK 0xFF arrives (see handleIPCPacket / launchLocalServer).
	private static boolean workerBooted = false;
	private static IPCPacket00StartServer pendingStartServer = null;
	// Browser LAN is not wired yet, but pause policy must not assume that forever.
	// The future LAN publisher owns this flag; local worker worlds start unpublished.
	private static boolean serverPublished = false;

	// last pushed options snapshot (send-on-change)
	private static int lastRenderDistance = -1;
	private static int lastSimulationDistance = -1;
	private static float lastEntityScale = Float.NaN;
	private static boolean lastPaused = false;
	// Keep the authoritative server at its small bootstrap distance until the client has
	// actually compiled the player's center section. Expanding earlier lets distance-four
	// lighting/worldgen bury the 3x3 terrain needed to close the loading screen.
	private static boolean terrainBootstrap = false;
	private static int distanceRamp = 2;
	private static long nextDistanceRampAtMillis = 0L;
	private static final long DISTANCE_RAMP_STEP_MILLIS = 800L;
	private static long stopRequestedAtMillis = 0L;
	private static final long STOP_ACK_TIMEOUT_MILLIS = 30_000L;
	private static long tickRateReceivedAtMillis = -1L;
	private static double workerTicksPerSecond;
	private static boolean workerTickPaused;

	public static boolean hasWorkerTickRate() {
		return tickRateReceivedAtMillis >= 0L;
	}

	public static boolean isWorkerTickRateStale() {
		return tickRateReceivedAtMillis < 0L || EagRuntime.steadyTimeMillis() - tickRateReceivedAtMillis > 2500L;
	}

	public static double getWorkerTicksPerSecond() {
		return workerTicksPerSecond;
	}

	public static boolean isWorkerTickPaused() {
		return workerTickPaused;
	}

	public static int getState() {
		return state;
	}

	public static boolean isWorldReady() {
		return state == STATE_READY;
	}

	public static boolean isServerPublished() {
		return serverPublished;
	}

	public static void setServerPublished(boolean published) {
		serverPublished = published;
	}

	public static boolean publishLANRelay(String hostURI, int gameMode, boolean allowCommands) {
		return publishLANRelay(java.util.Collections.singletonList(hostURI), gameMode, allowCommands);
	}

	public static boolean publishLANRelay(List<String> hostURIs, int gameMode, boolean allowCommands) {
		if(state != STATE_READY || !ClientPlatformSingleplayer.isServerWorkerMode()) {
			return false;
		}
		if(hostURIs == null || hostURIs.isEmpty()) {
			return false;
		}
		long deadline = net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis() + 15000L;
		boolean connected = false;
		for(int i = 0; i < hostURIs.size(); ++i) {
			long remaining = deadline - net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis();
			if(remaining <= 0L) {
				break;
			}
			int attemptsLeft = hostURIs.size() - i;
			int timeout = (int)Math.max(1000L, remaining / attemptsLeft);
			if(ClientPlatformSingleplayer.openLANRelay(hostURIs.get(i), timeout)) {
				connected = true;
				break;
			}
		}
		if(!connected) {
			return false;
		}
		sendIPCPacket(new IPCPacket17ConfigureLAN(gameMode, allowCommands, java.util.Collections.emptyList()));
		serverPublished = true;
		return true;
	}

	public static boolean unpublishLANRelay() {
		if(!serverPublished) {
			ClientPlatformSingleplayer.closeLANRelay();
			return false;
		}
		sendIPCPacket(new IPCPacket17ConfigureLAN(255, false, java.util.Collections.emptyList()));
		ClientPlatformSingleplayer.closeLANRelay();
		serverPublished = false;
		return true;
	}

	public static void updateLANRelayPolicy(int gameMode, boolean allowCommands) {
		if(serverPublished && state == STATE_READY) {
			sendIPCPacket(new IPCPacket17ConfigureLAN(gameMode, allowCommands, java.util.Collections.emptyList()));
		}
	}

	public static String getLANRelayCode() {
		return ClientPlatformSingleplayer.getLANRelayCode();
	}

	public static String getLANRelayError() {
		return ClientPlatformSingleplayer.getLANRelayError();
	}

	/** Direct connect: apply the guest's answer code to the room's invite. */
	public static void directHostCompleteAnswer(String answerCode) {
		ClientPlatformSingleplayer.directHostCompleteAnswer(answerCode);
	}

	/** Direct connect: mint the offer code for the next guest. */
	public static String directHostNewInviteCode() {
		return ClientPlatformSingleplayer.directHostNewInviteCode();
	}

	/** Direct connect: guest links in the room, connected or still handshaking. */
	public static int getDirectGuestCount() {
		return ClientPlatformSingleplayer.getDirectGuestCount();
	}

	/** Direct connect: guest links whose data channel is actually open. */
	public static int getDirectConnectedGuestCount() {
		return ClientPlatformSingleplayer.getDirectConnectedGuestCount();
	}

	/** Direct connect: true when the world was published through eagler-direct:. */
	public static boolean isDirectConnectHost() {
		return serverPublished && ClientPlatformSingleplayer.isDirectRoomHosting();
	}

	public static boolean isLANRelayOpen() {
		return serverPublished && ClientPlatformSingleplayer.isLANRelayOpen();
	}

	public static boolean isWorldStopped() {
		return state == STATE_WORKER_IDLE || state == STATE_NONE || state == STATE_FAILED;
	}

	/** The LocalAddress id announced by the worker (valid once READY). */
	public static String getLocalAddress() {
		return pendingLocalAddress;
	}

	/** Boot the worker thread (idempotent). */
	public static void startIntegratedServerWorker() {
		if(state == STATE_NONE) {
			stopRequestedAtMillis = 0L;
			state = STATE_WORKER_BOOTING;
			ClientPlatformSingleplayer.startIntegratedServer(false);
		}
	}

	/** Cancel the menu-time worker boot without treating it as a world crash. */
	public static void cancelWorkerStartup() {
		if(state == STATE_WORKER_BOOTING || state == STATE_WORKER_IDLE) {
			try {
				ClientPlatformSingleplayer.killWorker();
			}catch(Throwable t) {
				logger.warn("Failed to terminate integrated-server startup worker");
				logger.warn(t);
			}
			state = STATE_NONE;
			workerBooted = false;
			pendingStartServer = null;
			pendingLocalAddress = null;
			stopRequestedAtMillis = 0L;
			closeClientBridge();
		}
	}

	/**
	 * Releases every local-world transport and worker resource before a remote
	 * multiplayer connection is allocated. Minecraft.disconnect() must run first
	 * so an active world is saved and reaches a stopped state.
	 */
	public static void prepareForRemoteMultiplayer() {
		unpublishLANRelay();
		closeClientBridge();
		if(state != STATE_NONE) {
			try {
				ClientPlatformSingleplayer.killWorker();
			}catch(Throwable t) {
				logger.warn("Failed to retire integrated-server worker during multiplayer handoff");
				logger.warn(t);
			}
		}
		state = STATE_NONE;
		workerBooted = false;
		pendingStartServer = null;
		pendingLocalAddress = null;
		pendingCrashReport = null;
		worldStatusString = "";
		worldStatusProgress = -1.0f;
		stopRequestedAtMillis = 0L;
		terrainBootstrap = false;
		distanceRamp = 2;
		nextDistanceRampAtMillis = 0L;
		serverPublished = false;
		lastRenderDistance = -1;
		lastSimulationDistance = -1;
		lastEntityScale = Float.NaN;
		lastPaused = false;
		MeshWorkerRuntime.prewarm();
		logger.info("Remote multiplayer handoff released LAN and integrated-server state");
	}

	/**
	 * Starts the server worker while the player is choosing a world so its startup work
	 * can overlap that time. The worker is started only in server-worker mode and only
	 * when the controller is idle. If startup fails, world launch can still start a
	 * worker normally. A worker that finishes booting first waits for the world launch;
	 * otherwise the launch request is sent when boot completes.
	 */
	public static void prewarmServerWorker() {
		try {
			if(state == STATE_NONE && ClientPlatformSingleplayer.isServerWorkerModeRequested()) {
				logger.info("serverWorker: prewarming the integrated-server worker for Singleplayer");
				startIntegratedServerWorker();
			}
		}catch(Throwable t) {
			// Prewarm is a pure optimization — a failure here must never break the title screen.
			// World-create falls back to the normal (world-create-time) spawn / single-thread path.
			logger.warn("serverWorker: prewarm failed (will spawn at world-create instead)");
			logger.warn(t);
		}
	}

	/** [CLIENT] hand the constructed launch closure to the worker + track state. */
	public static void launchLocalServer(Supplier<IntegratedServer> launch) {
		launchLocalServer(launch, null);
	}

	/**
	 * [CLIENT] launch a world. In single-thread mode the {@code launch} closure is run
	 * cooperatively by the supervisor pump (shared heap). In serverWorker mode the
	 * closure cannot cross the postMessage boundary, so we additionally send an
	 * {@link IPCPacket00StartServer} carrying the world name and settings; the worker
	 * loads the world from IndexedDB.
	 */
	public static void launchLocalServer(Supplier<IntegratedServer> launch, String worldName) {
		beginLocalServerLaunch(worldName);
		EaglerWorkerHandoff.submitLaunch(launch);
		sendWorkerLaunch(worldName);
	}

	/** Wasm-GC dedicated-worker launch path. Deliberately has no server Supplier in its
	 * signature, so TeaVM can DCE the complete integrated-server graph from the page image. */
	public static void launchLocalServerWorker(String worldName) {
		beginLocalServerLaunch(worldName);
		sendWorkerLaunch(worldName);
	}

	private static void beginLocalServerLaunch(String worldName) {
		boolean reusedWarmWorker = state == STATE_WORKER_IDLE && workerBooted;
		serverPublished = false;
		lastPaused = false;
		stopRequestedAtMillis = 0L;
		startIntegratedServerWorker();
		state = STATE_STARTING;
		pendingLocalAddress = null;
		worldStatusString = reusedWarmWorker ? "singleplayer.busy.runningWorkers" : "";
		worldStatusProgress = -1.0f;
		terrainBootstrap = true;
		distanceRamp = 2;
		nextDistanceRampAtMillis = 0L;
	}

	private static void sendWorkerLaunch(String worldName) {
		if(ClientPlatformSingleplayer.isServerWorkerMode()) {
			String owner = "";
			try {
				owner = net.lax1dude.eaglercraft.v1_8.profile.EaglerProfile.getName();
			}catch(Throwable t) {
			}
			// Bring up only the center neighborhood first. terrainReady() releases this
			// bootstrap cap as soon as the player's center section has actually compiled,
			// then pushOptionsSnapshot sends the user's full distances. This changes order,
			// not final quality, and prevents hundreds of outer-ring generation tasks from
			// delaying the first playable frame.
			int initialViewDistance = 2;
			int initialSimulationDistance = 2;
			IPCPacket00StartServer startPkt = new IPCPacket00StartServer(worldName != null ? worldName : "world",
					owner, 2, initialViewDistance, initialSimulationDistance, false);
			if(workerBooted) {
				// Worker already up (its IPC router is installed): send the launch now.
				sendIPCPacket(startPkt);
			}else {
				// First launch: the worker was just spawned in this same call and is still booting,
				// so its {ch,dat} router is not installed yet. Defer the StartServer until the
				// boot-OK 0xFF arrives (handleIPCPacket flushes it), otherwise the mesh-splitter
				// self.onmessage silently drops it and the world never builds.
				pendingStartServer = startPkt;
				logger.info("serverWorker: deferring StartServer for '{}' until the worker reports boot OK",
						startPkt.worldName);
			}
		}
	}

	public static void shutdownServer() {
		if(state == STATE_STARTING || state == STATE_READY) {
			if(serverPublished) {
				logger.error("Active LAN host shutdown requested; tracing the caller");
				logger.error(new Throwable("LAN host shutdown caller"));
			}
			unpublishLANRelay();
			state = STATE_STOPPING;
			stopRequestedAtMillis = net.minecraft.util.Util.getMillis();
			sendIPCPacket(new IPCPacket01StopServer());
		}
	}

	public static void autoSave() {
		if(state == STATE_READY) {
			sendIPCPacket(new IPCPacket19Autosave());
		}
	}

	/** Per-frame pump; called from Minecraft.runTick (hosted mode). */
	public static void runTick() {
		if(state == STATE_NONE) {
			return;
		}

		// Single-thread mode: the integrated-server supervisor shares this thread, so it
		// must be pumped every frame or it never takes the submitted launch, spins the
		// server core, or reports ready — the busy screen would wait forever ("Starting
		// up integrated server"). No-ops in Web Worker mode (the worker owns its loop).
		ClientPlatformSingleplayer.updateSingleThreadMode();
		ClientPlatformSingleplayer.updateLANRelay();

		if(state == STATE_STOPPING && stopRequestedAtMillis != 0L
				&& net.minecraft.util.Util.getMillis() - stopRequestedAtMillis >= STOP_ACK_TIMEOUT_MILLIS) {
			logger.error("Integrated server did not acknowledge shutdown within {} ms; terminating the worker",
					STOP_ACK_TIMEOUT_MILLIS);
			unpublishLANRelay();
			closeClientBridge();
			try {
				ClientPlatformSingleplayer.killWorker();
			}catch(Throwable t) {
				logger.warn("Failed to terminate unresponsive integrated-server worker");
				logger.warn(t);
			}
			state = STATE_NONE;
			workerBooted = false;
			pendingStartServer = null;
			pendingLocalAddress = null;
			stopRequestedAtMillis = 0L;
			return;
		}

		List<IPCPacketData> queue = ClientPlatformSingleplayer.recieveAllPacket();
		if(queue != null) {
			for(int i = 0, l = queue.size(); i < l; ++i) {
				IPCPacketData data = queue.get(i);
				if(IPC_CHANNEL.equals(data.channel)) {
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
					// serverWorker mode data plane: a batch of framed vanilla packet
					// bytes (server -> client). Decode the [u32 LE len][bytes]×K frames
					// and feed each into the client Connection as if from a socket.
					drainInboundDataBatch(data.contents);
				}
			}
		}

		// Metered inbound feed (anti-freeze): spread a chunk-packet burst across frames so a fast-gen
		// batch isn't all deserialized in one frame (the 0-11 fps hitch); also evens out entity-move
		// delivery that used to arrive in one lump. Runs every frame to drain any backlog.
		pumpInboundFrames();

		pushOptionsSnapshot();

		// serverWorker mode: after the client Connection has queued its outbound packets
		// (from initiateServerboundPlayConnection / gameplay), drain them from the bridge
		// pipeline and transfer the batch to the worker on the data channel.
		pumpClientBridgeOutbound();
	}

	/**
	 * Feed one server->client data batch into the client {@link Connection} via the seam-c
	 * {@link WorkerPacketBridge} (an {@code EmbeddedChannel} running the real client pipeline):
	 * {@code writeInbound} the framed vanilla packet bytes so the client handles them exactly
	 * as if they had arrived over a socket. The bridge is created lazily by
	 * {@link #getOrCreateClientBridgeConnection()} at world join (serverWorker mode only).
	 */
	private static void drainInboundDataBatch(byte[] batch) {
		if(clientBridge == null) {
			// a data batch before the bridge exists = transport is live but the client end
			// isn't wired yet (world not joined); count it so the transport stays observable.
			inboundDataFrames += ServerWorkerProtocol.decodeBatch(batch, (b) -> {});
			return;
		}
		// Anti-freeze: decode the batch into individual packet frames and ENQUEUE them (strict wire
		// order); the metered pump feeds a time-budgeted slice per frame so a burst of chunk packets
		// from the fast native worldgen isn't deserialized all-at-once on the render thread (the
		// 0-11 fps hitch). Cheap packets (entity moves, time) still drain many-per-ms so they're
		// barely delayed — which also smooths entity movement that used to arrive in one big lump.
		long arrivalNanos = net.minecraft.util.Util.getNanos();
		int added = ServerWorkerProtocol.decodeBatch(batch, pendingInboundFrames::addLast);
		for(int i = 0; i < added; ++i) {
			pendingInboundArrivalNanos.enqueue(arrivalNanos);
		}
	}

	/**
	 * Metered inbound pump: feed queued packet frames into the client Connection until the per-frame
	 * time budget is spent, then defer the rest to the next frame. Each frame is handled SYNCHRONOUSLY
	 * inside writeInboundFrame (same-thread pipeline), so a chunk packet's full deserialize (~24
	 * sections of palette + block-states + heightmaps + light-sources + block entities) is charged
	 * here; capping the time keeps the render thread near 60 fps while chunks still stream in fast.
	 * Guarantees progress (always feeds at least one frame, budget checked AFTER each), so the
	 * world-join wait loop can never deadlock — join/handshake packets are cheap and clear immediately.
	 * Runs every frame so a backlog drains even when no new batch arrived. serverWorker/web mode only.
	 */
	private static void pumpInboundFrames() {
		if(clientBridge == null || pendingInboundFrames.isEmpty()) {
			return;
		}
		int queueAtStart = pendingInboundFrames.size();
		long nowNanos = net.minecraft.util.Util.getNanos();
		long headNanosAtStart = pendingInboundArrivalNanos.headNanos();
		long oldestNanosAtStart = headNanosAtStart == 0L
				? 0L : Math.max(0L, nowNanos - headNanosAtStart);
		// Most frames in a terrain burst are tiny follow-up packets. Keeping the fixed 6 ms
		// slice after their FIFO grows into the hundreds leaves entity movement behind terrain
		// for visible seconds even though the server worker is still reporting healthy TPS. A
		// constrained client can also retain a shallow queue for seconds, so queue depth alone
		// is not enough: age promotes the queue through the same bounded 9/12 ms catch-up tiers.
		// Ordinary fresh traffic retains the 6 ms ceiling, the hard cap stays 12 ms, and packet
		// order remains strictly unchanged.
		double budgetMs = SingleplayerInboundPumpPolicy.budgetMillis(queueAtStart, oldestNanosAtStart);
		double startMs = net.minecraft.util.Util.getNanos() / 1_000_000.0;
		long startNanos = net.minecraft.util.Util.getNanos();
		int delivered = 0;
		int deliveredBytes = 0;
		byte[] frame;
		while((frame = pendingInboundFrames.pollFirst()) != null) {
			int n = clientBridge.writeInboundFrame(frame);
			if(n == 0) {
				pendingInboundFrames.clear(); // bridge torn down mid-drain (clean quit)
				pendingInboundArrivalNanos.clear();
				break;
			}
			// PacketUtils may defer the actual listener callback even though the Netty decode
			// itself ran synchronously. Running those tasks only once after this loop allowed a
			// spawn/chunk burst to hide 122 ms of work outside the 6/9/12 ms budget. Execute the
			// deferred work after each wire frame so its real cost is included below. FIFO order
			// is unchanged, and the always-one-frame progress guarantee still holds.
			clientBridge.runInboundTasks();
			pendingInboundArrivalNanos.dequeue();
			inboundDataFrames += n;
			++delivered;
			deliveredBytes += frame.length;
			if((net.minecraft.util.Util.getNanos() / 1_000_000.0 - startMs) >= budgetMs) {
				break; // budget spent — rest stay queued, delivered next frame
			}
		}
		if(delivered > 0) {
			long oldestNanos;
			if(pendingInboundFrames.isEmpty()) {
				pendingInboundArrivalNanos.clear();
				oldestNanos = 0L;
			}else {
				oldestNanos = Math.max(0L, net.minecraft.util.Util.getNanos()
						- pendingInboundArrivalNanos.headNanos());
			}
			net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.singleplayerInbound(
					delivered, pendingInboundFrames.size(), deliveredBytes,
					net.minecraft.util.Util.getNanos() - startNanos, oldestNanos);
		}
	}

	/** Drain the client bridge's outbound packet bytes and transfer them to the worker. */
	private static void pumpClientBridgeOutbound() {
		if(clientBridge == null) {
			return;
		}
		byte[] out = clientBridge.drainOutbound();
		if(out != null) {
			ClientPlatformSingleplayer.sendDataBatch(ServerWorkerProtocol.DATA_CHANNEL, out);
		}
	}

	/**
	 * [CLIENT, serverWorker mode] the vanilla {@link Connection} to use as the pending
	 * connection instead of {@code Connection.connectToLocalServer(addr)}: it runs the real
	 * client pipeline inside an {@code EmbeddedChannel} whose bytes cross to the worker via
	 * the data channel. It reports {@code isMemoryConnection()==true}. Created once
	 * per world join; {@link Minecraft#doWorldLoad} calls this in the serverWorker branch.
	 */
	public static Connection getOrCreateClientBridgeConnection() {
		if(clientBridge == null) {
			clientBridge = WorkerPacketBridge.createClientSide();
		}
		return clientBridge.getConnection();
	}

	private static void closeClientBridge() {
		tickRateReceivedAtMillis = -1L;
		workerTicksPerSecond = 0.0;
		workerTickPaused = false;
		if(clientBridge != null) {
			clientBridge.close();
			clientBridge = null;
		}
		pendingInboundFrames.clear(); // drop any un-fed frames so they don't leak into the next world
		pendingInboundArrivalNanos.clear();
	}

	private static long inboundDataFrames = 0L;

	/** serverWorker mode: inbound vanilla-packet frames (decoded from arriving ~!SVDATA batches)
	 *  awaiting metered delivery to the client Connection. FIFO = strict wire order preserved.
	 *  Metered (see pumpInboundFrames) so a burst of chunk packets from the fast native worldgen is
	 *  spread across frames instead of all deserialized in ONE frame (which froze the render thread). */
	private static final java.util.ArrayDeque<byte[]> pendingInboundFrames = new java.util.ArrayDeque<>();
	private static final SingleplayerInboundPumpPolicy.ArrivalTimes pendingInboundArrivalNanos =
			new SingleplayerInboundPumpPolicy.ArrivalTimes();

	/** Diagnostics: total server->client vanilla-packet frames received over the worker
	 *  data channel (serverWorker mode). */
	public static long getInboundDataFrames() {
		return inboundDataFrames;
	}

	private static void handleIPCPacket(IPCPacketBase packet) {
		switch(packet.id()) {
		case IPCPacketFFProcessKeepAlive.ID: {
			int ack = ((IPCPacketFFProcessKeepAlive) packet).ack;
			switch(ack) {
			case 0xFF:
				logger.info("Worker thread reported boot OK");
				workerBooted = true;
				if(state == STATE_WORKER_BOOTING) {
					state = STATE_WORKER_IDLE;
				}
				// The worker's IPC router is now live: flush any StartServer deferred at launch
				// time (first-world case, where launch raced the worker boot).
				if(pendingStartServer != null) {
					logger.info("serverWorker: worker booted — flushing deferred StartServer for '{}'",
							pendingStartServer.worldName);
					sendIPCPacket(pendingStartServer);
					pendingStartServer = null;
					// Any options snapshot pushed during the boot window was dropped by the worker's
					// pre-boot mesh splitter; reset the send-on-change trackers so the next runTick
					// re-pushes the real render/sim/entity distances + pause to the worker.
					lastRenderDistance = -1;
					lastSimulationDistance = -1;
					lastEntityScale = Float.NaN;
					lastPaused = false;
				}
				break;
			case 0x00:
				logger.info("Worker accepted the launch request");
				break;
				case IPCPacket01StopServer.ID:
					logger.info("Worker reported the server has stopped");
					stopRequestedAtMillis = 0L;
					pendingLocalAddress = null;
					closeClientBridge();
					if(ClientPlatformSingleplayer.canKillWorker()) {
						// A Wasm-GC worker owns a private Java heap. Keeping the stopped worker
						// alive retains its old heap reservation even after every world reference
						// is cleared. Terminating the isolate returns that memory without a
						// stop-the-world System.gc() pause on the render thread.
						ClientPlatformSingleplayer.killWorker();
						state = STATE_NONE;
						workerBooted = false;
						pendingStartServer = null;
						logger.info("Retired stopped integrated-server worker and released its Wasm heap");
					}else {
						state = STATE_WORKER_IDLE;
					}
					break;
			case IPCPacketFFProcessKeepAlive.FAILURE:
				logger.error("Worker reported a launch failure");
				stopRequestedAtMillis = 0L;
				state = STATE_FAILED;
				closeClientBridge();
				break;
			case IPCPacketFFProcessKeepAlive.EXITED:
				logger.error("Worker thread has exited");
				stopRequestedAtMillis = 0L;
				state = STATE_NONE;
				// The worker is gone; a fresh one must re-handshake before it can take a launch.
				workerBooted = false;
				pendingStartServer = null;
				closeClientBridge();
				break;
			default:
				logger.warn("Unknown keepalive ACK 0x{}", Integer.toHexString(ack));
			}
			break;
		}
		case IPCPacket0DProgressUpdate.ID: {
			IPCPacket0DProgressUpdate pkt = (IPCPacket0DProgressUpdate) packet;
			worldStatusString = pkt.updateMessage;
			worldStatusProgress = pkt.updateProgress;
			break;
		}
		case IPCPacket21TickSamples.ID: {
			IPCPacket21TickSamples pkt = (IPCPacket21TickSamples)packet;
			tickRateReceivedAtMillis = EagRuntime.steadyTimeMillis();
			workerTicksPerSecond = pkt.ticksPerSecond();
			workerTickPaused = pkt.paused;
			net.minecraft.util.debugchart.LocalSampleLogger logger =
					Minecraft.getInstance().getDebugOverlay().getTickTimeLogger();
			for(int i = 0, l = pkt.samples.size(); i < l; ++i) {
				logger.logFullSample(pkt.samples.get(i));
			}
			break;
		}
		case IPCPacket15Crashed.ID:
			if(state == STATE_WORKER_BOOTING || state == STATE_WORKER_IDLE) {
				// A worker failure before world launch must not show the server crash screen over
				// the title menu. Stop the failed worker and return to STATE_NONE so the next launch
				// starts a fresh worker instead of sending StartServer to a dead one.
				logger.warn("serverWorker: prewarmed worker reported a failure before any world launch; "
						+ "resetting for a clean world-create retry");
				state = STATE_NONE;
				workerBooted = false;
				pendingStartServer = null;
				try {
					ClientPlatformSingleplayer.killWorker();
				}catch(Throwable t) {
				}
			}else {
				logger.error("Integrated server reported a crash");
				pendingCrashReport = ((IPCPacket15Crashed) packet).crashReport;
			}
			break;
		case IPCPacket22LocalWorldReady.ID:
			pendingLocalAddress = ((IPCPacket22LocalWorldReady) packet).localAddress;
			state = STATE_READY;
			logger.info("Integrated server ready, local channel: {}", pendingLocalAddress);
			break;
		default:
			logger.warn("Client ignored IPC packet 0x{}", Integer.toHexString(packet.id()));
		}
	}

	public static String worldStatusString() {
		return worldStatusString;
	}

	public static float worldStatusProgress() {
		return worldStatusProgress;
	}

	/** Non-null once after a worker/server crash report arrives (0x15). */
	public static String getAndClearCrashReport() {
		String r = pendingCrashReport;
		if(r != null) {
			pendingCrashReport = null;
		}
		return r;
	}

	/** Replaces IntegratedServer's direct reads of Minecraft statics (send-on-change). */
	private static void pushOptionsSnapshot() {
		if(state != STATE_READY && state != STATE_STARTING) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int requestedRender = mc.options.renderDistance().get();
		int requestedSimulation = mc.options.simulationDistance().get();
		int render;
		int simulation;
		if(terrainBootstrap) {
			render = 2;
			simulation = 2;
		}else {
			int requestedMax = Math.max(requestedRender, requestedSimulation);
			long now = EagRuntime.steadyTimeMillis();
			if(distanceRamp > requestedMax) {
				distanceRamp = requestedMax; // distance reductions apply immediately
			}else if(distanceRamp < requestedMax && now >= nextDistanceRampAtMillis) {
				++distanceRamp;
				nextDistanceRampAtMillis = now + DISTANCE_RAMP_STEP_MILLIS;
			}
			render = Math.min(requestedRender, distanceRamp);
			simulation = Math.min(requestedSimulation, distanceRamp);
		}
		float entityScale = mc.options.entityDistanceScaling().get().floatValue();
		/*
		 * Minecraft.pause is updated at the end of the render tick, after this
		 * control-plane pump runs. The GUI state is authoritative at this point and
		 * also covers pause-on-focus-loss. Never forward it while the world is
		 * published: a LAN host opening a menu must not freeze every guest.
		 */
		boolean paused = !serverPublished && mc.gui.isPausing();
		if(render != lastRenderDistance || simulation != lastSimulationDistance
				|| entityScale != lastEntityScale || paused != lastPaused) {
			lastRenderDistance = render;
			lastSimulationDistance = simulation;
			lastEntityScale = entityScale;
			lastPaused = paused;
			sendIPCPacket(new IPCPacket20OptionsSnapshot(render, simulation, entityScale, paused));
		}
	}

	/** Called by the vanilla terrain screen only after the player's section is compiled. */
	public static void terrainReady() {
		if(terrainBootstrap) {
			terrainBootstrap = false;
			distanceRamp = 2;
			nextDistanceRampAtMillis = EagRuntime.steadyTimeMillis();
			// Force the next frame to send the player's real distances.
			lastRenderDistance = -1;
			lastSimulationDistance = -1;
		}
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
		ClientPlatformSingleplayer.sendPacket(new IPCPacketData(IPC_CHANNEL, bytes));
	}

}
