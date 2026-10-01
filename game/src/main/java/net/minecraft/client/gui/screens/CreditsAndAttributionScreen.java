package net.minecraft.client.gui.screens;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonLinks;

public class CreditsAndAttributionScreen extends Screen {
   private static final int BUTTON_SPACING = 8;
   private static final int BUTTON_WIDTH = 210;
   private static final Component TITLE = Component.translatable("credits_and_attribution.screen.title");
   private static final Component CREDITS_BUTTON = Component.translatable("credits_and_attribution.button.credits");
   private static final Component ATTRIBUTION_BUTTON = Component.translatable("credits_and_attribution.button.attribution");
   private static final Component LICENSES_BUTTON = Component.translatable("credits_and_attribution.button.licenses");
   private final Screen lastScreen;
   private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

   public CreditsAndAttributionScreen(final Screen lastScreen) {
      super(TITLE);
      this.lastScreen = lastScreen;
   }

   @Override
   protected void init() {
      this.layout.addTitleHeader(TITLE, this.font);
      LinearLayout content = this.layout.addToContents(LinearLayout.vertical()).spacing(8);
      content.defaultCellSetting().alignHorizontallyCenter();
      content.addChild(Button.builder(CREDITS_BUTTON, button -> this.openCreditsScreen()).width(210).build());
      content.addChild(Button.builder(ATTRIBUTION_BUTTON, ConfirmLinkScreen.confirmLink(this, CommonLinks.ATTRIBUTION)).width(210).build());
      content.addChild(Button.builder(LICENSES_BUTTON, ConfirmLinkScreen.confirmLink(this, CommonLinks.LICENSES)).width(210).build());
      this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
      this.layout.arrangeElements();
      this.layout.visitWidgets(this::addRenderableWidget);
   }

   @Override
   protected void repositionElements() {
      this.layout.arrangeElements();
   }

   @Override
   public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
      super.extractRenderState(graphics, mouseX, mouseY, a);
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
         graphics.centeredText(this.font, Component.translatableWithFallback("eagler.credits.brand", "Eaglercraft %s",
               net.lax1dude.eaglercraft.v1_8.EaglercraftVersion.projectForkVersion), this.width / 2, 32, -1);
         graphics.centeredText(this.font, Component.translatableWithFallback("eagler.credits.basedOn",
               "Based on the original EaglercraftX 1.8 workspace"),
               this.width / 2, 44, -6250336);
         graphics.centeredText(this.font, Component.translatableWithFallback("eagler.credits.rewriteBy", "26.2 rewrite by %s",
               net.lax1dude.eaglercraft.v1_8.EaglercraftVersion.projectForkVendor), this.width / 2, 56, -6250336);
      }
   }

   private void openCreditsScreen() {
      this.minecraft.gui.setScreen(new WinScreen(false, () -> this.minecraft.gui.setScreen(this)));
   }

   @Override
   public void onClose() {
      this.minecraft.gui.setScreen(this.lastScreen);
   }
}
