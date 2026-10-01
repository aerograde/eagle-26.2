package net.minecraft.network;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.io.IOException;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.util.profiling.jfr.JvmProfiler;
import org.slf4j.Logger;

public class PacketDecoder<T extends PacketListener> extends ByteToMessageDecoder implements ProtocolSwapHandler {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final ProtocolInfo<T> protocolInfo;
   private final BrowserParticlePacketGate eaglerParticlePacketGate;

   public PacketDecoder(final ProtocolInfo<T> protocolInfo) {
      this.protocolInfo = protocolInfo;
      this.eaglerParticlePacketGate = new BrowserParticlePacketGate(protocolInfo);
   }

   protected void decode(final ChannelHandlerContext ctx, final ByteBuf input, final List<Object> out) throws Exception {
      int startIndex = input.readerIndex();
      int readableBytes = input.readableBytes();
      if (this.eaglerParticlePacketGate.isParticlePacket(input)
            && !this.eaglerParticlePacketGate.admit(
               net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis(), readableBytes)) {
         input.skipBytes(readableBytes);
         return;
      }

      Packet<? super T> packet;
      try {
         packet = this.protocolInfo.codec().decode(input);
      } catch (Exception e) {
         if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.isEnabled()) {
            LOGGER.warn(
               "[EagPacketDecode] protocol={} bytes={} consumed={} body={}",
               this.protocolInfo.id().id(), readableBytes,
               input.readerIndex() - startIndex, diagnosticHex(input, startIndex, readableBytes, 64)
            );
         }
         if (e instanceof SkipPacketException) {
            input.skipBytes(input.readableBytes());
         }

         throw e;
      }

      PacketType<? extends Packet<? super T>> packetId = packet.type();
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.isEnabled()) {
         net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.decodedPacket(
            packet.getClass().getSimpleName(), readableBytes);
      }
      JvmProfiler.INSTANCE.onPacketReceived(this.protocolInfo.id(), packetId, ctx.channel().remoteAddress(), readableBytes);
      if (input.readableBytes() > 0) {
         throw new IOException(
            "Packet "
               + this.protocolInfo.id().id()
               + "/"
               + packetId
               + " ("
               + packet.getClass().getSimpleName()
               + ") was larger than I expected, found "
               + input.readableBytes()
               + " bytes extra whilst reading packet "
               + packetId
         );
      }

      out.add(packet);
      if (LOGGER.isDebugEnabled()) {
         LOGGER.debug(
            Connection.PACKET_RECEIVED_MARKER,
            " IN: [{}:{}] {} -> {} bytes",
            new Object[]{this.protocolInfo.id().id(), packetId, packet.getClass().getName(), readableBytes}
         );
      }

      ProtocolSwapHandler.handleInboundTerminalPacket(ctx, packet);
   }

   private static String diagnosticHex(final ByteBuf input, final int start, final int length, final int edgeBytes) {
      int end = start + length;
      int headEnd = Math.min(end, start + edgeBytes);
      int tailStart = Math.max(headEnd, end - edgeBytes);
      StringBuilder result = new StringBuilder((headEnd - start + end - tailStart) * 2 + 8);
      appendHex(result, input, start, headEnd);
      if (tailStart > headEnd) {
         result.append("...");
      }
      appendHex(result, input, tailStart, end);
      return result.toString();
   }

   private static void appendHex(final StringBuilder result, final ByteBuf input, final int start, final int end) {
      final char[] digits = "0123456789abcdef".toCharArray();
      for (int i = start; i < end; ++i) {
         int value = input.getUnsignedByte(i);
         result.append(digits[value >>> 4]).append(digits[value & 15]);
      }
   }
}
