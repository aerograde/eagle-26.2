package net.minecraft.client.server;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.CrashReport;
import net.minecraft.SharedConstants;
import net.minecraft.SystemReport;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.notifications.NotificationManager;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.stats.Stats;
import net.minecraft.util.ModCheck;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class IntegratedServer extends MinecraftServer {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MIN_SIM_DISTANCE = 2;
   public static final int MAX_PLAYERS = 8;
   // Eagler seam b: the IntegratedServer no longer holds a client Minecraft directly; every
   // former this.minecraft read is funnelled through this host so the server can be built +
   // ticked inside a Web Worker (no client object). Desktop/single-thread wrap Minecraft in a
   // MinecraftBackedHost (byte-identical); the worker uses a WorkerIntegratedServerHost.
   private final net.lax1dude.eaglercraft.v1_8.sp.server.EaglerIntegratedServerHost host;
   private boolean paused = true;
   private int publishedPort = -1;
   private @Nullable GameType gameTypeForOtherPlayers;
   private @Nullable Boolean commandsAllowedForOtherPlayers;
   private @Nullable LanServerPinger lanPinger;
   private @Nullable UUID uuid;
   private int previousSimulationDistance = 0;
   private volatile List<SimpleGizmoCollector.GizmoInstance> latestTicksGizmos = new ArrayList<>();
   private final SimpleGizmoCollector gizmoCollector = new SimpleGizmoCollector();
   private MinecraftServer.MultiplayerScope multiplayerScope = MinecraftServer.MultiplayerScope.OFF;

   /**
    * Desktop hosted mode + single-thread web mode: the launch closure captures the client
    * {@code Minecraft}; wrap it in a {@link net.lax1dude.eaglercraft.v1_8.sp.server.MinecraftBackedHost}
    * so behaviour is byte-identical to the pre-seam-b class.
    */
   public IntegratedServer(
      final Thread serverThread,
      final Minecraft minecraft,
      final LevelStorageSource.LevelStorageAccess levelStorageAccess,
      final PackRepository packRepository,
      final WorldStem worldStem,
      final Optional<GameRules> gameRules,
      final Services services,
      final LevelLoadListener levelLoadListener
   ) {
      this(
         serverThread,
         new net.lax1dude.eaglercraft.v1_8.sp.server.MinecraftBackedHost(minecraft),
         levelStorageAccess,
         packRepository,
         worldStem,
         gameRules,
         services,
         levelLoadListener
      );
   }

   /**
    * Eagler seam b: the host-based constructor. The dedicated integrated-server Web Worker
    * uses this directly with a {@code WorkerIntegratedServerHost} — no client Minecraft object
    * is required to build or run the server.
    */
   public IntegratedServer(
      final Thread serverThread,
      final net.lax1dude.eaglercraft.v1_8.sp.server.EaglerIntegratedServerHost host,
      final LevelStorageSource.LevelStorageAccess levelStorageAccess,
      final PackRepository packRepository,
      final WorldStem worldStem,
      final Optional<GameRules> gameRules,
      final Services services,
      final LevelLoadListener levelLoadListener
   ) {
      super(
         serverThread,
         levelStorageAccess,
         packRepository,
         worldStem,
         gameRules,
         host.getProxy(),
         host.getFixerUpper(),
         services,
         levelLoadListener,
         false,
         new NotificationManager()
      );
      this.setSingleplayerProfile(host.getGameProfile());
      this.setDemo(host.isDemo());
	      this.setPlayerList(new IntegratedPlayerList(this, this.registries(), this.playerDataStorage));
	      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
	         this.previousSimulationDistance = this.getPlayerList().getSimulationDistance();
	      }
	      this.host = host;
   }

   @Override
   protected boolean initServer() {
      LOGGER.info("Starting integrated minecraft server version {}", SharedConstants.getCurrentVersion().name());
      this.setUsesAuthentication(true);
      this.initializeKeyPair();
      this.loadLevel();
      GameProfile host = this.getSingleplayerProfile();
      String levelName = this.getWorldData().getLevelName();
      this.setMotd(host != null ? host.name() + " - " + levelName : levelName);
      this.saveEverything(false, true, true);
      return true;
   }

   @Override
   public boolean isPaused() {
      return this.paused;
   }

   @Override
   protected void processPacketsAndTick(final boolean sprinting) {
      try (Gizmos.TemporaryCollection ignored = Gizmos.withCollector(this.gizmoCollector)) {
         super.processPacketsAndTick(sprinting);
      }

      if (this.tickRateManager().runsNormally()) {
         this.latestTicksGizmos = this.gizmoCollector.drainGizmos();
      }
   }

   @Override
   protected void tickServer(final BooleanSupplier haveTime) {
      boolean wasPaused = this.paused;
      // Eagler worker split: pause comes over the IPC options snapshot, not a
      // cross-thread read of client statics (browser-identical control plane)
      this.paused = this.host.isPauseRequested() || this.getPlayerList().getPlayers().isEmpty();
	      ProfilerFiller profiler = Profiler.get();
	      // Browser worldgen runs on its own worker/coroutine. Keep applying the
	      // ring-ramped distance snapshot while gameplay is paused or the tab is
	      // occluded, so minimized loading does not resume to an empty outer ring.
	      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
	         this.updateDistanceSettings();
	      }
      if (!wasPaused && this.paused) {
         profiler.push("autoSave");
         LOGGER.info("Saving and pausing game...");
         this.saveEverything(false, false, false);
         profiler.pop();
      }

      if (this.paused) {
         this.tickPaused();
      } else {
         if (wasPaused) {
            this.forceGameTimeSynchronization();
         }

         super.tickServer(haveTime);
	         if (!net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
	            this.updateDistanceSettings();
	         }
	      }
	   }

	   private void updateDistanceSettings() {
	      int serverViewDistance = Math.max(2, this.host.getRenderDistance());
	      if (serverViewDistance != this.getPlayerList().getViewDistance()) {
	         LOGGER.info("Changing view distance to {}, from {}", serverViewDistance, this.getPlayerList().getViewDistance());
	         this.getPlayerList().setViewDistance(serverViewDistance);
	      }

	      int serverSimulationDistance = Math.max(2, this.host.getSimulationDistance());
	      if (serverSimulationDistance != this.previousSimulationDistance) {
	         LOGGER.info("Changing simulation distance to {}, from {}", serverSimulationDistance, this.previousSimulationDistance);
	         this.getPlayerList().setSimulationDistance(serverSimulationDistance);
	         this.previousSimulationDistance = serverSimulationDistance;
	      }
	   }

   protected LocalSampleLogger getTickTimeLogger() {
      return this.host.getTickTimeLogger();
   }

   @Override
   public boolean isTickTimeLoggingEnabled() {
      return true;
   }

   private void tickPaused() {
      this.tickConnection();

      for (ServerPlayer player : this.getPlayerList().getPlayers()) {
         player.awardStat(Stats.TOTAL_WORLD_TIME);
      }
   }

   @Override
   public boolean shouldRconBroadcast() {
      return true;
   }

   @Override
   public boolean shouldInformAdmins() {
      return true;
   }

   @Override
   public Path getServerDirectory() {
      return this.host.getServerDirectory();
   }

   @Override
   public boolean isDedicatedServer() {
      return false;
   }

   @Override
   public int getRateLimitPacketsPerSecond() {
      return 0;
   }

   @Override
   public int getCommandSpamThresholdSeconds() {
      return 0;
   }

   @Override
   public int getChatSpamThresholdSeconds() {
      return 0;
   }

   @Override
   public boolean useNativeTransport() {
      return this.host.useNativeTransport();
   }

   @Override
   protected void onServerCrash(final CrashReport report) {
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
         // Eagler crash UX: report over IPC 0x15 — the client shows the crash screen
         // and SURVIVES (vanilla relayDelayCrash would take the whole game down)
         net.lax1dude.eaglercraft.v1_8.sp.server.EaglerIntegratedServerWorker26
               .reportCrash(report.getFriendlyReport(net.minecraft.ReportType.CRASH));
      } else {
         BlockableEventLoop.relayDelayCrash(report);
      }
   }

   @Override
   public SystemReport fillServerSystemReport(final SystemReport systemReport) {
      systemReport.setDetail("Type", "Integrated Server");
      systemReport.setDetail("Is Modded", () -> this.getModdedStatus().fullDescription());
      systemReport.setDetail("Launched Version", this.host::getLaunchedVersion);
      return systemReport;
   }

   @Override
   public ModCheck getModdedStatus() {
      return this.host.getModdedStatus().merge(super.getModdedStatus());
   }

   @Override
   public boolean publishServer(final MinecraftServer.MultiplayerScope scope, final @Nullable GameType gameMode, final boolean allowCommands, final int port) {
      if (gameMode != null) {
         this.setGameTypeForOtherPlayers(gameMode);
      }

      this.setCommandsAllowedForOtherPlayers(allowCommands);
      return this.publishServer(scope, port);
   }

   public boolean publishServer(final MinecraftServer.MultiplayerScope scope, final int port) {
      if (scope != MinecraftServer.MultiplayerScope.OFF && !this.isPublished()) {
         try {
            this.host.prepareForMultiplayer();
            this.host.prepareClientKeyPair();
            this.getConnection().startTcpServerListener(null, port);
            LOGGER.info("Published LAN server on port {}", port);
            this.publishedPort = port;
            this.lanPinger = new LanServerPinger(this.getMotd(), Integer.toString(port));
            this.lanPinger.start();
            this.setMultiplayerScope(scope);
            this.updateCommandsAllowedForOtherPlayers();
            return true;
         } catch (IOException var4) {
            return false;
         }
      } else {
         return false;
      }
   }

   /**
    * Eagler browser LAN publishes through an authenticated WebSocket relay rather than a TCP
    * listening socket. The page owns the room socket; the server worker still owns all vanilla
    * login/game connections and only needs the published policy state.
    */
   public boolean eaglerPublishRelayServer(final @Nullable GameType gameMode, final boolean allowCommands) {
      if (gameMode != null) {
         this.setGameTypeForOtherPlayers(gameMode);
      }
      this.setCommandsAllowedForOtherPlayers(allowCommands);
      // Browser workers do not have the RSA KeyPairGenerator used by vanilla's
      // online-mode login. Relay LAN is code-gated and intentionally supports
      // offline/cracked clients, so do not enter the encryption/session-server
      // branch with the browser's null keypair.
      this.setUsesAuthentication(false);
      if (this.isPublished()) {
         this.updateCommandsAllowedForOtherPlayers();
         return false;
      }
      this.publishedPort = 0;
      this.setMultiplayerScope(MinecraftServer.MultiplayerScope.LAN);
      this.updateCommandsAllowedForOtherPlayers();
      LOGGER.info("Published integrated server through browser LAN relay");
      return true;
   }

   public void setWorldGameType(final GameType gameMode) {
      this.setDefaultGameType(gameMode);
      this.applyGameTypeToPlayers(gameMode, true);
      if (this.gameTypeForOtherPlayers == null) {
         this.applyGameTypeToPlayers(gameMode, false);
      }
   }

   public GameType getGameTypeForOtherPlayers() {
      return (GameType)MoreObjects.firstNonNull(this.gameTypeForOtherPlayers, this.worldData.getGameType());
   }

   public void setGameTypeForOtherPlayers(final GameType gameMode) {
      this.gameTypeForOtherPlayers = gameMode;
      this.applyGameTypeToPlayers(gameMode, false);
   }

   private void applyGameTypeToPlayers(final GameType gameMode, final boolean singleplayerOwner) {
      for (ServerPlayer player : this.getPlayerList().getPlayers()) {
         if (this.isSingleplayerOwner(player.nameAndId()) == singleplayerOwner) {
            player.setGameMode(gameMode);
         }
      }
   }

   public void setWorldAllowCommands(final boolean allowCommands) {
      this.getWorldData().setAllowCommands(allowCommands);
      this.updateCommandsAllowedForOtherPlayers();
   }

   public boolean commandsAllowedForOtherPlayers() {
      return (Boolean)MoreObjects.firstNonNull(this.commandsAllowedForOtherPlayers, this.worldData.isAllowCommands());
   }

   public void setCommandsAllowedForOtherPlayers(final boolean allowCommands) {
      this.commandsAllowedForOtherPlayers = allowCommands;
      this.updateCommandsAllowedForOtherPlayers();
   }

   private void updateCommandsAllowedForOtherPlayers() {
      this.getPlayerList().setAllowCommandsForAllPlayers(this.commandsAllowedForOtherPlayers());

      for (ServerPlayer player : this.getPlayerList().getPlayers()) {
         this.getPlayerList().sendPlayerPermissionLevel(player);
      }

      NameAndId localPlayer = this.host.getLocalPlayerNameAndId();
      if (localPlayer != null) {
         this.host.applyLocalPlayerPermissions(this.getProfilePermissions(localPlayer));
      }
   }

   private void teardownPublishedState() {
      this.stopLanPinger();
      this.publishedPort = -1;
      this.setMultiplayerScope(MinecraftServer.MultiplayerScope.OFF);
      this.updateCommandsAllowedForOtherPlayers();
   }

   private void stopLanPinger() {
      if (this.lanPinger != null) {
         this.lanPinger.interrupt();
         this.lanPinger = null;
      }
   }

   @Override
   public boolean unpublishServer() {
      if (!this.isPublished()) {
         return false;
      }

      if (this.multiplayerScope == MinecraftServer.MultiplayerScope.LAN) {
         LOGGER.info("Unpublishing integrated server (was on port {})", this.publishedPort);
      }

      this.getConnection().stopTcpServerListener();
      Component reason = Component.translatable("multiplayer.disconnect.server_shutdown");

      for (ServerPlayer player : Lists.newArrayList(this.getPlayerList().getPlayers())) {
         if (!player.getUUID().equals(this.uuid)) {
            player.connection.disconnect(reason);
         }
      }

      this.getPlayerList().setAllowCommandsForAllPlayers(false);
      this.teardownPublishedState();
      return true;
   }

   @Override
   public void stopServer() {
      this.teardownPublishedState();
      super.stopServer();
   }

   @Override
   public void halt(final boolean wait) {
      // The normal stop path has already entered MinecraftServer.stopServer().
      // Do not create and synchronously observe a new server task from the
      // owner's disconnect callback: browser workers cannot complete that
      // future while the current shutdown stack is still running.
      if (!this.isStopped()) {
         this.executeBlocking(() -> {
            for (ServerPlayer player : Lists.newArrayList(this.getPlayerList().getPlayers())) {
               if (!player.getUUID().equals(this.uuid)) {
                  this.getPlayerList().remove(player);
               }
            }
         });
      }
      super.halt(wait);
      this.stopLanPinger();
   }

   @Override
   public boolean isPublished() {
      return this.multiplayerScope != MinecraftServer.MultiplayerScope.OFF;
   }

   @Override
   public int getPort() {
      return this.publishedPort;
   }

   @Override
   public LevelBasedPermissionSet operatorUserPermissions() {
      return LevelBasedPermissionSet.GAMEMASTER;
   }

   public LevelBasedPermissionSet getFunctionCompilationPermissions() {
      return LevelBasedPermissionSet.GAMEMASTER;
   }

   public void setUUID(final UUID uuid) {
      this.uuid = uuid;
   }

   @Override
   public boolean isSingleplayerOwner(final NameAndId nameAndId) {
      return this.getSingleplayerProfile() != null && nameAndId.name().equalsIgnoreCase(this.getSingleplayerProfile().name());
   }

   @Override
   public int getScaledTrackingDistance(final int baseRange) {
      return (int)(this.host.getEntityDistanceScaling() * baseRange);
   }

   @Override
   public boolean forceSynchronousWrites() {
      return this.host.forceSynchronousWrites();
   }

   @Override
   public @Nullable GameType getForcedGameType() {
      return this.isPublished() && !this.isHardcore() ? this.getGameTypeForOtherPlayers() : null;
   }

   @Override
   protected GlobalPos selectLevelLoadFocusPos() {
      UUID lastSinglePlayerOwnerUUID = this.worldData.getSinglePlayerUUID();
      if (lastSinglePlayerOwnerUUID == null) {
         return super.selectLevelLoadFocusPos();
      }

      Optional<CompoundTag> playerData = this.playerDataStorage.load(new NameAndId(lastSinglePlayerOwnerUUID, "<single player owner>"));
      if (playerData.isEmpty()) {
         return super.selectLevelLoadFocusPos();
      }

      try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
         ValueInput input = TagValueInput.create(reporter, this.registryAccess(), playerData.get());
         ServerPlayer.SavedPosition loadedPosition = input.<ServerPlayer.SavedPosition>read(ServerPlayer.SavedPosition.MAP_CODEC)
            .orElse(ServerPlayer.SavedPosition.EMPTY);
         if (loadedPosition.dimension().isPresent() && loadedPosition.position().isPresent()) {
            return new GlobalPos(loadedPosition.dimension().get(), BlockPos.containing(loadedPosition.position().get()));
         }
      }

      return super.selectLevelLoadFocusPos();
   }

   @Override
   public void sendLowDiskSpaceWarning() {
      super.sendLowDiskSpaceWarning();
      this.host.sendLowDiskSpaceWarning();
   }

   @Override
   public void reportChunkLoadFailure(final Throwable throwable, final RegionStorageInfo storageInfo, final ChunkPos pos) {
      super.reportChunkLoadFailure(throwable, storageInfo, pos);
      this.warnOnLowDiskSpace();
      this.host.onChunkLoadFailure(pos);
   }

   @Override
   public void reportChunkSaveFailure(final Throwable throwable, final RegionStorageInfo storageInfo, final ChunkPos pos) {
      super.reportChunkSaveFailure(throwable, storageInfo, pos);
      this.warnOnLowDiskSpace();
      this.host.onChunkSaveFailure(pos);
   }

   @Override
   public int getMaxPlayers() {
      return 8;
   }

   public Collection<SimpleGizmoCollector.GizmoInstance> getPerTickGizmos() {
      return this.latestTicksGizmos;
   }

   private void setMultiplayerScope(final MinecraftServer.MultiplayerScope multiplayerScope) {
      if (this.multiplayerScope != multiplayerScope) {
         this.multiplayerScope = multiplayerScope;
      }
   }

   public MinecraftServer.MultiplayerScope getMultiplayerScope() {
      return this.multiplayerScope;
   }
}
