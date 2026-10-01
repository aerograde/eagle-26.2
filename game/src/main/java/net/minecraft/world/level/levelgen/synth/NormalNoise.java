package net.minecraft.world.level.levelgen.synth;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.doubles.DoubleListIterator;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jspecify.annotations.Nullable;

public class NormalNoise {
   private static final double INPUT_FACTOR = 1.0181268882175227;
   private static final double TARGET_DEVIATION = 0.3333333333333333;
   private final double valueFactor;
   private final PerlinNoise first;
   private final PerlinNoise second;
   private final double maxValue;
   private final NormalNoise.NoiseParameters parameters;
   /**
    * Eagler native-noise seam: index of this instance's slot in the native {@code rustnoise}
    * kernel, or -1 to use the pure-Java path. Set once in the constructor (behind a per-instance
    * parity gate) and read on every {@link #getValue}. On desktop {@code PlatformNativeNoise}
    * is a stub whose {@code available()} is false, so this is always -1 there.
    */
   private final int nativeSlot;
   // Fixed probe points (x,y,z triples, varied scales) for the parity gate — native output must
   // match the Java formula at all of these before a slot is trusted.
   private static final double[] NATIVE_PARITY_PROBES = {
      0.0, 0.0, 0.0,   0.5, 0.5, 0.5,   10.3, -5.7, 100.1,   -0.1, 0.2, -0.3,
      1234.5, 6.7, -890.1,   50000.5, 12.3, -4.5,   -333.3, 44.4, 55.5,   7.7, -7.7, 7.7
   };
   private static boolean nativeLogged;

   @Deprecated
   public static NormalNoise createLegacyNetherBiome(final RandomSource random, final NormalNoise.NoiseParameters parameters) {
      return new NormalNoise(random, parameters, false);
   }

   public static NormalNoise create(final RandomSource random, final int firstOctave, final double... amplitudes) {
      return create(random, new NormalNoise.NoiseParameters(firstOctave, new DoubleArrayList(amplitudes)));
   }

   public static NormalNoise create(final RandomSource random, final NormalNoise.NoiseParameters parameters) {
      return new NormalNoise(random, parameters, true);
   }

   private NormalNoise(final RandomSource random, final NormalNoise.NoiseParameters parameters, final boolean useNewInitialization) {
      int firstOctave = parameters.firstOctave;
      DoubleList amplitudes = parameters.amplitudes;
      this.parameters = parameters;
      if (useNewInitialization) {
         this.first = PerlinNoise.create(random, firstOctave, amplitudes);
         this.second = PerlinNoise.create(random, firstOctave, amplitudes);
      } else {
         this.first = PerlinNoise.createLegacyForLegacyNetherBiome(random, firstOctave, amplitudes);
         this.second = PerlinNoise.createLegacyForLegacyNetherBiome(random, firstOctave, amplitudes);
      }

      int minOctave = Integer.MAX_VALUE;
      int maxOctave = Integer.MIN_VALUE;
      DoubleListIterator iterator = amplitudes.iterator();

      while (iterator.hasNext()) {
         int i = iterator.nextIndex();
         double amplitude = iterator.nextDouble();
         if (amplitude != 0.0) {
            minOctave = Math.min(minOctave, i);
            maxOctave = Math.max(maxOctave, i);
         }
      }

      this.valueFactor = 0.16666666666666666 / expectedDeviation(maxOctave - minOctave);
      this.maxValue = (this.first.maxValue() + this.second.maxValue()) * this.valueFactor;
      this.nativeSlot = tryInstallNative();
   }

   /**
    * Eagler: if the native {@code rustnoise} kernel is present (web only), pack this NormalNoise's
    * two PerlinNoise octave stacks into a native slot and return its index — but ONLY after a
    * parity gate confirms the native output matches this instance's Java formula bit-closely at the
    * fixed probe points. Any wiring/layout mistake (or an exhausted slot pool, or {@code >32}
    * octaves) returns -1, leaving the pure-Java path in charge. So the native path can never ship
    * wrong terrain; worst case it is simply not used.
    */
   private int tryInstallNative() {
      if (!net.lax1dude.eaglercraft.v1_8.internal.PlatformNativeNoise.available()) {
         return -1;
      }

      int firstCount = this.first.countNativeOctaves();
      int secondCount = this.second.countNativeOctaves();
      if (firstCount + secondCount == 0 || firstCount + secondCount > 32) {
         return -1;
      }

      int slot = net.lax1dude.eaglercraft.v1_8.internal.PlatformNativeNoise.allocSlot();
      if (slot < 0) {
         return -1; // native slot pool exhausted this realm — stay on Java
      }

      net.lax1dude.eaglercraft.v1_8.internal.PlatformNativeNoise.writeHeader(
         slot, this.valueFactor, INPUT_FACTOR, firstCount, secondCount);
      this.first.writeNativeOctaves(slot, 0);
      this.second.writeNativeOctaves(slot, firstCount);

      double maxErr = 0.0;
      boolean exact = true;
      for (int i = 0; i < NATIVE_PARITY_PROBES.length; i += 3) {
         double x = NATIVE_PARITY_PROBES[i];
         double y = NATIVE_PARITY_PROBES[i + 1];
         double z = NATIVE_PARITY_PROBES[i + 2];
         double nativeVal = net.lax1dude.eaglercraft.v1_8.internal.PlatformNativeNoise.noiseSlot(slot, x, y, z);
         double javaVal = this.javaGetValue(x, y, z);
         double err = Math.abs(nativeVal - javaVal);
         exact &= Double.doubleToLongBits(nativeVal) == Double.doubleToLongBits(javaVal);
         if (err > maxErr) {
            maxErr = err;
         }
      }

      // Validate the packed TeaVM -> Rust -> TeaVM array bridge too. The scalar gate above
      // proves the slot layout; this second gate proves that batching preserves point order and
      // writes back into the Java output array. A browser/runtime integration mismatch therefore
      // disables only the native optimization, never changes generated terrain.
      if (exact
            && net.lax1dude.eaglercraft.v1_8.internal.PlatformNativeNoise.maxBatchPoints() >= 8) {
         double[] nativeBatch = new double[8];
         net.lax1dude.eaglercraft.v1_8.internal.PlatformNativeNoise.noiseBatch(
            slot, NATIVE_PARITY_PROBES, nativeBatch, 8);
         for (int i = 0; i < nativeBatch.length; ++i) {
            int coordinate = i * 3;
            double javaVal = this.javaGetValue(
               NATIVE_PARITY_PROBES[coordinate], NATIVE_PARITY_PROBES[coordinate + 1],
               NATIVE_PARITY_PROBES[coordinate + 2]);
            double err = Math.abs(nativeBatch[i] - javaVal);
            exact &= Double.doubleToLongBits(nativeBatch[i]) == Double.doubleToLongBits(javaVal);
            if (err > maxErr) {
               maxErr = err;
            }
         }
      }

      if (exact) {
         if (!nativeLogged) {
            nativeLogged = true;
            org.slf4j.LoggerFactory.getLogger("NormalNoise")
               .info("[rustnoise] native noise kernel enabled (parity maxErr={})", maxErr);
         }
         return slot;
      }

      if (!nativeLogged) {
         nativeLogged = true;
         org.slf4j.LoggerFactory.getLogger("NormalNoise")
            .warn("[rustnoise] native parity FAILED (maxErr={}) — using Java noise", maxErr);
      }
      return -1;
   }

   /** The pure-Java NormalNoise value — used by the parity gate and as the fallback path. */
   private double javaGetValue(final double x, final double y, final double z) {
      double x2 = x * 1.0181268882175227;
      double y2 = y * 1.0181268882175227;
      double z2 = z * 1.0181268882175227;
      return (this.first.getValue(x, y, z) + this.second.getValue(x2, y2, z2)) * this.valueFactor;
   }

   public double maxValue() {
      return this.maxValue;
   }

   private static double expectedDeviation(final int octaveSpan) {
      return 0.1 * (1.0 + 1.0 / (octaveSpan + 1));
   }

   public double getValue(final double x, final double y, final double z) {
      if (this.nativeSlot >= 0) {
         return net.lax1dude.eaglercraft.v1_8.internal.PlatformNativeNoise.noiseSlot(this.nativeSlot, x, y, z);
      }

      return this.javaGetValue(x, y, z);
   }

   public NormalNoise.NoiseParameters parameters() {
      return this.parameters;
   }

   @VisibleForTesting
   public void parityConfigString(final StringBuilder sb) {
      sb.append("NormalNoise {");
      sb.append("first: ");
      this.first.parityConfigString(sb);
      sb.append(", second: ");
      this.second.parityConfigString(sb);
      sb.append("}");
   }

   public record NoiseParameters(int firstOctave, DoubleList amplitudes) {
      public static final Codec<NormalNoise.NoiseParameters> DIRECT_CODEC = RecordCodecBuilder.create(
         i -> i.group(
               Codec.INT.fieldOf("firstOctave").forGetter(NormalNoise.NoiseParameters::firstOctave),
               Codec.DOUBLE.listOf().fieldOf("amplitudes").forGetter(NormalNoise.NoiseParameters::amplitudes)
            )
            .apply(i, NormalNoise.NoiseParameters::new)
      );
      public static final Codec<Holder<NormalNoise.NoiseParameters>> CODEC = RegistryFileCodec.create(Registries.NOISE, DIRECT_CODEC);

      public NoiseParameters(final int firstOctave, final List<Double> amplitudes) {
         this(firstOctave, new DoubleArrayList(amplitudes));
      }

      public NoiseParameters(final int firstOctave, final double firstAmplitude, final double... amplitudes) {
         this(firstOctave, Util.make(new DoubleArrayList(amplitudes), list -> list.add(0, firstAmplitude)));
      }
   }
}
