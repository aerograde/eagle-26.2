package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class CompressionDecoder extends ByteToMessageDecoder {
   public static final int MAXIMUM_COMPRESSED_LENGTH = 2097152;
   public static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8388608;
   private final Inflater inflater;
   private int threshold;
   private boolean validateDecompressed;
   private byte[] compressedBuffer = new byte[8192];
   private byte[] decompressedBuffer = new byte[8192];

   public CompressionDecoder(final int threshold, final boolean validateDecompressed) {
      this.threshold = threshold;
      this.validateDecompressed = validateDecompressed;
      this.inflater = new Inflater();
   }

   protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
      int uncompressedLength = VarInt.read(in);
      if (uncompressedLength == 0) {
         out.add(in.readBytes(in.readableBytes()));
      } else {
         if (this.validateDecompressed) {
            if (uncompressedLength < this.threshold) {
               throw new DecoderException("Badly compressed packet - size of " + uncompressedLength + " is below server threshold of " + this.threshold);
            }

            if (uncompressedLength > 8388608) {
               throw new DecoderException("Badly compressed packet - size of " + uncompressedLength + " is larger than protocol maximum of 8388608");
            }
         }

         this.setupInflaterInput(in);
         ByteBuf output = this.inflate(ctx, uncompressedLength);
         this.inflater.reset();
         out.add(output);
      }
   }

   private void setupInflaterInput(final ByteBuf in) {
      int readableBytes = in.readableBytes();
      if (this.compressedBuffer.length < readableBytes) {
         this.compressedBuffer = Arrays.copyOf(this.compressedBuffer, readableBytes);
      }

      in.readBytes(this.compressedBuffer, 0, readableBytes);
      this.inflater.setInput(this.compressedBuffer, 0, readableBytes);
   }

   private ByteBuf inflate(final ChannelHandlerContext ctx, final int uncompressedLength) throws DataFormatException {
      if (this.decompressedBuffer.length < uncompressedLength) {
         this.decompressedBuffer = Arrays.copyOf(this.decompressedBuffer, uncompressedLength);
      }

      ByteBuf output = ctx.alloc().buffer(uncompressedLength, uncompressedLength);

      try {
         int actualUncompressedLength = this.inflater.inflate(this.decompressedBuffer, 0, uncompressedLength);
         if (actualUncompressedLength != uncompressedLength) {
            throw new DecoderException(
               "Badly compressed packet - actual length of uncompressed payload "
                  + actualUncompressedLength
                  + " is does not match declared size "
                  + uncompressedLength
            );
         }

         output.writeBytes(this.decompressedBuffer, 0, actualUncompressedLength);
         return output;
      } catch (Exception e) {
         output.release();
         throw e;
      }
   }

   public void setThreshold(final int threshold, final boolean validateDecompressed) {
      this.threshold = threshold;
      this.validateDecompressed = validateDecompressed;
   }
}
