package nl.rowendu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    assertEquals("Find Codex session", results.get(0).getTitle());
    assertEquals("Find Codex session", results.get(1).getTitle());
  }

  @Test
  void ignoresNonJsonlFiles() throws Exception {
    Files.writeString(tempDir.resolve("chat.json"), "{\"content\":\"codex\"}");

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "codex", () -> false);

    assertEquals(0, results.size());
  }

  @Test
  void returnsNoResultsWhenCancelledBeforeTraversal() throws Exception {
    Path history = tempDir.resolve("chat.jsonl");
    Files.writeString(history, "{\"role\":\"user\",\"content\":\"codex match\"}\n");

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "codex", () -> true);

    assertEquals(0, results.size());
  }

  @Test
  void deduplicatesMatchingJsonlLinesInSameFile() throws Exception {
    Path history = tempDir.resolve("chat.jsonl");
    Files.writeString(
        history,
        """
        {"type":"message","role":"developer","content":"same codex content"}
        {"type":"message","role":"developer","content":"same codex content"}
        {"type":"message","role":"developer","content":"same codex content"}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "codex", () -> false);

    assertEquals(1, results.size());
    assertEquals(1, results.get(0).getLineNumber());
    assertEquals("1, 2, 3", results.get(0).getLineLabel());
  }

  @Test
  void excludesCodexTurnContextFromResults() throws Exception {
    Path history = tempDir.resolve("codex.jsonl");
    Files.writeString(
        history,
        """
        {"type":"response_item","payload":{"type":"message","role":"developer","content":[{"type":"input_text","text":"same codex payload"}]}}
        {"type":"turn_context","payload":{"items":[{"type":"message","role":"developer","content":[{"type":"input_text","text":"same codex payload"}]}]}}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "codex", () -> false);

    assertEquals(1, results.size());
    assertEquals("1", results.get(0).getLineLabel());
  }

  @Test
  void excludesEmptyCodexTaskStartedTokenCountAgentReasoningAndReasoningFromResults() throws Exception {
    Path history = tempDir.resolve("codex-empty.jsonl");
    Files.writeString(
        history,
        """
        {"timestamp":"2026-04-22T21:43:08.325Z","type":"event_msg","payload":{"type":"task_started","turn_id":"turn-codex","started_at":1776894188,"model_context_window":258400,"collaboration_mode_kind":"default"}}
        {"timestamp":"2025-11-05T23:24:18.271Z","type":"event_msg","payload":{"type":"token_count","info":null,"rate_limits":{"primary":{"used_percent":3.0,"window_minutes":300,"resets_at":1762393564},"secondary":{"used_percent":1.0,"window_minutes":10080,"resets_at":1762980364}}}}
        {"timestamp":"2025-11-05T23:24:20.316Z","type":"event_msg","payload":{"type":"agent_reasoning","text":"**Preparing to run Maven compile command**"}}
        {"timestamp":"2026-04-22T21:43:09.501Z","type":"response_item","payload":{"type":"reasoning","summary":[],"content":null,"encrypted_content":"codex"}}
        {"type":"event_msg","payload":{"type":"user_message","message":"Visible codex request"}}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "codex", () -> false);

    assertEquals(1, results.size());
    assertEquals(5, results.get(0).getLineNumber());
  }

  @Test
  void excludesCodexToolRowsFromResultsEvenWhenTheyMatchSearchText() throws Exception {
    Path history = tempDir.resolve("codex-tool.jsonl");
    Files.writeString(
        history,
        """
        {"type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":"{\\"cmd\\":\\"rg SearchArchive\\"}","call_id":"call_1"}}
        {"type":"event_msg","payload":{"type":"user_message","message":"Find SearchArchive references"}}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "SearchArchive", () -> false);

    assertEquals(1, results.size());
    assertEquals(2, results.get(0).getLineNumber());
  }

  @Test
  void excludesCodexShellRowsFromResultsEvenWhenTheyMatchSearchText() throws Exception {
    Path history = tempDir.resolve("codex-shell.jsonl");
    Files.writeString(
        history,
        """
        {"type":"response_item","payload":{"type":"local_shell_call","action":{"type":"exec","command":["rg","SearchArchive"]}}}
        {"type":"event_msg","payload":{"type":"exec_command_end","command":["rg","SearchArchive"],"aggregated_output":"SearchArchive match"}}
        {"type":"event_msg","payload":{"type":"user_message","message":"Find SearchArchive references"}}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "SearchArchive", () -> false);

    assertEquals(1, results.size());
    assertEquals(3, results.get(0).getLineNumber());
  }

  @Test
  void excludesClaudeToolRowsFromResultsEvenWhenTheyMatchSearchText() throws Exception {
    Path history = tempDir.resolve("claude-tool.jsonl");
    Files.writeString(
        history,
        """
        {"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"rg SearchArchive"}}]}}
        {"type":"user","message":{"role":"user","content":[{"type":"text","text":"Find SearchArchive references"}]}}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "SearchArchive", () -> false);

    assertEquals(1, results.size());
    assertEquals(2, results.get(0).getLineNumber());
  }

  @Test
  void addsCodexSessionTitleFromFirstUserPrompt() throws Exception {
    Path history = tempDir.resolve("codex-title.jsonl");
    Files.writeString(
        history,
        """
        {"type":"session_meta","payload":{"cwd":"/repo"}}
        {"type":"event_msg","payload":{"type":"user_message","message":"Search the archive history"}}
        {"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"codex match"}]}}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "codex", () -> false);

    assertEquals(1, results.size());
    assertEquals("Search the archive history", results.get(0).getTitle());
  }

  @Test
  void skipsInjectedCodexContextWhenDerivingSessionTitle() throws Exception {
    Path history = tempDir.resolve("codex-context-title.jsonl");
    Files.writeString(
        history,
        """
        {"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions for /repo\\n\\n<INSTRUCTIONS>\\nRepository guidance\\n</INSTRUCTIONS>\\n<environment_context>\\n  <cwd>/repo</cwd>\\n</environment_context>"}]}}
        {"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"task: assess alignment between SearchArchive and JSONL history search"}]}}
        {"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"codex match"}]}}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "codex", () -> false);

    assertEquals(1, results.size());
    assertEquals("task: assess alignment between SearchArchive and JSONL history search", results.get(0).getTitle());
  }

  @Test
  void excludesInjectedCodexContextFromResults() throws Exception {
    Path history = tempDir.resolve("codex-context-result.jsonl");
    Files.writeString(
        history,
        """
        {"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions for /repo\\n\\n<INSTRUCTIONS>\\nSearchArchive guidance\\n</INSTRUCTIONS>\\n<environment_context>\\n  <cwd>/repo/SearchArchive</cwd>\\n</environment_context>"}]}}
        {"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"Find SearchArchive JSONL matches"}]}}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "SearchArchive", () -> false);

    assertEquals(1, results.size());
    assertEquals(2, results.get(0).getLineNumber());
    assertEquals("Find SearchArchive JSONL matches", results.get(0).getTitle());
  }

  @Test
  void addsClaudeSessionTitleFromFirstMeaningfulUserPrompt() throws Exception {
    Path history = tempDir.resolve("claude-title.jsonl");
    Files.writeString(
        history,
        """
        {"type":"user","message":{"role":"user","content":"<command-message>init</command-message>\\n<command-name>/init</command-name>"}}
        {"type":"user","message":{"role":"user","content":[{"type":"text","text":"Please analyze this codebase"}]}}
        {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"claude match"}]}}
        """);

    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    List<SearchResult> results = searcher.search(tempDir, "claude", () -> false);

    assertEquals(1, results.size());
    assertEquals(history, results.get(0).getFilePath());
    assertEquals(3, results.get(0).getLineNumber());
    assertEquals("Please analyze this codebase", results.get(0).getTitle());
  }

  @Test
  void titleForUnwrapsUncheckedIOExceptionFromCacheComputation() throws Exception {
    JsonlHistorySearcher searcher = new JsonlHistorySearcher();
    Path history = tempDir.resolve("chat.jsonl");
    Map<Path, String> failingCache = new LinkedHashMap<>() {
      @Override
      public String computeIfAbsent(Path key,
          java.util.function.Function<? super Path, ? extends String> mappingFunction) {
        throw new UncheckedIOException(new IOException("title read failed"));
      }
    };

    Method titleFor = JsonlHistorySearcher.class.getDeclaredMethod("titleFor", Path.class, Map.class);
    titleFor.setAccessible(true);

    InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
        () -> titleFor.invoke(searcher, history, failingCache));
    assertEquals(IOException.class, thrown.getCause().getClass());
    assertEquals("title read failed", thrown.getCause().getMessage());
  }
}
