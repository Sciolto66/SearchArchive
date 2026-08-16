package nl.rowendu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveFileSearcherTest {
  @TempDir Path tempDir;
  private String previousTempDirProperty;

  @BeforeEach
  void configureTempDirectory() {
    previousTempDirProperty = System.getProperty(ArchiveFileSearcher.TEMP_DIR_PROPERTY);
    System.setProperty(ArchiveFileSearcher.TEMP_DIR_PROPERTY, tempDir.resolve("app-tmp").toString());
  }

  @AfterEach
  void restoreTempDirectoryProperty() {
    if (previousTempDirProperty == null) {
      System.clearProperty(ArchiveFileSearcher.TEMP_DIR_PROPERTY);
    } else {
      System.setProperty(ArchiveFileSearcher.TEMP_DIR_PROPERTY, previousTempDirProperty);
    }
  }

  @Test
  void searchesFilenamesInArchiveCaseInsensitiveAndPartial() throws Exception {
    Path archive = tempDir.resolve("archive.zip");
    createZip(
        archive,
        new Entry("settings/config.properties", "x=1".getBytes()),
        new Entry("docs/readme.txt", "no match".getBytes()));

    ArchiveFileSearcher searcher = new ArchiveFileSearcher();
    List<SearchResult> results = searcher.search(archive, "CONFIG", () -> false);

    assertEquals(1, results.size());
    assertEquals("archive.zip!settings/config.properties", results.get(0).getLocation());
    assertTempBaseIsEmpty();
  }

  @Test
  void searchesFilenamesInNestedArchives() throws Exception {
    byte[] nestedArchive = nestedZipBytes(new Entry("inside/target.txt", "x".getBytes()));
    Path archive = tempDir.resolve("archive.zip");
    createZip(archive, new Entry("lib/nested.jar", nestedArchive));

    ArchiveFileSearcher searcher = new ArchiveFileSearcher();
    List<SearchResult> results = searcher.search(archive, "target", () -> false);

    assertEquals(1, results.size());
    assertEquals("archive.zip!lib/nested.jar!inside/target.txt", results.get(0).getLocation());
    assertTempBaseIsEmpty();
  }

  private void assertTempBaseIsEmpty() throws Exception {
    Path tempBase = Path.of(System.getProperty(ArchiveFileSearcher.TEMP_DIR_PROPERTY));
    if (!Files.exists(tempBase)) return;
    try (var children = Files.list(tempBase)) {
      assertTrue(children.findAny().isEmpty());
    }
  }

  private byte[] nestedZipBytes(Entry... entries) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Entry entry : entries) {
        zip.putNextEntry(new ZipEntry(entry.name()));
        zip.write(entry.content());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private void createZip(Path archive, Entry... entries) throws Exception {
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      for (Entry entry : entries) {
        zip.putNextEntry(new ZipEntry(entry.name()));
        zip.write(entry.content());
        zip.closeEntry();
      }
    }
  }

  private record Entry(String name, byte[] content) {}
}
