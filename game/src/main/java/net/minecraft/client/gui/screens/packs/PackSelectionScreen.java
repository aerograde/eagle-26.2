package net.minecraft.client.gui.screens.packs;

import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.NoticeWithLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackDetector;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.FileUtil;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Util;
import net.minecraft.world.level.validation.ForbiddenSymlinkInfo;
import net.lax1dude.eaglercraft.v1_8.EagRuntime;
import net.lax1dude.eaglercraft.v1_8.Filesystem;
import net.lax1dude.eaglercraft.v1_8.internal.FileChooserResult;
import net.lax1dude.eaglercraft.v1_8.internal.IEaglerFilesystem;
import net.lax1dude.eaglercraft.v1_8.internal.PlatformRuntime;
import net.lax1dude.eaglercraft.v1_8.internal.buffer.ByteBuffer;
import net.lax1dude.eaglercraft.v1_8.minecraft.EaglerClientPerf;
import net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class PackSelectionScreen extends Screen {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Component AVAILABLE_TITLE = Component.translatable("pack.available.title");
   private static final Component SELECTED_TITLE = Component.translatable("pack.selected.title");
   private static final Component OPEN_PACK_FOLDER_TITLE = Component.translatable("pack.openFolder");
   private static final Component SEARCH = Component.translatable("gui.packSelection.search").withStyle(EditBox.SEARCH_HINT_STYLE);
   private static final int LIST_WIDTH = 200;
   private static final int HEADER_ELEMENT_SPACING = 4;
   private static final int SEARCH_BOX_HEIGHT = 15;
   private static final Component DRAG_AND_DROP = Component.translatable("pack.dropInfo").withStyle(ChatFormatting.GRAY);
   private static final Component DIRECTORY_BUTTON_TOOLTIP = Component.translatable("pack.folderInfo");
   private static final int RELOAD_COOLDOWN = 20;
   private static final int BROWSER_IMPORT_BATCH_FILES = 64;
   private static final long BROWSER_IMPORT_BATCH_BYTES = 8L * 1024L * 1024L;
   private static final Identifier DEFAULT_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png");
   private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
   private final PackSelectionModel model;
   private PackSelectionScreen.@Nullable Watcher watcher;
   private long ticksToReload;
   private @Nullable TransferableSelectionList availablePackList;
   private @Nullable TransferableSelectionList selectedPackList;
   private @Nullable EditBox search;
   private final Path packDir;
   private @Nullable Button importButton;
   private @Nullable Button doneButton;
   private @Nullable StringWidget browserStatus;
   private boolean waitingForBrowserImport;
   private volatile boolean browserImportInProgress;
   private volatile boolean browserOperationFinished;
   private volatile @Nullable Exception browserOperationFailure;
   private volatile @Nullable String browserOperationPackId;
   private volatile String browserOperationName = "";
   private volatile String browserOperationVerb = "Importing";
   private volatile int browserProgressFiles;
   private volatile int browserProgressTotalFiles = -1;
   private volatile long browserProgressBytes;
   private volatile long browserProgressTotalBytes = -1L;
   private boolean browserDiscoveryPending;
   private int browserDiscoveryRetries;
   private int browserDiscoveryCooldown;
   private int browserStatusTicks;
   private final Map<String, Identifier> packIcons = Maps.newHashMap();

   public PackSelectionScreen(final PackRepository repository, final Consumer<PackRepository> output, final Path packDir, final Component title) {
      super(title);
      this.model = new PackSelectionModel(this::populateLists, this::getPackIcon, repository, output);
      this.packDir = packDir;
      this.watcher = net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isBrowserRuntime()
         ? null
         : PackSelectionScreen.Watcher.create(packDir);
   }

   @Override
   public void onClose() {
      this.model.commit();
      this.closeWatcher();
   }

   private void closeWatcher() {
      if (this.watcher != null) {
         try {
            this.watcher.close();
            this.watcher = null;
         } catch (Exception var2) {
         }
      }
   }

   @Override
   protected void init() {
      boolean browser = EaglerHosted.isBrowserRuntime();
      this.layout.setHeaderHeight(4 + 9 + 4 + 9 + 4 + 15 + (browser ? 4 + 9 : 0) + 4);
      LinearLayout header = this.layout.addToHeader(LinearLayout.vertical().spacing(4));
      header.defaultCellSetting().alignHorizontallyCenter();
      header.addChild(new StringWidget(this.getTitle(), this.font));
      header.addChild(new StringWidget(DRAG_AND_DROP, this.font));
      this.search = header.addChild(new EditBox(this.font, 0, 0, 200, 15, Component.empty()));
      this.search.setHint(SEARCH);
      this.search.setResponder(this::updateFilteredEntries);
      if (browser) {
         this.browserStatus = header.addChild(
            new StringWidget(Component.translatableWithFallback("resourcePack.browser.ready", "Ready to import ZIP packs"), this.font)
               .setMaxWidth(Math.min(420, Math.max(120, this.width - 20)))
         );
      }
      this.availablePackList = this.layout.addToContents(new TransferableSelectionList(this.minecraft, this, 200, this.height - 66, AVAILABLE_TITLE));
      this.selectedPackList = this.layout.addToContents(new TransferableSelectionList(this.minecraft, this, 200, this.height - 66, SELECTED_TITLE));
      LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
      if (browser) {
         this.importButton = footer.addChild(Button.builder(Component.translatableWithFallback("resourcePack.browser.import", "Import Pack..."), button -> {
            this.waitingForBrowserImport = true;
            this.browserStatusTicks = 0;
            this.setBrowserStatus(Component.translatableWithFallback("resourcePack.browser.choose", "Choose a ZIP resource pack...").getString());
            EagRuntime.displayFileChooser(null, "zip");
         }).tooltip(Tooltip.create(Component.translatableWithFallback("resourcePack.browser.importTooltip",
            "Import a resource pack or compatible Eagler shader pack ZIP"))).build());
      } else {
         footer.addChild(
            Button.builder(OPEN_PACK_FOLDER_TITLE, button -> Util.getPlatform().openPath(this.packDir))
               .tooltip(Tooltip.create(DIRECTORY_BUTTON_TOOLTIP))
               .build()
         );
      }
      this.doneButton = footer.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).build());
      this.layout.visitWidgets(x$0 -> this.addRenderableWidget(x$0));
      this.repositionElements();
      this.reload();
   }

   @Override
   protected void setInitialFocus() {
      if (this.search != null) {
         this.setInitialFocus(this.search);
      } else {
         super.setInitialFocus();
      }
   }

   private void updateFilteredEntries(final String value) {
      this.updateFilteredEntries(value, null);
   }

   private void updateFilteredEntries(final String value, final PackSelectionModel.@Nullable EntryBase transferredEntry) {
      this.filterEntries(value, this.model.getSelected(), this.selectedPackList, transferredEntry);
      this.filterEntries(value, this.model.getUnselected(), this.availablePackList, transferredEntry);
   }

   private void filterEntries(
      final String value,
      final Stream<PackSelectionModel.Entry> oldEntries,
      final @Nullable TransferableSelectionList listToUpdate,
      final PackSelectionModel.@Nullable EntryBase transferredEntry
   ) {
      if (listToUpdate != null) {
         String lowerCaseValue = value.toLowerCase(Locale.ROOT);
         Stream<PackSelectionModel.Entry> filteredEntries = oldEntries.filter(
            packEntry -> value.isBlank()
               || packEntry.getId().toLowerCase(Locale.ROOT).contains(lowerCaseValue)
               || packEntry.getTitle().getString().toLowerCase(Locale.ROOT).contains(lowerCaseValue)
               || packEntry.getDescription().getString().toLowerCase(Locale.ROOT).contains(lowerCaseValue)
         );
         listToUpdate.updateList(filteredEntries, transferredEntry);
      }
   }

   @Override
   protected void repositionElements() {
      this.layout.arrangeElements();
      if (this.availablePackList != null) {
         this.availablePackList.updateSizeAndPosition(200, this.layout.getContentHeight(), this.width / 2 - 15 - 200, this.layout.getHeaderHeight());
      }

      if (this.selectedPackList != null) {
         this.selectedPackList.updateSizeAndPosition(200, this.layout.getContentHeight(), this.width / 2 + 15, this.layout.getHeaderHeight());
      }
   }

   @Override
   public void tick() {
      if (this.waitingForBrowserImport && EagRuntime.fileChooserIsReading()) {
         this.setBrowserStatus(formatReadProgress());
      }
      if (this.waitingForBrowserImport && EagRuntime.fileChooserHasResult()) {
         this.waitingForBrowserImport = false;
         FileChooserResult result = EagRuntime.getFileChooserResult();
         if (result != null) {
            this.importBrowserPack(result);
         } else {
            this.setBrowserStatus(Component.translatableWithFallback("resourcePack.browser.cancelled", "Import cancelled").getString());
            this.browserStatusTicks = 80;
         }
      }
      if (this.browserImportInProgress) {
         this.setBrowserStatus(formatOperationProgress());
      }
      if (this.browserOperationFinished) {
         this.finishBrowserOperation();
      }
      if (this.browserDiscoveryPending && this.browserDiscoveryCooldown > 0 && --this.browserDiscoveryCooldown == 0) {
         this.tryFinishBrowserImportDiscovery();
      }
      if (this.browserStatusTicks > 0 && --this.browserStatusTicks == 0 && !this.browserImportInProgress && !this.waitingForBrowserImport) {
         this.setBrowserStatus(Component.translatableWithFallback("resourcePack.browser.ready", "Ready to import ZIP packs").getString());
      }
      this.updateBrowserButtons();
      if (this.watcher != null) {
         try {
            if (this.watcher.pollForChanges()) {
               this.ticksToReload = 20L;
            }
         } catch (IOException e) {
            LOGGER.warn("Failed to poll for directory {} changes, stopping", this.packDir);
            this.closeWatcher();
         }
      }

      if (this.ticksToReload > 0L && --this.ticksToReload == 0L) {
         this.reload();
      }
   }

   private String formatReadProgress() {
      long read = EagRuntime.fileChooserBytesRead();
      long total = EagRuntime.fileChooserBytesTotal();
      String name = EagRuntime.fileChooserReadingName();
      if (name == null || name.isBlank()) {
         name = "pack.zip";
      }
      return (total > 0L
         ? Component.translatableWithFallback("resourcePack.browser.readingProgress", "Reading %s | %s/%s | %s%%",
            shortenStatusName(name), formatBytes(read), formatBytes(total), Math.min(100L, read * 100L / total))
         : Component.translatableWithFallback("resourcePack.browser.reading", "Reading %s | %s",
            shortenStatusName(name), formatBytes(read))).getString();
   }

   private Component operationVerbText() {
      return Component.translatableWithFallback(this.browserOperationVerb.equals("Deleting")
         ? "resourcePack.browser.deleting" : "resourcePack.browser.importing",
         this.browserOperationVerb.equals("Deleting") ? "Deleting" : "Importing");
   }

   private String formatOperationProgress() {
      int files = this.browserProgressFiles;
      int totalFiles = this.browserProgressTotalFiles;
      long bytes = this.browserProgressBytes;
      long totalBytes = this.browserProgressTotalBytes;
      StringBuilder message = new StringBuilder(operationVerbText().getString()).append(' ')
         .append(shortenStatusName(this.browserOperationName)).append(" | ");
      if (totalFiles > 0) {
         message.append(files).append('/').append(totalFiles);
      } else if (this.browserOperationVerb.equals("Deleting")) {
         message.append(Component.translatableWithFallback("resourcePack.browser.working", "working...").getString());
      } else {
         message.append(Component.translatableWithFallback("resourcePack.browser.files", "%s files", files).getString());
      }
      if (totalBytes > 0L) {
         message.append(" | ").append(formatBytes(bytes)).append('/').append(formatBytes(totalBytes));
         message.append(" | ").append(Math.min(100L, bytes * 100L / totalBytes)).append('%');
      } else if (bytes > 0L) {
         message.append(" | ").append(formatBytes(bytes));
      }
      return message.toString();
   }

   private static String shortenStatusName(final String name) {
      if (name == null || name.isBlank()) {
         return "pack.zip";
      }
      return name.length() <= 32 ? name : name.substring(0, 14) + "..." + name.substring(name.length() - 15);
   }

   private static String formatBytes(final long bytes) {
      if (bytes < 1024L) {
         return bytes + " B";
      }
      if (bytes < 1024L * 1024L) {
         return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
      }
      return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
   }

   private void setBrowserStatus(final String status) {
      if (this.browserStatus != null) {
         this.browserStatus.setMessage(Component.literal(status));
         // StringWidget changes its width with the message, but the header layout is
         // not rearranged on every progress tick. Recenter only this widget so a long
         // filename cannot inherit the old short-message x coordinate and run offscreen.
         this.browserStatus.setX((this.width - this.browserStatus.getWidth()) / 2);
      }
   }

   private void updateBrowserButtons() {
      boolean busy = this.browserImportInProgress || this.browserDiscoveryPending || EagRuntime.fileChooserIsReading();
      if (this.importButton != null) {
         this.importButton.active = !busy;
      }
      if (this.doneButton != null) {
         this.doneButton.active = !busy && this.selectedPackList != null && !this.selectedPackList.children().isEmpty();
      }
   }

   private void finishBrowserOperation() {
      this.browserOperationFinished = false;
      this.browserImportInProgress = false;
      Exception failure = this.browserOperationFailure;
      this.browserOperationFailure = null;
      if (failure == null) {
         if (this.browserOperationVerb.equals("Importing") && this.browserOperationPackId != null) {
            // IndexedDB commits can become visible to a fresh directory query a tick
            // after the write promise resolves. Verify discovery on this screen before
            // announcing success; this removes the close/reopen workaround without
            // triggering a heavyweight resource reload.
            this.browserDiscoveryPending = true;
            this.browserDiscoveryRetries = 8;
            this.browserDiscoveryCooldown = 0;
            this.tryFinishBrowserImportDiscovery();
            return;
         }
         this.reload();
         this.announceBrowserOperationSuccess();
      } else {
         this.failBrowserOperation(failure);
      }
      this.updateBrowserButtons();
   }

   private void tryFinishBrowserImportDiscovery() {
      this.reload();
      String packId = this.browserOperationPackId;
      if (packId != null && this.model.isPackAvailable(packId)) {
         this.browserDiscoveryPending = false;
         this.browserOperationPackId = null;
         this.announceBrowserOperationSuccess();
      } else if (--this.browserDiscoveryRetries <= 0) {
         this.browserDiscoveryPending = false;
         this.browserOperationPackId = null;
         this.failBrowserOperation(new IOException("Pack was stored but pack.mcmeta was not discovered"));
      } else {
         this.setBrowserStatus(Component.translatableWithFallback("resourcePack.browser.finishing",
            "Finishing import | checking pack metadata...").getString());
         this.browserDiscoveryCooldown = 5;
      }
      this.updateBrowserButtons();
   }

   private void announceBrowserOperationSuccess() {
      boolean deleting = this.browserOperationVerb.equals("Deleting");
      this.setBrowserStatus((deleting
         ? Component.translatableWithFallback("resourcePack.browser.deleted", "Pack deleted")
         : Component.translatableWithFallback("resourcePack.browser.imported", "Pack imported: %s",
            shortenStatusName(this.browserOperationName))).getString());
      this.browserStatusTicks = 120;
      if (EaglerClientPerf.isEnabled()) {
         LOGGER.info("[EagPerfClient] pack browser operation complete verb={} name={} packId={}",
            this.browserOperationVerb, this.browserOperationName, this.browserOperationPackId);
      }
      SystemToast.add(this.minecraft.gui.toastManager(), new SystemToast.SystemToastId(),
         deleting ? Component.translatableWithFallback("resourcePack.browser.deleted", "Pack deleted")
            : Component.translatableWithFallback("resourcePack.browser.importedTitle", "Pack imported"),
         Component.literal(this.browserOperationName));
   }

   private void failBrowserOperation(final Exception failure) {
      LOGGER.error("Browser resource-pack operation failed: {} {}", this.browserOperationVerb, this.browserOperationName, failure);
      String detail = rootCauseMessage(failure);
      this.setBrowserStatus(Component.translatableWithFallback("resourcePack.browser.operationFailed", "%s failed: %s",
         operationVerbText(), detail).getString());
      this.browserStatusTicks = 240;
      SystemToast.add(this.minecraft.gui.toastManager(), new SystemToast.SystemToastId(),
         Component.translatableWithFallback("resourcePack.browser.operationFailedTitle", "%s failed", operationVerbText()),
         Component.literal(detail));
   }

   private static String rootCauseMessage(final Throwable failure) {
      Throwable cause = failure;
      while (cause.getCause() != null && cause.getCause() != cause) {
         cause = cause.getCause();
      }
      String message = cause.getMessage();
      String result = cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
      return result.length() > 180 ? result.substring(0, 177) + "..." : result;
   }

   private void importBrowserPack(final FileChooserResult result) {
      byte[] data = result.fileData;
      String originalName = result.fileName == null ? "pack.zip" : result.fileName;
      if (data == null || data.length < 4 || data.length > 262144000
            || data[0] != 'P' || data[1] != 'K') {
         SystemToast.onPackCopyFailure(this.minecraft, "Select a valid ZIP pack (maximum 250 MiB)");
         return;
      }

      String safeName = originalName.replace('\\', '/');
      int slash = safeName.lastIndexOf('/');
      if (slash >= 0) {
         safeName = safeName.substring(slash + 1);
      }
      safeName = safeName.replaceAll("[^A-Za-z0-9._ -]", "_");
      if (safeName.isBlank()) {
         safeName = "pack.zip";
      } else if (!safeName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
         safeName += ".zip";
      }

      if (this.browserImportInProgress) {
         SystemToast.onPackCopyFailure(this.minecraft, "A pack import is already running");
         return;
      }

      final String importedName = safeName;
      final byte[] importedData = data;
      this.browserOperationVerb = "Importing";
      this.browserOperationName = importedName;
      this.browserProgressFiles = 0;
      this.browserProgressTotalFiles = -1;
      this.browserProgressBytes = 0L;
      this.browserProgressTotalBytes = -1L;
      this.browserOperationFailure = null;
      this.browserOperationPackId = null;
      this.browserOperationFinished = false;
      this.browserImportInProgress = true;
      Thread importThread = new Thread(() -> {
         Exception failure = null;
         IEaglerFilesystem browserFilesystem = null;
         try {
            if (EaglerHosted.isBrowserRuntime()) {
               browserFilesystem = Filesystem.getHandleFor(EagRuntime.getConfiguration().getWorldsDB());
            }
            FileUtil.createDirectoriesSafe(this.packDir);
            this.browserOperationPackId = this.unpackBrowserPack(importedData, this.packDir, importedName, browserFilesystem);
         } catch (Exception e) {
            failure = e;
         } finally {
            if (browserFilesystem != null) {
               browserFilesystem.closeHandle();
            }
         }
         this.browserOperationFailure = failure;
         this.browserOperationFinished = true;
      }, "Eagler pack import");
      importThread.setDaemon(true);
      importThread.start();
   }

   private String unpackBrowserPack(final byte[] zipData, final Path packDir, final String importedName,
         final @Nullable IEaglerFilesystem browserFilesystem) throws IOException {
      boolean perfEnabled = EaglerClientPerf.isEnabled();
      long started = perfEnabled ? EagRuntime.steadyTimeMillis() : 0L;
      String root = findPackRoot(zipData);
      ZipSummary summary = summarizeZip(zipData, root);
      this.browserProgressTotalFiles = summary.files;
      this.browserProgressTotalBytes = summary.bytes;
      long afterRoot = perfEnabled ? EagRuntime.steadyTimeMillis() : 0L;
      String folderName = importedName.substring(0, importedName.length() - 4).trim();
      if (folderName.isEmpty()) {
         folderName = "pack";
      }
      folderName += "-" + Integer.toHexString(java.util.Arrays.hashCode(zipData));
      Path destination = packDir.resolve(folderName);
      if (EaglerHosted.isBrowserRuntime()) {
         deleteBrowserDirectory(browserFilesystem, destination);
      } else if (Files.exists(destination)) {
         deleteDirectory(destination);
      }
      Files.createDirectories(destination);

      long unpackedBytes = 0L;
      int unpackedFiles = 0;
      boolean hasMetadata = false;
      JsonArray propertyFiles = new JsonArray();
      JsonArray potionFiles = new JsonArray();
      List<String> batchPaths = new ArrayList<>(BROWSER_IMPORT_BATCH_FILES);
      List<byte[]> batchData = new ArrayList<>(BROWSER_IMPORT_BATCH_FILES);
      long batchBytes = 0L;
      byte[] buffer = new byte[16384];
      try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipData))) {
         ZipEntry entry;
         while ((entry = zip.getNextEntry()) != null) {
            String name = safeZipPath(entry.getName());
            if (name == null || entry.isDirectory() || !name.startsWith(root)) {
               continue;
            }
            String relativeName = name.substring(root.length());
            if (relativeName.isEmpty()) {
               continue;
            }
            if (++unpackedFiles > 65536) {
               throw new IOException("Pack contains too many files");
            }

            long declaredSize = entry.getSize();
            int initialCapacity = declaredSize >= 0L && declaredSize <= 16777216L ? (int)declaredSize : 8192;
            ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
            int read;
            while ((read = zip.read(buffer)) != -1) {
               unpackedBytes += read;
               if (unpackedBytes > 536870912L) {
                  throw new IOException("Unpacked pack is larger than 512 MiB");
               }
               output.write(buffer, 0, read);
            }

            Path outputPath = destination.resolve(relativeName);
            byte[] outputBytes = output.toByteArray();
            if (EaglerHosted.isBrowserRuntime()) {
               batchPaths.add(outputPath.toString());
               batchData.add(outputBytes);
               batchBytes += outputBytes.length;
               if (batchPaths.size() >= BROWSER_IMPORT_BATCH_FILES || batchBytes >= BROWSER_IMPORT_BATCH_BYTES) {
                  flushBrowserBatch(browserFilesystem, batchPaths, batchData);
                  batchBytes = 0L;
               }
            } else {
               Path parent = outputPath.getParent();
               if (parent != null) {
                  Files.createDirectories(parent);
               }
               Files.write(outputPath, outputBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            this.browserProgressFiles = unpackedFiles;
            this.browserProgressBytes = unpackedBytes;
            if ("pack.mcmeta".equals(relativeName)) {
               hasMetadata = true;
            }
            String lowerName = relativeName.toLowerCase(Locale.ROOT);
            if (lowerName.startsWith("assets/minecraft/") && lowerName.endsWith(".properties")) {
               propertyFiles.add(relativeName.substring("assets/minecraft/".length()));
            }
            if ((lowerName.startsWith("assets/minecraft/mcpatcher/cit/potion/")
                  || lowerName.startsWith("assets/minecraft/optifine/cit/potion/"))
                  && lowerName.endsWith(".png")) {
               potionFiles.add(relativeName.substring("assets/minecraft/".length()));
            }
         }
         if (EaglerHosted.isBrowserRuntime()) {
            flushBrowserBatch(browserFilesystem, batchPaths, batchData);
         }
      } catch (IOException | RuntimeException ex) {
         cleanupDestination(browserFilesystem, destination);
         throw ex;
      }
      if (!hasMetadata) {
         cleanupDestination(browserFilesystem, destination);
         throw new IOException("Pack is missing pack.mcmeta");
      }
      if (!propertyFiles.isEmpty()) {
         JsonObject index = new JsonObject();
         index.add("propertyFiles", propertyFiles);
         writeCompatibilityIndex(destination.resolve("assets/minecraft/optifine/_property_files_index.json"), index,
            browserFilesystem);
      }
      if (!potionFiles.isEmpty()) {
         JsonObject index = new JsonObject();
         index.add("potionsFiles", potionFiles);
         writeCompatibilityIndex(destination.resolve("assets/minecraft/mcpatcher/cit/potion/_potions_files_index.json"), index,
            browserFilesystem);
      }
      if (perfEnabled) {
         long finished = EagRuntime.steadyTimeMillis();
         LOGGER.info("[EagPerfClient] pack import name={} compressed={}B files={} unpacked={}B rootScan={}ms extractAndStore={}ms total={}ms",
            importedName, zipData.length, unpackedFiles, unpackedBytes, afterRoot - started, finished - afterRoot, finished - started);
      }
      return "file/" + folderName;
   }

   private static void flushBrowserBatch(final @Nullable IEaglerFilesystem filesystem,
         final List<String> paths, final List<byte[]> data) {
      if (paths.isEmpty()) {
         return;
      }
      if (filesystem == null) {
         throw new IllegalStateException("Browser game filesystem is unavailable");
      }
      String[] normalizedPaths = new String[paths.size()];
      ByteBuffer[] buffers = new ByteBuffer[data.size()];
      try {
         for (int i = 0; i < paths.size(); ++i) {
            normalizedPaths[i] = normalizeBrowserPath(paths.get(i));
            ByteBuffer buffer = PlatformRuntime.castPrimitiveByteArray(data.get(i));
            if (buffer == null) {
               byte[] fileData = data.get(i);
               buffer = PlatformRuntime.allocateByteBuffer(fileData.length);
               buffer.put(fileData);
               buffer.flip();
            }
            buffers[i] = buffer;
         }
         filesystem.eaglerWriteBatch(normalizedPaths, buffers);
      } finally {
         for (ByteBuffer buffer : buffers) {
            if (buffer != null && PlatformRuntime.castNativeByteBuffer(buffer) == null) {
               PlatformRuntime.freeByteBuffer(buffer);
            }
         }
         paths.clear();
         data.clear();
      }
   }

   private static void cleanupDestination(final @Nullable IEaglerFilesystem browserFilesystem, final Path destination) {
      try {
         if (EaglerHosted.isBrowserRuntime()) {
            deleteBrowserDirectory(browserFilesystem, destination);
         } else {
            deleteDirectory(destination);
         }
      } catch (Exception ignored) {
      }
   }

   private static void deleteDirectory(final Path destination) throws IOException {
      if (!Files.exists(destination)) {
         return;
      }
      try (Stream<Path> files = Files.walk(destination)) {
         for (Path path : files.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
            Files.deleteIfExists(path);
         }
      }
   }

   private static void deleteBrowserDirectory(final @Nullable IEaglerFilesystem filesystem, final Path destination) {
      if (filesystem == null) {
         throw new IllegalStateException("Browser game filesystem is unavailable");
      }
      filesystem.eaglerDeleteRecursive(normalizeBrowserPath(destination.toString()));
   }

   private static String normalizeBrowserPath(final String path) {
      String normalized = path.replace('\\', '/');
      while (normalized.startsWith("/")) {
         normalized = normalized.substring(1);
      }
      // TeaVM's relative Path implementation renders game-directory paths with a
      // leading "./", while the mounted VFS and its directory markers use bare
      // store keys. Keeping the dot segment creates a second, invisible tree in
      // IndexedDB ("./resourcepacks/..."), so extraction succeeds but the pack
      // repository cannot discover pack.mcmeta. Canonicalize the direct batch
      // path exactly like EaglerVirtualFilesystem.normalize().
      while (normalized.startsWith("./")) {
         normalized = normalized.substring(2);
      }
      while (normalized.endsWith("/") && !normalized.isEmpty()) {
         normalized = normalized.substring(0, normalized.length() - 1);
      }
      return normalized;
   }

   private static ZipSummary summarizeZip(final byte[] zipData, final String root) {
      try {
         int eocd = -1;
         int lower = Math.max(0, zipData.length - 65557);
         for (int i = zipData.length - 22; i >= lower; --i) {
            if (readIntLE(zipData, i) == 0x06054B50) {
               eocd = i;
               break;
            }
         }
         if (eocd < 0) {
            return ZipSummary.UNKNOWN;
         }
         int entries = readUnsignedShortLE(zipData, eocd + 10);
         long offsetLong = readUnsignedIntLE(zipData, eocd + 16);
         if (entries == 0xFFFF || offsetLong > Integer.MAX_VALUE) {
            return ZipSummary.UNKNOWN;
         }
         int offset = (int)offsetLong;
         int files = 0;
         long bytes = 0L;
         for (int i = 0; i < entries; ++i) {
            if (offset < 0 || offset + 46 > zipData.length || readIntLE(zipData, offset) != 0x02014B50) {
               return ZipSummary.UNKNOWN;
            }
            int nameLength = readUnsignedShortLE(zipData, offset + 28);
            int extraLength = readUnsignedShortLE(zipData, offset + 30);
            int commentLength = readUnsignedShortLE(zipData, offset + 32);
            int next = offset + 46 + nameLength + extraLength + commentLength;
            if (nameLength < 0 || offset + 46 + nameLength > zipData.length || next > zipData.length) {
               return ZipSummary.UNKNOWN;
            }
            String name = safeZipPath(new String(zipData, offset + 46, nameLength, StandardCharsets.UTF_8));
            if (name != null && !name.endsWith("/") && name.startsWith(root) && name.length() > root.length()) {
               ++files;
               long size = readUnsignedIntLE(zipData, offset + 24);
               if (size == 0xFFFFFFFFL) {
                  return new ZipSummary(files, -1L);
               }
               bytes += size;
            }
            offset = next;
         }
         return new ZipSummary(files, bytes);
      } catch (RuntimeException ignored) {
         return ZipSummary.UNKNOWN;
      }
   }

   private static int readUnsignedShortLE(final byte[] data, final int offset) {
      return (data[offset] & 255) | (data[offset + 1] & 255) << 8;
   }

   private static int readIntLE(final byte[] data, final int offset) {
      return (data[offset] & 255) | (data[offset + 1] & 255) << 8 | (data[offset + 2] & 255) << 16 | data[offset + 3] << 24;
   }

   private static long readUnsignedIntLE(final byte[] data, final int offset) {
      return readIntLE(data, offset) & 0xFFFFFFFFL;
   }

   private static final class ZipSummary {
      static final ZipSummary UNKNOWN = new ZipSummary(-1, -1L);
      final int files;
      final long bytes;

      ZipSummary(final int files, final long bytes) {
         this.files = files;
         this.bytes = bytes;
      }
   }

   private static void writeCompatibilityIndex(final Path path, final JsonObject index,
         final @Nullable IEaglerFilesystem browserFilesystem) throws IOException {
      byte[] encoded = GsonHelper.toStableString(index).getBytes(StandardCharsets.UTF_8);
      if (EaglerHosted.isBrowserRuntime()) {
         if (browserFilesystem == null) {
            throw new IOException("Browser game filesystem is unavailable");
         }
         ByteBuffer buffer = PlatformRuntime.castPrimitiveByteArray(encoded);
         if (buffer == null) {
            buffer = PlatformRuntime.allocateByteBuffer(encoded.length);
            buffer.put(encoded);
            buffer.flip();
         }
         try {
            // The imported tree is already implicit in IndexedDB. Going back through
            // Files.createDirectories() here asks the mounted TeaVM VFS to discover
            // the entire freshly-written pack before the import thread can finish.
            // Write the small generated index through the same handle as the ZIP
            // batches instead; repository discovery then performs one cached scan.
            browserFilesystem.eaglerWrite(normalizeBrowserPath(path.toString()), buffer);
         } finally {
            if (PlatformRuntime.castNativeByteBuffer(buffer) == null) {
               PlatformRuntime.freeByteBuffer(buffer);
            }
         }
         return;
      }
      Files.createDirectories(path.getParent());
      Files.write(
         path,
         encoded,
         StandardOpenOption.CREATE,
         StandardOpenOption.TRUNCATE_EXISTING
      );
   }

   private static String findPackRoot(final byte[] zipData) throws IOException {
      String root = null;
      try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipData))) {
         ZipEntry entry;
         while ((entry = zip.getNextEntry()) != null) {
            String name = safeZipPath(entry.getName());
            if (name != null && (name.equals("pack.mcmeta") || name.endsWith("/pack.mcmeta"))) {
               String candidate = name.substring(0, name.length() - "pack.mcmeta".length());
               // No later entry can have a shorter root than the archive root.
               // Returning here avoids inflating the rest of a normal resource
               // pack once for discovery and then a second time for extraction.
               if (candidate.isEmpty()) {
                  return "";
               }
               if (root == null || candidate.length() < root.length()) {
                  root = candidate;
               }
            }
         }
      }
      if (root == null) {
         throw new IOException("Pack is missing pack.mcmeta");
      }
      return root;
   }

   private static @Nullable String safeZipPath(final String input) {
      if (input == null || input.indexOf(0) >= 0) {
         return null;
      }
      String name = input.replace('\\', '/');
      while (name.startsWith("/")) {
         name = name.substring(1);
      }
      StringBuilder output = new StringBuilder(name.length());
      for (String part : name.split("/")) {
         if (part.isEmpty() || ".".equals(part)) {
            continue;
         }
         if ("..".equals(part) || part.indexOf(':') >= 0) {
            return null;
         }
         if (!output.isEmpty()) {
            output.append('/');
         }
         output.append(part);
      }
      return output.toString();
   }

   private void populateLists(final PackSelectionModel.@Nullable EntryBase transferredEntry) {
      if (this.selectedPackList != null) {
         this.selectedPackList.updateList(this.model.getSelected(), transferredEntry);
      }

      if (this.availablePackList != null) {
         this.availablePackList.updateList(this.model.getUnselected(), transferredEntry);
      }

      if (this.search != null) {
         this.updateFilteredEntries(this.search.getValue(), transferredEntry);
      }

      if (this.doneButton != null) {
         this.doneButton.active = !this.browserImportInProgress && !this.browserDiscoveryPending && !this.selectedPackList.children().isEmpty();
      }
   }

   boolean canDeleteBrowserPack(final String packId) {
      if (!EaglerHosted.isBrowserRuntime() || this.browserImportInProgress || this.browserDiscoveryPending || packId == null || !packId.startsWith("file/")) {
         return false;
      }
      String folder = packId.substring(5);
      return !folder.isBlank() && folder.indexOf('/') < 0 && folder.indexOf('\\') < 0 && !folder.equals(".") && !folder.equals("..");
   }

   void requestDeleteBrowserPack(final String packId, final Component title) {
      if (!this.canDeleteBrowserPack(packId)) {
         return;
      }
      String folder = packId.substring(5);
      this.minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
         this.minecraft.gui.setScreen(this);
         if (confirmed) {
            this.startDeleteBrowserPack(folder, title.getString());
         }
      }, Component.translatableWithFallback("resourcePack.browser.deleteTitle", "Delete resource pack?"),
         Component.translatableWithFallback("resourcePack.browser.deleteMessage", "Delete %s from browser storage?", title),
         Component.translatableWithFallback("resourcePack.browser.delete", "Delete"), CommonComponents.GUI_CANCEL));
   }

	private void startDeleteBrowserPack(final String folder, final String displayName) {
      if (this.browserImportInProgress) {
         return;
      }
      this.browserOperationVerb = "Deleting";
      this.browserOperationName = displayName;
      this.browserProgressFiles = 0;
      this.browserProgressTotalFiles = -1;
      this.browserProgressBytes = 0L;
      this.browserProgressTotalBytes = -1L;
      this.browserOperationFailure = null;
      this.browserOperationPackId = null;
      this.browserOperationFinished = false;
      this.browserImportInProgress = true;
      Thread deleteThread = new Thread(() -> {
         Exception failure = null;
         IEaglerFilesystem browserFilesystem = null;
         try {
            browserFilesystem = Filesystem.getHandleFor(EagRuntime.getConfiguration().getWorldsDB());
            Path destination = this.packDir.resolve(folder);
            browserFilesystem.eaglerDeleteRecursive(normalizeBrowserPath(destination.toString()));
         } catch (Exception ex) {
            failure = ex;
         } finally {
            if (browserFilesystem != null) {
               browserFilesystem.closeHandle();
            }
         }
         this.browserOperationFailure = failure;
         this.browserOperationFinished = true;
      }, "Eagler pack delete");
      deleteThread.setDaemon(true);
      deleteThread.start();
   }

   private void reload() {
      this.model.findNewPacks();
      this.populateLists(null);
      this.ticksToReload = 0L;
   }

   protected static void copyPacks(final Minecraft minecraft, final List<Path> files, final Path targetDir) {
      MutableBoolean showErrorToast = new MutableBoolean();
      files.forEach(pack -> {
         try (Stream<Path> contents = Files.walk(pack)) {
            contents.forEach(path -> {
               try {
                  Util.copyBetweenDirs(pack.getParent(), targetDir, path);
               } catch (IOException e) {
                  LOGGER.warn("Failed to copy datapack file  from {} to {}", path, targetDir, e);
                  showErrorToast.setTrue();
               }
            });
         } catch (IOException e) {
            LOGGER.warn("Failed to copy datapack file from {} to {}", pack, targetDir);
            showErrorToast.setTrue();
         }
      });
      if (showErrorToast.isTrue()) {
         SystemToast.onPackCopyFailure(minecraft, targetDir.toString());
      }
   }

   @Override
   public void onFilesDrop(final List<Path> files) {
      String names = extractPackNames(files).collect(Collectors.joining(", "));
      this.minecraft
         .gui
         .setScreen(
            new ConfirmScreen(
               result -> {
                  if (result) {
                     List<Path> packCandidates = new ArrayList<>(files.size());
                     Set<Path> leftoverPacks = new HashSet<>(files);
                     PackDetector<Path> packDetector = new PackDetector<Path>(this.minecraft.directoryValidator()) {
                        protected Path createZipPack(final Path content) {
                           return content;
                        }

                        protected Path createDirectoryPack(final Path content) {
                           return content;
                        }
                     };
                     List<ForbiddenSymlinkInfo> issues = new ArrayList<>();

                     for (Path path : files) {
                        try {
                           Path candidate = packDetector.detectPackResources(path, issues);
                           if (candidate == null) {
                              LOGGER.warn("Path {} does not seem like pack", path);
                           } else {
                              packCandidates.add(candidate);
                              leftoverPacks.remove(candidate);
                           }
                        } catch (IOException e) {
                           LOGGER.warn("Failed to check {} for packs", path, e);
                        }
                     }

                     if (!issues.isEmpty()) {
                        this.minecraft.gui.setScreen(NoticeWithLinkScreen.createPackSymlinkWarningScreen(() -> this.minecraft.gui.setScreen(this)));
                        return;
                     }

                     if (!packCandidates.isEmpty()) {
                        copyPacks(this.minecraft, packCandidates, this.packDir);
                        this.reload();
                     }

                     if (!leftoverPacks.isEmpty()) {
                        String leftoverNames = extractPackNames(leftoverPacks).collect(Collectors.joining(", "));
                        this.minecraft
                           .gui
                           .setScreen(
                              new AlertScreen(
                                 () -> this.minecraft.gui.setScreen(this),
                                 Component.translatable("pack.dropRejected.title"),
                                 Component.translatable("pack.dropRejected.message", leftoverNames)
                              )
                           );
                        return;
                     }
                  }

                  this.minecraft.gui.setScreen(this);
               },
               Component.translatable("pack.dropConfirm"),
               Component.literal(names)
            )
         );
   }

   private static Stream<String> extractPackNames(final Collection<Path> files) {
      return files.stream().map(Path::getFileName).map(Path::toString);
   }

   private Identifier loadPackIcon(final TextureManager textureManager, final Pack pack) {
      try (PackResources packResources = pack.open()) {
         IoSupplier<InputStream> resource = packResources.getRootResource("pack.png");
         if (resource == null) {
            return DEFAULT_ICON;
         }

         String id = pack.getId();
         Identifier location = Identifier.withDefaultNamespace(
            "pack/" + Util.sanitizeName(id, Identifier::validPathChar) + "/" + Hashing.sha1().hashUnencodedChars(id) + "/icon"
         );

         try (InputStream stream = resource.get()) {
            NativeImage iconImage = NativeImage.read(stream);
            textureManager.register(location, new DynamicTexture(location::toString, iconImage));
            return location;
         }
      } catch (Exception e) {
         LOGGER.warn("Failed to load icon from pack {}", pack.getId(), e);
         return DEFAULT_ICON;
      }
   }

   private Identifier getPackIcon(final Pack pack) {
      return this.packIcons.computeIfAbsent(pack.getId(), s -> this.loadPackIcon(this.minecraft.getTextureManager(), pack));
   }

   private static class Watcher implements AutoCloseable {
      private final WatchService watcher;
      private final Path packPath;

      public Watcher(final Path packPath) throws IOException {
         this.packPath = packPath;
         this.watcher = packPath.getFileSystem().newWatchService();

         try {
            this.watchDir(packPath);

            try (DirectoryStream<Path> paths = Files.newDirectoryStream(packPath)) {
               for (Path path : paths) {
                  if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                     this.watchDir(path);
                  }
               }
            }
         } catch (Exception e) {
            this.watcher.close();
            throw e;
         }
      }

      public static PackSelectionScreen.@Nullable Watcher create(final Path packDir) {
         try {
            return new PackSelectionScreen.Watcher(packDir);
         } catch (IOException e) {
            PackSelectionScreen.LOGGER.warn("Failed to initialize pack directory {} monitoring", packDir, e);
            return null;
         }
      }

      private void watchDir(final Path packPath) throws IOException {
         packPath.register(this.watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
      }

      public boolean pollForChanges() throws IOException {
         boolean hasChanges = false;

         WatchKey key;
         while ((key = this.watcher.poll()) != null) {
            for (WatchEvent<?> watchEvent : key.pollEvents()) {
               hasChanges = true;
               if (key.watchable() == this.packPath && watchEvent.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                  Path newPath = this.packPath.resolve((Path)watchEvent.context());
                  if (Files.isDirectory(newPath, LinkOption.NOFOLLOW_LINKS)) {
                     this.watchDir(newPath);
                  }
               }
            }

            key.reset();
         }

         return hasChanges;
      }

      @Override
      public void close() throws IOException {
         this.watcher.close();
      }
   }
}
