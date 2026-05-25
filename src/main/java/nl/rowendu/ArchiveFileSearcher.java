package nl.rowendu;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ArchiveFileSearcher {
  private static final System.Logger LOGGER = System.getLogger(ArchiveFileSearcher.class.getName());
  private static final Set<String> SUPPORTED_EXTENSIONS =
      new HashSet<>(Arrays.asList(".zip", ".jar", ".ear", ".sar"));
  private static final String SKIP_DIRECTORY = "META-INF";

  boolean isArchiveSupported(String fileName) {
    return SUPPORTED_EXTENSIONS.stream().anyMatch(ext -> fileName.toLowerCase().endsWith(ext));
  }

  List<SearchResult> search(Path archivePath, String searchText, CancellationToken token)
      throws IOException {
    if (!isArchiveSupported(archivePath.toString())) {
      throw new IOException("Unsupported archive type.");
    }
    if (!Files.isRegularFile(archivePath)) {
      throw new IOException("Archive file does not exist: " + archivePath);
    }

    String searchFileLower = searchText.toLowerCase();
    List<SearchResult> results = new ArrayList<>();
    searchInArchive(archivePath.toFile(), archivePath, searchFileLower, "", token, results);
    return results;
  }

  private void searchInArchive(
      File archiveFile,
      Path originalArchivePath,
      String searchFileLower,
      String parentPath,
      CancellationToken token,
      List<SearchResult> results)
      throws IOException {
    LOGGER.log(
        System.Logger.Level.INFO,
        "Checking archive: {0}",
        parentPath.isEmpty() ? archiveFile.getName() : parentPath);

    try (ZipFile zip = new ZipFile(archiveFile)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();

      while (entries.hasMoreElements()) {
        if (token.isCancelled()) {
          LOGGER.log(System.Logger.Level.INFO, "Archive search cancelled.");
          return;
        }

        ZipEntry entry = entries.nextElement();
        String currentPath =
            parentPath.isEmpty() ? entry.getName() : parentPath + "/" + entry.getName();

        if (entry.isDirectory()) {
          LOGGER.log(System.Logger.Level.DEBUG, () -> "Traversing folder: " + currentPath);
          continue;
        }

        String fileNameLower = new File(entry.getName()).getName().toLowerCase();
        if (fileNameLower.contains(searchFileLower)) {
          results.add(
              new SearchResult(
                  SearchMode.ARCHIVE_FILENAME,
                  originalArchivePath,
                  currentPath,
                  0,
                  currentPath,
                  ""));
        }

        if (isArchiveSupported(entry.getName())) {
          if (entry.getName().startsWith(SKIP_DIRECTORY + "/")) {
            LOGGER.log(
                System.Logger.Level.DEBUG, () -> "Skipping META-INF archive: " + currentPath);
            continue;
          }
          processNestedArchive(
              zip, entry, originalArchivePath, searchFileLower, currentPath, token, results);
        }
      }
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Error processing archive: " + e.getMessage(), e);
    }
  }

  private void processNestedArchive(
      ZipFile zip,
      ZipEntry entry,
      Path originalArchivePath,
      String searchFileLower,
      String currentPath,
      CancellationToken token,
      List<SearchResult> results) {
    try {
      Path tempFile = extractToTempFile(zip, entry);
      try {
        searchInArchive(
            tempFile.toFile(), originalArchivePath, searchFileLower, currentPath, token, results);
      } finally {
        deleteTempFile(tempFile);
      }
    } catch (Exception e) {
      LOGGER.log(System.Logger.Level.ERROR, "Failed to process nested archive: " + currentPath, e);
    }
  }

  private Path extractToTempFile(ZipFile zip, ZipEntry entry) throws IOException {
    Path tempFile = Files.createTempFile("nested", ".tmp");
    try (InputStream in = zip.getInputStream(entry);
        OutputStream out = Files.newOutputStream(tempFile)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = in.read(buffer)) != -1) {
        out.write(buffer, 0, bytesRead);
      }
    }
    return tempFile;
  }

  private void deleteTempFile(Path tempFile) {
    try {
      Files.delete(tempFile);
    } catch (NoSuchFileException ignored) {
      // Already removed.
    } catch (IOException e) {
      LOGGER.log(
          System.Logger.Level.WARNING,
          "Failed to delete temporary file " + tempFile + ": " + e.getMessage());
    }
  }
}
