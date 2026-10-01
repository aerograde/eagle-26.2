package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.lax1dude.eaglercraft.v1_8.EaglerInputStream;
import net.lax1dude.eaglercraft.v1_8.internal.vfs2.VFile2;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.sp.server.internal.ServerPlatformSingleplayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FileUtil;
import net.minecraft.world.level.storage.FileNameDateFormatter;

/**
 * 26.2 companion to EaglerVFSChunkStorage: everything in a world folder that is
 * NOT chunk data lives here when running on the Eagler VFS (hosted mode). Mirrors
 * the upstream 1.8 worlds DB conventions — worlds_list.txt in the VFS root is THE
 * world list (no directory scan), level.dat/level.dat_old are gzip NBT blobs, and
 * session.lock becomes an in-process guard because the VFS has no file locking.
 *
 * Layout under worlds/&lt;levelId&gt;/:
 *   level.dat, level.dat_old, icon.png, datapacks/...
 *   players/data/&lt;uuid&gt;.dat(+.dat_old), players/stats|advancements/&lt;uuid&gt;.json
 *   data/&lt;ns&gt;/&lt;path&gt;.dat                       (global SavedData)
 *   &lt;dimension ns_path&gt;/data/&lt;ns&gt;/&lt;path&gt;.dat   (per-dimension SavedData, same
 *                                               folder convention as chunk storage)
 *   generated/&lt;ns&gt;/structure/&lt;path&gt;.nbt        (structure templates)
 */
public class EaglerVFSWorldStorage {
	private static final String IMPORTED_WORLD_MARKER = ".eagler_imported";

	private static final Logger logger = LogManager.getLogger("EaglerVFSWorldStorage");

	public static final String WORLDS_LIST_FILE = "worlds_list.txt";

	/** guards all non-chunk VFS IO (one blob store shared across threads) */
	private static final Object diskLock = new Object();

	/** in-process replacement for vanilla session.lock (no VFS analog) */
	private static final Set<String> openLevels = new HashSet<>();

	private static volatile boolean fsBooted = false;

	/**
	 * vanilla FileUtil.COPY_COUNTER_PATTERN (private there). Numbered groups
	 * (1=name, 2=count) rather than (?&lt;name&gt;)/(?&lt;count&gt;) because TeaVM's
	 * regex engine has no named-group support; identical match behaviour.
	 */
	private static final Pattern COPY_COUNTER_PATTERN = Pattern.compile("(.*) \\((\\d*)\\)",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	/**
	 * The worlds DB handle is normally initialized on the worker thread at boot,
	 * but hosted world listing/creation happens on the client thread before the
	 * worker exists — initializeContext is idempotent (same in-JVM handle on
	 * desktop), so front-running it here is safe.
	 */
	public static void bootFilesystem() {
		if(!fsBooted) {
			synchronized(EaglerVFSWorldStorage.class) {
				if(!fsBooted) {
					ServerPlatformSingleplayer.initializeContext();
					fsBooted = true;
				}
			}
		}
	}

	// ==================== session locks (in-process) ====================

	public static void lockLevel(String levelId) throws IOException {
		synchronized(openLevels) {
			if(!openLevels.add(levelId)) {
				throw new IOException("Level \"" + levelId + "\" is already open (in-process session lock)");
			}
		}
	}

	public static void unlockLevel(String levelId) {
		synchronized(openLevels) {
			openLevels.remove(levelId);
		}
	}

	public static boolean isLevelLocked(String levelId) {
		synchronized(openLevels) {
			return openLevels.contains(levelId);
		}
	}

	// ==================== level.dat ====================

	private static VFile2 getLevelDatFile(String levelId, boolean fallback) {
		return WorldsDB.newVFile("worlds", levelId, fallback ? "level.dat_old" : "level.dat");
	}

	public static CompoundTag readLevelDat(String levelId, boolean fallback) throws IOException {
		bootFilesystem();
		byte[] bytes;
		synchronized(diskLock) {
			bytes = getLevelDatFile(levelId, fallback).getAllBytes();
		}
		if(bytes == null) {
			throw new FileNotFoundException("worlds/" + levelId + (fallback ? "/level.dat_old" : "/level.dat"));
		}
		return NbtIo.readCompressed(new EaglerInputStream(bytes), NbtAccounter.uncompressedQuota());
	}

	/** vanilla LevelStorageSource.readLightweightData, VFS edition */
	public static void parseLevelDat(String levelId, StreamTagVisitor visitor) throws IOException {
		parseLevelDat(levelId, false, visitor);
	}

	/** vanilla LevelStorageSource.readLightweightData, VFS edition */
	public static void parseLevelDat(String levelId, boolean fallback, StreamTagVisitor visitor) throws IOException {
		bootFilesystem();
		byte[] bytes;
		synchronized(diskLock) {
			bytes = getLevelDatFile(levelId, fallback).getAllBytes();
		}
		if(bytes == null) {
			throw new FileNotFoundException("worlds/" + levelId + (fallback ? "/level.dat_old" : "/level.dat"));
		}
		NbtIo.parseCompressed(new EaglerInputStream(bytes), visitor, NbtAccounter.uncompressedQuota());
	}

	/**
	 * Vanilla temp-file + Util.safeReplaceFile rotation collapses to two blob
	 * writes (VFS blob writes are atomic). Also keeps the world listed — a fresh
	 * world becomes real on its first level.dat save, so create/re-save both
	 * funnel through here and listing stays consistent with actual world data.
	 */
	public static void writeLevelDat(String levelId, CompoundTag root) throws IOException {
		net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.vfsWriteStarted("levelDat");
		try {
			bootFilesystem();
			ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
			NbtIo.writeCompressed(root, out);
			byte[] bytes = out.toByteArray();
			synchronized(diskLock) {
				VFile2 current = getLevelDatFile(levelId, false);
				byte[] oldBytes = current.getAllBytes();
				if(oldBytes != null) {
					getLevelDatFile(levelId, true).setAllBytes(oldBytes);
				}
				current.setAllBytes(bytes);
				ensureWorldListedLocked(levelId);
			}
		} finally {
			net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.vfsWriteCompleted();
		}
	}

	public static boolean hasLevelDat(String levelId, boolean fallback) {
		bootFilesystem();
		synchronized(diskLock) {
			return getLevelDatFile(levelId, fallback).exists();
		}
	}

	/** level.dat OR level.dat_old present (vanilla hasWorldData) */
	public static boolean hasLevelData(String levelId) {
		bootFilesystem();
		synchronized(diskLock) {
			return getLevelDatFile(levelId, false).exists() || getLevelDatFile(levelId, true).exists();
		}
	}

	/** vanilla Util.safeReplaceOrMoveFile(level.dat, level.dat_old, corrupted) */
	public static boolean restoreLevelDatFromOld(String levelId) {
		bootFilesystem();
		synchronized(diskLock) {
			VFile2 old = getLevelDatFile(levelId, true);
			byte[] oldBytes = old.getAllBytes();
			if(oldBytes == null) {
				return false;
			}
			VFile2 current = getLevelDatFile(levelId, false);
			byte[] corruptBytes = current.getAllBytes();
			if(corruptBytes != null) {
				WorldsDB.newVFile("worlds", levelId,
						"level.dat_corrupted_" + FileNameDateFormatter.FORMATTER.format(ZonedDateTime.now()))
						.setAllBytes(corruptBytes);
			}
			current.setAllBytes(oldBytes);
			old.delete();
			return true;
		}
	}

	// ==================== worlds_list.txt ====================

	public static List<String> getWorldsList() {
		bootFilesystem();
		synchronized(diskLock) {
			return getWorldsListLocked();
		}
	}

	private static List<String> getWorldsListLocked() {
		String[] lines = WorldsDB.newVFile(WORLDS_LIST_FILE).getAllLines();
		List<String> ret = new ArrayList<>();
		boolean dirty = false;
		if(lines != null) {
			for(int i = 0; i < lines.length; ++i) {
				String s = lines[i].trim();
				if(s.isEmpty() || ret.contains(s)) {
					dirty |= !s.isEmpty();
					continue;
				}
				// self-heal: drop ghosts (listed but no level data on the VFS)
				if(!getLevelDatFile(s, false).exists() && !getLevelDatFile(s, true).exists()) {
					dirty = true;
					continue;
				}
				ret.add(s);
			}
		}
		if(dirty) {
			writeWorldsListLocked(ret);
		}
		return ret;
	}

	private static void writeWorldsListLocked(List<String> list) {
		WorldsDB.newVFile(WORLDS_LIST_FILE).setAllChars(String.join("\n", list));
	}

	public static void ensureWorldListed(String levelId) {
		bootFilesystem();
		synchronized(diskLock) {
			ensureWorldListedLocked(levelId);
		}
	}

	/**
	 * Marks a completed EPK/vanilla import. Imported saves can be sparse around the
	 * stored player position, so the first login must not synchronously generate a
	 * full 3x3 neighborhood before showing terrain.
	 */
	public static void markWorldImported(String levelId) {
		bootFilesystem();
		WorldsDB.newVFile("worlds", levelId, IMPORTED_WORLD_MARKER).setAllBytes(new byte[] { 1 });
	}

	public static boolean isImportedWorld(String levelId) {
		bootFilesystem();
		return WorldsDB.newVFile("worlds", levelId, IMPORTED_WORLD_MARKER).exists();
	}

	private static void ensureWorldListedLocked(String levelId) {
		List<String> list = getWorldsListLocked();
		if(!list.contains(levelId)) {
			list.add(levelId);
			writeWorldsListLocked(list);
		}
	}

	private static void removeWorldFromListLocked(String levelId) {
		List<String> list = getWorldsListLocked();
		if(list.remove(levelId)) {
			writeWorldsListLocked(list);
		}
	}

	/** listed in worlds_list.txt or has level data blobs */
	public static boolean worldExists(String levelId) {
		bootFilesystem();
		synchronized(diskLock) {
			return getWorldsListLocked().contains(levelId) || getLevelDatFile(levelId, false).exists()
					|| getLevelDatFile(levelId, true).exists() || hasWorldBlobs(levelId);
		}
	}

	/** any blobs under worlds/<id>/ — catches ghost dirs from interrupted deletes,
	 * so a new world can never inherit a dead world's chunks */
	private static boolean hasWorldBlobs(String levelId) {
		List<VFile2> files = WorldsDB.newVFile("worlds", levelId).listFiles(true);
		return files != null && !files.isEmpty();
	}

	/**
	 * Vanilla FileUtil.findAvailableName probes the real saves dir with
	 * createDirectory; hosted mode dedupes against the VFS worlds list instead
	 * (same sanitize + " (N)" counter behavior).
	 */
	public static String findAvailableWorldName(String baseName) {
		bootFilesystem();
		baseName = FileUtil.sanitizeName(baseName);
		if(!FileUtil.isPathPartPortable(baseName)) {
			baseName = "_" + baseName + "_";
		}
		Matcher matcher = COPY_COUNTER_PATTERN.matcher(baseName);
		int count = 0;
		if(matcher.matches()) {
			baseName = matcher.group(1);
			count = Integer.parseInt(matcher.group(2));
		}
		if(baseName.length() > 255) {
			baseName = baseName.substring(0, 255);
		}
		synchronized(diskLock) {
			List<String> list = getWorldsListLocked();
			while(true) {
				String nameToTest = baseName;
				if(count != 0) {
					String countSuffix = " (" + count + ")";
					int maxLength = 255 - countSuffix.length();
					if(nameToTest.length() > maxLength) {
						nameToTest = nameToTest.substring(0, maxLength);
					}
					nameToTest = nameToTest + countSuffix;
				}
				if(!list.contains(nameToTest) && !getLevelDatFile(nameToTest, false).exists()
						&& !getLevelDatFile(nameToTest, true).exists() && !hasWorldBlobs(nameToTest)) {
					return nameToTest;
				}
				++count;
			}
		}
	}

	/** purge every blob under worlds/&lt;levelId&gt;/ and drop the list entry */
	public static void deleteWorld(String levelId) {
		bootFilesystem();
		synchronized(diskLock) {
			// delist FIRST: an interrupted delete leaves an unlisted ghost dir,
			// never a listed broken world (and ghosts are ignored by the dedup probe)
			removeWorldFromListLocked(levelId);
			List<VFile2> files = WorldsDB.newVFile("worlds", levelId).listFiles(true);
			for(int i = 0, l = files.size(); i < l; ++i) {
				files.get(i).delete();
			}
		}
	}

	/** upstream GuiScreenBackupWorldSelection "Duplicate World": copy every blob to a
	 * deduped new folder, rename the level, list it. Returns the new folder name. */
	public static String duplicateWorld(String srcId,
			net.lax1dude.eaglercraft.v1_8.sp.server.export.EaglerConvertProgress progress) throws IOException {
		bootFilesystem();
		String newId;
		synchronized(diskLock) {
			newId = findAvailableWorldName(srcId);
			String srcPrefix = WorldsDB.newVFile("worlds", srcId).getPath() + "/";
			List<VFile2> files = WorldsDB.newVFile("worlds", srcId).listFiles(true);
			for(int i = 0, l = files.size(); i < l; ++i) {
				VFile2 f = files.get(i);
				String rel = f.getPath().substring(srcPrefix.length());
				VFile2.copyFile(f, WorldsDB.newVFile("worlds", newId, rel));
				if(progress != null && (i % 200) == 0) {
					progress.report("singleplayer.busy.duplicating", (float) i / (float) l);
				}
			}
		}
		CompoundTag root = readLevelDat(newId, false);
		if(root != null) {
			CompoundTag data = root.getCompoundOrEmpty("Data");
			data.putString("LevelName", newId);
			root.put("Data", data);
			writeLevelDat(newId, root);
		}
		ensureWorldListed(newId);
		return newId;
	}

	/** how many timestamped in-VFS backups to keep per world before the oldest are pruned
	 *  (IndexedDB has a finite quota; a world backup is a full copy of the world folder). */
	private static final int MAX_BACKUPS_PER_WORLD = 5;

	/**
	 * Real in-VFS world backup (the vanilla {@code LevelStorageAccess.makeWorldBackup} analog;
	 * the desktop path zips the world dir, which needs a real-FS walk unavailable on the VFS).
	 * Recursively copies every blob under {@code worlds/<levelId>/} to
	 * {@code backups/<levelId>/<timestamp>/} using the same 1.8 {@code duplicateWorld} primitive
	 * ({@link VFile2#listFiles}/{@link VFile2#copyFile}), into a NON-playable {@code backups} area
	 * (never registered in worlds_list.txt, so it can't be mistaken for a world). The newest
	 * {@link #MAX_BACKUPS_PER_WORLD} backups are kept; older ones are pruned. A restorable snapshot
	 * so a bad save can't lose the world. Returns total bytes copied.
	 */
	public static long backupWorld(String levelId) throws IOException {
		bootFilesystem();
		String stamp = FileNameDateFormatter.FORMATTER.format(ZonedDateTime.now());
		long total = 0L;
		synchronized(diskLock) {
			VFile2 srcRoot = WorldsDB.newVFile("worlds", levelId);
			String srcPrefix = srcRoot.getPath() + "/";
			List<VFile2> files = srcRoot.listFiles(true);
			for(int i = 0, l = files.size(); i < l; ++i) {
				VFile2 f = files.get(i);
				String rel = f.getPath().substring(srcPrefix.length());
				if(rel.equals("session.lock")) {
					continue;
				}
				int n = VFile2.copyFile(f, WorldsDB.newVFile("backups", levelId, stamp, rel));
				if(n > 0) {
					total += n;
				}
			}
			pruneOldBackupsLocked(levelId);
		}
		logger.info("Backed up world \"{}\" to backups/{}/{} ({} bytes)", levelId, levelId, stamp, total);
		return total;
	}

	/** keep only the newest {@link #MAX_BACKUPS_PER_WORLD} timestamped backups for this level;
	 *  delete the blobs of every older snapshot. Must hold diskLock. */
	private static void pruneOldBackupsLocked(String levelId) {
		VFile2 backupRoot = WorldsDB.newVFile("backups", levelId);
		String prefix = backupRoot.getPath() + "/";
		List<VFile2> files = backupRoot.listFiles(true);
		// distinct <timestamp> first-segments; TreeSet keeps them in chronological (== lexical) order
		java.util.TreeSet<String> stamps = new java.util.TreeSet<>();
		for(int i = 0, l = files.size(); i < l; ++i) {
			String rel = files.get(i).getPath().substring(prefix.length());
			int slash = rel.indexOf('/');
			if(slash > 0) {
				stamps.add(rel.substring(0, slash));
			}
		}
		while(stamps.size() > MAX_BACKUPS_PER_WORLD) {
			String oldest = stamps.pollFirst();
			List<VFile2> del = WorldsDB.newVFile("backups", levelId, oldest).listFiles(true);
			for(int i = 0, l = del.size(); i < l; ++i) {
				del.get(i).delete();
			}
		}
	}

	/** upstream "Clear Player Data": wipe players/ (data, stats, advancements) */
	public static void clearPlayerData(String levelId) {
		bootFilesystem();
		synchronized(diskLock) {
			List<VFile2> files = WorldsDB.newVFile("worlds", levelId, "players").listFiles(true);
			if(files != null) {
				for(int i = 0, l = files.size(); i < l; ++i) {
					files.get(i).delete();
				}
			}
		}
	}

	/** world seed for the backup menu display; null when unreadable */
	public static Long readWorldSeed(String levelId) {
		try {
			CompoundTag root = readLevelDat(levelId, false);
			if(root == null) {
				return null;
			}
			CompoundTag wgs = root.getCompoundOrEmpty("Data").getCompoundOrEmpty("WorldGenSettings");
			return wgs.getLong("seed").orElse(null);
		}catch(Throwable t) {
			return null;
		}
	}

	// ==================== player data (players/data) ====================

	/** @return null when the file does not exist */
	public static CompoundTag readPlayerData(String levelId, String fileName) throws IOException {
		bootFilesystem();
		byte[] bytes;
		synchronized(diskLock) {
			bytes = WorldsDB.newVFile("worlds", levelId, "players/data", fileName).getAllBytes();
		}
		if(bytes == null) {
			return null;
		}
		return NbtIo.readCompressed(new EaglerInputStream(bytes), NbtAccounter.unlimitedHeap());
	}

	/** temp-file + safeReplaceFile rotation collapsed to blob writes, like level.dat */
	public static void writePlayerData(String levelId, String uuid, CompoundTag tag) throws IOException {
		bootFilesystem();
		ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
		NbtIo.writeCompressed(tag, out);
		byte[] bytes = out.toByteArray();
		synchronized(diskLock) {
			VFile2 current = WorldsDB.newVFile("worlds", levelId, "players/data", uuid + ".dat");
			byte[] oldBytes = current.getAllBytes();
			if(oldBytes != null) {
				WorldsDB.newVFile("worlds", levelId, "players/data", uuid + ".dat_old").setAllBytes(oldBytes);
			}
			current.setAllBytes(bytes);
		}
	}

	/** vanilla PlayerDataStorage.backup — snapshot a corrupt &lt;uuid&gt;&lt;suffix&gt; blob */
	public static void backupCorruptedPlayerData(String levelId, String idString, String suffix) {
		bootFilesystem();
		synchronized(diskLock) {
			byte[] bytes = WorldsDB.newVFile("worlds", levelId, "players/data", idString + suffix).getAllBytes();
			if(bytes != null) {
				WorldsDB.newVFile("worlds", levelId, "players/data",
						idString + "_corrupted_" + FileNameDateFormatter.FORMATTER.format(ZonedDateTime.now()) + suffix)
						.setAllBytes(bytes);
			}
		}
	}

	// ==================== stats + advancements json ====================

	public static VFile2 getPlayerStatsFile(String levelId, String jsonName) {
		return WorldsDB.newVFile("worlds", levelId, "players/stats", jsonName);
	}

	public static VFile2 getPlayerAdvancementsFile(String levelId, String jsonName) {
		return WorldsDB.newVFile("worlds", levelId, "players/advancements", jsonName);
	}

	/** @return null when the file does not exist */
	public static String readFileChars(VFile2 file) {
		bootFilesystem();
		synchronized(diskLock) {
			return file.getAllChars();
		}
	}

	public static void writeFileChars(VFile2 file, String chars) {
		bootFilesystem();
		synchronized(diskLock) {
			file.setAllChars(chars);
		}
	}

	// ==================== SavedData (data/<ns>/<path>.dat) ====================

	public static String getGlobalSavedDataPath(String levelId) {
		return "worlds/" + levelId + "/data";
	}

	/** same per-dimension folder convention as EaglerVFSChunkStorage */
	public static String getDimensionSavedDataPath(String levelId, Identifier dimension) {
		return "worlds/" + levelId + "/" + dimension.toString().replace(':', '_') + "/data";
	}

	/** @return null when the file does not exist */
	public static byte[] readSavedDataBytes(String dataPath, Identifier id) {
		bootFilesystem();
		synchronized(diskLock) {
			return WorldsDB.newVFile(dataPath, id.getNamespace(), id.getPath() + ".dat").getAllBytes();
		}
	}

	public static void writeSavedData(String dataPath, Identifier id, CompoundTag tag) throws IOException {
		net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.vfsWriteStarted("savedData");
		try {
			bootFilesystem();
			ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
			NbtIo.writeCompressed(tag, out);
			byte[] bytes = out.toByteArray();
			synchronized(diskLock) {
				WorldsDB.newVFile(dataPath, id.getNamespace(), id.getPath() + ".dat").setAllBytes(bytes);
			}
		} finally {
			net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.vfsWriteCompleted();
		}
	}

	/** vanilla LevelStorageSource.readExistingSavedData read (global data dir) */
	public static CompoundTag readSavedDataTag(String levelId, Identifier id) throws IOException {
		byte[] bytes = readSavedDataBytes(getGlobalSavedDataPath(levelId), id);
		if(bytes == null) {
			throw new FileNotFoundException(getGlobalSavedDataPath(levelId) + "/" + id.getNamespace() + "/" + id.getPath() + ".dat");
		}
		return NbtIo.readCompressed(new EaglerInputStream(bytes), NbtAccounter.unlimitedHeap());
	}

	// ==================== structure templates (generated/) ====================

	private static VFile2 getStructureTemplateFile(String levelId, Identifier id) {
		return WorldsDB.newVFile("worlds", levelId, "generated", id.getNamespace(), "structure", id.getPath() + ".nbt");
	}

	/** @return null when the template does not exist */
	public static byte[] readStructureTemplateBytes(String levelId, Identifier id) {
		bootFilesystem();
		synchronized(diskLock) {
			return getStructureTemplateFile(levelId, id).getAllBytes();
		}
	}

	public static boolean writeStructureTemplate(String levelId, Identifier id, CompoundTag tag) throws IOException {
		bootFilesystem();
		ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
		NbtIo.writeCompressed(tag, out);
		byte[] bytes = out.toByteArray();
		synchronized(diskLock) {
			getStructureTemplateFile(levelId, id).setAllBytes(bytes);
		}
		return true;
	}

	public static List<Identifier> listStructureTemplates(String levelId) {
		bootFilesystem();
		List<String> names;
		synchronized(diskLock) {
			names = WorldsDB.newVFile("worlds", levelId, "generated").listFilenames(true);
		}
		String prefix = "worlds/" + levelId + "/generated/";
		List<Identifier> ret = new ArrayList<>();
		for(int i = 0, l = names.size(); i < l; ++i) {
			String s = names.get(i);
			if(!s.startsWith(prefix) || !s.endsWith(".nbt")) {
				continue;
			}
			String rel = s.substring(prefix.length(), s.length() - 4);
			int idx = rel.indexOf('/');
			if(idx == -1) {
				continue;
			}
			String namespace = rel.substring(0, idx);
			String path = rel.substring(idx + 1);
			if(!path.startsWith("structure/")) {
				continue;
			}
			Identifier id = Identifier.tryBuild(namespace, path.substring(10));
			if(id != null) {
				ret.add(id);
			}
		}
		return ret;
	}

	// ==================== world icon ====================

	public static boolean hasWorldIcon(String levelId) {
		bootFilesystem();
		synchronized(diskLock) {
			return WorldsDB.newVFile("worlds", levelId, "icon.png").exists();
		}
	}

	/** @return the icon.png blob, or null when the world has no icon */
	public static byte[] readWorldIcon(String levelId) {
		bootFilesystem();
		synchronized(diskLock) {
			return WorldsDB.newVFile("worlds", levelId, "icon.png").getAllBytes();
		}
	}

	public static void deleteWorldIcon(String levelId) {
		bootFilesystem();
		synchronized(diskLock) {
			WorldsDB.newVFile("worlds", levelId, "icon.png").delete();
		}
	}

	public static void writeWorldIcon(String levelId, byte[] pngBytes) {
		bootFilesystem();
		try {
			synchronized(diskLock) {
				WorldsDB.newVFile("worlds", levelId, "icon.png").setAllBytes(pngBytes);
			}
		}catch(Throwable t) {
			logger.warn("Couldn't save the icon of world \"{}\" to the worlds DB", levelId);
			logger.warn(t);
		}
	}

	// ==================== datapacks ====================

	/**
	 * Copies the temp-dir datapacks selected on the create screen into the VFS.
	 * They are stored for later, NOT loaded — see the hosted branch in
	 * ServerPacksSource.createPackRepository.
	 */
	public static void copyDatapacksToVFS(String levelId, Path tempDataPackDir) throws IOException {
		bootFilesystem();
		try(Stream<Path> walk = Files.walk(tempDataPackDir)) {
			for(Iterator<Path> itr = walk.iterator(); itr.hasNext();) {
				Path source = itr.next();
				if(!Files.isRegularFile(source)) {
					continue;
				}
				String rel = tempDataPackDir.relativize(source).toString().replace('\\', '/');
				byte[] bytes = Files.readAllBytes(source);
				synchronized(diskLock) {
					WorldsDB.newVFile("worlds", levelId, "datapacks", rel).setAllBytes(bytes);
				}
			}
		}
	}

	public static void warnIfWorldDatapacksPresent(String levelId) {
		bootFilesystem();
		boolean present;
		synchronized(diskLock) {
			present = !WorldsDB.newVFile("worlds", levelId, "datapacks").listFilenames(true).isEmpty();
		}
		if(present) {
			logger.warn("World \"{}\" has datapacks stored on the VFS, world datapacks are not yet supported in hosted mode and will be ignored!", levelId);
		}
	}

}
