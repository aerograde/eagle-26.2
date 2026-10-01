package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.sp.SingleplayerServerController26;
import net.lax1dude.eaglercraft.v1_8.sp.server.EaglerWorkerHandoff;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 26.2 upgrade of upstream sp/gui/GuiScreenIntegratedServerBusy: the generic
 * eagler busy screen for integrated-server operations — status line from the IPC
 * 0x0D progress stream, animated dots, percentage, and a cancel button that
 * appears after 6 seconds (enabled once the server core exists to shut down).
 */
public class EaglerIntegratedServerBusyScreen extends Screen {

	private StringWidget statusWidget;
	private StringWidget elapsedWidget;
	private Button cancelButton;
	private final long openedAt = System.currentTimeMillis();
	private int dots = 0;

	public EaglerIntegratedServerBusyScreen() {
		super(Component.translatableWithFallback("singleplayer.busy.title", "Starting Integrated Server"));
	}

	@Override
	protected void init() {
		this.statusWidget = this.addRenderableWidget(new StringWidget(this.width / 2, this.height / 2 - 30,
				0, 9, Component.empty(), this.font));
		this.elapsedWidget = this.addRenderableWidget(new StringWidget(this.width / 2, this.height / 2 - 16,
				0, 9, Component.empty(), this.font));
		this.cancelButton = this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("singleplayer.busy.killTask", "Cancel Task"), btn -> {
			SingleplayerServerController26.shutdownServer();
			this.minecraft.gui.setScreen(new TitleScreen());
		}).bounds(this.width / 2 - 100, this.height / 2 + 8, 200, 20).build());
		this.cancelButton.active = false;
	}

	@Override
	public void extractBackground(final net.minecraft.client.gui.GuiGraphicsExtractor graphics,
			final int mouseX, final int mouseY, final float a) {
		// upstream busy screen uses the classic tiled menu background
		this.extractMenuBackground(graphics);
	}

	@Override
	public void tick() {
		dots = (dots + 1) % 40;
		String message = SingleplayerServerController26.worldStatusString();
		float progress = SingleplayerServerController26.worldStatusProgress();
		Component line = Component.translatableWithFallback(message.isEmpty()
				? "singleplayer.busy.startingIntegratedServer" : message, humanize(message));
		Component text = progress > 0.0f
				? Component.translatableWithFallback("singleplayer.busy.progress", "%s%s %s%%", line,
						".".repeat(dots / 10 + 1), Mth.floor(progress * 100.0f))
				: Component.translatableWithFallback("singleplayer.busy.progressNoPercent", "%s%s", line,
						".".repeat(dots / 10 + 1));
		if(statusWidget != null) {
			// 26.2 StringWidget has no center alignment; center by measured width
			statusWidget.setMessage(text);
			int w = this.font.width(text);
			statusWidget.setWidth(w);
			statusWidget.setPosition(this.width / 2 - w / 2, this.height / 2 - 30);
		}
		long elapsed = (System.currentTimeMillis() - openedAt) / 1000L;
		if(elapsedWidget != null) {
			Component elapsedText = elapsed > 3L
					? Component.translatableWithFallback("singleplayer.integratedStartup.elapsed", "(%ss)", elapsed)
					: Component.empty();
			elapsedWidget.setMessage(elapsedText);
			int w = this.font.width(elapsedText);
			elapsedWidget.setWidth(w);
			elapsedWidget.setPosition(this.width / 2 - w / 2, this.height / 2 - 16);
		}
		if(cancelButton != null) {
			// Cancelable once the controller is in a shutdownable state (STARTING/READY). The old
			// getCurrentServer() != null check was always false in server-worker mode (the client
			// realm never holds the server object), permanently greying the button; shutdownServer()
			// itself works in worker mode (sends IPCPacket01StopServer).
			int st = SingleplayerServerController26.getState();
			cancelButton.active = elapsed >= 6L
					&& (st == SingleplayerServerController26.STATE_STARTING || st == SingleplayerServerController26.STATE_READY);
		}
	}

	private static String humanize(String key) {
		if(key == null || key.isEmpty()) {
			return "Starting up integrated server";
		}
		switch(key) {
		case "singleplayer.busy.startingIntegratedServer": return "Starting up integrated server";
		case "singleplayer.busy.runningWorkers": return "Running preheated workers";
		case "singleplayer.busy.preparingSpawn": return "Preparing spawn area";
		case "singleplayer.busy.loadingChunks": return "Loading chunks";
		case "singleplayer.busy.loadingPlayerChunks": return "Loading player chunks";
		default: return key;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

}
