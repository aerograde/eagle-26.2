package net.minecraft.server.packs;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.FileUtil;
import net.minecraft.util.HttpUtil;
import net.minecraft.util.Util;
import net.minecraft.util.eventlog.JsonEventLog;
import net.minecraft.util.thread.ConsecutiveExecutor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class DownloadQueue implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MAX_KEPT_PACKS = 20;
   private final Path cacheDir;
   private final JsonEventLog<DownloadQueue.LogEntry> eventLog;
   private final ConsecutiveExecutor tasks = new ConsecutiveExecutor(Util.nonCriticalIoPool(), "download-queue");

   public DownloadQueue(final Path cacheDir) throws IOException {
      this.cacheDir = cacheDir;
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isBrowserRuntime()) {
         // Eagler web: the IndexedDB-backed java.nio VFS stores downloaded packs,
         // but it has no FileChannel-backed JSON event log. Avoid only the event-log
         // and vacuum setup; download/cache paths remain active below.
         this.eventLog = null;
         return;
      }
      FileUtil.createDirectoriesSafe(cacheDir);
      this.eventLog = JsonEventLog.open(DownloadQueue.LogEntry.CODEC, cacheDir.resolve("log.json"));
      DownloadCacheCleaner.vacuumCacheDir(cacheDir, 20);
   }

   private DownloadQueue.BatchResult runDownload(final DownloadQueue.BatchConfig config, final Map<UUID, DownloadQueue.DownloadRequest> requests) {
      DownloadQueue.BatchResult result = new DownloadQueue.BatchResult();
      requests.forEach(
         (id, request) -> {
            Path targetDir = this.cacheDir.resolve(id.toString());
            Path downloadedFile = null;

            try {
               downloadedFile = HttpUtil.downloadFile(
                  targetDir, request.url, config.headers, config.hashFunction, request.hash, config.maxSize, config.proxy, config.listener
               );
               result.downloaded.put(id, downloadedFile);
            } catch (Exception e) {
               LOGGER.error("Failed to download {}", request.url, e);
               result.failed.add(id);
            }

            if (this.eventLog != null) {
               try {
                  this.eventLog.write(
                     new DownloadQueue.LogEntry(
                        id,
                        request.url.toString(),
                        Instant.now(),
                        Optional.ofNullable(request.hash).map(HashCode::toString),
                        downloadedFile != null ? this.getFileInfo(downloadedFile) : Either.left("download_failed")
                     )
                  );
               } catch (Exception e) {
                  LOGGER.error("Failed to log download of {}", request.url, e);
               }
            }
         }
      );
      return result;
   }

   private Either<String, DownloadQueue.FileInfoEntry> getFileInfo(final Path downloadedFile) {
      try {
         long size = Files.size(downloadedFile);
         Path relativePath = this.cacheDir.relativize(downloadedFile);
         return Either.right(new DownloadQueue.FileInfoEntry(relativePath.toString(), size));
      } catch (IOException e) {
         LOGGER.error("Failed to get file size of {}", downloadedFile, e);
         return Either.left("no_access");
      }
   }

   public CompletableFuture<DownloadQueue.BatchResult> downloadBatch(
      final DownloadQueue.BatchConfig config, final Map<UUID, DownloadQueue.DownloadRequest> requests
   ) {
      net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.AsyncByteDownloader browserDownloader =
         net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webDownloadBytesAsync;
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isBrowserRuntime()) {
         CompletableFuture<DownloadQueue.BatchResult> future = new CompletableFuture<>();
         if (browserDownloader == null) {
            LOGGER.error(
               "Browser resource-pack downloader is not initialized "
               + "(hostedProperty={}, activeSnapshot={}, syncDownloader={})",
               Boolean.getBoolean("eagler.hosted"),
               net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive(),
               net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.webDownloadBytes != null
            );
            DownloadQueue.BatchResult result = new DownloadQueue.BatchResult();
            result.failed.addAll(requests.keySet());
            future.complete(result);
            return future;
         }
         this.downloadBrowserRequest(
            config, new ArrayList<>(requests.entrySet()), 0, new DownloadQueue.BatchResult(), future, browserDownloader
         );
         return future;
      }
      return CompletableFuture.supplyAsync(() -> this.runDownload(config, requests), this.tasks::schedule);
   }

   private void downloadBrowserRequest(
      final DownloadQueue.BatchConfig config,
      final List<Map.Entry<UUID, DownloadQueue.DownloadRequest>> requests,
      final int index,
      final DownloadQueue.BatchResult result,
      final CompletableFuture<DownloadQueue.BatchResult> future,
      final net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.AsyncByteDownloader downloader
   ) {
      if (index >= requests.size()) {
         future.complete(result);
         return;
      }

      Map.Entry<UUID, DownloadQueue.DownloadRequest> entry = requests.get(index);
      UUID id = entry.getKey();
      DownloadQueue.DownloadRequest request = entry.getValue();
      config.listener.requestStart();
      try {
         Path targetDir = this.cacheDir.resolve(id.toString());
         // Every java.nio cache operation must start from a real TeaVM green
         // thread. This method can be reached directly from a Fetch rejection
         // callback while advancing a batch, where suspendable IndexedDB access
         // would otherwise corrupt the continuation stack. The lookup thread also
         // bounds cache-hit recursion: the next request only schedules a new thread.
         Thread lookupThread = new Thread(() -> {
            try {
               Path cachedFile = HttpUtil.findCachedFile(targetDir, config.hashFunction, request.hash);
               if (cachedFile != null) {
                  config.listener.requestFinished(true);
                  result.downloaded.put(id, cachedFile);
                  this.downloadBrowserRequest(config, requests, index + 1, result, future, downloader);
                  return;
               }
               downloader.download(request.url.toString(), bytes -> {
                  try {
                     if (bytes == null) {
                        config.listener.requestFailed(
                           "Download failed. Check the pack URL/network; web clients need HTTPS and CORS permission."
                        );
                     }
                     // A Fetch callback is not a suspendable Java green-thread frame.
                     // Schedule the IndexedDB-backed Files write on a real TeaVM thread.
                     Thread storeThread = new Thread(() -> {
                        try {
                           Path downloadedFile = HttpUtil.storeDownloadedFile(
                              targetDir, request.url, config.hashFunction, request.hash,
                              config.maxSize, config.listener, bytes
                           );
                           result.downloaded.put(id, downloadedFile);
                        } catch (Throwable t) {
                           LOGGER.error("Failed to download {}", request.url, t);
                           result.failed.add(id);
                        }
                        this.downloadBrowserRequest(config, requests, index + 1, result, future, downloader);
                     }, "Eagler resource-pack store");
                     storeThread.setDaemon(true);
                     storeThread.start();
                  } catch (Throwable t) {
                     LOGGER.error("Browser download queue rejected {}", request.url, t);
                     config.listener.requestFinished(false);
                     result.failed.add(id);
                     this.downloadBrowserRequest(config, requests, index + 1, result, future, downloader);
                  }
               });
            } catch (Throwable t) {
               LOGGER.error("Failed to start browser download {}", request.url, t);
               config.listener.requestFinished(false);
               result.failed.add(id);
               this.downloadBrowserRequest(config, requests, index + 1, result, future, downloader);
            }
         }, "Eagler resource-pack lookup");
         lookupThread.setDaemon(true);
         lookupThread.start();
      } catch (Throwable t) {
         LOGGER.error("Browser cache lookup queue rejected {}", request.url, t);
         config.listener.requestFinished(false);
         result.failed.add(id);
         this.downloadBrowserRequest(config, requests, index + 1, result, future, downloader);
      }
   }

   @Override
   public void close() throws IOException {
      this.tasks.close();
      if (this.eventLog != null) {
         this.eventLog.close();
      }
   }

   public record BatchConfig(HashFunction hashFunction, int maxSize, Map<String, String> headers, Proxy proxy, HttpUtil.DownloadProgressListener listener) {
   }

   public record BatchResult(Map<UUID, Path> downloaded, Set<UUID> failed) {
      public BatchResult() {
         this(new HashMap<>(), new HashSet<>());
      }
   }

   public record DownloadRequest(URL url, @Nullable HashCode hash) {
   }

   private record FileInfoEntry(String name, long size) {
      public static final Codec<DownloadQueue.FileInfoEntry> CODEC = RecordCodecBuilder.create(
         i -> i.group(
               Codec.STRING.fieldOf("name").forGetter(DownloadQueue.FileInfoEntry::name),
               Codec.LONG.fieldOf("size").forGetter(DownloadQueue.FileInfoEntry::size)
            )
            .apply(i, DownloadQueue.FileInfoEntry::new)
      );
   }

   private record LogEntry(UUID id, String url, Instant time, Optional<String> hash, Either<String, DownloadQueue.FileInfoEntry> errorOrFileInfo) {
      public static final Codec<DownloadQueue.LogEntry> CODEC = RecordCodecBuilder.create(
         i -> i.group(
               UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(DownloadQueue.LogEntry::id),
               Codec.STRING.fieldOf("url").forGetter(DownloadQueue.LogEntry::url),
               ExtraCodecs.INSTANT_ISO8601.fieldOf("time").forGetter(DownloadQueue.LogEntry::time),
               Codec.STRING.optionalFieldOf("hash").forGetter(DownloadQueue.LogEntry::hash),
               Codec.mapEither(Codec.STRING.fieldOf("error"), DownloadQueue.FileInfoEntry.CODEC.fieldOf("file"))
                  .forGetter(DownloadQueue.LogEntry::errorOrFileInfo)
            )
            .apply(i, DownloadQueue.LogEntry::new)
      );
   }
}
