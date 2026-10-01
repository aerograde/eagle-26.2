package net.minecraft.network.protocol.login;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;

public record ClientboundLoginFinishedPacket(GameProfile gameProfile, UUID sessionId) implements Packet<ClientLoginPacketListener> {
   public static final StreamCodec<ByteBuf, ClientboundLoginFinishedPacket> STREAM_CODEC = StreamCodec.of(
      (output, packet) -> {
         ByteBufCodecs.GAME_PROFILE.encode(output, packet.gameProfile());
         UUIDUtil.STREAM_CODEC.encode(output, packet.sessionId());
      },
      input -> {
         GameProfile profile = ByteBufCodecs.GAME_PROFILE.decode(input);
         // Some Velocity/ViaVersion networks advertise the current protocol but
         // intentionally retain the older login-success payload so 1.8-era
         // clients can share the same backend. The new session UUID is not used
         // by the client login path, so accept its complete absence while still
         // rejecting a partially truncated UUID.
         UUID sessionId = input.readableBytes() == 0 ? profile.id() : UUIDUtil.STREAM_CODEC.decode(input);
         return new ClientboundLoginFinishedPacket(profile, sessionId);
      }
   );

   @Override
   public PacketType<ClientboundLoginFinishedPacket> type() {
      return LoginPacketTypes.CLIENTBOUND_LOGIN_FINISHED;
   }

   public void handle(final ClientLoginPacketListener listener) {
      listener.handleLoginFinished(this);
   }

   @Override
   public boolean isTerminal() {
      return true;
   }
}
