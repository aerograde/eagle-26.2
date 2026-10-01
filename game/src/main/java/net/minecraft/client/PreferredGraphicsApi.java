package net.minecraft.client;

import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

public enum PreferredGraphicsApi implements StringRepresentable {
   DEFAULT("default", "options.graphicsApi.default"),
   OPENGL("opengl", "options.graphicsApi.opengl"),
   VULKAN("vulkan", "options.graphicsApi.vulkan");

   public static final Codec<PreferredGraphicsApi> CODEC = StringRepresentable.fromEnum(PreferredGraphicsApi::values);
   private final String serializedName;
   private final Component key;

   PreferredGraphicsApi(final String serializedName, final String key) {
      this.serializedName = serializedName;
      this.key = Component.translatable(key);
   }

   public Component caption() {
      return this.key;
   }

   @Override
   public String getSerializedName() {
      return this.serializedName;
   }

   public GpuBackend[] getBackendsToTry() {
      // Eagler 26.2 Phase 3.3b backend-selection seam: the web entry point injects
      // its GpuBackend factory (WebGL2) via EaglerHosted; when set, it is the only
      // backend tried. Desktop leaves the factory null and runs the vanilla path.
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()
            && net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webGpuBackendFactory != null) {
         return new GpuBackend[]{net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webGpuBackendFactory.get()};
      }
      GlBackend gl = new GlBackend();
      VulkanBackend vulkan = new VulkanBackend();
      return this == VULKAN ? new GpuBackend[]{vulkan, gl} : new GpuBackend[]{gl, vulkan};
   }
}
