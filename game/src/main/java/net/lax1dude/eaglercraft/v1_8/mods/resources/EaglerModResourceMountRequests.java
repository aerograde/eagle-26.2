package net.lax1dude.eaglercraft.v1_8.mods.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Staged parser/publication helper for client-resource-reload-v1. */
public final class EaglerModResourceMountRequests {
    public static final int MAX_MODULES = 64;
    public static final int MAX_NAMESPACES_PER_MODULE = 64;
    private static final Pattern IDENTIFIER_PART = Pattern.compile("[a-z0-9_.-]{1,64}");

    public record MountRequest(String moduleId, Set<String> namespaces, List<Identifier> probes) { }
    @FunctionalInterface public interface Publisher {
        boolean publish(String moduleId, int reloadGeneration, String json);
    }

    private EaglerModResourceMountRequests() { }

    public static List<MountRequest> parse(JsonArray array, Set<Identifier> aggregateResources) {
        if (array == null || array.size() < 1 || array.size() > MAX_MODULES || aggregateResources == null) {
            throw new IllegalArgumentException("mount requests");
        }
        Set<String> aggregateNamespaces = new HashSet<>();
        aggregateResources.forEach(id -> aggregateNamespaces.add(id.getNamespace()));
        for (String namespace : aggregateNamespaces) requireIdentifier(namespace, "aggregate namespace");
        List<MountRequest> result = new ArrayList<>(array.size());
        Set<String> modules = new HashSet<>(), union = new HashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("mount request");
            JsonObject object = element.getAsJsonObject();
            if (object.size() != 3 || !object.has("moduleId") || !object.has("namespaces") || !object.has("probes")) {
                throw new IllegalArgumentException("mount request keys");
            }
            String moduleId = exactString(object.get("moduleId"), "module id");
            requireIdentifier(moduleId, "module id");
            if (!modules.add(moduleId)) throw new IllegalArgumentException("duplicate module id");
            JsonElement namespaceValue = object.get("namespaces");
            if (!namespaceValue.isJsonArray() || namespaceValue.getAsJsonArray().size() > MAX_NAMESPACES_PER_MODULE) {
                throw new IllegalArgumentException("namespaces");
            }
            Set<String> namespaces = new LinkedHashSet<>();
            for (JsonElement value : namespaceValue.getAsJsonArray()) {
                String namespace = exactString(value, "namespace");
                requireIdentifier(namespace, "namespace");
                if (!aggregateNamespaces.contains(namespace) || !namespaces.add(namespace)) {
                    throw new IllegalArgumentException("unowned or duplicate namespace");
                }
                if (!union.add(namespace)) throw new IllegalArgumentException("namespace has multiple owners");
            }
            JsonElement probeValue = object.get("probes");
            if (!probeValue.isJsonArray() || probeValue.getAsJsonArray().size() != namespaces.size()) {
                throw new IllegalArgumentException("probes");
            }
            List<Identifier> probes = new ArrayList<>(namespaces.size());
            Set<String> probeNamespaces = new HashSet<>();
            for (JsonElement value : probeValue.getAsJsonArray()) {
                if (!value.isJsonObject()) throw new IllegalArgumentException("probe");
                JsonObject probe = value.getAsJsonObject();
                if (probe.size() != 2 || !probe.has("namespace") || !probe.has("path")) throw new IllegalArgumentException("probe keys");
                String namespace = exactString(probe.get("namespace"), "probe namespace");
                String path = exactString(probe.get("path"), "probe path");
                Identifier id = Identifier.tryBuild(namespace, path);
                if (id == null || !namespaces.contains(namespace) || !probeNamespaces.add(namespace) || !aggregateResources.contains(id)) {
                    throw new IllegalArgumentException("unowned or duplicate probe");
                }
                probes.add(id);
            }
            result.add(new MountRequest(moduleId, Set.copyOf(namespaces), List.copyOf(probes)));
        }
        if (!union.equals(aggregateNamespaces)) throw new IllegalArgumentException("mount namespace ownership is incomplete");
        return List.copyOf(result);
    }

    public static int publishAll(List<MountRequest> requests, ResourceManager resourceManager,
            int packGeneration, int reloadGeneration, Publisher publisher) {
        if (requests == null || resourceManager == null || packGeneration < 1 || reloadGeneration < 1 || publisher == null) {
            throw new IllegalArgumentException("reload publication");
        }
        String telemetryBatch = EaglerModResourceBridge.performanceBeginBatch(reloadGeneration, "mount", "resource-manager");
        boolean completed = false;
        try {
            int accepted = 0;
            for (MountRequest request : requests) {
                boolean visible = isMounted(request, resourceManager);
                JsonObject publication = new JsonObject();
                publication.addProperty("version", 1);
                publication.addProperty("ok", visible);
                publication.addProperty("packGeneration", packGeneration);
                if (!visible) publication.addProperty("error", "A declared mod-pack probe is absent from the active ResourceManager stack");
                if (publisher.publish(request.moduleId(), reloadGeneration, publication.toString())) accepted++;
            }
            completed = true;
            return accepted;
        } finally {
            EaglerModResourceBridge.performanceFinishBatch(telemetryBatch, completed);
        }
    }

    static boolean isMounted(MountRequest request, ResourceManager resourceManager) {
        return resourceManager != null && isMounted(request,
                id -> resourceManager.getResourceStack(id).stream()
                        .map(resource -> resource.sourcePackId()).toList());
    }

    static boolean isMounted(MountRequest request, Function<Identifier, List<String>> probeSourcePacks) {
        if (request == null || probeSourcePacks == null) return false;
        // A module with root-only resources has no namespaced probe; reaching
        // this completed callback is its mount barrier.
        for (Identifier probe : request.probes()) {
            List<String> stack = probeSourcePacks.apply(probe);
            if (stack == null || !stack.contains(EaglerModResourcePackSource.PACK_ID)) return false;
        }
        return true;
    }

    private static String exactString(JsonElement value, String label) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(label);
        }
        return value.getAsString();
    }

    private static void requireIdentifier(String value, String label) {
        if (!IDENTIFIER_PART.matcher(value).matches()) throw new IllegalArgumentException(label);
    }
}
