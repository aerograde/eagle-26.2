package net.minecraft.server.packs;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.FileUtil;
import net.minecraft.util.Util;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class PathPackResources extends AbstractPackResources {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final Path root;

   public PathPackResources(final PackLocationInfo location, final Path root) {
      super(location);
      this.root = root;
   }

   @Override
   public @Nullable IoSupplier<InputStream> getRootResource(final String... path) {
      FileUtil.validatePath(path);
      Path pathInRoot = FileUtil.resolvePath(this.root, List.of(path));
      return Files.exists(pathInRoot) ? IoSupplier.create(pathInRoot) : null;
   }

   public static boolean validatePath(final Path path) {
      if (!SharedConstants.DEBUG_VALIDATE_RESOURCE_PATH_CASE) {
         return true;
      }

      if (path.getFileSystem() != FileSystems.getDefault()) {
         return true;
      }

      try {
         return path.toRealPath().endsWith(path);
      } catch (IOException e) {
         LOGGER.warn("Failed to resolve real path for {}", path, e);
         return false;
      }
   }

   private Path topPackDir(final PackType type) {
      return this.root.resolve(type.getDirectory());
   }

   @Override
   public @Nullable IoSupplier<InputStream> getResource(final PackType type, final Identifier location) {
      Path topDir = this.topPackDir(type);
      IoSupplier<InputStream> resource = getResource(topDir, location);
      if (resource != null) {
         return resource;
      }
      Identifier alias = legacyOptifineAlias(location);
      return alias == null ? null : getResource(topDir, alias);
   }

   private static @Nullable Identifier legacyOptifineAlias(final Identifier location) {
      String path = location.getPath();
      if (path.startsWith("optifine/")) {
         return Identifier.tryBuild(location.getNamespace(), "mcpatcher/" + path.substring("optifine/".length()));
      }
      if (path.startsWith("mcpatcher/")) {
         return Identifier.tryBuild(location.getNamespace(), "optifine/" + path.substring("mcpatcher/".length()));
      }
      return null;
   }

   public static @Nullable IoSupplier<InputStream> getResource(final Path topDir, final Identifier location) {
      Path namespaceDir = topDir.resolve(location.getNamespace());
      return (IoSupplier<InputStream>)FileUtil.decomposePath(location.getPath()).mapOrElse(decomposedPath -> {
         Path resolvedPath = FileUtil.resolvePath(namespaceDir, decomposedPath);
         return returnFileIfExists(resolvedPath);
      }, error -> {
         LOGGER.error("Invalid path {}: {}", location, error.message());
         return null;
      });
   }

   private static @Nullable IoSupplier<InputStream> returnFileIfExists(final Path resolvedPath) {
      return Files.exists(resolvedPath) && validatePath(resolvedPath) ? IoSupplier.create(resolvedPath) : null;
   }

   @Override
   public void listResources(final PackType type, final String namespace, final String directory, final PackResources.ResourceOutput output) {
      Path topDir = this.topPackDir(type);
      Set<Identifier> directResources = new HashSet<>();
      listResources(topDir, namespace, directory, (location, supplier) -> {
         directResources.add(location);
         output.accept(location, supplier);
      });
      String aliasDirectory = null;
      String sourcePrefix = null;
      String targetPrefix = null;
      if (directory.equals("optifine") || directory.startsWith("optifine/")) {
         aliasDirectory = "mcpatcher" + directory.substring("optifine".length());
         sourcePrefix = "mcpatcher/";
         targetPrefix = "optifine/";
      } else if (directory.equals("mcpatcher") || directory.startsWith("mcpatcher/")) {
         aliasDirectory = "optifine" + directory.substring("mcpatcher".length());
         sourcePrefix = "optifine/";
         targetPrefix = "mcpatcher/";
      }
      if (aliasDirectory != null) {
         final String from = sourcePrefix;
         final String to = targetPrefix;
         listResources(topDir, namespace, aliasDirectory, (location, supplier) -> {
            String path = location.getPath();
            if (path.startsWith(from)) {
               Identifier alias = Identifier.tryBuild(location.getNamespace(), to + path.substring(from.length()));
               if (alias != null && !directResources.contains(alias)) {
                  output.accept(alias, supplier);
               }
            }
         });
      }
   }

   public static void listResources(final Path topPath, final String namespace, final String directory, final PackResources.ResourceOutput output) {
      FileUtil.decomposePath(directory).ifSuccess(decomposedPath -> {
         Path namespaceDir = topPath.resolve(namespace);
         listPath(namespace, namespaceDir, decomposedPath, output);
      }).ifError(error -> LOGGER.error("Invalid path {}: {}", directory, error.message()));
   }

   public static void listPath(final String namespace, final Path topDir, final List<String> decomposedPrefixPath, final PackResources.ResourceOutput output) {
      Path targetPath = FileUtil.resolvePath(topDir, decomposedPrefixPath);

      // TeaVM 0.13.1's Files.find() returns a null stream for a non-matching
      // directory/leaf and then feeds it to Stream.concat(), which crashes a
      // browser resource reload in GenericConcatStream. Walking and filtering
      // is equivalent here and retains the desktop path semantics.
      try (Stream<Path> files = Files.walk(targetPath).filter(PathPackResources::isRegularFile)) {
         files.forEach(file -> {
            // TeaVM's default Path iterator can expose a null component for a
            // relative VFS path, which makes Guava Joiner throw during a browser
            // resource reload. Path.toString() already carries the complete
            // relative path; only normalize the desktop separator here.
            String resourcePath = topDir.relativize(file).toString().replace('\\', '/');
            Identifier identifier = Identifier.tryBuild(namespace, resourcePath);
            if (identifier == null) {
               Util.logAndPauseIfInIde(String.format(Locale.ROOT, "Invalid path in pack: %s:%s, ignoring", namespace, resourcePath));
            } else {
               output.accept(identifier, IoSupplier.create(file));
            }
         });
      } catch (NoSuchFileException | NotDirectoryException var10) {
      } catch (IOException e) {
         LOGGER.error("Failed to list path {}", targetPath, e);
      }
   }

   private static boolean isRegularFile(final Path file) {
      return !SharedConstants.IS_RUNNING_IN_IDE
         ? Files.isRegularFile(file)
         : Files.isRegularFile(file) && !StringUtils.equalsIgnoreCase(file.getFileName().toString(), ".ds_store");
   }

   @Override
   public Set<String> getNamespaces(final PackType type) {
      Path assetRoot = this.topPackDir(type);
      return getNamespaces(assetRoot);
   }

   public static Set<String> getNamespaces(final Path rootDir) {
      Set<String> namespaces = new HashSet<>();

      try (DirectoryStream<Path> directDirs = Files.newDirectoryStream(rootDir)) {
         for (Path directDir : directDirs) {
            if (!Files.isDirectory(directDir)) {
               LOGGER.warn("Non-directory entry {} found in namespace directory, rejecting", directDir);
            } else {
               String namespace = directDir.getFileName().toString();
               if (Identifier.isValidNamespace(namespace)) {
                  namespaces.add(namespace);
               } else {
                  LOGGER.warn("Non {} character in namespace {} in pack directory {}, ignoring", new Object[]{"[a-z0-9_.-]", namespace, rootDir});
               }
            }
         }
      } catch (NoSuchFileException | NotDirectoryException var8) {
      } catch (IOException e) {
         LOGGER.error("Failed to list path {}", rootDir, e);
      }

      return namespaces;
   }

   @Override
   public void close() {
   }

   public static class PathResourcesSupplier implements Pack.ResourcesSupplier {
      private final Path content;

      public PathResourcesSupplier(final Path content) {
         this.content = content;
      }

      @Override
      public PackResources openPrimary(final PackLocationInfo location) {
         return new PathPackResources(location, this.content);
      }

      @Override
      public PackResources openFull(final PackLocationInfo location, final Pack.Metadata metadata) {
         PackResources primary = this.openPrimary(location);
         List<String> overlays = metadata.overlays();
         if (overlays.isEmpty()) {
            return primary;
         }

         List<PackResources> overlayResources = new ArrayList<>(overlays.size());

         for (String overlay : overlays) {
            Path overlayRoot = this.content.resolve(overlay);
            overlayResources.add(new PathPackResources(location, overlayRoot));
         }

         return new CompositePackResources(primary, overlayResources);
      }
   }
}
