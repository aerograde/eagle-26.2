package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.sp.server.export.EaglerConvertProgress;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Generic progress screen for local (client-thread-spawned) background tasks
 * against the Eagler VFS — world import/export/delete. Same widget layout as
 * EaglerIntegratedServerBusyScreen (dirt background, animated dots, optional
 * percentage) but fed by a volatile status snapshot instead of the IPC 0x0D
 * stream; the worker thread reports through the EaglerConvertProgress sink.
 */
public class EaglerLocalProgressScreen extends Screen implements EaglerConvertProgress {

	private volatile String detail = "";
	private volatile float fraction = -1.0f;

	private StringWidget statusWidget;
	private StringWidget detailWidget;
	private final long openedAt = System.currentTimeMillis();
	private int dots = 0;

	public EaglerLocalProgressScreen(Component title) {
		super(title);
	}

	@Override
	public void report(String detail, float fraction) {
		this.detail = detail == null ? "" : detail;
		this.fraction = fraction;
	}

	@Override
	protected void init() {
		this.statusWidget = this.addRenderableWidget(new StringWidget(this.width / 2, this.height / 2 - 30,
				0, 9, Component.empty(), this.font));
		this.detailWidget = this.addRenderableWidget(new StringWidget(this.width / 2, this.height / 2 - 16,
				0, 9, Component.empty(), this.font));
	}

	@Override
	public void extractBackground(final net.minecraft.client.gui.GuiGraphicsExtractor graphics,
			final int mouseX, final int mouseY, final float a) {
		// same classic tiled menu background as the integrated-server busy screen
		this.extractMenuBackground(graphics);
	}

	@Override
	public void tick() {
		dots = (dots + 1) % 40;
		float progress = this.fraction;
		String text = this.title.getString() + ".".repeat(dots / 10 + 1)
				+ (progress >= 0.0f ? " " + Mth.floor(progress * 100.0f) + "%" : "");
		if(statusWidget != null) {
			// 26.2 StringWidget has no center alignment; center by measured width
			statusWidget.setMessage(Component.literal(text));
			int w = this.font.width(text);
			statusWidget.setWidth(w);
			statusWidget.setPosition(this.width / 2 - w / 2, this.height / 2 - 30);
		}
		if(detailWidget != null) {
			String detailText = this.detail;
			long elapsed = (System.currentTimeMillis() - openedAt) / 1000L;
			if(elapsed > 3L) {
				detailText = detailText.isEmpty() ? "(" + elapsed + "s)" : detailText + " (" + elapsed + "s)";
			}
			detailWidget.setMessage(Component.literal(detailText));
			int w = this.font.width(detailText);
			detailWidget.setWidth(w);
			detailWidget.setPosition(this.width / 2 - w / 2, this.height / 2 - 16);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

}
