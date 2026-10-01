package net.lax1dude.eaglercraft.v1_8.profile;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.slf4j.Logger;

/**
 * Client side of the official EaglerXServer v5 texture service. V5 carries
 * these packets in 0xEE-prefixed WebSocket frames, outside Minecraft's packet
 * codec. The old bridge used to discard those frames, leaving every remote
 * player on a generated Steve/Alex fallback.
 */
public final class EaglerSkinCache26 {

   private static final Logger LOGGER = LogUtils.getLogger();
   private static final long REQUEST_TIMEOUT_MILLIS = 30_000L;
   private static final long UNUSED_ENTRY_MILLIS = 15L * 60L * 1000L;
   private static int textureSequence;

   private final Connection connection;
   private final Map<UUID, Entry> entries = new HashMap<>();
   private final Map<Integer, UUID> pending = new HashMap<>();
   private int nextRequestId;
   private int ticksUntilCleanup = 200;

   public EaglerSkinCache26(final Connection connection) {
      this.connection = connection;
      connection.setEaglerMessageReceiver(this::handleFrame);
      LOGGER.info("Eagler v5 player texture service enabled");
   }

   public Supplier<PlayerSkin> createLookup(final UUID uuid) {
      PlayerSkin fallback = DefaultPlayerSkin.get(uuid);
      return () -> {
         Entry entry = this.entries.computeIfAbsent(uuid, ignored -> new Entry());
         entry.lastHit = System.currentTimeMillis();
         if (entry.skin != null) {
            return entry.skin;
         }
         if (entry.requestId < 0 || entry.requestExpiresAt <= entry.lastHit) {
            this.requestTextures(uuid, entry);
         }
         return fallback;
      };
   }

   public void tick() {
      if (--this.ticksUntilCleanup > 0) {
         return;
      }
      this.ticksUntilCleanup = 200;
      long now = System.currentTimeMillis();
      Iterator<Map.Entry<UUID, Entry>> iterator = this.entries.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, Entry> mapped = iterator.next();
         Entry entry = mapped.getValue();
         if (entry.requestId >= 0 && entry.requestExpiresAt <= now) {
            this.pending.remove(entry.requestId);
            entry.requestId = -1;
         }
         if (now - entry.lastHit > UNUSED_ENTRY_MILLIS) {
            if (entry.requestId >= 0) {
               this.pending.remove(entry.requestId);
            }
            entry.release();
            iterator.remove();
         }
      }
   }

   public void invalidate(final UUID uuid) {
      Entry entry = this.entries.remove(uuid);
      if (entry != null) {
         if (entry.requestId >= 0) {
            this.pending.remove(entry.requestId);
         }
         entry.release();
      }
   }

   public void destroy() {
      this.connection.setEaglerMessageReceiver(null);
      for (Entry entry : this.entries.values()) {
         entry.release();
      }
      this.entries.clear();
      this.pending.clear();
   }

   private void requestTextures(final UUID uuid, final Entry entry) {
      int requestId = this.nextRequestId = (this.nextRequestId + 1) & 0x3FFF;
      if (entry.requestId >= 0) {
         this.pending.remove(entry.requestId);
      }
      entry.requestId = requestId;
      entry.requestExpiresAt = System.currentTimeMillis() + REQUEST_TIMEOUT_MILLIS;
      this.pending.put(requestId, uuid);
      try {
         ByteArrayOutputStream bytes = new ByteArrayOutputStream(20);
         DataOutputStream output = new DataOutputStream(bytes);
         output.writeByte(0x03); // CPacketGetOtherTexturesV5EAG
         writeVarInt(output, requestId);
         output.writeLong(uuid.getMostSignificantBits());
         output.writeLong(uuid.getLeastSignificantBits());
         this.connection.sendEaglerMessage(bytes.toByteArray());
      } catch (IOException impossible) {
         throw new IllegalStateException(impossible);
      }
   }

   private void handleFrame(final byte[] payload) {
      try {
         Cursor input = new Cursor(payload);
         if (input.remaining() == 0) {
            return;
         }
         if (input.peekUnsignedByte() == 0xFF) {
            input.readUnsignedByte();
            int count = input.readVarInt();
            if (count < 0 || count > 256) {
               throw new IOException("Invalid Eagler texture packet count: " + count);
            }
            for (int i = 0; i < count; ++i) {
               int length = input.readVarInt();
               this.handlePacket(input.readBytes(length));
            }
            if (input.remaining() != 0) {
               throw new IOException("Trailing bytes after Eagler texture packet bundle");
            }
         } else {
            this.handlePacket(payload);
         }
      } catch (IOException | RuntimeException ex) {
         LOGGER.warn("Ignoring malformed Eagler v5 game-message frame: {}", ex.getMessage());
      }
   }

   private void handlePacket(final byte[] payload) throws IOException {
      Cursor input = new Cursor(payload);
      int packetId = input.readUnsignedByte();
      switch (packetId) {
         case 0x01 -> this.handleSkinPreset(input);
         case 0x02 -> this.handleSkinCustom(input);
         case 0x05 -> this.handleTextures(input);
         case 0x16 -> this.handleInvalidation(input);
         default -> {
            return; // voice, notifications, redirects, etc. are separate features
         }
      }
      if (input.remaining() != 0) {
         throw new IOException("Trailing bytes in Eagler game packet 0x" + Integer.toHexString(packetId));
      }
   }

   private void handleSkinPreset(final Cursor input) throws IOException {
      int requestId = input.readVarInt();
      int skinId = input.readVarInt();
      Entry entry = this.completeRequest(requestId);
      if (entry != null) {
         DefaultSkins skin = DefaultSkins.getSkinFromId(skinId);
         entry.install(skin.location, skin.model, null, false, false);
      }
   }

   private void handleSkinCustom(final Cursor input) throws IOException {
      int requestId = input.readVarInt();
      SkinModel model = SkinModel.getSanitizedModelFromId(input.readUnsignedByte());
      byte[] skinData = convertSkinV4ToRGBA(input.readBytes(12288));
      Entry entry = this.completeRequest(requestId);
      if (entry != null) {
         Identifier skin = registerSkinTexture(skinData, model);
         entry.install(skin, model, null, true, false);
      }
   }

   private void handleTextures(final Cursor input) throws IOException {
      int requestId = input.readVarInt();
      int skinId = input.readVarInt();
      int capeId = input.readVarInt();
      byte[] customSkin = skinId < 0 ? input.readBytes(12288) : null;
      byte[] customCape = capeId < 0 ? input.readBytes(1173) : null;
      Entry entry = this.completeRequest(requestId);
      if (entry == null) {
         return;
      }

      Identifier skinLocation;
      SkinModel model;
      boolean dynamicSkin;
      if (skinId >= 0) {
         DefaultSkins preset = DefaultSkins.getSkinFromId(skinId);
         skinLocation = preset.location;
         model = preset.model;
         dynamicSkin = false;
      } else {
         model = SkinModel.getSanitizedModelFromId(-skinId - 1);
         skinLocation = registerSkinTexture(convertSkinV4ToRGBA(customSkin), model);
         dynamicSkin = true;
      }

      Identifier capeLocation;
      boolean dynamicCape;
      if (capeId >= 0) {
         capeLocation = DefaultCapes.getCapeFromId(capeId).location;
         dynamicCape = false;
      } else {
         capeLocation = registerCapeTexture(customCape);
         dynamicCape = true;
      }
      entry.install(skinLocation, model, capeLocation, dynamicSkin, dynamicCape);
   }

   private void handleInvalidation(final Cursor input) throws IOException {
      int count = input.readVarInt();
      if (count < 0 || count > 4096) {
         throw new IOException("Invalid Eagler skin invalidation count: " + count);
      }
      for (int i = 0; i < count; ++i) {
         input.readUnsignedByte(); // skin/cape flags; combined entries are refreshed together
         this.invalidate(new UUID(input.readLong(), input.readLong()));
      }
   }

   private Entry completeRequest(final int requestId) {
      UUID uuid = this.pending.remove(requestId);
      if (uuid == null) {
         return null;
      }
      Entry entry = this.entries.get(uuid);
      if (entry != null && entry.requestId == requestId) {
         entry.requestId = -1;
         return entry;
      }
      return null;
   }

   private static Identifier registerSkinTexture(final byte[] rgba, final SkinModel model) {
      Identifier location = Identifier.parse("eagler:multiplayer/skin_" + textureSequence++);
      NativeImage image = new NativeImage(model.width, model.height, false);
      copyRGBA(image, rgba, model.width, model.height);
      Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(location::toString, image));
      return location;
   }

   private static Identifier registerCapeTexture(final byte[] encodedCape) {
      byte[] rgba = new byte[4096];
      SkinConverter.convertCape23x17RGBto32x32RGBA(encodedCape, rgba);
      Identifier location = Identifier.parse("eagler:multiplayer/cape_" + textureSequence++);
      NativeImage image = new NativeImage(64, 32, true);
      copyRGBA(image, rgba, 32, 32);
      Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(location::toString, image));
      return location;
   }

   private static void copyRGBA(final NativeImage image, final byte[] rgba, final int width, final int height) {
      for (int y = 0; y < height; ++y) {
         for (int x = 0; x < width; ++x) {
            int index = (y * width + x) << 2;
            image.setPixelABGR(x, y, ((rgba[index] & 0xFF) << 24) | ((rgba[index + 1] & 0xFF) << 16)
               | ((rgba[index + 2] & 0xFF) << 8) | (rgba[index + 3] & 0xFF));
         }
      }
   }

   private static byte[] convertSkinV4ToRGBA(final byte[] source) {
      byte[] result = new byte[16384];
      for (int pixel = 0; pixel < 4096; ++pixel) {
         int from = pixel * 3;
         int to = pixel << 2;
         result[to + 1] = source[from];
         result[to + 2] = source[from + 1];
         result[to + 3] = (byte)((source[from + 2] & 0x7F) << 1);
         result[to] = (source[from + 2] & 0x80) != 0 ? (byte)0xFF : 0;
      }
      return result;
   }

   private static void writeVarInt(final DataOutputStream output, int value) throws IOException {
      while ((value & -128) != 0) {
         output.writeByte(value & 127 | 128);
         value >>>= 7;
      }
      output.writeByte(value);
   }

   private static final class Entry {
      private PlayerSkin skin;
      private Identifier dynamicSkin;
      private Identifier dynamicCape;
      private int requestId = -1;
      private long requestExpiresAt;
      private long lastHit = System.currentTimeMillis();

      private void install(final Identifier skinLocation, final SkinModel model, final Identifier capeLocation,
            final boolean ownsSkin, final boolean ownsCape) {
         this.release();
         ClientAsset.ResourceTexture body = new ClientAsset.ResourceTexture(skinLocation, skinLocation);
         ClientAsset.ResourceTexture cape = capeLocation != null ? new ClientAsset.ResourceTexture(capeLocation, capeLocation) : null;
         this.skin = PlayerSkin.insecure(body, cape, null, model.getPlayerModelType());
         this.dynamicSkin = ownsSkin ? skinLocation : null;
         this.dynamicCape = ownsCape ? capeLocation : null;
      }

      private void release() {
         if (this.dynamicSkin != null) {
            Minecraft.getInstance().getTextureManager().release(this.dynamicSkin);
         }
         if (this.dynamicCape != null) {
            Minecraft.getInstance().getTextureManager().release(this.dynamicCape);
         }
         this.dynamicSkin = null;
         this.dynamicCape = null;
         this.skin = null;
      }
   }

   private static final class Cursor {
      private final byte[] data;
      private int index;

      private Cursor(final byte[] data) {
         this.data = data;
      }

      private int remaining() {
         return this.data.length - this.index;
      }

      private int peekUnsignedByte() throws IOException {
         this.require(1);
         return this.data[this.index] & 0xFF;
      }

      private int readUnsignedByte() throws IOException {
         this.require(1);
         return this.data[this.index++] & 0xFF;
      }

      private int readVarInt() throws IOException {
         int result = 0;
         for (int shift = 0; shift < 35; shift += 7) {
            int next = this.readUnsignedByte();
            result |= (next & 0x7F) << shift;
            if ((next & 0x80) == 0) {
               return result;
            }
         }
         throw new IOException("Eagler VarInt is too long");
      }

      private long readLong() throws IOException {
         this.require(8);
         long value = 0L;
         for (int i = 0; i < 8; ++i) {
            value = value << 8 | (this.data[this.index++] & 0xFFL);
         }
         return value;
      }

      private byte[] readBytes(final int length) throws IOException {
         if (length < 0) {
            throw new IOException("Negative Eagler packet length");
         }
         this.require(length);
         byte[] result = new byte[length];
         System.arraycopy(this.data, this.index, result, 0, length);
         this.index += length;
         return result;
      }

      private void require(final int length) throws IOException {
         if (length > this.remaining()) {
            throw new IOException("Eagler game-message frame underflow");
         }
      }
   }
}
