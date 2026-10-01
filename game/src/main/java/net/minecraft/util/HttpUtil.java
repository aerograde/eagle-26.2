package net.minecraft.util;

import com.google.common.hash.Funnels;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import org.apache.commons.io.IOUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class HttpUtil {
   private static final Logger LOGGER = LogUtils.getLogger();

   private HttpUtil() {
   }

   public static Path downloadFile(
      final Path targetDir,
      final URL url,
      final Map<String, String> headers,
      final HashFunction hashFunction,
      final @Nullable HashCode requestedHash,
      final int maxSize,
      final Proxy proxy,
      final HttpUtil.DownloadProgressListener listener
   ) {
      HttpURLConnection connection = null;
      InputStream input = null;
      listener.requestStart();
      Path targetFile = requestedHash != null ? cachedFilePath(targetDir, requestedHash) : null;
      try {
         Path cachedFile = findCachedFile(targetDir, hashFunction, requestedHash);
         if (cachedFile != null) {
            listener.requestFinished(true);
            return cachedFile;
         }
      } catch (RuntimeException e) {
         listener.requestFinished(false);
         throw e;
      }

      try {
         long contentLength;
         java.util.function.Function<String, byte[]> webDownloader =
            net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webDownloadBytes;
         if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isBrowserRuntime()) {
            if (webDownloader == null) {
               throw new IOException("Browser HTTP downloader is not initialized");
            }
            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)
                  && !"data".equalsIgnoreCase(protocol) && !"blob".equalsIgnoreCase(protocol)) {
               throw new IOException("Unsupported browser download protocol: " + protocol);
            }
            byte[] downloaded = webDownloader.apply(url.toString());
            if (downloaded == null) {
               throw new IOException("Browser download failed (the server may not permit cross-origin requests)");
            }
            contentLength = downloaded.length;
            input = new ByteArrayInputStream(downloaded);
         } else {
            connection = (HttpURLConnection)url.openConnection(proxy);
            connection.setInstanceFollowRedirects(true);
            headers.forEach(connection::setRequestProperty);
            input = connection.getInputStream();
            contentLength = connection.getContentLengthLong();
         }
         OptionalLong size = contentLength != -1L ? OptionalLong.of(contentLength) : OptionalLong.empty();
         FileUtil.createDirectoriesSafe(targetDir);
         listener.downloadStart(size);
         if (size.isPresent() && size.getAsLong() > maxSize) {
            throw new IOException("Filesize is bigger than maximum allowed (file is " + size + ", limit is " + maxSize + ")");
         }

         if (targetFile != null) {
            HashCode actualHash = downloadAndHash(hashFunction, maxSize, listener, input, targetFile);
            if (!actualHash.equals(requestedHash)) {
               throw new IOException("Hash of downloaded file (" + actualHash + ") did not match requested (" + requestedHash + ")");
            }

            listener.requestFinished(true);
            return targetFile;
         } else {
            Path tmpPath = Files.createTempFile(targetDir, "download", ".tmp");

            try {
               HashCode actualHash = downloadAndHash(hashFunction, maxSize, listener, input, tmpPath);
               Path actualPath = cachedFilePath(targetDir, actualHash);
               if (!checkExistingFile(actualPath, hashFunction, actualHash)) {
                  Files.move(tmpPath, actualPath, StandardCopyOption.REPLACE_EXISTING);
               } else {
                  updateModificationTime(actualPath);
               }

               listener.requestFinished(true);
               return actualPath;
            } finally {
               Files.deleteIfExists(tmpPath);
            }
         }
      } catch (Throwable t) {
         if (connection != null) {
            InputStream error = connection.getErrorStream();
            if (error != null) {
               try {
                  LOGGER.error("HTTP response error: {}", IOUtils.toString(error, StandardCharsets.UTF_8));
               } catch (Exception e) {
                  LOGGER.error("Failed to read response from server");
               }
            }
         }

         listener.requestFinished(false);
         throw new IllegalStateException("Failed to download file " + url, t);
      } finally {
         IOUtils.closeQuietly(input);
      }
   }

   /**
    * Finish an asynchronously fetched browser download without touching
    * URLConnection. {@code requestStart()} is owned by the caller so the download
    * toast appears while Fetch is in flight.
    */
   public static Path storeDownloadedFile(
      final Path targetDir,
      final URL url,
      final HashFunction hashFunction,
      final @Nullable HashCode requestedHash,
      final int maxSize,
      final HttpUtil.DownloadProgressListener listener,
      final byte[] downloaded
   ) {
      Path targetFile = requestedHash != null ? cachedFilePath(targetDir, requestedHash) : null;
      try {
         if (downloaded == null) {
            throw new IOException("Browser download failed (HTTP error, CORS rejection, network failure, or cancellation)");
         }
         if (downloaded.length > maxSize) {
            throw new IOException("Filesize is bigger than maximum allowed (file is "
               + downloaded.length + ", limit is " + maxSize + ")");
         }
         if (targetFile != null && checkExistingFile(targetFile, hashFunction, requestedHash)) {
            LOGGER.info("Returning cached file since actual hash matches requested");
            listener.requestFinished(true);
            updateModificationTime(targetFile);
            return targetFile;
         }

         FileUtil.createDirectoriesSafe(targetDir);
         listener.downloadStart(OptionalLong.of(downloaded.length));
         try (InputStream input = new ByteArrayInputStream(downloaded)) {
            if (targetFile != null) {
               Files.deleteIfExists(targetFile);
               HashCode actualHash = downloadAndHash(hashFunction, maxSize, listener, input, targetFile);
               if (!actualHash.equals(requestedHash)) {
                  Files.deleteIfExists(targetFile);
                  throw new IOException("Hash of downloaded file (" + actualHash
                     + ") did not match requested (" + requestedHash + ")");
               }
               listener.requestFinished(true);
               return targetFile;
            }

            Path tmpPath = Files.createTempFile(targetDir, "download", ".tmp");
            try {
               HashCode actualHash = downloadAndHash(hashFunction, maxSize, listener, input, tmpPath);
               Path actualPath = cachedFilePath(targetDir, actualHash);
               if (!checkExistingFile(actualPath, hashFunction, actualHash)) {
                  Files.move(tmpPath, actualPath, StandardCopyOption.REPLACE_EXISTING);
               } else {
                  updateModificationTime(actualPath);
               }
               listener.requestFinished(true);
               return actualPath;
            } finally {
               Files.deleteIfExists(tmpPath);
            }
         }
      } catch (Throwable t) {
         listener.requestFinished(false);
         throw new IllegalStateException("Failed to store downloaded file " + url, t);
      }
   }

   /**
    * Resolve and validate a requested-hash cache entry before starting a download.
    * This is shared by the blocking desktop path and the browser Fetch path so a
    * browser page reload can reuse the IndexedDB-backed pack cache without issuing
    * another HTTP request.
    *
    * <p>The caller owns progress-listener start/finish events. A missing hash has no
    * stable cache key and returns {@code null}.</p>
    */
   public static @Nullable Path findCachedFile(
      final Path targetDir,
      final HashFunction hashFunction,
      final @Nullable HashCode requestedHash
   ) {
      if (requestedHash == null) {
         return null;
      }

      Path targetFile = cachedFilePath(targetDir, requestedHash);
      try {
         if (checkExistingFile(targetFile, hashFunction, requestedHash)) {
            LOGGER.info("Returning cached file since actual hash matches requested");
            updateModificationTime(targetFile);
            return targetFile;
         }
      } catch (IOException e) {
         LOGGER.warn("Failed to check cached file {}", targetFile, e);
      }

      try {
         LOGGER.warn("Existing file {} not found or had mismatched hash", targetFile);
         Files.deleteIfExists(targetFile);
      } catch (IOException e) {
         throw new UncheckedIOException("Failed to remove existing file " + targetFile, e);
      }
      return null;
   }

   private static void updateModificationTime(final Path targetFile) {
      try {
         Files.setLastModifiedTime(targetFile, FileTime.from(Instant.now()));
      } catch (IOException | UnsupportedOperationException e) {
         LOGGER.warn("Failed to update modification time of {}", targetFile, e);
      }
   }

   private static HashCode hashFile(final Path file, final HashFunction hashFunction) throws IOException {
      Hasher hasher = hashFunction.newHasher();

      try (
         OutputStream outputStream = Funnels.asOutputStream(hasher);
         InputStream fileInput = Files.newInputStream(file);
      ) {
         fileInput.transferTo(outputStream);
      }

      return hasher.hash();
   }

   private static boolean checkExistingFile(final Path file, final HashFunction hashFunction, final HashCode expectedHash) throws IOException {
      if (Files.exists(file)) {
         HashCode actualHash = hashFile(file, hashFunction);
         if (actualHash.equals(expectedHash)) {
            return true;
         }

         LOGGER.warn("Mismatched hash of file {}, expected {} but found {}", new Object[]{file, expectedHash, actualHash});
      }

      return false;
   }

   private static Path cachedFilePath(final Path targetDir, final HashCode requestedHash) {
      return targetDir.resolve(requestedHash.toString());
   }

   private static HashCode downloadAndHash(
      final HashFunction hashFunction, final int maxSize, final HttpUtil.DownloadProgressListener listener, final InputStream input, final Path downloadFile
   ) throws IOException {
      try (OutputStream output = Files.newOutputStream(downloadFile, StandardOpenOption.CREATE)) {
         Hasher hasher = hashFunction.newHasher();
         byte[] buffer = new byte[8196];
         long readSoFar = 0L;

         int read;
         while ((read = input.read(buffer)) >= 0) {
            readSoFar += read;
            listener.downloadedBytes(readSoFar);
            if (readSoFar > maxSize) {
               throw new IOException("Filesize was bigger than maximum allowed (got >= " + readSoFar + ", limit was " + maxSize + ")");
            }

            if (Thread.interrupted()) {
               LOGGER.error("INTERRUPTED");
               throw new IOException("Download interrupted");
            }

            output.write(buffer, 0, read);
            hasher.putBytes(buffer, 0, read);
            if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isBrowserRuntime()
                  && (readSoFar & 0x3FFFFL) < read) {
               net.lax1dude.eaglercraft.v1_8.EagUtils.sleep(0L);
            }
         }

         return hasher.hash();
      }
   }

   public static int getAvailablePort() {
      try (ServerSocket server = new ServerSocket(0)) {
         return server.getLocalPort();
      } catch (IOException ignored) {
         return 25564;
      }
   }

   public static boolean isPortAvailable(final int port) {
      if (port >= 0 && port <= 65535) {
         try (ServerSocket server = new ServerSocket(port)) {
            return server.getLocalPort() == port;
         } catch (IOException ignored) {
            return false;
         }
      } else {
         return false;
      }
   }

   public interface DownloadProgressListener {
      void requestStart();

      void downloadStart(OptionalLong sizeBytes);

      void downloadedBytes(long bytesSoFar);

      /** Optional detail shown when the transport fails before any bytes arrive. */
      default void requestFailed(String reason) {
      }

      void requestFinished(boolean success);
   }
}
