package nl.rowendu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchiveFileSearcherTest {
  @TempDir Path tempDir;

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
    assertEquals("settings/config.properties", results.get(0).getLocation());
  }

  @Test
  void searchesFilenamesInNestedArchives() throws Exception {
    byte[] nestedArchive = nestedZipBytes(new Entry("inside/target.txt", "x".getBytes()));
    Path archive = tempDir.resolve("archive.zip");
    createZip(archive, new Entry("lib/nested.jar", nestedArchive));

    ArchiveFileSearcher searcher = new ArchiveFileSearcher();
    List<SearchResult> results = searcher.search(archive, "target", () -> false);

    assertEquals(1, results.size());
    assertEquals("lib/nested.jar/inside/target.txt", results.get(0).getLocation());
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
