package net.minecraft.client.renderer;

public class RenderBuffers implements AutoCloseable {
   private final SectionBufferBuilderPack fixedBufferPack = new SectionBufferBuilderPack();
   private final SectionBufferBuilderPool sectionBufferPool;
   private final StagedVertexBuffer stagedVertexBuffer;
   private boolean eaglerTrimStagedPending;
   private boolean eaglerTrimFixedPending;
   private int eaglerTrimPoolRemaining;

   public RenderBuffers(final int maxSectionBuilders) {
      this.sectionBufferPool = SectionBufferBuilderPool.allocate(maxSectionBuilders);
      this.stagedVertexBuffer = new StagedVertexBuffer(() -> "Shared Buffer", 4194304);
   }

   public SectionBufferBuilderPack fixedBufferPack() {
      return this.fixedBufferPack;
   }

   public SectionBufferBuilderPool sectionBufferPool() {
      return this.sectionBufferPool;
   }

   public StagedVertexBuffer stagedVertexBuffer() {
      return this.stagedVertexBuffer;
   }

   public void endFrame() {
      this.stagedVertexBuffer.endFrame();
   }

   public void eaglerTrimAfterWorld() {
      this.stagedVertexBuffer.eaglerTrimAfterWorld();
      this.fixedBufferPack.eaglerTrimAfterWorld();
      this.sectionBufferPool.eaglerTrimAfterWorld();
   }

   public void eaglerBeginTrimAfterWorld() {
      this.eaglerTrimStagedPending = true;
      this.eaglerTrimFixedPending = true;
      this.eaglerTrimPoolRemaining = this.sectionBufferPool.getFreeBufferCount();
   }

   public void eaglerCancelTrimAfterWorld() {
      this.eaglerTrimStagedPending = false;
      this.eaglerTrimFixedPending = false;
      this.eaglerTrimPoolRemaining = 0;
   }

   public void eaglerTrimAfterWorldStep() {
      if (this.eaglerTrimStagedPending) {
         this.eaglerTrimStagedPending = false;
         this.stagedVertexBuffer.eaglerTrimAfterWorld();
      } else if (this.eaglerTrimFixedPending) {
         this.eaglerTrimFixedPending = false;
         this.fixedBufferPack.eaglerTrimAfterWorld();
      } else if (this.eaglerTrimPoolRemaining > 0) {
         if (this.sectionBufferPool.eaglerTrimOneAfterWorld()) {
            this.eaglerTrimPoolRemaining--;
         } else {
            this.eaglerTrimPoolRemaining = 0;
         }
      }
   }

   @Override
   public void close() {
      this.sectionBufferPool.close();
      this.stagedVertexBuffer.close();
   }
}
