package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

public class RelaySettingsLink extends AbstractButton {

	private final Screen parent;

	public RelaySettingsLink(Screen parent) {
		this(parent, false);
	}

	public RelaySettingsLink(Screen parent, boolean multiplayer) {
		super(5, 5, 72, 14, Component.translatableWithFallback("relay.title", "Relays")
				.withStyle(ChatFormatting.UNDERLINE));
		this.parent = parent;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		Minecraft.getInstance().gui.setScreen(new RelaySettingsScreen(this.parent));
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.setMessage(Component.translatableWithFallback("relay.title", "Relays").withStyle(ChatFormatting.UNDERLINE));
		graphics.text(Minecraft.getInstance().font, this.getMessage(), this.getX(), this.getY() + 2,
				this.isHoveredOrFocused() ? 0xFFFFFF55 : 0xFFCCCCCC);
	}

	@Override
	public void updateWidgetNarration(NarrationElementOutput output) {
		this.defaultButtonNarrationText(output);
	}
}
