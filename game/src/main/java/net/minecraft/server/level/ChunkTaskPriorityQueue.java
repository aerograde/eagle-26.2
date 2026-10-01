package net.minecraft.server.level;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public class ChunkTaskPriorityQueue {
   public static final int PRIORITY_LEVEL_COUNT = ChunkLevel.MAX_LEVEL + 2;
   private final List<Long2ObjectLinkedOpenHashMap<List<Runnable>>> queuesPerPriority = IntStream.range(0, PRIORITY_LEVEL_COUNT)
      .mapToObj(priority -> new Long2ObjectLinkedOpenHashMap<List<Runnable>>())
      .toList();
   // Each dispatcher owns a separate queue, while the holder queue level is shared.
   // Read our actual bucket when reprioritizing; another dispatcher may already
   // have updated the shared level before our deferred callback runs.
   private final Long2IntOpenHashMap queuedPriorities = new Long2IntOpenHashMap();
   private volatile int topPriorityQueueIndex = PRIORITY_LEVEL_COUNT;
   private final String name;

   public ChunkTaskPriorityQueue(final String name) {
      this.name = name;
      this.queuedPriorities.defaultReturnValue(-1);
   }

   protected void resortChunkTasks(final int oldPriority, final ChunkPos pos, final int newPriority) {
      this.resortChunkTasks(pos.pack(), newPriority);
   }

   private void resortChunkTasks(final long chunkPos, final int newPriority) {
      int actualPriority = this.queuedPriorities.get(chunkPos);
      if (actualPriority >= 0) {
         Long2ObjectLinkedOpenHashMap<List<Runnable>> oldQueue = this.queuesPerPriority.get(actualPriority);
         List<Runnable> oldTasks = (List<Runnable>)oldQueue.remove(chunkPos);
         if (actualPriority == this.topPriorityQueueIndex) {
            while (this.hasWork() && this.queuesPerPriority.get(this.topPriorityQueueIndex).isEmpty()) {
               this.topPriorityQueueIndex++;
            }
         }

         if (oldTasks != null && !oldTasks.isEmpty()) {
            this.queuesPerPriority.get(newPriority).put(chunkPos, oldTasks);
            this.queuedPriorities.put(chunkPos, newPriority);
            this.topPriorityQueueIndex = Math.min(this.topPriorityQueueIndex, newPriority);
         } else {
            this.queuedPriorities.remove(chunkPos);
         }
      }
   }

   protected void submit(final Runnable task, final long chunkPos, final int level) {
      // A fresh submission can observe a newer holder level than an older task.
      // Keep all work for the same chunk together and preserve its FIFO order.
      if (this.queuedPriorities.get(chunkPos) != level) {
         this.resortChunkTasks(chunkPos, level);
      }
      ((List)this.queuesPerPriority.get(level).computeIfAbsent(chunkPos, k -> Lists.newArrayList())).add(task);
      this.queuedPriorities.put(chunkPos, level);
      this.topPriorityQueueIndex = Math.min(this.topPriorityQueueIndex, level);
   }

   protected ChunkTaskPriorityQueue.@Nullable TasksForChunk eaglerRemoveTasks(final long pos) {
      List<Runnable> removed = null;
      for (Long2ObjectLinkedOpenHashMap<List<Runnable>> queue : this.queuesPerPriority) {
         List<Runnable> tasks = (List<Runnable>)queue.remove(pos);
         if (tasks != null && !tasks.isEmpty()) {
            if (removed == null) {
               removed = Lists.newArrayList();
            }
            removed.addAll(tasks);
         }
      }

      while (this.hasWork() && this.queuesPerPriority.get(this.topPriorityQueueIndex).isEmpty()) {
         this.topPriorityQueueIndex++;
      }
      this.queuedPriorities.remove(pos);
      return removed == null ? null : new ChunkTaskPriorityQueue.TasksForChunk(pos, removed);
   }

   protected void release(final long pos, final boolean unschedule) {
      for (Long2ObjectLinkedOpenHashMap<List<Runnable>> queue : this.queuesPerPriority) {
         List<Runnable> tasks = (List<Runnable>)queue.get(pos);
         if (tasks != null) {
            if (unschedule) {
               tasks.clear();
            }

            if (tasks.isEmpty()) {
               queue.remove(pos);
               this.queuedPriorities.remove(pos);
            }
         }
      }

      while (this.hasWork() && this.queuesPerPriority.get(this.topPriorityQueueIndex).isEmpty()) {
         this.topPriorityQueueIndex++;
      }
   }

   public ChunkTaskPriorityQueue.@Nullable TasksForChunk pop() {
      if (!this.hasWork()) {
         return null;
      }

      int index = this.topPriorityQueueIndex;
      Long2ObjectLinkedOpenHashMap<List<Runnable>> queue = this.queuesPerPriority.get(index);
      long chunkPos = queue.firstLongKey();
      List<Runnable> tasks = (List<Runnable>)queue.remove(chunkPos);
      this.queuedPriorities.remove(chunkPos);

      while (this.hasWork() && this.queuesPerPriority.get(this.topPriorityQueueIndex).isEmpty()) {
         this.topPriorityQueueIndex++;
      }

      return new ChunkTaskPriorityQueue.TasksForChunk(chunkPos, tasks);
   }

   public boolean hasWork() {
      return this.topPriorityQueueIndex < PRIORITY_LEVEL_COUNT;
   }

   @Override
   public String toString() {
      return this.name + " " + this.topPriorityQueueIndex + "...";
   }

   public record TasksForChunk(long chunkPos, List<Runnable> tasks) {
   }
}
