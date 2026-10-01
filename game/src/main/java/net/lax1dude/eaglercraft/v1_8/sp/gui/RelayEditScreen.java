package net.lax1dude.eaglercraft.v1_8.sp.gui;

import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings.Profile;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings.Capability;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.socket.AddressResolver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class RelayEditScreen extends Screen {

	private final RelaySettingsScreen parent;
	private final int index;
	private EditBox nameEdit;
	private EditBox addressEdit;
	private EditBox tokenEdit;
	private Button saveButton;
	private Button webSocketSchemeButton;
	private CycleButton<Boolean> accessButton;
	private boolean privateRelay;
	private Capability capability;
	private boolean secureWebSocket = true;
	private boolean normalizingAddress;

	public RelayEditScreen(RelaySettingsScreen parent, int index) {
		super(index < 0 ? Component.translatableWithFallback("relay.addTitle", "Add relay")
				: Component.translatableWithFallback("relay.editTitle", "Edit relay"));
		this.parent = parent;
		this.index = index;
	}

	@Override
	protected void init() {
		Profile profile = this.index >= 0 ? RelaySettings.all().get(this.index) : null;
		boolean isolatedWebApp = PlatformNetworking.isIsolatedWebApp();
		this.privateRelay = profile != null && profile.privateRelay;
		// Legacy WISP relay profiles remain readable and removable, but WISP is now
		// owned by the injected script's Settings endpoint rather than relay-list data.
		this.capability = profile != null && profile.configuredCapability != Capability.WISP ? profile.configuredCapability
				: isolatedWebApp ? Capability.SINGLEPLAYER : Capability.AUTO;
		String savedAddress = profile == null ? "" : profile.address;
		this.secureWebSocket = !savedAddress.trim().regionMatches(true, 0, "ws://", 0, 5);
		this.nameEdit = new EditBox(this.font, this.width / 2 - 100, 54, 200, 20,
				Component.translatableWithFallback("relay.nameHint", "Relay name"));
		this.nameEdit.setMaxLength(80);
		this.nameEdit.setValue(profile != null ? profile.name : "");
		this.nameEdit.setResponder(value -> this.updateSaveButton());
		this.addRenderableWidget(this.nameEdit);
		int schemeWidth = this.webSocketSchemeButtonWidth();
		this.webSocketSchemeButton = this.addRenderableWidget(
			Button.builder(Component.literal(this.addressPrefix()), button -> this.toggleWebSocketScheme())
				.bounds(this.width / 2 - 100, 94, schemeWidth, 20).build());
		this.addressEdit = new EditBox(this.font, this.width / 2 - 100 + schemeWidth + 2, 94,
			200 - schemeWidth - 2, 20,
				Component.translatableWithFallback("relay.urlHint", "Relay WebSocket URL"));
		this.addressEdit.setMaxLength(256);
		this.addressEdit.setValue(AddressResolver.stripEaglerXScheme(savedAddress));
		this.addressEdit.setResponder(value -> this.onAddressChanged(value));
		this.addRenderableWidget(this.addressEdit);
		CycleButton<Capability> capabilityButton = this.addRenderableWidget(
			CycleButton.builder(RelaySettingsScreen::capabilityLabel, this.capability)
				.withValues(isolatedWebApp ? new Capability[] { Capability.SINGLEPLAYER }
						: new Capability[] { Capability.AUTO, Capability.SINGLEPLAYER,
								Capability.MULTIPLAYER, Capability.BOTH })
				.create(this.width / 2 - 100, 128, 200, 20, Component.translatableWithFallback("relay.capability", "Capability"), (button, value) -> {
					this.capability = value;
					this.updateTokenState();
					this.updateSaveButton();
				})
		);
		capabilityButton.active = true;
		this.accessButton = this.addRenderableWidget(
			CycleButton.builder(value -> Component.translatableWithFallback(value ? "relay.private" : "relay.public",
					value ? "Private" : "Public"), this.privateRelay)
				.withValues(false, true)
				.create(this.width / 2 - 100, 158, 200, 20, Component.translatableWithFallback("relay.access", "Access"), (button, value) -> {
					this.privateRelay = value;
					this.updateTokenState();
					this.updateSaveButton();
				})
		);
		this.tokenEdit = new EditBox(this.font, this.width / 2 - 100, 198, 200, 20,
				Component.translatableWithFallback("relay.accessTokenHint", "Private access token"));
		this.tokenEdit.setMaxLength(2048);
		this.tokenEdit.setValue(profile != null ? profile.token : "");
		this.tokenEdit.setResponder(value -> this.updateSaveButton());
		this.addRenderableWidget(this.tokenEdit);
		this.saveButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.save())
				.bounds(this.width / 2 - 100, 234, 98, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose())
				.bounds(this.width / 2 + 2, 234, 98, 20).build());
		this.updateTokenState();
		this.updateWebSocketSchemeButton();
		this.updateSaveButton();
	}

	private int webSocketSchemeButtonWidth() {
		return Math.max(this.font.width("wss://") + 8, 42);
	}

	private String addressPrefix() {
		return this.secureWebSocket ? "wss://" : "ws://";
	}

	private String fullAddress() {
		return AddressResolver.buildEaglerXURI(this.addressEdit == null ? "" : this.addressEdit.getValue(),
				this.secureWebSocket);
	}

	private void onAddressChanged(String value) {
		if (!this.normalizingAddress) {
			String trimmed = value == null ? "" : value.trim();
			if (trimmed.regionMatches(true, 0, "wss://", 0, 6)
					|| trimmed.regionMatches(true, 0, "ws://", 0, 5)) {
				this.secureWebSocket = !trimmed.regionMatches(true, 0, "ws://", 0, 5);
				String stripped = AddressResolver.stripEaglerXScheme(trimmed);
				if (!stripped.equals(value)) {
					this.normalizingAddress = true;
					this.addressEdit.setValue(stripped);
					this.normalizingAddress = false;
				}
			}
		}
		this.updateWebSocketSchemeButton();
		this.updateSaveButton();
	}

	private void updateWebSocketSchemeButton() {
		if (this.webSocketSchemeButton == null) return;
		this.webSocketSchemeButton.setMessage(Component.literal(this.addressPrefix()));
		String insecureAddress = AddressResolver.buildEaglerXURI(this.addressEdit == null ? ""
				: this.addressEdit.getValue(), false);
		boolean blocked = RelaySettings.isMixedContentBlocked(insecureAddress);
		this.webSocketSchemeButton.active = !blocked || !this.secureWebSocket;
		this.webSocketSchemeButton.setTooltip(Tooltip.create(blocked
				? Component.translatableWithFallback("relay.mixedContent",
						"HTTPS pages block insecure ws:// relays; use wss:// or a localhost relay.")
				: this.secureWebSocket
					? Component.translatableWithFallback("relay.switchInsecure",
						"Click for ws:// local/offline relays.")
					: Component.translatableWithFallback("relay.switchSecure",
						"Unencrypted ws:// selected. Click to switch to wss://.")));
	}

	private void toggleWebSocketScheme() {
		boolean nextSecure = !this.secureWebSocket;
		String candidate = AddressResolver.buildEaglerXURI(this.addressEdit.getValue(), nextSecure);
		if (!nextSecure && RelaySettings.isMixedContentBlocked(candidate)) {
			this.updateWebSocketSchemeButton();
			return;
		}
		this.secureWebSocket = nextSecure;
		this.updateWebSocketSchemeButton();
		this.updateSaveButton();
	}

	private void updateTokenState() {
		if (this.accessButton != null) {
			this.accessButton.active = true;
			this.accessButton.setValue(this.privateRelay);
		}
		this.tokenEdit.visible = this.privateRelay;
		this.tokenEdit.active = this.privateRelay;
		this.tokenEdit.setEditable(this.privateRelay);
		if(!this.privateRelay) {
			this.tokenEdit.setFocused(false);
		}
	}

	private void updateSaveButton() {
		if(this.saveButton != null) {
			this.saveButton.active = RelaySettings.isValidAddress(this.fullAddress())
					&& (!this.privateRelay || !this.tokenEdit.getValue().isBlank());
		}
	}

	private void save() {
		String address = this.fullAddress();
		if(!RelaySettings.isValidAddress(address)
				|| (this.privateRelay && this.tokenEdit.getValue().isBlank())) {
			this.updateSaveButton();
			this.setFocused(this.addressEdit);
			return;
		}
		Capability savedCapability = this.capability;
		Profile profile = new Profile(this.nameEdit.getValue(), address,
				this.privateRelay, this.tokenEdit.getValue(), savedCapability);
		if(this.index < 0) {
			RelaySettings.add(profile);
		}else {
			RelaySettings.replace(this.index, profile);
		}
		this.parent.refresh();
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	protected void setInitialFocus() {
		this.setInitialFocus(this.nameEdit);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 18, -1);
		graphics.text(this.font, Component.translatableWithFallback("relay.name", "Name"), this.width / 2 - 99, 42, -6250336);
		graphics.text(this.font, Component.translatableWithFallback("relay.url", "Relay URL"), this.width / 2 - 99, 82, -6250336);
		String address = this.fullAddress();
		if(!RelaySettings.isValidAddress(address)) {
			Component scheme = RelaySettings.isMixedContentBlocked(address)
					? Component.translatableWithFallback("relay.mixedContent",
							"HTTPS pages block insecure ws:// relays; use wss:// or a localhost relay.")
					: Component.translatableWithFallback("relay.invalidUrl", "Relay URL must start with ws:// or wss://");
			graphics.centeredText(this.font, scheme, this.width / 2, 118, 0xFFFFFF55);
		}
		if(this.privateRelay) {
			graphics.text(this.font, Component.translatableWithFallback("relay.accessToken", "Access token"), this.width / 2 - 99, 186, -6250336);
		}else {
			graphics.centeredText(this.font, Component.translatableWithFallback("relay.publicDescription", "Public relay: no token is sent"),
						this.width / 2, 190, 0xFFAAAAAA);
		}
	}
}
