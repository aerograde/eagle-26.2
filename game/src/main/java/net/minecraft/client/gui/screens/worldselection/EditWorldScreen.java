package net.minecraft.client.gui.screens.worldselection;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.GenericWaitingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;

public class EditWorldScreen extends Screen {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Component NAME_LABEL = Component.translatable("selectWorld.enterName").withStyle(ChatFormatting.GRAY);
   private static final Component RESET_ICON_BUTTON = Component.translatable("selectWorld.edit.resetIcon");
   private static final Component FOLDER_BUTTON = Component.translatable("selectWorld.edit.openFolder");
   private static final Component BACKUP_BUTTON = Component.translatable("selectWorld.edit.backup");
   private static final Component BACKUP_FOLDER_BUTTON = Component.translatable("selectWorld.edit.backupFolder");
   private static final Component OPTIMIZE_BUTTON = Component.translatable("selectWorld.edit.optimize");
   private static final Component OPTIMIZE_TITLE = Component.translatable("optimizeWorld.confirm.title");
   private static final Component OPTIMIZE_DESCRIPTION = Component.translatable("optimizeWorld.confirm.description");
   private static final Component OPTIMIZE_CONFIRMATION = Component.translatable("optimizeWorld.confirm.proceed");
   private static final Component SAVE_BUTTON = Component.translatable("selectWorld.edit.save");
   private static final int DEFAULT_WIDTH = 200;
   private static final int VERTICAL_SPACING = 4;
   private static final int HALF_WIDTH = 98;
   private final LinearLayout layout = LinearLayout.vertical().spacing(5);
   private final BooleanConsumer callback;
   private final LevelStorageSource.LevelStorageAccess levelAccess;
   private final EditBox nameEdit;

   public static EditWorldScreen create(final Minecraft minecraft, final LevelStorageSource.LevelStorageAccess levelAccess, final BooleanConsumer callback) throws IOException {
      LevelSummary summary = levelAccess.fixAndGetSummary();
      return new EditWorldScreen(minecraft, levelAccess, summary.getLevelName(), callback);
   }

   private EditWorldScreen(
      final Minecraft minecraft, final LevelStorageSource.LevelStorageAccess levelAccess, final String name, final BooleanConsumer callback
   ) {
      super(Component.translatable("selectWorld.edit.title"));
      this.callback = callback;
      this.levelAccess = levelAccess;
      Font font = minecraft.font;
      this.layout.addChild(new SpacerElement(200, 20));
      this.layout.addChild(new StringWidget(NAME_LABEL, font));
      this.nameEdit = this.layout.addChild(new EditBox(font, 200, 20, NAME_LABEL));
      this.nameEdit.setValue(name);
      LinearLayout bottomButtonRow = LinearLayout.horizontal().spacing(4);
      Button renameButton = bottomButtonRow.addChild(Button.builder(SAVE_BUTTON, button -> this.onRename(this.nameEdit.getValue())).width(98).build());
      bottomButtonRow.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).width(98).build());
      this.nameEdit.setResponder(newName -> renameButton.active = !StringUtil.isBlank(newName));
      this.layout.addChild(Button.builder(RESET_ICON_BUTTON, button -> {
         if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
            // Eagler: the icon is a VFS blob in hosted mode
            net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSWorldStorage.deleteWorldIcon(levelAccess.getLevelId());
            button.active = false;
            return;
         }

         levelAccess.getIconFile().ifPresent(p -> FileUtils.deleteQuietly(p.toFile()));
         button.active = false;
      }).width(200).build()).active = net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()
         ? net.lax1dude.eaglercraft.v1_8.sp.server.EaglerVFSWorldStorage.hasWorldIcon(levelAccess.getLevelId())
         : levelAccess.getIconFile().filter(x$0 -> Files.isRegularFile(x$0)).isPresent();
      Button folderButton = this.layout
         .addChild(Button.builder(FOLDER_BUTTON, button -> Util.getPlatform().openPath(levelAccess.getLevelPath(LevelResource.ROOT))).width(200).build());
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
         // Eagler: hosted worlds have no real folder to open
         folderButton.active = false;
      }
      this.layout
         .addChild(
            Button.builder(BACKUP_BUTTON, button -> makeBackupAndShowToast(levelAccess).thenAcceptAsync(success -> this.callback.accept(!success), minecraft))
               .width(200)
               .build()
         );
      Button backupFolderButton = this.layout.addChild(Button.builder(BACKUP_FOLDER_BUTTON, button -> {
         LevelStorageSource levelSource = minecraft.getLevelSource();
         Path path = levelSource.getBackupPath();

         try {
            FileUtil.createDirectoriesSafe(path);
         } catch (IOException e) {
            throw new RuntimeException(e);
         }

         Util.getPlatform().openPath(path);
      }).width(200).build());
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
         // Eagler: hosted backups are EPK downloads (downloads/), not the backups dir
         backupFolderButton.active = false;
      }
      Button optimizeButton = this.layout
         .addChild(
            Button.builder(
                  OPTIMIZE_BUTTON,
                  button -> minecraft.gui
                     .setScreen(
                        new BackupConfirmScreen(
                           () -> minecraft.gui.setScreen(this),
                           (backup, eraseCache) -> conditionallyMakeBackupAndShowToast(backup, levelAccess)
                              .thenAcceptAsync(
                                 var4x -> minecraft.gui
                                    .setScreen(OptimizeWorldScreen.create(minecraft, this.callback, minecraft.getFixerUpper(), levelAccess, eraseCache)),
                                 minecraft
                              ),
                           OPTIMIZE_TITLE,
                           OPTIMIZE_DESCRIPTION,
                           OPTIMIZE_CONFIRMATION,
                           true
                        )
                     )
               )
               .width(200)
               .build()
         );
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
         // Eagler: OptimizeWorldScreen (WorldUpgrader) walks real region paths — off hosted
         optimizeButton.active = false;
      }
      this.layout.addChild(new SpacerElement(200, 20));
      this.layout.addChild(bottomButtonRow);
      this.layout.visitWidgets(x$0 -> this.addRenderableWidget(x$0));
   }

   @Override
   protected void setInitialFocus() {
      this.setInitialFocus(this.nameEdit);
   }

   @Override
   protected void init() {
      this.repositionElements();
   }

   @Override
   protected void repositionElements() {
      this.layout.arrangeElements();
      FrameLayout.centerInRectangle(this.layout, this.getRectangle());
   }

   @Override
   public boolean keyPressed(final KeyEvent event) {
      if (this.nameEdit.isFocused() && event.isConfirmation()) {
         this.onRename(this.nameEdit.getValue());
         this.onClose();
         return true;
      } else {
         return super.keyPressed(event);
      }
   }

   @Override
   public void onClose() {
      this.callback.accept(false);
   }

   private void onRename(final String newName) {
      try {
         this.levelAccess.renameLevel(newName);
      } catch (IOException | NbtException | ReportedNbtException e) {
         LOGGER.error("Failed to access world '{}'", this.levelAccess.getLevelId(), e);
         SystemToast.onWorldAccessFailure(this.minecraft, this.levelAccess.getLevelId());
      }

      this.callback.accept(true);
   }

   public static CompletableFuture<Boolean> conditionallyMakeBackupAndShowToast(final boolean createBackup, final LevelStorageSource.LevelStorageAccess access) {
      return createBackup ? makeBackupAndShowToast(access) : CompletableFuture.completedFuture(false);
   }

   public static CompletableFuture<Boolean> makeBackupAndShowToast(final LevelStorageSource.LevelStorageAccess access) {
      Minecraft minecraft = Minecraft.getInstance();
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
         // Eagler: VFS worlds back up as an EPK download instead of a zip in backups/
         // (LevelStorageAccess.makeWorldBackup stays a warn+0 stub, this path bypasses it)
         return eaglerMakeEpkBackupAndShowToast(minecraft, access);
      }

      minecraft.setScreenAndShow(
         GenericWaitingScreen.createWaitingWithoutButton(
            Component.translatable("selectWorld.waitingForBackup.title"),
            Component.translatable("selectWorld.waitingForBackup.message").withStyle(ChatFormatting.GRAY)
         )
      );
      return CompletableFuture.<Long>supplyAsync(() -> {
         try {
            return access.makeWorldBackup();
         } catch (IOException e) {
            throw new RuntimeException(e);
         }
      }, Util.backgroundExecutor()).thenApplyAsync(size -> {
         Component title = Component.translatable("selectWorld.edit.backupCreated", access.getLevelId());
         Component message = Component.translatable("selectWorld.edit.backupSize", Mth.ceil(size.longValue() / 1048576.0));
         minecraft.gui.toastManager().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_BACKUP, title, message));
         return true;
      }, minecraft).exceptionallyAsync(exception -> {
         Component title = Component.translatable("selectWorld.edit.backupFailed");
         Component message = Component.literal(exception.getMessage());
         minecraft.gui.toastManager().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_BACKUP, title, message));
         return false;
      }, minecraft);
   }

   // Eagler: hosted backup path — pack worlds/<id>/ into an EPK on a background
   // thread behind the local progress screen, then hand it to the platform
   // download sink (desktop saves to downloads/)
   private static CompletableFuture<Boolean> eaglerMakeEpkBackupAndShowToast(
      final Minecraft minecraft, final LevelStorageSource.LevelStorageAccess access
   ) {
      net.lax1dude.eaglercraft.v1_8.sp.gui.EaglerLocalProgressScreen progressScreen =
         new net.lax1dude.eaglercraft.v1_8.sp.gui.EaglerLocalProgressScreen(
            Component.translatableWithFallback("singleplayer.busy.exporting.1", "Exporting world as EPK"));
      minecraft.setScreenAndShow(progressScreen);
      CompletableFuture<Boolean> future = new CompletableFuture<>();
      String levelId = access.getLevelId();
      String owner = net.lax1dude.eaglercraft.v1_8.sp.server.export.WorldConverterEPK26.getExportOwner();
      Thread exportThread = new Thread(() -> {
         try {
            byte[] epkBytes = net.lax1dude.eaglercraft.v1_8.sp.server.export.WorldConverterEPK26
               .exportWorld(levelId, owner, progressScreen);
            net.lax1dude.eaglercraft.v1_8.EagRuntime.downloadFileWithName(levelId + ".epk", epkBytes);
            minecraft.execute(() -> {
               Component title = Component.translatable("selectWorld.edit.backupCreated", levelId);
               Component message = Component.translatable("selectWorld.edit.backupSize", Mth.ceil(epkBytes.length / 1048576.0));
               minecraft.gui.toastManager().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_BACKUP, title, message));
               future.complete(true);
            });
         } catch (Throwable e) {
            LOGGER.error("Failed to export world {} as EPK", levelId, e);
            minecraft.execute(() -> {
               Component title = Component.translatable("selectWorld.edit.backupFailed");
               Component message = Component.literal(e.getMessage() == null ? e.toString() : e.getMessage());
               minecraft.gui.toastManager().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_BACKUP, title, message));
               future.complete(false);
            });
         }
      }, "EaglerWorldExport");
      exportThread.setDaemon(true);
      exportThread.start();
      return future;
   }

   @Override
   public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
      super.extractRenderState(graphics, mouseX, mouseY, a);
      graphics.centeredText(this.font, this.title, this.width / 2, 15, -1);
   }
}
