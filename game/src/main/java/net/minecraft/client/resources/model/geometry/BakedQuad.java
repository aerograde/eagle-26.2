package net.minecraft.client.resources.model.geometry;

import com.mojang.blaze3d.platform.Transparency;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import org.joml.Vector3fc;

public record BakedQuad(
   Vector3fc position0,
   Vector3fc position1,
   Vector3fc position2,
   Vector3fc position3,
   long packedUV0,
   long packedUV1,
   long packedUV2,
   long packedUV3,
   Direction direction,
   BakedQuad.MaterialInfo materialInfo
) {
   private static final float FULL_FACE_EPSILON = 1.0E-5F;
   public static final int VERTEX_COUNT = 4;
   public static final int FLAG_TRANSLUCENT = 1;
   public static final int FLAG_ANIMATED = 2;
   public static final int FLAG_NON_FULL_BLOCK_GEOMETRY = 4;

   public Vector3fc position(final int vertex) {
      return switch (vertex) {
         case 0 -> this.position0;
         case 1 -> this.position1;
         case 2 -> this.position2;
         case 3 -> this.position3;
         default -> throw new IndexOutOfBoundsException(vertex);
      };
   }

   public long packedUV(final int vertex) {
      return switch (vertex) {
         case 0 -> this.packedUV0;
         case 1 -> this.packedUV1;
         case 2 -> this.packedUV2;
         case 3 -> this.packedUV3;
         default -> throw new IndexOutOfBoundsException(vertex);
      };
   }

   /**
    * Returns whether this quad is one complete axis-aligned face of the unit
    * block cube. The fast-leaves renderer may safely make those six vanilla
    * cube faces opaque, but must not do that to resource-pack foliage made from
    * rotated or partial planes because their transparent texels are geometry.
    */
   public boolean isFullBlockBoundaryFace() {
      int firstX = -1;
      int firstY = -1;
      int firstZ = -1;
      boolean sameX = true;
      boolean sameY = true;
      boolean sameZ = true;
      int yzCorners = 0;
      int xzCorners = 0;
      int xyCorners = 0;

      for (int i = 0; i < VERTEX_COUNT; ++i) {
         Vector3fc position = this.position(i);
         int x = boundaryBit(position.x());
         int y = boundaryBit(position.y());
         int z = boundaryBit(position.z());
         if (x < 0 || y < 0 || z < 0) {
            return false;
         }

         if (i == 0) {
            firstX = x;
            firstY = y;
            firstZ = z;
         } else {
            sameX &= x == firstX;
            sameY &= y == firstY;
            sameZ &= z == firstZ;
         }
         yzCorners |= 1 << (y << 1 | z);
         xzCorners |= 1 << (x << 1 | z);
         xyCorners |= 1 << (x << 1 | y);
      }

      return (sameX && yzCorners == 0xF)
         || (sameY && xzCorners == 0xF)
         || (sameZ && xyCorners == 0xF);
   }

   public @BakedQuad.MaterialFlags int geometryFlags() {
      return this.isFullBlockBoundaryFace() ? 0 : FLAG_NON_FULL_BLOCK_GEOMETRY;
   }

   private static int boundaryBit(final float coordinate) {
      if (near(coordinate, 0.0F)) return 0;
      if (near(coordinate, 1.0F)) return 1;
      return -1;
   }

   private static boolean near(final float value, final float expected) {
      return Math.abs(value - expected) <= FULL_FACE_EPSILON;
   }

   @Retention(RetentionPolicy.CLASS)
   @Target(ElementType.TYPE_USE)
   public @interface MaterialFlags {
   }

   public record MaterialInfo(TextureAtlasSprite sprite, ChunkSectionLayer layer, RenderType itemRenderType, int tintIndex, boolean shade, int lightEmission) {
      public static BakedQuad.MaterialInfo of(
         final Material.Baked material, final Transparency transparency, final int tintIndex, final boolean shade, final int lightEmission
      ) {
         ChunkSectionLayer layer = ChunkSectionLayer.byTransparency(transparency);
         RenderType itemRenderType;
         if (material.sprite().atlasLocation().equals(TextureAtlas.LOCATION_BLOCKS)) {
            itemRenderType = transparency.hasTranslucent() ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockItemSheet();
         } else {
            itemRenderType = transparency.hasTranslucent() ? Sheets.translucentItemSheet() : Sheets.cutoutItemSheet();
         }

         return new BakedQuad.MaterialInfo(material.sprite(), layer, itemRenderType, tintIndex, shade, lightEmission);
      }

      public boolean isTinted() {
         return this.tintIndex != -1;
      }

      public @BakedQuad.MaterialFlags int flags() {
         int flags = 0;
         flags |= this.layer.translucent() ? 1 : 0;
         return flags | (this.sprite.contents().isAnimated() ? 2 : 0);
      }
   }
}
