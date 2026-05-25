package nl.rowendu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

  @Test
  void parsesCodexMetadataUserAssistantAndCollapsedToolRecords() throws Exception {
    Path transcriptFile = tempDir.resolve("codex.jsonl");
    Files.writeString(
        transcriptFile,
        """
        {"type":"session_meta","payload":{"cwd":"/repo","git_branch":"feature","model_provider":"openai","cli_version":"1.2.3"}}
        {"type":"event_msg","payload":{"type":"user_message","message":"Find codex notes"}}
        {"type":"response_item","payload":{"item":{"role":"assistant","content":[{"type":"output_text","text":"Found them"}]}}}
        {"type":"response_item","payload":{"item":{"type":"function_call","name":"shell","arguments":"rg codex"}}}
        """);

    ChatTranscript transcript = new JsonlTranscriptParser().parse(transcriptFile);
    List<ChatTurn> turns = transcript.getTurns();

    assertEquals(4, turns.size());
    assertEquals(ChatRole.SYSTEM, turns.get(0).getRole());
    assertTrue(turns.get(0).isCollapsed());
    assertTrue(turns.get(0).getContent().contains("Working directory: /repo"));
    assertEquals(ChatRole.USER, turns.get(1).getRole());
    assertEquals("Find codex notes", turns.get(1).getContent());
    assertEquals(ChatRole.ASSISTANT, turns.get(2).getRole());
    assertEquals("Found them", turns.get(2).getContent());
    assertEquals(ChatRole.TOOL, turns.get(3).getRole());
    assertTrue(turns.get(3).isCollapsed());
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
