package net.minecraft.client.renderer.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted;
import java.util.concurrent.Executor;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Util;
import org.slf4j.Logger;

public class SkinTextureDownloader {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int SKIN_WIDTH = 64;
   private static final int SKIN_HEIGHT = 64;
   private static final int LEGACY_SKIN_HEIGHT = 32;
   private final Proxy proxy;
   private final TextureManager textureManager;
   private final Executor mainThreadExecutor;

   public SkinTextureDownloader(final Proxy proxy, final TextureManager textureManager, final Executor mainThreadExecutor) {
      this.proxy = proxy;
      this.textureManager = textureManager;
      this.mainThreadExecutor = mainThreadExecutor;
   }

   public CompletableFuture<ClientAsset.Texture> downloadAndRegisterSkin(
      final Identifier textureId, final Path localCopy, final String url, final boolean processLegacySkin
   ) {
      ClientAsset.DownloadedTexture texture = new ClientAsset.DownloadedTexture(textureId, url);
      if (EaglerHosted.isBrowserRuntime()) {
         BrowserSkinDownload download = new BrowserSkinDownload(localCopy, texture.url());
         CompletableFuture<ClientAsset.Texture> result = download.result
            .thenApply(image -> processLegacySkin ? processLegacySkin(image, texture.url()) : image)
            .thenCompose(image -> this.registerTextureInManager(texture, image));
         synchronized (BROWSER_DOWNLOADS) {
            BROWSER_DOWNLOADS.addLast(download);
         }
         pumpBrowserDownloads();
         return result;
      }
      return CompletableFuture.<NativeImage>supplyAsync(() -> {
         NativeImage loadedSkin;
         try {
            loadedSkin = this.downloadSkin(localCopy, texture.url());
         } catch (IOException e) {
            throw new UncheckedIOException(e);
         }

         return processLegacySkin ? processLegacySkin(loadedSkin, texture.url()) : loadedSkin;
      }, Util.nonCriticalIoPool().forName("downloadTexture")).thenCompose(fixedSkin -> this.registerTextureInManager(texture, fixedSkin));
   }

   // Browser executors run inline. Use callbacks for network I/O and explicit
   // green threads for IndexedDB/cache/image work, never suspend the render caller.
   private static final int MAX_BROWSER_DOWNLOADS = 4;
   private static final ArrayDeque<BrowserSkinDownload> BROWSER_DOWNLOADS = new ArrayDeque<>();
   private static final ArrayDeque<BrowserSkinDownload> ACTIVE_BROWSER_DOWNLOADS = new ArrayDeque<>();
   private static final long BROWSER_DOWNLOAD_TIMEOUT_NANOS = 30_000_000_000L;
   private static boolean browserWatchdogRunning;

   private static void watchBrowserDownloads() {
      while (true) {
         BrowserSkinDownload[] active;
         synchronized (BROWSER_DOWNLOADS) {
            if (ACTIVE_BROWSER_DOWNLOADS.isEmpty()) {
               browserWatchdogRunning = false;
               return;
            }
            active = ACTIVE_BROWSER_DOWNLOADS.toArray(new BrowserSkinDownload[0]);
         }
         long now = System.nanoTime();
         for (BrowserSkinDownload request : active) {
            if (now - request.startedAt >= BROWSER_DOWNLOAD_TIMEOUT_NANOS) {
               request.finish(null, new IOException("Browser texture download timed out"));
            }
         }
         try {
            Thread.sleep(1000L);
         } catch (InterruptedException ignored) {
            // This single watchdog remains responsible for outstanding requests.
         }
      }
   }

   private static void pumpBrowserDownloads() {
      while (true) {
         BrowserSkinDownload next;
         boolean startWatchdog = false;
         synchronized (BROWSER_DOWNLOADS) {
            if (ACTIVE_BROWSER_DOWNLOADS.size() >= MAX_BROWSER_DOWNLOADS || BROWSER_DOWNLOADS.isEmpty()) {
               return;
            }
            next = BROWSER_DOWNLOADS.removeFirst();
            next.startedAt = System.nanoTime();
            ACTIVE_BROWSER_DOWNLOADS.addLast(next);
            if (!browserWatchdogRunning) {
               browserWatchdogRunning = true;
               startWatchdog = true;
            }
         }
         if (startWatchdog) {
            Thread watchdog = new Thread(SkinTextureDownloader::watchBrowserDownloads, "Eagler skin timeout");
            watchdog.setDaemon(true);
            watchdog.start();
         }
         next.startThread(next::start, "Eagler skin download");
      }
   }

   private static final class BrowserSkinDownload {
      final CompletableFuture<NativeImage> result = new CompletableFuture<>();
      final Path localCopy;
      final String url;
      final AtomicBoolean callbackClaimed = new AtomicBoolean();
      final AtomicBoolean finished = new AtomicBoolean();
      volatile long startedAt;

      BrowserSkinDownload(Path localCopy, String url) {
         this.localCopy = localCopy;
         this.url = url;
      }

      void startThread(Runnable action, String name) {
         try {
            new Thread(() -> {
               try {
                  action.run();
               } catch (Throwable error) {
                  finish(null, error);
               }
            }, name).start();
         } catch (Throwable error) {
            finish(null, error);
         }
      }

      void start() {
         try {
            if (Files.isRegularFile(this.localCopy)) {
               try (InputStream input = Files.newInputStream(this.localCopy)) {
                  finish(NativeImage.read(input), null);
               }
               return;
            }
            EaglerHosted.AsyncByteDownloader downloader = EaglerHosted.webDownloadBytesAsync;
            if (downloader == null) {
               throw new IOException("Browser texture downloader is not initialized");
            }
            downloader.download(this.url, bytes -> {
               if (this.callbackClaimed.compareAndSet(false, true) && !this.finished.get()) {
                  // Fetch callbacks cannot directly suspend into IndexedDB/NativeImage.
                  startThread(() -> store(bytes), "Eagler skin store");
               }
            });
         } catch (Throwable error) {
            // An implementation that calls back then throws has already handed off.
            if (this.callbackClaimed.compareAndSet(false, true)) {
               finish(null, error);
            }
         }
      }

      void store(byte[] bytes) {
         try {
            if (bytes == null) {
               throw new IOException("Browser texture download returned no data");
            }
            try {
               FileUtil.createDirectoriesSafe(this.localCopy.getParent());
               Files.write(this.localCopy, bytes);
            } catch (IOException error) {
               LOGGER.warn("Failed to cache texture {} in {}", this.url, this.localCopy);
            }
            finish(NativeImage.read(bytes), null);
         } catch (Throwable error) {
            finish(null, error);
         }
      }

      void finish(NativeImage image, Throwable error) {
         if (!this.finished.compareAndSet(false, true)) {
            if (image != null) image.close();
            return;
         }
         try {
            if (error != null) this.result.completeExceptionally(error);
            else this.result.complete(image);
         } finally {
            synchronized (BROWSER_DOWNLOADS) {
               ACTIVE_BROWSER_DOWNLOADS.remove(this);
            }
            pumpBrowserDownloads();
         }
      }
   }

   private NativeImage downloadSkin(final Path localCopy, final String url) throws IOException {
      if (Files.isRegularFile(localCopy)) {
         LOGGER.debug("Loading HTTP texture from local cache ({})", localCopy);

         try (InputStream inputStream = Files.newInputStream(localCopy)) {
            return NativeImage.read(inputStream);
         }
      } else {
         HttpURLConnection connection = null;
         LOGGER.debug("Downloading HTTP texture from {} to {}", url, localCopy);
         URI uri = URI.create(url);

         try {
            byte[] imageContents;
            connection = (HttpURLConnection)uri.toURL().openConnection(this.proxy);
            connection.setDoInput(true);
            connection.setDoOutput(false);
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode / 100 != 2) {
               throw new IOException("Failed to open " + uri + ", HTTP error code: " + responseCode);
            }
            imageContents = connection.getInputStream().readAllBytes();

            try {
               FileUtil.createDirectoriesSafe(localCopy.getParent());
               Files.write(localCopy, imageContents);
            } catch (IOException e) {
               LOGGER.warn("Failed to cache texture {} in {}", url, localCopy);
            }

            return NativeImage.read(imageContents);
         } finally {
            if (connection != null) {
               connection.disconnect();
            }
         }
      }
   }

   private CompletableFuture<ClientAsset.Texture> registerTextureInManager(final ClientAsset.Texture textureId, final NativeImage contents) {
      return CompletableFuture.supplyAsync(() -> {
         DynamicTexture texture = new DynamicTexture(textureId.texturePath()::toString, contents);
         this.textureManager.register(textureId.texturePath(), texture);
         return textureId;
      }, this.mainThreadExecutor);
   }

   private static NativeImage processLegacySkin(NativeImage image, final String url) {
      int height = image.getHeight();
      int width = image.getWidth();
      if (width == 64 && (height == 32 || height == 64)) {
         boolean isLegacy = height == 32;
         if (isLegacy) {
            NativeImage newImage = new NativeImage(64, 64, true);
            newImage.copyFrom(image);
            image.close();
            image = newImage;
            image.fillRect(0, 32, 64, 32, 0);
            image.copyRect(4, 16, 16, 32, 4, 4, true, false);
            image.copyRect(8, 16, 16, 32, 4, 4, true, false);
            image.copyRect(0, 20, 24, 32, 4, 12, true, false);
            image.copyRect(4, 20, 16, 32, 4, 12, true, false);
            image.copyRect(8, 20, 8, 32, 4, 12, true, false);
            image.copyRect(12, 20, 16, 32, 4, 12, true, false);
            image.copyRect(44, 16, -8, 32, 4, 4, true, false);
            image.copyRect(48, 16, -8, 32, 4, 4, true, false);
            image.copyRect(40, 20, 0, 32, 4, 12, true, false);
            image.copyRect(44, 20, -8, 32, 4, 12, true, false);
            image.copyRect(48, 20, -16, 32, 4, 12, true, false);
            image.copyRect(52, 20, -8, 32, 4, 12, true, false);
         }

         setNoAlpha(image, 0, 0, 32, 16);
         if (isLegacy) {
            doNotchTransparencyHack(image, 32, 0, 64, 32);
         }

         setNoAlpha(image, 0, 16, 64, 32);
         setNoAlpha(image, 16, 48, 48, 64);
         return image;
      } else {
         image.close();
         throw new IllegalStateException("Discarding incorrectly sized (" + width + "x" + height + ") skin texture from " + url);
      }
   }

   private static void doNotchTransparencyHack(final NativeImage image, final int x0, final int y0, final int x1, final int y1) {
      for (int x = x0; x < x1; x++) {
         for (int y = y0; y < y1; y++) {
            int pix = image.getPixel(x, y);
            if (ARGB.alpha(pix) < 128) {
               return;
            }
         }
      }

      for (int x = x0; x < x1; x++) {
         for (int y = y0; y < y1; y++) {
            image.setPixel(x, y, image.getPixel(x, y) & 16777215);
         }
      }
   }

   private static void setNoAlpha(final NativeImage image, final int x0, final int y0, final int x1, final int y1) {
      for (int x = x0; x < x1; x++) {
         for (int y = y0; y < y1; y++) {
            image.setPixel(x, y, ARGB.opaque(image.getPixel(x, y)));
         }
      }
   }
}
