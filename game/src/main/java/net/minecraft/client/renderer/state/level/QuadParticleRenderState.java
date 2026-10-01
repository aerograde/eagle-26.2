package net.minecraft.client.renderer.state.level;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.SubmitNodeCollector;

public class QuadParticleRenderState implements ParticleGroupRenderState {
   private static final int INITIAL_PARTICLE_CAPACITY = 1024;
   private static final int FLOATS_PER_PARTICLE = 12;
   private static final int INTS_PER_PARTICLE = 2;
   private final Map<SingleQuadParticle.Layer, QuadParticleRenderState.Storage> particles = new HashMap<>();
   private int particleCount;

   public void add(
      final SingleQuadParticle.Layer layer,
      final float x,
      final float y,
      final float z,
      final float xRot,
      final float yRot,
      final float zRot,
      final float wRot,
      final float scale,
      final float u0,
      final float u1,
      final float v0,
      final float v1,
      final int color,
      final int lightCoords
   ) {
      this.particles
         .computeIfAbsent(layer, ignored -> new QuadParticleRenderState.Storage())
         .add(x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords);
      this.particleCount++;
   }

   @Override
   public void clear() {
      this.particles.values().forEach(QuadParticleRenderState.Storage::clear);
      this.particleCount = 0;
   }

   public boolean isEmpty() {
      return this.particleCount == 0;
   }

   public void buildLayer(final SingleQuadParticle.Layer layer, final VertexConsumer bufferBuilder) {
      QuadParticleRenderState.Storage storage = this.particles.get(layer);
      if (storage != null) {
         storage.forEachParticle(
            (x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords) -> this.renderRotatedQuad(
               bufferBuilder, x, y, z, xRot, yRot, zRot, wRot, scale, u0, u1, v0, v1, color, lightCoords
            )
         );
      }
   }

   public Set<SingleQuadParticle.Layer> layers() {
      return this.particles.keySet();
   }

   protected void renderRotatedQuad(
      final VertexConsumer builder,
      final float x,
      final float y,
      final float z,
      final float xRot,
      final float yRot,
      final float zRot,
      final float wRot,
      final float scale,
      final float u0,
      final float u1,
      final float v0,
      final float v1,
      final int color,
      final int lightCoords
   ) {
      // All four corners share a rotation. Match Quaternionf.transform's operation
      // order (including non-unit quaternions and FMA), but calculate its matrix
      // once instead of four times and avoid five temporary objects per particle.
      float xx = xRot * xRot;
      float yy = yRot * yRot;
      float zz = zRot * zRot;
      float ww = wRot * wRot;
      float xy = xRot * yRot;
      float xz = xRot * zRot;
      float yz = yRot * zRot;
      float xw = xRot * wRot;
      float zw = zRot * wRot;
      float yw = yRot * wRot;
      float inverseLength = 1.0F / (xx + yy + zz + ww);
      float m00 = (xx - yy - zz + ww) * inverseLength;
      float m01 = 2.0F * (xy - zw) * inverseLength;
      float m10 = 2.0F * (xy + zw) * inverseLength;
      float m11 = (yy - xx - zz + ww) * inverseLength;
      float m20 = 2.0F * (xz - yw) * inverseLength;
      float m21 = 2.0F * (yz + xw) * inverseLength;
      // Keep signed-zero/NaN behavior of the original z=0 transform, too.
      float zeroX = (2.0F * (xz + yw) * inverseLength) * 0.0F;
      float zeroY = (2.0F * (yz - xw) * inverseLength) * 0.0F;
      float zeroZ = ((zz - xx - yy + ww) * inverseLength) * 0.0F;
      this.renderVertex(builder, x, y, z, 1.0F, -1.0F, scale, u1, v1, color, lightCoords,
         m00, m01, m10, m11, m20, m21, zeroX, zeroY, zeroZ);
      this.renderVertex(builder, x, y, z, 1.0F, 1.0F, scale, u1, v0, color, lightCoords,
         m00, m01, m10, m11, m20, m21, zeroX, zeroY, zeroZ);
      this.renderVertex(builder, x, y, z, -1.0F, 1.0F, scale, u0, v0, color, lightCoords,
         m00, m01, m10, m11, m20, m21, zeroX, zeroY, zeroZ);
      this.renderVertex(builder, x, y, z, -1.0F, -1.0F, scale, u0, v1, color, lightCoords,
         m00, m01, m10, m11, m20, m21, zeroX, zeroY, zeroZ);
   }

   private void renderVertex(
      final VertexConsumer builder,
      final float x,
      final float y,
      final float z,
      final float nx,
      final float ny,
      final float scale,
      final float u,
      final float v,
      final int color,
      final int lightCoords,
      final float m00, final float m01,
      final float m10, final float m11,
      final float m20, final float m21,
      final float zeroX, final float zeroY, final float zeroZ
   ) {
      float vx = org.joml.Math.fma(m00, nx, org.joml.Math.fma(m01, ny, zeroX)) * scale + x;
      float vy = org.joml.Math.fma(m10, nx, org.joml.Math.fma(m11, ny, zeroY)) * scale + y;
      float vz = org.joml.Math.fma(m20, nx, org.joml.Math.fma(m21, ny, zeroZ)) * scale + z;
      builder.addVertex(vx, vy, vz).setUv(u, v).setColor(color).setLight(lightCoords);
   }

   @Override
   public void submit(final SubmitNodeCollector submitNodeCollector, final CameraRenderState camera) {
      if (this.particleCount > 0) {
         submitNodeCollector.submitQuadParticleGroup(this);
      }
   }

   @FunctionalInterface
   public interface ParticleConsumer {
      void consume(
         final float x,
         final float y,
         final float z,
         final float xRot,
         final float yRot,
         final float zRot,
         final float wRot,
         final float scale,
         final float u0,
         final float u1,
         final float v0,
         final float v1,
         final int color,
         final int lightCoords
      );
   }

   private static class Storage {
      private int capacity = 1024;
      private float[] floatValues = new float[12288];
      private int[] intValues = new int[2048];
      private int currentParticleIndex;

      public void add(
         final float x,
         final float y,
         final float z,
         final float xRot,
         final float yRot,
         final float zRot,
         final float wRot,
         final float scale,
         final float u0,
         final float u1,
         final float v0,
         final float v1,
         final int color,
         final int lightCoords
      ) {
         if (this.currentParticleIndex >= this.capacity) {
            this.grow();
         }

         int index = this.currentParticleIndex * 12;
         this.floatValues[index++] = x;
         this.floatValues[index++] = y;
         this.floatValues[index++] = z;
         this.floatValues[index++] = xRot;
         this.floatValues[index++] = yRot;
         this.floatValues[index++] = zRot;
         this.floatValues[index++] = wRot;
         this.floatValues[index++] = scale;
         this.floatValues[index++] = u0;
         this.floatValues[index++] = u1;
         this.floatValues[index++] = v0;
         this.floatValues[index] = v1;
         index = this.currentParticleIndex * 2;
         this.intValues[index++] = color;
         this.intValues[index] = lightCoords;
         this.currentParticleIndex++;
      }

      public void forEachParticle(final QuadParticleRenderState.ParticleConsumer consumer) {
         for (int particleIndex = 0; particleIndex < this.currentParticleIndex; particleIndex++) {
            int floatIndex = particleIndex * 12;
            int intIndex = particleIndex * 2;
            consumer.consume(
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex++],
               this.floatValues[floatIndex],
               this.intValues[intIndex++],
               this.intValues[intIndex]
            );
         }
      }

      public void clear() {
         this.currentParticleIndex = 0;
      }

      private void grow() {
         this.capacity *= 2;
         this.floatValues = Arrays.copyOf(this.floatValues, this.capacity * 12);
         this.intValues = Arrays.copyOf(this.intValues, this.capacity * 2);
      }

      public int count() {
         return this.currentParticleIndex;
      }
   }
}
