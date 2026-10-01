package net.minecraft.client.resources;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.hash.Hashing;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.TextureUrlChecker;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Services;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class SkinManager {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final Services services;
   private final SkinTextureDownloader skinTextureDownloader;
   private final LoadingCache<SkinManager.CacheKey, CompletableFuture<Optional<PlayerSkin>>> skinCache;
   private final SkinManager.TextureCache skinTextures;
   private final SkinManager.TextureCache capeTextures;
   private final SkinManager.TextureCache elytraTextures;

   public SkinManager(final Path skinsDirectory, final Services services, final SkinTextureDownloader skinTextureDownloader, final Executor mainThreadExecutor) {
      this.services = services;
      this.skinTextureDownloader = skinTextureDownloader;
      this.skinTextures = new SkinManager.TextureCache(skinsDirectory, Type.SKIN);
      this.capeTextures = new SkinManager.TextureCache(skinsDirectory, Type.CAPE);
      this.elytraTextures = new SkinManager.TextureCache(skinsDirectory, Type.ELYTRA);
      this.skinCache = CacheBuilder.newBuilder()
         .expireAfterAccess(Duration.ofSeconds(15L))
         .build(
            new CacheLoader<SkinManager.CacheKey, CompletableFuture<Optional<PlayerSkin>>>() {
               public CompletableFuture<Optional<PlayerSkin>> load(final SkinManager.CacheKey key) {
                  return CompletableFuture.<MinecraftProfileTextures>supplyAsync(() -> {
                        Property packedTextures = key.packedTextures();
                        if (packedTextures == null) {
                           return MinecraftProfileTextures.EMPTY;
                        }

                        MinecraftProfileTextures textures = net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()
                           ? SkinManager.unpackHostedTextures(packedTextures)
                           : services.sessionService().unpackTextures(packedTextures);
                        if (textures.signatureState() == SignatureState.INVALID) {
                           SkinManager.LOGGER.warn("Profile contained invalid signature for textures property (profile id: {})", key.profileId());
                        }

                        return textures;
                     }, Util.backgroundExecutor().forName("unpackSkinTextures"))
                     .thenComposeAsync(textures -> SkinManager.this.registerTextures(key.profileId(), textures), mainThreadExecutor)
                     .handle((playerSkin, throwable) -> {
                        if (throwable != null) {
                           SkinManager.LOGGER.warn("Failed to load texture for profile {}", key.profileId, throwable);
                        }

                        return Optional.ofNullable(playerSkin);
                     });
               }
            }
         );
   }

   private static MinecraftProfileTextures unpackHostedTextures(final Property packedTextures) {
      try {
         String json = new String(Base64.getDecoder().decode(packedTextures.value()), StandardCharsets.UTF_8);
         JsonObject root = JsonParser.parseString(json).getAsJsonObject();
         JsonElement texturesElement = root.get("textures");
         if (texturesElement == null || !texturesElement.isJsonObject()) {
            return MinecraftProfileTextures.EMPTY;
         }

         JsonObject textures = texturesElement.getAsJsonObject();
         MinecraftProfileTexture skin = parseHostedTexture(textures, "SKIN");
         MinecraftProfileTexture cape = parseHostedTexture(textures, "CAPE");
         MinecraftProfileTexture elytra = parseHostedTexture(textures, "ELYTRA");
         return new MinecraftProfileTextures(skin, cape, elytra, SignatureState.UNSIGNED);
      } catch (RuntimeException ex) {
         LOGGER.debug("Ignoring malformed browser profile texture payload: {}", ex.getMessage());
         return MinecraftProfileTextures.EMPTY;
      }
   }

   private static @Nullable MinecraftProfileTexture parseHostedTexture(final JsonObject textures, final String type) {
      JsonElement textureElement = textures.get(type);
      if (textureElement == null || !textureElement.isJsonObject()) {
         return null;
      }

      JsonObject texture = textureElement.getAsJsonObject();
      JsonElement urlElement = texture.get("url");
      if (urlElement == null || !urlElement.isJsonPrimitive()) {
         return null;
      }

      String url = urlElement.getAsString();
      if (!TextureUrlChecker.isAllowedTextureDomain(url)) {
         return null;
      }
      // Mojang still sends HTTP texture URLs in player-info properties.
      // Use TLS directly rather than causing mixed-content failures/retries.
      if (url.startsWith("http://")) {
         url = "https://" + url.substring(7);
      }

      Map<String, String> metadata = null;
      JsonElement metadataElement = texture.get("metadata");
      if (metadataElement != null && metadataElement.isJsonObject()) {
         metadata = new HashMap<>();
         for (Map.Entry<String, JsonElement> entry : metadataElement.getAsJsonObject().entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
               metadata.put(entry.getKey(), entry.getValue().getAsString());
            }
         }
      }

      return new MinecraftProfileTexture(url, metadata);
   }

   public Supplier<PlayerSkin> createLookup(final GameProfile profile, final boolean requireSecure) {
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()
         && net.minecraft.client.Minecraft.getInstance().isLocalPlayer(profile.id())) {
         return net.lax1dude.eaglercraft.v1_8.profile.EaglerProfile::getPlayerSkin;
      }

      CompletableFuture<Optional<PlayerSkin>> future = this.get(profile);
      // Hosted parsing validates texture domains but cannot verify the authlib
      // signature. These are cosmetic assets, not proof of account ownership.
      // Keep their UNSIGNED status and the native client's signature policy.
      boolean requireVerified = requireSecure && !net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive();
      PlayerSkin defaultSkin = DefaultPlayerSkin.get(profile);
      if (SharedConstants.DEBUG_DEFAULT_SKIN_OVERRIDE) {
         return () -> defaultSkin;
      } else {
         Optional<PlayerSkin> currentValue = future.getNow(null);
         if (currentValue != null) {
            PlayerSkin playerSkin = currentValue.filter(skin -> !requireVerified || skin.secure()).orElse(defaultSkin);
            return () -> playerSkin;
         } else {
            return () -> future.getNow(Optional.empty()).filter(skin -> !requireVerified || skin.secure()).orElse(defaultSkin);
         }
      }
   }

   public CompletableFuture<Optional<PlayerSkin>> get(final GameProfile profile) {
      if (SharedConstants.DEBUG_DEFAULT_SKIN_OVERRIDE) {
         PlayerSkin defaultSkin = DefaultPlayerSkin.get(profile);
         return CompletableFuture.completedFuture(Optional.of(defaultSkin));
      } else {
         Property packedTextures = this.services.sessionService().getPackedTextures(profile);
         return (CompletableFuture<Optional<PlayerSkin>>)this.skinCache.getUnchecked(new SkinManager.CacheKey(profile.id(), packedTextures));
      }
   }

   private CompletableFuture<PlayerSkin> registerTextures(final UUID profileId, final MinecraftProfileTextures textures) {
      MinecraftProfileTexture skinInfo = textures.skin();
      CompletableFuture<ClientAsset.Texture> skinTexture;
      PlayerModelType model;
      if (skinInfo != null) {
         skinTexture = this.skinTextures.getOrLoad(skinInfo);
         model = PlayerModelType.byLegacyServicesName(skinInfo.getMetadata("model"));
      } else {
         PlayerSkin defaultSkin = DefaultPlayerSkin.get(profileId);
         skinTexture = CompletableFuture.completedFuture(defaultSkin.body());
         model = defaultSkin.model();
      }

      MinecraftProfileTexture capeInfo = textures.cape();
      CompletableFuture<ClientAsset.Texture> capeTexture = capeInfo != null
         ? this.capeTextures.getOrLoad(capeInfo).exceptionally(error -> null) : CompletableFuture.completedFuture(null);
      MinecraftProfileTexture elytraInfo = textures.elytra();
      CompletableFuture<ClientAsset.Texture> elytraTexture = elytraInfo != null
         ? this.elytraTextures.getOrLoad(elytraInfo).exceptionally(error -> null)
         : CompletableFuture.completedFuture(null);
      return CompletableFuture.allOf(skinTexture, capeTexture, elytraTexture)
         .thenApply(
            unused -> new PlayerSkin(skinTexture.join(), capeTexture.join(), elytraTexture.join(), model, textures.signatureState() == SignatureState.SIGNED)
         );
   }

   private record CacheKey(UUID profileId, @Nullable Property packedTextures) {
   }

   private class TextureCache {
      private final Path root;
      private final Type type;
      private final Map<String, CompletableFuture<ClientAsset.Texture>> textures = new Object2ObjectOpenHashMap();

      private TextureCache(final Path root, final Type type) {
         this.root = root;
         this.type = type;
      }

      public CompletableFuture<ClientAsset.Texture> getOrLoad(final MinecraftProfileTexture texture) {
         String hash = texture.getHash();
         CompletableFuture<ClientAsset.Texture> future = this.textures.get(hash);
         if (future == null) {
            future = this.registerTexture(texture);
            this.textures.put(hash, future);
         }

         return future;
      }

      private CompletableFuture<ClientAsset.Texture> registerTexture(final MinecraftProfileTexture textureInfo) {
         String hash = Hashing.sha1().hashUnencodedChars(textureInfo.getHash()).toString();
         Identifier textureId = this.getTextureLocation(hash);
         Path file = this.root.resolve(hash.length() > 2 ? hash.substring(0, 2) : "xx").resolve(hash);
         return SkinManager.this.skinTextureDownloader.downloadAndRegisterSkin(textureId, file, textureInfo.getUrl(), this.type == Type.SKIN);
      }

      private Identifier getTextureLocation(final String textureHash) {
         String root = switch (this.type) {
            case SKIN -> "skins";
            case CAPE -> "capes";
            case ELYTRA -> "elytra";
            default -> throw new MatchException(null, null);
         };
         return Identifier.withDefaultNamespace(root + "/" + textureHash);
      }
   }
}
