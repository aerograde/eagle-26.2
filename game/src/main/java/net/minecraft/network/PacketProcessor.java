package net.minecraft.network;

import com.google.common.collect.Queues;
import com.mojang.logging.LogUtils;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ReportedException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import org.slf4j.Logger;

public class PacketProcessor implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final Queue<PacketProcessor.ListenerAndPacket<?>> packetsToBeHandled = Queues.newConcurrentLinkedQueue();
   private final AtomicInteger queuedPacketCount = new AtomicInteger();
   private final Thread runningThread;
   private boolean closed;

   public PacketProcessor(final Thread runningThread) {
      this.runningThread = runningThread;
   }

   public boolean isSameThread() {
      return Thread.currentThread() == this.runningThread;
   }

   public <T extends PacketListener> void scheduleIfPossible(final T listener, final Packet<T> packet) {
      if (this.closed) {
         throw new RejectedExecutionException("Server already shutting down");
      }

      this.packetsToBeHandled.add(new PacketProcessor.ListenerAndPacket<>(listener, packet, System.nanoTime()));
      this.queuedPacketCount.incrementAndGet();
   }

   public void processQueuedPackets() {
      if (!this.closed) {
         while (!this.packetsToBeHandled.isEmpty()) {
            PacketProcessor.ListenerAndPacket<?> next = this.packetsToBeHandled.poll();
            if (next != null) {
               this.decrementQueuedPacketCount();
               next.handle();
            }
         }
      }
   }

   /**
    * Drain packets in FIFO order until the time budget is spent. One packet is
    * always handled so an individually expensive chunk packet cannot starve.
    */
   public int processQueuedPackets(final long nanosBudget) {
      if (this.closed) {
         return 0;
      }
      long started = System.nanoTime();
      int processed = 0;
      PacketProcessor.ListenerAndPacket<?> next;
      while ((next = this.packetsToBeHandled.poll()) != null) {
         this.decrementQueuedPacketCount();
         next.handle();
         processed++;
         if (System.nanoTime() - started >= nanosBudget) {
            break;
         }
      }
      return processed;
   }

   public int queuedPacketCount() {
      return this.queuedPacketCount.get();
   }

   public long oldestQueuedPacketAgeNanos() {
      PacketProcessor.ListenerAndPacket<?> next = this.packetsToBeHandled.peek();
      return next == null ? 0L : Math.max(0L, System.nanoTime() - next.queuedAtNanos());
   }

   private void decrementQueuedPacketCount() {
      if (this.queuedPacketCount.decrementAndGet() < 0) {
         // A concurrent disconnect may clear the queue between poll and this
         // diagnostic counter update. The queue itself remains authoritative.
         this.queuedPacketCount.set(0);
      }
   }

   /**
    * Drop packets retained for a connection that has already been closed. The
    * client owns one processor for its whole lifetime, so closing the processor
    * itself would also reject packets from the next server connection.
    */
   public void clearQueuedPackets() {
      this.packetsToBeHandled.clear();
      this.queuedPacketCount.set(0);
   }

   @Override
   public void close() {
      this.closed = true;
      this.packetsToBeHandled.clear();
      this.queuedPacketCount.set(0);
   }

   private record ListenerAndPacket<T extends PacketListener>(T listener, Packet<T> packet, long queuedAtNanos) {
      public void handle() {
         if (this.listener.shouldHandleMessage(this.packet)) {
            try {
               traceActionQueue(this.packet, Math.max(0L, System.nanoTime() - this.queuedAtNanos));
               this.packet.handle(this.listener);
            } catch (Exception e) {
               if (e instanceof ReportedException re && re.getCause() instanceof OutOfMemoryError) {
                  throw PacketUtils.makeReportedException(e, this.packet, this.listener);
               }

               this.listener.onPacketError(this.packet, e);
            }
         } else {
            PacketProcessor.LOGGER.debug("Ignoring packet due to disconnection: {}", this.packet);
         }
      }

      private static void traceActionQueue(final Packet<?> packet, final long queueAgeNanos) {
         if (packet instanceof ServerboundUseItemOnPacket usePacket) {
            net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.actionTrace(
               "use_block", usePacket.getSequence(), "queue_dequeue", -1,
               "queueAgeMs=" + formatMillis(queueAgeNanos)
            );
         } else if (packet instanceof ServerboundAttackPacket attackPacket) {
            net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.actionTrace(
               "attack", attackPacket.entityId(), "queue_dequeue", -1,
               "queueAgeMs=" + formatMillis(queueAgeNanos)
            );
         } else if (packet instanceof ClientboundOpenScreenPacket openPacket) {
            net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.openScreenPacketStage(
               "queue_dequeue", openPacket.getContainerId(), queueAgeNanos
            );
         } else if (packet instanceof ClientboundDamageEventPacket damagePacket) {
            net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.attackPacketStage(
               "queue_dequeue", damagePacket.entityId(), queueAgeNanos, "damage_event"
            );
         }
      }

      private static String formatMillis(final long nanos) {
         return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0);
      }
   }
}
