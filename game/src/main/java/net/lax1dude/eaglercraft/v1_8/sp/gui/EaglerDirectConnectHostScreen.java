package net.lax1dude.eaglercraft.v1_8.sp.gui;

import java.util.Collections;
import java.util.Objects;

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
 * the room mints one invite code per guest (QR + text with a copy button) and
 * accepts that guest's answer code back. Guests already in the world keep
 * playing while the next invite is handed out, because a WebRTC peer
 * connection is a 1:1 transport.
 *
 * The peer connections use the free public STUN servers, so the codes also
 * carry a server-reflexive candidate for players behind NAT; there is still
 * no relay server and no signaling server involved.
 */
public class EaglerDirectConnectHostScreen extends Screen {

	private static final int COPY_FEEDBACK_TICKS = 40;
	private static final int MINT_RETRY_TICKS = 40;

	private final Screen parent;
	private final int gameMode;
	private final boolean allowCommands;

	private StringWidget statusWidget;
	private EditBox answerBox;
	private Button completeButton;
	private Button copyButton;
	private boolean published;
	private String errorText;
	private int autoCloseTimer = -1;
	private int copyFeedbackTimer = -1;
	private int mintCooldown;
	private String lastCode;
	private boolean awaitFirstGuest;

	public EaglerDirectConnectHostScreen(Screen parent, int gameMode, boolean allowCommands) {
		super(Component.translatableWithFallback("direct.host.title", "Direct Connect Room"));
		this.parent = parent;
		this.gameMode = gameMode;
		this.allowCommands = allowCommands;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		this.statusWidget = this.addRenderableWidget(new StringWidget(0, 24, 0, 9,
				Component.translatableWithFallback("direct.host.creating", "Preparing the first invite code..."), this.font));
		this.answerBox = this.addRenderableWidget(new EditBox(this.font, centerX - 152, this.height / 2 + 66, 260, 20,
				Component.translatableWithFallback("direct.host.answerHint", "Paste the guest's answer code")));
		this.answerBox.setMaxLength(1024);
		this.answerBox.setHint(Component.translatableWithFallback("direct.host.answerHint",
				"Paste the guest's answer code"));
		this.answerBox.setResponder(value -> this.updateConnectButton());
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("direct.paste", "Paste"),
				button -> {
					String clipboard = EagRuntime.getClipboard();
					if(clipboard != null && !clipboard.isBlank()) {
						this.answerBox.setValue(clipboard);
					}
				}).bounds(centerX + 112, this.height / 2 + 66, 40, 20).build());
		this.copyButton = this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("direct.host.copy", "Copy Invite Code"), button -> {
					String code = SingleplayerServerController26.getLANRelayCode();
					if(code != null && !code.isBlank()) {
						EagRuntime.setClipboard(code);
						button.setMessage(Component.translatableWithFallback("direct.copied", "Copied!")
								.withStyle(ChatFormatting.GREEN));
						this.copyFeedbackTimer = COPY_FEEDBACK_TICKS;
					}
				}).bounds(centerX - 152, this.height / 2 + 30, 148, 20).build());
		this.completeButton = this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("direct.host.connectGuest", "Connect Guest"), button -> {
					String code = this.answerBox.getValue().trim();
					if(code.isEmpty()) {
						return;
					}
					try {
						SingleplayerServerController26.directHostCompleteAnswer(code);
						this.errorText = null;
						this.answerBox.setValue("");
						this.setStatus(Component.translatableWithFallback("direct.host.handshake",
								"Guest connecting - the next invite code is ready below"));
					}catch(Throwable t) {
						this.errorText = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
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
		this.lastCode = SingleplayerServerController26.getLANRelayCode();
		if(this.published && this.lastCode == null) {
			// The previous invite was consumed by a guest, or a mint failed:
			// the room always keeps one offer code ready for the next guest.
			this.mintInvite();
		}
		this.updateCopyButton();
		this.updateConnectButton();
		this.setStatus(Component.translatableWithFallback("direct.host.shareTitle",
				"Send a friend the invite code below, then paste their answer code"));
		if(this.errorText != null) {
			this.setErrorStatus();
		}
		// Only the first guest sends the host back into the world; while a guest
		// is already connected this screen stays so another invite can be shared.
		this.awaitFirstGuest = SingleplayerServerController26.getDirectConnectedGuestCount() == 0;
	}

	private void mintInvite() {
		try {
			SingleplayerServerController26.directHostNewInviteCode();
			this.mintCooldown = 0;
		}catch(Throwable t) {
			this.errorText = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
			this.mintCooldown = MINT_RETRY_TICKS;
		}
	}

	private void updateCopyButton() {
		if(this.copyButton != null) {
			this.copyButton.setMessage(Component.translatableWithFallback("direct.host.copy", "Copy Invite Code"));
		}
	}

	private void updateConnectButton() {
		if(this.completeButton != null && this.answerBox != null) {
			this.completeButton.active = this.published && !this.answerBox.getValue().isBlank();
		}
	}

	private void setStatus(Component message) {
		if(this.statusWidget == null) {
			return;
		}
		this.statusWidget.setMessage(message);
		// StringWidget draws left-aligned from its x, so center it by hand.
		this.statusWidget.setX(this.width / 2 - this.font.width(message) / 2);
	}

	private void setErrorStatus() {
		if(this.statusWidget != null && this.errorText != null) {
			this.setStatus(Component.literal(this.errorText).withStyle(ChatFormatting.RED));
		}
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
			String code = SingleplayerServerController26.getLANRelayCode();
			if(!Objects.equals(code, this.lastCode)) {
				this.lastCode = code;
				this.copyFeedbackTimer = -1;
				this.updateCopyButton();
			}
			if(code == null && this.errorText == null) {
				if(this.mintCooldown > 0) {
					--this.mintCooldown;
				}else {
					// The invite was consumed: keep the next code ready so the room
					// can hand out one invite per guest without leaving the screen.
					this.mintInvite();
				}
			}
			int guests = SingleplayerServerController26.getDirectGuestCount();
			int connected = SingleplayerServerController26.getDirectConnectedGuestCount();
			if(connected > 0) {
				if(this.awaitFirstGuest) {
					this.awaitFirstGuest = false;
					this.autoCloseTimer = 30;
				}
				this.setStatus(Component.translatableWithFallback("direct.host.guests",
						"%s guest(s) connected - invite someone else any time", connected));
			}else if(guests > 0) {
				this.setStatus(Component.translatableWithFallback("direct.host.handshake",
						"Guest connecting - the next invite code is ready below"));
			}else if(this.errorText == null) {
				this.setStatus(Component.translatableWithFallback("direct.host.shareTitle",
						"Send a friend the invite code below, then paste their answer code"));
			}
			if(this.autoCloseTimer > 0 && --this.autoCloseTimer == 0) {
				this.autoCloseTimer = -1;
				this.published = false;
				this.minecraft.gui.setScreen((Screen) null);
			}
		}
		if(this.copyFeedbackTimer > 0 && --this.copyFeedbackTimer == 0) {
			this.copyFeedbackTimer = -1;
			this.updateCopyButton();
		}
		if(this.errorText != null) {
			this.setErrorStatus();
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
		graphics.centeredText(this.font, this.title, centerX, 10, -1);
		int connected = SingleplayerServerController26.getDirectConnectedGuestCount();
		int guests = SingleplayerServerController26.getDirectGuestCount();
		if(guests > 0) {
			graphics.centeredText(this.font, Component.translatableWithFallback("direct.host.roomStatus",
					"Room: %s invited, %s connected", guests, connected), centerX, 38, 0xFF8FD18F);
		}
		String code = SingleplayerServerController26.getLANRelayCode();
		if(code != null && !code.isBlank()) {
			boolean[][] qr = null;
			try {
				qr = QRCode.encode(code, QRCode.ECC_L);
			}catch(Throwable ignored) {
			}
			int bandTop = 52;
			int qrSize = 0;
			int textWidth = 300;
			int textCenterX = centerX;
			if(qr != null) {
				// QR on the left, code text on the right: a ~150 character code
				// cannot fit beneath the QR at this screen height.
				int scale = Math.max(1, Math.min(3, 84 / qr.length));
				qrSize = qr.length * scale;
				textWidth = 190;
				int groupX = centerX - (qrSize + 14 + textWidth) / 2;
				graphics.fill(groupX - 2, bandTop - 2, groupX + qrSize + 2, bandTop + qrSize + 2, 0xFFFFFFFF);
				for(int qy = 0; qy < qr.length; ++qy) {
					for(int qx = 0; qx < qr.length; ++qx) {
						if(qr[qy][qx]) {
							graphics.fill(groupX + qx * scale, bandTop + qy * scale,
									groupX + (qx + 1) * scale, bandTop + (qy + 1) * scale, 0xFF000000);
						}
					}
				}
				textCenterX = groupX + qrSize + 14 + textWidth / 2;
			}
			String[] lines = this.wrapCode(code, textWidth).split("\n");
			int maxLines = 8;
			for(int i = 0; i < lines.length && i < maxLines; ++i) {
				String line = lines[i];
				if(i == maxLines - 1 && lines.length > maxLines) {
					line = line + "...";
				}
				graphics.centeredText(this.font, Component.literal(line), textCenterX, bandTop + 2 + i * 10,
						0xFF55FFFF);
			}
			int bandHeight = Math.max(qrSize, Math.min(lines.length, maxLines) * 10 + 2);
			graphics.centeredText(this.font, Component.translatableWithFallback("direct.host.chars",
					"%s characters - peer to peer, no relay server", code.length()), centerX, bandTop + bandHeight + 2,
					0xFF8FD18F);
		}
	}

	private String wrapCode(String code, int maxWidth) {
		int chunk = Math.max(8, maxWidth / Math.max(1, this.font.width("M")));
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
