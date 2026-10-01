package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class IsolatedAppGuideScreen extends Screen {

	private static final String FLAG_ADDRESS = "chrome://flags/#enable-isolated-web-apps";
	private static final String WEB_APP_INTERNALS_ADDRESS = "chrome://web-app-internals/";
	private static final Component MESSAGE = Component.translatableWithFallback("isolatedApp.message",
			"Connect directly to Java servers without a multiplayer relay. Chrome desktop or ChromeOS 151+ is required.\n\n"
			+ "1. Build your client in the Eaglercraft patcher, then create an isolated app (.swbn) from that build.\n"
			+ "2. Open the copied Chrome flags address. Enable both Enable Isolated Web Apps and Enable Isolated Web App Developer Mode, then restart Chrome.\n"
			+ "3. Open the copied Web App Internals address in Chrome.\n"
			+ "4. Under Developer Mode, find Install IWA from Signed Web Bundle, click Select file..., and choose the .swbn made by the patcher.\n"
			+ "5. Launch the app, open Multiplayer, and enter the server address.\n"
			+ "6. Later, open chrome://apps/ and click Eaglercraft 26.2 to launch it again.");

	private final Screen parent;
	private final GridLayout layout = new GridLayout().rowSpacing(10);

	public IsolatedAppGuideScreen(Screen parent) {
		super(Component.translatableWithFallback("isolatedApp.title", "Install isolated app (Chrome preview)"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.layout.defaultCellSetting().alignHorizontallyCenter();
		GridLayout.RowHelper rows = this.layout.createRowHelper(1);
		rows.addChild(new StringWidget(this.title.copy().withStyle(ChatFormatting.BOLD), this.font));
		rows.addChild(new MultiLineTextWidget(MESSAGE, this.font)
				.setMaxWidth(Math.min(460, this.width - 40)).setCentered(true));

		GridLayout buttons = new GridLayout().columnSpacing(5).rowSpacing(4);
		GridLayout.RowHelper buttonRows = buttons.createRowHelper(2);
		buttonRows.addChild(Button.builder(Component.translatableWithFallback("isolatedApp.copyFlags", "Copy flag address"), button ->
				this.minecraft.keyboardHandler.setClipboard(FLAG_ADDRESS)).size(180, 20).build());
		buttonRows.addChild(Button.builder(Component.translatableWithFallback("isolatedApp.copySetup", "Copy setup address"), button ->
				this.minecraft.keyboardHandler.setClipboard(WEB_APP_INTERNALS_ADDRESS)).size(180, 20).build());
		rows.addChild(buttons);
		rows.addChild(Button.builder(CommonComponents.GUI_BACK, button -> this.onClose())
				.size(180, 20).build());
		this.repositionElements();
		this.layout.visitWidgets(this::addRenderableWidget);
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		FrameLayout.centerInRectangle(this.layout, this.getRectangle());
	}

	@Override
	public Component getNarrationMessage() {
		return CommonComponents.joinForNarration(super.getNarrationMessage(), MESSAGE);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
