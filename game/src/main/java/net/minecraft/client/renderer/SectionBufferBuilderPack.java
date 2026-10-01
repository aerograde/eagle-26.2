package net.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import java.util.Arrays;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.util.Util;

public class SectionBufferBuilderPack implements AutoCloseable {
   public static final int TOTAL_BUFFERS_SIZE = Arrays.stream(ChunkSectionLayer.values()).mapToInt(ChunkSectionLayer::bufferSize).sum();
   private final Map<ChunkSectionLayer, ByteBufferBuilder> buffers = Util.makeEnumMap(
      ChunkSectionLayer.class, layer -> new ByteBufferBuilder(layer.bufferSize())
   );

   public ByteBufferBuilder buffer(final ChunkSectionLayer layer) {
      ByteBufferBuilder buffer = this.buffers.get(layer);
      if (buffer == null) {
         buffer = new ByteBufferBuilder(layer.bufferSize());
         this.buffers.put(layer, buffer);
      }
      return buffer;
   }

   public void clearAll() {
      this.buffers.values().forEach(buffer -> {
         if (buffer != null) {
            buffer.clear();
         }
      });
   }

   public void discardAll() {
      this.buffers.values().forEach(buffer -> {
         if (buffer != null) {
            buffer.discard();
         }
      });
   }

   /**
    * Drop peak capacities accumulated while meshing the previous world. This is intentionally
    * used only after every section task has stopped; normal clear/discard keeps allocations
    * warm during gameplay.
    */
   public void eaglerTrimAfterWorld() {
      for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
         ByteBufferBuilder old = this.buffers.get(layer);
         if (old != null) {
            old.close();
         }
         this.buffers.put(layer, null);
      }
   }

   @Override
   public void close() {
      this.buffers.values().forEach(buffer -> {
         if (buffer != null) {
            buffer.close();
         }
      });
   }
}
