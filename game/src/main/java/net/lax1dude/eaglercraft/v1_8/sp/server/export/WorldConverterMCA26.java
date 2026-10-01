package net.lax1dude.eaglercraft.v1_8.sp.server.export;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.OptionalDynamic;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSChunkStorage;
import net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSWorldStorage;
import net.lax1dude.eaglercraft.v1_8.sp.server.WorldsDB;
import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.PrimaryLevelData;

/**
 * 26.2 upgrade of upstream sp/server/export/WorldConverterMCA (import side):
 * unpacks a zipped vanilla world into the hosted VFS layout. Region files are
 * parsed from bytes with a small standalone .mca reader (26.2's RegionFile
 * needs a real Path) and every chunk becomes a per-chunk gzip NBT blob at
 * worlds/&lt;id&gt;/&lt;dim ns_path&gt;/&lt;chunk|entities|poi&gt;/&lt;hex12&gt;.
 * Chunk NBT keeps its original DataVersion — 26.2's chunk DFU upgrades on
 * read. level.dat can NOT stay stale: hosted mode skips the vanilla
 * FileFixerUpper, and getLevelDataAndDimensions throws on any pre-file-fixer
 * DataVersion — so this importer replays the load-bearing v4772 file fixes in
 * memory (LEVEL DFU + the LevelDatToSavedDataFileFix extractions + the
 * DimensionStorageFileFix / PlayerStorageFileFix path mappings) and stamps the
 * result with the current DataVersion.
 */
public class WorldConverterMCA26 {

	private static final Logger logger = LogManager.getLogger("WorldConverterMCA26");
	private static final boolean YIELD_BROWSER_WORK = EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP;

	private static final String DIM_OVERWORLD = "minecraft_overworld";
	private static final String DIM_NETHER = "minecraft_the_nether";
	private static final String DIM_END = "minecraft_the_end";

	/** FileFixerUpper.FILE_FIXER_INTRODUCTION_VERSION — the schema the v4772 file fixes run at */
	private static final int EXTRACTION_VERSION = 4772;

	private static final Pattern REGION_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");
	private static final Pattern LEGACY_MAP_NAME = Pattern.compile("^map_(\\d+)\\.dat$");
	private static final Pattern LEGACY_COMMAND_STORAGE_NAME = Pattern.compile("^command_storage_([a-z0-9_.-]+)\\.dat$");

	/** pre-1.13 structure reference data consumed by LegacyStructureFileFix (not ported) + obsolete village data */
	private static final Set<String> LEGACY_UNSUPPORTED_DATA = Set.of("Village.dat", "Temple.dat", "Mineshaft.dat",
			"Monument.dat", "Fortress.dat", "Stronghold.dat", "EndCity.dat", "Igloo.dat", "Mansion.dat",
			"villages.dat", "villages_nether.dat", "villages_end.dat");

	/**
	 * Extract a zipped vanilla world into worlds/&lt;folderName&gt;/. The caller
	 * must have deduped folderName via EaglerVFSWorldStorage.findAvailableWorldName;
	 * level.dat is fixed up + written LAST so an interrupted import leaves an
	 * unlisted ghost dir, never a listed broken world.
	 */
	public static void importWorld(byte[] archiveContents, String folderName, String displayName,
			EaglerConvertProgress progress) throws IOException {
		logger.info("Importing world \"{}\" from vanilla zip", folderName);
		EaglerVFSWorldStorage.bootFilesystem();
		if(EaglerVFSWorldStorage.worldExists(folderName)) {
			throw new IOException("Refusing to import over existing world \"" + folderName + "\"");
		}

		try {
			// pass 1: the world root is the shortest folder containing a level.dat.
			// Keep that level.dat in memory too.  We must classify it before the
			// first region/data blob is allowed to reach the VFS; old vanilla worlds
			// do not have a safe hosted migration path yet.
			String prefix = null;
			byte[] levelDatBytes = null;
			int totalEntries = 0;
			try(ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archiveContents))) {
			ZipEntry e;
			while((e = zis.getNextEntry()) != null) {
				String name = WorldConverterEPK26.sanitizeEntryName(e.getName());
				if(name == null || isIgnoredZipEntry(e, name)) {
					continue;
				}
				++totalEntries;
				if(YIELD_BROWSER_WORK && (totalEntries & 63) == 0) {
					EagUtils.sleep(0L);
				}
				if(name.equals("level.dat") || name.endsWith("/level.dat")) {
					String p = name.substring(0, name.length() - "level.dat".length());
					if(prefix == null || p.length() < prefix.length()) {
						prefix = p;
						levelDatBytes = readEntryBytes(zis, e);
					}
				}
			}
			}
			if(prefix == null) {
				throw new IOException("The zip file does not contain a level.dat (this is not a vanilla world)");
			}
			if(levelDatBytes == null) {
				throw new IOException("The zip file contains an unreadable level.dat");
			}
			CompoundTag levelDatRoot = NbtIo.readCompressed(new ByteArrayInputStream(levelDatBytes),
					NbtAccounter.unlimitedHeap());
			preflightLevelDat(levelDatRoot);

			// pass 2: stream entries into the VFS (level.dat is buffered for last)
			int done = 0;
			long bytesRead = 0L;
			int total = Math.max(totalEntries, 1);
			try(ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(archiveContents))) {
			ZipEntry e;
			while((e = zis.getNextEntry()) != null) {
				String name = WorldConverterEPK26.sanitizeEntryName(e.getName());
				if(name != null && isIgnoredZipEntry(e, name)) {
					continue;
				}
				if(name == null) {
					logger.warn("Skipping zip entry with unsafe name: {}", e.getName());
					continue;
				}
				++done;
				if(YIELD_BROWSER_WORK && (done & 7) == 0) {
					EagUtils.sleep(0L);
				}
				float fraction = (float)done / (float)total;
				if(!name.startsWith(prefix)) {
					continue;
				}
				String rel = name.substring(prefix.length());
				if(rel.isEmpty()) {
					continue;
				}
				byte[] b = readEntryBytes(zis, e);
				bytesRead += b.length;
				if("level.dat".equals(rel)) {
					levelDatBytes = b;
				}else {
					storeEntry(folderName, rel, b, progress, fraction);
				}
				if(progress != null) {
					progress.report(done + " files, " + (bytesRead / 1024L) + " KB", fraction);
				}
			}
			}
			if(levelDatBytes == null) {
				throw new IOException("The zip file does not contain a level.dat (this is not a vanilla world)");
			}
			byte[] fixedLevelDat = fixLevelDat(levelDatRoot, folderName, displayName);
			WorldsDB.newVFile("worlds", folderName, "level.dat").setAllBytes(fixedLevelDat);
			EaglerVFSWorldStorage.markWorldImported(folderName);
			EaglerVFSWorldStorage.ensureWorldListed(folderName);
			logger.info("Vanilla world was successfully extracted into world \"{}\"", folderName);
		}catch(IOException | RuntimeException | Error failure) {
			// folderName was proven absent above. Roll back every blob written during a
			// failed extraction so malformed/truncated archives cannot reserve a ghost
			// world name, and never clean up an existing user's world on a collision.
			try {
				EaglerVFSWorldStorage.deleteWorld(folderName);
			}catch(Throwable cleanupFailure) {
				logger.error("Could not roll back failed import for world \"{}\"", folderName);
				logger.error(cleanupFailure);
			}
			throw failure;
		}
	}

	/**
	 * Validate the level header before any destination VFS file is created.
	 * Minecraft 1.8.8 level.dat files have no DataVersion, and the browser
	 * importer cannot safely convert their chunks yet.  Rejecting here keeps
	 * the import fast and leaves no half-imported world behind.
	 */
	private static void preflightLevelDat(CompoundTag root) throws IOException {
		CompoundTag dataTag = root.getCompoundOrEmpty("Data");
		int dataVersion = NbtUtils.getDataVersion(dataTag);
		if(dataVersion <= 0) {
			throw new IOException(
					"Minecraft 1.8.x and other legacy worlds are not yet supported in the browser. "
							+ "Open and save a copy in a supported Minecraft version, then import that world.");
		}
		int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
		if(dataVersion > currentVersion) {
			throw new IOException("This world is from a newer version of Minecraft (DataVersion " + dataVersion + " > "
					+ currentVersion + ") and cannot be imported");
		}
	}

	private static boolean isIgnoredZipEntry(ZipEntry entry, String normalizedName) {
		return entry.isDirectory() || entry.getName().endsWith("/") || entry.getName().endsWith("\\")
				|| normalizedName.startsWith("__MACOSX/");
	}

	// ==================== zip entry routing ====================

	private static void storeEntry(String folderName, String rel, byte[] b, EaglerConvertProgress progress,
			float fraction) throws IOException {
		if(rel.startsWith("DIM-1/")) {
			storeDimEntry(folderName, DIM_NETHER, rel.substring(6), b, progress, fraction);
			return;
		}
		if(rel.startsWith("DIM1/")) {
			storeDimEntry(folderName, DIM_END, rel.substring(5), b, progress, fraction);
			return;
		}
		if(rel.startsWith("dimensions/")) {
			storeModernDimEntry(folderName, rel, b, progress, fraction);
			return;
		}
		if(rel.startsWith("region/") || rel.startsWith("entities/") || rel.startsWith("poi/")) {
			storeDimEntry(folderName, DIM_OVERWORLD, rel, b, progress, fraction);
			return;
		}
		if(rel.startsWith("data/")) {
			storeGlobalDataFile(folderName, rel.substring(5), b);
			return;
		}
		if(rel.equals("icon.png")) {
			WorldsDB.newVFile("worlds", folderName, "icon.png").setAllBytes(b);
			return;
		}
		// PlayerStorageFileFix (v4772): playerdata/stats/advancements -> players/*
		if(rel.startsWith("playerdata/")) {
			WorldsDB.newVFile("worlds", folderName, "players/data", rel.substring(11)).setAllBytes(b);
			return;
		}
		if(rel.startsWith("stats/")) {
			WorldsDB.newVFile("worlds", folderName, "players/stats", rel.substring(6)).setAllBytes(b);
			return;
		}
		if(rel.startsWith("advancements/")) {
			WorldsDB.newVFile("worlds", folderName, "players/advancements", rel.substring(13)).setAllBytes(b);
			return;
		}
		if(rel.startsWith("players/data/") || rel.startsWith("players/stats/")
				|| rel.startsWith("players/advancements/")) {
			WorldsDB.newVFile("worlds", folderName, rel).setAllBytes(b);
			return;
		}
		if(rel.startsWith("generated/")) {
			// GeneratedStructuresRenameFileFix (v4773): structures -> structure
			WorldsDB.newVFile("worlds", folderName, rel.replaceFirst("/structures/", "/structure/")).setAllBytes(b);
			return;
		}
		if(rel.startsWith("datapacks/")) {
			// stored, NOT loaded — see the hosted branch in ServerPacksSource
			WorldsDB.newVFile("worlds", folderName, rel).setAllBytes(b);
			return;
		}
		if(rel.equals("level.dat_old") || rel.equals("level.dat_mcr") || rel.equals("session.lock")
				|| rel.startsWith("filefix/") || rel.startsWith("backups/")) {
			return;
		}
		logger.info("Skipping file: {}", rel);
	}

	/** dimensions/&lt;ns&gt;/&lt;path...&gt;/(region|entities|poi|data)/... — modern per-dimension layout */
	private static void storeModernDimEntry(String folderName, String rel, byte[] b, EaglerConvertProgress progress,
			float fraction) throws IOException {
		String rest = rel.substring("dimensions/".length());
		String[] parts = rest.split("/");
		int bound = -1;
		for(int i = 1; i < parts.length - 1; ++i) {
			String p = parts[i];
			if("region".equals(p) || "entities".equals(p) || "poi".equals(p) || "data".equals(p)) {
				bound = i;
				break;
			}
		}
		if(bound < 2) {
			logger.info("Skipping file: {}", rel);
			return;
		}
		String ns = parts[0];
		String path = String.join("/", Arrays.copyOfRange(parts, 1, bound));
		String sub = String.join("/", Arrays.copyOfRange(parts, bound, parts.length));
		storeDimEntry(folderName, ns + "_" + path, sub, b, progress, fraction);
	}

	/** sub is (region|entities|poi|data)/... relative to one dimension's storage root */
	private static void storeDimEntry(String folderName, String dimFolder, String sub, byte[] b,
			EaglerConvertProgress progress, float fraction) throws IOException {
		String typeName = null;
		String fileName = null;
		if(sub.startsWith("region/")) {
			typeName = "chunk";
			fileName = sub.substring(7);
		}else if(sub.startsWith("entities/")) {
			typeName = "entities";
			fileName = sub.substring(9);
		}else if(sub.startsWith("poi/")) {
			typeName = "poi";
			fileName = sub.substring(4);
		}else if(sub.startsWith("data/")) {
			storeDimDataFile(folderName, dimFolder, sub.substring(5), b);
			return;
		}else {
			logger.info("Skipping file: {}/{}", dimFolder, sub);
			return;
		}
		if(fileName.indexOf('/') != -1) {
			logger.info("Skipping file: {}/{}", dimFolder, sub);
			return;
		}
		if(fileName.endsWith(".mcr")) {
			logger.warn("{}: pre-anvil .mcr region files are not supported, skipping", fileName);
			return;
		}
		if(!fileName.endsWith(".mca")) {
			logger.info("Skipping file: {}/{}", dimFolder, sub);
			return;
		}
		importRegionFile(folderName, dimFolder, typeName, fileName, b, progress, fraction);
	}

	// ==================== .mca region parsing ====================

	/**
	 * Standalone anvil region reader: 4KiB header of 1024 big-endian ints
	 * ((sectorOffset &lt;&lt; 8) | sectorCount), each chunk at sectorOffset*4096 as
	 * [4-byte length][1-byte compression: 1=gzip 2=zlib 3=none][length-1 bytes].
	 * Chunk coords come from the chunk's own NBT (xPos/zPos at the root for
	 * 1.18+, under "Level" before; entities carry "Position") with the region
	 * file name + slot as the fallback.
	 */
	private static void importRegionFile(String folderName, String dimFolder, String typeName, String fileName,
			byte[] mca, EaglerConvertProgress progress, float fraction) {
		if(mca.length < 8192) {
			logger.warn("{}: region file is truncated, skipping", fileName);
			return;
		}
		Matcher m = REGION_NAME.matcher(fileName);
		boolean haveRegionCoords = m.matches();
		int regionX = 0;
		int regionZ = 0;
		if(haveRegionCoords) {
			regionX = Integer.parseInt(m.group(1));
			regionZ = Integer.parseInt(m.group(2));
		}
		int imported = 0;
		for(int i = 0; i < 1024; ++i) {
			if(YIELD_BROWSER_WORK && (i & 31) == 0) {
				EagUtils.sleep(0L);
			}
			int loc = readInt(mca, i << 2);
			int sectorOff = loc >>> 8;
			int sectorCount = loc & 0xFF;
			if(sectorOff == 0 || sectorCount == 0) {
				continue;
			}
			long byteOff = sectorOff * 4096L;
			if(byteOff + 5L > mca.length) {
				logger.warn("{}: chunk slot {} points past the end of the file, skipping", fileName, i);
				continue;
			}
			int len = readInt(mca, (int)byteOff);
			int compression = mca[(int)byteOff + 4] & 0xFF;
			if(len < 1 || byteOff + 4L + len > mca.length) {
				logger.warn("{}: chunk slot {} has an invalid length, skipping", fileName, i);
				continue;
			}
			CompoundTag nbt;
			try {
				InputStream payload = new ByteArrayInputStream(mca, (int)byteOff + 5, len - 1);
				InputStream decompressed;
				switch(compression) {
				case 1:
					decompressed = new GZIPInputStream(payload);
					break;
				case 2:
					decompressed = new InflaterInputStream(payload);
					break;
				case 3:
					decompressed = payload;
					break;
				default:
					logger.warn("{}: chunk slot {} uses unsupported compression type {}, skipping", fileName, i,
							compression);
					continue;
				}
				try(DataInputStream dis = new DataInputStream(new BufferedInputStream(decompressed))) {
					nbt = NbtIo.read(dis, NbtAccounter.unlimitedHeap());
				}
			}catch(Throwable t) {
				logger.warn("{}: could not read chunk at slot {}", fileName, i);
				logger.warn(t.toString());
				continue;
			}
			int localX = i & 31;
			int localZ = i >>> 5;
			int chunkX;
			int chunkZ;
			int[] nbtPos = readChunkPosFromNBT(nbt, typeName);
			if(nbtPos != null) {
				chunkX = nbtPos[0];
				chunkZ = nbtPos[1];
			}else if(haveRegionCoords) {
				chunkX = (regionX << 5) + localX;
				chunkZ = (regionZ << 5) + localZ;
			}else {
				logger.warn("{}: chunk slot {} has no coordinates in its NBT and the file name is not r.X.Z.mca, skipping",
						fileName, i);
				continue;
			}
			try {
				ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
				NbtIo.writeCompressed(nbt, out);
				WorldsDB.newVFile("worlds", folderName, dimFolder, typeName,
						EaglerVFSChunkStorage.getChunkPath(new ChunkPos(chunkX, chunkZ))).setAllBytes(out.toByteArray());
				++imported;
			}catch(Throwable t) {
				logger.warn("{}: could not write chunk {}, {}", fileName, chunkX, chunkZ);
				logger.warn(t.toString());
				continue;
			}
			if(progress != null && (imported & 63) == 0) {
				progress.report(imported + " chunks from " + fileName, fraction);
			}
		}
		logger.info("{}: imported {} {} entries into {}", fileName, imported, typeName, dimFolder);
	}

	private static int[] readChunkPosFromNBT(CompoundTag nbt, String typeName) {
		if("chunk".equals(typeName)) {
			Optional<Integer> x = nbt.getInt("xPos");
			Optional<Integer> z = nbt.getInt("zPos");
			if(x.isPresent() && z.isPresent()) {
				return new int[] { x.get(), z.get() };
			}
			CompoundTag level = nbt.getCompoundOrEmpty("Level");
			x = level.getInt("xPos");
			z = level.getInt("zPos");
			if(x.isPresent() && z.isPresent()) {
				return new int[] { x.get(), z.get() };
			}
		}else if("entities".equals(typeName)) {
			Optional<int[]> pos = nbt.getIntArray("Position");
			if(pos.isPresent() && pos.get().length == 2) {
				return new int[] { pos.get()[0], pos.get()[1] };
			}
		}
		// poi has no position in its NBT — region-slot coords only
		return null;
	}

	private static int readInt(byte[] b, int off) {
		return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
	}

	private static byte[] readEntryBytes(ZipInputStream zis, ZipEntry e) throws IOException {
		int sz = (int)e.getSize();
		if(sz >= 0) {
			byte[] b = new byte[sz];
			int j = 0;
			int k;
			while(j < sz && (k = zis.read(b, j, sz - j)) != -1) {
				j += k;
			}
			if(j < sz) {
				throw new EOFException("Unexpected end of zip entry: " + e.getName());
			}
			return b;
		}
		return zis.readAllBytes();
	}

	// ==================== saved-data file mapping (DimensionStorageFileFix, v4772) ====================

	/** &lt;world&gt;/data/&lt;rest&gt; — namespaced entries copy verbatim, legacy flat names remap */
	private static void storeGlobalDataFile(String folderName, String rest, byte[] b) {
		if(rest.indexOf('/') != -1) {
			WorldsDB.newVFile("worlds", folderName, "data", rest).setAllBytes(b);
			return;
		}
		if(LEGACY_UNSUPPORTED_DATA.contains(rest)) {
			logger.info("Skipping obsolete data file: data/{}", rest);
			return;
		}
		// legacy per-dimension data stored at the world root pre-v4772
		switch(rest) {
		case "raids.dat":
			WorldsDB.newVFile("worlds", folderName, DIM_OVERWORLD, "data/minecraft/raids.dat").setAllBytes(b);
			return;
		case "chunks.dat":
			WorldsDB.newVFile("worlds", folderName, DIM_OVERWORLD, "data/minecraft/chunk_tickets.dat").setAllBytes(b);
			return;
		case "world_border.dat":
			WorldsDB.newVFile("worlds", folderName, DIM_OVERWORLD, "data/minecraft/world_border.dat").setAllBytes(b);
			return;
		case "idcounts.dat":
			WorldsDB.newVFile("worlds", folderName, "data/minecraft/maps/last_id.dat").setAllBytes(b);
			return;
		default:
			break;
		}
		Matcher m = LEGACY_MAP_NAME.matcher(rest);
		if(m.matches()) {
			WorldsDB.newVFile("worlds", folderName, "data/minecraft/maps", m.group(1) + ".dat").setAllBytes(b);
			return;
		}
		m = LEGACY_COMMAND_STORAGE_NAME.matcher(rest);
		if(m.matches()) {
			WorldsDB.newVFile("worlds", folderName, "data", m.group(1), "command_storage.dat").setAllBytes(b);
			return;
		}
		// scoreboard.dat, random_sequences.dat, stopwatches.dat, ... -> minecraft/<name>
		WorldsDB.newVFile("worlds", folderName, "data/minecraft", rest).setAllBytes(b);
	}

	/** per-dimension data folder (DIM-1/data, DIM1/data, dimensions/&lt;ns&gt;/&lt;path&gt;/data) */
	private static void storeDimDataFile(String folderName, String dimFolder, String rest, byte[] b) {
		if(rest.indexOf('/') != -1) {
			WorldsDB.newVFile("worlds", folderName, dimFolder, "data", rest).setAllBytes(b);
			return;
		}
		if(LEGACY_UNSUPPORTED_DATA.contains(rest)) {
			logger.info("Skipping obsolete data file: {}/data/{}", dimFolder, rest);
			return;
		}
		switch(rest) {
		case "chunks.dat":
			WorldsDB.newVFile("worlds", folderName, dimFolder, "data/minecraft/chunk_tickets.dat").setAllBytes(b);
			return;
		case "raids.dat":
		case "raids_end.dat":
			WorldsDB.newVFile("worlds", folderName, dimFolder, "data/minecraft/raids.dat").setAllBytes(b);
			return;
		case "world_border.dat":
			WorldsDB.newVFile("worlds", folderName, dimFolder, "data/minecraft/world_border.dat").setAllBytes(b);
			return;
		default:
			WorldsDB.newVFile("worlds", folderName, dimFolder, "data/minecraft", rest).setAllBytes(b);
		}
	}

	// ==================== level.dat fix-up ====================

	/**
	 * Replays what FileFixerUpper does for real-FS worlds (hosted mode skips
	 * it): LEVEL DFU to v4772, the LevelDatToSavedDataFileFix extractions into
	 * VFS saved-data blobs, LEVEL DFU to current, then current DataVersion +
	 * Version tag + LastPlayed + the chosen LevelName.
	 */
	private static byte[] fixLevelDat(CompoundTag root, String folderName, String displayName) throws IOException {
		CompoundTag dataTag = root.getCompoundOrEmpty("Data");
		int dataVersion = NbtUtils.getDataVersion(dataTag);
		if(dataVersion <= 0) {
			throw new IOException(
					"This world is from Minecraft 1.8.x or older (level.dat has no DataVersion) and cannot be imported");
		}
		int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
		if(dataVersion > currentVersion) {
			throw new IOException("This world is from a newer version of Minecraft (DataVersion " + dataVersion + " > "
					+ currentVersion + ") and cannot be imported");
		}
		Dynamic<Tag> data = new Dynamic<>(NbtOps.INSTANCE, dataTag);
		if(dataVersion < EXTRACTION_VERSION) {
			data = DataFixTypes.LEVEL.update(DataFixers.getDataFixer(), data, dataVersion, EXTRACTION_VERSION);
			data = extractLevelDatSavedData(folderName, data);
			data = NbtUtils.addDataVersion(data, EXTRACTION_VERSION);
			dataVersion = EXTRACTION_VERSION;
		}
		Dynamic<?> fixed = DataFixTypes.LEVEL.update(DataFixers.getDataFixer(), data, dataVersion, currentVersion);
		// FileFixerUpper.addVersionsToLevelData
		fixed = NbtUtils.addDataVersion(fixed, currentVersion);
		fixed = PrimaryLevelData.writeLastPlayed(fixed);
		fixed = PrimaryLevelData.writeVersionTag(fixed);
		CompoundTag fixedData = (CompoundTag)fixed.convert(NbtOps.INSTANCE).getValue();
		fixedData.putString("LevelName", displayName);
		CompoundTag newRoot = new CompoundTag();
		newRoot.put("Data", fixedData);
		ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
		NbtIo.writeCompressed(newRoot, out);
		return out.toByteArray();
	}

	/** in-memory replay of LevelDatToSavedDataFileFix (runs on data at v4772) */
	private static Dynamic<Tag> extractLevelDatSavedData(String folderName, Dynamic<Tag> content) throws IOException {
		content = extractToSavedData(folderName, DIM_END + "/data", "minecraft/ender_dragon_fight", content,
				"dragon_fight");
		content = extractPlayerData(folderName, content);
		content = extractToSavedData(folderName, "data", "minecraft/wandering_trader", content,
				"wandering_trader_migration_data");
		content = extractToSavedData(folderName, "data", "minecraft/custom_boss_events", content, "CustomBossEvents");
		content = extractToSavedData(folderName, "data", "minecraft/weather", content, "weather_data");
		content = extractToSavedData(folderName, "data", "minecraft/scheduled_events", content, "scheduled_events");
		content = extractWorldBorder(folderName, content);
		content = extractToSavedData(folderName, "data", "minecraft/game_rules", content, "game_rules");
		content = extractWorldGenSettings(folderName, content);
		content = extractToSavedData(folderName, "data", "minecraft/world_clocks", content, "world_clocks");
		return content;
	}

	private static Dynamic<Tag> extractToSavedData(String folderName, String dataDir, String fileRel,
			Dynamic<Tag> content, String key) throws IOException {
		OptionalDynamic<Tag> tagOpt = content.get(key);
		if(tagOpt.result().isEmpty()) {
			return content;
		}
		writeSavedDataBlob(folderName, dataDir, fileRel, tagOpt.result().get());
		return content.remove(key);
	}

	private static Dynamic<Tag> extractPlayerData(String folderName, Dynamic<Tag> content) throws IOException {
		OptionalDynamic<Tag> playerOpt = content.get("Player");
		if(playerOpt.result().isEmpty()) {
			return content;
		}
		Dynamic<Tag> playerTag = playerOpt.result().get();
		int playerVersion = NbtUtils.getDataVersion(playerTag);
		Dynamic<Tag> playerFixed = DataFixTypes.PLAYER.update(DataFixers.getDataFixer(), playerTag, playerVersion,
				EXTRACTION_VERSION);
		Optional<Dynamic<Tag>> uuidOpt = playerFixed.get("UUID").result();
		Dynamic<?> usedUuid;
		if(uuidOpt.isPresent()) {
			// the old playerdata/<uuid>.dat copy of this player was imported already
			usedUuid = uuidOpt.get();
		}else {
			CompoundTag playerOut = (CompoundTag)playerFixed.convert(NbtOps.INSTANCE).getValue();
			playerOut.putInt("DataVersion", EXTRACTION_VERSION);
			ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
			NbtIo.writeCompressed(playerOut, out);
			WorldsDB.newVFile("worlds", folderName, "players/data", Util.NIL_UUID + ".dat")
					.setAllBytes(out.toByteArray());
			usedUuid = content.createIntList(Arrays.stream(UUIDUtil.uuidToIntArray(Util.NIL_UUID)));
		}
		return content.remove("Player").set("singleplayer_uuid", usedUuid);
	}

	private static Dynamic<Tag> extractWorldBorder(String folderName, Dynamic<Tag> content) throws IOException {
		if(content.get("world_border").result().isEmpty()) {
			return content;
		}
		writeWorldBorder(folderName, DIM_OVERWORLD, content, 1.0);
		writeWorldBorder(folderName, DIM_NETHER, content, 8.0);
		writeWorldBorder(folderName, DIM_END, content, 1.0);
		return content.remove("world_border");
	}

	private static void writeWorldBorder(String folderName, String dimFolder, Dynamic<Tag> content, double divider)
			throws IOException {
		Optional<Dynamic<Tag>> borderOpt = content.get("world_border").result();
		if(borderOpt.isPresent()) {
			Dynamic<Tag> border = borderOpt.get()
					.update("center_x", x -> x.createDouble(x.asDouble(0.0) / divider))
					.update("center_z", z -> z.createDouble(z.asDouble(0.0) / divider));
			writeSavedDataBlob(folderName, dimFolder + "/data", "minecraft/world_border", border);
		}
	}

	private static Dynamic<Tag> extractWorldGenSettings(String folderName, Dynamic<Tag> content) throws IOException {
		OptionalDynamic<Tag> wgsOpt = content.get("world_gen_settings");
		if(wgsOpt.result().isEmpty()) {
			return content;
		}
		// world_gen_settings rode along untyped — it needs its own DFU chain from
		// the ORIGINAL DataVersion (still in content, LEVEL DFU never rewrites it)
		int dataVersion = NbtUtils.getDataVersion(content);
		Dynamic<Tag> fixedWgs = DataFixTypes.WORLD_GEN_SETTINGS.update(DataFixers.getDataFixer(),
				wgsOpt.result().get(), dataVersion, EXTRACTION_VERSION);
		writeSavedDataBlob(folderName, "data", "minecraft/world_gen_settings", fixedWgs);
		return content.remove("world_gen_settings");
	}

	/** SavedDataNbt.write: {data: <tag>, DataVersion: 4772} gzip NBT — DFU'd to current on read */
	private static void writeSavedDataBlob(String folderName, String dataDir, String fileRel, Dynamic<?> tag)
			throws IOException {
		CompoundTag wrapper = new CompoundTag();
		wrapper.put("data", (Tag)tag.convert(NbtOps.INSTANCE).getValue());
		wrapper.putInt("DataVersion", EXTRACTION_VERSION);
		ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
		NbtIo.writeCompressed(wrapper, out);
		WorldsDB.newVFile("worlds", folderName, dataDir, fileRel + ".dat").setAllBytes(out.toByteArray());
	}

}
