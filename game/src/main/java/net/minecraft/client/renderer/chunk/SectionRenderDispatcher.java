package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.TlsfAllocator;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.Display;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import net.lax1dude.eaglercraft.v1_8.mesh.MeshJobCodec;
import net.lax1dude.eaglercraft.v1_8.mesh.MeshResultCodec;
import net.lax1dude.eaglercraft.v1_8.mesh.MeshWorkerRuntime;
import net.lax1dude.eaglercraft.v1_8.mesh.SectionSnapshot;
import net.lax1dude.eaglercraft.v1_8.mesh.SectionSnapshotBuilder;
import net.lax1dude.eaglercraft.v1_8.mesh.WorkerRenderSectionRegion;
import net.minecraft.CrashReport;
import net.minecraft.TracingExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class SectionRenderDispatcher {
   public static final int NEARBY_SECTION_DISTANCE_IN_BLOCKS = 32;
   private final SectionTaskDynamicQueue queue = new SectionTaskDynamicQueue();
   private final SectionBufferBuilderPack fixedBuffers;
   private final SectionBufferBuilderPool bufferPool;
   private volatile boolean closed;
   private final TracingExecutor executor;
   private final Consumer<SectionRenderDispatcher.RenderSection> onSectionMeshUpdate;
   private final AtomicReference<Vec3> cameraPosition = new AtomicReference<>(Vec3.ZERO);
   private volatile SectionCompiler sectionCompiler;
   private final StagingBuffer stagingBuffer;
   private final Map<ChunkSectionLayer, SectionRenderDispatcher.SectionUberBuffers> chunkUberBuffers;
   private final ReentrantLock copyLock = new ReentrantLock();
   // ==== Mesh-worker plan Phase C (design doc §4.2/§3.3) ====
   private static final Logger MESH_LOGGER = LogUtils.getLogger();
   /** Monotonic job id for worker-routed compiles (matches results to in-flight tasks). */
   private int nextWorkerJobId = 1;
   /** jobId -> the CompileTask awaiting its worker result (main-thread only). */
   private final Map<Integer, SectionRenderDispatcher.RenderSection.CompileTask> workerInFlight = new HashMap<>();
   /** Results posted back from workers, drained on the render thread each frame. */
   private final ArrayDeque<SectionRenderDispatcher.WorkerResult> workerResultQueue = new ArrayDeque<>();
   /** Scratch buffer pack the render thread reconstructs worker MeshData into (lazy). */
   private SectionBufferBuilderPack meshReconstructBuffers;
   /** Rolling counter for the 1-in-32 runtime byte-parity tripwire (opts meshWorkerVerify). */
   private int verifyCounter;
   // ==== Phase C tuning (Workstream A) — shared per-frame main-thread mesh budget ====
   // Bounds the total main-thread time the worker path may burn in one frame: snapshot+encode
   // (green-thread dispatch), result install (render thread), and the table-encode slice (in
   // LevelRenderer). A block-break or fast camera turn used to burst all dirty sections' snapshots
   // / installs in a single frame → the reported freeze. These caps spread that work across frames
   // (a section's mesh lands ~1-2 frames later, no stall). Reset each frame in onRenderFrameStart.
   /** Normal gameplay ceiling in ms across snapshot-dispatch + install + table-encode. */
   public static final double MESH_FRAME_BUDGET_MS = 4.0;
   private static final double LOADING_MESH_FRAME_BUDGET_MS = 8.0;
   private static final double SCREEN_MESH_FRAME_BUDGET_MS = 2.0;
   private static final double RECOVERY_MESH_FRAME_BUDGET_MS = 1.0;
	private static final double BACKGROUND_MESH_FRAME_BUDGET_MS = 24.0;
	private static final double RESUME_MESH_FRAME_BUDGET_MS = 10.0;
   private static final double LONG_FRAME_THRESHOLD_MS = 25.0;
	private static final int MAX_SNAPSHOTS_PER_FRAME = 2;
   private static final int MAX_INSTALLS_PER_FRAME = 4;
	private static final int MAX_ASYNC_INLINE_PER_FRAME = 1;
   private double frameBudgetRemainingMs = MESH_FRAME_BUDGET_MS;
   private int frameSnapshots;
   private int frameInstalls;
	private int frameAsyncInline;
	private int frameSnapshotLimit = MAX_SNAPSHOTS_PER_FRAME;
	private int frameInstallLimit = MAX_INSTALLS_PER_FRAME;
	private int frameAsyncInlineLimit = MAX_ASYNC_INLINE_PER_FRAME;
	private boolean displayWasActive = true;
   // A4 instrumentation (published to __meshWorkerPool via MeshWorkerRuntime.reportFrameStats).
   private double lastFrameStartMs;
   private double worstFrameMs;
   private double worstWindowStartMs;
   private double snapshotMsThisFrame;
   private double installMsThisFrame;

   private static double meshNowMs() {
      return Util.getNanos() / 1_000_000.0;
   }
   /** Single shared sink handed to the worker pool; routes results back by jobId. */
   private final MeshWorkerRuntime.MeshResultSink workerSink = new MeshWorkerRuntime.MeshResultSink() {
      @Override
      public void onResult(final int jobId, final byte[] resultBytes) {
         SectionRenderDispatcher.this.enqueueWorkerResult(jobId, resultBytes, false, null);
      }

      @Override
      public void onError(final int jobId, final String message) {
         SectionRenderDispatcher.this.enqueueWorkerResult(jobId, null, true, message);
      }
   };

   /** One worker result awaiting the render-thread drain. */
   private static final class WorkerResult {
      final int jobId;
      final byte @Nullable [] blob;
      final boolean error;
      final @Nullable String message;

      WorkerResult(final int jobId, final byte @Nullable [] blob, final boolean error, final @Nullable String message) {
         this.jobId = jobId;
         this.blob = blob;
         this.error = error;
         this.message = message;
      }
   }

   public SectionRenderDispatcher(
      final TracingExecutor executor,
      final RenderBuffers renderBuffers,
      final SectionCompiler sectionCompiler,
      final Consumer<SectionRenderDispatcher.RenderSection> onSectionMeshUpdate
   ) {
      this.onSectionMeshUpdate = onSectionMeshUpdate;
      this.fixedBuffers = renderBuffers.fixedBufferPack();
      this.bufferPool = renderBuffers.sectionBufferPool();
      this.executor = executor;
      this.sectionCompiler = sectionCompiler;
      boolean web = EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP;
      int stagingBufferSize = web ? 25165824 : 102760448;
      GpuDevice gpuDevice = RenderSystem.getDevice();
      this.stagingBuffer = StagingBuffer.create("Chunk", gpuDevice, stagingBufferSize);
      this.chunkUberBuffers = Util.makeEnumMap(ChunkSectionLayer.class, layer -> {
         // WebGL2 keeps lazy, shared arenas, but sizes the first arena to the layer's real
         // density. A fixed 32+8 MiB reservation for all three layers stranded up to 120 MiB
         // of GPU/driver memory in sparse cutout/translucent worlds.
         int vertexBufferHeapSize = web ? switch (layer) {
            case SOLID -> 33554432;
            case CUTOUT -> 16777216;
            case TRANSLUCENT -> 8388608;
         } : 134217728;
         int indexBufferHeapSize = web ? switch (layer) {
            case SOLID -> 8388608;
            case CUTOUT, TRANSLUCENT -> 4194304;
         } : 33554432;
         VertexFormat vertexFormat = layer.pipeline().getVertexFormatBinding(0);
         UberGpuBuffer<SectionMesh> vertexUberBuffer = new UberGpuBuffer<>(
            layer.label(), 32, vertexBufferHeapSize, vertexFormat.getVertexSize(), this.stagingBuffer
         );
         UberGpuBuffer<SectionMesh> indexUberBuffer = new UberGpuBuffer<>(
            layer.label(), 64, indexBufferHeapSize, 8, this.stagingBuffer
         );
         return new SectionRenderDispatcher.SectionUberBuffers(vertexUberBuffer, indexUberBuffer);
      });
   }

   public void setCompiler(final SectionCompiler sectionCompiler) {
      this.sectionCompiler = sectionCompiler;
   }

   private void runTask() {
      if (!this.closed) {
         SectionRenderDispatcher.RenderSection.SectionTask task = this.queue.poll(this.cameraPosition.get());
         if (task != null && !task.isCompleted.get() && !task.isCancelled.get()) {
			boolean budgetInlineCompile = false;
            // ==== Mesh-worker plan Phase C: route async compiles to the worker pool ====
            // compileSync (near/player-affected sections) never reaches runTask — it runs
            // inline on the render thread — so everything polled here is an async task and is
            // eligible for a worker. compileSync therefore stays the automatic inline path,
            // and this branch is skipped whenever the pool is not ready (design doc §4.2/§5).
            if (MeshWorkerRuntime.hasBridge() && task instanceof SectionRenderDispatcher.RenderSection.CompileTask) {
               if (MeshWorkerRuntime.isReady()) {
                  // Phase C tuning (Workstream A1/A3): cap snapshots/frame so a rebuild storm
                  // (block break, fast camera turn) cannot burst N snapshot+encode passes onto
                  // one frame. When the frame budget is spent, defer this task to a later frame
                  // (re-queue, no self-pump); onRenderFrameStart re-kicks the pump next frame.
	                  // Priority changes are polled first, but still obey the frame budget. The old
	                  // exemption let repeated block edits snapshot an unbounded number of sections
	                  // in one frame, freezing entity animation and packet handling for seconds.
	                  if (this.frameSnapshots >= this.frameSnapshotLimit || this.frameBudgetRemainingMs <= 0.0) {
                     this.queue.add(task);
                     return;
                  }
                  SectionRenderDispatcher.RenderSection.CompileTask compileTask = (SectionRenderDispatcher.RenderSection.CompileTask) task;
                  int outcome;
                  try {
                     outcome = compileTask.tryDispatchToWorker();
                  } catch (Throwable t) {
                     MESH_LOGGER.warn("[MeshWorkerPool] snapshot/dispatch threw; falling back to inline", t);
                     MeshWorkerRuntime.reportInlineFallback();
                     outcome = SectionRenderDispatcher.RenderSection.CompileTask.DISPATCH_INLINE;
                  }
                  if (outcome == SectionRenderDispatcher.RenderSection.CompileTask.DISPATCH_ROUTED) {
                     this.executor.execute(this::runTask); // pump the next worker slot
                     return;
                  }
                  if (outcome == SectionRenderDispatcher.RenderSection.CompileTask.DISPATCH_REQUEUE) {
                     this.queue.add(task); // every worker at cap — retry when a slot frees on return
                     return;
                  }
				  if (this.frameAsyncInline >= this.frameAsyncInlineLimit || this.frameBudgetRemainingMs <= 0.0) {
					 this.queue.add(task);
					 return;
				  }
				  budgetInlineCompile = true;
                  // DISPATCH_INLINE (fluid/cancelled/error): fall through to the stock path below.
               } else {
                  // Pool installed but still warming up (or disabled after a crash): this async
                  // compile bakes inline on the green thread this frame.
                  MeshWorkerRuntime.reportInlineFallback();
               }
            }

            try {
			   double inlineStarted = budgetInlineCompile ? meshNowMs() : 0.0;
               SectionBufferBuilderPack buffer = Objects.requireNonNull(this.bufferPool.acquire());
               SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult result = task.doTask(buffer);
               task.isCompleted.set(true);
               if (task instanceof SectionRenderDispatcher.RenderSection.CompileTask compileTask) {
                  compileTask.releaseRetainedInputs();
               }
               if (result == SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.SUCCESSFUL) {
                  buffer.clearAll();
               } else {
                  buffer.discardAll();
               }

               this.bufferPool.release(buffer);
			   if (budgetInlineCompile) {
				  double inlineMs = meshNowMs() - inlineStarted;
				  this.frameAsyncInline++;
				  this.frameBudgetRemainingMs -= inlineMs;
				  net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.meshInlineCompile(
						(long) (inlineMs * 1_000_000.0));
			   }
               this.executor.execute(this::runTask);
            } catch (NullPointerException e) {
               this.queue.add(task);
            } catch (Exception e) {
               Minecraft.getInstance().delayCrash(CrashReport.forThrowable(e, "Batching sections"));
            }
         }
      }
   }

   public void setCameraPosition(final Vec3 cameraPosition) {
      this.cameraPosition.set(cameraPosition);
   }

   /**
    * Phase C tuning (Workstream A) — call once per rendered frame from {@code LevelRenderer},
    * before {@code pumpMeshWorkerPool()} / {@code drainWorkerMeshResults()} and the terrain
    * upload. Rolls the trailing-5s worst-frame window, publishes the A4 telemetry
    * ({@code worstFrameMs}/{@code snapshotMsFrame}/{@code installMsFrame}), resets the shared
    * per-frame mesh budget, and re-kicks the pump for any tasks that were budget-deferred last
    * frame (they were re-queued without self-pumping).
    */
   public void onRenderFrameStart() {
      double now = meshNowMs();
      double previousFrameMs = 0.0;
      if (this.lastFrameStartMs > 0.0) {
         double delta = now - this.lastFrameStartMs;
         previousFrameMs = delta;
         if (now - this.worstWindowStartMs > 5000.0) {
            this.worstWindowStartMs = now;
            this.worstFrameMs = delta; // tumble the window; seed with this frame
         } else if (delta > this.worstFrameMs) {
            this.worstFrameMs = delta;
         }
      } else {
         this.worstWindowStartMs = now;
      }
      this.lastFrameStartMs = now;
	  boolean displayActive = Display.isActive();
	  boolean pageVisible = Display.isPageVisible();
	  boolean resumed = displayActive && pageVisible && !this.displayWasActive;
	  this.displayWasActive = displayActive && pageVisible;
	  MeshWorkerRuntime.reportDisplayState(displayActive, pageVisible, resumed);
	      int resultBacklog;
	      synchronized (this.workerResultQueue) {
	         resultBacklog = this.workerResultQueue.size();
	      }
	      MeshWorkerRuntime.reportFrameStats(previousFrameMs, this.worstFrameMs, this.snapshotMsThisFrame, this.installMsThisFrame,
	         this.queue.size(), resultBacklog, this.workerInFlight.size());
	      int gpuHeaps = 0;
	      int gpuAllocations = 0;
	      int stagedAllocations = 0;
	      long gpuCapacityBytes = 0L;
	      long gpuAllocatedBytes = 0L;
	      for (SectionRenderDispatcher.SectionUberBuffers buffers : this.chunkUberBuffers.values()) {
	         gpuHeaps += buffers.vertexBuffer.eaglerHeapCount() + buffers.indexBuffer.eaglerHeapCount();
	         gpuAllocations += buffers.vertexBuffer.eaglerAllocationCount() + buffers.indexBuffer.eaglerAllocationCount();
	         stagedAllocations += buffers.vertexBuffer.eaglerStagedAllocationCount()
	            + buffers.indexBuffer.eaglerStagedAllocationCount();
	         gpuCapacityBytes += buffers.vertexBuffer.eaglerHeapCapacityBytes()
	            + buffers.indexBuffer.eaglerHeapCapacityBytes();
	         gpuAllocatedBytes += buffers.vertexBuffer.eaglerAllocatedBytes()
	            + buffers.indexBuffer.eaglerAllocatedBytes();
	      }
	      MeshWorkerRuntime.reportMemoryStats(gpuHeaps, gpuAllocations, stagedAllocations,
	         gpuCapacityBytes, gpuAllocatedBytes);
	  this.frameSnapshotLimit = displayActive ? MAX_SNAPSHOTS_PER_FRAME : 12;
	  this.frameInstallLimit = displayActive ? MAX_INSTALLS_PER_FRAME : 32;
	  this.frameAsyncInlineLimit = displayActive ? MAX_ASYNC_INLINE_PER_FRAME : 2;
      boolean loadingTerrain = Minecraft.getInstance().gui.screen() instanceof LevelLoadingScreen;
	  if (!displayActive) {
		 this.frameBudgetRemainingMs = BACKGROUND_MESH_FRAME_BUDGET_MS;
	  } else if (resumed) {
		 // The hidden tab accumulated packet/mesh work while Chrome throttled its visual
		 // callbacks. Spend one bounded catch-up frame instead of treating the long hidden
		 // interval as a rendering stall and reducing the budget to 1 ms.
		 this.frameBudgetRemainingMs = RESUME_MESH_FRAME_BUDGET_MS;
      } else if (loadingTerrain) {
         this.frameBudgetRemainingMs = LOADING_MESH_FRAME_BUDGET_MS;
      } else if (previousFrameMs > LONG_FRAME_THRESHOLD_MS) {
         this.frameBudgetRemainingMs = RECOVERY_MESH_FRAME_BUDGET_MS;
      } else if (Minecraft.getInstance().gui.screen() != null) {
         this.frameBudgetRemainingMs = SCREEN_MESH_FRAME_BUDGET_MS;
      } else {
         this.frameBudgetRemainingMs = MESH_FRAME_BUDGET_MS;
      }
      this.frameSnapshots = 0;
      this.frameInstalls = 0;
	  this.frameAsyncInline = 0;
      this.snapshotMsThisFrame = 0.0;
      this.installMsThisFrame = 0.0;
      // Budget-deferred dispatch tasks are sitting in the queue without a pending runTask; make
      // sure the pump advances this frame so they are not stranded until the next dirty section.
      if (!this.closed && MeshWorkerRuntime.isReady() && this.queue.size() > 0) {
         this.executor.execute(this::runTask);
      }
   }

   /** Remaining shared per-frame mesh budget in ms — LevelRenderer's table-encode slice honours
    *  it so the one-time ramp encode shares the same ceiling as steady-state install/dispatch. */
   public double frameBudgetRemainingMs() {
      return Math.max(0.0, this.frameBudgetRemainingMs);
   }

   /** Charge {@code ms} of main-thread mesh work against this frame's shared budget. */
   public void consumeFrameBudget(final double ms) {
      this.frameBudgetRemainingMs -= ms;
   }

   /** Worker pool sink callback (may fire outside the render thread's active step): stash the
    *  result for the next {@link #drainWorkerMeshResults()} on the render thread. */
   private void enqueueWorkerResult(final int jobId, final byte @Nullable [] blob, final boolean error,
         final @Nullable String message) {
      if (this.closed) {
         return;
      }
      synchronized (this.workerResultQueue) {
         if (this.closed) {
            return;
         }
         SectionRenderDispatcher.WorkerResult result = new SectionRenderDispatcher.WorkerResult(jobId, blob, error, message);
         SectionRenderDispatcher.RenderSection.CompileTask task = this.workerInFlight.get(jobId);
         if (task != null && task.playerPriority()) {
            this.workerResultQueue.addFirst(result);
         } else {
            this.workerResultQueue.addLast(result);
         }
      }
   }

   /**
    * Mesh-worker plan Phase C+D — drain every worker result that has come back, reconstructing
    * each into a {@code CompiledSectionMesh} and staging it through the untouched upload sink.
    * MUST be called on the render thread (it touches the uber buffers) right before
    * {@link #uploadTerrainBuffersToGpu()} each frame, so the subsequent staging flush makes the
    * meshes live. Running here (not in the async sink callback) guarantees
    * {@code RenderSystem.isOnRenderThread()} is true, so {@code addSectionBuffersToUberBuffer}
    * flushes inline when the uber buffer fills instead of spin-waiting (design doc §3.3/§3.4).
    */
   public void drainWorkerMeshResults() {
      // Phase C tuning (Workstream A1/A2): install at most MAX_INSTALLS_PER_FRAME successful
      // results (or until the shared frame budget is spent), leaving the rest queued for the
      // next frame — decodeToResults + the ByteBufferBuilder copy + addSectionBuffersToUberBuffer
      // are the render-thread burst that stacked with the snapshot burst. Error results are cheap
      // (they only re-schedule) and are always processed so a stale/failed job is never stranded.
      while (true) {
         SectionRenderDispatcher.WorkerResult wr = null;
         synchronized (this.workerResultQueue) {
            if (this.workerResultQueue.isEmpty()) {
               return;
            }
			boolean capped = this.frameInstalls >= this.frameInstallLimit || this.frameBudgetRemainingMs <= 0.0;
            if (!capped) {
               wr = this.workerResultQueue.poll();
            } else {
	               // Over budget, only cheap error re-schedules may bypass the cap. Priority
	               // installs remain first in queue but cannot create an unbounded upload burst.
               java.util.Iterator<SectionRenderDispatcher.WorkerResult> it = this.workerResultQueue.iterator();
               while (it.hasNext()) {
                  SectionRenderDispatcher.WorkerResult cand = it.next();
                  if (cand.error) {
                     it.remove();
                     wr = cand;
                     break;
                  }
               }
               if (wr == null) {
                  return; // nothing urgent — defer the rest to next frame
               }
            }
         }
         SectionRenderDispatcher.RenderSection.CompileTask task = this.workerInFlight.remove(wr.jobId);
         if (task == null) {
            continue; // task was replaced/cancelled and its entry already cleared
         }
         task.workerJobId = 0;
         double installStart = meshNowMs();
         if (wr.error) {
            // Worker failed this job (the pool disables itself on a hard worker error). Re-run
            // it through the normal scheduler; with the pool now not-ready it bakes inline.
            MESH_LOGGER.warn("[MeshWorkerPool] job {} failed in worker ({}), re-scheduling inline", wr.jobId,
               wr.message);
            if (!task.isCancelled.get() && !task.isCompleted.get()) {
               this.schedule(task);
            }
            continue;
         }
         try {
            if (MeshWorkerRuntime.verifyEnabled() && (++this.verifyCounter & 31) == 0) {
               try {
                  boolean mismatch = task.verifyAgainstInline(wr.blob);
                  MeshWorkerRuntime.reportVerify(mismatch);
               } catch (Throwable t) {
                  // A broken tripwire must fail LOUDLY: the reason goes into the line itself
                  // (some slf4j backends drop the throwable arg) plus a raw stack for the JS
                  // console, and it counts as a mismatch so verifyMismatches surfaces it.
                  MESH_LOGGER.error("[MeshWorkerVerify] verify pass THREW (counted as mismatch): {}", t.toString(), t);
                  t.printStackTrace();
                  MeshWorkerRuntime.reportVerify(true);
               }
            }
            task.finishFromWorker(wr.blob);
         } catch (Throwable t) {
            MESH_LOGGER.error("[MeshWorkerPool] failed to install worker result for job {}", wr.jobId, t);
            if (!task.isCancelled.get() && !task.isCompleted.get()) {
               this.schedule(task); // recover by baking inline
            }
         }
         // Charge this install against the shared frame budget (Workstream A1).
         double dt = meshNowMs() - installStart;
         this.frameInstalls++;
         this.installMsThisFrame += dt;
         this.frameBudgetRemainingMs -= dt;
         this.executor.execute(this::runTask); // a slot freed — pump the queue
      }
   }

   /** Lazily-allocated scratch pack for reconstructing worker MeshData (worker mode only). */
   private SectionBufferBuilderPack meshReconstructBuffers() {
      if (this.meshReconstructBuffers == null) {
         this.meshReconstructBuffers = new SectionBufferBuilderPack();
      }
      return this.meshReconstructBuffers;
   }

   public SectionRenderDispatcher.@Nullable RenderSectionBufferSlice getRenderSectionSlice(final SectionMesh sectionMesh, final ChunkSectionLayer layer) {
      SectionRenderDispatcher.SectionUberBuffers uberBuffers = this.chunkUberBuffers.get(layer);
      TlsfAllocator.Allocation vertexSlice = uberBuffers.vertexBuffer.getAllocation(sectionMesh);
      if (vertexSlice == null) {
         return null;
      }

      long vertexBufferOffset = vertexSlice.getOffsetFromHeap();
      TlsfAllocator.Allocation indexSlice = uberBuffers.indexBuffer.getAllocation(sectionMesh);
      long indexBufferOffset = 0L;
      GpuBuffer indexBuffer = null;
      if (indexSlice != null) {
         indexBufferOffset = indexSlice.getOffsetFromHeap();
         indexBuffer = uberBuffers.indexBuffer.getGpuBuffer(indexSlice);
      }

      return new SectionRenderDispatcher.RenderSectionBufferSlice(
         uberBuffers.vertexBuffer.getGpuBuffer(vertexSlice), vertexBufferOffset, indexBuffer, indexBufferOffset
      );
   }

   public void lock() {
      this.copyLock.lock();
   }

   public void unlock() {
      this.copyLock.unlock();
   }

   public void uploadTerrainBuffersToGpu() {
      GpuDevice device = RenderSystem.getDevice();

      try (StagingBuffer.Uploader uploader = this.stagingBuffer.startUploading(device.createCommandEncoder())) {
         for (SectionRenderDispatcher.SectionUberBuffers buffers : this.chunkUberBuffers.values()) {
            boolean performedBufferResize = buffers.vertexBuffer.uploadStagedAllocations(device, uploader);
            buffers.indexBuffer.uploadStagedAllocations(device, uploader);
            if (performedBufferResize) {
               break;
            }
         }
      }
   }

   private void schedule(final SectionRenderDispatcher.RenderSection.SectionTask task) {
      if (!this.closed) {
         this.queue.add(task);
         this.executor.execute(this::runTask);
      }
   }

   public void clearCompileQueue() {
      this.queue.clear();
   }

   public boolean isQueueEmpty() {
      return this.queue.size() == 0;
   }

   public void dispose() {
      this.closed = true;
      this.clearCompileQueue();
      for (SectionRenderDispatcher.RenderSection.CompileTask task : new ArrayList<>(this.workerInFlight.values())) {
         task.cancel();
      }
      this.workerInFlight.clear();
      synchronized (this.workerResultQueue) {
         this.workerResultQueue.clear();
      }
      if (this.meshReconstructBuffers != null) {
         this.meshReconstructBuffers.close();
         this.meshReconstructBuffers = null;
      }
      this.copyLock.lock();

      try {
         for (SectionRenderDispatcher.SectionUberBuffers buffers : this.chunkUberBuffers.values()) {
            buffers.vertexBuffer.close();
            buffers.indexBuffer.close();
         }

         this.stagingBuffer.close();
      } finally {
         this.copyLock.unlock();
      }
   }

   @VisibleForDebug
   public String getStats() {
      return String.format(Locale.ROOT, "pC: %03d, aB: %02d", this.queue.size(), this.bufferPool.getFreeBufferCount());
   }

   @VisibleForDebug
   public int getCompileQueueSize() {
      return this.queue.size();
   }

   @VisibleForDebug
   public int getFreeBufferCount() {
      return this.bufferPool.getFreeBufferCount();
   }

   public class RenderSection implements RotatingSectionStorage.Value {
      public final int index;
      public final AtomicReference<SectionMesh> sectionMesh = new AtomicReference<>(CompiledSectionMesh.UNCOMPILED);
      private SectionRenderDispatcher.RenderSection.@Nullable CompileTask lastCompileTask;
      private SectionRenderDispatcher.RenderSection.@Nullable ResortTransparencyTask lastResortTransparencyTask;
      private AABB bb;
      private volatile long sectionNode = SectionPos.asLong(-1, -1, -1);
      private final BlockPos.MutableBlockPos renderOrigin = new BlockPos.MutableBlockPos(-1, -1, -1);
      private long uploadedTime;
      private long fadeDuration;
      private boolean wasPreviouslyEmpty;

      public RenderSection(final int index, final long sectionNode) {
         this.index = index;
         this.setSectionNode(sectionNode);
      }

      public float getVisibility(final long now) {
         long elapsed = now - this.uploadedTime;
         return elapsed >= this.fadeDuration ? 1.0F : (float)elapsed / (float)this.fadeDuration;
      }

      public void setFadeDuration(final long fadeDuration) {
         this.fadeDuration = fadeDuration;
      }

      public void setWasPreviouslyEmpty(final boolean wasPreviouslyEmpty) {
         this.wasPreviouslyEmpty = wasPreviouslyEmpty;
      }

      public boolean wasPreviouslyEmpty() {
         return this.wasPreviouslyEmpty;
      }

      public AABB getBoundingBox() {
         return this.bb;
      }

      @Override
      public void setSectionNode(final long sectionNode) {
         this.reset();
         this.sectionNode = sectionNode;
         int x = SectionPos.sectionToBlockCoord(SectionPos.x(sectionNode));
         int y = SectionPos.sectionToBlockCoord(SectionPos.y(sectionNode));
         int z = SectionPos.sectionToBlockCoord(SectionPos.z(sectionNode));
         this.renderOrigin.set(x, y, z);
         this.bb = new AABB(x, y, z, x + 16, y + 16, z + 16);
      }

      public SectionMesh getSectionMesh() {
         return this.sectionMesh.get();
      }

      public void reset() {
         this.cancelTasks();
         SectionMesh mesh = this.sectionMesh.getAndSet(CompiledSectionMesh.UNCOMPILED);
         SectionRenderDispatcher.this.copyLock.lock();

         try {
            this.releaseSectionMesh(mesh);
         } finally {
            SectionRenderDispatcher.this.copyLock.unlock();
         }

         this.uploadedTime = 0L;
         this.wasPreviouslyEmpty = false;
      }

      public BlockPos getRenderOrigin() {
         return this.renderOrigin;
      }

      @Override
      public long getSectionNode() {
         return this.sectionNode;
      }

      public long getNeighborSectionNode(final Direction direction) {
         return SectionPos.offset(this.sectionNode, direction);
      }

      public void resortTransparency() {
         if (this.getSectionMesh() instanceof CompiledSectionMesh mesh) {
            this.lastResortTransparencyTask = new SectionRenderDispatcher.RenderSection.ResortTransparencyTask(mesh);
            SectionRenderDispatcher.this.schedule(this.lastResortTransparencyTask);
         }
      }

      public boolean hasTranslucentGeometry() {
         return this.getSectionMesh().hasTranslucentGeometry();
      }

      public boolean transparencyResortingScheduled() {
         return this.lastResortTransparencyTask != null && !this.lastResortTransparencyTask.isCompleted.get();
      }

      private void cancelTasks() {
         if (this.lastCompileTask != null) {
            this.lastCompileTask.cancel();
            this.lastCompileTask = null;
         }

         if (this.lastResortTransparencyTask != null) {
            this.lastResortTransparencyTask.cancel();
            this.lastResortTransparencyTask = null;
         }
      }

      private void onCompileTaskFinished(final SectionRenderDispatcher.RenderSection.CompileTask task) {
         if (this.lastCompileTask == task) {
            this.lastCompileTask = null;
         }
      }

      private SectionRenderDispatcher.RenderSection.SectionTask createCompileTask(final RenderSectionRegion region) {
         this.cancelTasks();
         boolean isRecompile = this.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED;
         this.lastCompileTask = new SectionRenderDispatcher.RenderSection.CompileTask(region, isRecompile);
         return this.lastCompileTask;
      }

      public void compileAsync(final RenderSectionRegion region) {
         SectionRenderDispatcher.RenderSection.SectionTask task = this.createCompileTask(region);
         SectionRenderDispatcher.this.schedule(task);
      }

      /**
       * Async compile for a player-affected rebuild (block break/place). The task jumps the
       * distance queue, while the shared capacity and frame budgets keep repeated edits from
       * starving terrain work or growing the worker queue without bound.
       */
      public void compileAsyncPriority(final RenderSectionRegion region) {
         SectionRenderDispatcher.RenderSection.SectionTask task = this.createCompileTask(region);
         task.playerPriority = true;
         SectionRenderDispatcher.this.schedule(task);
      }

      public void compileSync(final RenderSectionRegion region) {
         SectionRenderDispatcher.RenderSection.SectionTask task = this.createCompileTask(region);
         try {
            task.doTask(SectionRenderDispatcher.this.fixedBuffers);
            task.isCompleted.set(true);
         } finally {
            ((SectionRenderDispatcher.RenderSection.CompileTask)task).releaseRetainedInputs();
         }
      }

      private SectionMesh setSectionMesh(final SectionMesh sectionMesh) {
         SectionMesh oldMesh = this.sectionMesh.getAndSet(sectionMesh);
         SectionRenderDispatcher.this.onSectionMeshUpdate.accept(this);
         if (this.uploadedTime == 0L) {
            this.uploadedTime = Util.getMillis();
         }

         return oldMesh;
      }

      private void releaseSectionMesh(final SectionMesh oldMesh) {
         oldMesh.close();

         for (SectionRenderDispatcher.SectionUberBuffers buffers : SectionRenderDispatcher.this.chunkUberBuffers.values()) {
            buffers.vertexBuffer.removeAllocation(oldMesh);
            buffers.indexBuffer.removeAllocation(oldMesh);
         }
      }

      private VertexSorting createVertexSorting(final SectionPos sectionPos, final Vec3 cameraPos) {
         return VertexSorting.byDistance(
            (float)(cameraPos.x - sectionPos.minBlockX()), (float)(cameraPos.y - sectionPos.minBlockY()), (float)(cameraPos.z - sectionPos.minBlockZ())
         );
      }

      private void checkSectionMesh(final CompiledSectionMesh compiledSectionMesh) {
         boolean allBuffersUpdated = true;

         for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            SectionMesh.SectionDraw draw = compiledSectionMesh.getSectionDraw(layer);
            if (draw != null) {
               allBuffersUpdated &= compiledSectionMesh.isIndexBufferUploaded(layer);
               allBuffersUpdated &= compiledSectionMesh.isVertexBufferUploaded(layer);
            }
         }

         if (allBuffersUpdated && this.sectionMesh.get() != compiledSectionMesh) {
            SectionMesh oldMesh = this.setSectionMesh(compiledSectionMesh);
            this.releaseSectionMesh(oldMesh);
         }
      }

      private void vertexBufferUploadCallback(final CompiledSectionMesh sectionMesh, final ChunkSectionLayer layer) {
         sectionMesh.setVertexBufferUploaded(layer);
         this.checkSectionMesh(sectionMesh);
      }

      private void indexBufferUploadCallback(final CompiledSectionMesh sectionMesh, final ChunkSectionLayer layer, final boolean sortedIndexBuffer) {
         sectionMesh.setIndexBufferUploaded(layer);
         if (!sortedIndexBuffer) {
            this.checkSectionMesh(sectionMesh);
         }
      }

      private boolean addSectionBuffersToUberBuffer(
         final ChunkSectionLayer layer, final CompiledSectionMesh key, final @Nullable ByteBuffer vertexBuffer, final @Nullable ByteBuffer indexBuffer
      ) {
         boolean success = true;
         SectionRenderDispatcher.this.copyLock.lock();

         try {
            SectionMesh.SectionDraw draw = key.getSectionDraw(layer);
            if (draw != null) {
               SectionRenderDispatcher.SectionUberBuffers sectionBuffers = SectionRenderDispatcher.this.chunkUberBuffers.get(layer);
               assert sectionBuffers != null;
               if (vertexBuffer != null) {
                  UberGpuBuffer.UploadCallback<CompiledSectionMesh> callback = mesh -> this.vertexBufferUploadCallback(mesh, layer);
                  success &= sectionBuffers.vertexBuffer.addAllocation(key, callback, vertexBuffer);
               }

               if (indexBuffer != null) {
                  boolean sortedIndexBuffer = vertexBuffer == null;
                  UberGpuBuffer.UploadCallback<CompiledSectionMesh> callback = mesh -> this.indexBufferUploadCallback(mesh, layer, sortedIndexBuffer);
                  success &= sectionBuffers.indexBuffer.addAllocation(key, callback, indexBuffer);
               } else {
                  key.setIndexBufferUploaded(layer);
               }
            }

            if (!success && RenderSystem.isOnRenderThread()) {
               SectionRenderDispatcher.this.uploadTerrainBuffersToGpu();
            }
         } finally {
            SectionRenderDispatcher.this.copyLock.unlock();
         }

         return success;
      }

      private class CompileTask extends SectionRenderDispatcher.RenderSection.SectionTask {
         private final RenderSectionRegion region;
         /** Non-zero while the current compile is owned by a mesh worker. */
         private int workerJobId;
         /** The encoded snapshot sent to the worker, kept only when meshWorkerVerify is on so
          *  the 1-in-32 tripwire can reproduce the worker's exact inputs (Phase C §6.6). */
         private byte @Nullable [] verifyJobBytes;

         public CompileTask(final RenderSectionRegion region, final boolean isRecompile) {
            super(isRecompile);
            this.region = region;
         }

         @Override
         public SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult doTask(final SectionBufferBuilderPack buffers) {
            if (this.isCancelled.get()) {
               return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED;
            }

            long sectionNode = RenderSection.this.sectionNode;
            SectionPos sectionPos = SectionPos.of(sectionNode);
            if (this.isCancelled.get()) {
               return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED;
            }

            Vec3 cameraPos = SectionRenderDispatcher.this.cameraPosition.get();

            SectionCompiler.Results results;
            try (Zone ignored = Profiler.get().zone("Compile Section")) {
               results = SectionRenderDispatcher.this.sectionCompiler
                  .compile(sectionPos, this.region, RenderSection.this.createVertexSorting(sectionPos, cameraPos), buffers);
            }

            return this.installResults(results, sectionNode, cameraPos);
         }

         /**
          * Wrap a {@code SectionCompiler.Results} into a {@code CompiledSectionMesh} and stage it
          * through the uber-buffer upload sink. Extracted from {@link #doTask} so the mesh-worker
          * path ({@link #finishFromWorker}) reuses the exact same, untouched install/upload tail
          * (design doc §3.3). Identical semantics to the original doTask body: empty-layer fast
          * swap, else per-layer {@code addSectionBuffersToUberBuffer} with off-render-thread
          * spin-wait back-pressure and cancellation-mid-upload cleanup.
          */
         private SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult installResults(
               final SectionCompiler.Results results, final long sectionNode, final Vec3 cameraPos) {
            TranslucencyPointOfView translucencyPointOfView = TranslucencyPointOfView.of(cameraPos, sectionNode);
            CompiledSectionMesh compiledSectionMesh = new CompiledSectionMesh(translucencyPointOfView, results);
            if (results.renderedLayers.isEmpty()) {
               SectionMesh oldMesh = RenderSection.this.setSectionMesh(compiledSectionMesh);
               SectionRenderDispatcher.this.copyLock.lock();

               try {
                  RenderSection.this.releaseSectionMesh(oldMesh);
               } finally {
                  SectionRenderDispatcher.this.copyLock.unlock();
               }

               return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.SUCCESSFUL;
            } else {
               for (Entry<ChunkSectionLayer, MeshData> entry : results.renderedLayers.entrySet()) {
                  MeshData meshData = entry.getValue();
                  boolean success = false;

                  while (!success) {
                     if (this.isCancelled.get()) {
                        results.release();
                        SectionRenderDispatcher.this.copyLock.lock();

                        try {
                           RenderSection.this.releaseSectionMesh(compiledSectionMesh);
                        } finally {
                           SectionRenderDispatcher.this.copyLock.unlock();
                        }

                        return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED;
                     }

                     success = RenderSection.this.addSectionBuffersToUberBuffer(
                        entry.getKey(), compiledSectionMesh, meshData.vertexBuffer(), meshData.indexBuffer()
                     );
                     if (!success && !RenderSystem.isOnRenderThread()) {
                        Thread.onSpinWait();
                     }
                  }

                  meshData.close();
               }

               return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.SUCCESSFUL;
            }
         }

         // ==== Mesh-worker plan Phase C ====
         static final int DISPATCH_ROUTED = 0;  // snapshot posted to a worker; result pending
         static final int DISPATCH_REQUEUE = 1; // every worker at cap; re-queue and retry later
         static final int DISPATCH_INLINE = 2;  // not workerable (fluid/cancelled/error) — inline

         /** Snapshot this section on the main thread and post it to the least-loaded worker.
          *  Returns one of DISPATCH_ROUTED/REQUEUE/INLINE. Runs on the green (background)
          *  thread, exactly where doTask would otherwise run. */
         int tryDispatchToWorker() {
            if (this.isCancelled.get()) {
               return DISPATCH_INLINE; // let the inline path drop it via its isCancelled checks
            }
            // Do not snapshot/encode the same 18^3 section every frame while worker mailboxes are
            // full. Capacity is checked on the same JS thread as submit, so it cannot be stolen
            // between this gate and the transfer.
            if (!MeshWorkerRuntime.hasCapacity(this.playerPriority())) {
               return DISPATCH_REQUEUE;
            }
            long sectionNode = RenderSection.this.sectionNode;
            SectionPos sectionPos = SectionPos.of(sectionNode);
            int sx = sectionPos.x();
            int sy = sectionPos.y();
            int sz = sectionPos.z();
            // Fluid geometry still depends on the stock client-side FluidRenderer path.
            // SectionSnapshotBuilder already exposes the exact, palette-backed section test;
            // keep only those sections inline instead of sending them to an isolate which can
            // otherwise return a valid-looking mesh with the fluid surface omitted.
            if (SectionSnapshotBuilder.sectionHasFluid(this.region, sx, sy, sz)) {
               MeshWorkerRuntime.reportFluidInline();
               return DISPATCH_INLINE;
            }
            SectionCompiler compiler = SectionRenderDispatcher.this.sectionCompiler;
            boolean ao = compiler.ambientOcclusion();
            boolean cutout = compiler.cutoutLeaves();
            Vec3 cameraPos = SectionRenderDispatcher.this.cameraPosition.get();
            // Phase C tuning (Workstream A1): the snapshot build (18³ block reads + 16³×4 tint
            // reads) + codec encode is the per-job main-thread cost we budget. Time it and charge
            // the shared frame budget so runTask's snapshot cap self-regulates the burst.
            double snapStart = meshNowMs();
            SectionSnapshot snapshot = SectionSnapshotBuilder.build(this.region, sx, sy, sz, ao, cutout, false,
               this.isRecompile(), cameraPos.x, cameraPos.y, cameraPos.z);
            double captureEnd = meshNowMs();
            int jobId = SectionRenderDispatcher.this.nextWorkerJobId++;
            byte[] jobBytes;
            boolean tableCovered;
            try {
               snapshot.jobId = jobId;
               jobBytes = MeshJobCodec.encode(snapshot);
               tableCovered = MeshWorkerRuntime.ensureTableCoverage(snapshot.blockStatePalette);
            } finally {
               SectionSnapshotBuilder.release(snapshot);
            }
            net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.meshSnapshot(
               (long) ((captureEnd - snapStart) * 1_000_000.0),
               (long) ((meshNowMs() - captureEnd) * 1_000_000.0));
            // c87 fix 2: the broadcast table is scoped to appearing states — make sure every
            // palette id in this snapshot is covered, broadcasting one lazy delta for the new
            // ones first (per-worker message order lands the merge before this job). If no
            // delta encoder is installed (bake not completed yet) the section bakes inline.
            if (!tableCovered) {
               MeshWorkerRuntime.reportInlineFallback();
               return DISPATCH_INLINE;
            }
            double snapDt = meshNowMs() - snapStart;
            SectionRenderDispatcher.this.frameSnapshots++;
            SectionRenderDispatcher.this.snapshotMsThisFrame += snapDt;
            SectionRenderDispatcher.this.frameBudgetRemainingMs -= snapDt;
            // c81 tripwire fix (hazard H2): MeshWorkerPool.submit posts jobBytes' OWN backing
            // storage with transfer (TeaVM Int8Array.fromJavaArray WRAPS the byte[], it does
            // not copy — the documented c76 hazard), so after a successful submit jobBytes is
            // NEUTERED. Retaining it for the tripwire meant MeshJobCodec.decode later read a
            // detached buffer and threw bad-magic — the c81 "verify pass threw" x3
            // (jobs=105 -> exactly three 1-in-32 checks). Clone the exact wire bytes BEFORE
            // the transfer happens.
            byte[] verifyCopy = MeshWorkerRuntime.verifyEnabled() ? jobBytes.clone() : null;
            boolean accepted = MeshWorkerRuntime.submit(jobId, jobBytes, SectionRenderDispatcher.this.workerSink,
               this.playerPriority());
            if (!accepted) {
               return DISPATCH_REQUEUE;
            }
            this.verifyJobBytes = verifyCopy; // exact worker inputs for the 1-in-32 tripwire
            this.workerJobId = jobId;
            SectionRenderDispatcher.this.workerInFlight.put(jobId, this);
            return DISPATCH_ROUTED;
         }

         /** Rebuild {@code Results} from a worker blob and install it. Render thread only. */
         void finishFromWorker(final byte[] blob) {
            this.workerJobId = 0;
            if (this.isCancelled.get()) {
               // Cancelled while in flight: discard the worker output (design doc §4.2).
               this.isCompleted.set(true);
               this.releaseRetainedInputs();
               return;
            }
            long sectionNode = RenderSection.this.sectionNode;
            SectionPos sectionPos = SectionPos.of(sectionNode);
            Vec3 cameraPos = SectionRenderDispatcher.this.cameraPosition.get();
            VertexSorting sorting = RenderSection.this.createVertexSorting(sectionPos, cameraPos);
            List<BlockEntity> blockEntities = this.collectBlockEntities(sectionPos);
            SectionBufferBuilderPack pack = SectionRenderDispatcher.this.meshReconstructBuffers();
            pack.discardAll();
            SectionCompiler.Results results = MeshResultCodec.decodeToResults(blob, sorting, blockEntities, pack);
            this.installResults(results, sectionNode, cameraPos);
            this.isCompleted.set(true);
            this.releaseRetainedInputs();
         }

         /**
          * A completed compile no longer needs the 27-section CPU snapshot captured by
          * {@link RenderSectionRegion}. Drop the RenderSection's final reference to this task
          * as soon as its mesh is installed so traveled-through chunks can be reclaimed instead
          * of accumulating in the browser heap until the render ring is recycled.
          */
         private void releaseRetainedInputs() {
            this.verifyJobBytes = null;
            RenderSection.this.onCompileTaskFinished(this);
         }

         /** Reproduce the worker's <b>exact inputs</b> — decode the snapshot that was sent, wrap
          *  it in a {@link WorkerRenderSectionRegion}, and compile with the trusted main-thread
          *  models + the snapshot's own camera-relative sorting — then byte-compare against the
          *  worker's result blob. This isolates the worker environment (hydrated model table,
          *  registry order, H9 statics) from camera drift / live-level changes, so any mismatch
          *  is a real worker bug — the production tripwire for section types the one-shot Phase B
          *  test never saw. Render thread only (uses fixedBuffers). Returns true on mismatch. */
         boolean verifyAgainstInline(final byte[] workerBlob) {
            if (this.verifyJobBytes == null) {
               return false;
            }
            SectionSnapshot snapshot = MeshJobCodec.decode(this.verifyJobBytes);
            WorkerRenderSectionRegion verifyRegion = new WorkerRenderSectionRegion(snapshot);
            SectionPos sectionPos = SectionPos.of(snapshot.sectionX, snapshot.sectionY, snapshot.sectionZ);
            VertexSorting sorting = VertexSorting.byDistance(snapshot.cameraRelX, snapshot.cameraRelY,
               snapshot.cameraRelZ);
            SectionBufferBuilderPack pack = SectionRenderDispatcher.this.fixedBuffers;
            pack.discardAll();
            byte[] refBlob;
            SectionCompiler.Results ref = SectionRenderDispatcher.this.sectionCompiler
               .compile(sectionPos, verifyRegion, sorting, pack);
            try {
               refBlob = MeshResultCodec.encode(ref);
            } finally {
               ref.release();
               pack.discardAll();
            }
            boolean mismatch = !java.util.Arrays.equals(refBlob, workerBlob);
            if (mismatch) {
               MESH_LOGGER.error("[MeshWorkerVerify] MISMATCH at {} inline={}B worker={}B", sectionPos,
                  refBlob.length, workerBlob.length);
               try {
                  for (String line : MeshResultCodec.diagnoseMismatch(refBlob, workerBlob)) {
                     MESH_LOGGER.error("[MeshWorkerVerify] diag {}", line);
                  }
               } catch (Throwable t) {
                  MESH_LOGGER.error("[MeshWorkerVerify] diag threw", t);
               }
            }
            return mismatch;
         }

         /** Re-derive the renderable block entities main-side (design doc §1.5), mirroring
          *  SectionCompiler.handleBlockEntity — the worker never returns them. */
         private List<BlockEntity> collectBlockEntities(final SectionPos sectionPos) {
            List<BlockEntity> out = new ArrayList<>();
            BlockPos origin = sectionPos.origin();
            BlockPos max = origin.offset(15, 15, 15);
            for (BlockPos pos : BlockPos.betweenClosed(origin, max)) {
               BlockState state = this.region.getBlockState(pos);
               if (!state.isAir() && state.hasBlockEntity()) {
                  BlockEntity be = this.region.getBlockEntity(pos);
                  if (be != null) {
                     out.add(be);
                  }
               }
            }
            return out;
         }

         @Override
         public void cancel() {
            if (this.isCancelled.compareAndSet(false, true)) {
               SectionRenderDispatcher.this.queue.discard(this);
               this.verifyJobBytes = null;
               int jobId = this.workerJobId;
               if (jobId != 0) {
                  this.workerJobId = 0;
                  if (SectionRenderDispatcher.this.workerInFlight.get(jobId) == this) {
                     SectionRenderDispatcher.this.workerInFlight.remove(jobId);
                  }
                  MeshWorkerRuntime.cancel(jobId);
               }
            }
         }
      }

      private class ResortTransparencyTask extends SectionRenderDispatcher.RenderSection.SectionTask {
         private final CompiledSectionMesh compiledSectionMesh;

         public ResortTransparencyTask(final CompiledSectionMesh compiledSectionMesh) {
            super(true);
            this.compiledSectionMesh = compiledSectionMesh;
         }

         @Override
         public SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult doTask(final SectionBufferBuilderPack buffers) {
            if (this.isCancelled.get()) {
               return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED;
            }

            MeshData.SortState state = this.compiledSectionMesh.getTransparencyState();
            if (state != null && !this.compiledSectionMesh.isEmpty(ChunkSectionLayer.TRANSLUCENT)) {
               Vec3 cameraPos = SectionRenderDispatcher.this.cameraPosition.get();
               long sectionNode = RenderSection.this.sectionNode;
               VertexSorting vertexSorting = RenderSection.this.createVertexSorting(SectionPos.of(sectionNode), cameraPos);
               TranslucencyPointOfView translucencyPointOfView = TranslucencyPointOfView.of(cameraPos, sectionNode);
               if (!this.compiledSectionMesh.isDifferentPointOfView(translucencyPointOfView) && !translucencyPointOfView.isAxisAligned()) {
                  return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED;
               }

               ByteBufferBuilder.Result indexBuffer = state.buildSortedIndexBuffer(buffers.buffer(ChunkSectionLayer.TRANSLUCENT), vertexSorting);
               if (indexBuffer == null) {
                  return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED;
               }

               boolean success = false;

               while (!success) {
                  if (this.isCancelled.get()) {
                     indexBuffer.close();
                     return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED;
                  }

                  success = RenderSection.this.addSectionBuffersToUberBuffer(
                     ChunkSectionLayer.TRANSLUCENT, this.compiledSectionMesh, null, indexBuffer.byteBuffer()
                  );
                  if (!success && !RenderSystem.isOnRenderThread()) {
                     Thread.onSpinWait();
                  }
               }

               indexBuffer.close();
               this.compiledSectionMesh.setTranslucencyPointOfView(translucencyPointOfView);
               return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.SUCCESSFUL;
            } else {
               return SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult.CANCELLED;
            }
         }

         @Override
         public void cancel() {
            this.isCancelled.set(true);
            SectionRenderDispatcher.this.queue.discard(this);
         }
      }

      public abstract class SectionTask {
         protected final AtomicBoolean isCancelled = new AtomicBoolean(false);
         protected final AtomicBoolean isCompleted = new AtomicBoolean(false);
         private final boolean isRecompile;
         /** Player-affected rebuild (block break/place): polled ahead of normal terrain work. */
         boolean playerPriority;

         public SectionTask(final boolean isRecompile) {
            this.isRecompile = isRecompile;
         }

         public boolean playerPriority() {
            return this.playerPriority;
         }

         public abstract SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult doTask(final SectionBufferBuilderPack buffers);

         public abstract void cancel();

         public boolean isRecompile() {
            return this.isRecompile;
         }

         public BlockPos getRenderOrigin() {
            return RenderSection.this.renderOrigin;
         }

         public enum SectionTaskResult {
            SUCCESSFUL,
            CANCELLED;
         }
      }
   }

   public record RenderSectionBufferSlice(GpuBuffer vertexBuffer, long vertexBufferOffset, @Nullable GpuBuffer indexBuffer, long indexBufferOffset) {
   }

   private record SectionUberBuffers(UberGpuBuffer<SectionMesh> vertexBuffer, UberGpuBuffer<SectionMesh> indexBuffer) {
   }
}
