package net.minecraft.client;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.Codec;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

public enum GraphicsPreset implements StringRepresentable {
   FAST("fast", "options.graphics.fast"),
   FANCY("fancy", "options.graphics.fancy"),
   FABULOUS("fabulous", "options.graphics.fabulous"),
   CUSTOM("custom", "options.graphics.custom");

   private final String serializedName;
   private final String key;
   public static final Codec<GraphicsPreset> CODEC = StringRepresentable.fromEnum(GraphicsPreset::values);

   GraphicsPreset(final String serializedName, final String key) {
      this.serializedName = serializedName;
      this.key = key;
   }

   @Override
   public String getSerializedName() {
      return this.serializedName;
   }

   public String getKey() {
      return this.key;
   }

   public void apply(final Minecraft minecraft) {
      OptionsSubScreen screen = minecraft.gui != null && minecraft.gui.screen() instanceof OptionsSubScreen ? (OptionsSubScreen)minecraft.gui.screen() : null;
      GpuDevice device = RenderSystem.getDevice();
      switch (this) {
         case FAST: {
            // Eagler (web): FAST is the boot default and everything still runs on one
            // browser thread, so it is tuned harder than desktop FAST. Worldgen runs on a
            // SINGLE green thread doing BigInt-emulated long noise math (~37x slower than
            // native), so the server must generate every chunk in the view radius on that one
            // thread after join — VD 6 (13x13 = 169 chunks) is what left the server ~53s / 1000+
            // ticks behind with holes in the world. VD 4 (9x9 = 81, ~2x fewer) matches the 1.8
            // reference default render distance and lets gen keep up; sim 4 keeps mob simulation
            // reasonable while trimming per-tick chunk ticking. Both are just defaults the user
            // can raise in Options once the native noise kernel lands.
            boolean eaglerWeb = net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webGpuBackendFactory != null;
            int viewDistance = eaglerWeb ? 4 : 8;
            set(screen, minecraft.options.biomeBlendRadius(), eaglerWeb ? 0 : 1);
            set(screen, minecraft.options.renderDistance(), viewDistance);
            set(screen, minecraft.options.prioritizeChunkUpdates(), PrioritizeChunkUpdates.NONE);
            set(screen, minecraft.options.simulationDistance(), eaglerWeb ? 4 : 6);
            set(screen, minecraft.options.ambientOcclusion(), false);
            set(screen, minecraft.options.cloudStatus(), CloudStatus.FAST);
            set(screen, minecraft.options.particles(), eaglerWeb ? ParticleStatus.MINIMAL : ParticleStatus.DECREASED);
            set(screen, minecraft.options.mipmapLevels(), 2);
            set(screen, minecraft.options.entityShadows(), false);
            set(screen, minecraft.options.entityDistanceScaling(), eaglerWeb ? 0.5 : 0.75);
            set(screen, minecraft.options.menuBackgroundBlurriness(), 2);
            set(screen, minecraft.options.cloudRange(), 32);
            set(screen, minecraft.options.cutoutLeaves(), false);
            set(screen, minecraft.options.improvedTransparency(), false);
            set(screen, minecraft.options.weatherRadius(), 5);
            set(screen, minecraft.options.maxAnisotropyBit(), 1);
            set(screen, minecraft.options.textureFiltering(), TextureFilteringMethod.NONE);
            break;
         }
         case FANCY: {
            int viewDistance = 16;
            set(screen, minecraft.options.biomeBlendRadius(), 2);
            set(screen, minecraft.options.renderDistance(), 16);
            set(screen, minecraft.options.prioritizeChunkUpdates(), PrioritizeChunkUpdates.PLAYER_AFFECTED);
            set(screen, minecraft.options.simulationDistance(), 12);
            set(screen, minecraft.options.ambientOcclusion(), true);
            set(screen, minecraft.options.cloudStatus(), CloudStatus.FANCY);
            set(screen, minecraft.options.particles(), ParticleStatus.ALL);
            set(screen, minecraft.options.mipmapLevels(), 4);
            set(screen, minecraft.options.entityShadows(), true);
            set(screen, minecraft.options.entityDistanceScaling(), 1.0);
            set(screen, minecraft.options.menuBackgroundBlurriness(), 5);
            set(screen, minecraft.options.cloudRange(), 64);
            set(screen, minecraft.options.cutoutLeaves(), true);
            set(screen, minecraft.options.improvedTransparency(), false);
            set(screen, minecraft.options.weatherRadius(), 10);
            set(screen, minecraft.options.maxAnisotropyBit(), 1);
            set(screen, minecraft.options.textureFiltering(), TextureFilteringMethod.RGSS);
            break;
         }
         case FABULOUS: {
            int viewDistance = 32;
            set(screen, minecraft.options.biomeBlendRadius(), 2);
            set(screen, minecraft.options.renderDistance(), 32);
            set(screen, minecraft.options.prioritizeChunkUpdates(), PrioritizeChunkUpdates.PLAYER_AFFECTED);
            set(screen, minecraft.options.simulationDistance(), 12);
            set(screen, minecraft.options.ambientOcclusion(), true);
            set(screen, minecraft.options.cloudStatus(), CloudStatus.FANCY);
            set(screen, minecraft.options.particles(), ParticleStatus.ALL);
            set(screen, minecraft.options.mipmapLevels(), 4);
            set(screen, minecraft.options.entityShadows(), true);
            set(screen, minecraft.options.entityDistanceScaling(), 1.25);
            set(screen, minecraft.options.menuBackgroundBlurriness(), 5);
            set(screen, minecraft.options.cloudRange(), 128);
            set(screen, minecraft.options.cutoutLeaves(), true);
            // Eagler (web): the fabulous transparency post-chain does not compile on the
            // WebGL2 backend (ShaderManager$CompilationException "minecraft:transparency"
            // = hard crash when the preset slider crosses FABULOUS). Excluded on web the
            // same way vanilla excludes it on macOS.
            set(screen, minecraft.options.improvedTransparency(), Util.getPlatform() != Util.OS.OSX
               && net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webGpuBackendFactory == null);
            set(screen, minecraft.options.weatherRadius(), 10);
            set(screen, minecraft.options.maxAnisotropyBit(), 2);
            if (device.getDeviceInfo().hintsAndWorkarounds().anisotropyHasKnownIssues()) {
               set(screen, minecraft.options.textureFiltering(), TextureFilteringMethod.RGSS);
            } else {
               set(screen, minecraft.options.textureFiltering(), TextureFilteringMethod.ANISOTROPIC);
            }
         }
      }
   }

   private static <T> void set(final @Nullable OptionsSubScreen screen, final OptionInstance<T> option, final T value) {
      if (option.get() != value) {
         option.set(value);
         if (screen != null) {
            screen.resetOption(option);
         }
      }
   }
}
