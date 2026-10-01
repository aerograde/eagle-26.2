package net.lax1dude.eaglercraft.v1_8.mods.resources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.teavm.jso.JSBody;

/**
 * Test-only native observation seam for the model-resource capability.
 *
 * The probe never asks the renderer to manufacture a result. It records the
 * actual model map and stitched sprite map after their respective apply
 * barriers, then publishes one row on a normal client tick. A new pack or
 * reload generation invalidates an unfinished row, so delayed old callbacks
 * cannot satisfy a newer request.
 */
public final class EaglerModResourceModelProbe {
    private static final Object LOCK = new Object();
    private static final int MAX_ROWS = 64;
    private static List<EaglerModResourceSnapshot.ModelRequest> requests = List.of();
    private static int packGeneration;
    private static int lastReloadGeneration;
    private static Pending pending;
    private static final Map<String, AcceptedPublication> accepted = new HashMap<>();
    private static final Map<String, Integer> shownGuiGenerations = new HashMap<>();
    private static long drawStateCounter;
    private static String lastDrawModuleId;
    private static int lastDrawReloadGeneration;

    /** Gate-only receipt seam for the real GuiGraphicsExtractor.item route. */
    @JSBody(params = {"moduleId", "reloadGeneration", "closureSHA256", "drawStateCounter", "itemModelId", "x", "y", "width", "height", "frameNonce"}, script =
            "var g=globalThis.__eaglerNativeResourceModelDraw||(globalThis.__eaglerNativeResourceModelDraw={events:[]});"
          + "var row={version:1,moduleId:moduleId,reloadGeneration:reloadGeneration,closureSHA256:closureSHA256,"
          + "drawStateCounter:Number(drawStateCounter),itemModelId:itemModelId,drawBounds:{x:Number(x),y:Number(y),width:Number(width),height:Number(height)},frameNonce:frameNonce,nativePath:'graphics.item',submittedAfterGraphicsItem:true,timestampMs:"
          + "(typeof performance!=='undefined'&&typeof performance.now==='function'?performance.now():0)};"
          + "g.last=row;g.events.push(row);if(g.events.length>64)g.events.shift();")
    private static native void publishDrawTrace(String moduleId, int reloadGeneration,
            String closureSHA256, String drawStateCounter, String itemModelId,
            String x, String y, String width, String height, String frameNonce);

    private EaglerModResourceModelProbe() { }

    /**
     * Prepares the real vanilla GUI item state used by the lightweight probe
     * screen. This deliberately stops before draw submission; no
     * ad-hoc GPU texture or synthetic pixel is involved.
     */
    public static TrackingItemStackRenderState prepareStickGuiState(net.minecraft.client.Minecraft minecraft,
            Identifier requestedItemModelId) {
        if (minecraft == null || requestedItemModelId == null) return null;
        if (!Items.STICK.builtInRegistryHolder().areComponentsBound()) return null;
        ItemStack stack = new ItemStack(Items.STICK);
        stack.set(DataComponents.ITEM_MODEL, requestedItemModelId);
        TrackingItemStackRenderState state = new TrackingItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI,
                minecraft.level, minecraft.player, 0);
        return state.getModelIdentity() instanceof java.util.List<?> values && !values.isEmpty() ? state : null;
    }

    /** Creates the generic GUI draw-state path; browser pixels remain a separate gate. */
    public static Screen prepareStickGuiProbeScreen(net.minecraft.client.Minecraft minecraft,
            Identifier requestedItemModelId, String moduleId, int reloadGeneration) {
        TrackingItemStackRenderState state = prepareStickGuiState(minecraft, requestedItemModelId);
        if (state == null) return null;
        return new ProbeScreen(minecraft, requestedItemModelId, moduleId, reloadGeneration);
    }

    /** GUI preparation is available only after the bridge accepts this proof. */
    public static Screen prepareAcceptedStickGuiProbeScreen(net.minecraft.client.Minecraft minecraft,
            String moduleId, int reloadGeneration) {
        if (!Items.STICK.builtInRegistryHolder().areComponentsBound()) return null;
        synchronized (LOCK) {
            AcceptedPublication publication = accepted.get(moduleId);
            if (publication == null || publication.reloadGeneration != reloadGeneration
                    || !publication.itemModelNonMissing) return null;
            return prepareStickGuiProbeScreen(minecraft, publication.itemModelId, moduleId, reloadGeneration);
        }
    }

    /** Shows the bounded probe screen only after accepted publication. */
    public static Screen showAcceptedStickGuiProbeScreen(net.minecraft.client.Minecraft minecraft,
            String moduleId, int reloadGeneration) {
        if (minecraft == null || minecraft.gui.screen() != null) return null;
        synchronized (LOCK) {
            AcceptedPublication publication = accepted.get(moduleId);
            if (publication == null || publication.reloadGeneration != reloadGeneration
                    || !publication.itemModelNonMissing
                    || Integer.valueOf(reloadGeneration).equals(shownGuiGenerations.get(moduleId))) return null;
        }
        Screen screen = prepareAcceptedStickGuiProbeScreen(minecraft, moduleId, reloadGeneration);
        if (screen == null || minecraft.gui.screen() != null) return null;
        minecraft.gui.setScreen(screen);
        if (minecraft.gui.screen() != screen) return null;
        synchronized (LOCK) {
            AcceptedPublication publication = accepted.get(moduleId);
            if (publication == null || publication.reloadGeneration != reloadGeneration
                    || !publication.itemModelNonMissing) return null;
            shownGuiGenerations.put(moduleId, reloadGeneration);
        }
        return screen;
    }

    private static void pumpAcceptedGuiProbe(net.minecraft.client.Minecraft minecraft) {
        if (minecraft == null) return;
        List<AcceptedPublication> candidates;
        synchronized (LOCK) {
            candidates = new ArrayList<>(accepted.values());
        }
        for (AcceptedPublication publication : candidates) {
            showAcceptedStickGuiProbeScreen(minecraft, publication.moduleId,
                    publication.reloadGeneration);
        }
    }

    static void install(List<EaglerModResourceSnapshot.ModelRequest> next, int generation) {
        synchronized (LOCK) {
            requests = next == null ? List.of() : List.copyOf(next);
            packGeneration = generation;
            lastReloadGeneration = 0;
            pending = null;
            accepted.clear();
            shownGuiGenerations.clear();
            drawStateCounter = 0L;
            lastDrawModuleId = null;
            lastDrawReloadGeneration = 0;
        }
    }

    static void beginReload(int nextPackGeneration, int reloadGeneration, Object reloadToken,
            List<EaglerModResourceMountRequests.MountRequest> mounts, ResourceManager resourceManager) {
        synchronized (LOCK) {
            if (nextPackGeneration != packGeneration || nextPackGeneration < 1
                    || reloadGeneration < 1 || reloadGeneration <= lastReloadGeneration
                    || reloadToken == null || requests.size() < 1 || requests.size() > MAX_ROWS) return;
            lastReloadGeneration = reloadGeneration;
            boolean mounted = true;
            for (EaglerModResourceSnapshot.ModelRequest request : requests) {
                EaglerModResourceMountRequests.MountRequest mount = findMount(mounts, request.moduleId());
                mounted &= mount != null && EaglerModResourceMountRequests.isMounted(mount, resourceManager);
            }
            pending = new Pending(nextPackGeneration, reloadGeneration, reloadToken, mounted, requests);
        }
    }

    public static int reloadGenerationFor(Object reloadToken) {
        synchronized (LOCK) {
            return pending != null && pending.reloadToken == reloadToken ? pending.reloadGeneration : -1;
        }
    }

    /**
     * Begins a receipt-bound stage for the real reload token. A missing
     * pending probe means this is an ordinary vanilla reload and telemetry is
     * intentionally disabled for that call; a configured bridge rejection is
     * surfaced by the bridge and aborts the reload.
     */
    public static String beginPerformanceStage(Object reloadToken, String queue, String scope) {
        int generation;
        synchronized (LOCK) {
            if (pending == null || pending.reloadToken != reloadToken || pending.reloadGeneration < 1) return null;
            generation = pending.reloadGeneration;
        }
        return EaglerModResourceBridge.performanceBeginBatch(generation, queue, scope);
    }

    public static void finishPerformanceStage(String batch, boolean success) {
        EaglerModResourceBridge.performanceFinishBatch(batch, success);
    }

    public static void closePerformanceStage(String batch, boolean success) {
        EaglerModResourceBridge.performanceCloseBatch(batch, success);
    }

    /**
     * Waits for the receipt-bound generation-N decode gate. The production
     * path receives an already-complete future because the telemetry ledger is
     * disabled unless explicitly configured test-only.
     */
    public static CompletableFuture<Void> awaitPerformanceDecodeFinish(String batch) {
        return EaglerModResourceBridge.performanceAwaitDecodeFinish(batch);
    }

    /** Rejects a failed reload and invalidates callbacks for its token. */
    public static void rejectReload(Object reloadToken, int reloadGeneration) {
        synchronized (LOCK) {
            if (pending != null && pending.reloadToken == reloadToken
                    && pending.reloadGeneration == reloadGeneration) pending = null;
            accepted.entrySet().removeIf(entry -> {
                boolean remove = entry.getValue().reloadGeneration == reloadGeneration;
                if (remove) shownGuiGenerations.remove(entry.getKey());
                return remove;
            });
        }
    }

    /** Rejects a bridge publication and clears active proof for its module. */
    public static void rejectPublication(String moduleId, int reloadGeneration) {
        synchronized (LOCK) {
            AcceptedPublication publication = accepted.get(moduleId);
            if (publication != null && publication.reloadGeneration == reloadGeneration) {
                accepted.remove(moduleId);
                shownGuiGenerations.remove(moduleId);
            }
        }
    }

    /** Reconciles Java-side accepted proof with the active owner/generation registry. */
    public static void reconcileLifecycle() {
        synchronized (LOCK) {
            accepted.entrySet().removeIf(entry -> {
                AcceptedPublication publication = entry.getValue();
                if (EaglerModResourceBridge.modelPublicationAllowed(publication.moduleId,
                        publication.reloadGeneration)) return false;
                EaglerModResourceBridge.clearResourceModel(publication.moduleId, publication.reloadGeneration);
                shownGuiGenerations.remove(entry.getKey());
                return true;
            });
        }
    }

    static long drawStateCounterForTests() { synchronized (LOCK) { return drawStateCounter; } }
    static String lastDrawModuleIdForTests() { synchronized (LOCK) { return lastDrawModuleId; } }
    static int lastDrawReloadGenerationForTests() { synchronized (LOCK) { return lastDrawReloadGeneration; } }
    static boolean recordAcceptedDrawForTests(String moduleId, int reloadGeneration) {
        synchronized (LOCK) { return recordAcceptedDraw(moduleId, reloadGeneration) != null; }
    }
    static void acceptForTests(String moduleId, int reloadGeneration, Identifier itemModelId,
            boolean itemModelNonMissing) {
        synchronized (LOCK) {
            accepted.put(moduleId, new AcceptedPublication(moduleId, reloadGeneration, itemModelId,
                    itemModelNonMissing, "test-fixture"));
        }
    }

    public static void observeModels(Object reloadToken, int reloadGeneration,
            Set<Identifier> resolvedModelIds, Map<Identifier, ItemModel> itemModels,
            ItemModel missingItemModel, BlockStateModelSet blockStateModels) {
        synchronized (LOCK) {
            if (pending == null || pending.reloadToken != reloadToken
                    || pending.reloadGeneration != reloadGeneration || pending.modelsObserved) return;
            pending.modelsObserved = true;
            pending.resolvedModelIds = resolvedModelIds == null ? Set.of() : Set.copyOf(resolvedModelIds);
            pending.itemModels = itemModels == null ? Map.of() : Map.copyOf(itemModels);
            pending.missingItemModel = missingItemModel;
            pending.blockStateModels = blockStateModels;
            pending.modelGeneration = pending.reloadGeneration;
        }
    }

    public static void observeSprites(Object reloadToken, int reloadGeneration,
            Map<SpriteId, TextureAtlasSprite> sprites,
            Map<Identifier, Identifier> atlasDefinitionToTexture) {
        synchronized (LOCK) {
            if (pending == null || pending.reloadToken != reloadToken
                    || pending.reloadGeneration != reloadGeneration || pending.atlasObserved) return;
            pending.atlasObserved = true;
            pending.sprites = sprites == null ? Map.of() : Map.copyOf(sprites);
            pending.atlasDefinitionToTexture = atlasDefinitionToTexture == null
                    ? Map.of() : Map.copyOf(atlasDefinitionToTexture);
            pending.atlasGeneration = pending.reloadGeneration;
        }
    }

    /** Clears in-flight and installed model probes on disable, failed reload, or uninstall. */
    public static void clear() {
        synchronized (LOCK) {
            requests = List.of();
            packGeneration = 0;
            lastReloadGeneration = 0;
            pending = null;
            accepted.clear();
            shownGuiGenerations.clear();
            drawStateCounter = 0L;
            lastDrawModuleId = null;
            lastDrawReloadGeneration = 0;
        }
        EaglerModDeclaredBlockStates.clear();
    }

    /** Explicit lifecycle names keep disable/uninstall call sites fail-closed and reviewable. */
    public static void disable() { clear(); }
    public static void teardown() { clear(); }
    public static void uninstall() { clear(); }

    /** Called from the existing client tick only after both native apply hooks have run. */
    public static void drain() {
        drain(null);
    }

    /** Called from the client tick; GUI preparation is gated by accepted publication. */
    public static void drain(net.minecraft.client.Minecraft minecraft) {
        List<Publication> ready;
        int reloadGeneration;
        synchronized (LOCK) {
            if (pending == null || !pending.modelsObserved || !pending.atlasObserved
                    || pending.modelGeneration != pending.reloadGeneration
                    || pending.atlasGeneration != pending.reloadGeneration) {
                ready = List.of();
                reloadGeneration = 0;
            } else {
                reloadGeneration = pending.reloadGeneration;
                ready = pending.publications();
                pending = null;
            }
        }
        if (!ready.isEmpty()) {
            String telemetryBatch = EaglerModResourceBridge.performanceBeginBatch(reloadGeneration, "publication", "model-probe");
            boolean completed = false;
            try {
                for (Publication publication : ready) {
                    boolean published = EaglerModResourceBridge.publishResourceModel(publication.moduleId, publication.reloadGeneration,
                            publication.json);
                    EaglerModResourceBridge.performanceRecordPublication(telemetryBatch, publication.moduleId, published);
                    if (published) {
                        synchronized (LOCK) {
                            accepted.put(publication.moduleId, new AcceptedPublication(publication.moduleId,
                                    publication.reloadGeneration, publication.itemModelId, publication.itemModelNonMissing,
                                    publication.closureSHA256));
                            shownGuiGenerations.remove(publication.moduleId);
                        }
                    } else {
                        rejectPublication(publication.moduleId, publication.reloadGeneration);
                    }
                }
                completed = true;
            } finally {
                EaglerModResourceBridge.performanceFinishBatch(telemetryBatch, completed);
            }
        }
        pumpAcceptedGuiProbe(minecraft);
    }

    private static EaglerModResourceMountRequests.MountRequest findMount(
            List<EaglerModResourceMountRequests.MountRequest> mounts, String moduleId) {
        if (mounts == null) return null;
        for (EaglerModResourceMountRequests.MountRequest mount : mounts) {
            if (mount.moduleId().equals(moduleId)) return mount;
        }
        return null;
    }

    private static final class Pending {
        final int packGeneration;
        final int reloadGeneration;
        final Object reloadToken;
        final boolean mounted;
        final List<EaglerModResourceSnapshot.ModelRequest> requests;
        boolean modelsObserved;
        boolean atlasObserved;
        int modelGeneration;
        int atlasGeneration;
        Set<Identifier> resolvedModelIds = Set.of();
        Map<Identifier, ItemModel> itemModels = Map.of();
        ItemModel missingItemModel;
        BlockStateModelSet blockStateModels;
        Map<SpriteId, TextureAtlasSprite> sprites = Map.of();
        Map<Identifier, Identifier> atlasDefinitionToTexture = Map.of();

        Pending(int packGeneration, int reloadGeneration, Object reloadToken, boolean mounted,
                List<EaglerModResourceSnapshot.ModelRequest> requests) {
            this.packGeneration = packGeneration;
            this.reloadGeneration = reloadGeneration;
            this.reloadToken = reloadToken;
            this.mounted = mounted;
            this.requests = requests;
        }

        List<Publication> publications() {
            List<Publication> result = new ArrayList<>(requests.size());
            for (EaglerModResourceSnapshot.ModelRequest request : requests) {
                List<String> missingModels = missingModelIds(request);
                List<String> missingTextures = missingTextureIds(request);
                List<String> missingAtlases = missingAtlasIds(request);
                boolean itemPresent = itemModels.containsKey(request.itemModelId())
                        && itemModels.get(request.itemModelId()) != null
                        && itemModels.get(request.itemModelId()) != missingItemModel;
                boolean hasItemModel = itemModels.containsKey(request.itemModelId());
                boolean itemModelNonMissing = itemPresent;
                BlockstateObservation blockstate = blockstate(request.blockstateId());
                boolean blockstatePresent = blockstate.present;
                boolean ok = mounted && itemPresent && blockstatePresent
                        && missingModels.isEmpty() && missingTextures.isEmpty() && missingAtlases.isEmpty();
                StringBuilder json = new StringBuilder(512).append("{\"version\":1,\"ok\":")
                        .append(ok).append(",\"packGeneration\":").append(packGeneration)
                        .append(",\"reloadGeneration\":").append(reloadGeneration)
                        .append(",\"modelGeneration\":").append(modelGeneration)
                        .append(",\"atlasGeneration\":").append(atlasGeneration)
                        .append(",\"moduleId\":");
                appendJsonString(json, request.moduleId()).append(",\"itemModelId\":");
                appendJsonString(json, request.itemModelId().toString()).append(",\"blockstateId\":");
                appendJsonString(json, request.blockstateId().toString()).append(",\"closureSHA256\":");
                appendJsonString(json, request.closureSHA256()).append(",\"itemModelPresent\":").append(itemPresent)
                        .append(",\"hasItemModel\":").append(hasItemModel)
                        .append(",\"itemModelNonMissing\":").append(itemModelNonMissing)
                        .append(",\"itemModelProof\":");
                appendJsonString(json, itemModelNonMissing ? "runtime" : "MissingItemModel")
                        .append(",\"ownerModuleId\":");
                appendJsonString(json, request.moduleId()).append(",\"provenance\":\"native-model-manager-atlas-manager\"")
                        .append(",\"blockstatePresent\":").append(blockstatePresent)
                        .append(",\"blockstateRuntimeProof\":").append(blockstate.runtimeProof)
                        .append(",\"blockstateExpectedCount\":").append(blockstate.expectedCount)
                        .append(",\"blockstateNonMissingCount\":").append(blockstate.nonMissingCount)
                        .append(",\"blockstateProof\":");
                appendJsonString(json, blockstate.proof)
                        .append(",\"missingModelIds\":");
                appendStrings(json, missingModels).append(",\"missingTextureIds\":");
                appendStrings(json, missingTextures).append(",\"missingAtlasIds\":");
                appendStrings(json, missingAtlases).append('}');
                result.add(new Publication(request.moduleId(), reloadGeneration, request.itemModelId(),
                        itemModelNonMissing, request.closureSHA256(), json.toString()));
            }
            return result;
        }

        private List<String> missingModelIds(EaglerModResourceSnapshot.ModelRequest request) {
            List<String> result = new ArrayList<>();
            for (Identifier id : request.modelIds()) if (!resolvedModelIds.contains(id)) result.add(id.toString());
            return result;
        }

        private List<String> missingTextureIds(EaglerModResourceSnapshot.ModelRequest request) {
            List<String> result = new ArrayList<>();
            for (Identifier texture : request.textureIds()) {
                Identifier atlasDefinition = request.textureToAtlas().get(texture);
                Identifier atlasTexture = atlasDefinition == null ? null : atlasDefinitionToTexture.get(atlasDefinition);
                TextureAtlasSprite sprite = atlasTexture == null ? null : sprites.get(new SpriteId(atlasTexture, texture));
                boolean present = sprite != null && sprite.contents() != null
                        && texture.equals(sprite.contents().name())
                        && !MissingTextureAtlasSprite.getLocation().equals(sprite.contents().name());
                if (!present) result.add(texture.toString());
            }
            return result;
        }

        private List<String> missingAtlasIds(EaglerModResourceSnapshot.ModelRequest request) {
            List<String> result = new ArrayList<>();
            for (Identifier atlas : request.atlasIds()) {
                boolean present = atlasDefinitionToTexture.containsKey(atlas);
                if (!present) result.add(atlas.toString());
            }
            return result;
        }

        private BlockstateObservation blockstate(Identifier id) {
            if (blockStateModels == null) return new BlockstateObservation(false, false, "runtime-unavailable");
            EaglerModDeclaredBlockStates.Definition virtual = EaglerModDeclaredBlockStates.forReload(this.reloadToken).definition(id);
            if (virtual != null) {
                int expected = virtual.expectedCount();
                int nonMissing = 0;
                for (BlockState state : virtual.stateDefinition().getPossibleStates()) {
                    if (blockStateModels.get(state) != blockStateModels.missingModel()) nonMissing++;
                }
                return new BlockstateObservation(nonMissing == expected, true, "runtime", expected, nonMissing);
            }
            java.util.Optional<Block> registered = BuiltInRegistries.BLOCK.getOptional(id);
            if (registered.isEmpty()) return new BlockstateObservation(false, false, "unregistered-block", 0, 0);
            Block block = registered.get();
            int expected = block.getStateDefinition().getPossibleStates().size();
            int nonMissing = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (blockStateModels.get(state) != blockStateModels.missingModel()) {
                    nonMissing++;
                }
            }
            return new BlockstateObservation(nonMissing == expected, true,
                    nonMissing == expected ? "runtime" : "missing-baked-state", expected, nonMissing);
        }
    }

    private record BlockstateObservation(boolean present, boolean runtimeProof, String proof,
            int expectedCount, int nonMissingCount) {
        BlockstateObservation(boolean present, boolean runtimeProof, String proof) {
            this(present, runtimeProof, proof, 0, 0);
        }
    }

    /** Generic GUI state route used by the native probe; no synthetic texture is uploaded. */
    private static final class ProbeScreen extends Screen {
        private final ItemStack stack;
        private final String moduleId;
        private final int reloadGeneration;

        ProbeScreen(net.minecraft.client.Minecraft minecraft, Identifier requestedItemModelId,
                String moduleId, int reloadGeneration) {
            super(minecraft, minecraft.font, Component.literal("Native resource model probe"));
            this.stack = new ItemStack(Items.STICK);
            this.stack.set(DataComponents.ITEM_MODEL, requestedItemModelId);
            this.moduleId = moduleId;
            this.reloadGeneration = reloadGeneration;
        }

        @Override
        public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                int mouseX, int mouseY, float partialTick) {
            boolean authorized;
            synchronized (LOCK) {
                AcceptedPublication publication = accepted.get(this.moduleId);
                authorized = publication != null && publication.reloadGeneration == this.reloadGeneration
                        && publication.itemModelNonMissing
                        && this.stack.get(DataComponents.ITEM_MODEL).equals(publication.itemModelId);
            }
            if (!EaglerModResourceBridge.performanceRecordDrawOutcome(this.moduleId, this.reloadGeneration, authorized)) return;
            if (!authorized) return;
            DrawSubmission submission = recordAcceptedDraw(this.moduleId, this.reloadGeneration);
            if (submission != null) {
                int x = (this.width - submission.width) / 2;
                int y = (this.height - submission.height) / 2;
                graphics.item(this.stack, x, y);
                publishDrawTrace(submission.moduleId, submission.reloadGeneration, submission.closureSHA256,
                        Long.toString(submission.drawStateCounter), submission.itemModelId,
                        Integer.toString(x), Integer.toString(y), Integer.toString(submission.width),
                        Integer.toString(submission.height), submission.frameNonce);
            }
        }
    }

    private static DrawSubmission recordAcceptedDraw(String moduleId, int reloadGeneration) {
        synchronized (LOCK) {
            AcceptedPublication publication = accepted.get(moduleId);
            if (publication == null || publication.reloadGeneration != reloadGeneration
                    || !publication.itemModelNonMissing) return null;
            drawStateCounter++;
            lastDrawModuleId = moduleId;
            lastDrawReloadGeneration = reloadGeneration;
            return new DrawSubmission(moduleId, reloadGeneration, publication.closureSHA256,
                    publication.itemModelId.toString(), drawStateCounter,
                    moduleId + ":" + reloadGeneration + ":" + drawStateCounter, 16, 16);
        }
    }

    private record DrawSubmission(String moduleId, int reloadGeneration, String closureSHA256,
            String itemModelId, long drawStateCounter, String frameNonce, int width, int height) { }

    private record Publication(String moduleId, int reloadGeneration, Identifier itemModelId,
            boolean itemModelNonMissing, String closureSHA256, String json) { }

    private record AcceptedPublication(String moduleId, int reloadGeneration, Identifier itemModelId,
            boolean itemModelNonMissing, String closureSHA256) { }

    private static StringBuilder appendJsonString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') builder.append('\\');
            builder.append(c);
        }
        return builder.append('"');
    }

    private static StringBuilder appendStrings(StringBuilder builder, List<String> values) {
        builder.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            appendJsonString(builder, values.get(i));
        }
        return builder.append(']');
    }
}
