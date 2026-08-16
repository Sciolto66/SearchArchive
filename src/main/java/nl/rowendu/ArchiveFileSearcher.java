package nl.rowendu;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ArchiveFileSearcher implements Searcher {
  private static final System.Logger LOGGER = System.getLogger(ArchiveFileSearcher.class.getName());
  private static final Set<String> SUPPORTED_EXTENSIONS =
      new HashSet<>(Arrays.asList(".zip", ".jar", ".ear", ".sar"));
  private static final String ARCHIVE_BANG = "!";
  private static final String ARCHIVE_ENTRY_SEPARATOR = "/";
  private static final String SKIP_DIRECTORY = "META-INF";
  static final String TEMP_DIR_PROPERTY = "archivesearcher.tempDir";

  @Override
  public SearcherMode getMode() {
    return SearcherMode.ARCHIVE_FILENAME;
  }

  @Override
  public boolean isArchiveSupported(String fileName) {
    return SUPPORTED_EXTENSIONS.stream().anyMatch(ext -> fileName.toLowerCase().endsWith(ext));
  }

  @Override
  public List<SearchResult> search(Path archivePath, String searchText, CancellationToken token)
      throws IOException {
    if (!isArchiveSupported(archivePath.toString())) {
      throw new IOException("Unsupported archive type.");
    }
    if (!Files.isRegularFile(archivePath)) {
      throw new IOException("Archive file does not exist: " + archivePath);
    }

    String searchFileLower = searchText.toLowerCase();
    List<SearchResult> results = new ArrayList<>();
    Path tempDir = createPrivateTempDirectory();
    try {
      String rootPath = archivePath.getFileName() == null
          ? archivePath.toString()
          : archivePath.getFileName().toString();
      searchInArchive(archivePath.toFile(), archivePath, searchFileLower, rootPath, tempDir, token,
          results);
      return results;
    } finally {
      deleteTempTree(tempDir);
    }
  }

  private void searchInArchive(File archiveFile, Path originalArchivePath, String searchFileLower,
      String parentPath, Path tempDir, CancellationToken token, List<SearchResult> results)
      throws IOException {
    LOGGER.log(System.Logger.Level.INFO, "Checking archive: {0}",
        parentPath.isEmpty() ? archiveFile.getName() : parentPath);

    try (ZipFile zip = new ZipFile(archiveFile)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        if (token.isCancelled()) {
          LOGGER.log(System.Logger.Level.INFO, "Archive search cancelled.");
          return;
        }

        ZipEntry entry = entries.nextElement();
        String currentPath = archiveEntryPath(parentPath, entry.getName());

        boolean directory = entry.isDirectory();
        if (directory) {
          LOGGER.log(System.Logger.Level.DEBUG, () -> "Traversing folder: " + currentPath);
        }

        if (!directory) {
          String fileNameLower = new File(entry.getName()).getName().toLowerCase();
          if (fileNameLower.contains(searchFileLower)) {
            results.add(new SearchResult(getMode(), originalArchivePath, currentPath,
                0, currentPath, ""));
          }

          if (isArchiveSupported(entry.getName())) {
            if (isInSkippedDirectory(entry.getName())) {
              LOGGER.log(System.Logger.Level.DEBUG,
                  () -> "Skipping META-INF archive: " + currentPath);
            } else {
              processNestedArchive(zip, entry, originalArchivePath, searchFileLower, tempDir,
                  currentPath, token, results);
            }
          }
        }
      }
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Error processing archive: " + e.getMessage(), e);
    }
  }

  private String archiveEntryPath(String parentPath, String entryName) {
    if (parentPath.isEmpty()) {
      return entryName;
    }
    return parentPath + ARCHIVE_BANG + entryName;
  }

  private boolean isInSkippedDirectory(String entryName) {
    return entryName.startsWith(SKIP_DIRECTORY + ARCHIVE_ENTRY_SEPARATOR);
  }

  private void processNestedArchive(ZipFile zip, ZipEntry entry, Path originalArchivePath,
      String searchFileLower, Path tempDir, String currentPath, CancellationToken token,
      List<SearchResult> results) {
    try {
      if (token.isCancelled()) {
        LOGGER.log(System.Logger.Level.DEBUG, "Cancelled before processing nested archive: " + currentPath);
        return;
      }
      Path tempFile = extractToTempFile(zip, entry, tempDir, token);
      try {
        if (token.isCancelled()) {
          LOGGER.log(System.Logger.Level.DEBUG, "Cancelled before searching nested archive: " + currentPath);
          return;
        }
        searchInArchive(tempFile.toFile(), originalArchivePath, searchFileLower,
            currentPath, tempDir, token, results);
      } finally {
        deleteTempFile(tempFile);
      }
    } catch (Exception e) {
      LOGGER.log(System.Logger.Level.ERROR,
          "Failed to process nested archive: " + currentPath, e);
    }
  }

  private Path extractToTempFile(ZipFile zip, ZipEntry entry, Path tempDir,
      CancellationToken token) throws IOException {
    Path tempFile = Files.createTempFile(tempDir, "nested", ".tmp");
    try (InputStream in = zip.getInputStream(entry);
        OutputStream out = Files.newOutputStream(tempFile)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = in.read(buffer)) != -1) {
        if (token.isCancelled()) {
          LOGGER.log(System.Logger.Level.DEBUG, "Cancelled while extracting entry: " + entry.getName());
          return tempFile;
        }
        out.write(buffer, 0, bytesRead);
      }
    }
    return tempFile;
  }

  private void deleteTempFile(Path tempFile) {
    try {
      Files.delete(tempFile);
    } catch (NoSuchFileException ignored) {
    } catch (IOException e) {
      LOGGER.log(System.Logger.Level.WARNING,
          "Failed to delete temporary file " + tempFile + ": " + e.getMessage());
    }
  }

  private Path createPrivateTempDirectory() throws IOException {
    Path baseDir = tempBaseDirectory();
    try {
      FileAttribute<Set<PosixFilePermission>> permissions =
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
      Files.createDirectories(baseDir, permissions);
      Files.setPosixFilePermissions(baseDir, PosixFilePermissions.fromString("rwx------"));
      return Files.createTempDirectory(baseDir, "archive-searcher-", permissions);
    } catch (UnsupportedOperationException e) {
      Files.createDirectories(baseDir);
      return Files.createTempDirectory(baseDir, "archive-searcher-");
    }
  }

  private Path tempBaseDirectory() {
    String configured = System.getProperty(TEMP_DIR_PROPERTY);
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured);
    }
    return Path.of(System.getProperty("user.home"), ".archivesearcher", "tmp");
  }

  private void deleteTempTree(Path tempDir) {
    try {
      Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          if (exc != null) throw exc;
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      LOGGER.log(System.Logger.Level.WARNING,
          "Failed to delete temporary directory " + tempDir + ": " + e.getMessage());
    }
  }
}
