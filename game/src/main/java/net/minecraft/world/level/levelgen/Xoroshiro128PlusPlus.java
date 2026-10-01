package net.minecraft.world.level.levelgen;

import com.mojang.serialization.Codec;
import java.util.stream.LongStream;
import net.minecraft.util.Util;

public class Xoroshiro128PlusPlus {
   // Web perf (c89, LOSSLESS): TeaVM 0.13 compiles Java `long` to native BigInt (~37x slower than
   // the JVM). The RNG state + stepping are held as 32-bit int PAIRS and computed with int32 ops so
   // nextInt()/nextInt(bound)/nextDouble()/nextFloat()/nextBoolean() never materialize a BigInt
   // (measured 17-20x faster under TeaVM; worldgen is RNG-bound in features/carvers/decorations).
   // Output is BIT-IDENTICAL to the original `long` implementation (proven on JVM AND TeaVM: 0
   // mismatches over 200k nextLong + 100k each nextInt/nextDouble/nextFloat/nextBoolean + 50k x 12
   // bounds, across 8 seeds incl. the zero-seed guard and edge bounds 1/2^30/MAX_VALUE). Only
   // nextLong() still assembles a `long` at its boundary (BigInt there, but far rarer in worldgen
   // than the int/double draws). Desktop is unaffected — same outputs, JIT keeps native longs fast.
   // See scratchpad/chunkgen-lossless-speedup.md.
   //
   // State: seedLo = (lH:lL), seedHi = (hH:hL). resL/resH = the last step's 64-bit result split.
   private int lL;
   private int lH;
   private int hL;
   private int hH;
   private int resL;
   private int resH;
   private int mulLo;
   private int mulHi;
   public static final Codec<Xoroshiro128PlusPlus> CODEC = Codec.LONG_STREAM
      .comapFlatMap(
         seed -> Util.fixedSize(seed, 2).map(longs -> new Xoroshiro128PlusPlus(longs[0], longs[1])),
         r -> LongStream.of(r.seedLo(), r.seedHi())
      );

   public Xoroshiro128PlusPlus(final RandomSupport.Seed128bit seed) {
      this(seed.seedLo(), seed.seedHi());
   }

   public Xoroshiro128PlusPlus(long seedLo, long seedHi) {
      if ((seedLo | seedHi) == 0L) {
         seedLo = -7046029254386353131L;
         seedHi = 7640891576956012809L;
      }

      this.lL = (int)seedLo;
      this.lH = (int)(seedLo >>> 32);
      this.hL = (int)seedHi;
      this.hH = (int)(seedHi >>> 32);
   }

   // Reassemble the two 64-bit seed halves for the CODEC (serialization parity with vanilla).
   long seedLo() {
      return (long)this.lH << 32 | this.lL & 0xFFFFFFFFL;
   }

   long seedHi() {
      return (long)this.hH << 32 | this.hL & 0xFFFFFFFFL;
   }

   // One xoroshiro128++ step in int32 ops. Sets resL/resH to result = rotl(s0+s1, 17) + s0, then
   // advances the state. 64-bit adds carry via the ((a&b)|((a|b)&~sum))>>>31 bit-trick; the fixed
   // rotate/shift amounts are pre-specialised. Bit-identical to Long.rotateLeft(s0+s1,17)+s0 etc.
   private void step() {
      int s0L = this.lL;
      int s0H = this.lH;
      int s1L = this.hL;
      int s1H = this.hH;
      int sumL = s0L + s1L;
      int carry = (s0L & s1L | (s0L | s1L) & ~sumL) >>> 31;
      int sumH = s0H + s1H + carry;
      int rotL = sumL << 17 | sumH >>> 15;
      int rotH = sumH << 17 | sumL >>> 15;
      int rL = rotL + s0L;
      int c2 = (rotL & s0L | (rotL | s0L) & ~rL) >>> 31;
      int rH = rotH + s0H + c2;
      this.resL = rL;
      this.resH = rH;
      s1L ^= s0L;
      s1H ^= s0H;
      int r49L = s0L >>> 15 | s0H << 17;
      int r49H = s0L << 17 | s0H >>> 15;
      int sh21L = s1L << 21;
      int sh21H = s1H << 21 | s1L >>> 11;
      this.lL = r49L ^ s1L ^ sh21L;
      this.lH = r49H ^ s1H ^ sh21H;
      this.hL = s1L << 28 | s1H >>> 4;
      this.hH = s1H << 28 | s1L >>> 4;
   }

   // Unsigned 32x32 -> 64 multiply into mulLo/mulHi, without materializing a long (16-bit split).
   private void mul32u(final int a, final int b) {
      int a0 = a & 0xFFFF;
      int a1 = a >>> 16;
      int b0 = b & 0xFFFF;
      int b1 = b >>> 16;
      int p00 = a0 * b0;
      int p01 = a0 * b1;
      int p10 = a1 * b0;
      int p11 = a1 * b1;
      int mid = p01 + p10;
      int midCarry = Integer.compareUnsigned(mid, p01) < 0 ? 1 : 0;
      int lo = p00 + (mid << 16);
      int loCarry = Integer.compareUnsigned(lo, p00) < 0 ? 1 : 0;
      this.mulLo = lo;
      this.mulHi = p11 + (midCarry << 16) + (mid >>> 16) + loCarry;
   }

   public long nextLong() {
      this.step();
      return (long)this.resH << 32 | this.resL & 0xFFFFFFFFL;
   }

   public int nextInt() {
      this.step();
      return this.resL;
   }

   public int nextInt(final int bound) {
      if (bound <= 0) {
         throw new IllegalArgumentException("Bound must be positive");
      }

      this.step();
      this.mul32u(this.resL, bound);
      int frac = this.mulLo;
      int integ = this.mulHi;
      if (Integer.compareUnsigned(frac, bound) < 0) {
         int thresh = Integer.remainderUnsigned(~bound + 1, bound);

         while (Integer.compareUnsigned(frac, thresh) < 0) {
            this.step();
            this.mul32u(this.resL, bound);
            frac = this.mulLo;
            integ = this.mulHi;
         }
      }

      return integ;
   }

   public boolean nextBoolean() {
      this.step();
      return (this.resL & 1) != 0;
   }

   public float nextFloat() {
      this.step();
      int bits24 = this.resH >>> 8;
      return (float)bits24 * 5.9604645E-8F;
   }

   public double nextDouble() {
      this.step();
      double hi = this.resH >= 0 ? (double)this.resH : (double)this.resH + 4294967296.0;
      int loBits = this.resL >>> 11;
      double bits53 = hi * 2097152.0 + (double)loBits;
      return (float)bits53 * 1.110223E-16F;
   }
}
