package net.minecraft.client.gui.screens;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.multiplayer.ServerData;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.socket.AddressResolver;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings.Profile;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class ManageServerScreen extends Screen {
   private static final Component NAME_LABEL = Component.translatable("manageServer.enterName");
   private static final Component IP_LABEL = Component.translatable("manageServer.enterIp");
   private static final Component DEFAULT_SERVER_NAME = Component.translatable("selectServer.defaultName");
   private Button addButton;
	   private Button relayButton;
	   private Button wispButton;
	   private Button eaglerXButton;
	   private Button directTCPButton;
	   private Button webSocketSchemeButton;
   private final BooleanConsumer callback;
   private final ServerData serverData;
   private EditBox ipEdit;
   private EditBox nameEdit;
	   private ServerData.ConnectionMode connectionMode;
	   private boolean normalizingWebSocketAddress;
	   private boolean secureWebSocket = true;
	   private boolean webSocketSchemeInitialized;
   private final Screen lastScreen;

   public ManageServerScreen(final Screen lastScreen, final Component title, final BooleanConsumer callback, final ServerData serverData) {
      super(title);
      this.lastScreen = lastScreen;
      this.callback = callback;
      this.serverData = serverData;
   }

   @Override
   protected void init() {
      Profile relay = RelaySettings.primary();
      boolean isolatedWebApp = PlatformNetworking.isIsolatedWebApp() && !PlatformNetworking.isWispcraftLoaded();
      boolean directTCPAvailable = PlatformNetworking.supportsDirectTCP();
	      if (this.connectionMode == null) {
         ServerData.ConnectionMode savedMode = this.serverData.connectionMode();
         boolean newEntry = this.serverData.ip == null || this.serverData.ip.isBlank();
         this.connectionMode = isolatedWebApp && newEntry
            ? ServerData.ConnectionMode.DIRECT_TCP : savedMode;
         if (isolatedWebApp && this.connectionMode == ServerData.ConnectionMode.RELAY) {
            this.connectionMode = directTCPAvailable
               ? ServerData.ConnectionMode.DIRECT_TCP : ServerData.ConnectionMode.EAGLERX;
         }
         if (newEntry && this.connectionMode == ServerData.ConnectionMode.DIRECT_TCP && !directTCPAvailable) {
            this.connectionMode = ServerData.ConnectionMode.RELAY;
         }
         if (newEntry && !PlatformNetworking.isWispcraftLoaded() && this.connectionMode == ServerData.ConnectionMode.RELAY && relay == null
            && EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP) {
            this.connectionMode = ServerData.ConnectionMode.EAGLERX;
	         }
	         // Older entries may have stored an explicit EaglerX URI while their
	         // connectionMode field still says relay. Preserve that URI's mode so
	         // the scheme control is visible during the edit itself.
	         if (AddressResolver.looksLikeEaglerXAddress(this.serverData.ip)) {
	            this.connectionMode = ServerData.ConnectionMode.EAGLERX;
	         }
	      }
	      if (!this.webSocketSchemeInitialized) {
	         String savedAddress = this.serverData.ip == null ? "" : this.serverData.ip.trim();
	         this.secureWebSocket = !savedAddress.regionMatches(true, 0, "ws://", 0, 5);
	         this.webSocketSchemeInitialized = true;
	      }
      String target = this.connectionMode == ServerData.ConnectionMode.RELAY || this.connectionMode == ServerData.ConnectionMode.WISP
         ? AddressResolver.extractTarget(this.serverData.ip, this.serverData.ip)
         : AddressResolver.stripEaglerXScheme(this.serverData.ip);
      this.nameEdit = new EditBox(this.font, this.width / 2 - 100, 42, 200, 20, NAME_LABEL);
      this.nameEdit.setValue(this.serverData.name);
      this.nameEdit.setHint(DEFAULT_SERVER_NAME);
      this.nameEdit.setResponder(v -> this.updateAddButtonStatus());
      this.addWidget(this.nameEdit);
      this.ipEdit = this.createAddressEdit(target);
      this.relayButton = this.addRenderableWidget(
         Button.builder(ServerData.ConnectionMode.RELAY.displayName(), button ->
            this.selectConnectionMode(ServerData.ConnectionMode.RELAY)).bounds(0, 108, 50, 20).build());
      this.wispButton = this.addRenderableWidget(
         Button.builder(ServerData.ConnectionMode.WISP.displayName(), button ->
            this.selectConnectionMode(ServerData.ConnectionMode.WISP)).bounds(0, 108, 50, 20).build());
      this.eaglerXButton = this.addRenderableWidget(
         Button.builder(Component.translatableWithFallback("network.mode.eaglerx", "EaglerX"), button -> this.selectConnectionMode(ServerData.ConnectionMode.EAGLERX))
            .bounds(0, 108, 50, 20)
            .build()
      );
      if (directTCPAvailable) {
         this.directTCPButton = this.addRenderableWidget(
            Button.builder(Component.translatableWithFallback("network.mode.tcp", "TCP"), button -> this.selectConnectionMode(ServerData.ConnectionMode.DIRECT_TCP))
               .bounds(0, 108, 50, 20)
               .build()
         );
      }
      this.layoutConnectionButtons();
      this.addRenderableWidget(
         CycleButton.builder(ServerData.ServerPackStatus::getName, this.serverData.getResourcePackStatus())
            .withValues(ServerData.ServerPackStatus.values())
            .create(
               this.width / 2 - 100,
               148,
               200,
               20,
               Component.translatable("manageServer.resourcePack"),
               (button, value) -> this.serverData.setResourcePackStatus(value)
            )
      );
      this.addButton = this.addRenderableWidget(
         Button.builder(CommonComponents.GUI_DONE, button -> this.onAdd()).bounds(this.width / 2 - 100, 176, 200, 20).build()
      );
      this.addRenderableWidget(
         Button.builder(CommonComponents.GUI_CANCEL, button -> this.callback.accept(false))
            .bounds(this.width / 2 - 100, 200, 200, 20)
            .build()
      );
      this.updateConnectionButtons();
      this.updateAddButtonStatus();
   }

	   private String addressPrefix() {
	      return this.connectionMode == ServerData.ConnectionMode.EAGLERX ? this.secureWebSocket ? "wss://" : "ws://"
	         : this.connectionMode == ServerData.ConnectionMode.WISP ? "java://" : "";
	   }

	   private void layoutConnectionButtons() {
	      Button[] buttons = {this.relayButton, this.wispButton, this.eaglerXButton, this.directTCPButton};
	      int count = 0;
	      for(Button button : buttons) if(button != null) ++count;
	      int gap = 2;
	      int width = (200 - gap * (count - 1)) / count;
	      int x = this.width / 2 - 100;
	      for(Button button : buttons) {
	         if(button != null) {
	            button.setX(x);
	            button.setWidth(width);
	            x += width + gap;
	         }
	      }
	   }

	   private int webSocketSchemeButtonWidth() {
	      return Math.max(this.font.width("wss://") + 8, 42);
	   }

	   private void updateWebSocketSchemeButton() {
	      if (this.webSocketSchemeButton == null) {
	         return;
	      }
	      this.webSocketSchemeButton.setMessage(Component.literal(this.addressPrefix()));
	      String insecureAddress = AddressResolver.buildEaglerXURI(this.ipEdit == null ? ""
	         : this.ipEdit.getValue(), false);
	      boolean insecureBlocked = RelaySettings.isMixedContentBlocked(insecureAddress);
	      this.webSocketSchemeButton.active = this.connectionMode == ServerData.ConnectionMode.EAGLERX
	         && (!insecureBlocked || !this.secureWebSocket);
	      this.webSocketSchemeButton.setTooltip(Tooltip.create(insecureBlocked
	         ? Component.translatableWithFallback("multiplayer.websocket.secureRequired",
	            "HTTPS pages block public ws:// relays; localhost and other loopback relays are allowed.")
	         : this.secureWebSocket
            ? Component.translatableWithFallback("multiplayer.websocket.switchInsecure",
               "Click for ws:// local servers (file://, http:// or a supported local app).")
	            : Component.translatableWithFallback("multiplayer.websocket.switchSecure",
	               "Unencrypted ws:// selected. Click to switch back to wss://.")));
	   }

	   private void toggleWebSocketScheme() {
	      if (this.connectionMode != ServerData.ConnectionMode.EAGLERX) return;
	      boolean nextSecure = !this.secureWebSocket;
	      String candidate = AddressResolver.buildEaglerXURI(this.ipEdit.getValue(), nextSecure);
	      if (!nextSecure && RelaySettings.isMixedContentBlocked(candidate)) return;
	      this.secureWebSocket = nextSecure;
	      this.updateWebSocketSchemeButton();
	      this.updateAddButtonStatus();
	   }

	   private void selectExplicitWebSocketScheme(final String value) {
	      String trimmed = value == null ? "" : value.trim();
	      if (trimmed.regionMatches(true, 0, "wss://", 0, 6)) {
	         this.secureWebSocket = true;
	      } else if (trimmed.regionMatches(true, 0, "ws://", 0, 5)) {
	         this.secureWebSocket = false;
	      }
	   }

   private String stripWispScheme(final String value) {
	      if (value == null) return "";
	      String trimmed = value.trim();
	      if (trimmed.regionMatches(true, 0, "java://", 0, 7)) return trimmed.substring(7);
	      if (AddressResolver.isWispURI(trimmed)) return AddressResolver.extractTarget(trimmed, "");
	      return value;
   }

	   private EditBox createAddressEdit(final String value) {
	      String prefix = this.addressPrefix();
	      boolean webSocket = this.connectionMode == ServerData.ConnectionMode.EAGLERX;
	      int prefixWidth = prefix.isEmpty() ? 0 : webSocket ? this.webSocketSchemeButtonWidth() + 2 : this.font.width(prefix) + 3;
	      if (webSocket) {
	         this.webSocketSchemeButton = this.addRenderableWidget(
	            Button.builder(Component.literal(prefix), button -> this.toggleWebSocketScheme())
	               .bounds(this.width / 2 - 100, 76, this.webSocketSchemeButtonWidth(), 20)
	               .build()
	         );
	         this.updateWebSocketSchemeButton();
	      } else {
	         this.webSocketSchemeButton = null;
	      }
	      EditBox edit = new EditBox(this.font, this.width / 2 - 100 + prefixWidth, 76, 200 - prefixWidth, 20, IP_LABEL);
      edit.setMaxLength(128);
      edit.setValue(this.connectionMode == ServerData.ConnectionMode.EAGLERX
         ? AddressResolver.stripEaglerXScheme(value) : this.stripWispScheme(value));
      edit.setResponder(v -> {
         String normalized = this.stripWispScheme(v);
         if (!normalized.equals(v)) {
            edit.setValue(normalized);
            return;
         }
	         if (this.connectionMode == ServerData.ConnectionMode.EAGLERX && !this.normalizingWebSocketAddress) {
	            String trimmed = v == null ? "" : v.trim();
	            if (trimmed.regionMatches(true, 0, "wss://", 0, 6)
	                  || trimmed.regionMatches(true, 0, "ws://", 0, 5)) {
	               this.selectExplicitWebSocketScheme(trimmed);
	               String stripped = AddressResolver.stripEaglerXScheme(trimmed);
	               if (!stripped.equals(v)) {
	                  this.normalizingWebSocketAddress = true;
	                  edit.setValue(stripped);
	                  this.normalizingWebSocketAddress = false;
	                  return;
	               }
	            }
	         }
	         this.updateWebSocketSchemeButton();
         this.updateAddButtonStatus();
      });
      this.addWidget(edit);
      return edit;
   }

   private void selectConnectionMode(final ServerData.ConnectionMode value) {
      if (value == this.connectionMode) {
         return;
      }
	      String valueBeforeSwitch = this.stripWispScheme(this.ipEdit.getValue());
	      if (value == ServerData.ConnectionMode.EAGLERX) {
	         this.selectExplicitWebSocketScheme(valueBeforeSwitch);
	      }
	      this.removeWidget(this.ipEdit);
	      if (this.webSocketSchemeButton != null) {
	         this.removeWidget(this.webSocketSchemeButton);
	         this.webSocketSchemeButton = null;
	      }
      this.connectionMode = value;
      this.ipEdit = this.createAddressEdit(AddressResolver.stripEaglerXScheme(valueBeforeSwitch));
      this.setFocused(this.ipEdit);
      this.updateConnectionButtons();
      this.updateAddButtonStatus();
   }

   private void updateConnectionButtons() {
      Profile relay = RelaySettings.primary();
      boolean relayAvailable = EagRuntime.getPlatformType() == EnumPlatformType.DESKTOP || relay != null;
      if (this.relayButton != null) {
         this.relayButton.active = relayAvailable || this.connectionMode == ServerData.ConnectionMode.RELAY;
         Component relayLabel = ServerData.ConnectionMode.RELAY.displayName();
         this.relayButton.setMessage(this.connectionMode == ServerData.ConnectionMode.RELAY
            ? Component.translatableWithFallback("network.mode.active", "> %s <", relayLabel) : relayLabel);
         this.relayButton.setTooltip(relayAvailable ? null
            : Tooltip.create(Component.translatableWithFallback("multiplayer.relay.missingForJoin",
               "No relay set. Add at least one relay point from the Multiplayer screen.")));
      }
      if (this.wispButton != null) {
         boolean available = RelaySettings.wispcraft() != null;
         Component label = ServerData.ConnectionMode.WISP.displayName();
         this.wispButton.active = available || this.connectionMode == ServerData.ConnectionMode.WISP;
         this.wispButton.setMessage(this.connectionMode == ServerData.ConnectionMode.WISP
            ? Component.translatableWithFallback("network.mode.active", "> %s <", label) : label);
         this.wispButton.setTooltip(available ? null
            : Tooltip.create(Component.translatableWithFallback("multiplayer.wisp.missing",
               "Wispcraft is not loaded or its Wisp Server endpoint is unavailable.")));
      }
      if (this.eaglerXButton != null) {
         Component label = Component.translatableWithFallback("network.mode.eaglerx", "EaglerX");
         this.eaglerXButton.setMessage(this.connectionMode == ServerData.ConnectionMode.EAGLERX
            ? Component.translatableWithFallback("network.mode.active", "> %s <", label) : label);
      }
      if (this.directTCPButton != null) {
         Component label = Component.translatableWithFallback("network.mode.tcp", "TCP");
         this.directTCPButton.setMessage(this.connectionMode == ServerData.ConnectionMode.DIRECT_TCP
            ? Component.translatableWithFallback("network.mode.active", "> %s <", label) : label);
      }
   }

   @Override
   protected void setInitialFocus() {
      this.setInitialFocus(this.nameEdit);
   }

   @Override
   public void resize(final int width, final int height) {
      String oldIpEdit = this.ipEdit.getValue();
      String oldNameEdit = this.nameEdit.getValue();
      this.init(width, height);
      this.ipEdit.setValue(oldIpEdit);
      this.nameEdit.setValue(oldNameEdit);
   }

	   private void onAdd() {
      String name = this.nameEdit.getValue();
      this.serverData.name = name.isEmpty() ? DEFAULT_SERVER_NAME.getString() : name;
	      if (this.connectionMode == ServerData.ConnectionMode.RELAY
	         && AddressResolver.looksLikeEaglerXAddress(this.ipEdit.getValue())) {
	         this.selectExplicitWebSocketScheme(this.ipEdit.getValue());
	         if (!RelaySettings.isValidAddress(AddressResolver.buildEaglerXURI(this.ipEdit.getValue(), this.secureWebSocket))) {
	            this.updateAddButtonStatus();
            return;
	         }
	         this.connectionMode = ServerData.ConnectionMode.EAGLERX;
	      }
	      Profile wisp = this.connectionMode == ServerData.ConnectionMode.WISP ? RelaySettings.wispcraft() : null;
	      if (this.connectionMode == ServerData.ConnectionMode.WISP && wisp == null) {
	         this.updateAddButtonStatus();
	         return;
	      }
	      this.serverData.setConnectionMode(this.connectionMode);
      Profile relay = RelaySettings.primary();
      this.serverData.ip = this.connectionMode == ServerData.ConnectionMode.DIRECT_TCP
         ? this.ipEdit.getValue().trim()
	         : this.connectionMode == ServerData.ConnectionMode.EAGLERX
	         ? AddressResolver.buildEaglerXURI(this.ipEdit.getValue(), this.secureWebSocket)
	         : this.connectionMode == ServerData.ConnectionMode.WISP
	         ? wisp.connectionURI(this.ipEdit.getValue())
         : relay == null
            ? this.ipEdit.getValue().trim()
            : relay.connectionURI(this.ipEdit.getValue());
      this.callback.accept(true);
   }

   @Override
   public void onClose() {
      this.minecraft.gui.setScreen(this.lastScreen);
   }

   private void updateAddButtonStatus() {
	      boolean explicitEaglerX = AddressResolver.looksLikeEaglerXAddress(this.ipEdit.getValue());
	      this.addButton.active = AddressResolver.isValidTargetSyntax(this.ipEdit.getValue())
	         && (this.connectionMode != ServerData.ConnectionMode.EAGLERX
	            || RelaySettings.isValidAddress(AddressResolver.buildEaglerXURI(this.ipEdit.getValue(), this.secureWebSocket)))
	         && (!explicitEaglerX || RelaySettings.isValidAddress(AddressResolver.buildEaglerXURI(this.ipEdit.getValue(), this.secureWebSocket)))
         && (this.connectionMode == ServerData.ConnectionMode.EAGLERX
            || this.connectionMode == ServerData.ConnectionMode.DIRECT_TCP && PlatformNetworking.supportsDirectTCP()
	            || this.connectionMode == ServerData.ConnectionMode.WISP && RelaySettings.wispcraft() != null
	            || this.connectionMode == ServerData.ConnectionMode.RELAY
	               && (net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
	                  == net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP || RelaySettings.primary() != null));
   }

   @Override
   public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
      super.extractRenderState(graphics, mouseX, mouseY, a);
      graphics.centeredText(this.font, this.title, this.width / 2, 17, -1);
      graphics.text(this.font, NAME_LABEL, this.width / 2 - 100 + 1, 30, -6250336);
      graphics.text(this.font, IP_LABEL, this.width / 2 - 100 + 1, 64, -6250336);
      Profile relay = RelaySettings.primary();
      String prefix = this.addressPrefix();
	      if (!prefix.isEmpty() && this.connectionMode != ServerData.ConnectionMode.EAGLERX) {
	         graphics.text(this.font, Component.literal(prefix), this.width / 2 - 99, 82, 0xFFFFFFFF);
	      }
      graphics.text(this.font, this.connectionMode == ServerData.ConnectionMode.DIRECT_TCP
         ? PlatformNetworking.isIsolatedWebApp()
            ? Component.translatableWithFallback("multiplayer.connection.direct", "Direct connection")
            : Component.translatableWithFallback("multiplayer.connection.directTcp", "Direct TCP: relay not used")
         : this.connectionMode == ServerData.ConnectionMode.EAGLERX
            ? Component.translatableWithFallback("multiplayer.connection.eaglerx", "Direct EaglerX connection")
	         : this.connectionMode == ServerData.ConnectionMode.WISP
	            ? RelaySettings.wispcraft() == null
	               ? Component.translatableWithFallback("multiplayer.connection.wispUnavailable", "Wisp unavailable: configure Wispcraft Wisp Server")
	               : Component.translatableWithFallback("multiplayer.connection.wispSelected", "WISP via %s", RelaySettings.wispcraft().address)
	            : relay == null ? Component.translatableWithFallback("multiplayer.relay.noneConfigured", "No relay configured")
               : Component.translatableWithFallback("multiplayer.relay.selected", "Relay: %s", relay.displayName()),
         this.width / 2 - 99, 133, 0xFFAAAAAA);
      this.nameEdit.extractRenderState(graphics, mouseX, mouseY, a);
      this.ipEdit.extractRenderState(graphics, mouseX, mouseY, a);
   }
}
