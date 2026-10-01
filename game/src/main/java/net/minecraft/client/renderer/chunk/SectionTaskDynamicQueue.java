package net.minecraft.client.renderer.chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Distance-prioritized section work queue.
 *
 * <p>The stock list implementation searched every pending section on every poll. During fast
 * flight that made dispatch O(pending sections x dispatched sections), precisely when the client
 * was already busy receiving and meshing terrain. Keep three heaps instead, deduplicate by task
 * identity, and only rebuild their distance keys after the camera has moved meaningfully.
 */
public class SectionTaskDynamicQueue {
   private static final int MAX_RECOMPILE_QUOTA = 2;
   private static final double CAMERA_REWEIGHT_DISTANCE_SQR = 64.0;
   private static final int PRIORITY = 0;
   private static final int INITIAL = 1;
   private static final int RECOMPILE = 2;
   private static final Comparator<QueuedTask> ORDER = Comparator
      .comparingDouble((QueuedTask entry) -> entry.distanceSqr)
      .thenComparingLong(entry -> entry.sequence);

   private final PriorityQueue<QueuedTask> priorityTasks = new PriorityQueue<>(ORDER);
   private final PriorityQueue<QueuedTask> initialTasks = new PriorityQueue<>(ORDER);
   private final PriorityQueue<QueuedTask> recompileTasks = new PriorityQueue<>(ORDER);
   private final Map<SectionRenderDispatcher.RenderSection.SectionTask, QueuedTask> entries = new IdentityHashMap<>();
   private int recompileQuota = MAX_RECOMPILE_QUOTA;
   private long nextSequence;
   private @Nullable Vec3 weightedCamera;

   public synchronized void add(final SectionRenderDispatcher.RenderSection.SectionTask task) {
      if (task.isCancelled.get() || task.isCompleted.get() || this.entries.containsKey(task)) {
         return;
      }

      Vec3 camera = this.weightedCamera == null ? Vec3.ZERO : this.weightedCamera;
      int queueType = task.playerPriority() ? PRIORITY : (task.isRecompile() ? RECOMPILE : INITIAL);
      QueuedTask entry = new QueuedTask(
         task, queueType, task.getRenderOrigin().distToCenterSqr(camera), this.nextSequence++
      );
      this.entries.put(task, entry);
      this.queueFor(queueType).add(entry);
   }

   public synchronized SectionRenderDispatcher.RenderSection.@Nullable SectionTask poll(final Vec3 cameraPos) {
      this.reweightIfNeeded(cameraPos);

      QueuedTask selected = this.pollValid(this.priorityTasks);
      if (selected == null) {
         QueuedTask initial = this.peekValid(this.initialTasks);
         QueuedTask recompile = this.peekValid(this.recompileTasks);
         if (recompile == null || initial != null
            && (this.recompileQuota <= 0 || !(recompile.distanceSqr < initial.distanceSqr))) {
            this.recompileQuota = MAX_RECOMPILE_QUOTA;
            selected = this.pollValid(this.initialTasks);
         } else {
            this.recompileQuota--;
            selected = this.pollValid(this.recompileTasks);
         }
      }

      if (selected != null) {
         this.entries.remove(selected.task);
         return selected.task;
      }
      return null;
   }

   /**
    * Remove a superseded task immediately. This is O(log n) conceptually, with the JDK heap's
    * identity removal doing a short linear lookup; it happens once per dirty-section replacement
    * and prevents cancelled entries from accumulating during block-edit storms.
    */
   public synchronized void discard(final SectionRenderDispatcher.RenderSection.SectionTask task) {
      QueuedTask entry = this.entries.remove(task);
      if (entry != null) {
         this.queueFor(entry.queueType).remove(entry);
      }
   }

   public synchronized int size() {
      return this.entries.size();
   }

   public synchronized void clear() {
      ArrayList<SectionRenderDispatcher.RenderSection.SectionTask> pending = new ArrayList<>(this.entries.keySet());
      this.entries.clear();
      this.priorityTasks.clear();
      this.initialTasks.clear();
      this.recompileTasks.clear();
      for (SectionRenderDispatcher.RenderSection.SectionTask task : pending) {
         task.cancel();
      }
   }

   private void reweightIfNeeded(final Vec3 cameraPos) {
      if (this.weightedCamera != null && this.weightedCamera.distanceToSqr(cameraPos) < CAMERA_REWEIGHT_DISTANCE_SQR) {
         return;
      }
      this.weightedCamera = cameraPos;
      if (this.entries.isEmpty()) {
         return;
      }

      this.priorityTasks.clear();
      this.initialTasks.clear();
      this.recompileTasks.clear();
      ArrayList<SectionRenderDispatcher.RenderSection.SectionTask> cancelled = null;
      for (QueuedTask entry : this.entries.values()) {
         if (entry.task.isCancelled.get() || entry.task.isCompleted.get()) {
            if (cancelled == null) {
               cancelled = new ArrayList<>();
            }
            cancelled.add(entry.task);
            continue;
         }
         entry.distanceSqr = entry.task.getRenderOrigin().distToCenterSqr(cameraPos);
         this.queueFor(entry.queueType).add(entry);
      }
      if (cancelled != null) {
         for (SectionRenderDispatcher.RenderSection.SectionTask task : cancelled) {
            this.entries.remove(task);
         }
      }
   }

   private @Nullable QueuedTask peekValid(final PriorityQueue<QueuedTask> queue) {
      while (true) {
         QueuedTask entry = queue.peek();
         if (entry == null) {
            return null;
         }
         if (!entry.task.isCancelled.get() && !entry.task.isCompleted.get()) {
            return entry;
         }
         queue.poll();
         this.entries.remove(entry.task);
      }
   }

   private @Nullable QueuedTask pollValid(final PriorityQueue<QueuedTask> queue) {
      QueuedTask entry = this.peekValid(queue);
      if (entry != null) {
         queue.poll();
      }
      return entry;
   }

   private PriorityQueue<QueuedTask> queueFor(final int queueType) {
      return switch (queueType) {
         case PRIORITY -> this.priorityTasks;
         case INITIAL -> this.initialTasks;
         default -> this.recompileTasks;
      };
   }

   private static final class QueuedTask {
      final SectionRenderDispatcher.RenderSection.SectionTask task;
      final int queueType;
      double distanceSqr;
      final long sequence;

      QueuedTask(
         final SectionRenderDispatcher.RenderSection.SectionTask task,
         final int queueType,
         final double distanceSqr,
         final long sequence
      ) {
         this.task = task;
         this.queueType = queueType;
         this.distanceSqr = distanceSqr;
         this.sequence = sequence;
      }
   }
}
