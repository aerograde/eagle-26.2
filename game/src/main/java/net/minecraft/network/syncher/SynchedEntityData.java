package net.minecraft.network.syncher;

import com.mojang.logging.LogUtils;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.util.ClassTreeIdRegistry;
import org.apache.commons.lang3.ObjectUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class SynchedEntityData {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MAX_ID_VALUE = 254;
   private static final ClassTreeIdRegistry ID_REGISTRY = new ClassTreeIdRegistry();
   private final SyncedDataHolder entity;
   private final SynchedEntityData.DataItem<?>[] itemsById;
   private boolean isDirty;

   private SynchedEntityData(final SyncedDataHolder entity, final SynchedEntityData.DataItem<?>[] itemsById) {
      this.entity = entity;
      this.itemsById = itemsById;
   }

   public static <T> EntityDataAccessor<T> defineId(final Class<? extends SyncedDataHolder> clazz, final EntityDataSerializer<T> type) {
      if (LOGGER.isDebugEnabled()) {
         String callerClassName = Thread.currentThread().getStackTrace()[2].getClassName();
         if (!callerClassName.equals(clazz.getName())) {
            LOGGER.debug("defineId called for: {} from {}", new Object[]{clazz, callerClassName, new RuntimeException()});
         }
      }

      // TeaVM may initialize an entity subclass before its superclass. Vanilla's
      // ClassTreeIdRegistry assumes JVM superclass-first <clinit> order, so on web
      // that can allocate duplicate protocol ids. Compute the same vanilla base
      // id from the source-verified declaration layout instead. Desktop retains
      // the unmodified JVM algorithm.
      int id = EagRuntime.isTeaVM() ? defineIdTeaVM(clazz) : ID_REGISTRY.define(clazz);
      if (id > 254) {
         throw new IllegalArgumentException("Data value id is too big with " + id + "! (Max is 254)");
      } else {
         return type.createAccessor(id);
      }
   }

   private static final Map<Class<?>, Integer> EAGLER_LOCAL_DATA_IDS = new HashMap<>();

   private static int defineIdTeaVM(final Class<?> clazz) {
      int localId = EAGLER_LOCAL_DATA_IDS.getOrDefault(clazz, 0);
      int declared = declaredEntityDataCount(clazz.getName());
      if (localId >= declared) {
         throw new IllegalStateException("Eagler entity-data layout is stale for " + clazz.getName()
            + ": accessor " + localId + " of " + declared);
      }
      EAGLER_LOCAL_DATA_IDS.put(clazz, localId + 1);

      int baseId = 0;
      for (Class<?> c = clazz.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
         baseId += declaredEntityDataCount(c.getName());
      }
      return baseId + localId;
   }

   private static int dataCountTeaVM(final Class<?> clazz) {
      int count = 0;
      for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
         count += declaredEntityDataCount(c.getName());
      }
      return count;
   }

   /**
    * Number of SynchedEntityData.defineId declarations physically owned by each
    * entity class. This table is generated from the 229 declarations under
    * net.minecraft.world.entity; the stale-layout check above makes additions
    * fail loudly instead of silently changing multiplayer protocol ids.
    */
   private static int declaredEntityDataCount(final String className) {
      return switch (className) {
         case
            "net.minecraft.world.entity.Display$BlockDisplay",
            "net.minecraft.world.entity.ExperienceOrb",
            "net.minecraft.world.entity.Mob",
            "net.minecraft.world.entity.OminousItemSpawner",
            "net.minecraft.world.entity.ambient.Bat",
            "net.minecraft.world.entity.animal.armadillo.Armadillo",
            "net.minecraft.world.entity.animal.cow.MushroomCow",
            "net.minecraft.world.entity.animal.equine.AbstractChestedHorse",
            "net.minecraft.world.entity.animal.equine.AbstractHorse",
            "net.minecraft.world.entity.animal.equine.Horse",
            "net.minecraft.world.entity.animal.feline.Ocelot",
            "net.minecraft.world.entity.animal.fish.AbstractFish",
            "net.minecraft.world.entity.animal.fish.Pufferfish",
            "net.minecraft.world.entity.animal.fish.Salmon",
            "net.minecraft.world.entity.animal.fish.TropicalFish",
            "net.minecraft.world.entity.animal.frog.Tadpole",
            "net.minecraft.world.entity.animal.golem.IronGolem",
            "net.minecraft.world.entity.animal.golem.SnowGolem",
            "net.minecraft.world.entity.animal.nautilus.AbstractNautilus",
            "net.minecraft.world.entity.animal.nautilus.ZombieNautilus",
            "net.minecraft.world.entity.animal.parrot.Parrot",
            "net.minecraft.world.entity.animal.polarbear.PolarBear",
            "net.minecraft.world.entity.animal.rabbit.Rabbit",
            "net.minecraft.world.entity.animal.sheep.Sheep",
            "net.minecraft.world.entity.animal.squid.GlowSquid",
            "net.minecraft.world.entity.boss.enderdragon.EnderDragon",
            "net.minecraft.world.entity.decoration.HangingEntity",
            "net.minecraft.world.entity.decoration.painting.Painting",
            "net.minecraft.world.entity.item.FallingBlockEntity",
            "net.minecraft.world.entity.item.ItemEntity",
            "net.minecraft.world.entity.monster.Blaze",
            "net.minecraft.world.entity.monster.Ghast",
            "net.minecraft.world.entity.monster.Phantom",
            "net.minecraft.world.entity.monster.Vex",
            "net.minecraft.world.entity.monster.Witch",
            "net.minecraft.world.entity.monster.Zoglin",
            "net.minecraft.world.entity.monster.cubemob.AbstractCubeMob",
            "net.minecraft.world.entity.monster.hoglin.Hoglin",
            "net.minecraft.world.entity.monster.illager.Pillager",
            "net.minecraft.world.entity.monster.illager.SpellcasterIllager",
            "net.minecraft.world.entity.monster.piglin.AbstractPiglin",
            "net.minecraft.world.entity.monster.skeleton.Bogged",
            "net.minecraft.world.entity.monster.skeleton.Skeleton",
            "net.minecraft.world.entity.monster.spider.Spider",
            "net.minecraft.world.entity.monster.warden.Warden",
            "net.minecraft.world.entity.npc.villager.AbstractVillager",
            "net.minecraft.world.entity.projectile.EyeOfEnder",
            "net.minecraft.world.entity.projectile.arrow.Arrow",
            "net.minecraft.world.entity.projectile.hurtingprojectile.Fireball",
            "net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull",
            "net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile",
            "net.minecraft.world.entity.raid.Raider",
            "net.minecraft.world.entity.vehicle.minecart.MinecartFurnace"
            -> 1;
         case
            "net.minecraft.world.entity.AgeableMob",
            "net.minecraft.world.entity.Avatar",
            "net.minecraft.world.entity.Display$ItemDisplay",
            "net.minecraft.world.entity.TamableAnimal",
            "net.minecraft.world.entity.animal.allay.Allay",
            "net.minecraft.world.entity.animal.bee.Bee",
            "net.minecraft.world.entity.animal.camel.Camel",
            "net.minecraft.world.entity.animal.chicken.Chicken",
            "net.minecraft.world.entity.animal.cow.Cow",
            "net.minecraft.world.entity.animal.dolphin.Dolphin",
            "net.minecraft.world.entity.animal.equine.Llama",
            "net.minecraft.world.entity.animal.frog.Frog",
            "net.minecraft.world.entity.animal.golem.CopperGolem",
            "net.minecraft.world.entity.animal.happyghast.HappyGhast",
            "net.minecraft.world.entity.animal.sniffer.Sniffer",
            "net.minecraft.world.entity.animal.turtle.Turtle",
            "net.minecraft.world.entity.boss.enderdragon.EndCrystal",
            "net.minecraft.world.entity.decoration.ItemFrame",
            "net.minecraft.world.entity.item.PrimedTnt",
            "net.minecraft.world.entity.monster.Guardian",
            "net.minecraft.world.entity.monster.Strider",
            "net.minecraft.world.entity.monster.cubemob.SulfurCube",
            "net.minecraft.world.entity.npc.villager.Villager",
            "net.minecraft.world.entity.projectile.FishingHook",
            "net.minecraft.world.entity.projectile.arrow.ThrownTrident",
            "net.minecraft.world.entity.vehicle.minecart.AbstractMinecart",
            "net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock"
            -> 2;
         case
            "net.minecraft.world.entity.AreaEffectCloud",
            "net.minecraft.world.entity.Interaction",
            "net.minecraft.world.entity.animal.axolotl.Axolotl",
            "net.minecraft.world.entity.animal.goat.Goat",
            "net.minecraft.world.entity.animal.pig.Pig",
            "net.minecraft.world.entity.decoration.Mannequin",
            "net.minecraft.world.entity.monster.Creeper",
            "net.minecraft.world.entity.monster.EnderMan",
            "net.minecraft.world.entity.monster.Shulker",
            "net.minecraft.world.entity.monster.piglin.Piglin",
            "net.minecraft.world.entity.monster.zombie.Zombie",
            "net.minecraft.world.entity.monster.zombie.ZombieVillager",
            "net.minecraft.world.entity.projectile.FireworkRocketEntity",
            "net.minecraft.world.entity.projectile.arrow.AbstractArrow",
            "net.minecraft.world.entity.vehicle.VehicleEntity",
            "net.minecraft.world.entity.vehicle.boat.AbstractBoat"
            -> 3;
         case
            "net.minecraft.world.entity.animal.fox.Fox",
            "net.minecraft.world.entity.boss.wither.WitherBoss",
            "net.minecraft.world.entity.monster.creaking.Creaking",
            "net.minecraft.world.entity.player.Player"
            -> 4;
         case
            "net.minecraft.world.entity.Display$TextDisplay",
            "net.minecraft.world.entity.animal.feline.Cat",
            "net.minecraft.world.entity.animal.wolf.Wolf"
            -> 5;
         case "net.minecraft.world.entity.animal.panda.Panda" -> 6;
         case
            "net.minecraft.world.entity.LivingEntity",
            "net.minecraft.world.entity.decoration.ArmorStand"
            -> 7;
         case "net.minecraft.world.entity.Entity" -> 8;
         case "net.minecraft.world.entity.Display" -> 15;
         default -> 0;
      };
   }

   private <T> SynchedEntityData.DataItem<T> getItem(final EntityDataAccessor<T> accessor) {
      return (SynchedEntityData.DataItem<T>)this.itemsById[accessor.id()];
   }

   public <T> T get(final EntityDataAccessor<T> accessor) {
      return this.getItem(accessor).getValue();
   }

   public <T> void set(final EntityDataAccessor<T> accessor, final T value) {
      this.set(accessor, value, false);
   }

   public <T> void set(final EntityDataAccessor<T> accessor, final T value, final boolean forceDirty) {
      SynchedEntityData.DataItem<T> dataItem = this.getItem(accessor);
      if (forceDirty || ObjectUtils.notEqual(value, dataItem.getValue())) {
         dataItem.setValue(value);
         this.entity.onSyncedDataUpdated(accessor);
         dataItem.setDirty(true);
         this.isDirty = true;
      }
   }

   public boolean isDirty() {
      return this.isDirty;
   }

   public @Nullable List<SynchedEntityData.DataValue<?>> packDirty() {
      if (!this.isDirty) {
         return null;
      }

      this.isDirty = false;
      List<SynchedEntityData.DataValue<?>> result = new ArrayList<>();

      for (SynchedEntityData.DataItem<?> dataItem : this.itemsById) {
         if (dataItem.isDirty()) {
            dataItem.setDirty(false);
            result.add(dataItem.value());
         }
      }

      return result;
   }

   public @Nullable List<SynchedEntityData.DataValue<?>> getNonDefaultValues() {
      List<SynchedEntityData.DataValue<?>> result = null;

      for (SynchedEntityData.DataItem<?> dataItem : this.itemsById) {
         if (!dataItem.isSetToDefault()) {
            if (result == null) {
               result = new ArrayList<>();
            }

            result.add(dataItem.value());
         }
      }

      return result;
   }

   public void assignValues(final List<SynchedEntityData.DataValue<?>> items) {
      for (SynchedEntityData.DataValue<?> item : items) {
         SynchedEntityData.DataItem<?> dataItem = this.itemsById[item.id];
         this.assignValue(dataItem, item);
         this.entity.onSyncedDataUpdated(dataItem.getAccessor());
      }

      this.entity.onSyncedDataUpdated(items);
   }

   private <T> void assignValue(final SynchedEntityData.DataItem<T> dataItem, final SynchedEntityData.DataValue<?> item) {
      if (!Objects.equals(item.serializer(), dataItem.accessor.serializer())) {
         throw new IllegalStateException(
            String.format(
               Locale.ROOT,
               "Invalid entity data item type for field %d on entity %s: old=%s(%s), new=%s(%s)",
               dataItem.accessor.id(),
               this.entity,
               dataItem.value,
               dataItem.value.getClass(),
               item.value,
               item.value.getClass()
            )
         );
      }

      dataItem.setValue((T)item.value);
   }

   public static class Builder {
      private final SyncedDataHolder entity;
      private final SynchedEntityData.@Nullable DataItem<?>[] itemsById;

      public Builder(final SyncedDataHolder entity) {
         this.entity = entity;
         int count = EagRuntime.isTeaVM()
            ? SynchedEntityData.dataCountTeaVM(entity.getClass())
            : SynchedEntityData.ID_REGISTRY.getCount(entity.getClass());
         this.itemsById = new SynchedEntityData.DataItem[count];
      }

      public <T> SynchedEntityData.Builder define(final EntityDataAccessor<T> accessor, final T value) {
         int id = accessor.id();
         if (id >= this.itemsById.length) {
            throw new IllegalArgumentException("Data value id is too big with " + id + "! (Max is " + this.itemsById.length + ")");
         }

         if (this.itemsById[id] != null) {
            throw new IllegalArgumentException("Duplicate id value for " + id + "!");
         }

         if (EntityDataSerializers.getSerializedId(accessor.serializer()) < 0) {
            throw new IllegalArgumentException("Unregistered serializer " + accessor.serializer() + " for " + id + "!");
         }

         this.itemsById[accessor.id()] = new SynchedEntityData.DataItem<>(accessor, value);
         return this;
      }

      public SynchedEntityData build() {
         for (int i = 0; i < this.itemsById.length; i++) {
            if (this.itemsById[i] == null) {
               throw new IllegalStateException("Entity " + this.entity.getClass() + " has not defined synched data value " + i);
            }
         }

         return new SynchedEntityData(this.entity, this.itemsById);
      }
   }

   public static class DataItem<T> {
      private final EntityDataAccessor<T> accessor;
      private T value;
      private final T initialValue;
      private boolean dirty;

      public DataItem(final EntityDataAccessor<T> accessor, final T initialValue) {
         this.accessor = accessor;
         this.initialValue = initialValue;
         this.value = initialValue;
      }

      public EntityDataAccessor<T> getAccessor() {
         return this.accessor;
      }

      public void setValue(final T value) {
         this.value = value;
      }

      public T getValue() {
         return this.value;
      }

      public boolean isDirty() {
         return this.dirty;
      }

      public void setDirty(final boolean dirty) {
         this.dirty = dirty;
      }

      public boolean isSetToDefault() {
         return this.initialValue.equals(this.value);
      }

      public SynchedEntityData.DataValue<T> value() {
         return SynchedEntityData.DataValue.create(this.accessor, this.value);
      }
   }

   public record DataValue<T>(int id, EntityDataSerializer<T> serializer, T value) {
      public static <T> SynchedEntityData.DataValue<T> create(final EntityDataAccessor<T> accessor, final T value) {
         EntityDataSerializer<T> serializer = accessor.serializer();
         return new SynchedEntityData.DataValue<>(accessor.id(), serializer, serializer.copy(value));
      }

      public void write(final RegistryFriendlyByteBuf output) {
         int serializerId = EntityDataSerializers.getSerializedId(this.serializer);
         if (serializerId < 0) {
            throw new EncoderException("Unknown serializer type " + this.serializer);
         }

         output.writeByte(this.id);
         output.writeVarInt(serializerId);
         this.serializer.codec().encode(output, this.value);
      }

      public static SynchedEntityData.DataValue<?> read(final RegistryFriendlyByteBuf input, final int id) {
         int start = input.readerIndex();
         int type = input.readVarInt();
         EntityDataSerializer<?> serializer = EntityDataSerializers.getSerializer(type);
         if (serializer == null) {
            throw new DecoderException("Unknown entity data serializer " + type + " for field " + id + " at byte " + start);
         } else {
            try {
               return read(input, id, serializer);
            } catch (Exception failure) {
               // The outer packet codec only names set_entity_data. Keep the
               // failing field/serializer in its cause chain so a malformed
               // translated particle list can be distinguished from item or
               // component metadata without logging successful packet bodies.
               throw new DecoderException("Failed to decode entity data field " + id + " with serializer " + type
                  + " at byte " + start + " (consumed " + (input.readerIndex() - start)
                  + ", remaining " + input.readableBytes() + ")", failure);
            }
         }
      }

      private static <T> SynchedEntityData.DataValue<T> read(final RegistryFriendlyByteBuf input, final int id, final EntityDataSerializer<T> serializer) {
         return new SynchedEntityData.DataValue<>(id, serializer, serializer.codec().decode(input));
      }
   }
}
