package net.minecraft.server.network;

import com.google.common.collect.Comparators;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

public class PlayerChunkSender {
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final float MIN_CHUNKS_PER_TICK = 0.01F;
   public static final float MAX_CHUNKS_PER_TICK = 64.0F;
   private static final float START_CHUNKS_PER_TICK = 9.0F;
   private static final int MAX_UNACKNOWLEDGED_BATCHES = 10;
   private static final float WEB_MIN_CHUNKS_PER_TICK = 4.0F;
   private static final float WEB_START_CHUNKS_PER_TICK = 12.0F;
   private static final float WEB_MAX_CHUNKS_PER_TICK = 24.0F;
   private static final int WEB_MAX_UNACKNOWLEDGED_BATCHES = 2;
   private final LongSet pendingChunks = new LongOpenHashSet();
   private final LongSet sentChunks = new LongOpenHashSet();
   private final boolean memoryConnection;
   private final boolean webMemoryConnection;
   private float desiredChunksPerTick = 9.0F;
   private float batchQuota;
   private int unacknowledgedBatches;
   private int maxUnacknowledgedBatches = 1;

   public PlayerChunkSender(final boolean memoryConnection) {
      this.memoryConnection = memoryConnection;
      this.webMemoryConnection = memoryConnection
         && net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
            != net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP;
      if (this.webMemoryConnection) {
         // The worker transport is local, but the client still has to decode each chunk on the
         // render thread. Keep a small ACK-clocked window instead of allowing ten batches to run
         // ahead: the latter built a hundreds-of-frames FIFO during terrain arrival, leaving
         // entity movement behind chunks while server TPS remained healthy. Two batches still
         // overlap the worker round trip, and the 12-chunk start keeps the mesh pool fed.
         this.maxUnacknowledgedBatches = WEB_MAX_UNACKNOWLEDGED_BATCHES;
         this.desiredChunksPerTick = WEB_START_CHUNKS_PER_TICK;
         return;
      }
      // A worker/memory connection is trusted and local; don't gate the initial chunk burst on the
      // first ack round-trip (which pays the worker park-wake latency). Start at the max so the first
      // load isn't throttled to a single batch. Remote connections keep the vanilla ramp-from-1.
      this.maxUnacknowledgedBatches = memoryConnection ? MAX_UNACKNOWLEDGED_BATCHES : 1;
      // Also start the per-tick chunk rate fat (32 of the 64 cap) for the local transport instead of
      // the vanilla ramp-from-9, so the small web view (VD 4 = 81 chunks) fills in a few batches
      // rather than trickling out; onChunkBatchReceivedByClient still adapts it to the client's ack rate.
      this.desiredChunksPerTick = memoryConnection ? 32.0F : START_CHUNKS_PER_TICK;
   }

   public void markChunkPendingToSend(final LevelChunk chunk) {
      long key = chunk.getPos().pack();
      if (!this.sentChunks.contains(key)) {
         this.pendingChunks.add(key);
      }
   }

   public void dropChunk(final ServerPlayer player, final ChunkPos pos) {
      long key = pos.pack();
      this.pendingChunks.remove(key);
      if (this.sentChunks.remove(key) && player.isAlive()) {
         player.connection.send(new ClientboundForgetLevelChunkPacket(pos));
      }
   }

   public void sendNextChunks(final ServerPlayer player) {
      // The completed FULL future already includes the generation-neighborhood and
      // lighting send dependencies. Waiting for BLOCK_TICKING here strands the outer
      // render-distance ring whenever it is intentionally outside simulation distance.
      this.sendNextChunks(player, true);
   }

   private void sendNextChunks(final ServerPlayer player, final boolean allowFullChunks) {
      if (this.unacknowledgedBatches < this.maxUnacknowledgedBatches) {
         float maxBatchSize = Math.max(1.0F, this.desiredChunksPerTick);
         this.batchQuota = Math.min(this.batchQuota + this.desiredChunksPerTick, maxBatchSize);
         if (!(this.batchQuota < 1.0F)) {
            if (!this.pendingChunks.isEmpty()) {
               ServerLevel level = player.level();
               ChunkMap chunkMap = level.getChunkSource().chunkMap;
               List<LevelChunk> chunksToSend = this.collectChunksToSend(chunkMap, player.chunkPosition(), allowFullChunks);
               if (!chunksToSend.isEmpty()) {
                  ServerGamePacketListenerImpl connection = player.connection;
                  this.unacknowledgedBatches++;
                  connection.send(ClientboundChunkBatchStartPacket.INSTANCE);

                  for (LevelChunk chunk : chunksToSend) {
                     sendChunk(connection, level, chunk);
                     this.sentChunks.add(chunk.getPos().pack());
                  }

                  connection.send(new ClientboundChunkBatchFinishedPacket(chunksToSend.size()));
                  this.batchQuota = this.batchQuota - chunksToSend.size();
               }
            }
         }
      }
   }

   /** Send the already-generated center neighborhood before post-login view expansion. */
   public void sendInitialChunks(final ServerPlayer player) {
      if (this.webMemoryConnection) {
         // Queue the verified 3x3 neighborhood immediately. The client needs the center's
         // neighbors to compile its visible section; normal distance expansion is held until
         // that mesh is ready. Vanilla may refresh a neighbor after its ticking transition.
         ServerLevel level = player.level();
         ChunkMap chunkMap = level.getChunkSource().chunkMap;
         ChunkPos center = player.chunkPosition();
         for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
               // getChunkNow() only exposes chunks promoted to the ticking map. The spawn
               // preparation future guarantees this 3x3 is FULL/send-ready before login, but
               // eight neighbors can still be outside the ticking map for another several
               // seconds. Read the send-synchronized FULL view directly so the prepared burst
               // actually contains the complete neighborhood.
               LevelChunk chunk = chunkMap.getFullChunkToSend(
                  ChunkPos.pack(center.x() + dx, center.z() + dz)
               );
               if (chunk != null) {
                  this.markChunkPendingToSend(chunk);
               }
            }
         }
         int before = this.pendingChunks.size();
         this.sendNextChunks(player, true);
         LOGGER.info("Web initial chunk send: {} ready, {} remaining", before, this.pendingChunks.size());
      }
   }

   private static void sendChunk(final ServerGamePacketListenerImpl connection, final ServerLevel level, final LevelChunk chunk) {
      connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
	  ChunkPos pos = chunk.getPos();
	  if (net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.isEnabled()) {
		 net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.chunkSent(
			connection.player.chunkPosition().getChessboardDistance(pos));
	  }
      if (SharedConstants.DEBUG_VERBOSE_SERVER_EVENTS) {
         LOGGER.debug("SEN {}", pos);
      }

      level.debugSynchronizers().startTrackingChunk(connection.player, chunk.getPos());
   }

   private List<LevelChunk> collectChunksToSend(final ChunkMap chunkMap, final ChunkPos playerPos, final boolean allowFullChunks) {
      int maxBatchSize = this.webMemoryConnection
         ? Math.min(Mth.floor(WEB_MAX_CHUNKS_PER_TICK), Mth.floor(this.batchQuota))
         : Mth.floor(this.batchQuota);
      List<LevelChunk> chunks;
      if ((!this.memoryConnection || this.webMemoryConnection) && this.pendingChunks.size() > maxBatchSize) {
         chunks = this.pendingChunks
            .longStream()
            .mapToObj(key -> allowFullChunks ? chunkMap.getFullChunkToSend(key) : chunkMap.getChunkToSend(key))
            .filter(Objects::nonNull)
            .collect(Comparators.least(maxBatchSize, Comparator.comparingInt(chunk -> playerPos.distanceSquared(chunk.getPos()))));
      } else {
         chunks = this.pendingChunks
            .longStream()
            .mapToObj(key -> allowFullChunks ? chunkMap.getFullChunkToSend(key) : chunkMap.getChunkToSend(key))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(chunkx -> playerPos.distanceSquared(chunkx.getPos())))
            .toList();
      }

      for (LevelChunk chunk : chunks) {
         this.pendingChunks.remove(chunk.getPos().pack());
      }

      return chunks;
   }

   public void onChunkBatchReceivedByClient(final float desiredChunksPerTick) {
      this.unacknowledgedBatches--;
      if (this.unacknowledgedBatches < 0) {
         this.unacknowledgedBatches = 0;
      }

      this.desiredChunksPerTick = this.webMemoryConnection
         ? Float.isNaN(desiredChunksPerTick)
            ? WEB_MIN_CHUNKS_PER_TICK
            : Mth.clamp(desiredChunksPerTick, WEB_MIN_CHUNKS_PER_TICK, WEB_MAX_CHUNKS_PER_TICK)
         : Float.isNaN(desiredChunksPerTick) ? 0.01F : Mth.clamp(desiredChunksPerTick, 0.01F, 64.0F);
      if (this.unacknowledgedBatches == 0) {
         this.batchQuota = 1.0F;
      }

      // The received ACK is also the proof that the page has consumed the batch. Do not reopen
      // the old ten-batch web window here; that bypassed the calculator's decode feedback and
      // allowed terrain traffic to hide entity updates for seconds on constrained devices.
      this.maxUnacknowledgedBatches = this.webMemoryConnection ? WEB_MAX_UNACKNOWLEDGED_BATCHES : MAX_UNACKNOWLEDGED_BATCHES;
   }

   public boolean isPending(final long pos) {
      return this.pendingChunks.contains(pos);
   }
}
