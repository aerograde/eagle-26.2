package net.minecraft.server.network.config;

import com.mojang.logging.LogUtils;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLoadCounter;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class PrepareSpawnTask implements ConfigurationTask {
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("prepare_spawn");
	/**
	 * Web prewarms a 3x3 neighborhood for a new center. For a saved world whose exact
	 * center blob already exists, or a world was imported, loading only that chunk avoids
	 * synchronously generating eight absent neighbors (common in sparse EPK exports). View-distance tickets
	 * expand normally after login. Desktop retains vanilla's larger bootstrap.
	 */
	private static int prepareChunkRadius(final MinecraftServer server, final ServerLevel level,
			final ChunkPos center, final boolean firstJoinWithoutSavedPosition) {
		if (net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
				== net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP) {
			return 3;
		}
		// A fresh browser world starts with a distance-2 client/server bootstrap. Preparing
		// only the center made the loading screen disappear early, but the first live player
		// base tick then synchronously encountered the missing fluid/collision neighbourhood:
		// source-matched 2x-CPU traces measured 12.9 s in LivingEntity.baseTick while those
		// chunks generated. Finish exactly the advertised 5x5 bootstrap before gameplay is
		// marked interactive. This moves mandatory work behind the loading screen; it does
		// not increase the user's render distance or the later interactive worldgen budget.
		if (firstJoinWithoutSavedPosition) {
			return 2;
		}
		if (net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSWorldStorage.isImportedWorld(server.eaglerLevelId())) {
			// Sparse EPKs may intentionally contain only the player's center chunk. Do not
			// generate around those before login, but a normal/full export should preload its
			// already-stored 3x3 just like a native save.
			for (int dx = -1; dx <= 1; ++dx) {
				for (int dz = -1; dz <= 1; ++dz) {
					if (!net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSChunkStorage.hasStoredChunk(
							server.eaglerLevelId(), level.dimension(), new ChunkPos(center.x() + dx, center.z() + dz))) {
						return 0;
					}
				}
			}
		}
		// A native saved world used to take the radius-0 shortcut merely because its
		// center existed. That made every reopen report ready with one chunk and stream
		// the surrounding terrain afterward. Keep the complete 3x3 readiness gate.
		return 1;
	}
   private final MinecraftServer server;
   private final NameAndId nameAndId;
   private final LevelLoadListener loadListener;
   private PrepareSpawnTask.@Nullable State state;

   public PrepareSpawnTask(final MinecraftServer server, final NameAndId nameAndId) {
      this.server = server;
      this.nameAndId = nameAndId;
      this.loadListener = server.getLevelLoadListener();
   }

   @Override
   public void start(final Consumer<Packet<?>> connection) {
      try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
         Optional<ValueInput> loadedData = this.server
            .getPlayerList()
            .loadPlayerData(this.nameAndId)
            .map(tag -> TagValueInput.create(reporter, this.server.registryAccess(), tag));
         ServerPlayer.SavedPosition loadedPosition = loadedData.<ServerPlayer.SavedPosition>flatMap(tag -> tag.read(ServerPlayer.SavedPosition.MAP_CODEC))
            .orElse(ServerPlayer.SavedPosition.EMPTY);
         LevelData.RespawnData respawnData = this.server.getWorldData().overworldData().getRespawnData();
         ServerLevel spawnLevel = loadedPosition.dimension().map(this.server::getLevel).orElseGet(() -> {
            ServerLevel spawnDataLevel = this.server.getLevel(respawnData.dimension());
            return spawnDataLevel != null ? spawnDataLevel : this.server.overworld();
         });
         Optional<Vec3> savedPosition = loadedPosition.position();
			 ChunkPos importedSpawnChunk = null;
			 net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerState.clearSparseStoredChunk();
			 if (savedPosition.isEmpty()
					 && net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSWorldStorage.isImportedWorld(this.server.eaglerLevelId())) {
			 importedSpawnChunk = net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSChunkStorage.findNearestStoredChunk(
					 this.server.eaglerLevelId(), spawnLevel.dimension(), ChunkPos.containing(respawnData.pos()));
			 }
			 final ChunkPos storedSpawnChunk = importedSpawnChunk;
			 if (storedSpawnChunk != null) {
				 net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerState.setSparseStoredChunk(spawnLevel.dimension(), storedSpawnChunk);
				 LOGGER.info("Using sparse stored spawn chunk {} in {}", storedSpawnChunk, spawnLevel.dimension().identifier());
			 } else if (savedPosition.isPresent()
					 && net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSWorldStorage.isImportedWorld(this.server.eaglerLevelId())) {
				 ChunkPos savedChunk = ChunkPos.containing(BlockPos.containing(savedPosition.get()));
				 if (net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSChunkStorage.hasStoredChunk(
						 this.server.eaglerLevelId(), spawnLevel.dimension(), savedChunk)) {
					 net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerState.setSparseStoredChunk(spawnLevel.dimension(), savedChunk);
					 LOGGER.info("Using sparse stored player chunk {} in {}", savedChunk, spawnLevel.dimension().identifier());
				 }
			 }
         CompletableFuture<Vec3> spawnPosition = savedPosition
            .map(CompletableFuture::completedFuture)
			.orElseGet(() -> storedSpawnChunk != null
					? PlayerSpawnFinder.findSpawnInStoredChunk(spawnLevel, storedSpawnChunk, respawnData.pos().getY())
					: net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isBrowserRuntime()
							// setInitialSpawn already generated and validated this exact position for a
							// fresh browser world. Running the randomized respawn-radius search here can
							// select an adjacent chunk and force a second FULL dependency pyramid before
							// the first frame. Reuse the known-safe position; normal respawn searching is
							// retained for desktop and saved player positions retain their exact location.
							? CompletableFuture.completedFuture(Vec3.atBottomCenterOf(respawnData.pos()))
							: PlayerSpawnFinder.findSpawn(spawnLevel, respawnData.pos()));
         Vec2 spawnAngle = loadedPosition.rotation().orElse(new Vec2(respawnData.yaw(), respawnData.pitch()));
		 this.state = new PrepareSpawnTask.Preparing(spawnLevel, spawnPosition, spawnAngle, savedPosition.isEmpty());
      }
   }

   @Override
   public boolean tick() {
      return switch (this.state) {
         case null -> false;
         case PrepareSpawnTask.Preparing preparing -> {
            PrepareSpawnTask.Ready ready = preparing.tick();
            if (ready != null) {
               this.state = ready;
               yield true;
            } else {
               yield false;
            }
         }
         case PrepareSpawnTask.Ready ignored -> true;
         default -> throw new MatchException(null, null);
      };
   }

   public ServerPlayer spawnPlayer(final Connection connection, final CommonListenerCookie cookie) {
      if (this.state instanceof PrepareSpawnTask.Ready ready) {
         return ready.spawn(connection, cookie);
      } else {
         throw new IllegalStateException("Player spawn was not ready");
      }
   }

   public void keepAlive() {
      if (this.state instanceof PrepareSpawnTask.Ready ready) {
         ready.keepAlive();
      }
   }

   public void close() {
      if (this.state instanceof PrepareSpawnTask.Preparing preparing) {
         preparing.cancel();
      }

      this.state = null;
   }

   @Override
   public ConfigurationTask.Type type() {
      return TYPE;
   }

   private final class Preparing implements PrepareSpawnTask.State {
      private final ServerLevel spawnLevel;
      private final CompletableFuture<Vec3> spawnPosition;
      private final Vec2 spawnAngle;
	      private int prepareChunkRadius = -1;
	      private final boolean firstJoinWithoutSavedPosition;
      private @Nullable CompletableFuture<?> chunkLoadFuture;
      private final ChunkLoadCounter chunkLoadCounter = new ChunkLoadCounter();

	      private Preparing(final ServerLevel spawnLevel, final CompletableFuture<Vec3> spawnPosition,
	            final Vec2 spawnAngle, final boolean firstJoinWithoutSavedPosition) {
	         this.spawnLevel = spawnLevel;
	         this.spawnPosition = spawnPosition;
	         this.spawnAngle = spawnAngle;
	         this.firstJoinWithoutSavedPosition = firstJoinWithoutSavedPosition;
	      }

      public void cancel() {
         this.spawnPosition.cancel(false);
      }

      public PrepareSpawnTask.@Nullable Ready tick() {
         if (!this.spawnPosition.isDone()) {
            return null;
         }

	         Vec3 spawnPosition = this.spawnPosition.join();
	         if (this.chunkLoadFuture == null) {
	            ChunkPos spawnChunk = ChunkPos.containing(BlockPos.containing(spawnPosition));
	            this.prepareChunkRadius = PrepareSpawnTask.prepareChunkRadius(
	               PrepareSpawnTask.this.server, this.spawnLevel, spawnChunk, this.firstJoinWithoutSavedPosition
	            );
	            this.chunkLoadCounter
               .track(
                  this.spawnLevel,
                  () -> this.chunkLoadFuture = this.spawnLevel
                     .getChunkSource()
                     .addTicketAndLoadWithRadius(TicketType.PLAYER_SPAWN, spawnChunk, this.prepareChunkRadius)
               );
            PrepareSpawnTask.this.loadListener.start(LevelLoadListener.Stage.LOAD_PLAYER_CHUNKS, this.chunkLoadCounter.totalChunks());
            PrepareSpawnTask.this.loadListener.updateFocus(this.spawnLevel.dimension(), spawnChunk);
         }

         PrepareSpawnTask.this.loadListener
            .update(LevelLoadListener.Stage.LOAD_PLAYER_CHUNKS, this.chunkLoadCounter.readyChunks(), this.chunkLoadCounter.totalChunks());
         if (!this.chunkLoadFuture.isDone()) {
            return null;
         }

         PrepareSpawnTask.this.loadListener.finish(LevelLoadListener.Stage.LOAD_PLAYER_CHUNKS);
         return PrepareSpawnTask.this.new Ready(this.spawnLevel, spawnPosition, this.spawnAngle, this.prepareChunkRadius);
      }
   }

   private final class Ready implements PrepareSpawnTask.State {
      private final ServerLevel spawnLevel;
      private final Vec3 spawnPosition;
      private final Vec2 spawnAngle;
      private final int prepareChunkRadius;

      private Ready(final ServerLevel spawnLevel, final Vec3 spawnPosition, final Vec2 spawnAngle, final int prepareChunkRadius) {
         this.spawnLevel = spawnLevel;
         this.spawnPosition = spawnPosition;
         this.spawnAngle = spawnAngle;
         this.prepareChunkRadius = prepareChunkRadius;
      }

      public void keepAlive() {
         this.spawnLevel
            .getChunkSource()
            .addTicketWithRadius(TicketType.PLAYER_SPAWN, ChunkPos.containing(BlockPos.containing(this.spawnPosition)), this.prepareChunkRadius);
      }

      public ServerPlayer spawn(final Connection connection, final CommonListenerCookie cookie) {
         ChunkPos spawnChunk = ChunkPos.containing(BlockPos.containing(this.spawnPosition));
         this.spawnLevel.waitForEntities(spawnChunk, this.prepareChunkRadius);
         ServerPlayer player = new ServerPlayer(PrepareSpawnTask.this.server, this.spawnLevel, cookie.gameProfile(), cookie.clientInformation());

         try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), PrepareSpawnTask.LOGGER)) {
            Optional<ValueInput> input = PrepareSpawnTask.this.server
               .getPlayerList()
               .loadPlayerData(PrepareSpawnTask.this.nameAndId)
               .map(tag -> TagValueInput.create(reporter, PrepareSpawnTask.this.server.registryAccess(), tag));
            input.ifPresent(player::load);
            player.snapTo(this.spawnPosition, this.spawnAngle.x, this.spawnAngle.y);
            PrepareSpawnTask.this.server.getPlayerList().placeNewPlayer(connection, player, cookie);
			if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()
					&& net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
							!= net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP) {
				net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerState.setInteractiveWorldgen(true);
			}
            input.ifPresent(tag -> {
               player.loadAndSpawnEnderPearls(tag);
               player.loadAndSpawnParentVehicle(tag);
            });
            return player;
         }
      }
   }

   private sealed interface State permits PrepareSpawnTask.Preparing, PrepareSpawnTask.Ready {
   }
}
