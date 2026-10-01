package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;

import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;

/**
 * Loads a world and creates its integrated server inside the server worker.
 *
 * <p>In the single-thread / desktop path the client's {@code WorldOpenFlows} builds the
 * {@code LevelStorageAccess} + {@code PackRepository} + {@code WorldStem} on the client
 * thread and hands the constructed launch closure (capturing them + the client Minecraft)
 * to the server. That closure cannot cross {@code postMessage}, so inside the dedicated
 * integrated-server Web Worker we rebuild the whole chain from the world folder in the
 * (IndexedDB-backed) VFS — the exact vanilla {@code WorldOpenFlows.openWorldLoadLevelStem}
 * sequence, minus the client GUI screens — and then spin the {@link IntegratedServer} with
 * a {@link WorkerIntegratedServerHost} (no client {@code Minecraft} object).
 *
 * <p>The DATA chain was audited Minecraft-free ({@code LevelStorageSource} is pure
 * {@code Path}/VFS; {@code WorldLoader.load} only wants an {@code Executor}). The one
 * client dependency was the blocking pump {@code minecraft.managedBlock(future::isDone)};
 * here a worker-local {@link WorkerLoadExecutor} (a {@link BlockableEventLoop} whose
 * running thread is this worker green thread) serves as the load's main-thread executor and
 * is drained the same way — a worker CAN block because it owns its thread, and the base
 * {@code waitForTasks()} yield lets the background-executor green threads progress (this is
 * the same machinery the client uses on web, so it is TeaVM-proven).
 *
 * <p>Prerequisites that must already be satisfied by the worker boot
 * ({@code ServerWorkerHost.runServer}) BEFORE this runs: {@code eagler.hosted=true}, the EPK
 * asset map populated + {@code EaglerHosted.webAssetMapSupplier} wired (so
 * {@code ServerPacksSource.createVanillaPackSource} serves the built-in datapack from RAM),
 * and {@code Bootstrap.bootStrap()} complete.
 */
public class WorkerWorldLoader {

	private static final Logger logger = LogManager.getLogger("WorkerWorldLoader");

	private static final String LAUNCHED_VERSION = "26.2-eagler";

	/**
	 * Build the launch supplier for the supervisor loop. The heavy build (datapack load +
	 * world data read + server spin) runs when the supplier is invoked on the supervisor
	 * green thread, inside {@code EaglerIntegratedServerWorker26.startServer}'s try/catch, so
	 * any failure is reported as an IPC {@code 0x15} crash instead of a silent hang.
	 */
	public static Supplier<IntegratedServer> buildLaunch(final String worldName, final String ownerName,
			final boolean demo) {
		return () -> spinWorld(worldName, ownerName, demo);
	}

	private static IntegratedServer spinWorld(final String worldName, final String ownerName, final boolean demo) {
		logger.info("Building world '{}' in the integrated-server worker realm", worldName);
		DataFixer fixer = DataFixers.getDataFixer();
		Path gameDir = Paths.get(".");
		DirectoryValidator validator = LevelStorageSource.parseValidator(gameDir.resolve("allowed_symlinks.txt"));
		LevelStorageSource levelSource = new LevelStorageSource(gameDir.resolve("saves"), gameDir.resolve("backups"),
				validator, fixer);

		LevelStorageSource.LevelStorageAccess access;
		try {
			access = levelSource.validateAndCreateAccess(worldName);
		} catch (Exception e) {
			throw new RuntimeException("Worker failed to open world folder '" + worldName + "'", e);
		}

		PackRepository packRepository = ServerPacksSource.createPackRepository(access);
		WorldStem worldStem;
		try {
			// VFS worlds are always current-version in hosted mode, so the raw unfixed data tag
			// is used directly (the client's WorldOpenFlows skips file-fixing when EaglerHosted).
			Dynamic<?> levelDataTag = access.getUnfixedDataTag(false);
			worldStem = loadWorldStem(access, levelDataTag, packRepository);
			for (LevelStem levelStem : worldStem.registries().compositeAccess().lookupOrThrow(Registries.LEVEL_STEM)) {
				levelStem.generator().validate();
			}
		} catch (Exception e) {
			try {
				access.safeClose();
			} catch (Throwable t2) {
			}
			throw new RuntimeException("Worker failed to load world data / datapacks for '" + worldName + "'", e);
		}

		access.saveDataTag(worldStem.worldDataAndGenSettings().data());

		Services services = Services.create(YggdrasilAuthenticationService.createOffline(java.net.Proxy.NO_PROXY),
				new File("."));
		LevelLoadListener loadListener = LevelLoadListener.compose(LoggingLevelLoadListener.forSingleplayer(),
				EaglerProgressListener.INSTANCE);
		EaglerIntegratedServerHost host = new WorkerIntegratedServerHost(ownerName, demo, LAUNCHED_VERSION, gameDir);

		final LevelStorageSource.LevelStorageAccess fAccess = access;
		final PackRepository fPackRepository = packRepository;
		final WorldStem fWorldStem = worldStem;
		logger.info("World '{}' data loaded; spinning integrated server core", worldName);
		return MinecraftServer.spin(thread -> new IntegratedServer(thread, host, fAccess, fPackRepository, fWorldStem,
				Optional.empty(), services, loadListener));
	}

	/**
	 * Mirror of {@code WorldOpenFlows.loadWorldStem} + {@code loadWorldDataBlocking}, with the
	 * worker green thread standing in for the client main-thread executor.
	 */
	private static WorldStem loadWorldStem(final LevelStorageSource.LevelStorageAccess access,
			final Dynamic<?> levelDataTag, final PackRepository packRepository) throws Exception {
		WorldLoader.PackConfig packConfig = LevelStorageSource.getPackConfig(levelDataTag, packRepository, false);
		WorldLoader.InitConfig config = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.INTEGRATED,
				LevelBasedPermissionSet.GAMEMASTER);
		WorkerLoadExecutor exec = new WorkerLoadExecutor();
		CompletableFuture<WorldStem> resourceLoad = WorldLoader.load(config, context -> {
			Registry<LevelStem> datapackDimensions = context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
			LevelDataAndDimensions data = LevelStorageSource.getLevelDataAndDimensions(access, levelDataTag,
					context.dataConfiguration(), datapackDimensions, context.datapackWorldgen());
			return new WorldLoader.DataLoadOutput<>(data.worldDataAndGenSettings(),
					data.dimensions().dimensionsRegistryAccess());
		}, WorldStem::new, Util.backgroundExecutor(), exec);
		exec.managedBlock(resourceLoad::isDone);
		return resourceLoad.get();
	}

	/**
	 * Worker-side stand-in for {@code Minecraft} as the {@code WorldLoader.load} main-thread
	 * executor: the sync-phase tasks land here and {@link #managedBlock} drains them on this
	 * worker green thread while the base {@code waitForTasks()} yields so the background
	 * executor's green threads progress. Same pattern as {@code SoundEngineExecutor} (web).
	 */
	private static final class WorkerLoadExecutor extends BlockableEventLoop<Runnable> {
		private final Thread thread = Thread.currentThread();

		WorkerLoadExecutor() {
			super("Worker world-load", true);
		}

		@Override
		public Runnable wrapRunnable(final Runnable runnable) {
			return runnable;
		}

		@Override
		protected boolean shouldRun(final Runnable task) {
			return true;
		}

		@Override
		protected Thread getRunningThread() {
			return this.thread;
		}
	}

}
