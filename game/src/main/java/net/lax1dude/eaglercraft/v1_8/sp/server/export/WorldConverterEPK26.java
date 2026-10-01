package net.lax1dude.eaglercraft.v1_8.sp.server.export;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.EagUtils;
import net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType;
import net.lax1dude.eaglercraft.v1_8.internal.vfs2.VFile2;
import net.lax1dude.eaglercraft.v1_8.log4j.LogManager;
import net.lax1dude.eaglercraft.v1_8.log4j.Logger;
import net.lax1dude.eaglercraft.v1_8.profile.EaglerProfile;
import net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSWorldStorage;
import net.lax1dude.eaglercraft.v1_8.sp.server.WorldsDB;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Util;

/**
 * 26.2 upgrade of upstream sp/server/export/WorldConverterEPK: packs a hosted
 * VFS world folder (worlds/&lt;id&gt;/) into an EPK v2 archive and back. The
 * container format is byte-compatible with upstream (EPKCompiler/EPKDecompiler
 * on the :platform classpath); the file-type HEAD tag is "epk/world262"
 * because the payload is 26.2 world data (per-chunk gzip NBT blobs, extracted
 * saved-data files) that a 1.8 client cannot use, and vice versa — 1.8 world
 * EPKs (epk/world188 / epk/world152) are rejected on import for now since the
 * region-layout + NBT differences need a full DFU bridge from 1.8.
 */
public class WorldConverterEPK26 {

	private static final Logger logger = LogManager.getLogger("WorldConverterEPK26");
	private static final boolean YIELD_BROWSER_WORK = EagRuntime.getPlatformType() != EnumPlatformType.DESKTOP;

	public static final String FILE_TYPE = "epk/world262";

	/** the profile username as the EPK world-owner tag (call on the client thread) */
	public static String getExportOwner() {
		EaglerProfile.readIfNeeded();
		String owner = EaglerProfile.getName();
		return owner == null || owner.isEmpty() ? "UNKNOWN" : owner;
	}

	/** pack every blob under worlds/<levelId>/ into an EPK (entry names relative to the world dir) */
	public static byte[] exportWorld(String levelId, String owner, EaglerConvertProgress progress) throws IOException {
		EaglerVFSWorldStorage.bootFilesystem();
		VFile2 worldDir = WorldsDB.newVFile("worlds", levelId);
		logger.info("Exporting world directory \"{}\" as EPK", worldDir.getPath());
		List<VFile2> filesList = worldDir.listFiles(true);
		if(filesList == null || filesList.isEmpty()) {
			throw new IOException("World \"" + levelId + "\" has no data to export!");
		}
		if(owner == null || owner.isEmpty()) {
			owner = "UNKNOWN";
		}
		EPKCompiler c = new EPKCompiler(levelId, owner, FILE_TYPE);
		String pfx = worldDir.getPath();
		long bytesWritten = 0L;
		int total = filesList.size();
		for(int i = 0; i < total; ++i) {
			if(YIELD_BROWSER_WORK && (i & 63) == 0) {
				EagUtils.sleep(0L);
			}
			VFile2 vf = filesList.get(i);
			byte[] b = vf.getAllBytes();
			if(b == null) {
				continue;
			}
			c.append(vf.getPath().substring(pfx.length() + 1), b);
			bytesWritten += b.length;
			if(progress != null) {
				progress.report((i + 1) + " files, " + (bytesWritten / 1024L) + " KB", (float)(i + 1) / (float)total);
			}
		}
		byte[] r = c.complete();
		logger.info("World directory \"{}\" was successfully exported as EPK ({} files, {} bytes)",
				worldDir.getPath(), total, r.length);
		return r;
	}

	/**
	 * Extract an epk/world262 archive into worlds/&lt;folderName&gt;/. The caller
	 * must have deduped folderName via EaglerVFSWorldStorage.findAvailableWorldName;
	 * level.dat is rewritten (LevelName = displayName, fresh LastPlayed) and
	 * written LAST so an interrupted import leaves an unlisted ghost dir, never a
	 * listed broken world.
	 */
	public static void importWorld(byte[] archiveContents, String folderName, String displayName,
			EaglerConvertProgress progress) throws IOException {
		logger.info("Importing world \"{}\" from EPK", folderName);
		EaglerVFSWorldStorage.bootFilesystem();
		byte[] levelDat = null;
		byte[] levelDatOld = null;
		try(EPKDecompiler dc = new EPKDecompiler(archiveContents)) {
			int total = Math.max(dc.getNumFiles(), 1);
			int done = 0;
			long bytesWritten = 0L;
			boolean hasReadType = false;
			EPKDecompiler.FileEntry f;
			while((f = dc.readFile()) != null) {
				++done;
				if(YIELD_BROWSER_WORK && (done & 63) == 0) {
					EagUtils.sleep(0L);
				}
				if(!hasReadType) {
					if("HEAD".equals(f.type) && "file-type".equals(f.name)) {
						String type = EPKDecompiler.readASCII(f.data);
						if(FILE_TYPE.equals(type)) {
							hasReadType = true;
							continue;
						}else if("epk/world188".equals(type) || "epk/world152".equals(type)) {
							throw new IOException("This is an Eaglercraft 1.8/1.5.2 world (" + type
									+ "), which this 26.2 client cannot import yet");
						}else {
							throw new IOException("Unsupported world type: " + type);
						}
					}else {
						throw new IOException("File does not contain an Eaglercraft 26.2 world!");
					}
				}
				if(!"FILE".equals(f.type)) {
					continue; // world-name / world-owner HEAD entries
				}
				String name = sanitizeEntryName(f.name);
				if(name == null) {
					logger.warn("Skipping EPK entry with unsafe name: {}", f.name);
					continue;
				}
				if("level.dat".equals(name)) {
					levelDat = f.data;
				}else if("level.dat_old".equals(name)) {
					levelDatOld = f.data;
				}else {
					WorldsDB.newVFile("worlds", folderName, name).setAllBytes(f.data);
				}
				bytesWritten += f.data.length;
				if(progress != null) {
					progress.report(done + " files, " + (bytesWritten / 1024L) + " KB", (float)done / (float)total);
				}
			}
		}
		if(levelDat == null && levelDatOld == null) {
			throw new IOException("The EPK file did not contain a level.dat!");
		}
		if(levelDatOld != null) {
			WorldsDB.newVFile("worlds", folderName, "level.dat_old")
					.setAllBytes(rewriteLevelName(levelDatOld, displayName));
		}
		if(levelDat != null) {
			WorldsDB.newVFile("worlds", folderName, "level.dat")
					.setAllBytes(rewriteLevelName(levelDat, displayName));
		}
		EaglerVFSWorldStorage.markWorldImported(folderName);
		EaglerVFSWorldStorage.ensureWorldListed(folderName);
		logger.info("EPK was successfully extracted into world \"{}\"", folderName);
	}

	/** Data.LevelName = displayName + fresh Data.LastPlayed (world262 payloads are already current-version) */
	private static byte[] rewriteLevelName(byte[] levelDatBytes, String displayName) throws IOException {
		CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(levelDatBytes), NbtAccounter.unlimitedHeap());
		CompoundTag data = root.getCompoundOrEmpty("Data");
		data.putString("LevelName", displayName);
		data.putLong("LastPlayed", Util.getEpochMillis());
		root.put("Data", data);
		ByteArrayOutputStream out = new ByteArrayOutputStream(levelDatBytes.length + 64);
		NbtIo.writeCompressed(root, out);
		return out.toByteArray();
	}

	/**
	 * The world-name HEAD entry of a world EPK, or null when the archive has none
	 * (or is not a valid EPK at all) — used to pre-fill the import name screen,
	 * NOT for validation (importWorld rejects bad types itself).
	 */
	public static String readWorldName(byte[] archiveContents) {
		try(EPKDecompiler dc = new EPKDecompiler(archiveContents)) {
			EPKDecompiler.FileEntry f;
			while((f = dc.readFile()) != null) {
				if(!"HEAD".equals(f.type)) {
					break;
				}
				if("world-name".equals(f.name)) {
					return EPKDecompiler.readASCII(f.data);
				}
			}
		}catch(Throwable t) {
			// name preview only, the import step reports real errors
		}
		return null;
	}

	/** normalize an archive-relative path; null = reject (absolute / traversal / empty) */
	static String sanitizeEntryName(String name) {
		if(name == null) {
			return null;
		}
		String s = name.replace('\\', '/');
		while(s.startsWith("/")) {
			s = s.substring(1);
		}
		if(s.isEmpty()) {
			return null;
		}
		for(String part : s.split("/")) {
			if(part.isEmpty() || ".".equals(part) || "..".equals(part)) {
				return null;
			}
		}
		return s;
	}

}
