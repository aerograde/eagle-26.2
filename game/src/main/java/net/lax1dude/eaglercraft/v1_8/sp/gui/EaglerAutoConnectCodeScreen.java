package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.lan.AutoConnect;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The AutoConnect code fallback, the plan's second tier.
 *
 * <p>Auto-connect is the first tier: the client scans the local network with no
 * server and no codes, and every player it finds appears in the nearby-players
 * list. When the plan's 20-second budget passes without a peer, that list (and a
 * prompt, when the player is on the title screen) opens this screen instead of
 * leaving them waiting.
 *
 * <p>One player presses "Get a Code" and reads out the six characters; the other
 * types them here. Both derive the same ICE credentials from the code
 * (SHA-256), so the same phases run with synchronized, authenticated peers. The
 * status line below shows which phase the module is in.
 */
public class EaglerAutoConnectCodeScreen extends Screen {

	private static final int COPY_FEEDBACK_TICKS = 40;

	private final Screen parent;

	private StringWidget statusWidget;
	private EditBox codeBox;
	private Button copyButton;
	private String shownCode;
	private String errorText;
	private int copyFeedbackTimer = -1;

	public EaglerAutoConnectCodeScreen(Screen parent) {
		super(Component.translatableWithFallback("autoconnect.code.title", "Auto-Connect Code"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		this.shownCode = AutoConnect.getCode();
		this.statusWidget = this.addRenderableWidget(new StringWidget(0, 24, 0, 9,
				Component.translatableWithFallback("autoconnect.code.status", "Scanning for players..."), this.font));
		this.copyButton = this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("autoconnect.code.copy", "Copy Code"), button -> {
					if(this.shownCode != null && !this.shownCode.isEmpty()) {
						EagRuntime.setClipboard(this.shownCode);
						button.setMessage(Component.translatableWithFallback("direct.copied", "Copied!")
								.withStyle(ChatFormatting.GREEN));
						this.copyFeedbackTimer = COPY_FEEDBACK_TICKS;
					}
				}).bounds(centerX - 152, this.height / 2 + 44, 148, 20).build());
		this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("autoconnect.code.get", "Get a Code"), button -> {
					this.shownCode = AutoConnect.newCode();
					if(this.codeBox != null) {
						this.codeBox.setValue("");
					}
					this.errorText = this.shownCode == null
							? "Could not create a code on this page" : null;
				}).bounds(centerX + 4, this.height / 2 + 44, 148, 20).build());
		this.codeBox = this.addRenderableWidget(new EditBox(this.font, centerX - 152, this.height / 2 + 14, 304, 20,
				Component.translatableWithFallback("autoconnect.code.hint", "Type the other player's 6-character code")));
		this.codeBox.setMaxLength(6);
		this.codeBox.setHint(Component.translatableWithFallback("autoconnect.code.hint",
				"Type the other player's 6-character code"));
		this.codeBox.setResponder(value -> this.errorText = null);
		this.addRenderableWidget(Button.builder(
				Component.translatableWithFallback("autoconnect.code.connect", "Connect"), button -> {
					String typed = this.codeBox == null ? "" : this.codeBox.getValue();
					if(AutoConnect.enterCode(typed)) {
						this.errorText = null;
					}else{
						this.errorText = "That is not a valid code";
					}
				}).bounds(centerX - 152, this.height / 2 + 70, 304, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
				.bounds(centerX - 152, this.height / 2 + 96, 304, 20).build());
	}

	private void setStatus(Component message) {
		if(this.statusWidget == null) {
			return;
		}
		this.statusWidget.setMessage(message);
		// StringWidget draws left-aligned from its x, so center it by hand.
		this.statusWidget.setX(this.width / 2 - this.font.width(message) / 2);
	}

	@Override
	public void tick() {
		if(AutoConnect.isInstalled()) {
			String phase = AutoConnect.getPhase();
			this.setStatus(Component.literal((phase == null ? "" : phase + " - ") + AutoConnect.getStatus()));
		}
		if(this.copyFeedbackTimer > 0 && --this.copyFeedbackTimer == 0 && this.copyButton != null) {
			this.copyButton.setMessage(Component.translatableWithFallback("autoconnect.code.copy", "Copy Code"));
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
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
		if(this.shownCode != null && !this.shownCode.isEmpty()) {
			graphics.centeredText(this.font, Component.literal(this.shownCode), centerX, this.height / 2 - 30, 0xFF55FFFF);
			graphics.centeredText(this.font, Component.translatableWithFallback("autoconnect.code.share",
					"Read this code out - the other player types it below"), centerX, this.height / 2 - 16, 0xFF8FD18F);
		}
		if(this.errorText != null) {
			graphics.centeredText(this.font, Component.literal(this.errorText), centerX, this.height / 2 + 126,
					0xFFFF5555);
		}
		graphics.centeredText(this.font, Component.translatableWithFallback("autoconnect.code.hintLine",
				"Auto-connect finds players by itself; a code only connects two players who cannot see each other"),
				centerX, this.height - 30, 0xFFAAAAAA);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}
}
