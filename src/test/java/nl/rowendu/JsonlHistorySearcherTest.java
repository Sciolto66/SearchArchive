package nl.rowendu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlHistorySearcherTest {
  @TempDir Path tempDir;

  @Test
  void returnsOneResultPerMatchingLineCaseInsensitive() throws Exception {
    Path nested = Files.createDirectories(tempDir.resolve("sessions/2026"));
    Path history = nested.resolve("chat.jsonl");
    Files.writeString(
        history,
        """
        {"role":"user","content":"Find Codex session"}
        {"role":"assistant","content":"codex result"}
        {"role":"assistant","content":"unrelated"}
        """);
    Files.writeString(tempDir.resolve("notes.txt"), "codex ignored");

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "CODEX", () -> false);

    assertEquals(2, results.size());
    assertEquals(history, results.get(0).getFilePath());
    assertEquals(1, results.get(0).getLineNumber());
    assertEquals(2, results.get(1).getLineNumber());
  }

  @Test
  void ignoresNonJsonlFiles() throws Exception {
    Files.writeString(tempDir.resolve("chat.json"), "{\"content\":\"codex\"}");

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "codex", () -> false);

    assertEquals(0, results.size());
  }
}
