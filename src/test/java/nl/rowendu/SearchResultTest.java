package nl.rowendu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SearchResultTest {
  @Test
  void tracksMergedSourceLinesForHighlighting() {
    SearchResult result =
        new SearchResult(
                SearcherMode.JSONL_HISTORY,
                Path.of("session.jsonl"),
                "session.jsonl",
                "Session title",
                2,
                "preview",
                "{}")
            .withAdditionalSourceLine(7);

    assertTrue(result.includesSourceLine(2));
    assertFalse(result.includesSourceLine(1));
    assertTrue(result.includesSourceLine(7));
  }
}
