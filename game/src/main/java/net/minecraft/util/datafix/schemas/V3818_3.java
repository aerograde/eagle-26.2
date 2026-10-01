package net.minecraft.util.datafix.schemas;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.templates.TypeTemplate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Supplier;
import net.minecraft.util.datafix.fixes.References;

public class V3818_3 extends NamespacedSchema {
   public V3818_3(final int versionKey, final Schema parent) {
      super(versionKey, parent);
   }

   public static SequencedMap<String, Supplier<TypeTemplate>> components(final Schema schema) {
      SequencedMap<String, Supplier<TypeTemplate>> components = new LinkedHashMap<String, Supplier<TypeTemplate>>();
      components.put("minecraft:bees", (Supplier<TypeTemplate>)() -> DSL.list(DSL.optionalFields("entity_data", References.ENTITY_TREE.in(schema))));
      components.put("minecraft:block_entity_data", (Supplier<TypeTemplate>)() -> References.BLOCK_ENTITY.in(schema));
      components.put("minecraft:bundle_contents", (Supplier<TypeTemplate>)() -> DSL.list(References.ITEM_STACK.in(schema)));
      components.put(
         "minecraft:can_break",
         (Supplier<TypeTemplate>)() -> DSL.optionalFields(
            "predicates", DSL.list(DSL.optionalFields("blocks", DSL.or(References.BLOCK_NAME.in(schema), DSL.list(References.BLOCK_NAME.in(schema)))))
         )
      );
      components.put(
         "minecraft:can_place_on",
         (Supplier<TypeTemplate>)() -> DSL.optionalFields(
            "predicates", DSL.list(DSL.optionalFields("blocks", DSL.or(References.BLOCK_NAME.in(schema), DSL.list(References.BLOCK_NAME.in(schema)))))
         )
      );
      components.put("minecraft:charged_projectiles", (Supplier<TypeTemplate>)() -> DSL.list(References.ITEM_STACK.in(schema)));
      components.put("minecraft:container", (Supplier<TypeTemplate>)() -> DSL.list(DSL.optionalFields("item", References.ITEM_STACK.in(schema))));
      components.put("minecraft:entity_data", (Supplier<TypeTemplate>)() -> References.ENTITY_TREE.in(schema));
      components.put("minecraft:pot_decorations", (Supplier<TypeTemplate>)() -> DSL.list(References.ITEM_NAME.in(schema)));
      components.put("minecraft:food", (Supplier<TypeTemplate>)() -> DSL.optionalFields("using_converts_to", References.ITEM_STACK.in(schema)));
      components.put("minecraft:custom_name", (Supplier<TypeTemplate>)() -> References.TEXT_COMPONENT.in(schema));
      components.put("minecraft:item_name", (Supplier<TypeTemplate>)() -> References.TEXT_COMPONENT.in(schema));
      components.put("minecraft:lore", (Supplier<TypeTemplate>)() -> DSL.list(References.TEXT_COMPONENT.in(schema)));
      components.put(
         "minecraft:written_book_content",
         (Supplier<TypeTemplate>)() -> DSL.optionalFields(
            "pages",
            DSL.list(
               DSL.or(
                  DSL.optionalFields("raw", References.TEXT_COMPONENT.in(schema), "filtered", References.TEXT_COMPONENT.in(schema)),
                  References.TEXT_COMPONENT.in(schema)
               )
            )
         )
      );
      return components;
   }

   public void registerTypes(
      final Schema schema, final Map<String, Supplier<TypeTemplate>> entityTypes, final Map<String, Supplier<TypeTemplate>> blockEntityTypes
   ) {
      super.registerTypes(schema, entityTypes, blockEntityTypes);
      schema.registerType(true, References.DATA_COMPONENTS, () -> DSL.optionalFieldsLazy(components(schema)));
   }
}
