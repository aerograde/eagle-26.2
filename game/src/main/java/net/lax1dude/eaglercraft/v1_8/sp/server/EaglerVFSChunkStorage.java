package net.lax1dude.eaglercraft.v1_8.sp.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.lax1dude.eaglercraft.v1_8.internal.vfs2.VFile2;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * 26.2 upgrade of upstream EaglerChunkLoader: per-chunk gzip-NBT blobs on the
 * Eagler VFS instead of .mca region files. Plugs into the narrowest vanilla seam
 * (IOWorker's RegionFileStorage — chunks, POI, and entities all flow through it;
 * DFU runs above, so world upgrades keep working).
 *
 * <p>Layout (evolves the 1.8 worlds DB convention for 26.2's typed storages):
 *   {@code worlds/<level>/<dimension namespace_path>/<type>/<XXXXXXYYYYYY>}
 * where the chunk file name is the upstream 6+6 hex digit encoding of
 * (x + 1900000, z + 1900000).</p>
 *
 * <h2>Codec (c98): synchronous java.util.zip GZIP — identical to desktop and to the
 * proven 1.8 {@code EaglerChunkLoader}/{@code CompressedStreamTools} path.</h2>
 *
 * <p>Earlier builds (c8x) compressed/decompressed chunk NBT on web with the browser
 * Compression Streams API through {@code PlatformChunkCompression}, a TeaVM {@code @Async}
 * seam. That path had a recurring fatal defect: the {@code @Async} browser gunzip on the
 * world-<b>reopen</b> read path (a green-thread suspend point that in-session writes never
 * hit — chunks are RAM-cached in-session and only decompressed on reopen) intermittently
 * resumed onto a null continuation, escaping as a per-chunk
 * {@code "Failed to read chunk … (JavaScript) TypeError: Cannot read properties of null"}
 * error wall that made every reopened world unusable (the 'cBL'/'cBy'/'cCd'/'cCg' family).</p>
 *
 * <p>The original justification for the browser path — "TeaVM 0.13's java.util.zip inflater
 * truncates gzip streams past the 32 KiB deflate window (Eagler/TeaVM gap #14)" — is FALSE
 * for this runtime: the shipped {@code assets.epk} (compression 'G', 5.5 MB, 19508 files) is
 * decompressed on web by stock {@code new GZIPInputStream(is)} every boot and the client
 * loads fine, i.e. the synchronous inflater handles multi-megabyte streams correctly. The
 * {@code teavm-compat} Inflater injector only touches the NIO {@code ByteBuffer} overloads,
 * not the {@code byte[]} path {@code GZIPInputStream} uses, and the write side's old
 * Deflater-flush crash is already fixed by {@code GZIPOutputStreamInject}. So chunks now use
 * the same synchronous {@code NbtIo.writeCompressed}/{@code readCompressed} (GZIP) on every
 * platform: deterministic, no {@code @Async}, ~6.6x smaller than raw NBT, and byte-compatible
 * with (a) desktop, (b) existing browser-gzip blobs from c8x — all are standard gzip (1f 8b)
 * of an unnamed NBT root — and (c) legacy c78 uncompressed blobs (sniffed by the missing gzip
 * magic and read raw). Reopened worlds load with identical content, zero regeneration.</p>
 */
public class EaglerVFSChunkStorage extends RegionFileStorage {

	private static final Logger LOGGER = LogUtils.getLogger();

	private final Object diskLock = new Object();
	private final String levelId;
	private final String dimensionPath;
	private final String typeName;

	/** true on web (browser runtime); false on desktop. Only used to gate the web DFU guard. */
	private final boolean web;

	public EaglerVFSChunkStorage(RegionStorageInfo info, Path folder, boolean sync) {
		super(info, folder, sync);
		this.levelId = info.level();
		this.dimensionPath = info.dimension().identifier().toString().replace(':', '_');
		this.typeName = info.type();
		this.web = net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
				!= net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP;
	}

	/** gzip streams start with 0x1f 0x8b; uncompressed NBT starts with a tag id (0x0A for the root compound) */
	private static boolean isGzip(byte[] b) {
		return b.length >= 2 && (b[0] & 0xFF) == 0x1f && (b[1] & 0xFF) == 0x8b;
	}

	private VFile2 getChunkFile(ChunkPos pos) {
		return WorldsDB.newVFile("worlds", levelId, dimensionPath, typeName, getChunkPath(pos));
	}

	/** Upstream EaglerChunkLoader.getChunkPath convention. */
	public static String getChunkPath(ChunkPos pos) {
		String x = Integer.toHexString(pos.x() + 1900000);
		String z = Integer.toHexString(pos.z() + 1900000);
		StringBuilder sb = new StringBuilder(12);
		for(int i = x.length(); i < 6; ++i) {
			sb.append('0');
		}
		sb.append(x);
		for(int i = z.length(); i < 6; ++i) {
			sb.append('0');
		}
		sb.append(z);
		return sb.toString();
	}

	/**
	 * Cheap hosted-world bootstrap probe. A saved center chunk can be loaded on its
	 * own; forcing all eight absent neighbors to FULL before login turns sparse EPK
	 * backups into a long world-generation stall. The normal chunk read still owns
	 * decoding and corruption handling after this existence check.
	 */
	public static boolean hasStoredChunk(String levelId, ResourceKey<Level> dimension, ChunkPos pos) {
		String dimensionPath = dimension.identifier().toString().replace(':', '_');
		return WorldsDB.newVFile("worlds", levelId, dimensionPath, "chunk", getChunkPath(pos)).exists();
	}

	/**
	 * Finds the stored chunk nearest a suggested spawn. Sparse imports can contain
	 * valid terrain beside a now-missing player/spawn chunk; using that terrain is
	 * both faster and safer than silently generating replacement chunks on login.
	 */
	public static ChunkPos findNearestStoredChunk(String levelId, ResourceKey<Level> dimension, ChunkPos origin) {
		String dimensionPath = dimension.identifier().toString().replace(':', '_');
		List<String> files = WorldsDB.newVFile("worlds", levelId, dimensionPath, "chunk").listFilenames(false);
		ChunkPos nearest = null;
		long nearestDistance = Long.MAX_VALUE;
		for (int i = 0, l = files.size(); i < l; ++i) {
			String name = VFile2.getNameFromPath(files.get(i));
			if (name.length() != 12) {
				continue;
			}
			try {
				int x = Integer.parseInt(name.substring(0, 6), 16) - 1900000;
				int z = Integer.parseInt(name.substring(6, 12), 16) - 1900000;
				long dx = (long)x - origin.x();
				long dz = (long)z - origin.z();
				long distance = dx * dx + dz * dz;
				if (distance < nearestDistance) {
					nearestDistance = distance;
					nearest = new ChunkPos(x, z);
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return nearest;
	}

	@Override
	public CompoundTag read(ChunkPos pos) throws IOException {
		byte[] bytes;
		try {
			synchronized(diskLock) {
				// One IndexedDB operation. VFile2.getAllBytes() already returns null when absent;
				// the old outer exists() plus getAllBytes()' own exists() made this three @Async
				// suspends per chunk and exposed the wasm-gc continuation bug seen on world reopen.
				bytes = getChunkFile(pos).getAllBytes();
			}
		} catch (RuntimeException t) {
			// A runtime/continuation failure is NOT evidence that the file is absent or corrupt.
			// Propagate it so callers can preserve an existing full chunk rather than regenerate
			// and overwrite user terrain.
			throw new IOException("VFS read of " + typeName + " chunk " + pos + " failed: " + describeCause(t), t);
		}
		if(bytes == null) {
			return null;
		}
		CompoundTag tag;
		try {
			tag = decodeChunkNbt(bytes);
		} catch (IOException | RuntimeException t) {
			// Genuine corruption (not a runtime defect — the decode is fully synchronous now).
			// LOUD diagnostic, non-destructive (we never delete the user's blob), return null so
			// vanilla treats the chunk as absent and regenerates it. The blob stays on disk (and a
			// world backup can be taken via EaglerVFSWorldStorage.backupWorld), so nothing is dropped.
			LOGGER.warn("Failed to decode {} chunk {} ({} bytes): {} — regenerating (original blob kept; world backup available)",
					typeName, pos, bytes.length, describeCause(t));
			return null;
		}
		if (rejectsOldDataVersion(tag)) {
			LOGGER.warn("Refusing {} chunk {}: {} — regenerating (original blob kept)",
					typeName, pos, describeCause(oldDataVersionCause(tag)));
			return null;
		}
		return tag;
	}

	@Override
	public void scanChunk(ChunkPos pos, StreamTagVisitor scanner) throws IOException {
		byte[] bytes;
		try {
			synchronized(diskLock) {
				bytes = getChunkFile(pos).getAllBytes();
			}
		} catch (RuntimeException t) {
			throw new IOException("VFS scan read of " + typeName + " chunk " + pos + " failed: " + describeCause(t), t);
		}
		if(bytes == null) {
			return;
		}
		CompoundTag tag;
		try {
			tag = decodeChunkNbt(bytes);
		} catch (IOException | RuntimeException t) {
			LOGGER.warn("Failed to decode {} chunk {} for scan ({} bytes): {} — treating as absent",
					typeName, pos, bytes.length, describeCause(t));
			return;
		}
		if (rejectsOldDataVersion(tag)) {
			return;
		}
		tag.acceptAsRoot(scanner);
	}

	/**
	 * Web only: the DataFixerUpper cannot run on TeaVM (its optics build Guava
	 * super-type-tokens whose getGenericSuperclass TeaVM cannot resolve — the same reason
	 * {@code DataFixers.optimize} is skipped on web). Any chunk whose DataVersion is older
	 * than the current build would be routed into that broken DFU by
	 * {@code SimpleRegionStorage.upgradeChunkTag} AFTER this storage returns — producing a
	 * per-chunk raw-TypeError error wall in ChunkMap/entity/POI handlers before the chunk
	 * regenerates anyway. Refuse such chunks HERE so they take the ONE-quiet-warn path
	 * instead. Same-build worlds (DataVersion == current) never trip this. Desktop (working
	 * JVM DFU) is untouched.
	 */
	private boolean rejectsOldDataVersion(CompoundTag tag) {
		if (!web) {
			return false;
		}
		int dataVersion = tag.getIntOr(net.minecraft.SharedConstants.DATA_VERSION_TAG, -1);
		return dataVersion < net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
	}

	private static Throwable oldDataVersionCause(CompoundTag tag) {
		return new IOException("DataVersion " + tag.getIntOr(net.minecraft.SharedConstants.DATA_VERSION_TAG, -1)
				+ " needs DFU upgrade to " + net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version()
				+ ", unavailable in the browser runtime");
	}

	/**
	 * Decode a chunk blob into a validated CompoundTag with the synchronous java.util.zip
	 * codec (identical to desktop / 1.8 CompressedStreamTools). gzip magic (1f 8b) =>
	 * {@code NbtIo.readCompressed} (GZIPInputStream); anything else => raw NBT (legacy c78
	 * uncompressed blobs). The parse is deep-validated: a truncated blob that somehow parsed
	 * into a tree with a null child would otherwise TypeError in a vanilla consumer far from
	 * the read site.
	 */
	private CompoundTag decodeChunkNbt(byte[] bytes) throws IOException {
		CompoundTag tag;
		if (isGzip(bytes)) {
			try (InputStream in = new ByteArrayInputStream(bytes)) {
				tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
			}
		} else {
			try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
				tag = NbtIo.read(in, NbtAccounter.unlimitedHeap());
			}
		}
		if (tag == null) {
			throw new IOException("NBT parse produced a null root tag");
		}
		validateTag(tag, 0);
		return tag;
	}

	/** Reject trees with null children (a corruption sentinel) so they take the quiet
	 *  regenerate path here rather than TypeError later in a vanilla consumer. */
	private static void validateTag(Tag tag, int depth) throws IOException {
		if (depth > 512) {
			throw new IOException("NBT tree deeper than 512");
		}
		if (tag instanceof CompoundTag compound) {
			for (Map.Entry<String, Tag> entry : compound.entrySet()) {
				Tag child = entry.getValue();
				if (child == null) {
					throw new IOException("null NBT child at key '" + entry.getKey() + "'");
				}
				validateTag(child, depth + 1);
			}
		} else if (tag instanceof ListTag list) {
			for (int i = 0, n = list.size(); i < n; ++i) {
				Tag child = list.get(i);
				if (child == null) {
					throw new IOException("null NBT list element " + i);
				}
				validateTag(child, depth + 1);
			}
		}
	}

	private static String describeCause(Throwable t) {
		if (t == null) {
			return "unknown";
		}
		Throwable root = t;
		int guard = 0;
		while (root.getCause() != null && root.getCause() != root && guard++ < 20) {
			root = root.getCause();
		}
		String name = root.getClass().getSimpleName();
		String msg = root.getMessage();
		return (msg != null && msg.length() <= 160) ? name + ": " + msg : name;
	}

	@Override
	public void write(ChunkPos pos, CompoundTag value) throws IOException {
		net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.vfsWriteStarted("chunk");
		try {
			if(value == null) {
				synchronized(diskLock) {
					getChunkFile(pos).delete();
				}
				return;
			}
			// Synchronous java.util.zip GZIP on every platform (desktop == web == 1.8 codec). The
			// output is a standard gzip stream of an unnamed NBT root, byte-compatible with existing
			// c8x browser-gzip blobs, and read back by NbtIo.readCompressed. The web Deflater-flush
			// crash is fixed by teavm-compat GZIPOutputStreamInject.
			ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
			NbtIo.writeCompressed(value, out);
			byte[] bytes = out.toByteArray();
			synchronized(diskLock) {
				VFile2 file = getChunkFile(pos);
				file.setAllBytes(bytes);
				// Cheap post-write size check (eaglerSize, no full re-read): catches a VFS/IndexedDB
				// truncation at write time. Log-only.
				int stored = file.length();
				if (stored != bytes.length) {
					LOGGER.warn("VFS chunk {} write size MISMATCH: wrote {} bytes but stored length={} (truncation at write)",
							pos, bytes.length, stored);
				}
			}
		} finally {
			net.lax1dude.eaglercraft.v1_8.sp.server.EaglerServerPerf.vfsWriteCompleted();
		}
	}

	@Override
	public void flush() throws IOException {
		// VFS writes are immediate
	}

	@Override
	public void close() throws IOException {
		// no file handles held
	}

}
