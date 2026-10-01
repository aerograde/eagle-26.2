package net.minecraft.world.entity.projectile;

public final class FireworkRocketBoostGuard {
   private FireworkRocketBoostGuard() {
   }

   static boolean allowsPrediction(final int clientLife, final int flightDuration) {
      return clientLife <= 10 * (1 + flightDuration) + 11;
   }
}
