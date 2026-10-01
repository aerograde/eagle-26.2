package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.sp.internal.ClientPlatformSingleplayer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * 26.2 upgrade of upstream sp/gui/GuiScreenIntegratedServerCrashed: the integrated
 * server crashed while the client remains active. Shows the report in the platform
 * crash overlay with a Continue button back to the title screen.
 */
public class EaglerServerCrashedScreen extends Screen {

	private static final Component DESC = Component.translatableWithFallback("singleplayer.crashed.desc",
			"The crash report is shown in a separate window, and was saved to the logs");

	private final String report;
	private boolean overlayShown = false;
	private FocusableTextWidget titleWidget;
	private FocusableTextWidget descWidget;

	public EaglerServerCrashedScreen(String report) {
		super(Component.translatableWithFallback("singleplayer.crashed.title", "The integrated server has crashed!"));
		this.report = report;
	}

	@Override
	protected void init() {
		this.titleWidget = this.addRenderableWidget(
				FocusableTextWidget.builder(this.title, this.font, 12).textWidth(this.font.width(this.title)).build());
		this.descWidget = this.addRenderableWidget(
				FocusableTextWidget.builder(DESC, this.font, 12).textWidth(Math.min(this.font.width(DESC), this.width - 40)).build());
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("gui.continue", "Continue"),
				btn -> this.onContinue()).bounds(this.width / 2 - 100, this.height - 38, 200, 20).build());
		this.repositionElements();

		if(!overlayShown) {
			overlayShown = true;
			int windowW = this.minecraft.getWindow().getScreenWidth();
			int windowH = this.minecraft.getWindow().getScreenHeight();
			try {
				ClientPlatformSingleplayer.showCrashReportOverlay(report, 32, 64,
						Math.max(320, windowW - 64), Math.max(240, windowH - 160));
			}catch(Throwable t) {
				System.err.println("Could not display crash report overlay: " + t);
			}
		}
	}

	@Override
	protected void repositionElements() {
		if(titleWidget != null) {
			titleWidget.setPosition(this.width / 2 - titleWidget.getWidth() / 2, 40);
		}
		if(descWidget != null) {
			descWidget.setPosition(this.width / 2 - descWidget.getWidth() / 2, 70);
		}
	}

	private void onContinue() {
		try {
			if(overlayShown) {
				ClientPlatformSingleplayer.hideCrashReportOverlay();
			}
		}catch(Throwable ignored) {
		}
		if(this.minecraft.level != null) {
			this.minecraft.disconnectFromWorld(Component.translatableWithFallback("menu.savingLevel", "Saving world"));
		}
		this.minecraft.gui.setScreen(new TitleScreen());
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

}
