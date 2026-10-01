package net.minecraft.util.filefix.access;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FileAccessProvider implements AutoCloseable {
   private final List<FileAccess<?>> accessedFiles = new ArrayList<>();
   private final ThreadLocal<Path> baseDirectory = new ThreadLocal<>();
   private final int dataVersion;
   private boolean frozen = false;

   public FileAccessProvider(final int dataVersion) {
      this.dataVersion = dataVersion;
   }

   public <T extends AutoCloseable> FileAccess<T> getFileAccess(final FileResourceType<T> type, final FileRelation fileRelation) {
      if (this.frozen) {
         throw new IllegalStateException("Cannot request new file access here.");
      }

      FileAccess<T> fileAccess = new FileAccess<>(this, type, fileRelation);
      this.accessedFiles.add(fileAccess);
      return fileAccess;
   }

   public void freeze() {
      this.frozen = true;
   }

   public Path baseDirectory() {
      Path path = this.baseDirectory.get();
      if (path == null) {
         throw new IllegalStateException("No file-fix base directory is bound to this thread");
      }
      return path;
   }

   public void runWithBaseDirectory(final Path path, final Runnable action) {
      Path previous = this.baseDirectory.get();
      this.baseDirectory.set(path);
      try {
         action.run();
      } finally {
         if (previous == null) {
            this.baseDirectory.remove();
         } else {
            this.baseDirectory.set(previous);
         }
      }
   }

   public int dataVersion() {
      return this.dataVersion;
   }

   @Override
   public void close() {
      for (FileAccess<?> accessedFile : this.accessedFiles) {
         accessedFile.close();
      }
   }
}
