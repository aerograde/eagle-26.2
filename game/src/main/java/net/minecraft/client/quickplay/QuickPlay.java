package net.minecraft.client.quickplay;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformNetworking;
import net.lax1dude.eaglercraft.v1_8.socket.AddressResolver;
import net.lax1dude.eaglercraft.v1_8.sp.relay.RelaySettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class QuickPlay {
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final Component ERROR_TITLE = Component.translatable("quickplay.error.title");
   private static final Component INVALID_IDENTIFIER = Component.translatable("quickplay.error.invalid_identifier");
   private static final Component REALM_CONNECT = Component.translatable("quickplay.error.realm_connect");
   private static final Component TO_TITLE = Component.translatable("gui.toTitle");
   private static final Component TO_WORLD_LIST = Component.translatable("gui.toWorld");
   private static final Component STARTUP_SETUP_TITLE = Component.translatableWithFallback("quickplay.startupSetup.title", "Server connection setup required");
   private static final String URL_STARTUP_PREFIX = "eagler-startup://";

   private record StartupRequest(String address, ServerData.@Nullable ConnectionMode connectionMode, boolean invalidTransport, boolean requiresWisp) {
   }

   public static void connect(final Minecraft minecraft, final GameConfig.QuickPlayVariant quickPlayVariant) {
      if (!quickPlayVariant.isEnabled()) {
         LOGGER.error("Quick play disabled");
         minecraft.gui.setScreen(new TitleScreen());
      } else {
         switch (quickPlayVariant) {
            case GameConfig.QuickPlayMultiplayerData multiplayerData:
               joinMultiplayerWorld(minecraft, multiplayerData.serverAddress());
               break;
            case GameConfig.QuickPlayRealmsData realmsData:
               LOGGER.warn("Realms quick play is not supported");
               minecraft.gui.setScreen(new DisconnectedScreen(new TitleScreen(), ERROR_TITLE, REALM_CONNECT, TO_TITLE));
               break;
            case GameConfig.QuickPlaySinglePlayerData singlePlayerData:
               String worldId = singlePlayerData.worldId();
               if (StringUtil.isBlank(worldId)) {
                  worldId = getLatestSingleplayerWorld(minecraft.getLevelSource());
               }

               joinSingleplayerWorld(minecraft, worldId);
               break;
            case GameConfig.QuickPlayDisabled disabled:
               LOGGER.error("Quick play disabled");
               minecraft.gui.setScreen(new TitleScreen());
               break;
            default:
               throw new MatchException(null, null);
         }
      }
   }

   private static @Nullable String getLatestSingleplayerWorld(final LevelStorageSource levelSource) {
      try {
         List<LevelSummary> levels = levelSource.loadLevelSummaries(levelSource.findLevelCandidates()).get();
         if (levels.isEmpty()) {
            LOGGER.warn("no latest singleplayer world found");
            return null;
         } else {
            return ((LevelSummary)levels.getFirst()).getLevelId();
         }
      } catch (InterruptedException | ExecutionException e) {
         LOGGER.error("failed to load singleplayer world summaries", e);
         return null;
      }
   }

   private static void joinSingleplayerWorld(final Minecraft minecraft, final @Nullable String identifier) {
      if (!StringUtil.isBlank(identifier) && minecraft.getLevelSource().levelExists(identifier)) {
         minecraft.createWorldOpenFlows().openWorld(identifier, () -> minecraft.gui.setScreen(new TitleScreen()));
      } else {
         Screen parent = new SelectWorldScreen(new TitleScreen());
         minecraft.gui.setScreen(new DisconnectedScreen(parent, ERROR_TITLE, INVALID_IDENTIFIER, TO_WORLD_LIST));
      }
   }

   private static void joinMultiplayerWorld(final Minecraft minecraft, final String serverAddressString) {
      ServerList servers = new ServerList(minecraft);
      servers.load();
      ServerData serverData = servers.get(serverAddressString);
      if (serverData == null) {
         serverData = new ServerData(I18n.get("selectServer.defaultName"), serverAddressString, ServerData.Type.OTHER);
         servers.add(serverData, true);
         servers.save();
      }

      ServerAddress serverAddress = ServerAddress.parseString(serverAddressString);
      ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), minecraft, serverAddress, serverData, true, null);
   }

   public static void connectStartupServer(final Minecraft minecraft, final Screen titleScreen, final String requestedAddress) {
      StartupRequest startupRequest = parseStartupRequest(requestedAddress);
      if (startupRequest.invalidTransport()) {
         showStartupSetup(minecraft, titleScreen,
            Component.translatableWithFallback("quickplay.startupSetup.invalidTransport",
               "The startup link contains an invalid transport. Use transport=eaglerx, transport=tcp, transport=relay, or transport=wisp."));
         return;
      }
      // Explicit Wisp links must never silently use the ordinary relay or an offline route.
      if (startupRequest.requiresWisp() && RelaySettings.wispcraft() == null) {
         showStartupSetup(minecraft, titleScreen, PlatformNetworking.isWispcraftLoaded()
            ? Component.translatableWithFallback("quickplay.startupSetup.wispInvalid",
               "The Wispcraft endpoint is missing or invalid. Open Multiplayer > Wisp Settings, configure its Wisp URL, then reload the link.")
            : Component.translatableWithFallback("quickplay.startupSetup.wispRequired",
               "This link requires Wispcraft. Inject the Wispcraft script into this build before opening the link; the URL does not load scripts."));
         return;
      }
      String requested = startupRequest.address();
      String target = AddressResolver.extractTarget(requested, requested);
      if (startupRequest.connectionMode() != ServerData.ConnectionMode.EAGLERX) {
         String stripped = AddressResolver.stripWebSocketScheme(target);
         if (stripped != null) {
            target = stripped;
         }
      }
      if (!AddressResolver.isValidTargetSyntax(target) || !ServerAddress.isValidAddress(target)) {
         showStartupSetup(minecraft, titleScreen,
            Component.translatableWithFallback("quickplay.startupSetup.invalidAddress",
               "The startup link contains an invalid server address. Check the server= value and reload the link."));
         return;
      }

      ServerList servers = new ServerList(minecraft);
      servers.load();
      ServerData serverData = findStartupServer(servers, requested, target, startupRequest.connectionMode());
      if (serverData == null) {
         serverData = createStartupServer(target, requested, startupRequest.connectionMode());
         if (serverData == null) {
            Component reason = startupUnavailableReason(startupRequest.connectionMode());
            showStartupSetup(minecraft, titleScreen, reason);
            return;
         }
      } else if (!startupRouteAvailable(serverData)) {
         Component reason = serverData.connectionMode() == ServerData.ConnectionMode.DIRECT_TCP
            ? Component.translatableWithFallback("quickplay.startupSetup.savedDirectTcpUnavailable",
               "This saved server uses Direct TCP, but Direct TCP is unavailable here. Choose WebSocket or EaglerX in the server editor, then reload the link.")
            : serverData.connectionMode() == ServerData.ConnectionMode.WISP
               ? Component.translatableWithFallback("quickplay.startupSetup.savedWispUnavailable",
                  "This saved server uses WISP, but the Wispcraft Wisp Server endpoint is unavailable. Configure it in Wisp Settings, then reload the link.")
            : Component.translatableWithFallback("quickplay.startupSetup.savedRelayUnavailable",
               "This saved server uses WebSocket routing, but no multiplayer relay is configured. Restore its relay in Relay Settings, then reload the link.");
         showStartupSetup(minecraft, titleScreen, reason);
         return;
      }

      ServerAddress serverAddress = ServerAddress.parseString(serverData.ip);
      ConnectScreen.startConnecting(new JoinMultiplayerScreen(titleScreen), minecraft, serverAddress, serverData, true, null);
   }

   private static StartupRequest parseStartupRequest(final @Nullable String encoded) {
      String requested = encoded == null ? "" : encoded.trim();
      if (!requested.regionMatches(true, 0, URL_STARTUP_PREFIX, 0, URL_STARTUP_PREFIX.length())) {
         boolean javaAddress = requested.regionMatches(true, 0, "java://", 0, 7);
         return new StartupRequest(javaAddress ? requested.substring(7).trim() : requested,
            javaAddress ? ServerData.ConnectionMode.WISP : null, false, javaAddress);
      }
      int transportEnd = requested.indexOf('/', URL_STARTUP_PREFIX.length());
      if (transportEnd < 0) {
         return new StartupRequest("", null, true, false);
      }
      String transport = requested.substring(URL_STARTUP_PREFIX.length(), transportEnd).toLowerCase(Locale.ROOT);
      ServerData.ConnectionMode connectionMode = switch (transport) {
         case "eaglerx" -> ServerData.ConnectionMode.EAGLERX;
         case "tcp" -> ServerData.ConnectionMode.DIRECT_TCP;
         case "relay" -> ServerData.ConnectionMode.RELAY;
         case "wisp" -> ServerData.ConnectionMode.WISP;
         default -> null;
      };
      String address = requested.substring(transportEnd + 1).trim();
      boolean javaAddress = address.regionMatches(true, 0, "java://", 0, 7);
      boolean requiresWisp = connectionMode == ServerData.ConnectionMode.WISP || javaAddress;
      boolean invalidTransport = connectionMode == null || javaAddress && connectionMode != ServerData.ConnectionMode.WISP;
      return new StartupRequest(javaAddress ? address.substring(7).trim() : address,
         connectionMode, invalidTransport, requiresWisp);
   }

   private static Component startupUnavailableReason(final ServerData.@Nullable ConnectionMode connectionMode) {
      if (connectionMode == ServerData.ConnectionMode.WISP) {
         return Component.translatableWithFallback("quickplay.startupSetup.wispRequired",
            "This link requires an available Wispcraft Wisp Server endpoint. Open Multiplayer > Wisp Settings, configure the script endpoint, then reload the link.");
      }
      if (connectionMode == ServerData.ConnectionMode.DIRECT_TCP
            || connectionMode == null && PlatformNetworking.isIsolatedWebApp()) {
         return PlatformNetworking.isIsolatedWebApp()
            ? Component.translatableWithFallback("quickplay.startupSetup.directTcpUnavailableInstalled",
               "Direct TCP is not available in this installed app. Choose EaglerX in Multiplayer, then reload the link.")
            : Component.translatableWithFallback("quickplay.startupSetup.directTcpRequiresInstalled",
               "Direct TCP is available only in the installed isolated app. Use transport=eaglerx here, or install and open the isolated app.");
      }
      return Component.translatableWithFallback("quickplay.startupSetup.relayUnavailable",
         "No multiplayer WebSocket relay is configured. Open Multiplayer > Relay Settings, add or restore a relay, then reload the link.");
   }

   private static @Nullable ServerData createStartupServer(final String target, final String requested,
         final ServerData.@Nullable ConnectionMode requestedMode) {
      ServerData serverData = new ServerData(I18n.get("selectServer.defaultName"), target, ServerData.Type.OTHER);
      if (requestedMode == ServerData.ConnectionMode.EAGLERX
            || requestedMode == null && AddressResolver.looksLikeEaglerXAddress(requested)) {
         serverData.setConnectionMode(ServerData.ConnectionMode.EAGLERX);
         serverData.ip = AddressResolver.buildEaglerXURI(requested);
         return serverData;
      }
      if (requestedMode == ServerData.ConnectionMode.DIRECT_TCP
            || requestedMode == null && PlatformNetworking.isIsolatedWebApp() && !PlatformNetworking.isWispcraftLoaded()) {
         if (!PlatformNetworking.supportsDirectTCP()) {
            return null;
         }
         serverData.setConnectionMode(ServerData.ConnectionMode.DIRECT_TCP);
         return serverData;
      }
      if (requestedMode == ServerData.ConnectionMode.WISP) {
         RelaySettings.Profile wisp = RelaySettings.wispcraft();
         if (wisp == null) {
            return null;
         }
         serverData.setConnectionMode(ServerData.ConnectionMode.WISP);
         serverData.ip = wisp.connectionURI(target);
         return serverData;
      }
      RelaySettings.Profile relay = RelaySettings.primary();
      if (relay == null) {
         return null;
      }
      serverData.setConnectionMode(ServerData.ConnectionMode.RELAY);
      serverData.ip = relay.connectionURI(target);
      return serverData;
   }

   private static boolean startupRouteAvailable(final ServerData serverData) {
      return switch (serverData.connectionMode()) {
         case RELAY -> RelaySettings.primary() != null;
         case WISP -> RelaySettings.wispcraft() != null;
         case EAGLERX -> true;
         case DIRECT_TCP -> PlatformNetworking.supportsDirectTCP();
      };
   }

   private static @Nullable ServerData findStartupServer(final ServerList servers, final String requested,
         final String target, final ServerData.@Nullable ConnectionMode requestedMode) {
      ServerData exact = servers.get(requested);
      if (exact != null && (requestedMode == null || exact.connectionMode() == requestedMode)) {
         return exact;
      }
      String targetKey = startupTargetKey(target);
      for (int i = 0; i < servers.size(); ++i) {
         ServerData candidate = servers.get(i);
         if (requestedMode != null && candidate.connectionMode() != requestedMode) {
            continue;
         }
         String storedTarget = candidate.connectionMode() == ServerData.ConnectionMode.RELAY
               || candidate.connectionMode() == ServerData.ConnectionMode.WISP
            ? AddressResolver.extractTarget(candidate.ip, candidate.ip)
            : AddressResolver.stripEaglerXScheme(candidate.ip);
         if (targetKey.equals(startupTargetKey(storedTarget))) {
            return candidate;
         }
      }
      return null;
   }

   private static String startupTargetKey(final String value) {
      String key = value == null ? "" : value.trim();
      String stripped = AddressResolver.stripWebSocketScheme(key);
      if (stripped != null) {
         key = stripped;
      }
      while (key.endsWith("/")) {
         key = key.substring(0, key.length() - 1);
      }
      return key.toLowerCase(Locale.ROOT);
   }

   private static void showStartupSetup(final Minecraft minecraft, final Screen titleScreen, final Component message) {
      minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(titleScreen), STARTUP_SETUP_TITLE,
         message, CommonComponents.GUI_OK, false));
   }

}
