package net.minecraft.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;

public class PacketBundleUnpacker extends MessageToMessageEncoder<Packet<?>> {
   private final BundlerInfo bundlerInfo;

   public PacketBundleUnpacker(final BundlerInfo bundlerInfo) {
      this.bundlerInfo = bundlerInfo;
   }

   // Eagler: explicit type check — TeaVM can't resolve the <Packet<?>> generic (TypeParameterMatcher).
   @Override
   public boolean acceptOutboundMessage(final Object msg) {
      return msg instanceof Packet;
   }

   protected void encode(final ChannelHandlerContext ctx, final Packet<?> msg, final List<Object> out) throws Exception {
      this.bundlerInfo.unbundlePacket(msg, out::add);
      if (msg.isTerminal()) {
         ctx.pipeline().remove(ctx.name());
      }
   }
}
