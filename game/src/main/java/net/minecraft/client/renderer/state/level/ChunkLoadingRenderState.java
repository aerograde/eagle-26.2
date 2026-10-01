package net.minecraft.client.renderer.state.level;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public class ChunkLoadingRenderState {
   private final LongOpenHashSet noUpdates = new LongOpenHashSet(0);
   public LongOpenHashSet addedEmptySections = this.noUpdates;
   public LongOpenHashSet removedEmptySections = this.noUpdates;
   public LongOpenHashSet addedLoadedChunks = this.noUpdates;
   public LongOpenHashSet removedLoadedChunks = this.noUpdates;
   public LongOpenHashSet loadedExpectedChunks = new LongOpenHashSet();

   public void reset() {
      this.addedEmptySections = this.noUpdates;
      this.removedEmptySections = this.noUpdates;
      this.addedLoadedChunks = this.noUpdates;
      this.removedLoadedChunks = this.noUpdates;
      this.loadedExpectedChunks.clear();
   }

   void releaseWorld() {
      this.loadedExpectedChunks = new LongOpenHashSet();
   }
}
