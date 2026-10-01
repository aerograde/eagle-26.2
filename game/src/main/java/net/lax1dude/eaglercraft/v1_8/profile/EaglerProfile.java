/*
 * Copyright (c) 2022-2024 lax1dude, ayunami2000. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 */

package net.lax1dude.eaglercraft.v1_8.profile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EaglerInputStream;
import net.lax1dude.eaglercraft.v1_8.EaglerOutputStream;
import net.lax1dude.eaglercraft.v1_8.EaglercraftRandom;
import net.lax1dude.eaglercraft.v1_8.HString;
import net.minecraft.core.ClientAsset;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 26.2 port of the upstream Eagler profile: static username + skin + cape
 * selection state persisted to EagRuntime storage key "p" with the upstream
 * gzip-NBT schema kept byte-compatible (presetSkin/customSkin/presetCape/
 * customCape ints, username string, skins list {name, data[16384], model},
 * capes list {name, data[1173]}).
 */
public class EaglerProfile {

	private static final Logger LOGGER = LogUtils.getLogger();
	private static String username;
	private static boolean hideDefaultUsernameWarning = false;

	public static int presetSkinId;
	public static int customSkinId;

	public static int presetCapeId;
	public static int customCapeId;

	public static final List<CustomSkin> customSkins = new ArrayList<>();
	public static final List<CustomCape> customCapes = new ArrayList<>();

	public static final EaglercraftRandom rand;

	private static boolean loaded = false;

	private static PlayerSkin cachedPlayerSkin = null;
	private static final ClientAsset.ResourceTexture VANILLA_ELYTRA = new ClientAsset.ResourceTexture(
			Identifier.withDefaultNamespace("entity/equipment/wings/elytra"));
	private static Identifier cachedPlayerSkinTexture = null;
	private static SkinModel cachedPlayerSkinModel = null;
	private static Identifier cachedPlayerCapeTexture = null;

	public static Identifier getActiveSkinResourceLocation() {
		if(presetSkinId == -1) {
			if(customSkinId >= 0 && customSkinId < customSkins.size()) {
				return customSkins.get(customSkinId).getResource();
			}else {
				customSkinId = -1;
				presetSkinId = 0;
				return DefaultSkins.defaultSkinsMap[0].location;
			}
		}else {
			if(presetSkinId >= 0 && presetSkinId < DefaultSkins.defaultSkinsMap.length) {
				return DefaultSkins.defaultSkinsMap[presetSkinId].location;
			}else {
				presetSkinId = 0;
				return DefaultSkins.defaultSkinsMap[0].location;
			}
		}
	}

	public static SkinModel getActiveSkinModel() {
		if(presetSkinId == -1) {
			if(customSkinId >= 0 && customSkinId < customSkins.size()) {
				return customSkins.get(customSkinId).model;
			}else {
				customSkinId = -1;
				presetSkinId = 0;
				return DefaultSkins.defaultSkinsMap[0].model;
			}
		}else {
			if(presetSkinId >= 0 && presetSkinId < DefaultSkins.defaultSkinsMap.length) {
				return DefaultSkins.defaultSkinsMap[presetSkinId].model;
			}else {
				presetSkinId = 0;
				return DefaultSkins.defaultSkinsMap[0].model;
			}
		}
	}

	public static Identifier getActiveCapeResourceLocation() {
		if(presetCapeId == -1) {
			if(customCapeId >= 0 && customCapeId < customCapes.size()) {
				return customCapes.get(customCapeId).getResource();
			}else {
				customCapeId = -1;
				presetCapeId = 0;
				return null;
			}
		}else {
			return DefaultCapes.getCapeFromId(presetCapeId).location;
		}
	}

	/**
	 * The active profile skin as a 26.2 PlayerSkin, used to override the local
	 * player's skin lookup when running on the Eagler runtime. Cached until
	 * the selected skin texture, model or cape changes.
	 */
	public static PlayerSkin getPlayerSkin() {
		readIfNeeded();
		Identifier texture = getActiveSkinResourceLocation();
		SkinModel model = getActiveSkinModel();
		Identifier cape = getActiveCapeResourceLocation();
		if(cachedPlayerSkin == null || !texture.equals(cachedPlayerSkinTexture) || model != cachedPlayerSkinModel
				|| !java.util.Objects.equals(cape, cachedPlayerCapeTexture)) {
			cachedPlayerSkin = PlayerSkin.insecure(new ClientAsset.ResourceTexture(texture, texture),
					cape != null ? new ClientAsset.ResourceTexture(cape, cape) : null, VANILLA_ELYTRA,
					model.getPlayerModelType());
			cachedPlayerSkinTexture = texture;
			cachedPlayerSkinModel = model;
			cachedPlayerCapeTexture = cape;
		}
		return cachedPlayerSkin;
	}

	public static String getName() {
		return username;
	}

	public static void setName(String str) {
		username = str;
	}

	/**
	 * Serialize the currently selected profile skin for the direct EaglerX
	 * handshake. Handshake v3 uses the original RGBA packet; v4/v5 use the
	 * compact skin_v2 packet from the official 1.8 workspace.
	 */
	public static byte[] getSkinPacket(int handshakeVersion) {
		readIfNeeded();
		if(presetSkinId == -1) {
			if(customSkinId >= 0 && customSkinId < customSkins.size()) {
				CustomSkin selected = customSkins.get(customSkinId);
				return handshakeVersion <= 3
						? SkinPackets.writeMySkinCustomV3(selected.model.id, selected.texture)
						: SkinPackets.writeMySkinCustomV4(selected.model.id, selected.texture);
			}
			customSkinId = -1;
			presetSkinId = 0;
		}
		if(presetSkinId < 0 || presetSkinId >= DefaultSkins.defaultSkinsMap.length) {
			presetSkinId = 0;
		}
		return SkinPackets.writeMySkinPreset(presetSkinId);
	}

	/** Serialize the currently selected profile cape for an EaglerX login. */
	public static byte[] getCapePacket() {
		readIfNeeded();
		if(presetCapeId == -1) {
			if(customCapeId >= 0 && customCapeId < customCapes.size()) {
				return CapePackets.writeMyCapeCustom(customCapes.get(customCapeId).texture);
			}
			customCapeId = -1;
			presetCapeId = 0;
		}
		if(presetCapeId < 0 || presetCapeId >= DefaultCapes.defaultCapesMap.length) {
			presetCapeId = 0;
		}
		return CapePackets.writeMyCapePreset(presetCapeId);
	}

	public static boolean isHideDefaultUsernameWarning() {
		return hideDefaultUsernameWarning;
	}

	public static void setHideDefaultUsernameWarning(boolean hide) {
		hideDefaultUsernameWarning = hide;
	}

	private static boolean doesSkinExist(String name) {
		for(int i = 0, l = customSkins.size(); i < l; ++i) {
			if(customSkins.get(i).name.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	public static int addCustomSkin(String fileName, byte[] rawSkin) {
		if(doesSkinExist(fileName)) {
			String newName;
			int i = 2;
			while(doesSkinExist(newName = fileName + " (" + i + ")")) {
				++i;
			}
			fileName = newName;
		}
		CustomSkin newSkin = new CustomSkin(fileName, rawSkin, SkinModel.STEVE);
		int r = customSkins.size();
		customSkins.add(newSkin);
		return r;
	}

	public static void clearCustomSkins() {
		for(int i = 0, l = customSkins.size(); i < l; ++i) {
			customSkins.get(i).delete();
		}
		customSkins.clear();
	}

	private static boolean doesCapeExist(String name) {
		for(int i = 0, l = customCapes.size(); i < l; ++i) {
			if(customCapes.get(i).name.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	public static int addCustomCape(String fileName, byte[] rawCape) {
		if(doesCapeExist(fileName)) {
			String newName;
			int i = 2;
			while(doesCapeExist(newName = fileName + " (" + i + ")")) {
				++i;
			}
			fileName = newName;
		}
		CustomCape newCape = new CustomCape(fileName, rawCape);
		int r = customCapes.size();
		customCapes.add(newCape);
		return r;
	}

	public static void clearCustomCapes() {
		for(int i = 0, l = customCapes.size(); i < l; ++i) {
			customCapes.get(i).delete();
		}
		customCapes.clear();
	}

	/**
	 * Idempotent read() so the screen and the local-player skin override can
	 * lazily initialize the profile without a dedicated boot hook.
	 */
	public static void readIfNeeded() {
		if(!loaded) {
			loaded = true;
			read();
		}
	}

	public static void read() {
		loaded = true;
		read(EagRuntime.getStorage("p"));
	}

	public static void read(byte[] profileStorage) {
		if (profileStorage == null) {
			return;
		}

		CompoundTag profile;
		try {
			// Profiles are now stored UNCOMPRESSED (see write(): TeaVM's GZIP Deflater
			// throws Z_BUF_ERROR on flush, so writeCompressed never succeeded on the web
			// build). Still read the old gzip format if present (magic 0x1f 0x8b) so any
			// desktop-written profile keeps loading.
			boolean gzipped = profileStorage.length >= 2 && (profileStorage[0] & 0xFF) == 0x1f
					&& (profileStorage[1] & 0xFF) == 0x8b;
			if(gzipped) {
				profile = NbtIo.readCompressed(new EaglerInputStream(profileStorage), NbtAccounter.unlimitedHeap());
			}else {
				profile = NbtIo.read(new DataInputStream(new EaglerInputStream(profileStorage)), NbtAccounter.unlimitedHeap());
			}
		}catch(Exception ex) {
			LOGGER.warn("Ignoring corrupt local Eagler profile data and using defaults", ex);
			return;
		}

		if (profile == null || profile.isEmpty()) {
			return;
		}

		presetSkinId = profile.getIntOr("presetSkin", 0);
		customSkinId = profile.getIntOr("customSkin", 0);

		presetCapeId = profile.getIntOr("presetCape", presetCapeId);
		customCapeId = profile.getIntOr("customCape", customCapeId);

		hideDefaultUsernameWarning = profile.getBooleanOr("hideDefaultUsernameWarning26", false);
		String loadUsername = profile.getStringOr("username", "").trim();

		if(!loadUsername.isEmpty()) {
			username = loadUsername.replaceAll("[^A-Za-z0-9]", "_");
		}

		clearCustomSkins();

		ListTag skinsList = profile.getListOrEmpty("skins");
		for(int i = 0, l = skinsList.size(); i < l; ++i) {
			CompoundTag skin = skinsList.getCompoundOrEmpty(i);
			String skinName = skin.getStringOr("name", "");
			byte[] skinData = skin.getByteArray("data").orElse(null);
			if(skinData == null || skinData.length != 16384) continue;
			for(int y = 20; y < 32; ++y) {
				for(int x = 16; x < 40; ++x) {
					skinData[(y << 8) | (x << 2)] = (byte)0xff;
				}
			}
			int skinModel = skin.getByteOr("model", (byte)0);
			customSkins.add(new CustomSkin(skinName, skinData, SkinModel.getModelFromId(skinModel)));
		}

		clearCustomCapes();

		ListTag capesList = profile.getListOrEmpty("capes");
		for(int i = 0, l = capesList.size(); i < l; ++i) {
			CompoundTag cape = capesList.getCompoundOrEmpty(i);
			String capeName = cape.getStringOr("name", "");
			byte[] capeData = cape.getByteArray("data").orElse(null);
			if(capeData == null || capeData.length != 1173) continue;
			customCapes.add(new CustomCape(capeName, capeData));
		}

		if(presetSkinId == -1) {
			if(customSkinId < 0 || customSkinId >= customSkins.size()) {
				presetSkinId = 0;
				customSkinId = -1;
			}
		}else {
			customSkinId = -1;
			if(presetSkinId < 0 || presetSkinId >= DefaultSkins.defaultSkinsMap.length) {
				presetSkinId = 0;
			}
		}

		if(presetCapeId == -1) {
			if(customCapeId < 0 || customCapeId >= customCapes.size()) {
				presetCapeId = 0;
				customCapeId = -1;
			}
		}else {
			customCapeId = -1;
			if(presetCapeId < 0 || presetCapeId >= DefaultCapes.defaultCapesMap.length) {
				presetCapeId = 0;
			}
		}

	}

	public static byte[] write() {
		CompoundTag profile = new CompoundTag();
		profile.putInt("presetSkin", presetSkinId);
		profile.putInt("customSkin", customSkinId);
		profile.putInt("presetCape", presetCapeId);
		profile.putInt("customCape", customCapeId);
		profile.putString("username", username);
		profile.putBoolean("hideDefaultUsernameWarning26", hideDefaultUsernameWarning);
		ListTag skinsList = new ListTag();
		for(int i = 0, l = customSkins.size(); i < l; ++i) {
			CustomSkin sk = customSkins.get(i);
			CompoundTag skin = new CompoundTag();
			skin.putString("name", sk.name);
			skin.putByteArray("data", sk.texture);
			skin.putByte("model", (byte)sk.model.id);
			skinsList.add(skin);
		}
		profile.put("skins", skinsList);
		ListTag capesList = new ListTag();
		for(int i = 0, l = customCapes.size(); i < l; ++i) {
			CustomCape cp = customCapes.get(i);
			CompoundTag cape = new CompoundTag();
			cape.putString("name", cp.name);
			cape.putByteArray("data", cp.texture);
			capesList.add(cape);
		}
		profile.put("capes", capesList);
		EaglerOutputStream bao = new EaglerOutputStream();
		try {
			// Keep profile data uncompressed: TeaVM's Deflater fails in
			// GZIPOutputStream.flush. World saves still use GZIP and need a separate fix.
			NbtIo.write(profile, new DataOutputStream(bao));
		} catch (IOException e) {
			return null;
		}
		return bao.toByteArray();
	}

	public static void save() {
		byte[] b = write();
		if(b != null) {
			EagRuntime.setStorage("p", b);
		}
		// The launcher reads the plain "username" key, so keep it in sync with the
		// name stored in the profile.
		try {
			EagRuntime.setStorage("username", username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}catch(Throwable t) {
		}
	}

	static {
		String[] defaultNames = new String[] {
				"Yeeish", "Yeeish", "Yee", "Yee", "Yeer", "Yeeler", "Eagler", "Eagl",
				"Darver", "Darvler", "Vool", "Vigg", "Vigg", "Deev", "Yigg", "Yeeg"
		};

		rand = new EaglercraftRandom();

		do {
			username = HString.format("%s%s%04d", defaultNames[rand.nextInt(defaultNames.length)], defaultNames[rand.nextInt(defaultNames.length)], rand.nextInt(10000));
		}while(username.length() > 16);

		presetSkinId = rand.nextInt(DefaultSkins.defaultSkinsMap.length);
		customSkinId = -1;

		presetCapeId = 0;
		customCapeId = -1;

	}

	public static boolean isDefaultUsername(String str) {
		return str.toLowerCase().matches("^(yeeish|yee|yeer|yeeler|eagler|eagl|darver|darvler|vool|vigg|deev|yigg|yeeg){2}\\d{2,4}$");
	}

}
