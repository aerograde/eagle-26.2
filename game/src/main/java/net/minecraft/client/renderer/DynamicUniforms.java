package net.minecraft.client.renderer;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

public class DynamicUniforms implements AutoCloseable {
   private static final Vector4fc WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
   private static final Vector3fc NO_OFFSET = new Vector3f();
   private static final Matrix4fc IDENTITY_TEXTURE_TRANSFORM = new Matrix4f();
   public static final int TRANSFORM_UBO_SIZE = new Std140SizeCalculator().putMat4f().putVec4().putVec3().putMat4f().get();
   public static final int CHUNK_SECTION_UBO_SIZE = new Std140SizeCalculator().putMat4f().putFloat().putIVec2().putIVec3().get();
   // Eagler: vanilla seeds both storages at 2 and doubles on demand — from cold that
   // is 7-10 mid-frame ring reallocations (3 GPU buffers each) plus possible fence
   // stalls before the high-water mark settles. Seed realistic capacities instead;
   // the memory cost is a few hundred KB once at startup.
   private static final int INITIAL_TRANSFORM_CAPACITY = 256;
   private static final int INITIAL_CHUNK_SECTION_CAPACITY = 1024;
   private final DynamicUniformStorage<DynamicUniforms.Transform> transforms = new DynamicUniformStorage<>(
      "Dynamic Transforms UBO", TRANSFORM_UBO_SIZE, INITIAL_TRANSFORM_CAPACITY
   );
   private final DynamicUniformStorage<DynamicUniforms.ChunkSectionInfo> chunkSections = new DynamicUniformStorage<>(
      "Chunk Sections UBO", CHUNK_SECTION_UBO_SIZE, INITIAL_CHUNK_SECTION_CAPACITY
   );

   public void reset() {
      this.transforms.endFrame();
      this.chunkSections.endFrame();
   }

   @Override
   public void close() {
      this.transforms.close();
      this.chunkSections.close();
   }

   public GpuBufferSlice writeTransform(final Matrix4f modelView) {
      return this.writeTransform(new DynamicUniforms.Transform(modelView, WHITE, NO_OFFSET, IDENTITY_TEXTURE_TRANSFORM));
   }

   public GpuBufferSlice writeTransform(final Matrix4f modelView, final Vector4f colorModulator) {
      return this.writeTransform(new DynamicUniforms.Transform(modelView, colorModulator, NO_OFFSET, IDENTITY_TEXTURE_TRANSFORM));
   }

   public GpuBufferSlice writeTransform(final Matrix4f modelView, final Matrix4f textureMatrix) {
      return this.writeTransform(new DynamicUniforms.Transform(modelView, WHITE, NO_OFFSET, textureMatrix));
   }

   public GpuBufferSlice writeTransform(final Matrix4f modelView, final Vector4f colorModulator, final Vector3f modelOffset, final Matrix4f textureMatrix) {
      return this.writeTransform(new DynamicUniforms.Transform(modelView, colorModulator, modelOffset, textureMatrix));
   }

   public GpuBufferSlice writeTransform(final DynamicUniforms.Transform uniform) {
      return this.transforms.writeUniform(uniform);
   }

   public GpuBufferSlice[] writeTransforms(final DynamicUniforms.Transform... transforms) {
      return this.transforms.writeUniforms(transforms);
   }

   public GpuBufferSlice[] writeChunkSections(final DynamicUniforms.ChunkSectionInfo... infos) {
      return this.chunkSections.writeUniforms(infos);
   }

   public record ChunkSectionInfo(Matrix4fc modelView, int x, int y, int z, float visibility, int textureAtlasWidth, int textureAtlasHeight)
      implements DynamicUniformStorage.DynamicUniform {
      @Override
      public void write(final ByteBuffer buffer) {
         Std140Builder.intoBuffer(buffer)
            .putMat4f(this.modelView)
            .putFloat(this.visibility)
            .putIVec2(this.textureAtlasWidth, this.textureAtlasHeight)
            .putIVec3(this.x, this.y, this.z);
      }
   }

   public record Transform(Matrix4fc modelView, Vector4fc colorModulator, Vector3fc modelOffset, Matrix4fc textureMatrix)
      implements DynamicUniformStorage.DynamicUniform {
      @Override
      public void write(final ByteBuffer buffer) {
         Std140Builder.intoBuffer(buffer).putMat4f(this.modelView).putVec4(this.colorModulator).putVec3(this.modelOffset).putMat4f(this.textureMatrix);
      }
   }
}
