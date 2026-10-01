package net.minecraft.world.entity.boss.enderdragon.phases;

import java.util.Arrays;
import java.util.function.Function;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

public class EnderDragonPhase<T extends DragonPhaseInstance> {
   private static EnderDragonPhase<?>[] phases = new EnderDragonPhase[0];
   public static final EnderDragonPhase<DragonHoldingPatternPhase> HOLDING_PATTERN = create(DragonHoldingPatternPhase::new, "HoldingPattern");
   public static final EnderDragonPhase<DragonStrafePlayerPhase> STRAFE_PLAYER = create(DragonStrafePlayerPhase::new, "StrafePlayer");
   public static final EnderDragonPhase<DragonLandingApproachPhase> LANDING_APPROACH = create(DragonLandingApproachPhase::new, "LandingApproach");
   public static final EnderDragonPhase<DragonLandingPhase> LANDING = create(DragonLandingPhase::new, "Landing");
   public static final EnderDragonPhase<DragonTakeoffPhase> TAKEOFF = create(DragonTakeoffPhase::new, "Takeoff");
   public static final EnderDragonPhase<DragonSittingFlamingPhase> SITTING_FLAMING = create(DragonSittingFlamingPhase::new, "SittingFlaming");
   public static final EnderDragonPhase<DragonSittingScanningPhase> SITTING_SCANNING = create(DragonSittingScanningPhase::new, "SittingScanning");
   public static final EnderDragonPhase<DragonSittingAttackingPhase> SITTING_ATTACKING = create(DragonSittingAttackingPhase::new, "SittingAttacking");
   public static final EnderDragonPhase<DragonChargePlayerPhase> CHARGING_PLAYER = create(DragonChargePlayerPhase::new, "ChargingPlayer");
   public static final EnderDragonPhase<DragonDeathPhase> DYING = create(DragonDeathPhase::new, "Dying");
   public static final EnderDragonPhase<DragonHoverPhase> HOVERING = create(DragonHoverPhase::new, "Hover");
   private final Function<EnderDragon, ? extends DragonPhaseInstance> factory;
   private final int id;
   private final String name;

   private EnderDragonPhase(final int id, final Function<EnderDragon, ? extends DragonPhaseInstance> factory, final String name) {
      this.id = id;
      this.factory = factory;
      this.name = name;
   }

   public DragonPhaseInstance createInstance(final EnderDragon dragon) {
      return this.factory.apply(dragon);
   }

   public int getId() {
      return this.id;
   }

   @Override
   public String toString() {
      return this.name + " (#" + this.id + ")";
   }

   public static EnderDragonPhase<?> getById(final int id) {
      return id >= 0 && id < phases.length ? phases[id] : HOLDING_PATTERN;
   }

   public static int getCount() {
      return phases.length;
   }

   private static <T extends DragonPhaseInstance> EnderDragonPhase<T> create(final Function<EnderDragon, T> factory, final String name) {
      EnderDragonPhase<T> phase = new EnderDragonPhase<>(phases.length, factory, name);
      phases = Arrays.copyOf(phases, phases.length + 1);
      phases[phase.getId()] = phase;
      return phase;
   }
}
