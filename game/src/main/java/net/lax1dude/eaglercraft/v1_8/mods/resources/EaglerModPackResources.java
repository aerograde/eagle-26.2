package net.lax1dude.eaglercraft.v1_8.mods.resources;

import java.io.InputStream;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.AbstractPackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jspecify.annotations.Nullable;

/** A sealed client resource pack backed by the validated injected-package registry. */
final class EaglerModPackResources extends AbstractPackResources {
    private final EaglerModResourceSnapshot snapshot;

    EaglerModPackResources(PackLocationInfo location, EaglerModResourceSnapshot snapshot) {
        super(location);
        this.snapshot = snapshot;
    }

    @Override
    public @Nullable IoSupplier<InputStream> getRootResource(String... path) {
        if (path.length == 0) return null;
        EaglerModResourceSnapshot.ResourceRef ref = this.snapshot.rootResources.get(String.join("/", path));
        return ref == null ? null : () -> ref.open(this.snapshot.packHandle);
    }

    @Override
    public @Nullable IoSupplier<InputStream> getResource(PackType type, Identifier location) {
        if (type != PackType.CLIENT_RESOURCES) return null;
        EaglerModResourceSnapshot.ResourceRef ref = this.snapshot.resources.get(location);
        return ref == null ? null : () -> ref.open(this.snapshot.packHandle);
    }

    @Override
    public void listResources(PackType type, String namespace, String directory, PackResources.ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !this.snapshot.namespaces.contains(namespace)) return;
        String prefix = directory.isEmpty() ? "" : directory + "/";
        this.snapshot.resources.forEach((id, ref) -> {
            if (id.getNamespace().equals(namespace)
                    && (directory.isEmpty() || id.getPath().equals(directory) || id.getPath().startsWith(prefix))) {
                output.accept(id, () -> ref.open(this.snapshot.packHandle));
            }
        });
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? this.snapshot.namespaces : Set.of();
    }

    @Override
    public void close() {
        // The sealed registry and lazy byte caches live for the injected client's lifetime.
    }
}
