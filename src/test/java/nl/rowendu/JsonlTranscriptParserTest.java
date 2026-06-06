package nl.rowendu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JsonlTranscriptParserTest {
  @TempDir Path tempDir;

  @Test
  void parsesClaudeMessagesAndContentBlocks() throws Exception {
    Path transcriptFile = tempDir.resolve("claude.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"type":"user","timestamp":"2026-05-25T10:00:00Z","cwd":"/repo","gitBranch":"main","message":{"role":"user","content":[{"type":"text","text":"Search the history"}]}}
        {"type":"assistant","timestamp":"2026-05-25T10:00:01Z","message":{"role":"assistant","content":[{"type":"text","content":"Here are the results"},{"type":"text","text":"Second block"}]}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(2, turns.size());
    assertEquals(ChatRole.USER, turns.get(0).getRole());
    assertEquals("Search the history", turns.get(0).getContent());
    assertEquals("cwd: /repo | branch: main", turns.get(0).getEnvironment());
    assertEquals(ChatRole.ASSISTANT, turns.get(1).getRole());
    assertEquals("Here are the results\n\nSecond block", turns.get(1).getContent());
  }

  @ParameterizedTest
  @MethodSource("fullyFilteredTranscripts")
  void filtersTranscriptsWithOnlyNoiseRecords(String caseName, String content) throws Exception {
    Path transcriptFile = tempDir.resolve(caseName + ".jsonl");
    Files.writeString(transcriptFile, content);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(0, turns.size());
  }

  private static Stream<Arguments> fullyFilteredTranscripts() {
    return Stream.of(
        Arguments.of("claude-tools",
            """
            {"type":"assistant","timestamp":"2026-05-25T10:00:01Z","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"rg codex"}}]}}
            {"type":"user","timestamp":"2026-05-25T10:00:02Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"found match"}]}}
            """),
        Arguments.of("codex-function-calls",
            """
            {"timestamp":"2026-05-25T15:57:11.679Z","type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":"{\\"cmd\\":\\"rg --files\\",\\"workdir\\":\\"/repo\\"}","call_id":"call_1"}}
            {"timestamp":"2026-05-25T15:57:11.682Z","type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":"{\\"cmd\\":\\"git status --short\\",\\"workdir\\":\\"/repo\\"}","call_id":"call_2"}}
            {"type":"event_msg","payload":{"type":"exec_command_end","aggregated_output":"src/App.java"}}
            """),
        Arguments.of("codex-shell",
            """
            {"type":"response_item","payload":{"type":"local_shell_call","action":{"type":"exec","command":["rg","codex"],"working_directory":"/repo"},"status":"completed"}}
            {"type":"event_msg","payload":{"type":"exec_command_end","command":["rg","codex"],"aggregated_output":"src/App.java:codex"}}
            """));
  }

  @ParameterizedTest
  @MethodSource("claudeNoiseRecords")
  void filtersClaudeNoiseRecords(String caseName, String noiseRecord) throws Exception {
    Path transcriptFile = tempDir.resolve("claude-" + caseName + ".jsonl");
    Files.writeString(transcriptFile,
        noiseRecord + "\n"
            + """
            {"type":"assistant","timestamp":"2026-05-25T10:00:01Z","message":{"role":"assistant","content":[{"type":"text","text":"visible message"}]}}
            """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(1, turns.size());
    assertEquals(ChatRole.ASSISTANT, turns.get(0).getRole());
    assertEquals("visible message", turns.get(0).getContent());
  }

  private static Stream<Arguments> claudeNoiseRecords() {
    return Stream.of(
        Arguments.of("snapshot",
            "{\"type\":\"file-history-snapshot\",\"timestamp\":\"2026-05-25T10:00:00Z\",\"content\":\"snapshot data\"}"),
        Arguments.of("progress",
            "{\"type\":\"progress\",\"data\":{\"type\":\"hook_progress\",\"hookEvent\":\"PostToolUse\",\"hookName\":\"PostToolUse:Glob\",\"command\":\"callback\"}}"),
        Arguments.of("duration",
            "{\"type\":\"system\",\"subtype\":\"turn_duration\",\"durationMs\":46883,\"messageCount\":19}"),
        Arguments.of("blank",
            "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"\\n\\n\"}]}}"));
  }

  @Test
  void parsesCodexMetadataUserAssistantAndFiltersToolRecords() throws Exception {
    Path transcriptFile = tempDir.resolve("codex.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"type":"session_meta","payload":{"cwd":"/repo","git":{"branch":"feature"},"model_provider":"openai","cli_version":"1.2.3"}}
        {"type":"event_msg","payload":{"type":"user_message","message":"Find codex notes"}}
        {"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Found them"}]}}
        {"type":"response_item","payload":{"type":"local_shell_call","action":{"type":"exec","command":["rg","codex"]}}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(3, turns.size());
    assertEquals(ChatRole.SYSTEM, turns.get(0).getRole());
    assertTrue(turns.get(0).isCollapsed());
    assertTrue(turns.get(0).getContent().contains("Working directory: /repo"));
    assertTrue(turns.get(0).getContent().contains("Git branch: feature"));
    assertEquals(ChatRole.USER, turns.get(1).getRole());
    assertEquals("Find codex notes", turns.get(1).getContent());
    assertEquals(ChatRole.ASSISTANT, turns.get(2).getRole());
    assertEquals("Found them", turns.get(2).getContent());
  }

  @Test
  void filtersEmptyCodexTaskStartedTokenCountAgentReasoningAndReasoningRecords() throws Exception {
    Path transcriptFile = tempDir.resolve("codex-empty.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"timestamp":"2026-04-22T21:43:08.325Z","type":"event_msg","payload":{"type":"task_started","turn_id":"turn-1","started_at":1776894188,"model_context_window":258400,"collaboration_mode_kind":"default"}}
        {"timestamp":"2025-11-05T23:24:18.271Z","type":"event_msg","payload":{"type":"token_count","info":null,"rate_limits":{"primary":{"used_percent":3.0,"window_minutes":300,"resets_at":1762393564},"secondary":{"used_percent":1.0,"window_minutes":10080,"resets_at":1762980364}}}}
        {"timestamp":"2025-11-05T23:24:20.316Z","type":"event_msg","payload":{"type":"agent_reasoning","text":"**Preparing to run Maven compile command**"}}
        {"timestamp":"2026-04-22T21:43:09.501Z","type":"response_item","payload":{"type":"reasoning","summary":[],"content":null,"encrypted_content":"secret"}}
        {"type":"event_msg","payload":{"type":"user_message","message":"Visible codex request"}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(1, turns.size());
    assertEquals(ChatRole.USER, turns.get(0).getRole());
    assertEquals("Visible codex request", turns.get(0).getContent());
  }

  @Test
  void keepsCodexReasoningRecordsWithVisibleSummaryBlocks() throws Exception {
    Path transcriptFile = tempDir.resolve("codex-visible-reasoning.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"type":"response_item","payload":{"type":"reasoning","summary":[{"type":"text","text":"Visible reasoning summary"}],"content":null}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(1, turns.size());
    assertEquals(ChatRole.SYSTEM, turns.get(0).getRole());
    assertEquals("Visible reasoning summary", turns.get(0).getContent());
  }

  @Test
  void keepsCodexTaskStartedEventsWithVisibleCommandOrContent() throws Exception {
    Path transcriptFile = tempDir.resolve("codex-visible-task-started.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"type":"event_msg","payload":{"type":"task_started","command":["rg","codex"]}}
        {"type":"event_msg","payload":{"type":"task_started","content":[{"type":"output_text","text":"Preparing visible codex task"}]}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(2, turns.size());
    assertEquals(ChatRole.SYSTEM, turns.get(0).getRole());
    assertEquals("Command: rg codex", turns.get(0).getContent());
    assertEquals(ChatRole.SYSTEM, turns.get(1).getRole());
    assertEquals("Preparing visible codex task", turns.get(1).getContent());
  }

  @Test
  void keepsCodexTaskStartedEventsWithVisibleToolCallContent() throws Exception {
    Path transcriptFile = tempDir.resolve("codex-visible-task-started-tool.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"type":"event_msg","payload":{"type":"task_started","content":[{"type":"function_call","name":"exec_command","arguments":"{\\"cmd\\":\\"rg codex\\"}"}]}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(1, turns.size());
    assertEquals(ChatRole.SYSTEM, turns.get(0).getRole());
    assertEquals("[Tool call: exec_command]\n{\"cmd\":\"rg codex\"}", turns.get(0).getContent());
  }

  @Test
  void filtersCodexTurnContextRecords() throws Exception {
    Path transcriptFile = tempDir.resolve("codex-turn-context.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"type":"response_item","payload":{"type":"message","role":"developer","content":[{"type":"input_text","text":"same developer instructions"}]}}
        {"type":"turn_context","payload":{"items":[{"type":"message","role":"developer","content":[{"type":"input_text","text":"same developer instructions"}]}]}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(1, turns.size());
    assertEquals(ChatRole.SYSTEM, turns.get(0).getRole());
    assertEquals("same developer instructions", turns.get(0).getContent());
    assertTrue(turns.get(0).includesSourceLine(1));
    assertFalse(turns.get(0).includesSourceLine(2));
    assertEquals("1", turns.get(0).getSourceLineLabel());
  }

  @Test
  void filtersInjectedCodexContextRecords() throws Exception {
    Path transcriptFile = tempDir.resolve("codex-injected-context.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions for /repo\\n\\n<INSTRUCTIONS>\\nRepository guidance\\n</INSTRUCTIONS>\\n<environment_context>\\n  <cwd>/repo</cwd>\\n</environment_context>"}]}}
        {"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"Actual user request"}]}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(1, turns.size());
    assertEquals(ChatRole.USER, turns.get(0).getRole());
    assertEquals("Actual user request", turns.get(0).getContent());
    assertEquals(2, turns.get(0).getLineNumber());
  }

  @Test
  void filtersInjectedCodexContextRecordsWithStringContent() throws Exception {
    Path transcriptFile = tempDir.resolve("codex-injected-context-string.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"type":"response_item","payload":{"type":"message","role":"user","content":"# AGENTS.md instructions for /repo\\n\\n<INSTRUCTIONS>\\nRepository guidance\\n</INSTRUCTIONS>\\n<environment_context>\\n  <cwd>/repo</cwd>\\n</environment_context>"}}
        {"type":"response_item","payload":{"type":"message","role":"user","content":"Actual user request"}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(1, turns.size());
    assertEquals(ChatRole.USER, turns.get(0).getRole());
    assertEquals("Actual user request", turns.get(0).getContent());
    assertEquals(2, turns.get(0).getLineNumber());
  }

  @Test
  void createsSameDedupeKeyForEquivalentCodexContent() {
    JsonlTranscriptParser parser = new JsonlTranscriptParser();
    String responseItem =
        "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"developer\",\"content\":[{\"type\":\"input_text\",\"text\":\"same codex payload\"}]}}";
    String simpleMessage =
        "{\"type\":\"message\",\"role\":\"developer\",\"content\":\"same   codex\\n payload\"}";

    assertEquals(parser.dedupeKeyForRawLine(responseItem), parser.dedupeKeyForRawLine(simpleMessage));
  }

  @Test
  void keepsInvalidJsonLinesAsUnknownTurns() throws Exception {
    Path transcriptFile = tempDir.resolve("broken.jsonl");
    Files.writeString(transcriptFile, "not-json\n");

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    ChatTurn turn = transcript.getTurns().get(0);

    assertEquals(ChatRole.UNKNOWN, turn.getRole());
    assertFalse(turn.isCollapsed());
    assertEquals("not-json", turn.getContent());
    assertEquals(1, turn.getLineNumber());
  }
}
