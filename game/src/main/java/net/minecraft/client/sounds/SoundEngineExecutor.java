package net.minecraft.client.sounds;

import java.util.concurrent.locks.LockSupport;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.util.thread.BlockableEventLoop;

public class SoundEngineExecutor extends BlockableEventLoop<Runnable> {
   // Eagler 26.2 (web): the browser runs on a single JS thread with cooperative green
   // threads, so a background "Sound engine" thread can never run while the render
   // thread blocks. SoundEngine.play() does channelAccess.createHandle(...).join(), and
   // that join() would deadlock (TeaVM throws "CompletableFuture observed before
   // completion"). On web we therefore run every audio task INLINE on the caller: the
   // handle future completes synchronously before join() is reached. Desktop keeps the
   // real dedicated thread and is byte-identical.
   private static final boolean IS_WEB =
      net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
         != net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP;
   private Thread thread = this.createThread();
   private volatile boolean shutdown;

   public SoundEngineExecutor() {
      super("Sound executor", false);
   }

   private Thread createThread() {
      if (IS_WEB) {
         // No background thread: tasks run inline (see scheduleExecutables). Report the
         // constructing (render) thread as the running thread so isSameThread() holds.
         return Thread.currentThread();
      }
      Thread thread = new Thread(this::run, "Sound engine");
      thread.setDaemon(true);
      thread.setUncaughtExceptionHandler(
         (t, e) -> Minecraft.getInstance().delayCrash(CrashReport.forThrowable(e, "Uncaught exception on thread: " + t.getName()))
      );
      thread.start();
      return thread;
   }

   @Override
   protected boolean scheduleExecutables() {
      // Force inline (same-thread) execution on web so createHandle().join() sees an
      // already-completed future instead of deadlocking on a thread that cannot run.
      return !IS_WEB && super.scheduleExecutables();
   }

   @Override
   public Runnable wrapRunnable(final Runnable runnable) {
      return runnable;
   }

   @Override
   public void schedule(final Runnable runnable) {
      if (!this.shutdown) {
         super.schedule(runnable);
      }
   }

   @Override
   protected boolean shouldRun(final Runnable task) {
      return !this.shutdown;
   }

   @Override
   protected Thread getRunningThread() {
      return this.thread;
   }

   private void run() {
      while (!this.shutdown) {
         this.managedBlock(() -> this.shutdown);
      }
   }

   @Override
   protected void waitForTasks() {
      LockSupport.park("waiting for tasks");
   }

   public void shutDown() {
      this.shutdown = true;
      this.dropAllTasks();
      if (IS_WEB) {
         // no background thread to interrupt/join (join() would itself deadlock)
         return;
      }
      this.thread.interrupt();

      try {
         this.thread.join();
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
   }

   public void startUp() {
      this.shutdown = false;
      if (IS_WEB) {
         return;
      }
      this.thread = this.createThread();
   }
}
