package net.minecraft.server.level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

public class ChunkGenerationTask {
   private final GeneratingChunkMap chunkMap;
   private final ChunkPos pos;
   private @Nullable ChunkStatus scheduledStatus = null;
   public final ChunkStatus targetStatus;
   private volatile boolean markedForCancellation;
   private final List<CompletableFuture<ChunkResult<ChunkAccess>>> scheduledLayer = new ArrayList<>();
   private final StaticCache2D<GenerationChunkHolder> cache;
   private final boolean sparseStoredChunkLoading;
   private boolean needsGeneration;
   private @Nullable ChunkStatus schedulingStatus;
   private boolean schedulingNeedsGeneration;
   private int schedulingRadius;
   private int schedulingX;
   private int schedulingZ;

   private ChunkGenerationTask(
      final GeneratingChunkMap chunkMap, final ChunkStatus targetStatus, final ChunkPos pos, final StaticCache2D<GenerationChunkHolder> cache
   ) {
      this.chunkMap = chunkMap;
      this.targetStatus = targetStatus;
      this.pos = pos;
      this.cache = cache;
      this.sparseStoredChunkLoading = chunkMap.useEaglerSparseStoredChunkLoading(pos, targetStatus);
   }

   public static ChunkGenerationTask create(final GeneratingChunkMap chunkMap, final ChunkStatus targetStatus, final ChunkPos pos) {
      int worstCaseRadius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(targetStatus).getAccumulatedRadiusOf(ChunkStatus.EMPTY);
      StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(
         pos.x(), pos.z(), worstCaseRadius, (x, z) -> chunkMap.acquireGeneration(ChunkPos.pack(x, z))
      );
      return new ChunkGenerationTask(chunkMap, targetStatus, pos, cache);
   }

   public @Nullable CompletableFuture<?> runUntilWait() {
      boolean scheduledBatch = false;
      while (true) {
         CompletableFuture<?> waitingFor = this.waitForScheduledLayer();
         if (waitingFor != null) {
            return waitingFor;
         }

         if (this.markedForCancellation || this.scheduledStatus == this.targetStatus) {
            this.releaseClaim();
            return null;
         }

         if (scheduledBatch && this.chunkMap.eaglerGenerationLayerBatchSize() != Integer.MAX_VALUE) {
            // Completed futures do not make waitForScheduledLayer yield. Return to
            // ChunkMap's dispatcher after a batch even when every step completed
            // inline, so its executor can service the server timer between batches.
            return CompletableFuture.completedFuture(null);
         }

         this.scheduleNextLayer();
         scheduledBatch = true;
      }
   }

   private void scheduleNextLayer() {
      if (this.schedulingStatus == null) {
         if (this.scheduledStatus == null) {
            this.schedulingStatus = ChunkStatus.EMPTY;
         } else if (!this.needsGeneration && this.scheduledStatus == ChunkStatus.EMPTY && !this.canLoadWithoutGeneration()) {
            this.needsGeneration = true;
            this.schedulingStatus = ChunkStatus.EMPTY;
         } else {
            this.schedulingStatus = ChunkStatus.getStatusList().get(this.scheduledStatus.getIndex() + 1);
         }

         this.schedulingNeedsGeneration = this.needsGeneration;
         this.schedulingRadius = this.getRadiusForLayer(this.schedulingStatus, this.schedulingNeedsGeneration);
         this.schedulingX = this.pos.x() - this.schedulingRadius;
         this.schedulingZ = this.pos.z() - this.schedulingRadius;
      }

      if (this.scheduleLayerBatch()) {
         this.scheduledStatus = this.schedulingStatus;
         this.schedulingStatus = null;
      }
   }

   public void markForCancellation() {
      this.markedForCancellation = true;
   }

   public boolean isMarkedForCancellation() {
      return this.markedForCancellation;
   }

   private void releaseClaim() {
      GenerationChunkHolder chunkHolder = this.cache.get(this.pos.x(), this.pos.z());
      chunkHolder.removeTask(this);
      this.cache.forEach(this.chunkMap::releaseGeneration);
      net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.generationTaskCompleted();
   }

   private boolean canLoadWithoutGeneration() {
      if (this.targetStatus == ChunkStatus.EMPTY) {
         return true;
      }

      ChunkStatus highestGeneratedStatus = this.cache.get(this.pos.x(), this.pos.z()).getPersistedStatus();
      if (highestGeneratedStatus != null && !highestGeneratedStatus.isBefore(this.targetStatus)) {
         ChunkPyramid loadingPyramid = this.sparseStoredChunkLoading
            ? ChunkPyramid.EAGLER_SPARSE_LOADING_PYRAMID
            : ChunkPyramid.LOADING_PYRAMID;
         ChunkDependencies dependencies = loadingPyramid.getStepTo(this.targetStatus).accumulatedDependencies();
         int range = dependencies.getRadius();

         for (int x = this.pos.x() - range; x <= this.pos.x() + range; x++) {
            for (int z = this.pos.z() - range; z <= this.pos.z() + range; z++) {
               int distance = this.pos.getChessboardDistance(x, z);
               ChunkStatus requiredStatus = dependencies.get(distance);
               ChunkStatus persistedStatus = this.cache.get(x, z).getPersistedStatus();
               if (persistedStatus == null || persistedStatus.isBefore(requiredStatus)) {
                  return false;
               }
            }
         }

         return true;
      } else {
         return false;
      }
   }

   public GenerationChunkHolder getCenter() {
      return this.cache.get(this.pos.x(), this.pos.z());
   }

   private boolean scheduleLayerBatch() {
      try (Zone zone = Profiler.get().zone("scheduleLayer")) {
         ChunkStatus status = this.schedulingStatus;
         boolean needsGeneration = this.schedulingNeedsGeneration;
         zone.addText(status::getName);
         int maximum = this.pos.x() + this.schedulingRadius;
         int scheduled = 0;
         int layerBatchSize = Math.max(1, this.chunkMap.eaglerGenerationLayerBatchSize());

         while (this.schedulingX <= maximum && scheduled < layerBatchSize) {
            GenerationChunkHolder chunkHolder = this.cache.get(this.schedulingX, this.schedulingZ);
            if (this.markedForCancellation || !this.scheduleChunkInLayer(status, needsGeneration, chunkHolder)) {
               return false;
            }

            scheduled++;
            if (++this.schedulingZ > this.pos.z() + this.schedulingRadius) {
               this.schedulingZ = this.pos.z() - this.schedulingRadius;
               this.schedulingX++;
            }
         }

         return this.schedulingX > maximum;
      }
   }

   private int getRadiusForLayer(final ChunkStatus status, final boolean needsGeneration) {
      ChunkPyramid pyramid = needsGeneration
         ? ChunkPyramid.GENERATION_PYRAMID
         : this.sparseStoredChunkLoading
            ? ChunkPyramid.EAGLER_SPARSE_LOADING_PYRAMID
            : ChunkPyramid.LOADING_PYRAMID;
      return pyramid.getStepTo(this.targetStatus).getAccumulatedRadiusOf(status);
   }

   private boolean scheduleChunkInLayer(final ChunkStatus status, final boolean needsGeneration, final GenerationChunkHolder chunkHolder) {
      ChunkStatus persistedStatus = chunkHolder.getPersistedStatus();
      boolean generate = persistedStatus != null && status.isAfter(persistedStatus);
      ChunkPyramid pyramid = generate
         ? ChunkPyramid.GENERATION_PYRAMID
         : this.sparseStoredChunkLoading
            ? ChunkPyramid.EAGLER_SPARSE_LOADING_PYRAMID
            : ChunkPyramid.LOADING_PYRAMID;
      if (generate && !needsGeneration) {
         throw new IllegalStateException("Can't load chunk, but didn't expect to need to generate");
      }

      CompletableFuture<ChunkResult<ChunkAccess>> future = chunkHolder.applyStep(pyramid.getStepTo(status), this.chunkMap, this.cache);
      ChunkResult<ChunkAccess> now = future.getNow(null);
      if (now == null) {
         this.scheduledLayer.add(future);
         return true;
      }

      if (now.isSuccess()) {
         return true;
      }

      this.markForCancellation();
      return false;
   }

   private @Nullable CompletableFuture<?> waitForScheduledLayer() {
      while (!this.scheduledLayer.isEmpty()) {
         CompletableFuture<ChunkResult<ChunkAccess>> lastFuture = (CompletableFuture<ChunkResult<ChunkAccess>>)this.scheduledLayer.getLast();
         ChunkResult<ChunkAccess> resultNow = lastFuture.getNow(null);
         if (resultNow == null) {
            return lastFuture;
         }

         this.scheduledLayer.removeLast();
         if (!resultNow.isSuccess()) {
            this.markForCancellation();
         }
      }

      return null;
   }
}
