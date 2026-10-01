package net.minecraft.client.gui.screens.options;

import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PrivacyConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonLinks;

public class OnlineOptionsScreen extends OptionsSubScreen {
   private static final Component TITLE = Component.translatable("options.online.title");
   private static final Component SERVERS_HEADER = Component.translatable("options.online.servers.header");
   private static final Component REALMS_HEADER = Component.translatable("options.online.realms.header");
   private static final Component XBOX_SETTINGS = Component.translatable("options.online.xboxSettings");

   public OnlineOptionsScreen(final Screen lastScreen, final Options options) {
      super(lastScreen, options, TITLE);
   }

   @Override
   protected void addOptions() {
      // Friends-list / allow-friend-requests / in-game-notification / share-presence rows removed:
      // they were entirely gated on the friend list, which is dead-on-web (backed by Mojang's
      // userApiService/friendsService, unreachable for an offline client). The remaining online
      // options (Xbox privacy link, server listing, realms notifications) are unaffected.
      this.list.addBig(Button.builder(XBOX_SETTINGS, var1x -> PrivacyConfirmLinkScreen.confirmLinkNow(this, CommonLinks.PRIVACY_AND_ONLINE_SETTINGS)).build());
      this.list.addHeader(SERVERS_HEADER);
      this.list.addBig(this.options.allowServerListing());
      this.list.addHeader(REALMS_HEADER);
      this.list.addBig(this.options.realmsNotifications());
   }
}
