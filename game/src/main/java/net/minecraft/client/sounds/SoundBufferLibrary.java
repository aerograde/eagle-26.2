package net.minecraft.client.sounds;

import com.google.common.collect.Maps;
import com.mojang.blaze3d.audio.SoundBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.Util;

public class SoundBufferLibrary {
   private final ResourceProvider resourceManager;
   private final Map<Identifier, CompletableFuture<SoundBuffer>> cache = Maps.newHashMap();
   private static final boolean IS_WEB =
      net.lax1dude.eaglercraft.v1_8.EagRuntime.getPlatformType()
         != net.lax1dude.eaglercraft.v1_8.internal.EnumPlatformType.DESKTOP;
   // dummy; unused because web SoundBuffers carry an already-decoded AL buffer
   private static final AudioFormat WEB_AUDIO_FORMAT = new AudioFormat(48000.0F, 16, 1, true, false);

   public SoundBufferLibrary(final ResourceProvider resourceProvider) {
      this.resourceManager = resourceProvider;
   }

   public CompletableFuture<SoundBuffer> getCompleteBuffer(final Identifier location) {
      return this.cache.computeIfAbsent(location, l -> {
         // Web: music ships as Opus, which the Vorbis-only JOrbis decoder can't read, so
         // route /music/ through the browser's native decodeAudioData. Everything else
         // (short effects, Vorbis) keeps the proven JOrbis path below, byte-identical.
         if (IS_WEB && (l.getPath().contains("music") || l.getPath().contains("records"))) {
            return decodeEncodedWeb(l);
         }
         return CompletableFuture.supplyAsync(() -> {
            try (
               InputStream is = this.resourceManager.open(l);
               FiniteAudioStream as = new JOrbisAudioStream(is);
            ) {
               ByteBuffer data = as.readAll();
               return new SoundBuffer(data, as.getFormat());
            } catch (IOException e) {
               throw new CompletionException(e);
            }
         }, Util.nonCriticalIoPool());
      });
   }

   // Web: read the encoded (Opus) bytes and let the browser decode them into an AudioBuffer
   // registered as an AL buffer; the returned future completes when decode resolves.
   private CompletableFuture<SoundBuffer> decodeEncodedWeb(final Identifier location) {
      final byte[] encoded;
      try (InputStream is = this.resourceManager.open(location)) {
         encoded = readAllBytes(is);
      } catch (IOException e) {
         CompletableFuture<SoundBuffer> failed = new CompletableFuture<>();
         failed.completeExceptionally(e);
         return failed;
      }
      final CompletableFuture<SoundBuffer> result = new CompletableFuture<>();
      net.lax1dude.eaglercraft.v1_8.internal.PlatformAudioDecode.decodeToAlBufferAsync(
         encoded,
         alBufferId -> result.complete(new SoundBuffer(alBufferId, WEB_AUDIO_FORMAT)),
         () -> result.completeExceptionally(new IOException("browser decodeAudioData failed for " + location))
      );
      return result;
   }

   private static byte[] readAllBytes(final InputStream is) throws IOException {
      java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(65536);
      byte[] buf = new byte[16384];
      int n;
      while ((n = is.read(buf)) >= 0) {
         bos.write(buf, 0, n);
      }
      return bos.toByteArray();
   }

   public CompletableFuture<AudioStream> getStream(final Identifier location, final boolean looping) {
      return CompletableFuture.supplyAsync(() -> {
         try {
            InputStream is = this.resourceManager.open(location);
            return looping ? new LoopingAudioStream(JOrbisAudioStream::new, is) : new JOrbisAudioStream(is);
         } catch (IOException e) {
            throw new CompletionException(e);
         }
      }, Util.nonCriticalIoPool());
   }

   public void clear() {
      this.cache.values().forEach(future -> future.thenAccept(SoundBuffer::discardAlBuffer));
      this.cache.clear();
   }

   public CompletableFuture<?> preload(final Collection<Sound> sounds) {
      return CompletableFuture.allOf(sounds.stream().map(sound -> this.getCompleteBuffer(sound.getPath())).toArray(CompletableFuture[]::new));
   }

   public void enumerate(final SoundBufferLibrary.DebugOutput debugOutput) {
      this.cache.forEach((id, bufferFuture) -> {
         SoundBuffer buffer = bufferFuture.getNow(null);
         if (buffer != null && buffer.isValid()) {
            debugOutput.accountBuffer(id, buffer.size(), buffer.format());
         }
      });
   }

   public interface DebugOutput {
      void accountBuffer(Identifier id, int size, AudioFormat format);

      class Counter implements SoundBufferLibrary.DebugOutput {
         private int totalCount;
         private long totalSize;

         @Override
         public void accountBuffer(final Identifier id, final int size, final AudioFormat format) {
            this.totalCount++;
            this.totalSize += size;
         }

         public int totalCount() {
            return this.totalCount;
         }

         public long totalSize() {
            return this.totalSize;
         }
      }
   }
}
