package net.minecraft.client.multiplayer;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.ForcedUsernameChangeException;
import com.mojang.authlib.exceptions.InsufficientPrivilegesException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.UserBannedException;
import com.mojang.logging.LogUtils;
import java.math.BigInteger;
import java.security.PublicKey;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginCompressionPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.ServerLinks;
import net.minecraft.util.Crypt;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlags;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ClientHandshakePacketListenerImpl implements ClientLoginPacketListener {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final Minecraft minecraft;
   private final @Nullable ServerData serverData;
   private final @Nullable Screen parent;
   private final Consumer<Component> updateStatus;
   private final Connection connection;
   private final boolean newWorld;
   private final @Nullable Duration worldLoadDuration;
   private @Nullable String minigameName;
   private final LevelLoadTracker levelLoadTracker;
   private final Map<Identifier, byte[]> cookies;
   private final boolean wasTransferredTo;
   private final Map<UUID, PlayerInfo> seenPlayers;
   private final boolean seenInsecureChatWarning;
   private final @Nullable BooleanSupplier missingSessionRetry;
   private final @Nullable GameProfile wispcraftProfile;
   private boolean missingSessionRetryStarted;
   private final AtomicReference<ClientHandshakePacketListenerImpl.State> state = new AtomicReference<>(ClientHandshakePacketListenerImpl.State.CONNECTING);

   public ClientHandshakePacketListenerImpl(
      final Connection connection,
      final Minecraft minecraft,
      final @Nullable ServerData serverData,
      final @Nullable Screen parent,
      final boolean newWorld,
      final @Nullable Duration worldLoadDuration,
      final Consumer<Component> updateStatus,
      final LevelLoadTracker levelLoadTracker,
      final @Nullable TransferState transferState
   ) {
      this(connection, minecraft, serverData, parent, newWorld, worldLoadDuration, updateStatus,
         levelLoadTracker, transferState, null);
   }

   public ClientHandshakePacketListenerImpl(
      final Connection connection,
      final Minecraft minecraft,
      final @Nullable ServerData serverData,
      final @Nullable Screen parent,
      final boolean newWorld,
      final @Nullable Duration worldLoadDuration,
      final Consumer<Component> updateStatus,
      final LevelLoadTracker levelLoadTracker,
      final @Nullable TransferState transferState,
      final @Nullable BooleanSupplier missingSessionRetry
   ) {
      this(connection, minecraft, serverData, parent, newWorld, worldLoadDuration, updateStatus,
         levelLoadTracker, transferState, missingSessionRetry, null);
   }

   public ClientHandshakePacketListenerImpl(
      final Connection connection,
      final Minecraft minecraft,
      final @Nullable ServerData serverData,
      final @Nullable Screen parent,
      final boolean newWorld,
      final @Nullable Duration worldLoadDuration,
      final Consumer<Component> updateStatus,
      final LevelLoadTracker levelLoadTracker,
      final @Nullable TransferState transferState,
      final @Nullable BooleanSupplier missingSessionRetry,
      final @Nullable GameProfile wispcraftProfile
   ) {
      this.wispcraftProfile = wispcraftProfile;
      this.connection = connection;
      this.minecraft = minecraft;
      this.serverData = serverData;
      this.parent = parent;
      this.updateStatus = updateStatus;
      this.newWorld = newWorld;
      this.worldLoadDuration = worldLoadDuration;
      this.levelLoadTracker = levelLoadTracker;
      this.cookies = transferState != null ? new HashMap<>(transferState.cookies()) : new HashMap<>();
      this.seenPlayers = transferState != null ? transferState.seenPlayers() : Map.of();
      this.seenInsecureChatWarning = transferState != null ? transferState.seenInsecureChatWarning() : false;
      this.wasTransferredTo = transferState != null;
      this.missingSessionRetry = missingSessionRetry;
   }

   private void switchState(final ClientHandshakePacketListenerImpl.State toState) {
      ClientHandshakePacketListenerImpl.State newState = this.state.updateAndGet(lastState -> {
         if (!toState.fromStates.contains(lastState)) {
            throw new IllegalStateException("Tried to switch to " + toState + " from " + lastState + ", but expected one of " + toState.fromStates);
         } else {
            return toState;
         }
      });
      this.updateStatus.accept(newState.message);
   }

   @Override
   public void handleHello(final ClientboundHelloPacket packet) {
      this.switchState(ClientHandshakePacketListenerImpl.State.AUTHORIZING);

      if (packet.shouldAuthenticate() && this.wispcraftProfile == null) {
         String accessToken = this.minecraft.getUser().getAccessToken();
         if ((accessToken == null || accessToken.isBlank() || "0".equals(accessToken))
            && this.missingSessionRetry != null && this.missingSessionRetry.getAsBoolean()) {
            this.missingSessionRetryStarted = true;
            return;
         }
      }

      Cipher decryptCipher;
      Cipher encryptCipher;
      String digest;
      ServerboundKeyPacket setKeyPacket;
      try {
         SecretKey secretKey = Crypt.generateSecretKey();
         PublicKey publicKey = packet.getPublicKey();
         digest = new BigInteger(Crypt.digestData(packet.getServerId(), publicKey, secretKey)).toString(16);
         decryptCipher = Crypt.getCipher(2, secretKey);
         encryptCipher = Crypt.getCipher(1, secretKey);
         byte[] challenge = packet.getChallenge();
         setKeyPacket = new ServerboundKeyPacket(secretKey, publicKey, challenge);
      } catch (Exception e) {
         throw new IllegalStateException("Protocol error", e);
      }

      if (packet.shouldAuthenticate()) {
         Util.ioPool().execute(() -> {
            Component error = this.authenticateServer(digest);
            if (error != null) {
               if (this.serverData == null || !this.serverData.isLan()) {
                  this.connection.disconnect(error);
                  return;
               }

               LOGGER.warn(error.getString());
            }

            this.setEncryption(setKeyPacket, decryptCipher, encryptCipher);
         });
      } else {
         this.setEncryption(setKeyPacket, decryptCipher, encryptCipher);
      }
   }

   private void setEncryption(final ServerboundKeyPacket setKeyPacket, final Cipher decryptCipher, final Cipher encryptCipher) {
      // Authentication may finish after the user cancels the connection.
      if (!this.connection.isConnected()) return;
      this.switchState(ClientHandshakePacketListenerImpl.State.ENCRYPTING);
      this.connection.send(setKeyPacket, PacketSendListener.thenRun(() -> this.connection.setEncryptionKey(decryptCipher, encryptCipher)));
   }

   private @Nullable Component authenticateServer(final String digest) {
      if (this.wispcraftProfile != null) {
         String error = PlatformNetworking.joinWispcraftServer(this.wispcraftProfile.id().toString(), digest);
         return error == null ? null : Component.translatable("disconnect.loginFailedInfo", Component.literal(error));
      }
      String accessToken = this.minecraft.getUser().getAccessToken();
      if (accessToken == null || accessToken.isBlank() || "0".equals(accessToken)) {
         return Component.translatable(
            "disconnect.loginFailedInfo",
            Component.translatableWithFallback("disconnect.loginFailed.microsoftAccount",
               "This server requires a Microsoft-authenticated Minecraft account")
         );
      }

      try {
         this.minecraft.services().sessionService().joinServer(this.minecraft.getUser().getProfileId(), accessToken, digest);
         return null;
      } catch (AuthenticationUnavailableException ignored) {
         return Component.translatable("disconnect.loginFailedInfo", Component.translatable("disconnect.loginFailedInfo.serversUnavailable"));
      } catch (InvalidCredentialsException ignored) {
         return Component.translatable("disconnect.loginFailedInfo", Component.translatable("disconnect.loginFailedInfo.invalidSession"));
      } catch (InsufficientPrivilegesException ignored) {
         return Component.translatable("disconnect.loginFailedInfo", Component.translatable("disconnect.loginFailedInfo.insufficientPrivileges"));
      } catch (UserBannedException | ForcedUsernameChangeException ignored) {
         return Component.translatable("disconnect.loginFailedInfo", Component.translatable("disconnect.loginFailedInfo.userBanned"));
      } catch (AuthenticationException e) {
         String message = e.getMessage();
         return Component.translatable(
            "disconnect.loginFailedInfo",
            message == null || message.isBlank() ? Component.translatable("disconnect.loginFailedInfo.invalidSession") : Component.literal(message)
         );
      }
   }

   @Override
   public void handleLoginFinished(final ClientboundLoginFinishedPacket packet) {
      this.switchState(ClientHandshakePacketListenerImpl.State.JOINING);
      GameProfile localGameProfile = packet.gameProfile();
      this.connection
         .setupInboundProtocol(
            ConfigurationProtocols.CLIENTBOUND,
            new ClientConfigurationPacketListenerImpl(
               this.minecraft,
               this.connection,
               new CommonListenerCookie(
                  this.levelLoadTracker,
                  localGameProfile,
                  ClientRegistryLayer.createRegistryAccess().compositeAccess(),
                  FeatureFlags.DEFAULT_FLAGS,
                  null,
                  this.serverData,
                  this.parent,
                  this.cookies,
                  null,
                  Map.of(),
                  ServerLinks.EMPTY,
                  this.seenPlayers,
                  false
               )
            )
         );
      this.connection.send(ServerboundLoginAcknowledgedPacket.INSTANCE);
      this.connection.setupOutboundProtocol(ConfigurationProtocols.SERVERBOUND);
      this.connection.send(new ServerboundCustomPayloadPacket(new BrandPayload(ClientBrandRetriever.getClientModName())));
      this.connection.send(new ServerboundClientInformationPacket(this.minecraft.options.buildPlayerInformation()));
   }

   public static void completeEaglerXLogin(
      final Connection connection,
      final Minecraft minecraft,
      final @Nullable ServerData serverData,
      final @Nullable Screen parent,
      final GameProfile localGameProfile
   ) {
      connection.setupInboundProtocol(
         ConfigurationProtocols.CLIENTBOUND,
         new ClientConfigurationPacketListenerImpl(
            minecraft,
            connection,
            new CommonListenerCookie(
               new LevelLoadTracker(),
               localGameProfile,
               ClientRegistryLayer.createRegistryAccess().compositeAccess(),
               FeatureFlags.DEFAULT_FLAGS,
               null,
               serverData,
               parent,
               new HashMap<>(),
               null,
               Map.of(),
               ServerLinks.EMPTY,
               Map.of(),
               false
            )
         )
      );
      connection.setupOutboundProtocolAfterEaglerHandshake(ConfigurationProtocols.SERVERBOUND);
      connection.send(new ServerboundCustomPayloadPacket(new BrandPayload(ClientBrandRetriever.getClientModName())));
      connection.send(new ServerboundClientInformationPacket(minecraft.options.buildPlayerInformation()));
   }

   @Override
   public void onDisconnect(final DisconnectionDetails details) {
      if (this.missingSessionRetryStarted) {
         return;
      }
      Component title = this.wasTransferredTo ? CommonComponents.TRANSFER_CONNECT_FAILED : CommonComponents.CONNECT_FAILED;
      if (this.serverData != null && this.serverData.isRealm()) {
         this.minecraft.gui.setScreen(new DisconnectedScreen(this.parent, title, details.reason(), CommonComponents.GUI_BACK));
      } else {
         this.minecraft.gui.setScreen(new DisconnectedScreen(this.parent, title, details));
      }
   }

   @Override
   public boolean isAcceptingMessages() {
      return this.connection.isConnected();
   }

   @Override
   public void handleDisconnect(final ClientboundLoginDisconnectPacket packet) {
      this.connection.disconnect(packet.reason());
   }

   @Override
   public void handleCompression(final ClientboundLoginCompressionPacket packet) {
      if (!this.connection.isMemoryConnection()) {
         this.connection.setupCompression(packet.getCompressionThreshold(), false);
      }
   }

   @Override
   public void handleCustomQuery(final ClientboundCustomQueryPacket packet) {
      this.updateStatus.accept(Component.translatable("connect.negotiating"));
      this.connection.send(new ServerboundCustomQueryAnswerPacket(packet.transactionId(), null));
   }

   public void setMinigameName(final @Nullable String minigameName) {
      this.minigameName = minigameName;
   }

   @Override
   public void handleRequestCookie(final ClientboundCookieRequestPacket packet) {
      this.connection.send(new ServerboundCookieResponsePacket(packet.key(), this.cookies.get(packet.key())));
   }

   @Override
   public void fillListenerSpecificCrashDetails(final CrashReport report, final CrashReportCategory connectionDetails) {
      connectionDetails.setDetail("Server type", () -> this.serverData != null ? this.serverData.type().toString() : "<unknown>");
      connectionDetails.setDetail("Login phase", () -> this.state.get().toString());
      connectionDetails.setDetail("Is Local", () -> String.valueOf(this.connection.isMemoryConnection()));
   }

   private enum State {
      CONNECTING(Component.translatable("connect.connecting"), Set.of()),
      AUTHORIZING(Component.translatable("connect.authorizing"), Set.of(CONNECTING)),
      ENCRYPTING(Component.translatable("connect.encrypting"), Set.of(AUTHORIZING)),
      JOINING(Component.translatable("connect.joining"), Set.of(ENCRYPTING, CONNECTING));

      private final Component message;
      private final Set<ClientHandshakePacketListenerImpl.State> fromStates;

      State(final Component message, final Set<ClientHandshakePacketListenerImpl.State> fromStates) {
         this.message = message;
         this.fromStates = fromStates;
      }
   }
}
