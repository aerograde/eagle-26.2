package net.lax1dude.eaglercraft.v1_8.sp.gui;

import java.util.ArrayList;
import java.util.List;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;

import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings.Capability;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings.Profile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class RelaySettingsScreen extends Screen {

	private static final Identifier DELETE = Identifier.withDefaultNamespace("widget/cross_button");
	private static final Identifier UP = Identifier.withDefaultNamespace("server_list/move_up");
	private static final Identifier LOCKED = Identifier.withDefaultNamespace("widget/locked_button");
	private static final Identifier UNLOCKED = Identifier.withDefaultNamespace("widget/unlocked_button");
	private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
	private static final Identifier BUTTON_HOVER = Identifier.withDefaultNamespace("widget/button_highlighted");
	private static final int UP_TEXTURE_SIZE = 32;
	private static final int UP_TEXTURE_X = 3;
	private static final int UP_TEXTURE_Y = 5;
	private static final int UP_ICON_WIDTH = 11;
	private static final int UP_ICON_HEIGHT = 7;
	private static final int ACTION_GAP = 3;
	private static final int UP_WIDTH = 26;
	private static final int EDIT_WIDTH = 42;
	private static final int DELETE_WIDTH = 26;
	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 56);
	private RelayList relayList;

	public RelaySettingsScreen(Screen parent) {
		super(Component.translatableWithFallback("relay.title", "Relays"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(this.title, this.font);
		this.relayList = this.layout.addToContents(new RelayList(this.minecraft));
		LinearLayout footer = this.layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignHorizontallyCenter();
		footer.addChild(Button.builder(Component.translatableWithFallback("relay.add", "Add relay"), button ->
				this.minecraft.gui.setScreen(new RelayEditScreen(this, -1))).width(200).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	void refresh() {
		if(this.relayList != null) {
			this.relayList.reload();
		}
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		if(this.relayList != null) {
			this.relayList.updateSize(this.width, this.layout);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	private String fitText(String text, int maxWidth) {
		if(maxWidth <= 0) {
			return "";
		}
		if(this.font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = CommonComponents.ELLIPSIS.getString();
		int clippedWidth = maxWidth - this.font.width(ellipsis);
		return clippedWidth > 0 ? this.font.plainSubstrByWidth(text, clippedWidth) + ellipsis
				: this.font.plainSubstrByWidth(ellipsis, maxWidth);
	}

	static Component capabilityLabel(Capability capability) {
		return switch(capability) {
		case AUTO -> Component.translatableWithFallback("relay.capability.auto", "Auto");
		case SINGLEPLAYER -> Component.translatableWithFallback("relay.capability.singleplayer", "Singleplayer");
		case MULTIPLAYER -> Component.translatableWithFallback("relay.capability.multiplayer", "Multiplayer");
		case BOTH -> Component.translatableWithFallback("relay.capability.both", "Both");
		case WISP -> Component.translatableWithFallback("relay.capability.legacyWisp", "Legacy WISP (inactive)");
		case UNKNOWN -> Component.translatableWithFallback("relay.capability.checking", "Checking");
		};
	}

	private class RelayList extends ObjectSelectionList<RelayList.Entry> {

		RelayList(Minecraft minecraft) {
			super(minecraft, RelaySettingsScreen.this.width, RelaySettingsScreen.this.height - 89, 33, 44);
			this.reload();
		}

		void reload() {
			List<Entry> entries = new ArrayList<>();
			if(PlatformNetworking.isWispcraftLoaded()) entries.add(new Entry(null));
			for(Profile profile : RelaySettings.all()) {
				entries.add(new Entry(profile));
			}
			this.replaceEntries(entries);
		}

		@Override
		public int getRowWidth() {
			return Math.min(420, RelaySettingsScreen.this.width - 24);
		}

		private class Entry extends ObjectSelectionList.Entry<Entry> {
			private final Profile profile;

			Entry(Profile profile) {
				this.profile = profile;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
				if(this.profile == null) {
					int x = this.getContentX() + 6, y = this.getContentY();
					int right = this.getContentRight();
					graphics.text(RelaySettingsScreen.this.font, Component.translatableWithFallback("relay.wispcraftAvailable",
							"WISP connection mode"), x, y + 9, 0xFF55FF55);
					graphics.text(RelaySettingsScreen.this.font, Component.literal(RelaySettingsScreen.this.fitText(
							RelaySettings.wispcraft() == null
									? Component.translatableWithFallback("relay.wispMissingUrl", "Set a valid Wisp URL in Wisp Settings").getString()
									: Component.translatableWithFallback("relay.wispcraftManaged", "Endpoint managed by the loaded Wispcraft script").getString(),
							right - x - 88)), x, y + 22, 0xFFAAAAAA);
					drawTextAction(graphics, mouseX, mouseY, right - 84, y + 20, 84,
							Component.translatableWithFallback("relay.wispSettings", "Wisp Settings"));
					return;
				}
				int index = RelaySettings.all().indexOf(this.profile);
				if(index < 0) {
					return;
				}
				int x = this.getContentX();
				int y = this.getContentY();
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, this.profile.privateRelay ? LOCKED : UNLOCKED, x + 2, y + 11, 20, 20);
				boolean lanDefault = RelaySettings.primaryLAN() == this.profile;
				boolean multiplayerDefault = RelaySettings.primary() == this.profile;
				String prefix = lanDefault && multiplayerDefault
						? Component.translatableWithFallback("relay.defaultBoth", "LAN + Multiplayer default - ").getString()
						: lanDefault ? Component.translatableWithFallback("relay.defaultLan", "LAN default - ").getString()
						: multiplayerDefault ? Component.translatableWithFallback("relay.defaultMultiplayer", "Multiplayer default - ").getString() : "";
				int right = this.getContentRight();
				int textX = x + 26;
				int deleteX = right - DELETE_WIDTH;
				int editX = deleteX - ACTION_GAP - EDIT_WIDTH;
				int upX = index > 0 ? editX - ACTION_GAP - UP_WIDTH : -1;
				int actionLeft = index > 0 ? upX : editX;
				int textWidth = Math.max(0, actionLeft - 6 - textX);
				String title = prefix + this.profile.displayName();
				String detail = "[" + RelaySettingsScreen.capabilityLabel(this.profile.capability()).getString() + "] " + this.profile.address;
				graphics.text(RelaySettingsScreen.this.font,
						Component.literal(RelaySettingsScreen.this.fitText(title, textWidth)), textX, y + 9, -1);
				graphics.text(RelaySettingsScreen.this.font,
						Component.literal(RelaySettingsScreen.this.fitText(detail, textWidth)), textX, y + 22,
						0xFFAAAAAA);

				// Only draw actions that actually work. The old fixed four-slot grid
				// rendered inactive blank boxes and made the small glyphs look misaligned.
				if(index > 0) {
					drawIconAction(graphics, mouseX, mouseY, upX, y + 9, UP);
				}
				drawTextAction(graphics, mouseX, mouseY, editX, y + 9, EDIT_WIDTH,
						Component.translatableWithFallback("relay.edit", "Edit"));
				drawIconAction(graphics, mouseX, mouseY, deleteX, y + 9, DELETE);
			}

			private void drawIconAction(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
					int x, int y, Identifier icon) {
				boolean over = mouseX >= x && mouseX < x + 26 && mouseY >= y && mouseY < y + 24;
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, over ? BUTTON_HOVER : BUTTON, x, y, 26, 24);
				if(icon == UP) {
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon, UP_TEXTURE_SIZE, UP_TEXTURE_SIZE,
							UP_TEXTURE_X, UP_TEXTURE_Y, x + (26 - UP_ICON_WIDTH) / 2,
							y + (24 - UP_ICON_HEIGHT) / 2, UP_ICON_WIDTH, UP_ICON_HEIGHT);
				}else {
					int size = 16;
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon, x + (26 - size) / 2,
							y + (24 - size) / 2, size, size);
				}
			}

			private void drawTextAction(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
					int x, int y, int width, Component label) {
				boolean over = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + 24;
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, over ? BUTTON_HOVER : BUTTON, x, y, width, 24);
				graphics.centeredText(RelaySettingsScreen.this.font, label, x + width / 2, y + 8, -1);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				if(this.profile == null) {
					PlatformNetworking.openWispcraftSettings();
					return true;
				}
				int index = RelaySettings.all().indexOf(this.profile);
				if(index < 0) {
					return false;
				}
				int right = this.getContentRight();
				int deleteX = right - DELETE_WIDTH;
				int editX = deleteX - ACTION_GAP - EDIT_WIDTH;
				int upX = index > 0 ? editX - ACTION_GAP - UP_WIDTH : -1;
				boolean actionY = event.y() >= this.getContentY() + 9 && event.y() < this.getContentY() + 33;
				if(actionY && event.x() >= deleteX && event.x() < deleteX + DELETE_WIDTH) {
					RelaySettings.remove(index);
					RelaySettingsScreen.this.refresh();
				}else if(actionY && event.x() >= editX && event.x() < editX + EDIT_WIDTH) {
					RelaySettingsScreen.this.minecraft.gui.setScreen(new RelayEditScreen(RelaySettingsScreen.this, index));
				}else if(actionY && index > 0 && event.x() >= upX && event.x() < upX + UP_WIDTH) {
					RelaySettings.move(index, -1);
					RelaySettingsScreen.this.refresh();
				}else {
					RelaySettings.setPrimary(index);
					RelaySettingsScreen.this.refresh();
				}
				return true;
			}

			@Override
			public Component getNarration() {
				if(this.profile == null) return Component.translatableWithFallback("relay.wispcraftNarration",
						"WISP connection mode is available. Open Wisp Settings to edit its script managed endpoint.");
				int index = RelaySettings.all().indexOf(this.profile);
				boolean lanDefault = RelaySettings.primaryLAN() == this.profile;
				boolean multiplayerDefault = RelaySettings.primary() == this.profile;
				String prefix = lanDefault && multiplayerDefault
						? Component.translatableWithFallback("relay.narrationDefaultBoth", "LAN and Multiplayer default ").getString()
						: lanDefault ? Component.translatableWithFallback("relay.narrationDefaultLan", "LAN default ").getString()
						: multiplayerDefault ? Component.translatableWithFallback("relay.narrationDefaultMultiplayer", "Multiplayer default ").getString() : "";
				return Component.literal(prefix + this.profile.displayName()
						+ ", " + RelaySettingsScreen.capabilityLabel(this.profile.capability()).getString());
			}

			@Override
			public void updateNarration(NarrationElementOutput output) {
				super.updateNarration(output);
			}
		}
	}
}
