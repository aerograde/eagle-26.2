package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;

/**
 * Browser-only admission gate for pathological streams of clientbound particle
 * packets. The normal particle limiter runs after packet decoding; large cosmetic
 * streams can therefore consume the frame on decoding and dispatch alone even
 * when every particle object is later rejected. This gate sheds only the excess
 * particle packet frames, before registry decoding or handler dispatch. All
 * gameplay packets and the desktop runtime remain byte-for-byte on the vanilla
 * path.
 */
public final class BrowserParticlePacketGate {
   // A normal busy lobby stays around 20 particle packets/second.  Keep ample
   // headroom for legitimate effects, but stop cosmetic wing emitters before
   // they can enqueue hundreds of decode/dispatch/render jobs per frame on a
   // throttled browser.
   private static final int RATE_PER_SECOND = 96;
   private static final int BURST_CAPACITY = 32;
   private final boolean enabled;
   private final int particlePacketId;
   private long lastRefillMillis = -1L;
   private long tokensMillis = (long)BURST_CAPACITY * 1000L;

   public BrowserParticlePacketGate(final ProtocolInfo<?> protocolInfo) {
      this.enabled = EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP
         && protocolInfo.id() == ConnectionProtocol.PLAY
         && protocolInfo.flow() == PacketFlow.CLIENTBOUND;
      this.particlePacketId = this.enabled ? ParticlePacketIdHolder.ID : -1;
   }

   /** Returns true only when this complete packet body is a particle packet. */
   public boolean isParticlePacket(final ByteBuf input) {
      if (!this.enabled || !input.isReadable()) {
         return false;
      }
      int index = input.readerIndex();
      int end = input.writerIndex();
      int value = 0;
      int shift = 0;
      while (index < end && shift < 35) {
         int next = input.getUnsignedByte(index++);
         value |= (next & 0x7F) << shift;
         if ((next & 0x80) == 0) {
            return value == this.particlePacketId;
         }
         shift += 7;
      }
      return false;
   }

   /** Returns true when one particle packet may proceed to normal decoding. */
   public boolean admit(final long nowMillis, final int packetBytes) {
      if (!this.enabled) {
         return true;
      }
      if (this.lastRefillMillis < 0L || nowMillis < this.lastRefillMillis) {
         this.lastRefillMillis = nowMillis;
      } else {
         long elapsed = Math.min(1000L, nowMillis - this.lastRefillMillis);
         if (elapsed > 0L) {
            this.tokensMillis = Math.min((long)BURST_CAPACITY * 1000L,
               this.tokensMillis + elapsed * RATE_PER_SECOND);
            this.lastRefillMillis = nowMillis;
         }
      }
      if (this.tokensMillis >= 1000L) {
         this.tokensMillis -= 1000L;
         EaglerClientPerf.earlyParticlePacket(true, packetBytes);
         return true;
      }
      EaglerClientPerf.earlyParticlePacket(false, packetBytes);
      return false;
   }

   private static int findClientboundPlayParticleId() {
      final int[] result = new int[]{-1};
      GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((PacketType<?> type, int networkId) -> {
         if (type == GamePacketTypes.CLIENTBOUND_LEVEL_PARTICLES) {
            result[0] = networkId;
         }
      });
      return result[0];
   }

   private static final class ParticlePacketIdHolder {
      private static final int ID = findClientboundPlayParticleId();
   }
}
