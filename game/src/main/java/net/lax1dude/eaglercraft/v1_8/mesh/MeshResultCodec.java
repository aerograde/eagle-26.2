/*
 * Copyright (c) 2026 lax1dude / Eagler 26.2. All Rights Reserved.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES.
 */

package net.lax1dude.eaglercraft.v1_8.mesh;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Serializes section compile results for worker transfer and parity checks.
 * Block entities are excluded and reattached by the caller.
 *
 * <pre>
 *   u8   magic0..3 = "EMR1"
 *   u8   version = 1
 *   u8   layerMask (bit i set iff ChunkSectionLayer.values()[i] is present)
 *   per present layer, in ChunkSectionLayer.values() order:
 *     i32 vertexCount
 *     i32 indexCount
 *     u8  primitiveTopology ordinal
 *     u8  indexType ordinal
 *     i32 vertexByteLen ; vertexBytes[]
 *     i32 indexByteLen  ; indexBytes[]     (0 when the layer uses the shared auto index)
 *   i64  visibility bits (bit d1*6+d2 = visibilityBetween(d1,d2))
 * </pre>
 */
public final class MeshResultCodec {

	private static final int MAGIC = 0x454D5231; // "EMR1"
	private static final byte VERSION = 1;
	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	private static final Direction[] DIRS = Direction.values();

	private MeshResultCodec() {
	}

	public static byte[] encode(final SectionCompiler.Results results) {
		int layerMask = 0;
		MeshData[] meshes = new MeshData[LAYERS.length];
		ByteBuffer[] vertexBuffers = new ByteBuffer[LAYERS.length];
		ByteBuffer[] indexBuffers = new ByteBuffer[LAYERS.length];
		int totalBytes = 6 + 8;
		for (int i = 0; i < LAYERS.length; ++i) {
			MeshData mesh = results.renderedLayers.get(LAYERS[i]);
			meshes[i] = mesh;
			if (mesh != null) {
				layerMask |= (1 << i);
				ByteBuffer vertex = mesh.vertexBuffer();
				ByteBuffer index = mesh.indexBuffer();
				vertexBuffers[i] = vertex;
				indexBuffers[i] = index;
				totalBytes += 18 + vertex.remaining() + (index == null ? 0 : index.remaining());
			}
		}

		// Allocate exactly once. The old ByteArrayOutputStream path allocated one temporary
		// byte[] per buffer and copied the entire stream again in toByteArray().
		byte[] out = new byte[totalBytes];
		int pos = 0;
		pos = putI32(out, pos, MAGIC);
		out[pos++] = VERSION;
		out[pos++] = (byte)(layerMask & 0xFF);

		for (int i = 0; i < LAYERS.length; ++i) {
			MeshData mesh = meshes[i];
			if (mesh == null) {
				continue;
			}
			MeshData.DrawState ds = mesh.drawState();
			pos = putI32(out, pos, ds.vertexCount());
			pos = putI32(out, pos, ds.indexCount());
			out[pos++] = (byte)(ds.primitiveTopology().ordinal() & 0xFF);
			out[pos++] = (byte)(ds.indexType().ordinal() & 0xFF);
			pos = putBuffer(out, pos, vertexBuffers[i]);
			pos = putBuffer(out, pos, indexBuffers[i]);
		}

		long vis = 0L;
		for (Direction d1 : DIRS) {
			for (Direction d2 : DIRS) {
				if (results.visibilitySet.visibilityBetween(d1, d2)) {
					vis |= 1L << (d1.ordinal() * 6 + d2.ordinal());
				}
			}
		}
		putI64(out, pos, vis);
		return out;
	}

	private static final PrimitiveTopology[] TOPOLOGIES = PrimitiveTopology.values();
	private static final IndexType[] INDEX_TYPES = IndexType.values();

	/**
	 * Rebuilds live results from a worker blob. Translucent indices are regenerated
	 * from the reconstructed vertices. The caller must discard {@code pack} first.
	 */
	public static SectionCompiler.Results decodeToResults(final byte[] blob, final VertexSorting sorting,
			final List<BlockEntity> blockEntities, final SectionBufferBuilderPack pack) {
		SectionCompiler.Results results = new SectionCompiler.Results();
		results.blockEntities.addAll(blockEntities);

		int pos = 0;
		int magic = readI32(blob, pos);
		pos += 4;
		if (magic != MAGIC) {
			throw new IllegalStateException("bad mesh-result magic 0x" + Integer.toHexString(magic));
		}
		int version = blob[pos++] & 0xFF;
		if (version != (VERSION & 0xFF)) {
			throw new IllegalStateException("unsupported mesh-result version " + version);
		}
		int layerMask = blob[pos++] & 0xFF;

		for (int i = 0; i < LAYERS.length; ++i) {
			if ((layerMask & (1 << i)) == 0) {
				continue;
			}
			ChunkSectionLayer layer = LAYERS[i];
			int vertexCount = readI32(blob, pos);
			pos += 4;
			int indexCount = readI32(blob, pos);
			pos += 4;
			int topology = blob[pos++] & 0xFF;
			int indexType = blob[pos++] & 0xFF;
			int vtxLen = readI32(blob, pos);
			pos += 4;
			int vtxOff = pos;
			pos += vtxLen;
			int idxLen = readI32(blob, pos);
			pos += 4;
			pos += idxLen; // Translucent indices are regenerated below.

			MeshData.DrawState drawState = new MeshData.DrawState(layer.vertexFormat(), vertexCount, indexCount,
					TOPOLOGIES[topology], INDEX_TYPES[indexType]);
			MeshData mesh = wrapVertexBytes(pack.buffer(layer), blob, vtxOff, vtxLen, drawState);
			if (layer == ChunkSectionLayer.TRANSLUCENT) {
				results.transparencyState = mesh.sortQuads(pack.buffer(layer), sorting);
			}
			results.renderedLayers.put(layer, mesh);
		}

		long vis = readI64(blob, pos);
		VisibilitySet visibilitySet = new VisibilitySet();
		for (Direction d1 : DIRS) {
			for (Direction d2 : DIRS) {
				if ((vis & (1L << (d1.ordinal() * 6 + d2.ordinal()))) != 0L) {
					visibilitySet.set(d1, d2, true);
				}
			}
		}
		results.visibilitySet = visibilitySet;
		return results;
	}

	/** Copy {@code len} bytes at {@code off} into a fresh native allocation from {@code bb},
	 *  returning a {@link MeshData} whose {@code vertexBuffer()} reads that direct memory. */
	private static MeshData wrapVertexBytes(final ByteBufferBuilder bb, final byte[] src, final int off, final int len,
			final MeshData.DrawState drawState) {
		bb.reserve(len);
		ByteBufferBuilder.Result result = bb.build();
		ByteBuffer dst = result.byteBuffer();
		dst.put(src, off, len);
		return new MeshData(result, drawState);
	}

	private static long readI64(final byte[] b, final int off) {
		long v = 0L;
		for (int i = 0; i < 8; ++i) {
			v |= (long) (b[off + i] & 0xFF) << (i << 3);
		}
		return v;
	}

	private static int putBuffer(final byte[] out, int pos, final ByteBuffer buf) {
		if (buf == null) {
			return putI32(out, pos, 0);
		}
		int len = buf.remaining();
		pos = putI32(out, pos, len);
		buf.get(out, pos, len);
		return pos + len;
	}

	private static int putI32(final byte[] out, final int pos, final int v) {
		out[pos] = (byte)(v & 0xFF);
		out[pos + 1] = (byte)((v >> 8) & 0xFF);
		out[pos + 2] = (byte)((v >> 16) & 0xFF);
		out[pos + 3] = (byte)((v >> 24) & 0xFF);
		return pos + 4;
	}

	private static void putI64(final byte[] out, final int pos, final long v) {
		for (int i = 0; i < 8; ++i) {
			out[pos + i] = (byte)((v >> (i << 3)) & 0xFF);
		}
	}

	// Mismatch diagnostics for inline and worker blobs.

	/** One parsed region of the blob: [start,end) with a label; vertex regions carry
	 *  the layer's VertexFormat for attribute mapping. */
	private static final class Region {
		final int start;
		final int end;
		final String label;
		final VertexFormat fmt; // non-null only for vertex-data regions

		Region(final int start, final int end, final String label, final VertexFormat fmt) {
			this.start = start;
			this.end = end;
			this.label = label;
			this.fmt = fmt;
		}
	}

	private static int readI32(final byte[] b, final int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	/** Parses labeled regions until the input ends or becomes invalid. */
	private static List<Region> parseLayout(final byte[] blob) {
		List<Region> out = new ArrayList<>();
		out.add(new Region(0, 4, "magic", null));
		out.add(new Region(4, 5, "version", null));
		out.add(new Region(5, 6, "layerMask", null));
		if (blob.length < 6) {
			return out;
		}
		int mask = blob[5] & 0xFF;
		int p = 6;
		for (int i = 0; i < LAYERS.length && p + 14 <= blob.length; ++i) {
			if ((mask & (1 << i)) == 0) {
				continue;
			}
			ChunkSectionLayer layer = LAYERS[i];
			int vtxCount = readI32(blob, p);
			int idxCount = readI32(blob, p + 4);
			out.add(new Region(p, p + 10, layer + " DrawState(vtx=" + vtxCount + ",idx=" + idxCount + ")", null));
			p += 10;
			int vtxLen = readI32(blob, p);
			out.add(new Region(p, p + 4, layer + " vtxLen", null));
			p += 4;
			out.add(new Region(p, p + vtxLen, layer + " vertexBytes", layer.vertexFormat()));
			p += vtxLen;
			if (p + 4 > blob.length) {
				break;
			}
			int idxLen = readI32(blob, p);
			out.add(new Region(p, p + 4, layer + " idxLen", null));
			p += 4;
			out.add(new Region(p, p + idxLen, layer + " indexBytes", null));
			p += idxLen;
		}
		out.add(new Region(p, p + 8, "visibilitySet", null));
		return out;
	}

	/** Describe one absolute blob offset: region label plus, for vertex data, the vertex
	 *  index, byte-within-vertex, and the VertexFormat attribute it falls on. */
	private static String describeOffset(final List<Region> layout, final int off) {
		for (Region r : layout) {
			if (off >= r.start && off < r.end) {
				if (r.fmt == null) {
					return r.label + " (+" + (off - r.start) + ")";
				}
				int stride = r.fmt.getVertexSize();
				int rel = off - r.start;
				int vtx = rel / stride;
				int inVtx = rel % stride;
				return r.label + " vtx#" + vtx + " byte" + inVtx + "/" + stride + " attr=" + attrName(r.fmt, inVtx);
			}
		}
		return "past-end";
	}

	/** Attribute name for a byte offset within one vertex, by element offset boundaries. */
	private static String attrName(final VertexFormat fmt, final int inVtx) {
		List<VertexFormatElement> els = fmt.getElements();
		String best = "?";
		int bestOff = -1;
		for (VertexFormatElement e : els) {
			if (e.offset() <= inVtx && e.offset() > bestOff) {
				bestOff = e.offset();
				best = e.name();
			}
		}
		return best;
	}

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private static String hexWindow(final byte[] b, final int center, final int radius) {
		int from = Math.max(0, center - radius);
		int to = Math.min(b.length, center + radius);
		StringBuilder sb = new StringBuilder();
		sb.append('[').append(from).append("..").append(to).append(") ");
		for (int i = from; i < to; ++i) {
			if (i == center) {
				sb.append('|'); // mark firstDiff
			}
			sb.append(HEX[(b[i] >> 4) & 0xF]).append(HEX[b[i] & 0xF]);
			if (((i - from) & 3) == 3) {
				sb.append(' ');
			}
		}
		return sb.toString();
	}

	/**
	 * Full mismatch report: layout summary, first-diff location (layer/region/attribute),
	 * diff clusters (gap > 8 bytes starts a new cluster, first 12 reported), a per-attribute
	 * histogram of differing bytes in vertex regions, and ±{@code hexRadius} hex windows of
	 * both blobs at the first difference.
	 */
	public static List<String> diagnoseMismatch(final byte[] inline, final byte[] worker) {
		List<String> lines = new ArrayList<>();
		List<Region> layout = parseLayout(inline);

		StringBuilder ls = new StringBuilder("layout:");
		for (Region r : layout) {
			if (r.fmt != null || r.label.contains("DrawState") || r.label.equals("visibilitySet")
					|| r.label.contains("indexBytes")) {
				ls.append(' ').append(r.label).append("=[").append(r.start).append("..").append(r.end).append(')');
			}
		}
		lines.add(ls.toString());

		int n = Math.min(inline.length, worker.length);
		int firstDiff = -1;
		int totalDiff = 0;
		// clusters
		List<int[]> clusters = new ArrayList<>();
		int clStart = -1;
		int lastDiff = -1000;
		// attribute histogram (name -> count), small linear map
		List<String> attrNames = new ArrayList<>();
		List<Integer> attrCounts = new ArrayList<>();

		for (int i = 0; i < n; ++i) {
			if (inline[i] == worker[i]) {
				continue;
			}
			++totalDiff;
			if (firstDiff < 0) {
				firstDiff = i;
			}
			if (i - lastDiff > 8) {
				if (clStart >= 0) {
					clusters.add(new int[] { clStart, lastDiff });
				}
				clStart = i;
			}
			lastDiff = i;
			// histogram (vertex regions only)
			for (Region r : layout) {
				if (r.fmt != null && i >= r.start && i < r.end) {
					String a = r.label.substring(0, r.label.indexOf(' ')) + "." // layer prefix
							+ attrName(r.fmt, (i - r.start) % r.fmt.getVertexSize());
					int idx = attrNames.indexOf(a);
					if (idx < 0) {
						attrNames.add(a);
						attrCounts.add(1);
					} else {
						attrCounts.set(idx, attrCounts.get(idx) + 1);
					}
					break;
				}
			}
		}
		if (clStart >= 0) {
			clusters.add(new int[] { clStart, lastDiff });
		}

		if (firstDiff < 0 && inline.length == worker.length) {
			lines.add("no byte differences (blobs identical)");
			return lines;
		}
		if (inline.length != worker.length) {
			lines.add("LENGTH differs: inline=" + inline.length + " worker=" + worker.length);
		}
		if (firstDiff >= 0) {
			lines.add("firstDiff@" + firstDiff + ": " + describeOffset(layout, firstDiff));
			lines.add("totalDiffBytes=" + totalDiff + " clusters=" + clusters.size());
			StringBuilder cs = new StringBuilder("clusters:");
			for (int c = 0; c < clusters.size() && c < 12; ++c) {
				int[] cl = clusters.get(c);
				cs.append(' ').append(cl[0]).append('-').append(cl[1]).append('(')
						.append(describeOffset(layout, cl[0])).append(')');
			}
			if (clusters.size() > 12) {
				cs.append(" ...+").append(clusters.size() - 12).append(" more");
			}
			lines.add(cs.toString());
			if (!attrNames.isEmpty()) {
				StringBuilder hs = new StringBuilder("attrHistogram:");
				for (int a = 0; a < attrNames.size(); ++a) {
					hs.append(' ').append(attrNames.get(a)).append('=').append(attrCounts.get(a));
				}
				lines.add(hs.toString());
			}
			lines.add("inline " + hexWindow(inline, firstDiff, 32));
			lines.add("worker " + hexWindow(worker, firstDiff, 32));
		}
		return lines;
	}
}
