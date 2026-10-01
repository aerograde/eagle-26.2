package net.lax1dude.eaglercraft.v1_8.teavmcompat.java.nio.channels;

/**
 * java.nio.channels.FileChannel$MapMode — the memory-map mode constants. Reached
 * from mmap paths (RegionFile etc.) that are compile-linked but never mmap in
 * the browser (TFileChannel.map throws).
 */
public class TFileChannel$MapMode {

	public static final TFileChannel$MapMode READ_ONLY = new TFileChannel$MapMode("READ_ONLY");
	public static final TFileChannel$MapMode READ_WRITE = new TFileChannel$MapMode("READ_WRITE");
	public static final TFileChannel$MapMode PRIVATE = new TFileChannel$MapMode("PRIVATE");

	private final String name;

	private TFileChannel$MapMode(String name) {
		this.name = name;
	}

	@Override
	public String toString() {
		return name;
	}
}
