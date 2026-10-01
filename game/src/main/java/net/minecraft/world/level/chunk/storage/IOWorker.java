package net.minecraft.world.level.chunk.storage;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class IOWorker implements AutoCloseable, ChunkScanAccess {
   public static final Supplier<CompoundTag> STORE_EMPTY = () -> null;
   private static final Logger LOGGER = LogUtils.getLogger();
   private final AtomicBoolean shutdownRequested = new AtomicBoolean();
   private final PriorityConsecutiveExecutor consecutiveExecutor;
   private final RegionFileStorage storage;
   private final SequencedMap<ChunkPos, IOWorker.PendingStore> pendingWrites = new LinkedHashMap<ChunkPos, IOWorker.PendingStore>();
   private final SequencedMap<ChunkPos, IOWorker.PendingStore> hostedWritesInFlight = new LinkedHashMap<ChunkPos, IOWorker.PendingStore>();
   private final boolean hostedWeb;
   private boolean hostedStoreTaskQueued;
   private final Long2ObjectLinkedOpenHashMap<CompletableFuture<BitSet>> regionCacheForBlender = new Long2ObjectLinkedOpenHashMap();
   private static final int REGION_CACHE_SIZE = 1024;

   protected IOWorker(final RegionStorageInfo info, final Path dir, final boolean sync) {
      // Eagler storage seam: hosted mode stores per-chunk blobs on the eagler VFS
      boolean hosted = net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive();
      this.storage = hosted
         ? new net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSChunkStorage(info, dir, sync)
         : new RegionFileStorage(info, dir, sync);
      this.hostedWeb = hosted && net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
         != net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP;
      this.consecutiveExecutor = new PriorityConsecutiveExecutor(IOWorker.Priority.values().length, Util.ioPool(), "IOWorker-" + info.type());
   }

   public boolean isOldChunkAround(final ChunkPos pos, final int range) {
	  if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()
			&& net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
					!= net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP) {
		 // Browser storage rejects pre-current DataVersion chunks before they reach
		 // worldgen because DFU is unavailable here. A blending scan therefore cannot
		 // find a usable old chunk; probing every one of the 1024 positions in each
		 // region only burns IndexedDB calls and can self-join this serialized I/O
		 // executor. Current-version saved chunks and newly generated chunks need no
		 // old/new terrain blending, so the correct answer is always false on web.
		 return false;
	  }
      ChunkPos from = new ChunkPos(pos.x() - range, pos.z() - range);
      ChunkPos to = new ChunkPos(pos.x() + range, pos.z() + range);

      for (int regionX = from.getRegionX(); regionX <= to.getRegionX(); regionX++) {
         for (int regionZ = from.getRegionZ(); regionZ <= to.getRegionZ(); regionZ++) {
            BitSet data = this.getOrCreateOldDataForRegion(regionX, regionZ).join();
            if (!data.isEmpty()) {
               ChunkPos minChunkPos = ChunkPos.minFromRegion(regionX, regionZ);
               int startChunkX = Math.max(from.x() - minChunkPos.x(), 0);
               int startChunkZ = Math.max(from.z() - minChunkPos.z(), 0);
               int endChunkX = Math.min(to.x() - minChunkPos.x(), 31);
               int endChunkZ = Math.min(to.z() - minChunkPos.z(), 31);

               for (int x = startChunkX; x <= endChunkX; x++) {
                  for (int z = startChunkZ; z <= endChunkZ; z++) {
                     int chunkIndex = z * 32 + x;
                     if (data.get(chunkIndex)) {
                        return true;
                     }
                  }
               }
            }
         }
      }

      return false;
   }

   private CompletableFuture<BitSet> getOrCreateOldDataForRegion(final int regionX, final int regionZ) {
      long regionPos = ChunkPos.pack(regionX, regionZ);
      synchronized (this.regionCacheForBlender) {
         CompletableFuture<BitSet> result = (CompletableFuture<BitSet>)this.regionCacheForBlender.getAndMoveToFirst(regionPos);
         if (result == null) {
            result = this.createOldDataForRegion(regionX, regionZ);
            this.regionCacheForBlender.putAndMoveToFirst(regionPos, result);
            if (this.regionCacheForBlender.size() > 1024) {
               this.regionCacheForBlender.removeLast();
            }
         }

         return result;
      }
   }

   private CompletableFuture<BitSet> createOldDataForRegion(final int regionX, final int regionZ) {
      return CompletableFuture.supplyAsync(
         () -> {
            ChunkPos from = ChunkPos.minFromRegion(regionX, regionZ);
            ChunkPos to = ChunkPos.maxFromRegion(regionX, regionZ);
            BitSet resultSet = new BitSet();
            ChunkPos.rangeClosed(from, to)
               .forEach(
                  pos -> {
                     CollectFields collectFields = new CollectFields(
                        new FieldSelector(IntTag.TYPE, "DataVersion"), new FieldSelector(CompoundTag.TYPE, "blending_data")
                     );

                     try {
                        this.scanChunk(pos, collectFields).join();
                     } catch (Exception e) {
                        LOGGER.warn("Failed to scan chunk {}", pos, e);
                        return;
                     }

                     if (collectFields.getResult() instanceof CompoundTag chunkTag && this.isOldChunk(chunkTag)) {
                        int chunkIndex = pos.getRegionLocalZ() * 32 + pos.getRegionLocalX();
                        resultSet.set(chunkIndex);
                     }
                  }
               );
            return resultSet;
         },
         Util.backgroundExecutor()
      );
   }

   private boolean isOldChunk(final CompoundTag tag) {
      return tag.getIntOr("DataVersion", 0) < 4882 ? true : tag.getCompound("blending_data").isPresent();
   }

   public CompletableFuture<Void> store(final ChunkPos pos, final CompoundTag value) {
      return this.store(pos, () -> value);
   }

   public CompletableFuture<Void> store(final ChunkPos pos, final Supplier<CompoundTag> supplier) {
      return this.<CompletableFuture<Void>>submitTask(() -> {
         CompoundTag data = supplier.get();
         IOWorker.PendingStore pendingStore = (IOWorker.PendingStore)this.pendingWrites.computeIfAbsent(pos, p -> new IOWorker.PendingStore(data));
         pendingStore.data = data;
         return pendingStore.result;
      }).thenCompose(Function.identity());
   }

   public CompletableFuture<Optional<CompoundTag>> loadAsync(final ChunkPos pos) {
      return this.submitThrowingTask(() -> {
         IOWorker.PendingStore pendingStore = (IOWorker.PendingStore)this.pendingWrites.get(pos);
         if (pendingStore == null && this.hostedWeb) {
            pendingStore = (IOWorker.PendingStore)this.hostedWritesInFlight.get(pos);
         }
         if (pendingStore != null) {
            try {
               return Optional.ofNullable(pendingStore.copyData());
            } catch (RuntimeException pendingFailure) {
               // A malformed in-memory NBT child must not make ChunkMap classify an existing
               // durable full chunk as replaceable. Fall back to the last committed blob; this
               // also identifies the pending-copy branch in readable logs.
               LOGGER.warn("Failed to copy pending {} chunk {}; falling back to durable storage",
                  this.storage.info().type(), pos, pendingFailure);
               return Optional.ofNullable(this.storage.read(pos));
            }
         }

         try {
            CompoundTag data = this.storage.read(pos);
            return Optional.ofNullable(data);
         } catch (Exception e) {
            LOGGER.warn("Failed to read chunk {}", pos, e);
            throw e;
         }
      });
   }

   public CompletableFuture<Void> synchronize(final boolean flush) {
      if (this.hostedWeb) {
         this.awaitHostedWrites();
         if (flush) {
            try {
               this.storage.flush();
            } catch (Exception e) {
               LOGGER.warn("Failed to synchronize chunks", e);
               return CompletableFuture.failedFuture(e);
            }
         }
         return CompletableFuture.completedFuture(null);
      }
      CompletableFuture<Void> currentWrites = this.<CompletableFuture<Void>>submitTask(
            () -> CompletableFuture.allOf(this.pendingWrites.values().stream().map(store -> store.result).toArray(CompletableFuture[]::new))
         )
         .thenCompose(Function.identity());
      return flush ? currentWrites.thenCompose(ignore -> this.submitThrowingTask(() -> {
         try {
            this.storage.flush();
            return null;
         } catch (Exception e) {
            LOGGER.warn("Failed to synchronize chunks", e);
            throw e;
         }
      })) : currentWrites.thenCompose(ignore -> this.submitTask(() -> null));
   }

   @Override
   public CompletableFuture<Void> scanChunk(final ChunkPos pos, final StreamTagVisitor visitor) {
      return this.submitThrowingTask(() -> {
         try {
            IOWorker.PendingStore pendingStore = (IOWorker.PendingStore)this.pendingWrites.get(pos);
            if (pendingStore == null && this.hostedWeb) {
               pendingStore = (IOWorker.PendingStore)this.hostedWritesInFlight.get(pos);
            }
            if (pendingStore != null) {
               if (pendingStore.data != null) {
                  pendingStore.data.acceptAsRoot(visitor);
               }
            } else {
               this.storage.scanChunk(pos, visitor);
            }

            return null;
         } catch (Exception e) {
            LOGGER.warn("Failed to bulk scan chunk {}", pos, e);
            throw e;
         }
      });
   }

   private <T> CompletableFuture<T> submitThrowingTask(final IOWorker.ThrowingSupplier<T> task) {
      return this.consecutiveExecutor.scheduleWithResult(IOWorker.Priority.FOREGROUND.ordinal(), future -> {
         if (!this.shutdownRequested.get()) {
            try {
               future.complete(task.get());
            } catch (Exception e) {
               future.completeExceptionally(e);
            }
         }

         this.tellStorePending();
      });
   }

   private <T> CompletableFuture<T> submitTask(final Supplier<T> task) {
      return this.consecutiveExecutor.scheduleWithResult(IOWorker.Priority.FOREGROUND.ordinal(), future -> {
         if (!this.shutdownRequested.get()) {
            future.complete(task.get());
         }

         this.tellStorePending();
      });
   }

   private void storePendingChunk() {
      Entry<ChunkPos, IOWorker.PendingStore> entry = this.pendingWrites.pollFirstEntry();
      if (entry != null) {
         if (this.hostedWeb) {
            this.hostedWritesInFlight.put(entry.getKey(), entry.getValue());
         }
         this.runStore(entry.getKey(), entry.getValue());
         if (this.hostedWeb) {
            this.hostedWritesInFlight.remove(entry.getKey(), entry.getValue());
            this.hostedStoreTaskQueued = false;
         }
         this.tellStorePending();
      } else if (this.hostedWeb) {
         this.hostedStoreTaskQueued = false;
      }
   }

   private void tellStorePending() {
      if (this.hostedWeb) {
         if (!this.pendingWrites.isEmpty() && !this.hostedStoreTaskQueued) {
            this.hostedStoreTaskQueued = true;
            net.lax1dude.eaglercraft.v1_8.sp.server.EaglerChunkIOExecutor.INSTANCE.execute(this::storePendingChunk);
         }
      } else {
         this.consecutiveExecutor.schedule(new StrictQueue.RunnableWithPriority(IOWorker.Priority.BACKGROUND.ordinal(), this::storePendingChunk));
      }
   }

   private void runStore(final ChunkPos pos, final IOWorker.PendingStore write) {
      try {
         this.storage.write(pos, write.data);
         write.result.complete(null);
      } catch (Exception e) {
         LOGGER.error("Failed to store chunk {}", pos, e);
         write.result.completeExceptionally(e);
      }
   }

   @Override
   public void close() throws IOException {
      if (this.shutdownRequested.compareAndSet(false, true)) {
         if (this.hostedWeb) {
            this.awaitHostedWrites();
         } else {
            this.waitForShutdown();
         }
         this.consecutiveExecutor.close();

         try {
            this.storage.close();
         } catch (Exception e) {
            LOGGER.error("Failed to close storage", e);
         }
      }
   }

   private void waitForShutdown() {
      this.consecutiveExecutor.scheduleWithResult(IOWorker.Priority.SHUTDOWN.ordinal(), future -> future.complete(Unit.INSTANCE)).join();
   }

   private void awaitHostedWrites() {
      while (this.hostedStoreTaskQueued || !this.pendingWrites.isEmpty() || !this.hostedWritesInFlight.isEmpty()) {
         net.lax1dude.eaglercraft.v1_8.EagUtils.sleep(0L);
      }
   }

   public RegionStorageInfo storageInfo() {
      return this.storage.info();
   }

   private static class PendingStore {
      private @Nullable CompoundTag data;
      private final CompletableFuture<Void> result = new CompletableFuture<>();

      public PendingStore(final @Nullable CompoundTag data) {
         this.data = data;
      }

      private @Nullable CompoundTag copyData() {
         CompoundTag data = this.data;
         return data == null ? null : data.copy();
      }
   }

   private enum Priority {
      FOREGROUND,
      BACKGROUND,
      SHUTDOWN;
   }

   @FunctionalInterface
   private interface ThrowingSupplier<T> {
      @Nullable T get() throws Exception;
   }
}
