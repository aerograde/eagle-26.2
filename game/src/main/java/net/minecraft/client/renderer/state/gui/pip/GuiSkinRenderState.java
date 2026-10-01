package net.minecraft.client.renderer.state.gui.pip;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.model.Model;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public record GuiSkinRenderState(
   Model.Simple playerModel,
   Identifier texture,
   // Eagler 26.2: optional cape model + texture rendered in the same PIP pass (profile screens, EaglerHosted only)
   Model.@Nullable Simple eaglerCapeModel,
   @Nullable Identifier eaglerCapeTexture,
   float rotationX,
   float rotationY,
   float pivotY,
   int x0,
   int y0,
   int x1,
   int y1,
   float scale,
   @Nullable ScreenRectangle scissorArea,
   @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {
   public GuiSkinRenderState(
      final Model.Simple playerModel,
      final Identifier texture,
      final float rotationX,
      final float rotationY,
      final float pivotY,
      final int x0,
      final int y0,
      final int x1,
      final int y1,
      final float scale,
      final @Nullable ScreenRectangle scissorArea
   ) {
      this(playerModel, texture, null, null, rotationX, rotationY, pivotY, x0, y0, x1, y1, scale, scissorArea);
   }

   // Eagler 26.2: convenience constructor carrying the optional cape pass
   public GuiSkinRenderState(
      final Model.Simple playerModel,
      final Identifier texture,
      final Model.@Nullable Simple eaglerCapeModel,
      final @Nullable Identifier eaglerCapeTexture,
      final float rotationX,
      final float rotationY,
      final float pivotY,
      final int x0,
      final int y0,
      final int x1,
      final int y1,
      final float scale,
      final @Nullable ScreenRectangle scissorArea
   ) {
      this(
         playerModel,
         texture,
         eaglerCapeModel,
         eaglerCapeTexture,
         rotationX,
         rotationY,
         pivotY,
         x0,
         y0,
         x1,
         y1,
         scale,
         scissorArea,
         PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea)
      );
   }
}
