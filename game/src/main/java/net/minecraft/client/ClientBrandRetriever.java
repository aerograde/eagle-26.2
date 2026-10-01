package net.minecraft.client;

public class ClientBrandRetriever {
   public static final String VANILLA_NAME = "vanilla";

   public static String getClientModName() {
      // Eagler branding: same brand string as upstream EaglercraftX
      return net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive() ? "eagler" : "vanilla";
   }
}
