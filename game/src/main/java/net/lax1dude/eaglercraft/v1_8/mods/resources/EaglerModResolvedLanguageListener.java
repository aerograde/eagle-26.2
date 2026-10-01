package net.lax1dude.eaglercraft.v1_8.mods.resources;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.locale.Language;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;

/** Runs immediately after LanguageManager and publishes one atomic resolved snapshot per module. */
public final class EaglerModResolvedLanguageListener implements PreparableReloadListener {
    private static final PreparableReloadListener.StateKey<ReloadContext> RELOAD_CONTEXT = new PreparableReloadListener.StateKey<>();
    private final LanguageManager languageManager;
    private final EaglerModResourceSnapshot snapshot;
    private int reloadGeneration;

    private record ReloadContext(ResourceManager resourceManager, int generation, boolean active) { }

    public EaglerModResolvedLanguageListener(LanguageManager languageManager, EaglerModResourcePackSource source) {
        this.languageManager = languageManager;
        this.snapshot = source.snapshot();
    }

    @Override
    public void prepareSharedState(PreparableReloadListener.SharedState currentReload) {
        ResourceManager resourceManager = currentReload.resourceManager();
        if (this.snapshot.isEmpty()) {
            currentReload.set(RELOAD_CONTEXT, new ReloadContext(resourceManager, 0, false));
            return;
        }
        int generation = ++this.reloadGeneration;
        try {
            // Capture immutable model state before any listener preparation starts.
            EaglerModDeclaredBlockStates.beginReload(this.snapshot.generation, generation, resourceManager);
            EaglerModResourceModelProbe.beginReload(this.snapshot.generation, generation, resourceManager,
                    this.snapshot.mountRequests, resourceManager);
            if (!this.snapshot.modelRequests.isEmpty()
                    && (EaglerModDeclaredBlockStates.forReload(resourceManager).packGeneration() != this.snapshot.generation
                    || EaglerModResourceModelProbe.reloadGenerationFor(resourceManager) != generation)) {
                throw new IllegalStateException("Mod model reload state was not captured for the active resource manager");
            }
            currentReload.set(RELOAD_CONTEXT, new ReloadContext(resourceManager, generation, true));
        } catch (RuntimeException failedReload) {
            EaglerModResourceModelProbe.rejectReload(resourceManager, generation);
            throw failedReload;
        }
    }

    @Override
    public CompletableFuture<Void> reload(
            PreparableReloadListener.SharedState currentReload,
            Executor taskExecutor,
            PreparableReloadListener.PreparationBarrier preparationBarrier,
            Executor reloadExecutor) {
        ReloadContext context = currentReload.get(RELOAD_CONTEXT);
        if (context.resourceManager() != currentReload.resourceManager()) {
            if (context.active()) {
                EaglerModResourceModelProbe.rejectReload(context.resourceManager(), context.generation());
            }
            throw new IllegalStateException("Mod model reload manager identity changed");
        }
        CompletableFuture<Void> result;
        try {
            result = preparationBarrier.wait(Unit.INSTANCE).thenRunAsync(() -> apply(context), reloadExecutor);
        } catch (RuntimeException failedReload) {
            if (context.active()) EaglerModResourceModelProbe.rejectReload(context.resourceManager(), context.generation());
            throw failedReload;
        }
        return result.whenComplete((ignored, failedReload) -> {
            if (failedReload != null && context.active()) {
                EaglerModResourceModelProbe.rejectReload(context.resourceManager(), context.generation());
            }
        });
    }

    private void apply(ReloadContext context) {
        if (!context.active()) return;
        ResourceManager resourceManager = context.resourceManager();
        int generation = context.generation();
        try {
            // Publish mount completion for every resource-owning module, including
            // texture-only modules with no language request, exactly once per completed reload callback.
            EaglerModResourceMountRequests.publishAll(this.snapshot.mountRequests, resourceManager,
                    this.snapshot.generation, generation, EaglerModResourceBridge::publishResourceReload);
            if (this.snapshot.languageRequests.isEmpty()) return;
        Language language = Language.getInstance(); // LanguageManager ran first and injected this instance.
        for (EaglerModResourceSnapshot.LanguageRequest request : this.snapshot.languageRequests) {
            boolean namespacesVisible = EaglerModResourceMountRequests.isMounted(
                    this.snapshot.mountRequestForModule(request.moduleId()), resourceManager);
            JsonObject publication = new JsonObject();
            publication.addProperty("version", 1);
            publication.addProperty("ok", namespacesVisible);
            publication.addProperty("locale", this.languageManager.getSelected()); // Preserve vanilla metadata code verbatim.
            publication.addProperty("packGeneration", this.snapshot.generation);
            if (!namespacesVisible) {
                publication.addProperty("error", "A declared mod-pack probe is absent from the active ResourceManager stack");
            } else {
                JsonObject values = new JsonObject();
                for (Map.Entry<String, String> entry : request.enUsFallback().entrySet()) {
                    values.addProperty(entry.getKey(), language.getOrDefault(entry.getKey(), entry.getValue()));
                }
                publication.add("values", values);
            }
            // One call publishes one complete generation. Java never invokes or awaits module main.
            // The runtime resolves the first-language barrier only for ok=true. On a later failure it
            // retains the preceding committed generation; on an initial failure no subscriber starts.
            EaglerModResourceBridge.publishResolvedLanguage(request.moduleId(), generation, publication.toString());
        }
        } catch (RuntimeException failedReload) {
            EaglerModResourceModelProbe.rejectReload(resourceManager, generation);
            throw failedReload;
        }
    }
}
