package net.minecraft.server.network;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.profiling.Profiler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class ServerCommonPacketListenerImpl implements ServerCommonPacketListener {
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final int LATENCY_CHECK_INTERVAL = 15000;
   private static final int CLOSED_LISTENER_TIMEOUT = 15000;
   private static final Component TIMEOUT_DISCONNECTION_MESSAGE = Component.translatable("disconnect.timeout");
   static final Component DISCONNECT_UNEXPECTED_QUERY = Component.translatable("multiplayer.disconnect.unexpected_query_response");
   protected final MinecraftServer server;
   protected final Connection connection;
   private final boolean transferred;
   private long keepAliveTime;
   private boolean keepAlivePending;
   private long keepAliveChallenge;
   private long closedListenerTime;
   private boolean closed = false;
   private int latency;
   private volatile boolean suspendFlushingOnServerThread = false;

   public ServerCommonPacketListenerImpl(final MinecraftServer server, final Connection connection, final CommonListenerCookie cookie) {
      this.server = server;
      this.connection = connection;
      this.keepAliveTime = Util.getMillis();
      this.latency = cookie.latency();
      this.transferred = cookie.transferred();
   }

   private void close() {
      if (!this.closed) {
         this.closedListenerTime = Util.getMillis();
         this.closed = true;
      }
   }

   @Override
   public void onDisconnect(final DisconnectionDetails details) {
      if (this.isSingleplayerOwner()) {
         LOGGER.info("Stopping singleplayer server as player logged out");
         this.server.halt(false);
      }
   }

   @Override
   public void onPacketError(final Packet packet, final Exception e) throws ReportedException {
      ServerCommonPacketListener.super.onPacketError(packet, e);
      this.server.reportPacketHandlingException(e, packet.type());
   }

   @Override
   public void handleKeepAlive(final ServerboundKeepAlivePacket packet) {
      if (this.keepAlivePending && packet.getId() == this.keepAliveChallenge) {
         int time = (int)(Util.getMillis() - this.keepAliveTime);
         this.latency = (this.latency * 3 + time) / 4;
         this.keepAlivePending = false;
      } else if (!this.isSingleplayerOwner()) {
         this.disconnect(TIMEOUT_DISCONNECTION_MESSAGE);
      }
   }

   @Override
   public void handlePong(final ServerboundPongPacket serverboundPongPacket) {
   }

   @Override
   public void handleCustomPayload(final ServerboundCustomPayloadPacket packet) {
   }

   @Override
   public void handleCustomClickAction(final ServerboundCustomClickActionPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
      this.server.handleCustomClickAction(packet.id(), packet.payload());
   }

   @Override
   public void handleResourcePackResponse(final ServerboundResourcePackPacket packet) {
      PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
      if (packet.action() == ServerboundResourcePackPacket.Action.DECLINED && this.server.isResourcePackRequired()) {
         LOGGER.info("Disconnecting {} due to resource pack {} rejection", this.playerProfile().name(), packet.id());
         this.disconnect(Component.translatable("multiplayer.requiredTexturePrompt.disconnect"));
      }
   }

   @Override
   public void handleCookieResponse(final ServerboundCookieResponsePacket packet) {
      this.disconnect(DISCONNECT_UNEXPECTED_QUERY);
   }

   protected void keepConnectionAlive() {
      Profiler.get().push("keepAlive");
      long now = Util.getMillis();
      // Eagler/web: a local in-process memory connection has no network to keep alive or time
      // out on. On single-thread TeaVM the server can spend >30s preparing a normal-world spawn
      // area, which starves the keep-alive/ack loop and would disconnect the local player with
      // "Timed out" (isSingleplayerOwner can be false during the config phase, so its guard alone
      // doesn't protect us). A relayed LAN guest has the same browser scheduling boundary: its
      // WebRTC close/error event is authoritative, while a frozen host page must not look like a
      // dead guest. Desktop-safe: real TCP LAN players still use keep-alive.
      if (!this.isSingleplayerOwner() && !this.connection.isMemoryConnection()
         && !this.connection.isRelayedLANConnection() && now - this.keepAliveTime >= 15000L) {
         if (this.keepAlivePending) {
            this.disconnect(TIMEOUT_DISCONNECTION_MESSAGE);
         } else if (this.checkIfClosed(now)) {
            this.keepAlivePending = true;
            this.keepAliveTime = now;
            this.keepAliveChallenge = now;
            this.send(new ClientboundKeepAlivePacket(this.keepAliveChallenge));
         }
      }

      Profiler.get().pop();
   }

   private boolean checkIfClosed(final long now) {
      if (this.closed) {
         if (now - this.closedListenerTime >= 15000L) {
            this.disconnect(TIMEOUT_DISCONNECTION_MESSAGE);
         }

         return false;
      } else {
         return true;
      }
   }

   public void suspendFlushing() {
      this.suspendFlushingOnServerThread = true;
   }

   public void resumeFlushing() {
      this.suspendFlushingOnServerThread = false;
      this.connection.flushChannel();
   }

   public void send(final Packet<?> packet) {
      this.send(packet, null);
   }

   public void send(final Packet<?> packet, final @Nullable ChannelFutureListener listener) {
      if (packet.isTerminal()) {
         this.close();
      }

      boolean flush = !this.suspendFlushingOnServerThread || !this.server.isSameThread();

      try {
         this.connection.send(packet, listener, flush);
      } catch (Throwable t) {
         CrashReport report = CrashReport.forThrowable(t, "Sending packet");
         CrashReportCategory category = report.addCategory("Packet being sent");
         category.setDetail("Packet class", () -> packet.getClass().getCanonicalName());
         throw new ReportedException(report);
      }
   }

   public void disconnect(final Component reason) {
      this.disconnect(new DisconnectionDetails(reason));
   }

   public void disconnect(final DisconnectionDetails details) {
      this.connection.send(new ClientboundDisconnectPacket(details.reason()), PacketSendListener.thenRun(() -> this.connection.disconnect(details)));
      this.connection.setReadOnly();
      // stopServer() marks the server stopped before PlayerList.removeAll(). In
      // the browser worker there is no independent JVM thread that can complete
      // executeBlocking(...).join(), so process shutdown disconnection inline.
      // A LAN unpublish is a second browser-only case: its IPC callback runs on
      // the worker supervisor green thread, not the synthetic server green
      // thread. Queue handleDisconnection without observing its future; the
      // server tick (or the later stopped-state removeAll pass) will run it.
      if (this.server.isStopped()) {
         this.connection.handleDisconnection();
      } else if (!this.server.isSameThread()
         && net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
            != net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP) {
         this.server.execute(this.connection::handleDisconnection);
      } else {
         this.server.executeBlocking(this.connection::handleDisconnection);
      }
   }

   protected boolean isSingleplayerOwner() {
      return this.server.isSingleplayerOwner(new NameAndId(this.playerProfile()));
   }

   protected abstract GameProfile playerProfile();

   @VisibleForDebug
   public GameProfile getOwner() {
      return this.playerProfile();
   }

   public int latency() {
      return this.latency;
   }

   protected CommonListenerCookie createCookie(final ClientInformation clientInformation) {
      return new CommonListenerCookie(this.playerProfile(), this.latency, clientInformation, this.transferred);
   }
}
