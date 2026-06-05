package nl.rowendu;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class JsonlTranscriptParser {

  private final JsonlLineParser lineParser = new JsonlLineParser();
  private final TranscriptFilter transcriptFilter = new TranscriptFilter();
  private final TranscriptDeduplicator deduplicator = new TranscriptDeduplicator();
  private final TranscriptNoiseFilter noiseFilter = new TranscriptNoiseFilter();

  ChatTranscript parse(Path jsonlFile) throws IOException {
    List<ChatTurn> turns = new ArrayList<>();

    try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank()) continue;
        ChatTurn turn = lineParser.parseLine(lineNumber, line);
        if (turn != null) turns.add(turn);
      }
    }

    return new ChatTranscript(jsonlFile,
        deduplicator.deduplicateTurns(transcriptFilter.filterToolTurns(turns)));
  }

  String sessionTitle(Path jsonlFile) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) continue;
        ChatTurn turn = lineParser.parseLine(0, line);
        if (turn == null || turn.getRole() != ChatRole.USER) continue;
        String title = titleFromContent(turn.getContent());
        if (!title.isBlank()) return title;
      }
    }
    return jsonlFile.getFileName().toString();
  }

  // ---- Forwarding methods for backward compatibility ----

  String dedupeKeyForRawLine(String rawLine) {
    return noiseFilter.dedupeKeyForRawLine(rawLine);
  }

  boolean isSearchResultLine(String rawLine) {
    return noiseFilter.isSearchResultLine(rawLine);
  }

  // ---- Title extraction ----

  private String titleFromContent(String content) {
    if (isLowValueTitleContent(content)) return "";
    String title = content
        .replaceAll("<[^>]+>", " ")
        .replaceAll("```[a-zA-Z0-9_-]*", " ")
        .trim()
        .replaceAll("\\s+", " ");
    if (isLowValueTitleContent(title)) return "";
    if (title.length() <= 90) return title;
    return title.substring(0, 87) + "...";
  }

  private boolean isLowValueTitleContent(String content) {
    String normalized = content.trim().toLowerCase(Locale.ROOT);
    return normalized.isBlank()
        || isInjectedContext(normalized)
        || normalized.contains("<command-name>")
        || normalized.contains("<command-message>")
        || normalized.startsWith("tool result")
        || normalized.matches("/[a-z0-9_-]+");
  }

  private boolean isInjectedContext(String normalized) {
    return normalized.startsWith("# agents.md instructions for ")
        || (normalized.contains("<instructions>")
            && normalized.contains("</instructions>")
            && normalized.contains("<environment_context>"));
  }
}
