package net.minecraft.client.gui.screens;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.util.Locale;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.socket.AddressResolver;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings.Profile;
import net.lax1dude.eaglercraft.v1_8.sp.gui.EaglerDirectConnectJoinScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class DirectJoinServerScreen extends Screen {
   private static final Component ENTER_IP_LABEL = Component.translatable("manageServer.enterIp");
   // Eagler: https page -> only wss:// endpoints are reachable (1.8 GuiScreenServerList)
   private static final Component SSL_WARN_1 = Component.translatable("eaglercraft.addServer.SSLWarn1");
   private static final Component SSL_WARN_2 = Component.translatable("eaglercraft.addServer.SSLWarn2");
   private Button selectButton;
	   private Button relayButton;
	   private Button wispButton;
	   private Button eaglerXButton;
	   private Button directTCPButton;
	   private Button webSocketSchemeButton;
   private final ServerData serverData;
   private EditBox ipEdit;
   private EditBox joinCodeEdit;
   private final BooleanConsumer callback;
   private final Screen lastScreen;
   private final boolean lanOnly;
	   private ServerData.ConnectionMode connectionMode;
	   private boolean normalizingJoinCode;
	   private boolean normalizingWebSocketAddress;
	   private boolean secureWebSocket = true;
	   private boolean webSocketSchemeInitialized;

   public DirectJoinServerScreen(final Screen lastScreen, final BooleanConsumer callback, final ServerData serverData) {
      this(lastScreen, callback, serverData, false);
   }

   public DirectJoinServerScreen(
      final Screen lastScreen, final BooleanConsumer callback, final ServerData serverData, final boolean lanOnly
   ) {
      super(Component.translatable("selectServer.direct"));
      this.lastScreen = lastScreen;
      this.serverData = serverData;
      this.callback = callback;
      this.lanOnly = lanOnly;
   }

   @Override
   public boolean keyPressed(final KeyEvent event) {
      if (this.selectButton.active && event.isConfirmation()
         && (this.getFocused() == this.ipEdit || this.getFocused() == this.joinCodeEdit)) {
         this.onSelect();
         return true;
      } else {
         return super.keyPressed(event);
      }
   }

   @Override
   protected void init() {
      Profile relay = this.lanOnly ? RelaySettings.primaryLAN() : RelaySettings.primary();
      Profile lanRelay = RelaySettings.primaryLAN();
      boolean isolatedWebApp = PlatformNetworking.isIsolatedWebApp() && !PlatformNetworking.isWispcraftLoaded();
      boolean directTCPAvailable = PlatformNetworking.supportsDirectTCP();
	      if (this.connectionMode == null) {
         ServerData.ConnectionMode savedMode = this.serverData.connectionMode();
         this.connectionMode = this.lanOnly ? ServerData.ConnectionMode.RELAY
            : isolatedWebApp && savedMode != ServerData.ConnectionMode.WISP ? ServerData.ConnectionMode.DIRECT_TCP : savedMode;
         if (this.connectionMode == ServerData.ConnectionMode.DIRECT_TCP && !directTCPAvailable) {
            this.connectionMode = ServerData.ConnectionMode.RELAY;
         }
         if (this.connectionMode == ServerData.ConnectionMode.RELAY && relay == null && lanRelay == null
            && EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP) {
            this.connectionMode = ServerData.ConnectionMode.EAGLERX;
	         }
	         // A persisted explicit EaglerX URI is authoritative even when an
	         // older server-list entry still carries the default "relay" mode.
	         // Otherwise the editor hides the scheme control and treats the URI
	         // as a Java target until the user presses Select.
	         if (!this.lanOnly && AddressResolver.looksLikeEaglerXAddress(this.serverData.ip)) {
	            this.connectionMode = ServerData.ConnectionMode.EAGLERX;
	         }
	      }
	      if (!this.webSocketSchemeInitialized) {
	         String savedAddress = this.serverData.ip == null ? "" : this.serverData.ip.trim();
	         this.secureWebSocket = !savedAddress.regionMatches(true, 0, "ws://", 0, 5);
	         this.webSocketSchemeInitialized = true;
      }
      String saved = this.minecraft.options.lastMpIp;
      if (this.serverData.ip != null) {
         saved = this.connectionMode == ServerData.ConnectionMode.RELAY || this.connectionMode == ServerData.ConnectionMode.WISP
            ? AddressResolver.extractTarget(this.serverData.ip, saved) : AddressResolver.stripEaglerXScheme(this.serverData.ip);
      }

      if (!this.lanOnly) {
         this.ipEdit = this.createAddressEdit(saved);
         this.relayButton = this.addRenderableWidget(
            Button.builder(ServerData.ConnectionMode.RELAY.displayName(), button ->
               this.selectConnectionMode(ServerData.ConnectionMode.RELAY)).bounds(0, 66, 50, 20).build());
         this.wispButton = this.addRenderableWidget(
            Button.builder(ServerData.ConnectionMode.WISP.displayName(), button ->
               this.selectConnectionMode(ServerData.ConnectionMode.WISP)).bounds(0, 66, 50, 20).build());
         this.eaglerXButton = this.addRenderableWidget(
            Button.builder(Component.translatableWithFallback("network.mode.eaglerx", "EaglerX"), button -> this.selectConnectionMode(ServerData.ConnectionMode.EAGLERX))
               .bounds(0, 66, 50, 20)
               .build()
         );
         if (directTCPAvailable) {
            this.directTCPButton = this.addRenderableWidget(
               Button.builder(Component.translatableWithFallback("network.mode.tcp", "TCP"), button -> this.selectConnectionMode(ServerData.ConnectionMode.DIRECT_TCP))
                  .bounds(0, 66, 50, 20)
                  .build()
            );
         }
         this.layoutConnectionButtons();
      }
      int codeY = this.lanOnly ? 72 : 112;
      this.joinCodeEdit = new EditBox(this.font, this.width / 2 - 100, codeY, 156, 20,
         Component.translatableWithFallback("multiplayer.lan.joinCode", "LAN join code"));
      this.joinCodeEdit.setMaxLength(1024);
      if (this.lanOnly) {
         // A "EG..." code pasted here is a relay-free direct connect invite.
         this.joinCodeEdit.setHint(Component.translatableWithFallback("multiplayer.lan.joinCodeHint",
            "Paste a LAN or direct connect code"));
      }
      this.joinCodeEdit.setResponder(value -> {
         if (!this.normalizingJoinCode) {
            String cleaned = value == null ? "" : value.replaceAll("\\s+", "");
            String next;
            if (cleaned.length() >= 2 && cleaned.regionMatches(true, 0, "eg", 0, 2)) {
               // Direct connect codes are "EG" + version + base64url: the payload
               // is case-sensitive, so only normalize the prefix.
               next = "EG" + cleaned.substring(2);
            } else {
               next = cleaned.toUpperCase(Locale.ROOT);
            }
            if (!next.equals(value)) {
               this.normalizingJoinCode = true;
               this.joinCodeEdit.setValue(next);
               this.normalizingJoinCode = false;
            }
         }
         this.updateSelectButtonStatus();
      });
      this.addWidget(this.joinCodeEdit);
      this.addRenderableWidget(
         Button.builder(Component.translatableWithFallback("multiplayer.lan.pasteCode", "Paste"),
            button -> {
               String clipboard = EagRuntime.getClipboard();
               if (clipboard != null && !clipboard.isBlank()) {
                  this.joinCodeEdit.setValue(clipboard);
               }
            }).bounds(this.width / 2 + 60, codeY, 40, 20).build()
      );
      this.selectButton = this.addRenderableWidget(
         Button.builder(Component.translatable("selectServer.select"), button -> this.onSelect())
            .bounds(this.width / 2 - 100, this.lanOnly ? 104 : 144, 200, 20)
            .build()
      );
      this.addRenderableWidget(
         Button.builder(CommonComponents.GUI_CANCEL, button -> this.callback.accept(false))
            .bounds(this.width / 2 - 100, this.lanOnly ? 128 : 168, 200, 20)
            .build()
      );
      this.updateConnectionButtons();
      this.updateSelectButtonStatus();
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
	      this.updateSelectButtonStatus();
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
	               .bounds(this.width / 2 - 100, 38, this.webSocketSchemeButtonWidth(), 20)
	               .build()
	         );
	         this.updateWebSocketSchemeButton();
	      } else {
	         this.webSocketSchemeButton = null;
	      }
	      EditBox edit = new EditBox(this.font, this.width / 2 - 100 + prefixWidth, 38, 200 - prefixWidth, 20, ENTER_IP_LABEL);
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
         this.updateSelectButtonStatus();
      });
      this.addWidget(edit);
      return edit;
   }

   private void selectConnectionMode(final ServerData.ConnectionMode value) {
      if (this.lanOnly || value == this.connectionMode) {
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
      this.updateSelectButtonStatus();
   }

   private void updateConnectionButtons() {
      Profile relay = RelaySettings.primary();
      boolean relayAvailable = EagRuntime.getPlatformType() == EnumPlatformType.DESKTOP
         || relay != null || RelaySettings.primaryLAN() != null;
      if (this.lanOnly) {
         return;
      }
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
      this.setInitialFocus(this.lanOnly ? this.joinCodeEdit : this.ipEdit);
   }

   @Override
   public void resize(final int width, final int height) {
      String oldEdit = this.ipEdit != null ? this.ipEdit.getValue() : "";
      String oldCode = this.joinCodeEdit.getValue();
      this.init(width, height);
      if (this.ipEdit != null) {
         this.ipEdit.setValue(oldEdit);
      }
      this.joinCodeEdit.setValue(oldCode);
   }

   private static boolean isDirectConnectCode(final String code) {
      // "EG" + version char + base64url payload; every real code is far
      // longer than the 5-10 char legacy room codes.
      return code != null && code.length() >= 16 && code.regionMatches(true, 0, "EG", 0, 2);
   }

   private void onSelect() {
      Profile relay = RelaySettings.primary();
      Profile lanRelay = RelaySettings.primaryLAN();
      String joinCode = this.joinCodeEdit.getValue().trim();
      if (isDirectConnectCode(joinCode)) {
         // Relay-free direct connect: the guest reduces the host's offer code
         // to an answer code, returns it, and only then enters the world.
         this.minecraft.gui.setScreen(
            new EaglerDirectConnectJoinScreen(this, this.serverData, this.callback, joinCode));
         return;
      }
      if (this.lanOnly && lanRelay == null) {
         this.updateSelectButtonStatus();
         return;
      }
	  if (this.connectionMode == ServerData.ConnectionMode.WISP && RelaySettings.wispcraft() == null) {
	     this.updateSelectButtonStatus();
	     return;
	  }
	  if (this.connectionMode == ServerData.ConnectionMode.RELAY && !joinCode.isEmpty() && lanRelay == null) {
	     this.updateSelectButtonStatus();
	     return;
	  }
	      if (!this.lanOnly && this.connectionMode == ServerData.ConnectionMode.RELAY
	         && joinCode.isEmpty() && AddressResolver.looksLikeEaglerXAddress(this.ipEdit.getValue())) {
	         this.selectExplicitWebSocketScheme(this.ipEdit.getValue());
	         if (!RelaySettings.isValidAddress(AddressResolver.buildEaglerXURI(this.ipEdit.getValue(), this.secureWebSocket))) {
	            this.updateSelectButtonStatus();
            return;
	         }
	         this.connectionMode = ServerData.ConnectionMode.EAGLERX;
	      }
      this.serverData.setConnectionMode(this.connectionMode);
      this.serverData.ip = this.lanOnly
         ? AddressResolver.buildLegacyLANJoinURI(lanRelay.address, joinCode)
         : this.connectionMode == ServerData.ConnectionMode.DIRECT_TCP
         ? this.ipEdit.getValue().trim()
	         : this.connectionMode == ServerData.ConnectionMode.WISP
	         ? RelaySettings.wispcraft().connectionURI(this.ipEdit.getValue())
	         : this.connectionMode == ServerData.ConnectionMode.EAGLERX
	         ? AddressResolver.buildEaglerXURI(this.ipEdit.getValue(), this.secureWebSocket)
         : !joinCode.isEmpty()
         ? AddressResolver.buildLegacyLANJoinURI(lanRelay.address, joinCode)
         : relay == null
            ? this.ipEdit.getValue().trim()
            : relay.connectionURI(this.ipEdit.getValue());
      this.callback.accept(true);
   }

   @Override
   public void onClose() {
      this.minecraft.gui.setScreen(this.lastScreen);
   }

   @Override
   public void removed() {
      if (this.ipEdit != null) {
         this.minecraft.options.lastMpIp = this.ipEdit.getValue();
         this.minecraft.options.save();
      }
   }

   private void updateSelectButtonStatus() {
      Profile relay = RelaySettings.primary();
      Profile lanRelay = RelaySettings.primaryLAN();
      boolean validAddressTransport = this.connectionMode == ServerData.ConnectionMode.EAGLERX
         || this.connectionMode == ServerData.ConnectionMode.DIRECT_TCP && PlatformNetworking.supportsDirectTCP()
         || this.connectionMode == ServerData.ConnectionMode.WISP && RelaySettings.wispcraft() != null
         || this.connectionMode == ServerData.ConnectionMode.RELAY
            && (EagRuntime.getPlatformType() == EnumPlatformType.DESKTOP || relay != null);
      String joinCode = this.joinCodeEdit.getValue().trim();
      boolean directCode = isDirectConnectCode(joinCode);
      // Legacy Eagler P2P relays still issue five-character room codes, while the
      // integrated relay uses six. Accept both; transport performs the final check.
      boolean validCode = directCode || joinCode.toUpperCase(Locale.ROOT).matches("[A-Z0-9]{5,10}");
      boolean explicitEaglerX = !this.lanOnly && joinCode.isEmpty()
         && AddressResolver.looksLikeEaglerXAddress(this.ipEdit.getValue());
      validAddressTransport |= explicitEaglerX;
      this.joinCodeEdit.active = this.connectionMode == ServerData.ConnectionMode.RELAY || this.lanOnly || directCode;
	      this.selectButton.active = this.lanOnly
         ? (lanRelay != null || directCode) && validCode
         : this.connectionMode == ServerData.ConnectionMode.RELAY && validCode
         ? (lanRelay != null || directCode)
	         : joinCode.isEmpty() && validAddressTransport
	            && (this.connectionMode != ServerData.ConnectionMode.EAGLERX
	               || RelaySettings.isValidAddress(AddressResolver.buildEaglerXURI(this.ipEdit.getValue(), this.secureWebSocket)))
	            && (!explicitEaglerX || RelaySettings.isValidAddress(AddressResolver.buildEaglerXURI(this.ipEdit.getValue(), this.secureWebSocket)))
	            && AddressResolver.isValidTargetSyntax(this.ipEdit.getValue());
   }

   @Override
   public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
      super.extractRenderState(graphics, mouseX, mouseY, a);
      Profile relay = this.lanOnly ? RelaySettings.primaryLAN() : RelaySettings.primary();
      Profile lanRelay = RelaySettings.primaryLAN();
      graphics.centeredText(this.font, this.lanOnly
         ? Component.translatableWithFallback("multiplayer.lan.joinWorld", "Join LAN World") : this.title, this.width / 2, 12, -1);
      if (this.lanOnly) {
         graphics.text(this.font, relay == null
            ? Component.translatableWithFallback("multiplayer.relay.noneConfigured", "No relay configured")
            : Component.translatableWithFallback("multiplayer.relay.selected", "Relay: %s", relay.displayName()),
            this.width / 2 - 99, 44, relay == null ? 0xFFFF5555 : 0xFFAAAAAA);
         graphics.text(this.font, Component.translatableWithFallback("multiplayer.lan.joinCode", "LAN join code"), this.width / 2 - 99, 60, -6250336);
         this.joinCodeEdit.extractRenderState(graphics, mouseX, mouseY, a);
         return;
      }
      graphics.text(this.font, ENTER_IP_LABEL, this.width / 2 - 100 + 1, 26, -6250336);
      String prefix = this.addressPrefix();
	      if (!prefix.isEmpty() && this.connectionMode != ServerData.ConnectionMode.EAGLERX) {
	         graphics.text(this.font, Component.literal(prefix), this.width / 2 - 99, 44, 0xFFFFFFFF);
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
               : relay == null ? Component.translatableWithFallback("multiplayer.connection.none", "No multiplayer relay configured")
               : Component.translatableWithFallback("multiplayer.connection.selected", "Multiplayer relay: %s", relay.displayName()),
         this.width / 2 - 99, 91, 0xFFAAAAAA);
      graphics.text(this.font, lanRelay == null
         ? Component.translatableWithFallback("multiplayer.lan.noneConfigured", "No LAN relay configured")
         : Component.translatableWithFallback("multiplayer.lan.viaCode", "LAN code via: %s", lanRelay.displayName()),
         this.width / 2 - 100 + 1, 100, -6250336);
      if (this.connectionMode != ServerData.ConnectionMode.DIRECT_TCP
         && net.lax1dude.eaglercraft.v1_8.EagRuntime.requireSSL()) {
         graphics.centeredText(this.font, SSL_WARN_1, this.width / 2, 200, 0xFFCCCCFF);
         graphics.centeredText(this.font, SSL_WARN_2, this.width / 2, 210, 0xFFCCCCFF);
      }
      this.ipEdit.extractRenderState(graphics, mouseX, mouseY, a);
      this.joinCodeEdit.extractRenderState(graphics, mouseX, mouseY, a);
   }
}
