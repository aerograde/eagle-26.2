package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.sp.SingleplayerServerController26;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;

/**
 * 26.2 port of EaglercraftX 1.8's GuiScreenIntegratedServerStartup. The
 * expensive Wasm worker boot is explicit and visible instead of silently
 * competing with the title/world-list UI on lower-end machines.
 */
public class EaglerIntegratedServerStartupScreen extends Screen {

	private final Screen parent;
	private final long openedAt = System.currentTimeMillis();
	private StringWidget statusWidget;
	private StringWidget elapsedWidget;
	private Button cancelButton;
	private boolean started;
	private int dots;

	public EaglerIntegratedServerStartupScreen(Screen parent) {
		super(Component.translatableWithFallback("singleplayer.integratedStartup", "Starting Integrated Server"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.statusWidget = this.addRenderableWidget(new StringWidget(0, this.height / 2 - 32, 0, 9, Component.empty(), this.font));
		this.elapsedWidget = this.addRenderableWidget(new StringWidget(0, this.height / 2 - 16, 0, 9, Component.empty(), this.font));
		this.cancelButton = this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("singleplayer.busy.killTask", "Cancel Task"), button -> cancelStartup())
				.bounds(this.width / 2 - 100, this.height / 2 + 12, 200, 20).build());
		this.cancelButton.active = false;
	}

	@Override
	public void tick() {
		super.tick();
		if(!started) {
			started = true;
			SingleplayerServerController26.startIntegratedServerWorker();
		}
		SingleplayerServerController26.runTick();
		int state = SingleplayerServerController26.getState();
		if(state == SingleplayerServerController26.STATE_WORKER_IDLE) {
			this.minecraft.gui.setScreen(new SelectWorldScreen(parent));
			return;
		}
		if(state == SingleplayerServerController26.STATE_NONE && started) {
			// A pre-world boot failure is already logged and reset by the controller.
			// Let world launch retry normally instead of trapping the user in a loop.
			this.minecraft.gui.setScreen(new SelectWorldScreen(parent));
			return;
		}

		dots = (dots + 1) % 40;
		setCentered(statusWidget, Component.translatableWithFallback("singleplayer.integratedStartup.progress",
				"Starting Integrated Server%s", ".".repeat(dots / 10 + 1)), this.height / 2 - 32);
		long elapsed = (System.currentTimeMillis() - openedAt) / 1000L;
		setCentered(elapsedWidget, elapsed > 3L
				? Component.translatableWithFallback("singleplayer.integratedStartup.elapsed", "(%ss)", elapsed)
				: Component.empty(), this.height / 2 - 16);
		cancelButton.active = elapsed >= 6L && state == SingleplayerServerController26.STATE_WORKER_BOOTING;
	}

	private void setCentered(StringWidget widget, Component message, int y) {
		widget.setMessage(message);
		int width = this.font.width(message);
		widget.setWidth(width);
		widget.setPosition(this.width / 2 - width / 2, y);
	}

	private void cancelStartup() {
		SingleplayerServerController26.cancelWorkerStartup();
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public void onClose() {
		cancelStartup();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(final net.minecraft.client.gui.GuiGraphicsExtractor graphics,
			final int mouseX, final int mouseY, final float a) {
		this.extractMenuBackground(graphics);
	}
}
