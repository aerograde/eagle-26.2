package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.nio.file.Path;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.DataFixer;

import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ModCheck;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.world.level.ChunkPos;

/**
 * Supplies Minecraft client services required by an integrated server.
 *
 * <p>{@link net.minecraft.client.server.IntegratedServer} was hard-wired to the client
 * {@link net.minecraft.client.Minecraft} instance: the constructor pulls the proxy /
 * datafixer / game-profile / demo flag off it, and ~15 runtime methods read
 * {@code this.minecraft} (tick-time logger, game directory, native transport, sync
 * writes, chunk-failure toasts, low-disk warning, the local player for permission
 * refresh, LAN-publish plumbing, the modded-status brand). None of that exists inside a
 * Web Worker (there is no client {@code Minecraft} object in the server-worker realm),
 * so every one of those reads is funnelled through this host interface.
 *
 * <p>Two implementations:
 * <ul>
 * <li>{@link MinecraftBackedHost} — desktop hosted mode + single-thread web mode. Wraps
 *     the real {@code Minecraft} and returns the exact same values in the exact same call
 *     order, so those paths are byte-identical to before the decouple.</li>
 * <li>{@link WorkerIntegratedServerHost} — the dedicated integrated-server Web Worker.
 *     Fed by the {@code IPCPacket00StartServer} launch data (owner name → game profile,
 *     demo flag) and the {@code IPCPacket20OptionsSnapshot} control channel
 *     ({@link EaglerServerState}); the render/simulation/entity distances + pause are
 *     ALREADY routed through {@code EaglerServerState} inside {@code IntegratedServer}
 *     (they never went through this host). Client-UI feedback (toasts, low-disk warning)
 *     is a no-op in the worker; LAN publish is unreachable there.</li>
 * </ul>
 *
 * <p><b>Why not just pass a null Minecraft?</b> Because the constructor dereferences it
 * immediately (getProxy/getFixerUpper/getGameProfile/isDemo) and the tick loop reads the
 * tick-time logger every tick, so a null would NPE on construction and on the first tick.
 * The interface lets the worker supply real, worker-appropriate values while the desktop
 * adapter forwards to Minecraft unchanged.
 */
public interface EaglerIntegratedServerHost {

	/** {@code super(...)} arg — the outbound proxy for LAN publish / auth. */
	java.net.Proxy getProxy();

	/** {@code super(...)} arg — the DataFixerUpper for saved-data migration. */
	DataFixer getFixerUpper();

	/** {@code setSingleplayerProfile(...)} — the world owner's game profile. */
	GameProfile getGameProfile();

	/** {@code setDemo(...)} — demo-mode flag. */
	boolean isDemo();

	/** Current client pause intent; worker values arrive through the options snapshot. */
	boolean isPauseRequested();

	/** Current server view distance requested by the client. */
	int getRenderDistance();

	/** Current server simulation distance requested by the client. */
	int getSimulationDistance();

	/** Entity tracking-distance scale requested by the client. */
	double getEntityDistanceScaling();

	/** Per-tick tick-time sample logger (never null — the tick loop logs every tick). */
	LocalSampleLogger getTickTimeLogger();

	/** {@code getServerDirectory()} — the game directory root. */
	Path getServerDirectory();

	/** {@code fillServerSystemReport} — the launched client version string. */
	String getLaunchedVersion();

	/** {@code useNativeTransport()} — epoll/native netty transport (never on web). */
	boolean useNativeTransport();

	/** {@code forceSynchronousWrites()} — fsync-on-write option. */
	boolean forceSynchronousWrites();

	/** The modded-status brand check merged into the server's own. */
	ModCheck getModdedStatus();

	/** Low-disk-space warning UX (client toast on desktop, no-op in the worker). */
	void sendLowDiskSpaceWarning();

	/** Chunk-load failure UX (client {@code SystemToast} on desktop, no-op in worker). */
	void onChunkLoadFailure(ChunkPos pos);

	/** Chunk-save failure UX (client {@code SystemToast} on desktop, no-op in worker). */
	void onChunkSaveFailure(ChunkPos pos);

	/**
	 * The local client player whose permission/chat abilities must be refreshed when the
	 * server's command permissions change ({@code updateCommandsAllowedForOtherPlayers}).
	 * {@code null} in the worker (there is no client player object in that realm) →
	 * {@code IntegratedServer} skips the refresh.
	 */
	NameAndId getLocalPlayerNameAndId();

	/** Apply refreshed command permissions to the local client player, if one exists. */
	void applyLocalPlayerPermissions(LevelBasedPermissionSet permissions);

	/** LAN-publish step: put the client into multiplayer mode (unreachable in worker). */
	void prepareForMultiplayer();

	/** LAN-publish step: prepare the client connection key pair (unreachable in worker). */
	void prepareClientKeyPair();

}
