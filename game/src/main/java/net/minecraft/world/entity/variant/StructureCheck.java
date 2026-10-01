package net.minecraft.world.entity.variant;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;

public record StructureCheck(HolderSet<Structure> requiredStructures) implements SpawnCondition {
   public static final MapCodec<StructureCheck> MAP_CODEC = RecordCodecBuilder.mapCodec(
      i -> i.group(RegistryCodecs.homogeneousList(Registries.STRUCTURE).fieldOf("structures").forGetter(StructureCheck::requiredStructures))
         .apply(i, StructureCheck::new)
   );

   public boolean test(final SpawnContext context) {
      StructureManager structureManager = context.level().getLevel().structureManager();
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()
         && net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
            != net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP
         && context.level() instanceof WorldGenRegion region) {
         // A structure template can finalize an entity while FEATURES is still
         // generating. Re-entering ServerChunkCache here asks its single browser
         // thread to synchronously join another unfinished chunk future. The
         // WorldGenRegion already contains the exact STRUCTURE_REFERENCES/STARTS
         // dependency set for this stage, so query that cache instead. Keep normal
         // server spawns and the desktop implementation on the vanilla path.
         structureManager = structureManager.forWorldGenRegion(region);
      }

      return structureManager.getStructureWithPieceAt(context.pos(), this.requiredStructures).isValid();
   }

   @Override
   public MapCodec<StructureCheck> codec() {
      return MAP_CODEC;
   }
}
