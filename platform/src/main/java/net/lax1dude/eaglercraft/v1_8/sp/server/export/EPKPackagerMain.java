package net.lax1dude.eaglercraft.v1_8.sp.server.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 26.2 packager CLI around the copied upstream EPKCompiler (the "eaglercraft
 * packager"): packs an asset tree into an EPK v2 archive and self-verifies the
 * result with EPKDecompiler (CRC32 per entry is validated on read).
 *
 * Usage: EPKPackagerMain <inputDir> <output.epk> [archiveName]
 * Driven by the buildAssetsEPK gradle task; the browser target downloads the
 * result at boot in Phase 3 (desktop serves the same tree from disk).
 */
public class EPKPackagerMain {

	/*
	 * These sprites are reachable only from com.mojang.realmsclient. The web title
	 * screen has no Realms entry point, so TeaVM drops that entire code graph. Keep
	 * this list exact: broad directory or name filters are unsafe because atlases,
	 * resource packs and mods resolve otherwise ordinary Minecraft assets by name.
	 */
	private static final Set<String> WEB_UNREACHABLE_ASSETS = Set.of(
			"assets/minecraft/textures/gui/sprites/icon/new_realm.png",
			"assets/minecraft/textures/gui/sprites/realm_status/closed.png",
			"assets/minecraft/textures/gui/sprites/realm_status/expired.png",
			"assets/minecraft/textures/gui/sprites/realm_status/expires_soon.png",
			"assets/minecraft/textures/gui/sprites/realm_status/expires_soon.png.mcmeta",
			"assets/minecraft/textures/gui/sprites/realm_status/open.png");

	public static void main(String[] args) throws IOException {
		if(args.length < 2) {
			System.err.println("Usage: EPKPackagerMain <inputDir> <output.epk> [archiveName]");
			System.exit(1);
		}
		Path input = Path.of(args[0]);
		Path output = Path.of(args[1]);
		String name = args.length > 2 ? args[2] : "assets.epk";
		boolean includeWebUnreachable = args.length > 3 && "--include-web-unreachable".equals(args[3]);
		if(args.length > 3 && !includeWebUnreachable) {
			System.err.println("Unknown option: " + args[3]);
			System.exit(1);
		}

		List<Path> files = new ArrayList<>();
		List<String> excluded = new ArrayList<>();
		try(var stream = Files.walk(input)) {
			stream.filter(Files::isRegularFile).sorted().forEach(f -> {
				String rel = input.relativize(f).toString().replace('\\', '/');
				if(!includeWebUnreachable && WEB_UNREACHABLE_ASSETS.contains(rel)) {
					excluded.add(rel);
				} else {
					files.add(f);
				}
			});
		}
		if(!includeWebUnreachable && excluded.size() != WEB_UNREACHABLE_ASSETS.size()) {
			Set<String> missing = new HashSet<>(WEB_UNREACHABLE_ASSETS);
			missing.removeAll(excluded);
			throw new IOException("Expected web-unreachable assets are missing from source tree: " + missing);
		}

		// gzip=true: the asset tree is ~10 MB of highly-compressible JSON (data/
		// worldgen, recipes, loot, tags) — storing it uncompressed made the EPK
		// LARGER than the raw files (per-entry headers, no deflate). The decompiler
		// (used by the round-trip verify below AND the browser runtime's
		// "Decompressing: assets.epk" path) handles the 'G' gzip flag, so this is a
		// self-validating win. world=true/comment left exactly as the known-good
		// format so ONLY the compression changes.
		EPKCompiler compiler = new EPKCompiler(name, "eagler-26.2", "epk/resources", true, true, null);
		long totalBytes = 0;
		for(Path f : files) {
			String rel = input.relativize(f).toString().replace('\\', '/');
			byte[] data = Files.readAllBytes(f);
			compiler.append(rel, data);
			totalBytes += data.length;
		}
		byte[] epk = compiler.complete();
		Files.createDirectories(output.toAbsolutePath().getParent());
		Files.write(output, epk);
		System.out.println("packed " + files.size() + " files (" + (totalBytes / 1024) + " kB) -> "
				+ output + " (" + (epk.length / 1024) + " kB)");

		// round-trip verify (CRC32 checked by the decompiler per entry)
		EPKDecompiler dec = new EPKDecompiler(Files.readAllBytes(output));
		Set<String> expectedNames = new HashSet<>();
		for(Path f : files) {
			expectedNames.add(input.relativize(f).toString().replace('\\', '/'));
		}
		Set<String> archivedNames = new HashSet<>();
		EPKDecompiler.FileEntry e;
		while((e = dec.readFile()) != null) {
			if("FILE".equals(e.type)) {
				if(!archivedNames.add(e.name)) {
					throw new IOException("VERIFY FAILED: duplicate archive entry " + e.name);
				}
			}
		}
		if(!archivedNames.equals(expectedNames)) {
			Set<String> missing = new HashSet<>(expectedNames);
			missing.removeAll(archivedNames);
			Set<String> unexpected = new HashSet<>(archivedNames);
			unexpected.removeAll(expectedNames);
			throw new IOException("VERIFY FAILED: missing=" + missing + ", unexpected=" + unexpected);
		}
		System.out.println("verify OK: " + archivedNames.size() + " entries round-tripped with exact membership");
		if(!excluded.isEmpty()) {
			long excludedBytes = 0;
			for(String rel : excluded) {
				excludedBytes += Files.size(input.resolve(rel));
			}
			System.out.println("excluded " + excluded.size() + " web-unreachable assets (" + excludedBytes + " bytes): " + excluded);
		}
	}

}
