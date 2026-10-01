package net.lax1dude.eaglercraft.v1_8.sp.gui;

import java.util.Collections;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.sp.SingleplayerServerController26;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Host side of the relay-free "Direct connect" flow, as a room. Publishing
 * reuses the ordinary LAN world pipeline (worker ConfigureLAN + ~!LAN peer
 * bridge), but replaces the signaling relay with manual offer/answer codes:
 * the room mints one offer code per guest (QR + text with a copy button) and
 * accepts that guest's answer code back. Guests already in the world keep
 * playing while the next invite is handed out, because a WebRTC peer
 * connection is a 1:1 transport. No relay server, no STUN/TURN.
 */
public class EaglerDirectConnectHostScreen extends Screen {

	private final Screen parent;
	private final int gameMode;
	private final boolean allowCommands;

	private StringWidget statusWidget;
	private EditBox answerBox;
	private Button completeButton;
	private boolean published;
	private String errorText;
	private int autoCloseTimer = -1;
	private boolean awaitFirstGuest;

	public EaglerDirectConnectHostScreen(Screen parent, int gameMode, boolean allowCommands) {
		super(Component.translatableWithFallback("direct.host.title", "Direct Connect"));
		this.parent = parent;
		this.gameMode = gameMode;
		this.allowCommands = allowCommands;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		this.statusWidget = this.addRenderableWidget(new StringWidget(centerX, 34, 0, 9,
				Component.translatableWithFallback("direct.host.creating", "Creating connect code..."), this.font));
		this.answerBox = this.addRenderableWidget(new EditBox(this.font, centerX - 152, this.height / 2 + 66, 260, 20,
				Component.translatableWithFallback("direct.host.answerHint", "Paste the guest's answer code")));
		this.answerBox.setMaxLength(1024);
		this.answerBox.setHint(Component.translatableWithFallback("direct.host.answerHint",
				"Paste the guest's answer code"));
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("direct.paste", "Paste"),
				button -> {
					String clipboard = EagRuntime.getClipboard();
					if(clipboard != null && !clipboard.isBlank()) {
						this.answerBox.setValue(clipboard);
					}
				}).bounds(centerX + 112, this.height / 2 + 66, 40, 20).build());
		this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("direct.copy", "Copy Code"), button -> {
					String code = SingleplayerServerController26.getLANRelayCode();
					if(code != null && !code.isBlank()) {
						EagRuntime.setClipboard(code);
						button.setMessage(Component.translatableWithFallback("direct.copied", "Copied!")
								.withStyle(ChatFormatting.GREEN));
					}
				}).bounds(centerX - 152, this.height / 2 + 30, 148, 20).build());
		this.completeButton = this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("direct.host.connectGuest", "Connect Guest"), button -> {
					String code = this.answerBox.getValue().trim();
					if(!code.isEmpty()) {
						try {
							SingleplayerServerController26.directHostCompleteAnswer(code);
							this.errorText = null;
							this.statusWidget.setMessage(Component.translatableWithFallback("direct.host.handshake",
									"Guest connecting - the next invite code is ready below"));
						}catch(Throwable t) {
							this.errorText = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
						}
					}
				}).bounds(centerX + 4, this.height / 2 + 30, 148, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("direct.host.back", "Back to Game"),
				button -> this.onClose()).bounds(centerX - 152, this.height / 2 + 92, 304, 20).build());
		if(!this.published) {
			if(SingleplayerServerController26.isDirectConnectHost()) {
				// Re-opened while the room is still hosting: keep the existing
				// links instead of invalidating the codes guests are using.
				this.published = true;
			}else {
				this.published = SingleplayerServerController26.publishLANRelay(
						Collections.singletonList("eagler-direct:"), this.gameMode, this.allowCommands);
			}
			if(!this.published) {
				this.errorText = SingleplayerServerController26.getLANRelayError();
				if(this.errorText == null) {
					this.errorText = "Could not open the world for direct connect";
				}
			}
		}
		if(this.published && SingleplayerServerController26.getLANRelayCode() == null) {
			// The previous invite was consumed by a guest, or a mint failed:
			// the room always keeps one offer code ready for the next guest.
			try {
				SingleplayerServerController26.directHostNewInviteCode();
			}catch(Throwable t) {
				this.errorText = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
			}
		}
		// Only the first guest sends the host back into the world; while a guest
		// is already connected this screen stays so another invite can be shared.
		this.awaitFirstGuest = SingleplayerServerController26.getDirectConnectedGuestCount() == 0;
	}

	@Override
	public void onClose() {
		// Leaving the screen keeps the room up, like an ordinary LAN world: the
		// guests keep playing, the world stops sharing when the world is quit.
		this.published = false;
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void tick() {
		if(this.published) {
			int guests = SingleplayerServerController26.getDirectGuestCount();
			int connected = SingleplayerServerController26.getDirectConnectedGuestCount();
			this.completeButton.active = true;
			if(connected > 0) {
				if(this.awaitFirstGuest) {
					this.awaitFirstGuest = false;
					this.autoCloseTimer = 30;
				}
				this.statusWidget.setMessage(Component.translatableWithFallback("direct.host.guests",
						"%s guest(s) connected - share the next code any time", connected));
			}else if(guests > 0) {
				this.statusWidget.setMessage(Component.translatableWithFallback("direct.host.handshake",
						"Guest connecting - the next invite code is ready below"));
			}else if(this.errorText == null) {
				this.statusWidget.setMessage(Component.translatableWithFallback("direct.host.shareTitle",
						"Send a friend the QR or code, then paste their answer below"));
			}
			if(this.autoCloseTimer > 0 && --this.autoCloseTimer == 0) {
				this.autoCloseTimer = -1;
				this.published = false;
				this.minecraft.gui.setScreen((Screen) null);
			}
		}
		if(this.errorText != null && this.statusWidget != null) {
			this.statusWidget.setMessage(Component.literal(this.errorText).withStyle(ChatFormatting.RED));
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
		int connected = SingleplayerServerController26.getDirectConnectedGuestCount();
		int guests = SingleplayerServerController26.getDirectGuestCount();
		if(guests > 0) {
			graphics.centeredText(this.font, Component.translatableWithFallback("direct.host.roomStatus",
					"Room: %s link(s), %s connected", guests, connected), centerX, 24, 0xFF8FD18F);
		}
		String code = SingleplayerServerController26.getLANRelayCode();
		if(code != null && !code.isBlank()) {
			boolean[][] qr = null;
			try {
				qr = QRCode.encode(code, QRCode.ECC_L);
			}catch(Throwable ignored) {
			}
			int y = 44;
			if(qr != null) {
				int scale = Math.max(1, Math.min(4, 104 / qr.length));
				int size = qr.length * scale;
				int x0 = centerX - size / 2;
				int y0 = y;
				graphics.fill(x0 - 2, y0 - 2, x0 + size + 2, y0 + size + 2, 0xFFFFFFFF);
				for(int qy = 0; qy < qr.length; ++qy) {
					for(int qx = 0; qx < qr.length; ++qx) {
						if(qr[qy][qx]) {
							graphics.fill(x0 + qx * scale, y0 + qy * scale,
									x0 + (qx + 1) * scale, y0 + (qy + 1) * scale, 0xFF000000);
						}
					}
				}
				y += size + 12;
			}
			String[] lines = this.wrapCode(code, 320).split("\n");
			int maxLines = Math.max(2, (this.height / 2 + 24 - y) / 10);
			for(int i = 0; i < lines.length && i < maxLines; ++i) {
				String line = lines[i];
				if(i == maxLines - 1 && lines.length > maxLines) {
					line = line + "...";
				}
				graphics.centeredText(this.font, Component.literal(line), centerX, y + i * 10, 0xFF55FFFF);
			}
			String chars = Component.translatableWithFallback("direct.host.chars",
					"%s characters - no server involved", code.length()).getString();
			graphics.centeredText(this.font, chars, centerX, this.height / 2 + 12, 0xFF8FD18F);
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
