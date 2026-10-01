package net.minecraft.client.gui.screens;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonLinks;

/**
 * Eaglercraft-specific credits, modeled after the CREDITS.txt presentation in
 * the official EaglercraftX 1.8 client while remaining inside the game window.
 */
public class EaglerCreditsScreen extends Screen {
   private static final Component TITLE = Component.translatableWithFallback("eagler.credits.title", "Eaglercraft Credits");
   private final Screen lastScreen;
   private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

   public EaglerCreditsScreen(final Screen lastScreen) {
      super(TITLE);
      this.lastScreen = lastScreen;
   }

   private static Component creditsText() {
      return Component.translatableWithFallback("eagler.credits.brand", "Eaglercraft %s",
            net.lax1dude.eaglercraft.v1_8.EaglercraftVersion.projectForkVersion)
         .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
         .append(Component.translatableWithFallback("eagler.credits.madeBy", "\nMade by o_xer").withStyle(ChatFormatting.AQUA))
         .append(Component.translatableWithFallback("eagler.credits.basedOnLong", "\n\nHeavily based on EaglercraftX 1.8 by lax1dude.")
            .withStyle(ChatFormatting.WHITE))
         .append(Component.translatableWithFallback("eagler.credits.contributors", "\n\nOriginal EaglercraftX 1.8 contributors")
            .withStyle(ChatFormatting.YELLOW))
         .append(Component.literal("\n\nlax1dude")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
         .append(Component.translatableWithFallback("eagler.credits.lax1dude",
            "\nCreator of Eaglercraft; TeaVM port, browser platform, renderer, multiplayer backends, relays, touch support and build system."))
         .append(Component.literal("\n\nayunami2000")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
         .append(Component.translatableWithFallback("eagler.credits.ayunami2000",
            "\nBug fixes, WebRTC shared worlds and voice chat, touch support, resource packs and other client features."))
         .append(Component.literal("\n\ncire3")
            .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
         .append(Component.translatableWithFallback("eagler.credits.cire3", "\nGradle plugin and Kotlin DSL work."))
         .append(Component.translatableWithFallback("eagler.credits.copyright",
            "\n\nMinecraft and its resources are copyright Mojang Studios.")
            .withStyle(ChatFormatting.GRAY));
   }

   @Override
   protected void init() {
      this.layout.addTitleHeader(TITLE, this.font);
      int contentWidth = Math.max(260, Math.min(360, this.width - 24));
      int textHeight = Math.max(50, this.layout.getContentHeight() - 58);
      LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(8));
      content.defaultCellSetting().alignHorizontallyCenter();
      content.addChild(new FittingMultiLineTextWidget(0, 0, contentWidth, textHeight, creditsText(), this.font));

      int buttonWidth = (contentWidth - 16) / 3;
      LinearLayout links = content.addChild(LinearLayout.horizontal().spacing(8));
      links.addChild(Button.builder(Component.translatableWithFallback("eagler.credits.minecraft", "Minecraft Credits"), button -> this.openMinecraftCredits()).width(buttonWidth).build());
      links.addChild(Button.builder(Component.translatableWithFallback("eagler.credits.attribution", "Attribution"), ConfirmLinkScreen.confirmLink(this, CommonLinks.ATTRIBUTION)).width(buttonWidth).build());
      links.addChild(Button.builder(Component.translatableWithFallback("eagler.credits.licenses", "Licenses"), ConfirmLinkScreen.confirmLink(this, CommonLinks.LICENSES)).width(buttonWidth).build());

      this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
      this.layout.visitWidgets(this::addRenderableWidget);
      this.repositionElements();
   }

   @Override
   protected void repositionElements() {
      this.layout.arrangeElements();
   }

   private void openMinecraftCredits() {
      this.minecraft.gui.setScreen(new WinScreen(false, () -> this.minecraft.gui.setScreen(this)));
   }

   @Override
   public void onClose() {
      this.minecraft.gui.setScreen(this.lastScreen);
   }
}
