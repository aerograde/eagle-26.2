package net.minecraft.client.resources.model.sprite;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.lax1dude.eaglercraft.v1_8.mods.resources.EaglerModResourceModelProbe;
import net.minecraft.client.resources.metadata.gui.GuiMetadataSection;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

public class AtlasManager implements AutoCloseable, PreparableReloadListener, SpriteGetter {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final List<AtlasManager.AtlasConfig> KNOWN_ATLASES = List.of(
      new AtlasManager.AtlasConfig(Sheets.ARMOR_TRIMS_SHEET, AtlasIds.ARMOR_TRIMS, false),
      new AtlasManager.AtlasConfig(Sheets.BANNER_SHEET, AtlasIds.BANNER_PATTERNS, false),
      new AtlasManager.AtlasConfig(TextureAtlas.LOCATION_BLOCKS, AtlasIds.BLOCKS, true),
      new AtlasManager.AtlasConfig(TextureAtlas.LOCATION_ITEMS, AtlasIds.ITEMS, false),
      new AtlasManager.AtlasConfig(Sheets.CHEST_SHEET, AtlasIds.CHESTS, false),
      new AtlasManager.AtlasConfig(Sheets.DECORATED_POT_SHEET, AtlasIds.DECORATED_POT, false),
      new AtlasManager.AtlasConfig(Sheets.GUI_SHEET, AtlasIds.GUI, false, Set.of(GuiMetadataSection.TYPE)),
      new AtlasManager.AtlasConfig(Sheets.MAP_DECORATIONS_SHEET, AtlasIds.MAP_DECORATIONS, false),
      new AtlasManager.AtlasConfig(Sheets.PAINTINGS_SHEET, AtlasIds.PAINTINGS, false),
      new AtlasManager.AtlasConfig(TextureAtlas.LOCATION_PARTICLES, AtlasIds.PARTICLES, false),
      new AtlasManager.AtlasConfig(Sheets.SHIELD_SHEET, AtlasIds.SHIELD_PATTERNS, false),
      new AtlasManager.AtlasConfig(Sheets.SHULKER_SHEET, AtlasIds.SHULKER_BOXES, false),
      new AtlasManager.AtlasConfig(Sheets.CELESTIAL_SHEET, AtlasIds.CELESTIALS, false)
   );
   public static final PreparableReloadListener.StateKey<AtlasManager.PendingStitchResults> PENDING_STITCH = new PreparableReloadListener.StateKey<>();
   private final Map<Identifier, AtlasManager.AtlasEntry> atlasByTexture = new HashMap<>();
   private final Map<Identifier, AtlasManager.AtlasEntry> atlasById = new HashMap<>();
   private Map<SpriteId, TextureAtlasSprite> spriteLookup = Map.of();
   private int maxMipmapLevels;

   public AtlasManager(final TextureManager textureManager, final int maxMipmapLevels) {
      for (AtlasManager.AtlasConfig info : KNOWN_ATLASES) {
         TextureAtlas atlasTexture = new TextureAtlas(info.textureId);
         textureManager.register(info.textureId, atlasTexture);
         AtlasManager.AtlasEntry atlasEntry = new AtlasManager.AtlasEntry(atlasTexture, info);
         this.atlasByTexture.put(info.textureId, atlasEntry);
         this.atlasById.put(info.definitionLocation, atlasEntry);
      }

      this.maxMipmapLevels = maxMipmapLevels;
   }

   public TextureAtlas getAtlasOrThrow(final Identifier atlasId) {
      AtlasManager.AtlasEntry atlasEntry = this.atlasById.get(atlasId);
      if (atlasEntry == null) {
         throw new IllegalArgumentException("Invalid atlas id: " + atlasId);
      } else {
         return atlasEntry.atlas();
      }
   }

   public void forEach(final BiConsumer<Identifier, TextureAtlas> output) {
      this.atlasById.forEach((atlasId, entry) -> output.accept(atlasId, entry.atlas));
   }

   public void updateMaxMipLevel(final int maxMipmapLevels) {
      this.maxMipmapLevels = maxMipmapLevels;
   }

   @Override
   public void close() {
      this.spriteLookup = Map.of();
      this.atlasById.values().forEach(AtlasManager.AtlasEntry::close);
      this.atlasById.clear();
      this.atlasByTexture.clear();
   }

   @Override
   public TextureAtlasSprite get(final SpriteId sprite) {
      TextureAtlasSprite result = this.spriteLookup.get(sprite);
      if (result != null) {
         return result;
      } else {
         Identifier atlasTextureId = sprite.atlasLocation();
         AtlasManager.AtlasEntry atlasEntry = this.atlasByTexture.get(atlasTextureId);
         if (atlasEntry == null) {
            throw new IllegalArgumentException("Invalid atlas texture id: " + atlasTextureId);
         } else {
            return atlasEntry.atlas().missingSprite();
         }
      }
   }

   @Override
   public void prepareSharedState(final PreparableReloadListener.SharedState currentReload) {
      int atlasCount = this.atlasById.size();
      List<AtlasManager.PendingStitch> pendingStitches = new ArrayList<>(atlasCount);
      Map<Identifier, CompletableFuture<SpriteLoader.Preparations>> pendingStitchById = new HashMap<>(atlasCount);
      List<CompletableFuture<?>> readyForUploads = new ArrayList<>(atlasCount);
      this.atlasById.forEach((atlasId, atlasEntry) -> {
         CompletableFuture<SpriteLoader.Preparations> stitchingDone = new CompletableFuture<>();
         pendingStitchById.put(atlasId, stitchingDone);
         pendingStitches.add(new AtlasManager.PendingStitch(atlasEntry, stitchingDone));
         readyForUploads.add(stitchingDone.thenCompose(SpriteLoader.Preparations::readyForUpload));
      });
      CompletableFuture<?> allReadyForUploads = CompletableFuture.allOf(readyForUploads.toArray(CompletableFuture[]::new));
      currentReload.set(PENDING_STITCH, new AtlasManager.PendingStitchResults(currentReload.resourceManager(), pendingStitches, pendingStitchById, allReadyForUploads));
   }

   @Override
   public CompletableFuture<Void> reload(
      final PreparableReloadListener.SharedState currentReload,
      final Executor taskExecutor,
      final PreparableReloadListener.PreparationBarrier preparationBarrier,
      final Executor reloadExecutor
   ) {
      AtlasManager.PendingStitchResults pendingStitches = currentReload.get(PENDING_STITCH);
      ResourceManager resourceManager = currentReload.resourceManager();
      pendingStitches.pendingStitches
         .forEach(pending -> pending.entry.scheduleLoad(resourceManager, taskExecutor, this.maxMipmapLevels).whenComplete((value, throwable) -> {
            if (value != null) {
               pending.preparations.complete(value);
            } else {
               pending.preparations.completeExceptionally(throwable);
            }
         }));
      return pendingStitches.allReadyToUpload
         .thenCompose(preparationBarrier::wait)
         .thenAcceptAsync(unused -> this.updateSpriteMaps(pendingStitches), reloadExecutor);
   }

   private void updateSpriteMaps(final AtlasManager.PendingStitchResults pendingStitches) {
      this.spriteLookup = pendingStitches.joinAndUpload();
      Map<Identifier, TextureAtlasSprite> globalSpriteLookup = new HashMap<>();
      this.spriteLookup
         .forEach(
            (id, sprite) -> {
               if (!id.texture().equals(MissingTextureAtlasSprite.getLocation())) {
                  TextureAtlasSprite previous = globalSpriteLookup.putIfAbsent(id.texture(), sprite);
                  if (previous != null) {
                     LOGGER.warn(
                        "Duplicate sprite {} from atlas {}, already defined in atlas {}. This will be rejected in a future version",
                        new Object[]{id.texture(), id.atlasLocation(), previous.atlasLocation()}
                     );
                  }
               }
            }
         );
      EaglerModResourceModelProbe.observeSprites(pendingStitches.reloadToken,
            EaglerModResourceModelProbe.reloadGenerationFor(pendingStitches.reloadToken), this.spriteLookup,
            pendingStitches.atlasDefinitionToTexture());
   }

   public record AtlasConfig(Identifier textureId, Identifier definitionLocation, boolean createMipmaps, Set<MetadataSectionType<?>> additionalMetadata) {
      public AtlasConfig(final Identifier textureId, final Identifier definitionLocation, final boolean createMipmaps) {
         this(textureId, definitionLocation, createMipmaps, Set.of());
      }
   }

   private record AtlasEntry(TextureAtlas atlas, AtlasManager.AtlasConfig config) implements AutoCloseable {
      @Override
      public void close() {
         this.atlas.close();
      }

      private CompletableFuture<SpriteLoader.Preparations> scheduleLoad(
         final ResourceManager resourceManager, final Executor executor, final int maxMipmapLevels
      ) {
         return SpriteLoader.create(this.atlas)
            .loadAndStitch(
               resourceManager, this.config.definitionLocation, this.config.createMipmaps ? maxMipmapLevels : 0, executor, this.config.additionalMetadata
            );
      }
   }

   private record PendingStitch(AtlasManager.AtlasEntry entry, CompletableFuture<SpriteLoader.Preparations> preparations) {
      public void joinAndUpload(final Map<SpriteId, TextureAtlasSprite> result) {
         SpriteLoader.Preparations preparations = this.preparations.join();
         this.entry.atlas.upload(preparations);
         preparations.regions().forEach((spriteId, spriteContents) -> result.put(new SpriteId(this.entry.config.textureId, spriteId), spriteContents));
      }
   }

   public static class PendingStitchResults {
      private final ResourceManager reloadToken;
      private final List<AtlasManager.PendingStitch> pendingStitches;
      private final Map<Identifier, CompletableFuture<SpriteLoader.Preparations>> stitchFuturesById;
      private final CompletableFuture<?> allReadyToUpload;
      private Map<Identifier, Identifier> uploadedAtlasDefinitionToTexture = Map.of();

      private PendingStitchResults(
         final ResourceManager reloadToken,
         final List<AtlasManager.PendingStitch> pendingStitches,
         final Map<Identifier, CompletableFuture<SpriteLoader.Preparations>> stitchFuturesById,
         final CompletableFuture<?> allReadyToUpload
      ) {
         this.reloadToken = reloadToken;
         this.pendingStitches = pendingStitches;
         this.stitchFuturesById = stitchFuturesById;
         this.allReadyToUpload = allReadyToUpload;
      }

      public Map<SpriteId, TextureAtlasSprite> joinAndUpload() {
         String telemetryBatch = EaglerModResourceModelProbe.beginPerformanceStage(this.reloadToken, "atlas-upload", "atlas-upload");
         boolean completed = false;
         try {
            Map<SpriteId, TextureAtlasSprite> result = new HashMap<>();
            Map<Identifier, Identifier> atlasDefinitionToTexture = new HashMap<>();
            for (int i = 0; i < this.pendingStitches.size(); i++) {
               AtlasManager.PendingStitch pendingStitch = this.pendingStitches.get(i);
               boolean profileUpload = net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf.isEnabled();
               long uploadStarted = profileUpload ? net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis() : 0L;
               pendingStitch.joinAndUpload(result);
               atlasDefinitionToTexture.put(pendingStitch.entry.config.definitionLocation,
                     pendingStitch.entry.config.textureId);
               if (profileUpload) {
                  LOGGER.info(
                     "[EagPerfClient] atlas upload {}={}ms",
                     pendingStitch.entry.config.definitionLocation,
                     Math.max(0L, net.lax1dude.eaglercraft.v1_8.EagRuntime.steadyTimeMillis() - uploadStarted)
                  );
               }
            }
            this.uploadedAtlasDefinitionToTexture = Map.copyOf(atlasDefinitionToTexture);
            completed = true;
            return result;
         } finally {
            EaglerModResourceModelProbe.finishPerformanceStage(telemetryBatch, completed);
         }
      }

      public Map<Identifier, Identifier> atlasDefinitionToTexture() {
         return this.uploadedAtlasDefinitionToTexture;
      }

      public CompletableFuture<SpriteLoader.Preparations> get(final Identifier atlasId) {
         return Objects.requireNonNull(this.stitchFuturesById.get(atlasId));
      }
   }
}
