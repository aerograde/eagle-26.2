package net.minecraft.client.multiplayer;

import com.google.common.collect.Lists;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import net.lax1dude.eaglercraft.v1_8.socket.AddressResolver;
import net.lax1dude.eaglercraft.v1_8.socket.EaglerXStatusQuery;
import net.lax1dude.eaglercraft.v1_8.socket.WebSocketPacketBridge;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import org.slf4j.Logger;

public class ServerStatusPinger {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Component CANT_CONNECT_MESSAGE = Component.translatable("multiplayer.status.cannot_connect").withColor(-65536);
   private static final ResolutionContext DESCRIPTION_SANITIZE_CONTEXT = ResolutionContext.builder()
      .withObjectInfoValidator(description -> !(description instanceof PlayerSprite))
      .setDepthLimit(16)
      .setDepthLimitBehavior(ResolutionContext.LimitBehavior.DISCARD_REMAINING)
      .build();
   private final List<Connection> connections = Collections.synchronizedList(Lists.newArrayList());
   private static final int MAX_ACTIVE_PINGS = 5;
   private final List<TrackedEaglerQuery> eaglerQueries = Collections.synchronizedList(Lists.newArrayList());
   private final List<Channel> legacyChannels = Collections.synchronizedList(Lists.newArrayList());
   private final Map<Connection, ServerPingAdmission.Ticket> connectionTickets = new IdentityHashMap<>();
   private final Object browserPingLifecycleLock = new Object();
   private final ServerPingAdmission pingAdmission = new ServerPingAdmission(MAX_ACTIVE_PINGS);

   public void pingServer(
      final ServerData data,
      final Runnable onPersistentDataChange,
      final Runnable onPongResponse,
      final Runnable onPingFailure,
      final EventLoopGroupHolder eventLoopGroupHolder,
      final ServerPingAdmission.Ticket ticket
   ) {
      synchronized (this.browserPingLifecycleLock) {
         if (!this.pingAdmission.isCurrent(ticket)) {
            ticket.close();
            return;
         }
      }
      final boolean browser = EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP;
      if (browser) {
         if (data.connectionMode() == ServerData.ConnectionMode.EAGLERX) {
            try {
               this.startEaglerQueryIfCurrent(data, onPersistentDataChange, onPongResponse, ticket);
            } catch (RuntimeException ex) {
               this.onPingFailedIfCurrent(
                  ticket, Component.literal(ex.getMessage() == null ? "Connection failed" : ex.getMessage()), data, onPingFailure
               );
               ticket.close();
            }
            return;
         }
         try {
            this.pingServerBlocking(data, onPersistentDataChange, onPongResponse, onPingFailure, eventLoopGroupHolder, true, ticket);
         } catch (UnknownHostException ex) {
            this.onPingFailedIfCurrent(ticket, ConnectScreen.UNKNOWN_HOST_MESSAGE, data, onPingFailure);
            ticket.close();
         } catch (RuntimeException ex) {
            if (this.onPingFailedIfCurrent(
               ticket,
               Component.literal(ex.getMessage() == null ? "Connection failed" : ex.getMessage()),
               data,
               onPingFailure
            )) {
               LOGGER.error("Failed to open browser status connection to {}", data.ip, ex);
            }
            ticket.close();
         }
         return;
      }
      try {
         this.pingServerBlocking(data, onPersistentDataChange, onPongResponse, onPingFailure, eventLoopGroupHolder, false, ticket);
      } catch (UnknownHostException ex) {
         this.onPingFailedIfCurrent(ticket, ConnectScreen.UNKNOWN_HOST_MESSAGE, data, onPingFailure);
         ticket.close();
      } catch (RuntimeException ex) {
         if (this.onPingFailedIfCurrent(
            ticket, Component.literal(ex.getMessage() == null ? "Connection failed" : ex.getMessage()), data, onPingFailure
         )) {
            LOGGER.error("Failed to open status connection to {}", data.ip, ex);
         }
         ticket.close();
      }
   }

   private void pingServerBlocking(
      final ServerData data,
      final Runnable onPersistentDataChange,
      final Runnable onPongResponse,
      final Runnable onPingFailure,
      final EventLoopGroupHolder eventLoopGroupHolder,
      final boolean browser,
      final ServerPingAdmission.Ticket ticket
   ) throws UnknownHostException {
      final ServerAddress rawAddress = ServerAddress.parseString(
         browser ? AddressResolver.extractTarget(data.ip, data.ip) : data.ip
      );
      Optional<InetSocketAddress> resolvedAddress = browser
         ? Optional.of(InetSocketAddress.createUnresolved(rawAddress.getHost(), rawAddress.getPort()))
         : ServerNameResolver.DEFAULT.resolveAddress(rawAddress).map(ResolvedServerAddress::asInetSocketAddress);
      if (resolvedAddress.isEmpty()) {
         this.onPingFailedIfCurrent(ticket, ConnectScreen.UNKNOWN_HOST_MESSAGE, data, onPingFailure);
         ticket.close();
      } else {
         final InetSocketAddress address = resolvedAddress.get();
         final Connection connection;
         if (browser) {
            WebSocketPacketBridge bridge;
            if (data.connectionMode() == ServerData.ConnectionMode.DIRECT_TCP) {
               bridge = WebSocketPacketBridge.connectDirectTCP(rawAddress.getHost(), rawAddress.getPort(), 10000);
            } else {
               List<String> candidates = RelaySettings.connectionCandidates(
                  AddressResolver.resolveURI(data.ip), rawAddress.getHost() + ":" + rawAddress.getPort()
               );
               int attemptTimeout = Math.max(2500, 10000 / Math.max(1, candidates.size()));
               RuntimeException lastFailure = null;
               bridge = null;
               for (String candidate : candidates) {
                  try {
                     bridge = WebSocketPacketBridge.connect(candidate, attemptTimeout);
                     break;
                  } catch (RuntimeException failure) {
                     lastFailure = failure;
                     LOGGER.debug("Status relay candidate failed: {}", candidate, failure);
                  }
               }
               if (bridge == null) {
                  if (lastFailure != null) {
                     throw lastFailure;
                  }
                  throw new IllegalStateException("No relay endpoint is configured");
               }
            }
            connection = bridge.getConnection();
         } else {
            connection = Connection.connectToServer(address, eventLoopGroupHolder, null);
         }
         final long connectionStarted = Util.getMillis();
         ClientStatusPacketListener listener = new ClientStatusPacketListener() {
            private boolean success;
            private boolean receivedPing;
            private boolean completionNotified;
            private long pingStart;
            private long fallbackPing;

            @Override
            public void handleStatusResponse(final ClientboundStatusResponsePacket packet) {
               synchronized (ServerStatusPinger.this.browserPingLifecycleLock) {
                  if (!ServerStatusPinger.this.isPingCurrent(ticket)) {
                     connection.disconnect(Component.translatable("multiplayer.status.cancelled"));
                     return;
                  }
                  if (this.receivedPing) {
                     connection.disconnect(Component.translatable("multiplayer.status.unrequested"));
                  } else {
                     this.receivedPing = true;
                     ServerStatus status = packet.status();
                     data.motd = sanitizeDescription(status.description());
                     status.version().ifPresentOrElse(version -> {
                        data.version = Component.literal(version.name());
                        data.protocol = version.protocol();
                     }, () -> {
                        data.version = Component.translatable("multiplayer.status.old");
                        data.protocol = 0;
                     });
                     status.players().ifPresentOrElse(players -> {
                        data.status = ServerStatusPinger.formatPlayerCount(players.online(), players.max());
                        data.players = players;
                        if (!players.sample().isEmpty()) {
                           List<Component> playerNames = new ArrayList<>(players.sample().size());

                           for (NameAndId profile : players.sample()) {
                              Component playerName;
                              if (profile.equals(MinecraftServer.ANONYMOUS_PLAYER_PROFILE)) {
                                 playerName = Component.translatable("multiplayer.status.anonymous_player");
                              } else {
                                 playerName = Component.literal(profile.name());
                              }

                              playerNames.add(playerName);
                           }

                           if (players.sample().size() < players.online()) {
                              playerNames.add(Component.translatable("multiplayer.status.and_more", players.online() - players.sample().size()));
                           }

                           data.playerList = playerNames;
                        } else {
                           data.playerList = List.of();
                        }
                     }, () -> data.status = Component.translatable("multiplayer.status.unknown").withStyle(ChatFormatting.DARK_GRAY));
                     status.favicon().ifPresent(newIcon -> {
                        if (!Arrays.equals(newIcon.iconBytes(), data.getIconBytes())) {
                           data.setIconBytes(ServerData.validateIcon(newIcon.iconBytes()));
                           onPersistentDataChange.run();
                        }
                     });
                     // The status response completes before the protocol's ping/pong.
                     // Do not mark the row successful yet or display relay connection
                     // and handshake time as if it were the measured network latency.
                     long responseAt = Util.getMillis();
                     this.fallbackPing = Math.max(1L, responseAt - connectionStarted);
                     this.pingStart = responseAt;
                     connection.send(new ServerboundPingRequestPacket(this.pingStart));
                     this.success = true;
                  }
               }
            }

            private static Component sanitizeDescription(final Component original) {
               try {
                  return ComponentUtils.resolve(ServerStatusPinger.DESCRIPTION_SANITIZE_CONTEXT, original);
               } catch (CommandSyntaxException e) {
                  ServerStatusPinger.LOGGER.warn("Failed to sanitize status {}", original, e);
                  return Component.empty();
               }
            }

            @Override
            public void handlePongResponse(final ClientboundPongResponsePacket packet) {
               synchronized (ServerStatusPinger.this.browserPingLifecycleLock) {
                  if (!ServerStatusPinger.this.isPingCurrent(ticket)) {
                     connection.disconnect(Component.translatable("multiplayer.status.cancelled"));
                     return;
                  }
                  long then = this.pingStart;
                  long now = Util.getMillis();
                  data.ping = now - then;
                  this.completionNotified = true;
                  connection.disconnect(Component.translatable("multiplayer.status.finished"));
                  onPongResponse.run();
               }
            }

            @Override
            public void onDisconnect(final DisconnectionDetails details) {
               synchronized (ServerStatusPinger.this.browserPingLifecycleLock) {
                  if (!ServerStatusPinger.this.isPingCurrent(ticket)) {
                     return;
                  }
                  if (!this.success) {
                     ServerStatusPinger.this.onPingFailed(details.reason(), data);
                     onPingFailure.run();
                     if (!browser) {
                        ServerStatusPinger.this.pingLegacyServer(
                           address, rawAddress, data, eventLoopGroupHolder, ticket
                        );
                     }
                  } else if (!this.completionNotified) {
                     // A number of valid Java proxies return the status JSON and then
                     // close without answering the optional ping packet. Keep the
                     // measured request/response latency instead of leaving gray bars.
                     data.ping = this.fallbackPing;
                     this.completionNotified = true;
                     onPongResponse.run();
                  }
               }
            }

            @Override
            public boolean isAcceptingMessages() {
               return connection.isConnected();
            }
         };

         try {
            synchronized (this.browserPingLifecycleLock) {
               if (!this.pingAdmission.isCurrent(ticket)) {
                  connection.disconnect(Component.translatable("multiplayer.status.cancelled"));
                  connection.releaseAfterDisconnect();
                  ticket.close();
                  return;
               }
               connection.initiateServerboundStatusConnection(rawAddress.getHost(), rawAddress.getPort(), listener);
               connection.send(ServerboundStatusRequestPacket.INSTANCE);
               data.motd = Component.translatable("multiplayer.status.pinging");
               data.playerList = Collections.emptyList();
               this.connections.add(connection);
               this.connectionTickets.put(connection, ticket);
            }
         } catch (Throwable t) {
            LOGGER.error("Failed to ping server {}", rawAddress, t);
            connection.disconnect(Component.translatable("multiplayer.status.cannot_connect"));
            connection.releaseAfterDisconnect();
            this.onPingFailedIfCurrent(
               ticket, Component.translatable("multiplayer.status.cannot_connect"), data, onPingFailure
            );
            ticket.close();
         }
      }
   }

   public ServerPingAdmission.Ticket tryPreparePing() {
      synchronized (this.browserPingLifecycleLock) {
         return this.pingAdmission.tryAcquire();
      }
   }

   private boolean isPingCurrent(final ServerPingAdmission.Ticket ticket) {
      return this.pingAdmission.isCurrent(ticket);
   }

   private void startEaglerQueryIfCurrent(
      final ServerData data,
      final Runnable onPersistentDataChange,
      final Runnable onPongResponse,
      final ServerPingAdmission.Ticket ticket
   ) {
      synchronized (this.browserPingLifecycleLock) {
         if (!this.pingAdmission.isCurrent(ticket)) {
            ticket.close();
            return;
         }
         this.eaglerQueries.add(
            new TrackedEaglerQuery(
               new EaglerXStatusQuery(AddressResolver.resolveURI(data.ip), data, onPongResponse, onPersistentDataChange), ticket
            )
         );
      }
   }

   private boolean onPingFailedIfCurrent(
      final ServerPingAdmission.Ticket ticket, final Component reason, final ServerData data, final Runnable onPingFailure
   ) {
      synchronized (this.browserPingLifecycleLock) {
         if (!this.pingAdmission.isCurrent(ticket)) {
            return false;
         }
         this.onPingFailed(reason, data);
         onPingFailure.run();
         return true;
      }
   }

   private void onPingFailed(final Component reason, final ServerData data) {
      LOGGER.error("Can't ping {}: {}", data.ip, reason.getString());
      data.motd = CANT_CONNECT_MESSAGE;
      data.status = CommonComponents.EMPTY;
   }

   private void pingLegacyServer(
      final InetSocketAddress resolvedAddress,
      final ServerAddress rawAddress,
      final ServerData data,
      final EventLoopGroupHolder eventLoopGroupHolder,
      final ServerPingAdmission.Ticket ticket
   ) {
      synchronized (this.browserPingLifecycleLock) {
         if (!this.pingAdmission.isCurrent(ticket)) {
            ticket.close();
            return;
         }
         ChannelFuture future = ((Bootstrap)((Bootstrap)((Bootstrap)new Bootstrap().group(eventLoopGroupHolder.eventLoopGroup())).handler(new ChannelInitializer<Channel>() {
            protected void initChannel(final Channel channel) {
               try {
                  channel.config().setOption(ChannelOption.TCP_NODELAY, true);
               } catch (ChannelException var3) {
               }

               channel.pipeline().addLast(new ChannelHandler[]{new LegacyServerPinger(rawAddress, (protocolVersion, gameVersion, motd, players, maxPlayers) -> {
                  synchronized (ServerStatusPinger.this.browserPingLifecycleLock) {
                     ServerStatusPinger.this.legacyChannels.remove(channel);
                     if (!ServerStatusPinger.this.isPingCurrent(ticket)) {
                        channel.close();
                        return;
                     }
                     data.setState(ServerData.State.INCOMPATIBLE);
                     data.version = Component.literal(gameVersion);
                     data.motd = Component.literal(motd);
                     data.status = ServerStatusPinger.formatPlayerCount(players, maxPlayers);
                     data.players = new ServerStatus.Players(maxPlayers, players, List.of());
                  }
               })});
            }
         })).channel(eventLoopGroupHolder.channelCls())).connect(resolvedAddress.getAddress(), resolvedAddress.getPort());
         Channel channel = future.channel();
         this.legacyChannels.add(channel);
         channel.closeFuture().addListener(ignored -> {
            synchronized (ServerStatusPinger.this.browserPingLifecycleLock) {
               ServerStatusPinger.this.legacyChannels.remove(channel);
            }
         });
      }
   }

   public static Component formatPlayerCount(final int curPlayers, final int maxPlayers) {
      Component current = Component.literal(Integer.toString(curPlayers)).withStyle(ChatFormatting.GRAY);
      Component max = Component.literal(Integer.toString(maxPlayers)).withStyle(ChatFormatting.GRAY);
      return Component.translatable("multiplayer.status.player_count", current, max).withStyle(ChatFormatting.DARK_GRAY);
   }

   public void tick() {
      synchronized (this.browserPingLifecycleLock) {
         synchronized (this.eaglerQueries) {
            this.eaglerQueries.removeIf(TrackedEaglerQuery::tick);
         }
         synchronized (this.connections) {
            Iterator<Connection> iterator = this.connections.iterator();

            while (iterator.hasNext()) {
               Connection connection = iterator.next();
               if (connection.isConnected()) {
                  connection.tick();
               } else {
                  iterator.remove();
                  connection.handleDisconnection();
                  this.releaseConnectionTicket(connection);
               }
            }
         }
         synchronized (this.legacyChannels) {
            this.legacyChannels.removeIf(channel -> !channel.isOpen());
         }
      }
   }

   public void removeAll() {
      final List<TrackedEaglerQuery> queriesToClose;
      final List<Connection> connectionsToClose;
      final List<Channel> legacyChannelsToClose;
      final List<ServerPingAdmission.Ticket> connectionTicketsToClose;
      synchronized (this.browserPingLifecycleLock) {
         this.pingAdmission.reset();
         synchronized (this.eaglerQueries) {
            queriesToClose = new ArrayList<>(this.eaglerQueries);
            this.eaglerQueries.clear();
         }
         synchronized (this.connections) {
            connectionsToClose = new ArrayList<>(this.connections);
            this.connections.clear();
            connectionTicketsToClose = new ArrayList<>(this.connectionTickets.values());
            this.connectionTickets.clear();
         }
         synchronized (this.legacyChannels) {
            legacyChannelsToClose = new ArrayList<>(this.legacyChannels);
            this.legacyChannels.clear();
         }
      }
      queriesToClose.forEach(TrackedEaglerQuery::close);
      for (Connection connection : connectionsToClose) {
         if (connection.isConnected()) {
            connection.disconnect(Component.translatable("multiplayer.status.cancelled"));
         }
         connection.releaseAfterDisconnect();
      }
      connectionTicketsToClose.forEach(ServerPingAdmission.Ticket::close);
      legacyChannelsToClose.forEach(Channel::close);
   }

   private void releaseConnectionTicket(final Connection connection) {
      ServerPingAdmission.Ticket ticket = this.connectionTickets.remove(connection);
      if (ticket != null) {
         ticket.close();
      }
   }

   private static final class TrackedEaglerQuery {
      private final EaglerXStatusQuery query;
      private final ServerPingAdmission.Ticket ticket;

      private TrackedEaglerQuery(final EaglerXStatusQuery query, final ServerPingAdmission.Ticket ticket) {
         this.query = query;
         this.ticket = ticket;
      }

      private boolean tick() {
         if (this.query.tick()) {
            this.ticket.close();
            return true;
         }
         return false;
      }

      private void close() {
         this.query.close();
         this.ticket.close();
      }
   }
}
