package net.minecraft.client.particle;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState;

public abstract class ParticleGroup<P extends Particle> {
   private static final int MAX_PARTICLES = 16384;
   private static final int RESERVOIR_SIZE = 4096;
   private static final int RESERVOIR_START = 12288;
   protected final ParticleEngine engine;
   protected final Queue<P> particles = new ArrayDeque<>(16384);

   public ParticleGroup(final ParticleEngine engine) {
      this.engine = engine;
   }

   public boolean isEmpty() {
      return this.particles.isEmpty();
   }

   public void tickParticles() {
      if (!this.particles.isEmpty()) {
         Iterator<P> iterator = this.particles.iterator();

         while (iterator.hasNext()) {
            P particle = iterator.next();
            // EAGDBG(web): TeaVM's ArrayDeque.iterator() can yield a phantom trailing null (same
            // iterator off-by-one class as gap #14's Guava Streams.mapWithIndex). ArrayDeque
            // forbids stored nulls, so any null here is that phantom past the last real element ->
            // stop iterating this group. Without this, tickParticle(null) -> null.tick() NPE, then
            // the crash-report builder's particle::toString requireNonNull(null) throws a BARE NPE
            // that masks it and crashes the render loop mid-play. Remove once TeaVM ArrayDeque is
            // fixed at the root.
            if (particle == null) {
               break;
            }

            this.tickParticle(particle);
            if (!particle.isAlive()) {
               particle.getParticleLimit().ifPresent(options -> this.engine.updateCount(options, -1));
               iterator.remove();
            }
         }
      }
   }

   private void tickParticle(final Particle particle) {
      try {
         particle.tick();
      } catch (Throwable t) {
         CrashReport report = CrashReport.forThrowable(t, "Ticking Particle");
         CrashReportCategory category = report.addCategory("Particle being ticked");
         // EAGDBG: use String.valueOf (not particle::toString / getGroup()::toString) so a null
         // particle or null group can't throw a fresh NPE here and MASK the real `t` exception
         // (the method-ref receivers are requireNonNull'd). Keeps the true cause visible.
         category.setDetail("Particle", () -> String.valueOf(particle));
         category.setDetail("Particle Type", () -> String.valueOf(particle == null ? null : particle.getGroup()));
         throw new ReportedException(report);
      }
   }

   public boolean add(final Particle particle) {
      int currentSize = this.particles.size();
      if (currentSize >= 16384) {
         return false;
      }

      if (currentSize >= 12288) {
         float freeSpace = (16384 - currentSize) / 4096.0F;
         if (this.engine.getRandom().nextFloat() >= freeSpace * freeSpace) {
            return false;
         }
      }

      this.particles.add((P)particle);
      return true;
   }

   public int size() {
      return this.particles.size();
   }

   public abstract ParticleGroupRenderState extractRenderState(Frustum frustum, Camera camera, float partialTickTime);
}
