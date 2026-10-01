package net.minecraft.client.color.block;

import java.util.Set;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.ColorResolver;
import org.jspecify.annotations.Nullable;

public interface BlockTintSource {
   int color(BlockState state);

   default int colorInWorld(final BlockState state, final BlockAndTintGetter level, final BlockPos pos) {
      return this.color(state);
   }

   default int colorAsTerrainParticle(final BlockState state, final BlockAndTintGetter level, final BlockPos pos) {
      return this.colorInWorld(state, level, pos);
   }

   default Set<Property<?>> relevantProperties() {
      return Set.of();
   }

   /**
    * The biome resolver read by {@link #colorInWorld}, or {@code null} when this
    * tint is derived only from block state. Mesh snapshots use this to capture
    * only biome colors that the section can actually request.
    */
   default @Nullable ColorResolver biomeColorResolver() {
      return null;
   }

   /** Relative Y coordinate read by the biome resolver. */
   default int biomeColorYOffset(final BlockState state) {
      return 0;
   }
}
