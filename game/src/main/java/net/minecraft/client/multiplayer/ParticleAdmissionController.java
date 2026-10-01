package net.minecraft.client.multiplayer;

/**
 * Smooth token-bucket admission for server-driven particles on the browser
 * client. Normal effects pass unchanged. Pathological cosmetic streams are
 * sampled before particle objects, random vectors, and render state are
 * allocated, keeping input and gameplay packets responsive.
 */
final class ParticleAdmissionController {
   static final int DEFAULT_RATE_PER_SECOND = 1024;
   static final int PRESSURE_RATE_PER_SECOND = 512;
   static final int BURST_CAPACITY = 256;
   static final int SOFT_LIVE_LIMIT = 1280;
   static final int HARD_LIVE_LIMIT = 2048;

   private long lastRefillMillis = -1L;
   private long tokensMillis = (long)BURST_CAPACITY * 1000L;
   private long admitted;
   private long dropped;

   int admit(final int requested, final int liveParticles, final long nowMillis) {
      if (requested <= 0) {
         return 0;
      }

      int live = Math.max(0, liveParticles);
      int rate = live >= SOFT_LIVE_LIMIT ? PRESSURE_RATE_PER_SECOND : DEFAULT_RATE_PER_SECOND;
      if (this.lastRefillMillis < 0L || nowMillis < this.lastRefillMillis) {
         this.lastRefillMillis = nowMillis;
      } else {
         long elapsed = Math.min(1000L, nowMillis - this.lastRefillMillis);
         if (elapsed > 0L) {
            this.tokensMillis = Math.min((long)BURST_CAPACITY * 1000L,
               this.tokensMillis + elapsed * (long)rate);
            this.lastRefillMillis = nowMillis;
         }
      }

      int liveHeadroom = Math.max(0, HARD_LIVE_LIMIT - live);
      int tokenHeadroom = (int)(this.tokensMillis / 1000L);
      int accepted = Math.min(requested, Math.min(liveHeadroom, tokenHeadroom));
      this.tokensMillis -= (long)accepted * 1000L;
      this.admitted += accepted;
      this.dropped += requested - accepted;
      return accepted;
   }

   long admitted() {
      return this.admitted;
   }

   long dropped() {
      return this.dropped;
   }

   void clearCounters() {
      this.admitted = 0L;
      this.dropped = 0L;
   }
}
