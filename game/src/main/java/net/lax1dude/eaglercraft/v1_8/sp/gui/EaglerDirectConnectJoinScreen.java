package net.lax1dude.eaglercraft.v1_8.sp.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.sp.internal.ClientPlatformSingleplayer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Guest side of the relay-free "Direct connect" flow. The offer code was pasted
 * in the join screen; this screen reduces it to an answer code (shown as text
 * with a copy button) and waits for the host to apply that answer. The WebRTC
 * data channel is already establishing in the background, so once it opens the
 * screen hands control back to the join callback, which starts the ordinary
 * connect flow against the {@code eagler-direct:} virtual URI.
 */
public class EaglerDirectConnectJoinScreen extends Screen {

	private static final int STATE_GENERATING = 0;
	private static final int STATE_WAITING = 1;
	private static final int STATE_CONNECTED = 2;
	private static final int STATE_FAILED = 3;

	private final Screen parent;
	private final ServerData serverData;
	private final BooleanConsumer callback;
	private final String offerCode;

	private StringWidget statusWidget;
	private Button copyButton;
	private String answerCode;
	private String errorText;
	private int state = STATE_GENERATING;
	private int advanceTimer;

	public EaglerDirectConnectJoinScreen(Screen parent, ServerData serverData, BooleanConsumer callback,
			String offerCode) {
		super(Component.translatableWithFallback("direct.join.title", "Direct Connect"));
		this.parent = parent;
		this.serverData = serverData;
		this.callback = callback;
		this.offerCode = offerCode;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		this.statusWidget = this.addRenderableWidget(new StringWidget(centerX, 34, 0, 9,
				Component.translatableWithFallback("direct.join.creating", "Creating answer code..."), this.font));
		this.copyButton = this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("direct.copy", "Copy Code"), button -> {
					String code = this.answerCode;
					if(code != null && !code.isBlank()) {
						EagRuntime.setClipboard(code);
						button.setMessage(Component.translatableWithFallback("direct.copied", "Copied!")
								.withStyle(ChatFormatting.GREEN));
					}
				}).bounds(centerX - 152, this.height / 2 + 40, 304, 20).build());
		this.copyButton.active = this.answerCode != null;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
				.bounds(centerX - 152, this.height / 2 + 66, 304, 20).build());
	}

	@Override
	public void onClose() {
		if(this.state != STATE_CONNECTED) {
			ClientPlatformSingleplayer.closeDirectConnect();
		}
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void tick() {
		switch(this.state) {
		case STATE_GENERATING:
			// Blocking: gathers ICE candidates for the answer code. Runs once.
			this.state = STATE_WAITING;
			try {
				this.answerCode = ClientPlatformSingleplayer.directJoinCreateAnswerCode(this.offerCode);
				if(this.answerCode == null || this.answerCode.isBlank()) {
					this.state = STATE_FAILED;
					this.errorText = "Could not create the answer code";
				}
			}catch(Throwable t) {
				this.state = STATE_FAILED;
				this.errorText = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
			}
			if(this.copyButton != null) {
				this.copyButton.active = this.answerCode != null;
			}
			break;
		case STATE_WAITING:
			if(ClientPlatformSingleplayer.isDirectJoinConnected()) {
				this.state = STATE_CONNECTED;
			}
			break;
		case STATE_CONNECTED:
			this.statusWidget.setMessage(Component.translatableWithFallback("direct.join.connected",
					"Connected! Entering the world..."));
			if(++this.advanceTimer > 10) {
				this.serverData.setConnectionMode(ServerData.ConnectionMode.RELAY);
				this.serverData.ip = "eagler-direct:" + this.offerCode;
				this.callback.accept(true);
			}
			break;
		default:
			break;
		}
		if(this.errorText != null && this.statusWidget != null) {
			this.statusWidget.setMessage(Component.literal(this.errorText).withStyle(ChatFormatting.RED));
		}else if(this.state == STATE_WAITING && this.statusWidget != null) {
			this.statusWidget.setMessage(Component.translatableWithFallback("direct.join.shareAnswer",
					"Send this answer code back to the host"));
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		this.extractMenuBackground(graphics);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int centerX = this.width / 2;
		graphics.centeredText(this.font, this.title, centerX, 20, -1);
		String code = this.answerCode;
		if(code != null && !code.isBlank()) {
			String[] lines = this.wrapCode(code, 300).split("\n");
			int y = this.height / 2 - 30;
			for(int i = 0; i < lines.length; ++i) {
				graphics.centeredText(this.font, Component.literal(lines[i]), centerX, y + i * 10, 0xFF55FFFF);
			}
			graphics.centeredText(this.font, Component.translatableWithFallback("direct.join.chars",
					"%s characters - no server involved", code.length()), centerX, y + lines.length * 10 + 4, 0xFF8FD18F);
		}
	}

	private String wrapCode(String code, int maxWidth) {
		int chunk = Math.max(8, 300 / Math.max(1, this.font.width("M")));
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < code.length(); i += chunk) {
			if(i > 0) sb.append('\n');
			sb.append(code, i, Math.min(code.length(), i + chunk));
		}
		return sb.toString();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}
}
