package net.lax1dude.eaglercraft.v1_8.mods.resources;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Generation-scoped definitions for resource-only blocks.
 *
 * <p>These definitions intentionally use {@link Blocks#AIR} as their owner.
 * They are a renderer-only overlay and are never inserted into a registry or
 * into a static definition table.  A reload token gets one immutable snapshot;
 * retaining the token in {@code reloadSnapshots} prevents a delayed callback
 * from consulting a newer pack generation.</p>
 */
public final class EaglerModDeclaredBlockStates {
    private static final Object LOCK = new Object();
    private static final Map<Object, Snapshot> RELOAD_SNAPSHOTS = new ConcurrentHashMap<>();
    private static Snapshot installed = Snapshot.EMPTY;
    private static int lastReloadPackGeneration;
    private static int lastReloadGeneration;

    private EaglerModDeclaredBlockStates() { }

    /** Installs the sealed pack-generation declarations. */
    static void install(int packGeneration, List<EaglerModResourceSnapshot.ModelRequest> requests) {
        Snapshot next = build(packGeneration, requests);
        synchronized (LOCK) {
            installed = next;
            // Keep snapshots already captured by older ResourceManagers so a
            // delayed N callback can only ever observe N.  New tokens are
            // captured explicitly by beginReload and never fall back to the
            // current generation.
            lastReloadPackGeneration = packGeneration;
            lastReloadGeneration = 0;
        }
    }

    /** Captures exactly one declaration snapshot for one reload token. */
    static void beginReload(int packGeneration, int reloadGeneration, Object reloadToken) {
        if (reloadToken == null) return;
        synchronized (LOCK) {
            Snapshot current = installed;
            if (current.packGeneration != packGeneration || reloadGeneration < 1
                    || (lastReloadPackGeneration == packGeneration && reloadGeneration <= lastReloadGeneration)) return;
            lastReloadPackGeneration = packGeneration;
            lastReloadGeneration = reloadGeneration;
            RELOAD_SNAPSHOTS.putIfAbsent(reloadToken, current);
        }
    }

    /** Returns the snapshot captured for the reload, or an empty snapshot. */
    public static Snapshot forReload(Object reloadToken) {
        if (reloadToken == null) return Snapshot.EMPTY;
        Snapshot result = RELOAD_SNAPSHOTS.get(reloadToken);
        return result != null ? result : Snapshot.EMPTY;
    }

    /** Clears all installed and in-flight definitions on teardown/disable. */
    public static void clear() {
        synchronized (LOCK) {
            installed = Snapshot.EMPTY;
            RELOAD_SNAPSHOTS.clear();
            lastReloadPackGeneration = 0;
            lastReloadGeneration = 0;
        }
    }

    public static void disable() { clear(); }
    public static void teardown() { clear(); }
    public static void uninstall() { clear(); }

    private static Snapshot build(int packGeneration,
            List<EaglerModResourceSnapshot.ModelRequest> requests) {
        if (packGeneration < 1 || requests == null || requests.isEmpty()) {
            return packGeneration < 1 ? Snapshot.EMPTY : new Snapshot(packGeneration, Map.of());
        }
        Map<Identifier, Definition> result = new LinkedHashMap<>();
        for (EaglerModResourceSnapshot.ModelRequest request : requests) {
            Identifier id = request.blockstateId();
            // A declaration may only supply a renderer definition for an
            // unknown non-minecraft ID. Registered/vanilla collisions are
            // deliberately rejected, never shadowed.
            if (id == null || !request.moduleId().equals(id.getNamespace())
                    || "minecraft".equals(id.getNamespace()) || BuiltInRegistries.BLOCK.getOptional(id).isPresent()) {
                throw new IllegalArgumentException("Declared virtual block is foreign, minecraft, or registered: " + id);
            }
            if (result.put(id, createDefinition(id, request.moduleId(), request.closureSHA256(), request.blockstateProperties())) != null) {
                throw new IllegalArgumentException("Duplicate declared virtual block: " + id);
            }
        }
        return new Snapshot(packGeneration, result);
    }

    private static Definition createDefinition(Identifier id, String moduleId, String closure,
            List<EaglerModResourceSnapshot.BlockstateProperty> declaredProperties) {
        if (declaredProperties == null || declaredProperties.size() > 8) {
            throw new IllegalArgumentException("Virtual block has too many properties: " + id);
        }
        List<GenericStringProperty> properties = new ArrayList<>(declaredProperties.size());
        long expectedStates = 1L;
        Set<String> propertyNames = new LinkedHashSet<>();
        for (EaglerModResourceSnapshot.BlockstateProperty declared : declaredProperties) {
            checkName(declared.name(), "property", id);
            if (!propertyNames.add(declared.name())) throw new IllegalArgumentException("Duplicate virtual property: " + id);
            List<String> values = List.copyOf(declared.values());
            if (values.size() < 2 || values.size() > 32) throw new IllegalArgumentException("Virtual property value count: " + id);
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String value : values) {
                checkName(value, "property value", id);
                if (!unique.add(value)) throw new IllegalArgumentException("Duplicate property value: " + id + "/" + declared.name());
            }
            expectedStates *= values.size();
            if (expectedStates > 256L) throw new IllegalArgumentException("Virtual block has too many states: " + id);
            properties.add(new GenericStringProperty(declared.name(), values));
        }
        StateDefinition.Builder<Block, BlockState> builder = new StateDefinition.Builder<>(Blocks.AIR);
        if (!properties.isEmpty()) builder.add(properties.toArray(Property[]::new));
        StateDefinition<Block, BlockState> stateDefinition = builder.create(Block::defaultBlockState, BlockState::new);

        Set<String> expected = new LinkedHashSet<>();
        for (BlockState state : stateDefinition.getPossibleStates()) expected.add(canonicalKey(state));
        return new Definition(id, moduleId, closure, stateDefinition,
                Collections.unmodifiableSet(expected));
    }

    private static String canonicalKey(BlockState state) {
        StringBuilder result = new StringBuilder();
        state.getValues().forEach(value -> {
            if (result.length() > 0) result.append(',');
            result.append(value.property().getName()).append('=').append(value.valueName());
        });
        return result.toString();
    }

    private static void checkName(String value, String label, Identifier id) {
        if (value == null || !value.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid virtual " + label + " for " + id + ": " + value);
        }
    }

    public static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(0, Map.of());
        private final int packGeneration;
        private final Map<Identifier, Definition> definitions;

        private Snapshot(int packGeneration, Map<Identifier, Definition> definitions) {
            this.packGeneration = packGeneration;
            this.definitions = Map.copyOf(definitions);
        }

        public int packGeneration() { return this.packGeneration; }
        public Definition definition(Identifier id) {
            // TeaVM's immutable empty-map implementation currently hashes via
            // a zero-length table. Avoid its modulo-zero path while retaining
            // the immutable Map.copyOf representation for non-empty snapshots.
            return this.definitions.isEmpty() ? null : this.definitions.get(id);
        }
        public Map<Identifier, Definition> definitions() { return this.definitions; }
        public boolean isEmpty() { return this.definitions.isEmpty(); }
    }

    public static final class Definition {
        private final Identifier id;
        private final String moduleId;
        private final String closureSHA256;
        private final StateDefinition<Block, BlockState> stateDefinition;
        private final Set<String> canonicalStateKeys;

        private Definition(Identifier id, String moduleId, String closureSHA256,
                StateDefinition<Block, BlockState> stateDefinition, Set<String> canonicalStateKeys) {
            this.id = id;
            this.moduleId = moduleId;
            this.closureSHA256 = closureSHA256;
            this.stateDefinition = stateDefinition;
            this.canonicalStateKeys = canonicalStateKeys;
        }

        public Identifier id() { return this.id; }
        public String moduleId() { return this.moduleId; }
        public String closureSHA256() { return this.closureSHA256; }
        public StateDefinition<Block, BlockState> stateDefinition() { return this.stateDefinition; }
        public Set<String> canonicalStateKeys() { return this.canonicalStateKeys; }
        public int expectedCount() { return this.stateDefinition.getPossibleStates().size(); }
    }

    private static final class GenericStringProperty extends Property<String> {
        private final List<String> values;
        private final Map<String, Integer> indices;

        GenericStringProperty(String name, List<String> values) {
            super(name, String.class);
            this.values = List.copyOf(values);
            Map<String, Integer> map = new LinkedHashMap<>();
            for (int i = 0; i < this.values.size(); i++) map.put(this.values.get(i), i);
            this.indices = Map.copyOf(map);
        }

        @Override public List<String> getPossibleValues() { return this.values; }
        @Override public String getName(String value) { return value; }
        @Override public java.util.Optional<String> getValue(String name) { return this.indices.containsKey(name) ? java.util.Optional.of(name) : java.util.Optional.empty(); }
        @Override public int getInternalIndex(String value) { return this.indices.getOrDefault(value, -1); }
    }
}
