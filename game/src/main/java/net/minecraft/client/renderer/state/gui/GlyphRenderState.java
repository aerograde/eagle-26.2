package net.minecraft.client.renderer.state.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

public final class GlyphRenderState implements GuiElementRenderState {
   private final Matrix4fc pose;
   private final RenderPipeline pipeline;
   private final TextureSetup textureSetup;
   private final @Nullable ScreenRectangle scissorArea;
   private final List<TextRenderable> renderables = new ArrayList<>();

   public GlyphRenderState(
      final Matrix4fc pose,
      final RenderPipeline pipeline,
      final TextureSetup textureSetup,
      final @Nullable ScreenRectangle scissorArea
   ) {
      this.pose = pose;
      this.pipeline = pipeline;
      this.textureSetup = textureSetup;
      this.scissorArea = scissorArea;
   }

   public void add(final TextRenderable renderable) {
      this.renderables.add(renderable);
   }

   @Override
   public void buildVertices(final VertexConsumer vertexConsumer) {
      for (TextRenderable renderable : this.renderables) {
         renderable.render(this.pose, vertexConsumer, 15728880, true);
      }
   }

   @Override
   public RenderPipeline pipeline() {
      return this.pipeline;
   }

   @Override
   public TextureSetup textureSetup() {
      return this.textureSetup;
   }

   @Override
   public @Nullable ScreenRectangle scissorArea() {
      return this.scissorArea;
   }

   @Override
   public @Nullable ScreenRectangle bounds() {
      return null;
   }
}
