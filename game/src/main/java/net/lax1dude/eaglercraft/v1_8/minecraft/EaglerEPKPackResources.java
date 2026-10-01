package net.lax1dude.eaglercraft.v1_8.minecraft;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceMetadata;

/**
 * The built-in "vanilla" pack served from the in-RAM EPK
 * asset map instead of classpath paths (TeaVM bundles no classpath resources, so
 * the .mcassetsroot probe in VanillaPackResourcesBuilder finds nothing on web).
 *
 * The map is the PlatformAssets store (assets.epk decompressed at boot): keys are
 * paths relative to game/src/main/resources with NO leading slash, e.g.
 * "assets/minecraft/textures/gui/title/minecraft.png", "pack.png", "version.json".
 * Subclasses VanillaPackResources (empty path lists) so ClientPackSource /
 * BuiltInPackSource keep their exact types; getMetadataSection is inherited (no
 * pack.mcmeta in the tree -> built-in metadata fallback, same as desktop).
 */
public class EaglerEPKPackResources extends VanillaPackResources {

	private final Map<String, byte[]> assets;

	/**
	 * Retain the pack's built-in namespaces and expose any additional namespaces
	 * present below the selected EPK root (assets/ or data/).
	 */
	public static Set<String> discoverNamespaces(final PackType type, final Set<String> builtInNamespaces,
			final Map<String, byte[]> assets) {
		Set<String> namespaces = new HashSet<>(builtInNamespaces);
		String root = type.getDirectory() + "/";
		for (String key : assets.keySet()) {
			if (key.startsWith(root)) {
				int namespaceEnd = key.indexOf('/', root.length());
				if (namespaceEnd > root.length()) {
					String namespace = key.substring(root.length(), namespaceEnd);
					if (Identifier.tryBuild(namespace, "namespace_probe") != null) {
						namespaces.add(namespace);
					}
				}
			}
		}
		return Set.copyOf(namespaces);
	}

	public EaglerEPKPackResources(final PackLocationInfo location, final ResourceMetadata metadata, final Set<String> namespaces,
			final Map<String, byte[]> assets) {
		super(location, metadata, namespaces, List.of(), Map.of(PackType.CLIENT_RESOURCES, List.of(), PackType.SERVER_DATA, List.of()));
		this.assets = assets;
	}

	private IoSupplier<InputStream> supplierFor(final String key) {
		final byte[] data = this.assets.get(key);
		if (data == null) {
			return null;
		}
		return () -> new ByteArrayInputStream(data);
	}

	@Override
	public IoSupplier<InputStream> getRootResource(final String... path) {
		return this.supplierFor(String.join("/", path));
	}

	@Override
	public IoSupplier<InputStream> getResource(final PackType type, final Identifier location) {
		return this.supplierFor(type.getDirectory() + "/" + location.getNamespace() + "/" + location.getPath());
	}

	@Override
	public void listResources(final PackType type, final String namespace, final String directory, final ResourceOutput output) {
		String nsPrefix = type.getDirectory() + "/" + namespace + "/";
		String prefix = directory.isEmpty() ? nsPrefix : nsPrefix + directory + "/";
		for (Map.Entry<String, byte[]> entry : this.assets.entrySet()) {
			String key = entry.getKey();
			if (key.startsWith(prefix)) {
				String path = key.substring(nsPrefix.length());
				Identifier id = Identifier.tryBuild(namespace, path);
				if (id != null) {
					final byte[] data = entry.getValue();
					output.accept(id, () -> new ByteArrayInputStream(data));
				}
			}
		}
	}
}
