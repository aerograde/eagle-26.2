package net.lax1dude.eaglercraft.v1_8.mods.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/** Immutable, bounded native view of a registry that JavaScript has already validated and sealed. */
final class EaglerModResourceSnapshot {
    static final int MAX_MODULES = 64;
    static final int MAX_RESOURCES = 8192;
    static final int MAX_RESOURCE_BYTES = 16 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;
    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    record LanguageRequest(String moduleId, Map<String, String> enUsFallback) { }

    /** Canonical renderer-only property declaration from model-request-v2. */
    record BlockstateProperty(String name, List<String> values) {
        BlockstateProperty {
            values = List.copyOf(values);
        }
    }

    record ModelRequest(String moduleId, Identifier itemModelId, Identifier blockstateId,
            List<Identifier> modelIds, List<Identifier> textureIds, List<Identifier> atlasIds,
            Map<Identifier, Identifier> textureToAtlas, String closureSHA256,
            List<BlockstateProperty> blockstateProperties) { }

    static final class ResourceRef {
        final String handle;
        final int bytes;
        final String sha256;
        final List<String> owners;
        private volatile byte[] cached;

        ResourceRef(String handle, int bytes, String sha256) {
            this(handle, bytes, sha256, List.of());
        }

        ResourceRef(String handle, int bytes, String sha256, List<String> owners) {
            this.handle = handle;
            this.bytes = bytes;
            this.sha256 = sha256;
            this.owners = List.copyOf(owners);
        }

        InputStream open(String packHandle) throws IOException {
            byte[] value = this.cached;
            if (value == null) {
                synchronized (this) {
                    value = this.cached;
                    if (value == null) {
                        ArrayBuffer buffer = EaglerModResourceBridge.readResource(packHandle, this.handle);
                        if (buffer == null || buffer.getByteLength() > MAX_RESOURCE_BYTES) {
                            EaglerModResourceBridge.failResourceInitialization("A sealed mod resource became unavailable after registry publication");
                            throw new IOException("Missing or oversized sealed mod resource " + this.handle);
                        }
                        value = new Int8Array(buffer).copyToJavaArray();
                        if (value.length != this.bytes || !sha256(value).equals(this.sha256)) {
                            EaglerModResourceBridge.failResourceInitialization("A sealed mod resource changed after registry publication");
                            throw new IOException("Sealed mod resource changed after validation: " + this.handle);
                        }
                        this.cached = value;
                    }
                }
            }
            return new ByteArrayInputStream(value);
        }
    }

    final int generation;
    final String packHandle;
    final Map<String, ResourceRef> rootResources;
    final Map<Identifier, ResourceRef> resources;
    final Set<String> namespaces;
    final List<EaglerModResourceMountRequests.MountRequest> mountRequests;
    final List<LanguageRequest> languageRequests;
    final List<ModelRequest> modelRequests;
    final String failureReason;
    private boolean failurePublished;

    private EaglerModResourceSnapshot(int generation, String packHandle, Map<String, ResourceRef> rootResources,
            Map<Identifier, ResourceRef> resources, Set<String> namespaces,
            List<EaglerModResourceMountRequests.MountRequest> mountRequests, List<LanguageRequest> languageRequests,
            List<ModelRequest> modelRequests, String failureReason) {
        this.generation = generation;
        this.packHandle = packHandle;
        this.rootResources = Map.copyOf(rootResources);
        this.resources = Map.copyOf(resources);
        this.namespaces = Set.copyOf(namespaces);
        this.mountRequests = List.copyOf(mountRequests);
        this.languageRequests = List.copyOf(languageRequests);
        this.modelRequests = List.copyOf(modelRequests);
        this.failureReason = failureReason;
    }

    static EaglerModResourceSnapshot capture() {
        String raw = EaglerModResourceBridge.snapshotPacks();
        if (raw == null) {
            return EaglerModResourceBridge.registryPresent()
                    ? rejected("Published mod resource registry did not provide a valid bounded snapshot") : absent();
        }
        return parse(raw);
    }

    /** Package-private for the source-matched staged JVM parser proof. */
    static EaglerModResourceSnapshot parse(String raw) {
        if (raw.length() > EaglerModResourceBridge.MAX_SNAPSHOT_JSON) return rejected("Mod resource snapshot exceeds native limit");
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            requireExactKeys(root, Set.of("version", "validated", "generation", "packHandle", "rootResources",
                    "resources", "mountRequests", "languageRequests", "modelCapability", "modelRequests"),
                    Set.of("version", "validated", "generation", "packHandle", "rootResources",
                    "resources", "mountRequests", "languageRequests"));
            if (exactInt(root, "version", 1, 1) != 1 || !exactBoolean(root, "validated")) return rejected("Mod resource snapshot version or validation seal is invalid");
            int generation = exactInt(root, "generation", 1, Integer.MAX_VALUE);
            String packHandle = boundedText(root, "packHandle", 128);
            if (packHandle == null) return rejected("Mod resource snapshot pack handle is invalid");
            Map<String, ResourceRef> roots = parseRootResources(root.getAsJsonArray("rootResources"));
            Map<Identifier, ResourceRef> resources = parseResources(root.getAsJsonArray("resources"));
            if (!roots.containsKey("pack.mcmeta")) return rejected("Validated mod resource snapshot is missing pack.mcmeta");
            long total = roots.values().stream().mapToLong(r -> r.bytes).sum()
                    + resources.values().stream().mapToLong(r -> r.bytes).sum();
            if (total > MAX_TOTAL_BYTES) return rejected("Validated mod resources exceed aggregate native limit");
            Set<String> namespaces = new HashSet<>();
            resources.keySet().forEach(id -> namespaces.add(id.getNamespace()));
            List<EaglerModResourceMountRequests.MountRequest> mounts = EaglerModResourceMountRequests.parse(root.getAsJsonArray("mountRequests"), resources.keySet());
            List<LanguageRequest> requests = parseLanguageRequests(root.getAsJsonArray("languageRequests"));
            Set<String> mountModules = new HashSet<>();
            mounts.forEach(request -> mountModules.add(request.moduleId()));
            if (roots.values().stream().anyMatch(ref -> ref.owners.stream().anyMatch(owner -> !mountModules.contains(owner)))) {
                return rejected("Root resource provenance names an unmounted module");
            }
            List<ModelRequest> modelRequests = parseModelRequests(root.has("modelRequests")
                    ? root.getAsJsonArray("modelRequests") : null, mounts, roots, resources);
            boolean modelCapability = root.has("modelCapability") && exactBoolean(root, "modelCapability");
            if (modelCapability != !modelRequests.isEmpty()) {
                return rejected("Model capability discriminator does not match model requests");
            }
            if (requests.stream().anyMatch(request -> !mountModules.contains(request.moduleId()))) {
                return rejected("Resolved-language request has no resource mount owner");
            }
            return new EaglerModResourceSnapshot(generation, packHandle, roots, resources, namespaces, mounts, requests, modelRequests, null);
        } catch (RuntimeException invalid) {
            return rejected("Published mod resource snapshot failed native validation");
        }
    }

    static EaglerModResourceSnapshot absent() {
        return new EaglerModResourceSnapshot(0, "", Map.of(), Map.of(), Set.of(), List.of(), List.of(), List.of(), null);
    }

    static EaglerModResourceSnapshot rejected(String reason) {
        return new EaglerModResourceSnapshot(-1, "", Map.of(), Map.of(), Set.of(), List.of(), List.of(), List.of(), reason);
    }

    boolean isEmpty() {
        return this.generation <= 0;
    }

    boolean isRejected() {
        return this.generation < 0;
    }

    synchronized void publishFailure(String reason) {
        if (!this.failurePublished) {
            this.failurePublished = true;
            EaglerModResourceBridge.failResourceInitialization(reason.length() <= 512 ? reason : reason.substring(0, 511) + "…");
        }
    }

    Set<String> namespacesForModule(String moduleId) {
        for (EaglerModResourceMountRequests.MountRequest request : this.mountRequests) {
            if (request.moduleId().equals(moduleId)) return request.namespaces();
        }
        return Set.of();
    }

    EaglerModResourceMountRequests.MountRequest mountRequestForModule(String moduleId) {
        for (EaglerModResourceMountRequests.MountRequest request : this.mountRequests) {
            if (request.moduleId().equals(moduleId)) return request;
        }
        return null;
    }

    private static List<ModelRequest> parseModelRequests(JsonArray array,
            List<EaglerModResourceMountRequests.MountRequest> mounts,
            Map<String, ResourceRef> roots, Map<Identifier, ResourceRef> resources) {
        if (array == null) return List.of();
        if (array.size() == 0) return List.of();
        if (array.size() > MAX_MODULES) throw new IllegalArgumentException("model requests");
        Map<String, EaglerModResourceMountRequests.MountRequest> owners = new HashMap<>();
        for (EaglerModResourceMountRequests.MountRequest mount : mounts) owners.put(mount.moduleId(), mount);
        Set<String> requestOwners = new HashSet<>(), closureHashes = new HashSet<>();
        List<ModelRequest> result = new ArrayList<>(array.size());
        for (JsonElement value : array) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("model request");
            JsonObject object = value.getAsJsonObject();
            requireExactKeys(object, Set.of("moduleId", "itemModelId", "blockstateId", "blockstateProperties", "modelIds", "textureIds", "atlasIds", "textureToAtlas", "closureSHA256"),
                    Set.of("moduleId", "itemModelId", "blockstateId", "blockstateProperties", "modelIds", "textureIds", "atlasIds", "textureToAtlas", "closureSHA256"));
            String moduleId = boundedText(object, "moduleId", 64);
            if (moduleId == null || !MOD_ID.matcher(moduleId).matches() || !requestOwners.add(moduleId)) {
                throw new IllegalArgumentException("model request owner");
            }
            EaglerModResourceMountRequests.MountRequest owner = owners.get(moduleId);
            if (owner == null) throw new IllegalArgumentException("model request owner is not mounted");
            Identifier itemModelId = exactIdentifier(object, "itemModelId");
            Identifier blockstateId = exactIdentifier(object, "blockstateId");
            if (!ownedOrVanilla(owner, itemModelId)
                    || "minecraft".equals(blockstateId.getNamespace())
                    || !moduleId.equals(blockstateId.getNamespace())
                    || blockstateId.getPath().startsWith("blockstates/")) {
                throw new IllegalArgumentException("model request root is not owned");
            }
            Identifier blockstateResource = Identifier.tryBuild(moduleId, "blockstates/" + blockstateId.getPath() + ".json");
            if (blockstateResource == null || !resources.containsKey(blockstateResource)) {
                throw new IllegalArgumentException("model request blockstate resource is missing");
            }
            List<BlockstateProperty> blockstateProperties = parseBlockstateProperties(object.get("blockstateProperties"));
            List<Identifier> modelIds = exactIdentifiers(object.getAsJsonArray("modelIds"), "model ids");
            List<Identifier> textureIds = exactIdentifiers(object.getAsJsonArray("textureIds"), "texture ids");
            List<Identifier> atlasIds = exactIdentifiers(object.getAsJsonArray("atlasIds"), "atlas ids");
            for (Identifier id : modelIds) if (!ownedOrVanilla(owner, id)) throw new IllegalArgumentException("model id owner");
            for (Identifier id : textureIds) if (!ownedOrVanilla(owner, id)) throw new IllegalArgumentException("texture id owner");
            for (Identifier id : atlasIds) if (!ownedOrVanilla(owner, id)) throw new IllegalArgumentException("atlas id owner");
            Map<Identifier, Identifier> textureToAtlas = exactTextureToAtlas(object.getAsJsonObject("textureToAtlas"), textureIds, atlasIds);
            String hash = boundedText(object, "closureSHA256", 64);
            if (hash == null || !SHA256.matcher(hash).matches() || !closureHashes.add(hash)) {
                throw new IllegalArgumentException("model request closure hash");
            }
            ModelRequest request = new ModelRequest(moduleId, itemModelId, blockstateId, modelIds, textureIds,
                    atlasIds, textureToAtlas, hash, blockstateProperties);
            // The JS registry validates each model request against the package's
            // own sealed roots/resources before packages are merged. Recreate
            // that ownership projection here; hashing the aggregate registry
            // would make an unrelated control package change this request's
            // closure and would no longer match the JS contract.
            Map<String, ResourceRef> ownedRoots = closureRoots(moduleId, roots);
            Map<Identifier, ResourceRef> ownedResources = closureResources(owner, resources);
            if (!hash.equals(canonicalClosureSHA256(request, owner, ownedRoots, ownedResources))) {
                throw new IllegalArgumentException("model request closure hash mismatch");
            }
            result.add(request);
        }
        return List.copyOf(result);
    }

    private static List<BlockstateProperty> parseBlockstateProperties(JsonElement raw) {
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() > 8) {
            throw new IllegalArgumentException("model request blockstateProperties");
        }
        List<BlockstateProperty> result = new ArrayList<>(raw.getAsJsonArray().size());
        Set<String> names = new HashSet<>();
        for (JsonElement element : raw.getAsJsonArray()) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("blockstate property");
            JsonObject object = element.getAsJsonObject();
            requireExactKeys(object, Set.of("name", "values"), Set.of("name", "values"));
            String name = propertyToken(object.get("name"), "blockstate property name");
            if (!names.add(name)) throw new IllegalArgumentException("duplicate blockstate property");
            JsonElement valuesElement = object.get("values");
            if (!valuesElement.isJsonArray() || valuesElement.getAsJsonArray().size() < 2
                    || valuesElement.getAsJsonArray().size() > 32) {
                throw new IllegalArgumentException("blockstate property values");
            }
            List<String> values = new ArrayList<>(valuesElement.getAsJsonArray().size());
            Set<String> seen = new HashSet<>();
            for (JsonElement value : valuesElement.getAsJsonArray()) {
                String parsed = propertyToken(value, "blockstate property value");
                if (!seen.add(parsed)) throw new IllegalArgumentException("duplicate blockstate property value");
                values.add(parsed);
            }
            result.add(new BlockstateProperty(name, canonicalPropertyValues(values)));
        }
        result.sort((a, b) -> a.name().compareTo(b.name()));
        long cartesianStates = 1L;
        for (BlockstateProperty property : result) cartesianStates *= property.values().size();
        if (cartesianStates > 256L) throw new IllegalArgumentException("blockstate Cartesian state count");
        return List.copyOf(result);
    }

    private static String propertyToken(JsonElement element, String label) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(label);
        }
        String value = element.getAsString();
        if (!value.matches("[a-z0-9_]{1,64}")) throw new IllegalArgumentException(label);
        return value;
    }

    private static List<String> canonicalPropertyValues(List<String> values) {
        List<String> directions = List.of("north", "east", "south", "west");
        if (values.size() == directions.size() && values.containsAll(directions)) return directions;
        return values.stream().sorted().toList();
    }

    private static boolean ownedOrVanilla(EaglerModResourceMountRequests.MountRequest owner, Identifier id) {
        return "minecraft".equals(id.getNamespace()) || owner.namespaces().contains(id.getNamespace());
    }

    private static List<Identifier> exactIdentifiers(JsonArray array, String label) {
        if (array == null || array.size() < 1 || array.size() > MAX_RESOURCES) throw new IllegalArgumentException(label);
        List<Identifier> result = new ArrayList<>(array.size());
        Set<Identifier> seen = new HashSet<>();
        for (JsonElement value : array) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(label);
            Identifier id = Identifier.tryParse(value.getAsString());
            if (id == null || !id.toString().equals(value.getAsString()) || !seen.add(id)) throw new IllegalArgumentException(label);
            result.add(id);
        }
        return List.copyOf(result);
    }

    private static Map<Identifier, Identifier> exactTextureToAtlas(JsonObject object,
            List<Identifier> textures, List<Identifier> atlases) {
        if (object == null || object.size() != textures.size()) throw new IllegalArgumentException("texture atlas map");
        Set<Identifier> allowed = Set.copyOf(atlases);
        Map<Identifier, Identifier> result = new LinkedHashMap<>();
        for (Identifier texture : textures) {
            JsonElement value = object.get(texture.toString());
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("texture atlas map");
            }
            Identifier atlas = Identifier.tryParse(value.getAsString());
            if (atlas == null || !atlas.toString().equals(value.getAsString())
                    || !allowed.contains(atlas) || result.put(texture, atlas) != null) {
                throw new IllegalArgumentException("texture atlas map");
            }
        }
        if (!result.keySet().equals(Set.copyOf(textures))) throw new IllegalArgumentException("texture atlas map");
        return Map.copyOf(result);
    }

    /** Canonical cross-language digest: IDs plus mounted resource path/size/hash bindings. */
    static String canonicalClosureSHA256(ModelRequest request,
            EaglerModResourceMountRequests.MountRequest owner,
            Map<String, ResourceRef> roots, Map<Identifier, ResourceRef> resources) {
        StringBuilder text = new StringBuilder(1024).append("model-request-v2\n")
                .append(request.moduleId()).append('\n').append(request.itemModelId()).append('\n')
                .append(request.blockstateId()).append('\n')
                .append("blockstateProperties=").append(canonicalBlockstatePropertiesJson(request.blockstateProperties())).append('\n');
        appendSorted(text, "models", request.modelIds());
        appendSorted(text, "textures", request.textureIds());
        appendSorted(text, "atlases", request.atlasIds());
        text.append("textureToAtlas=");
        request.textureToAtlas().entrySet().stream().sorted((a, b) -> a.getKey().toString().compareTo(b.getKey().toString()))
                .forEach(entry -> text.append(entry.getKey()).append("->").append(entry.getValue()).append(','));
        text.append("\nrefs=\n");
        roots.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendRef(text, "root", entry.getKey(), entry.getValue()));
        resources.entrySet().stream().sorted((a, b) -> a.getKey().toString().compareTo(b.getKey().toString()))
                .forEach(entry -> appendRef(text, "resource", entry.getKey().toString(), entry.getValue()));
        return sha256(text.toString());
    }

    private static Map<String, ResourceRef> closureRoots(String moduleId,
            Map<String, ResourceRef> roots) {
        Map<String, ResourceRef> result = new LinkedHashMap<>();
        roots.forEach((path, ref) -> {
            if (ref.owners.contains(moduleId)) result.put(path, ref);
        });
        return result;
    }

    private static Map<Identifier, ResourceRef> closureResources(
            EaglerModResourceMountRequests.MountRequest owner,
            Map<Identifier, ResourceRef> resources) {
        Map<Identifier, ResourceRef> result = new LinkedHashMap<>();
        resources.forEach((id, ref) -> {
            if (owner.namespaces().contains(id.getNamespace())) result.put(id, ref);
        });
        return result;
    }

    private static String canonicalBlockstatePropertiesJson(List<BlockstateProperty> properties) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < properties.size(); i++) {
            if (i > 0) text.append(',');
            BlockstateProperty property = properties.get(i);
            text.append("{\"name\":\"").append(property.name()).append("\",\"values\":[");
            for (int j = 0; j < property.values().size(); j++) {
                if (j > 0) text.append(',');
                text.append('\"').append(property.values().get(j)).append('\"');
            }
            text.append("]}");
        }
        return text.append(']').toString();
    }

    private static void appendSorted(StringBuilder text, String label, List<Identifier> values) {
        text.append(label).append('='); values.stream().map(Identifier::toString).sorted()
                .forEach(value -> text.append(value).append(',')); text.append('\n');
    }

    private static void appendRef(StringBuilder text, String kind, String id, ResourceRef ref) {
        text.append(kind).append('|').append(id).append('|').append(ref.bytes).append('|').append(ref.sha256).append('\n');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte part : digest) result.append(String.format("%02x", part & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Map<String, ResourceRef> parseRootResources(JsonArray array) {
        if (array == null || array.size() > 16) throw new IllegalArgumentException("root resources");
        Map<String, ResourceRef> result = new LinkedHashMap<>();
        for (JsonElement value : array) {
            JsonObject object = value.getAsJsonObject();
            String path = boundedText(object, "path", 128);
            if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) {
                throw new IllegalArgumentException("root path");
            }
            if (result.put(path, parseRef(object, Set.of("path", "handle", "bytes", "sha256", "owners"), true)) != null) throw new IllegalArgumentException("duplicate root");
        }
        return result;
    }

    private static Map<Identifier, ResourceRef> parseResources(JsonArray array) {
        if (array == null || array.size() > MAX_RESOURCES) throw new IllegalArgumentException("resources");
        Map<Identifier, ResourceRef> result = new LinkedHashMap<>();
        for (JsonElement value : array) {
            JsonObject object = value.getAsJsonObject();
            if (!"client".equals(boundedText(object, "type", 16))) throw new IllegalArgumentException("resource type");
            String namespace = boundedText(object, "namespace", 64);
            String path = boundedText(object, "path", 512);
            Identifier id = namespace == null || path == null ? null : Identifier.tryBuild(namespace, path);
            if (id == null || result.put(id, parseRef(object, Set.of("type", "namespace", "path", "handle", "bytes", "sha256"), false)) != null) throw new IllegalArgumentException("resource id");
        }
        return result;
    }

    private static List<LanguageRequest> parseLanguageRequests(JsonArray array) {
        if (array == null || array.size() > MAX_MODULES) throw new IllegalArgumentException("language requests");
        List<LanguageRequest> result = new ArrayList<>(array.size());
        Set<String> ids = new HashSet<>();
        for (JsonElement value : array) {
            JsonObject object = value.getAsJsonObject();
            requireExactKeys(object, Set.of("moduleId", "enUsFallback"), Set.of("moduleId", "enUsFallback"));
            String moduleId = boundedText(object, "moduleId", 64);
            if (moduleId == null || !MOD_ID.matcher(moduleId).matches() || !ids.add(moduleId)) throw new IllegalArgumentException("module id");
            JsonObject fallbacks = object.getAsJsonObject("enUsFallback");
            if (fallbacks == null || fallbacks.size() > 4096) throw new IllegalArgumentException("fallbacks");
            Map<String, String> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : fallbacks.entrySet()) {
                String key = entry.getKey();
                if (key.isBlank() || key.length() > 256 || containsControl(key)) throw new IllegalArgumentException("translation key");
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) throw new IllegalArgumentException("fallback value");
                String fallback = entry.getValue().getAsString();
                if (fallback.length() > 1024 || containsControlExceptWhitespace(fallback)) throw new IllegalArgumentException("fallback value");
                parsed.put(key, fallback);
            }
            result.add(new LanguageRequest(moduleId, Map.copyOf(parsed)));
        }
        return result;
    }

    private static ResourceRef parseRef(JsonObject object, Set<String> allowed, boolean withOwners) {
        requireExactKeys(object, allowed, withOwners ? Set.of("handle", "bytes", "sha256", "owners") : Set.of("handle", "bytes", "sha256"));
        String handle = boundedText(object, "handle", 128);
        String hash = boundedText(object, "sha256", 64);
        int bytes = exactInt(object, "bytes", 0, MAX_RESOURCE_BYTES);
        if (handle == null || hash == null || !SHA256.matcher(hash).matches()) throw new IllegalArgumentException("resource ref");
        List<String> owners = List.of();
        if (withOwners) {
            JsonArray ownerArray = object.getAsJsonArray("owners");
            if (ownerArray == null || ownerArray.size() < 1 || ownerArray.size() > MAX_MODULES) throw new IllegalArgumentException("resource owners");
            Set<String> seen = new HashSet<>();
            List<String> parsed = new ArrayList<>();
            for (JsonElement value : ownerArray) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("resource owners");
                String owner = value.getAsString();
                if (!MOD_ID.matcher(owner).matches() || !seen.add(owner)) throw new IllegalArgumentException("resource owners");
                parsed.add(owner);
            }
            owners = List.copyOf(parsed);
        }
        return new ResourceRef(handle, bytes, hash, owners);
    }

    private static String boundedText(JsonObject object, String key, int max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String text = value.getAsString();
        return text.isBlank() || text.length() > max ? null : text;
    }

    private static Identifier exactIdentifier(JsonObject object, String key) {
        String value = boundedText(object, key, 512);
        Identifier id = value == null ? null : Identifier.tryParse(value);
        if (id == null || !id.toString().equals(value)) throw new IllegalArgumentException(key);
        return id;
    }

    private static void requireExactKeys(JsonObject object, Set<String> allowed, Set<String> required) {
        if (object == null || !object.keySet().stream().allMatch(allowed::contains)
                || !object.keySet().containsAll(required)) throw new IllegalArgumentException("object keys");
    }

    private static int exactInt(JsonObject object, String key, int min, int max) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key);
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) throw new IllegalArgumentException(key);
        return (int)number;
    }

    private static boolean exactBoolean(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key);
        return value.getAsBoolean();
    }

    private static boolean containsControl(String value) {
        return value.codePoints().anyMatch(c -> Character.isISOControl(c));
    }

    private static boolean containsControlExceptWhitespace(String value) {
        return value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] result = new char[64];
            char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                result[i * 2] = hex[value >>> 4];
                result[i * 2 + 1] = hex[value & 15];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException impossible) {
            EaglerModResourceBridge.failResourceInitialization("Native SHA-256 validation is unavailable for mod resources");
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }
}
