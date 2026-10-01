package net.minecraft.client.gui.components;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class CommonButtons {
   public static SpriteIconButton language(final int width, final Button.OnPress onPress, final boolean iconOnly) {
      SpriteIconButton button = SpriteIconButton.builder(Component.translatable("options.language"), onPress, iconOnly)
         .width(width)
         .sprite(Identifier.withDefaultNamespace("icon/language"), 15, 15)
         .narration(var0 -> Component.translatable("options.language.narration"))
         .build();
      button.setTooltip(Tooltip.create(Component.translatable("options.language.tooltip")));
      return button;
   }

   public static SpriteIconButton accessibility(final int width, final Button.OnPress onPress, final boolean iconOnly) {
      Component text = iconOnly ? Component.translatable("options.accessibility") : Component.translatable("accessibility.onboarding.accessibility.button");
      SpriteIconButton button = SpriteIconButton.builder(text, onPress, iconOnly)
         .width(width)
         .sprite(Identifier.withDefaultNamespace("icon/accessibility"), 15, 15)
         .narration(var0 -> Component.translatable("accessibility.onboarding.accessibility.button.narration"))
         .build();
      button.setTooltip(Tooltip.create(Component.translatable("options.accessibility.tooltip")));
      return button;
   }

   public static SpriteIconButton profile(final int width, final Button.OnPress onPress, final boolean iconOnly) {
      Component text = Component.translatableWithFallback("eagler.menu.profile", "Edit Profile");
      SpriteIconButton button = SpriteIconButton.builder(text, onPress, iconOnly)
         .width(width)
         .sprite(Identifier.withDefaultNamespace("spectator/teleport_to_player"), 16, 16)
         .narration(var0 -> Component.translatableWithFallback("eagler.menu.profile.narration", "Edit Profile"))
         .build();
      button.setTooltip(Tooltip.create(Component.translatableWithFallback("eagler.menu.profile.tooltip", "Edit Profile")));
      return button;
   }
}
