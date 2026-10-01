package net.minecraft.client.gui.screens;

import com.mojang.logging.LogUtils;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFuture;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.client.GameNarrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.quickplay.QuickPlay;
import net.minecraft.client.quickplay.QuickPlayLog;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.util.Util;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.socket.WispcraftAccount;
import net.lax1dude.eaglercraft.v1_8.profile.EaglerProfile;
import net.lax1dude.eaglercraft.v1_8.socket.AddressResolver;
import net.lax1dude.eaglercraft.v1_8.socket.EaglerXHandshake;
import net.lax1dude.eaglercraft.v1_8.socket.WebSocketPacketBridge;
import net.lax1dude.eaglercraft.v1_8.sp.SingleplayerServerController26;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ConnectScreen extends Screen {
   private static final AtomicInteger UNIQUE_THREAD_ID = new AtomicInteger(0);
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final long NARRATION_DELAY_MS = 2000L;
   private static final int HYBRID_AUTH_RETRY_DELAY_MS = 3000;
   public static final Component ABORT_CONNECTION = Component.translatable("connect.aborted");
   public static final Component UNKNOWN_HOST_MESSAGE = Component.translatable("disconnect.genericReason", Component.translatable("disconnect.unknownHost"));
   private volatile @Nullable Connection connection;
   private @Nullable ChannelFuture channelFuture;
   private volatile boolean aborted;
   private final Screen parent;
   private Component status = Component.translatable("connect.connecting");
   private long lastNarration = -1L;
   private final Component connectFailedTitle;
   private boolean hybridAuthRetried;

   private ConnectScreen(final Screen parent, final Component connectFailedTitle) {
      super(GameNarrator.NO_TITLE);
      this.parent = parent;
      this.connectFailedTitle = connectFailedTitle;
   }

   public static void startConnecting(
      final Screen parent,
      final Minecraft minecraft,
      final ServerAddress hostAndPort,
      final ServerData data,
      final boolean isQuickPlay,
      final @Nullable TransferState transferState
   ) {
      if (minecraft.gui.screen() instanceof ConnectScreen) {
         LOGGER.error("Attempt to connect while already connecting");
      } else {
         Component connectFailedTitle;
         if (transferState != null) {
            connectFailedTitle = CommonComponents.TRANSFER_CONNECT_FAILED;
         } else if (isQuickPlay) {
            connectFailedTitle = QuickPlay.ERROR_TITLE;
         } else {
            connectFailedTitle = CommonComponents.CONNECT_FAILED;
         }

         ConnectScreen screen = new ConnectScreen(parent, connectFailedTitle);
         if (transferState != null) {
            screen.updateStatus(Component.translatable("connect.transferring"));
         }

         minecraft.disconnectWithProgressScreen(false);
         SingleplayerServerController26.prepareForRemoteMultiplayer();
         minecraft.prepareForMultiplayer();
         minecraft.quickPlayLog().setWorldData(QuickPlayLog.Type.MULTIPLAYER, data.ip, data.name);
         // The hosted runtime may begin the connector on a worker/task immediately.
         // Force the first connection frame to be presented before that happens;
         // otherwise the last frame from integrated-server shutdown ("Saving world")
         // can remain visible until the socket handshake finishes.
         minecraft.setScreenAndShow(screen);
         screen.connect(minecraft, hostAndPort, data, transferState);
      }
   }

   private void connect(final Minecraft minecraft, final ServerAddress hostAndPort, final ServerData server, final @Nullable TransferState transferState) {
      LOGGER.info("Connecting to {}, {}", hostAndPort.getHost(), hostAndPort.getPort());
      Thread thread = new Thread("Server Connector #" + UNIQUE_THREAD_ID.incrementAndGet()) {
         @Override
         public void run() {
            InetSocketAddress address = null;

            try {
               if (ConnectScreen.this.aborted) {
                  return;
               }

	               Connection pendingConnection;
	               EaglerXHandshake.Result eaglerProfile = null;
                  GameProfile wispcraftProfile = null;
	               String multiplayerName = EaglerProfile.getName();
	               if (multiplayerName == null || multiplayerName.isBlank()) {
	                  multiplayerName = minecraft.getUser().getName();
	               }
               boolean wispMode = server.connectionMode() == ServerData.ConnectionMode.WISP;
               boolean legacyLAN = AddressResolver.isLegacyLANJoinURI(server.ip);
               // Relay-free direct connect: the WebRTC data channel was already
               // established by exchanging offer/answer codes, so this virtual
               // URI must never be resolved as a relay or a Java address.
               boolean directConnectLAN = server.ip != null && server.ip.startsWith("eagler-direct:");
               if (wispMode && (EagRuntime.getPlatformType() == EnumPlatformType.DESKTOP || legacyLAN
                     || RelaySettings.wispcraft() == null)) {
                  throw new IllegalStateException("Wispcraft Wisp Server endpoint is unavailable");
               }
               if (EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP) {
	                  // The legacy LAN address is a virtual transport URI, not a
	                  // WebSocket URL. Resolving it as an ordinary address prefixes
	                  // "wss://", hides the room code from extractLANCode(), and
	                  // leaves the ordered LAN candidate list empty.
	                  String relayUri = (legacyLAN || directConnectLAN) ? server.ip : AddressResolver.resolveURI(server.ip);
                  ServerAddress targetAddress = (legacyLAN || directConnectLAN)
                     ? ServerAddress.parseString("localhost:25565")
                     : ServerAddress.parseString(
                        AddressResolver.extractTarget(server.ip, hostAndPort.getHost() + ":" + hostAndPort.getPort())
                     );
                  address = InetSocketAddress.createUnresolved(targetAddress.getHost(), targetAddress.getPort());
	                  boolean directEagler = server.connectionMode() == ServerData.ConnectionMode.EAGLERX;
	                  boolean directTCP = server.connectionMode() == ServerData.ConnectionMode.DIRECT_TCP;
                     if (!legacyLAN && !server.isLan() && !directEagler && !directTCP
	                           && wispMode) {
                        // Pin the selected account before login-start. The auth listener
                        // must authorize this same UUID instead of the startup offline User.
                        wispcraftProfile = WispcraftAccount.fromProfileJSON(PlatformNetworking.getWispcraftAccountProfile());
                        if (wispcraftProfile != null) multiplayerName = wispcraftProfile.name();
                        if (ConnectScreen.this.aborted) return;
                     }
                  WebSocketPacketBridge bridge;
                  if (directConnectLAN) {
                     // Attach to the already-open direct connect data channel.
                     bridge = WebSocketPacketBridge.connect(relayUri, 30000);
                  } else if (legacyLAN) {
	                     // These virtual URIs open the Eagler 1.8 WebRTC data-channel
	                     // client. Never wrap them as Java TCP targets on /minecraft.
	                     // Rebuild them from the current ordered LAN-capable profiles:
	                     // the join screen persists the code with the then-primary URI,
	                     // but that URI must not pin retries to one failed relay.
	                     List<String> candidates = RelaySettings.connectionCandidates(
	                        relayUri, targetAddress.getHost() + ":" + targetAddress.getPort(), server.connectionMode()
	                     );
	                     int attemptTimeout = Math.max(3000, 30000 / Math.max(1, candidates.size()));
	                     RuntimeException lastFailure = null;
	                     bridge = null;
	                     for (int i = 0; i < candidates.size() && !ConnectScreen.this.aborted; ++i) {
	                        String candidate = candidates.get(i);
	                        try {
	                           ConnectScreen.LOGGER.info("Connecting through LAN relay {}/{}: {}", i + 1,
	                              candidates.size(), AddressResolver.extractRelay(candidate));
	                           bridge = WebSocketPacketBridge.connect(candidate, attemptTimeout);
	                           break;
	                        } catch (RuntimeException failure) {
	                           lastFailure = failure;
	                           ConnectScreen.LOGGER.warn("LAN relay candidate {}/{} failed: {}", i + 1,
	                              candidates.size(), failure.getMessage());
	                        }
	                     }
	                     if (bridge == null) {
	                        if (lastFailure != null) {
	                           throw lastFailure;
	                        }
	                        throw new IllegalStateException("No LAN-capable relay endpoint is configured");
	                     }
	                  } else if (directTCP) {
	                     bridge = WebSocketPacketBridge.connectDirectTCP(
	                        targetAddress.getHost(), targetAddress.getPort(), 15000
	                     );
	                  } else if (directEagler) {
	                     bridge = WebSocketPacketBridge.connectEaglerX(relayUri, 15000, multiplayerName);
                  } else {
                     List<String> candidates = RelaySettings.connectionCandidates(
	                        relayUri, targetAddress.getHost() + ":" + targetAddress.getPort(), server.connectionMode()
	                     );
                     int attemptTimeout = Math.max(3000, 15000 / Math.max(1, candidates.size()));
                     RuntimeException lastFailure = null;
                     bridge = null;
                     for (int i = 0; i < candidates.size() && !ConnectScreen.this.aborted; ++i) {
                        String candidate = candidates.get(i);
                        try {
	                           ConnectScreen.LOGGER.info("Connecting through {} {}/{}: {}", wispMode ? "WISP" : "relay", i + 1, candidates.size(), AddressResolver.extractRelay(candidate));
                           bridge = WebSocketPacketBridge.connect(candidate, attemptTimeout);
                           break;
                        } catch (RuntimeException failure) {
                           lastFailure = failure;
	                           ConnectScreen.LOGGER.warn("{} candidate {}/{} failed: {}", wispMode ? "WISP" : "Relay", i + 1, candidates.size(), failure.getMessage());
                        }
                     }
                     if (bridge == null) {
                        if (lastFailure != null) {
                           throw lastFailure;
                        }
	                        throw new IllegalStateException(wispMode
	                           ? "No Wispcraft Wisp Server endpoint is configured"
	                           : "No relay endpoint is configured");
                     }
                  }
                  pendingConnection = bridge.getConnection();
                  eaglerProfile = bridge.getEaglerProfile();
               } else {
                  Optional<InetSocketAddress> resolvedAddress = ServerNameResolver.DEFAULT
                     .resolveAddress(hostAndPort)
                     .map(ResolvedServerAddress::asInetSocketAddress);
                  if (ConnectScreen.this.aborted) {
                     return;
                  }

                  if (resolvedAddress.isEmpty()) {
                     minecraft.execute(
                        () -> minecraft.gui
                           .setScreen(new DisconnectedScreen(ConnectScreen.this.parent, ConnectScreen.this.connectFailedTitle, ConnectScreen.UNKNOWN_HOST_MESSAGE))
                     );
                     return;
                  }

                  address = resolvedAddress.get();
                  pendingConnection = new Connection(PacketFlow.CLIENTBOUND);
                  pendingConnection.setBandwidthLogger(minecraft.getDebugOverlay().getBandwidthLogger());
                  synchronized (ConnectScreen.this) {
                     if (ConnectScreen.this.aborted) {
                        return;
                     }
                     ConnectScreen.this.channelFuture = Connection.connect(
                        address, EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()), pendingConnection
                     );
                  }
                  ConnectScreen.this.channelFuture.syncUninterruptibly();
               }

               synchronized (ConnectScreen.this) {
                  if (ConnectScreen.this.aborted) {
                     pendingConnection.disconnect(ConnectScreen.ABORT_CONNECTION);
                     return;
                  }

                  ConnectScreen.this.connection = pendingConnection;
                  minecraft.getDownloadedPackSource().configureForServerControl(pendingConnection, convertPackStatus(server.getResourcePackStatus()));
               }

               if (eaglerProfile != null) {
                  ConnectScreen.this.updateStatus(Component.translatable("connect.joining"));
                  ClientHandshakePacketListenerImpl.completeEaglerXLogin(
                     ConnectScreen.this.connection,
                     minecraft,
                     server,
                     ConnectScreen.this.parent,
                     new GameProfile(eaglerProfile.uuid(), eaglerProfile.username())
                  );
               } else {
                  ConnectScreen.this.connection
                     .initiateServerboundPlayConnection(
                        address.getHostName(),
                        address.getPort(),
                        LoginProtocols.SERVERBOUND,
                        LoginProtocols.CLIENTBOUND,
                        new ClientHandshakePacketListenerImpl(
                           ConnectScreen.this.connection,
                           minecraft,
                           server,
                           ConnectScreen.this.parent,
                           false,
                           null,
                           ConnectScreen.this::updateStatus,
                           new LevelLoadTracker(),
                           transferState,
                           EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP
                              && !server.isLan()
                              && (server.connectionMode() == ServerData.ConnectionMode.RELAY
	                                 || server.connectionMode() == ServerData.ConnectionMode.WISP
	                                 || server.connectionMode() == ServerData.ConnectionMode.DIRECT_TCP)
                              && transferState == null
                                 ? () -> ConnectScreen.this.retryHybridAuthentication(
                                    minecraft, hostAndPort, server, pendingConnection
                                 )
                                 : null,
                           wispcraftProfile
                        ),
                        transferState != null
                     );
	                  ConnectScreen.this.connection.send(
	                     new ServerboundHelloPacket(multiplayerName, wispcraftProfile != null
                           ? wispcraftProfile.id() : UUIDUtil.createOfflinePlayerUUID(multiplayerName))
	                  );
	               }
            } catch (Exception exception) {
               if (ConnectScreen.this.aborted) {
                  return;
               }

               Exception cause;
               if (exception.getCause() instanceof Exception originalCause) {
                  cause = originalCause;
               } else {
                  cause = exception;
               }

               ConnectScreen.LOGGER.error("Couldn't connect to server", exception);
               String causeMessage = cause.getMessage();
               if (causeMessage == null || causeMessage.isBlank()) {
                  causeMessage = cause.getClass().getSimpleName();
               }
               String message = address == null
                  ? causeMessage
                  : causeMessage.replace(address.getHostName() + ":" + address.getPort(), "").replace(address.toString(), "");
               minecraft.execute(
                  () -> minecraft.gui
                     .setScreen(
                        new DisconnectedScreen(
                           ConnectScreen.this.parent, ConnectScreen.this.connectFailedTitle, Component.translatable("disconnect.genericReason", message)
                        )
                     )
               );
            }
         }

         private static ServerPackManager.PackPromptStatus convertPackStatus(final ServerData.ServerPackStatus resourcePackStatus) {
            return switch (resourcePackStatus) {
               case ENABLED -> ServerPackManager.PackPromptStatus.ALLOWED;
               case DISABLED -> ServerPackManager.PackPromptStatus.DECLINED;
               case PROMPT -> ServerPackManager.PackPromptStatus.PENDING;
            };
         }
      };
      thread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
      thread.start();
   }

   private boolean retryHybridAuthentication(final Minecraft minecraft, final ServerAddress hostAndPort,
      final ServerData server, final Connection challengedConnection) {
      synchronized (this) {
         if (this.aborted || this.hybridAuthRetried || this.connection != challengedConnection) {
            return false;
         }
         this.hybridAuthRetried = true;
         this.connection = null;
      }

      LOGGER.info("Target requested authenticated login without a browser session; retrying raw login once");
      challengedConnection.disconnect(ABORT_CONNECTION);
      this.updateStatus(Component.translatable("connect.retryingOffline"));
      Thread retryThread = new Thread("Hybrid Auth Retry") {
         @Override
         public void run() {
            EagUtils.sleep(HYBRID_AUTH_RETRY_DELAY_MS);
            if (!ConnectScreen.this.aborted) {
               ConnectScreen.this.connect(minecraft, hostAndPort, server, null);
            }
         }
      };
      retryThread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
      retryThread.start();
      return true;
   }

   private void updateStatus(final Component status) {
      this.status = status;
   }

   @Override
   public void tick() {
      if (this.connection != null) {
         if (this.connection.isConnected()) {
            this.connection.tick();
         } else {
            this.connection.handleDisconnection();
         }
      }
   }

   @Override
   public boolean shouldCloseOnEsc() {
      return false;
   }

   @Override
   protected void init() {
      this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> {
         synchronized (this) {
            this.aborted = true;
            if (this.channelFuture != null) {
               this.channelFuture.cancel(true);
               this.channelFuture = null;
            }

            if (this.connection != null) {
               this.connection.disconnect(ABORT_CONNECTION);
            }
         }

         this.minecraft.gui.setScreen(this.parent);
      }).bounds(this.width / 2 - 100, this.height / 4 + 120 + 12, 200, 20).build());
   }

   @Override
   public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
      super.extractRenderState(graphics, mouseX, mouseY, a);
      long current = Util.getMillis();
      if (current - this.lastNarration > 2000L) {
         this.lastNarration = current;
         this.minecraft.getNarrator().saySystemNow(Component.translatable("narrator.joining"));
      }

      graphics.centeredText(this.font, this.status, this.width / 2, this.height / 2 - 50, -1);
   }
}
