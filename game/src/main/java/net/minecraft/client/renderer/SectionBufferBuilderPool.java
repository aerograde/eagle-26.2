package net.minecraft.client.renderer;

import com.google.common.collect.Queues;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class SectionBufferBuilderPool implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final ArrayBlockingQueue<SectionBufferBuilderPack> freeBuffers;

   private SectionBufferBuilderPool(final List<SectionBufferBuilderPack> buffers) {
      this.freeBuffers = Queues.newArrayBlockingQueue(buffers.size());
      this.freeBuffers.addAll(buffers);
   }

   public static SectionBufferBuilderPool allocate(final int maxWorkers) {
      int maxBuffers = Math.max(1, (int)(Runtime.getRuntime().maxMemory() * 0.3) / SectionBufferBuilderPack.TOTAL_BUFFERS_SIZE);
      int targetBufferCount = Math.max(1, Math.min(maxWorkers, maxBuffers));
      if (EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP) {
         // Web: the mesh-worker pool does the real chunk compilation off-thread, so the main-thread
         // pool only needs a few packs for inline-fallback + upload staging. Un-clamped, targetBufferCount
         // tracks hardwareConcurrency (up to ~17 packs * 8.75MB ~= 148MB eagerly allocated in the GC heap)
         // with no benefit on web. Main-thread meshing is serial, so 4 packs are ample; raise if the
         // dispatcher stalls under a chunk-storm with mesh workers disabled (?nomesh).
         targetBufferCount = Math.min(targetBufferCount, 4);
      }
      List<SectionBufferBuilderPack> buffers = new ArrayList<>(targetBufferCount);

      try {
         for (int i = 0; i < targetBufferCount; i++) {
            buffers.add(new SectionBufferBuilderPack());
         }
      } catch (OutOfMemoryError e) {
         LOGGER.warn("Allocated only {}/{} buffers", buffers.size(), targetBufferCount);
         int buffersToDrop = Math.min(buffers.size() * 2 / 3, buffers.size() - 1);

         for (int i = 0; i < buffersToDrop; i++) {
            buffers.remove(buffers.size() - 1).close();
         }
      }

      return new SectionBufferBuilderPool(buffers);
   }

   public @Nullable SectionBufferBuilderPack acquire() {
      return this.freeBuffers.poll();
   }

   public void release(final SectionBufferBuilderPack buffer) {
      this.freeBuffers.offer(buffer);
   }

   public boolean isEmpty() {
      return this.freeBuffers.isEmpty();
   }

   public int getFreeBufferCount() {
      return this.freeBuffers.size();
   }

   public void eaglerTrimAfterWorld() {
      this.freeBuffers.forEach(SectionBufferBuilderPack::eaglerTrimAfterWorld);
   }

   public boolean eaglerTrimOneAfterWorld() {
      SectionBufferBuilderPack buffer = this.freeBuffers.poll();
      if (buffer == null) {
         return false;
      }
      try {
         buffer.eaglerTrimAfterWorld();
      } finally {
         this.freeBuffers.offer(buffer);
      }
      return true;
   }

   @Override
   public void close() {
      this.freeBuffers.forEach(SectionBufferBuilderPack::close);
      this.freeBuffers.clear();
   }
}
