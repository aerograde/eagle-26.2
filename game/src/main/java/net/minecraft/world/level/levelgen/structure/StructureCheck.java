package net.minecraft.world.level.levelgen.structure;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.storage.ChunkScanAccess;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class StructureCheck {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int NO_STRUCTURE = -1;
   private final ChunkScanAccess storageAccess;
   private final RegistryAccess registryAccess;
   private final StructureTemplateManager structureTemplateManager;
   private final ResourceKey<Level> dimension;
   private final ChunkGenerator chunkGenerator;
   private final RandomState randomState;
   private final LevelHeightAccessor heightAccessor;
   private final BiomeSource biomeSource;
   private final long seed;
   private final DataFixer fixerUpper;
   private final Long2ObjectMap<Object2IntMap<Structure>> loadedChunks = new Long2ObjectOpenHashMap();
   private final Map<Structure, Long2BooleanMap> featureChecks = new HashMap<>();
   private final boolean eaglerWebStorage;

   public StructureCheck(
      final ChunkScanAccess storageAccess,
      final RegistryAccess registryAccess,
      final StructureTemplateManager structureTemplateManager,
      final ResourceKey<Level> dimension,
      final ChunkGenerator chunkGenerator,
      final RandomState randomState,
      final LevelHeightAccessor heightAccessor,
      final BiomeSource biomeSource,
      final long seed,
      final DataFixer fixerUpper
   ) {
      this.storageAccess = storageAccess;
      this.registryAccess = registryAccess;
      this.structureTemplateManager = structureTemplateManager;
      this.dimension = dimension;
      this.chunkGenerator = chunkGenerator;
      this.randomState = randomState;
      this.heightAccessor = heightAccessor;
      this.biomeSource = biomeSource;
      this.seed = seed;
      this.fixerUpper = fixerUpper;
      this.eaglerWebStorage = net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()
         && net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
            != net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP;
   }

   public StructureCheckResult checkStart(final ChunkPos pos, final Structure structure, final StructurePlacement placement, final boolean requireUnreferenced) {
      long posKey = pos.pack();
      Object2IntMap<Structure> cachedResult = (Object2IntMap<Structure>)this.loadedChunks.get(posKey);
      if (cachedResult != null) {
         return this.checkStructureInfo(cachedResult, structure, requireUnreferenced);
      }

      if (!this.eaglerWebStorage) {
         StructureCheckResult storageCheckResult = this.tryLoadFromStorage(pos, structure, requireUnreferenced, posKey);
         if (storageCheckResult != null) {
            return storageCheckResult;
         }
      }

      if (!placement.applyAdditionalChunkRestrictions(pos.x(), pos.z(), this.seed)) {
         return StructureCheckResult.START_NOT_PRESENT;
      }

      // The vanilla fast path probes region storage before it knows whether a
      // candidate chunk can contain the requested structure. Browser worlds use
      // one IndexedDB blob per chunk, so /locate structure turned every probe into
      // a nested @Async read + CompletableFuture.join. The Wasm-GC continuation
      // behind that path is the measured null-pointer trap for village locate.
      //
      // Current-version browser chunks are generated deterministically from this
      // exact seed and registry set; older chunks are already rejected at the VFS
      // boundary because browser DFU is unavailable. Compute the placement first.
      // A positive candidate is still loaded normally by ChunkGenerator, so saved
      // starts and references remain authoritative. Do not retain negative locate
      // probes forever: unlike loaded chunks, they have no unload event to evict
      // them and otherwise make repeated exploration an unbounded heap cache.
      boolean isFeatureChunk = this.eaglerWebStorage
         ? this.canCreateStructure(pos, structure)
         : this.featureChecks
            .computeIfAbsent(structure, k -> new Long2BooleanOpenHashMap())
            .computeIfAbsent(posKey, k -> this.canCreateStructure(pos, structure));
      return !isFeatureChunk ? StructureCheckResult.START_NOT_PRESENT : StructureCheckResult.CHUNK_LOAD_NEEDED;
   }

   private boolean canCreateStructure(final ChunkPos pos, final Structure structure) {
      return structure.findValidGenerationPoint(
            new Structure.GenerationContext(
               this.registryAccess,
               this.chunkGenerator,
               this.biomeSource,
               this.randomState,
               this.structureTemplateManager,
               this.seed,
               pos,
               this.heightAccessor,
               structure.biomes()::contains
            )
         )
         .isPresent();
   }

   private @Nullable StructureCheckResult tryLoadFromStorage(
      final ChunkPos pos, final Structure structure, final boolean requireUnreferenced, final long posKey
   ) {
      CollectFields collectFields = new CollectFields(
         new FieldSelector(IntTag.TYPE, "DataVersion"),
         new FieldSelector("Level", "Structures", CompoundTag.TYPE, "Starts"),
         new FieldSelector("structures", CompoundTag.TYPE, "starts")
      );

      try {
         this.storageAccess.scanChunk(pos, collectFields).join();
      } catch (Exception e) {
         LOGGER.warn("Failed to read chunk {}", pos, e);
         return StructureCheckResult.CHUNK_LOAD_NEEDED;
      }

      if (collectFields.getResult() instanceof CompoundTag chunkTag) {
         int version = NbtUtils.getDataVersion(chunkTag);
         SimpleRegionStorage.injectDatafixingContext(
            chunkTag, ChunkMap.getChunkDataFixContextTag(this.dimension, this.chunkGenerator.getTypeNameForDataFixer())
         );

         CompoundTag fixedChunkTag;
         try {
            fixedChunkTag = DataFixTypes.CHUNK.updateToCurrentVersion(this.fixerUpper, chunkTag, version);
         } catch (Exception e) {
            LOGGER.warn("Failed to partially datafix chunk {}", pos, e);
            return StructureCheckResult.CHUNK_LOAD_NEEDED;
         }

         Object2IntMap<Structure> knownStarts = this.loadStructures(fixedChunkTag);
         if (knownStarts == null) {
            return null;
         }

         this.storeFullResults(posKey, knownStarts);
         return this.checkStructureInfo(knownStarts, structure, requireUnreferenced);
      } else {
         return null;
      }
   }

   private @Nullable Object2IntMap<Structure> loadStructures(final CompoundTag chunkTag) {
      Optional<CompoundTag> maybeStartsTag = chunkTag.getCompound("structures").flatMap(tag -> tag.getCompound("starts"));
      if (maybeStartsTag.isEmpty()) {
         return null;
      }

      CompoundTag startsTag = maybeStartsTag.get();
      if (startsTag.isEmpty()) {
         return Object2IntMaps.emptyMap();
      }

      Object2IntMap<Structure> knownStarts = new Object2IntOpenHashMap();
      Registry<Structure> structuresRegistry = this.registryAccess.lookupOrThrow(Registries.STRUCTURE);
      startsTag.forEach((key, tag) -> {
         Identifier id = Identifier.tryParse(key);
         if (id != null) {
            Structure foundFeature = structuresRegistry.getValue(id);
            if (foundFeature != null) {
               tag.asCompound().ifPresent(structureData -> {
                  String pieceId = structureData.getStringOr("id", "");
                  if (!"INVALID".equals(pieceId)) {
                     int referenceCount = structureData.getIntOr("references", 0);
                     knownStarts.put(foundFeature, referenceCount);
                  }
               });
            }
         }
      });
      return knownStarts;
   }

   private static Object2IntMap<Structure> deduplicateEmptyMap(final Object2IntMap<Structure> map) {
      return map.isEmpty() ? Object2IntMaps.emptyMap() : map;
   }

   private StructureCheckResult checkStructureInfo(final Object2IntMap<Structure> cachedResult, final Structure structure, final boolean requireUnreferenced) {
      int referenceCount = cachedResult.getOrDefault(structure, -1);
      return referenceCount == -1 || requireUnreferenced && referenceCount != 0 ? StructureCheckResult.START_NOT_PRESENT : StructureCheckResult.START_PRESENT;
   }

   public void onStructureLoad(final ChunkPos pos, final Map<Structure, StructureStart> starts) {
      long posKey = pos.pack();
      Object2IntMap<Structure> startsToReferences = new Object2IntOpenHashMap();
      starts.forEach((structure, structureStart) -> {
         if (structureStart.isValid()) {
            startsToReferences.put(structure, structureStart.getReferences());
         }
      });
      this.storeFullResults(posKey, startsToReferences);
   }

   private void storeFullResults(final long posKey, final Object2IntMap<Structure> starts) {
      this.loadedChunks.put(posKey, deduplicateEmptyMap(starts));
      this.featureChecks.values().forEach(m -> m.remove(posKey));
   }

   public void incrementReference(final ChunkPos chunkPos, final Structure structure) {
      this.loadedChunks.compute(chunkPos.pack(), (key, counts) -> {
         if (counts == null || counts.isEmpty()) {
            counts = new Object2IntOpenHashMap();
         }

         counts.computeInt(structure, (k, value) -> value == null ? 1 : value + 1);
         return counts;
      });
   }

   /**
    * Vanilla treats these structure checks as lifetime caches. That is useful on
    * a desktop server, but it makes exploration retain at least one entry for
    * every chunk ever visited in a browser session. All results are durable or
    * deterministic, so dropping them with the owning chunk is safe.
    */
   public void eaglerUnload(final ChunkPos chunkPos) {
      long key = chunkPos.pack();
      this.loadedChunks.remove(key);
      this.featureChecks.values().forEach(checks -> checks.remove(key));
   }

   public int eaglerLoadedChunkCount() {
      return this.loadedChunks.size();
   }

   public int eaglerFeatureCheckCount() {
      int result = 0;
      for (Long2BooleanMap checks : this.featureChecks.values()) {
         result += checks.size();
      }
      return result;
   }
}
