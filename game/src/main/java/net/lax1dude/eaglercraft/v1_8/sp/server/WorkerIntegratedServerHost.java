package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.nio.file.Path;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.DataFixer;

import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ModCheck;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.TpsDebugDimensions;
import net.minecraft.world.level.ChunkPos;

/**
 * {@link EaglerIntegratedServerHost} for the dedicated integrated-server Web Worker —
 * there is NO client {@link net.minecraft.client.Minecraft} object in this realm, so this
 * adapter supplies worker-appropriate values from the launch data ({@code
 * IPCPacket00StartServer}: owner name → offline game profile, demo flag) and worker-local
 * substitutes. It deliberately references no client class (no {@code Minecraft}, no
 * {@code SystemToast}, no {@code DebugScreenOverlay}) so nothing pulls a client class-init
 * into the worker.
 *
 * <p>The distances / pause the server actually reacts to (render, simulation, entity
 * scaling, pause) do NOT flow through this host — {@code IntegratedServer} reads them from
 * {@link EaglerServerState}, which the worker feeds from {@code IPCPacket20OptionsSnapshot}.
 * This host only covers the remaining {@code this.minecraft} reads.
 */
public final class WorkerIntegratedServerHost implements EaglerIntegratedServerHost {

	private static final Logger logger = LogManager.getLogger("WorkerIntegratedServerHost");

	private final GameProfile ownerProfile;
	private final boolean demo;
	private final String launchedVersion;
	private final Path serverDirectory;
	// The server ticks every frame and logs a tick-time sample each tick, so this must be a
	// real, stable logger (not null). Same shape the client DebugScreenOverlay allocates.
	private final LocalSampleLogger tickTimeLogger = new LocalSampleLogger(TpsDebugDimensions.values().length);

	public WorkerIntegratedServerHost(final String ownerName, final boolean demo, final String launchedVersion,
			final Path serverDirectory) {
		String name = (ownerName == null || ownerName.isEmpty()) ? "Player" : ownerName;
		this.ownerProfile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
		this.demo = demo;
		this.launchedVersion = launchedVersion;
		this.serverDirectory = serverDirectory;
	}

	@Override
	public java.net.Proxy getProxy() {
		return java.net.Proxy.NO_PROXY;
	}

	@Override
	public DataFixer getFixerUpper() {
		return DataFixers.getDataFixer();
	}

	@Override
	public GameProfile getGameProfile() {
		return ownerProfile;
	}

	@Override
	public boolean isDemo() {
		return demo;
	}

	@Override
	public boolean isPauseRequested() {
		return EaglerServerState.isPauseRequested();
	}

	@Override
	public int getRenderDistance() {
		return EaglerServerState.getRenderDistance();
	}

	@Override
	public int getSimulationDistance() {
		return EaglerServerState.getSimulationDistance();
	}

	@Override
	public double getEntityDistanceScaling() {
		return EaglerServerState.getEntityDistanceScaling();
	}

	@Override
	public LocalSampleLogger getTickTimeLogger() {
		return tickTimeLogger;
	}

	@Override
	public Path getServerDirectory() {
		return serverDirectory;
	}

	@Override
	public String getLaunchedVersion() {
		return launchedVersion;
	}

	@Override
	public boolean useNativeTransport() {
		return false;
	}

	@Override
	public boolean forceSynchronousWrites() {
		// The browser VFS (IndexedDB) has no fsync concept; matches the desktop default.
		return false;
	}

	@Override
	public ModCheck getModdedStatus() {
		// A neutral "probably vanilla" verdict — the worker has no client brand to merge in,
		// and touching Minecraft.checkModStatus() here would force a client class-init.
		return ModCheck.identify("vanilla", () -> "vanilla", "Client", EaglerIntegratedServerHost.class);
	}

	@Override
	public void sendLowDiskSpaceWarning() {
		// No client toast surface in the worker realm; the server-side warnOnLowDiskSpace
		// log line (in the super call site) is enough.
		logger.warn("Low disk space warning (integrated server worker)");
	}

	@Override
	public void onChunkLoadFailure(final ChunkPos pos) {
		// Client SystemToast is unavailable off the main thread; the server already logged
		// the failure + ran warnOnLowDiskSpace before delegating here.
		logger.warn("Chunk load failure at {} (integrated server worker)", pos);
	}

	@Override
	public void onChunkSaveFailure(final ChunkPos pos) {
		logger.warn("Chunk save failure at {} (integrated server worker)", pos);
	}

	@Override
	public NameAndId getLocalPlayerNameAndId() {
		// No client player object in the worker; IntegratedServer skips the permission
		// refresh (the client applies its own permissions from the vanilla packets).
		return null;
	}

	@Override
	public void applyLocalPlayerPermissions(final LevelBasedPermissionSet permissions) {
		// No client player object exists in this realm.
	}

	@Override
	public void prepareForMultiplayer() {
		// LAN publish is unreachable from a browser worker; never invoked.
	}

	@Override
	public void prepareClientKeyPair() {
		// LAN publish is unreachable from a browser worker; never invoked.
	}

}
