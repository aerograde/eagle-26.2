package net.lax1dude.eaglercraft.v1_8.profile;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * The bordered preview box from upstream GuiScreenEditProfile/GuiScreenEditCape
 * (drawRect white-gray 1px border + dark navy 0xFF000015 fill), rebuilt as a
 * passive 26.2 extraction widget. Add it before the widget it frames so the
 * content renders on top.
 */
public class EaglerBorderBoxWidget26 extends AbstractWidget {

	public EaglerBorderBoxWidget26(int x, int y, int width, int height) {
		super(x, y, width, height, Component.empty());
		this.active = false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int x = this.getX();
		int y = this.getY();
		graphics.fill(x, y, x + this.width, y + this.height, 0xFFA0A0A0);
		graphics.fill(x + 1, y + 1, x + this.width - 1, y + this.height - 1, 0xFF000015);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}

}
