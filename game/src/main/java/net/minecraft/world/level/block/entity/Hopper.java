package net.minecraft.world.level.block.entity;

import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

public interface Hopper extends Container {
   AABB SUCK_AABB = Block.column(16.0, 11.0, 32.0).toAabbs().get(0);

   default AABB getSuckAabb() {
      // Eagler/web: interface static fields are subject to a TeaVM class-init-ORDER hazard — SUCK_AABB
      // can read its default (null) if the Hopper interface's <clinit> hasn't run when a hopper first
      // ticks, and HopperBlockEntity.getItemsAtAndAbove/entityInside then NPE on SUCK_AABB.move(...),
      // crashing the whole integrated server. Recompute the (constant) AABB if the field is null; on
      // desktop the field is always initialized so this returns it unchanged.
      AABB aabb = SUCK_AABB;
      return aabb != null ? aabb : Block.column(16.0, 11.0, 32.0).toAabbs().get(0);
   }

   double getLevelX();

   double getLevelY();

   double getLevelZ();

   boolean isGridAligned();
}
