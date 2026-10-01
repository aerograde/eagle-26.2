package net.minecraft.network;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import org.slf4j.Logger;

public class PacketEncoder<T extends PacketListener> extends MessageToByteEncoder<Packet<T>> {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final ProtocolInfo<T> protocolInfo;

   public PacketEncoder(final ProtocolInfo<T> protocolInfo) {
      this.protocolInfo = protocolInfo;
   }

   // Eagler: TeaVM can't resolve the MessageToByteEncoder<Packet<T>> generic parameter
   // (io.netty TypeParameterMatcher needs generic-signature reflection), so the teavm-compat
   // matcher accepts everything. Check the type explicitly so UnconfiguredPipelineHandler
   // config tasks pass through instead of being (mis-)encoded as packets. No-op on desktop
   // (identical to what the real matcher computes).
   @Override
   public boolean acceptOutboundMessage(final Object msg) {
      return msg instanceof Packet;
   }

   protected void encode(final ChannelHandlerContext ctx, final Packet<T> packet, final ByteBuf output) throws Exception {
      PacketType<? extends Packet<? super T>> packetId = packet.type();

      try {
         this.protocolInfo.codec().encode(output, packet);
         int writtenBytes = output.readableBytes();
         if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
               Connection.PACKET_SENT_MARKER,
               "OUT: [{}:{}] {} -> {} bytes",
               new Object[]{this.protocolInfo.id().id(), packetId, packet.getClass().getName(), writtenBytes}
            );
         }

         JvmProfiler.INSTANCE.onPacketSent(this.protocolInfo.id(), packetId, ctx.channel().remoteAddress(), writtenBytes);
      } catch (Throwable t) {
         LOGGER.error("Error sending packet {}", packetId, t);
         if (packet.isSkippable()) {
            throw new SkipPacketEncoderException(t);
         }

         throw t;
      } finally {
         ProtocolSwapHandler.handleOutboundTerminalPacket(ctx, packet);
      }
   }
}
