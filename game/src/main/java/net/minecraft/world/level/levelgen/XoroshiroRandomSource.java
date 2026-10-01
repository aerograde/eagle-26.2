package net.minecraft.world.level.levelgen;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.serialization.Codec;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

public class XoroshiroRandomSource implements RandomSource {
   private static final float FLOAT_UNIT = 5.9604645E-8F;
   private static final double DOUBLE_UNIT = 1.110223E-16F;
   public static final Codec<XoroshiroRandomSource> CODEC = Xoroshiro128PlusPlus.CODEC
      .xmap(generator -> new XoroshiroRandomSource(generator), source -> source.randomNumberGenerator);
   private Xoroshiro128PlusPlus randomNumberGenerator;
   private final MarsagliaPolarGaussian gaussianSource = new MarsagliaPolarGaussian(this);

   public XoroshiroRandomSource(final long seed) {
      this.randomNumberGenerator = new Xoroshiro128PlusPlus(RandomSupport.upgradeSeedTo128bit(seed));
   }

   public XoroshiroRandomSource(final RandomSupport.Seed128bit seed) {
      this.randomNumberGenerator = new Xoroshiro128PlusPlus(seed);
   }

   public XoroshiroRandomSource(final long seedLo, final long seedHi) {
      this.randomNumberGenerator = new Xoroshiro128PlusPlus(seedLo, seedHi);
   }

   private XoroshiroRandomSource(final Xoroshiro128PlusPlus randomNumberGenerator) {
      this.randomNumberGenerator = randomNumberGenerator;
   }

   @Override
   public RandomSource fork() {
      return new XoroshiroRandomSource(this.randomNumberGenerator.nextLong(), this.randomNumberGenerator.nextLong());
   }

   @Override
   public PositionalRandomFactory forkPositional() {
      return new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(this.randomNumberGenerator.nextLong(), this.randomNumberGenerator.nextLong());
   }

   @Override
   public void setSeed(final long seed) {
      this.randomNumberGenerator = new Xoroshiro128PlusPlus(RandomSupport.upgradeSeedTo128bit(seed));
      this.gaussianSource.reset();
   }

   // Web perf (c89, LOSSLESS): route the int/double draws through Xoroshiro128PlusPlus's int-pair
   // core so they never touch a BigInt (17-20x faster on TeaVM). Bit-identical to the original
   // (int)nextLong() / nextBits(n)*UNIT forms — proven, see Xoroshiro128PlusPlus.
   @Override
   public int nextInt() {
      return this.randomNumberGenerator.nextInt();
   }

   @Override
   public int nextInt(final int bound) {
      return this.randomNumberGenerator.nextInt(bound);
   }

   @Override
   public long nextLong() {
      return this.randomNumberGenerator.nextLong();
   }

   @Override
   public boolean nextBoolean() {
      return this.randomNumberGenerator.nextBoolean();
   }

   @Override
   public float nextFloat() {
      return this.randomNumberGenerator.nextFloat();
   }

   @Override
   public double nextDouble() {
      return this.randomNumberGenerator.nextDouble();
   }

   @Override
   public double nextGaussian() {
      return this.gaussianSource.nextGaussian();
   }

   @Override
   public void consumeCount(final int rounds) {
      for (int i = 0; i < rounds; i++) {
         this.randomNumberGenerator.nextLong();
      }
   }

   public static class XoroshiroPositionalRandomFactory implements PositionalRandomFactory {
      private final long seedLo;
      private final long seedHi;

      public XoroshiroPositionalRandomFactory(final long seedLo, final long seedHi) {
         this.seedLo = seedLo;
         this.seedHi = seedHi;
      }

      @Override
      public RandomSource at(final int x, final int y, final int z) {
         long positionalSeed = Mth.getSeed(x, y, z);
         long randomSeed = positionalSeed ^ this.seedLo;
         return new XoroshiroRandomSource(randomSeed, this.seedHi);
      }

      @Override
      public RandomSource fromHashOf(final String name) {
         RandomSupport.Seed128bit seed = RandomSupport.seedFromHashOf(name);
         return new XoroshiroRandomSource(seed.xor(this.seedLo, this.seedHi));
      }

      @Override
      public RandomSource fromSeed(final long seed) {
         return new XoroshiroRandomSource(seed ^ this.seedLo, seed ^ this.seedHi);
      }

      @VisibleForTesting
      @Override
      public void parityConfigString(final StringBuilder sb) {
         sb.append("seedLo: ").append(this.seedLo).append(", seedHi: ").append(this.seedHi);
      }
   }
}
