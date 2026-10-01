package net.lax1dude.eaglercraft.v1_8.profile;

import java.util.function.Supplier;

import org.joml.Quaternionf;

import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Drag-rotatable 3D player preview with the active Eagler cape, like the
 * upstream 1.8.8 SkinPreviewRenderer: a copy of vanilla PlayerSkinWidget
 * that additionally bakes the vanilla PLAYER_CAPE layer (posed with the
 * PlayerCapeModel.setupAnim rest rotation so the cape hangs off the back
 * like a stationary player's) and hands both models to the Eagler
 * GuiGraphicsExtractor.skin overload so the cape is drawn in the same
 * picture-in-picture pass/framebuffer as the body for correct
 * depth-occlusion. A null cape texture renders the body only.
 */
public class EaglerCapedSkinWidget26 extends AbstractWidget {

	private static final float MODEL_HEIGHT = 2.125F;
	private static final float FIT_SCALE = 0.97F;
	private static final float ROTATION_SENSITIVITY = 2.5F;
	private static final float ROTATION_X_LIMIT = 50.0F;

	private final Model.Simple wideModel;
	private final Model.Simple slimModel;
	private final Model.Simple capeModel;
	private final Supplier<PlayerSkin> skin;
	private final Supplier<Identifier> capeTexture;
	private float rotationX = -5.0F;
	private float rotationY = 30.0F;

	public EaglerCapedSkinWidget26(int width, int height, EntityModelSet models, Supplier<PlayerSkin> skin,
			Supplier<Identifier> capeTexture) {
		super(0, 0, width, height, CommonComponents.EMPTY);
		this.wideModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
		this.slimModel = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
		ModelPart capeRoot = models.bakeLayer(ModelLayers.PLAYER_CAPE);
		// vanilla PlayerCapeModel.setupAnim rest pose (capeFlap/capeLean/capeLean2 = 0)
		capeRoot.getChild("body").getChild("cape").rotateBy(new Quaternionf()
				.rotateY((float) -Math.PI)
				.rotateX(6.0F * (float) (Math.PI / 180.0))
				.rotateY((float) Math.PI));
		this.capeModel = new Model.Simple(capeRoot, RenderTypes::entitySolid);
		this.skin = skin;
		this.capeTexture = capeTexture;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		float scale = FIT_SCALE * this.getHeight() / MODEL_HEIGHT;
		PlayerSkin playerSkin = this.skin.get();
		Model.Simple model = playerSkin.model() == PlayerModelType.SLIM ? this.slimModel : this.wideModel;
		Identifier cape = capeTexture != null ? capeTexture.get() : null;
		graphics.skin(model, playerSkin.body().texturePath(), cape != null ? this.capeModel : null, cape, scale,
				this.rotationX, this.rotationY, -1.0625F, this.getX(), this.getY(), this.getRight(),
				this.getBottom());
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		this.rotationX = Mth.clamp(this.rotationX - (float) dy * ROTATION_SENSITIVITY, -ROTATION_X_LIMIT,
				ROTATION_X_LIMIT);
		this.rotationY += (float) dx * ROTATION_SENSITIVITY;
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}

	@Override
	public ComponentPath nextFocusPath(FocusNavigationEvent navigationEvent) {
		return null;
	}

}
