package net.lax1dude.eaglercraft.v1_8.minecraft;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cooperative resource-reload executor for the single-threaded browser runtime.
 *
 * <p>The TeaVM ForkJoinPool compatibility class executes tasks immediately on
 * the caller. Vanilla resource reloads schedule thousands of parsing and model
 * baking jobs, so using that pool makes createReload() run the entire graph
 * before it can return to the game loop. This queue keeps the same FIFO task
 * ordering but runs it from a daemon green thread and yields to the browser
 * between jobs.</p>
 */
public final class BrowserResourceReloadExecutor implements Executor {
   public static final BrowserResourceReloadExecutor INSTANCE = new BrowserResourceReloadExecutor();
   private static final Logger LOGGER = LoggerFactory.getLogger(BrowserResourceReloadExecutor.class);
   private static final long BOOT_SLICE_MILLIS = 12L;
   private static final long LOADING_OVERLAY_SLICE_MILLIS = 24L;
   private static final long WORLD_SLICE_MILLIS = 4L;
   private static final int PERF_SLOW_TASK_SLOTS = 8;

   private final Deque<Runnable> tasks = new ArrayDeque<>();
   private boolean workerRunning;
   private long perfTasks;
   private long perfTaskMillis;
   private long perfTaskMaxMillis;
   private long perfOver16Millis;
   private long perfOver50Millis;
   private long perfYields;
   private int perfQueueMax;
   private final long[] perfSlowTaskMillis = new long[PERF_SLOW_TASK_SLOTS];
   private final String[] perfSlowTaskNames = new String[PERF_SLOW_TASK_SLOTS];

   private BrowserResourceReloadExecutor() {
   }

   @Override
   public void execute(final Runnable command) {
      if (command == null) {
         throw new NullPointerException("command");
      }

      boolean startWorker = false;
      synchronized (this.tasks) {
         this.tasks.addLast(command);
         if (!this.workerRunning) {
            this.workerRunning = true;
            startWorker = true;
         }
      }

      if (startWorker) {
         Thread worker = new Thread(this::drain, "Eagler resource reload");
         worker.setDaemon(true);
         worker.start();
      }
   }

   private void drain() {
      long nextYieldAt = EagRuntime.steadyTimeMillis() + sliceMillis();
      while (true) {
         Runnable task;
         synchronized (this.tasks) {
            task = this.tasks.pollFirst();
            if (task == null) {
               this.workerRunning = false;
               if (EaglerClientPerf.isEnabled() && this.perfTasks != 0L) {
                  LOGGER.info("[EagPerfClient] resource executor drain tasks={} avg/max={}/{}ms over16={} over50={} yields={} queueMax={}",
                     this.perfTasks, this.perfTaskMillis / this.perfTasks, this.perfTaskMaxMillis,
                     this.perfOver16Millis, this.perfOver50Millis, this.perfYields, this.perfQueueMax);
                  for (int i = 0; i < PERF_SLOW_TASK_SLOTS && this.perfSlowTaskMillis[i] != 0L; i++) {
                     LOGGER.info("[EagPerfClient] resource executor slow task #{}={}ms {}", i + 1,
                        this.perfSlowTaskMillis[i], this.perfSlowTaskNames[i]);
                     this.perfSlowTaskMillis[i] = 0L;
                     this.perfSlowTaskNames[i] = null;
                  }
                  this.perfTasks = this.perfTaskMillis = this.perfTaskMaxMillis = 0L;
                  this.perfOver16Millis = this.perfOver50Millis = this.perfYields = 0L;
                  this.perfQueueMax = 0;
               }
               return;
            }
         }

         boolean perfEnabled = EaglerClientPerf.isEnabled();
         long taskStarted = perfEnabled ? EagRuntime.steadyTimeMillis() : 0L;
         try {
            task.run();
         } catch (Throwable t) {
            LOGGER.error("Uncaught resource reload task failure", t);
         }

         long now = EagRuntime.steadyTimeMillis();
         if (perfEnabled) {
            long duration = Math.max(0L, now - taskStarted);
            int queueDepth;
            synchronized (this.tasks) {
               queueDepth = this.tasks.size();
            }
            this.perfTasks++;
            this.perfTaskMillis += duration;
            this.perfTaskMaxMillis = Math.max(this.perfTaskMaxMillis, duration);
            if (duration > 16L) this.perfOver16Millis++;
            if (duration > 50L) this.perfOver50Millis++;
            this.perfQueueMax = Math.max(this.perfQueueMax, queueDepth);
            if (duration > this.perfSlowTaskMillis[PERF_SLOW_TASK_SLOTS - 1]) {
               int index = PERF_SLOW_TASK_SLOTS - 1;
               while (index > 0 && duration > this.perfSlowTaskMillis[index - 1]) {
                  this.perfSlowTaskMillis[index] = this.perfSlowTaskMillis[index - 1];
                  this.perfSlowTaskNames[index] = this.perfSlowTaskNames[index - 1];
                  index--;
               }
               this.perfSlowTaskMillis[index] = duration;
               this.perfSlowTaskNames[index] = task.getClass().getName();
            }
         }

         if (now >= nextYieldAt) {
            if (perfEnabled) this.perfYields++;
            EagUtils.sleep(0);
            nextYieldAt = EagRuntime.steadyTimeMillis() + sliceMillis();
         }
      }
   }

   private static long sliceMillis() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.gui != null && minecraft.gui.overlay() instanceof LoadingOverlay) {
         // A reload overlay already owns the frame, so process a slightly larger
         // slice before yielding. This removes thousands of zero-delay browser
         // timer hops without skipping any 26.2 reload listener or model task.
         return LOADING_OVERLAY_SLICE_MILLIS;
      }
      return minecraft != null && minecraft.level != null ? WORLD_SLICE_MILLIS : BOOT_SLICE_MILLIS;
   }
}
