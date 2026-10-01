package net.lax1dude.eaglercraft.v1_8.mods.resources;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;

/** Repository source captured synchronously before Minecraft constructs PackRepository. */
public final class EaglerModResourcePackSource implements RepositorySource {
    // Sorts before "vanilla". Required non-fixed BOTTOM insertion therefore yields
    // vanilla -> this pack -> ordinary selected user/server packs in PackRepository.
    public static final String PACK_ID = "eagler_mod_resources";
    private static final PackSelectionConfig SELECTION = new PackSelectionConfig(true, Pack.Position.BOTTOM, false);
    private final EaglerModResourceSnapshot snapshot;

    private EaglerModResourcePackSource(EaglerModResourceSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static EaglerModResourcePackSource capture() {
        EaglerModResourceSnapshot snapshot = EaglerModResourceSnapshot.capture();
        EaglerModResourceModelProbe.install(snapshot.modelRequests, snapshot.generation);
        try {
            EaglerModDeclaredBlockStates.install(snapshot.generation, snapshot.modelRequests);
        } catch (RuntimeException invalidVirtualDefinition) {
            EaglerModDeclaredBlockStates.clear();
            snapshot.publishFailure("Validated mod resource snapshot has an invalid declared virtual blockstate");
            EaglerModResourceModelProbe.disable();
            throw invalidVirtualDefinition;
        }
        if (snapshot.isRejected()) {
            EaglerModResourceModelProbe.disable();
            EaglerModDeclaredBlockStates.clear();
            snapshot.publishFailure(snapshot.failureReason);
        }
        return new EaglerModResourcePackSource(snapshot);
    }

    EaglerModResourceSnapshot snapshot() {
        return this.snapshot;
    }

    @Override
    public void loadPacks(Consumer<Pack> result) {
        if (this.snapshot.isEmpty()) return;
        PackLocationInfo location = new PackLocationInfo(PACK_ID, Component.literal("Eagler mod resources"), PackSource.DEFAULT, Optional.empty());
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo ignored) {
                return new EaglerModPackResources(location, snapshot);
            }

            @Override
            public PackResources openFull(PackLocationInfo ignored, Pack.Metadata metadata) {
                return new EaglerModPackResources(location, snapshot);
            }
        };
        Pack pack = Pack.readMetaAndCreate(location, supplier, PackType.CLIENT_RESOURCES, SELECTION);
        if (pack != null) {
            result.accept(pack);
        } else {
            this.snapshot.publishFailure("Validated mod resource pack metadata could not be opened by Pack.readMetaAndCreate");
        }
    }
}
