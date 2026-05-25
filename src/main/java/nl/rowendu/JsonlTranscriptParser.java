package nl.rowendu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class JsonlTranscriptParser {
  private final ObjectMapper objectMapper = new ObjectMapper();

  ChatTranscript parse(Path jsonlFile) throws IOException {
    List<ChatTurn> turns = new ArrayList<>();

    try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank()) {
          continue;
        }
        turns.add(parseLine(lineNumber, line));
      }
    }

    return new ChatTranscript(jsonlFile, turns);
  }

  private ChatTurn parseLine(int lineNumber, String rawLine) {
    try {
      JsonNode root = objectMapper.readTree(rawLine);
      return parseJsonLine(lineNumber, root, rawLine);
    } catch (Exception e) {
      return new ChatTurn(lineNumber, ChatRole.UNKNOWN, "", "", rawLine, false);
    }
  }

  private ChatTurn parseJsonLine(int lineNumber, JsonNode root, String rawLine) {
    String type = text(root, "type");
    JsonNode payload = object(root, "payload");
    JsonNode message = firstObject(object(root, "message"), object(payload, "message"));
    JsonNode item = firstObject(object(payload, "item"), object(payload, "response_item"));
    JsonNode candidate = firstObject(item, payload, root);

    if ("session_meta".equals(type)) {
      return new ChatTurn(
          lineNumber,
          ChatRole.SYSTEM,
          timestamp(root, payload),
          environment(root, payload),
          metadataSummary(payload),
          true);
    }

    ChatRole role = role(root, payload, message, candidate, type);
    boolean toolLike = isToolLike(type, payload, candidate, role);
    if (toolLike) {
      role = ChatRole.TOOL;
    }

    String content = content(message, payload, candidate, root);
    if (content.isBlank()) {
      content = fallbackSummary(root, rawLine);
    }

    return new ChatTurn(
        lineNumber,
        role,
        timestamp(root, payload),
        environment(root, payload),
        content,
        toolLike);
  }

  private ChatRole role(JsonNode root, JsonNode payload, JsonNode message, JsonNode candidate, String type) {
    String rawRole =
        firstText(
            text(message, "role"),
            text(candidate, "role"),
            text(payload, "role"),
            text(root, "role"),
            text(payload, "type"),
            text(candidate, "type"),
            type);

    String normalized = rawRole.toLowerCase(Locale.ROOT);
    if (normalized.contains("user")) {
      return ChatRole.USER;
    }
    if (normalized.contains("assistant")) {
      return ChatRole.ASSISTANT;
    }
    if (normalized.contains("system") || normalized.contains("meta")) {
      return ChatRole.SYSTEM;
    }
    if (normalized.contains("tool")
        || normalized.contains("command")
        || normalized.contains("function")
        || normalized.contains("exec")) {
      return ChatRole.TOOL;
    }
    return ChatRole.UNKNOWN;
  }

  private boolean isToolLike(String type, JsonNode payload, JsonNode candidate, ChatRole role) {
    if (role == ChatRole.TOOL) {
      return true;
    }
    String combined =
        (type
                + " "
                + text(payload, "type")
                + " "
                + text(candidate, "type")
                + " "
                + text(candidate, "name"))
            .toLowerCase(Locale.ROOT);
    return combined.contains("tool")
        || combined.contains("function_call")
        || combined.contains("command")
        || combined.contains("shell")
        || combined.contains("exec")
        || combined.contains("patch");
  }

  private String content(JsonNode message, JsonNode payload, JsonNode candidate, JsonNode root) {
    return firstText(
        contentFrom(message.get("content")),
        contentFrom(candidate.get("content")),
        contentFrom(payload.get("content")),
        text(message, "content"),
        text(payload, "message"),
        text(payload, "text"),
        text(payload, "input_text"),
        text(payload, "output_text"),
        text(candidate, "message"),
        text(candidate, "text"),
        text(candidate, "input_text"),
        text(candidate, "output_text"),
        contentFrom(root.get("content")),
        text(root, "message"),
        text(root, "text"));
  }

  private String contentFrom(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isArray()) {
      List<String> blocks = new ArrayList<>();
      for (JsonNode child : node) {
        String block = contentBlock(child);
        if (!block.isBlank()) {
          blocks.add(block);
        }
      }
      return String.join("\n\n", blocks);
    }
    if (node.isObject()) {
      return contentBlock(node);
    }
    return "";
  }

  private String contentBlock(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isObject()) {
      String type = text(node, "type");
      String content =
          firstText(
              text(node, "text"),
              text(node, "content"),
              text(node, "input"),
              text(node, "output"),
              contentFrom(node.get("message")));
      if (!content.isBlank()) {
        return content;
      }
      if (!type.isBlank()) {
        return "[" + type + "]";
      }
    }
    return "";
  }

  private String metadataSummary(JsonNode payload) {
    List<String> lines = new ArrayList<>();
    addIfPresent(lines, "Working directory", text(payload, "cwd"));
    addIfPresent(lines, "Git branch", firstText(text(payload, "gitBranch"), text(payload, "git_branch")));
    addIfPresent(lines, "Model provider", text(payload, "model_provider"));
    addIfPresent(lines, "Model", text(payload, "model"));
    addIfPresent(lines, "CLI version", firstText(text(payload, "cli_version"), text(payload, "version")));
    addIfPresent(lines, "Source", text(payload, "source"));
    if (lines.isEmpty()) {
      return "Session metadata";
    }
    return String.join("\n", lines);
  }

  private String fallbackSummary(JsonNode root, String rawLine) {
    String type = text(root, "type");
    if (!type.isBlank()) {
      return "[" + type + "]";
    }
    return rawLine;
  }

  private String timestamp(JsonNode root, JsonNode payload) {
    return firstText(text(root, "timestamp"), text(payload, "timestamp"), text(root, "created_at"));
  }

  private String environment(JsonNode root, JsonNode payload) {
    List<String> parts = new ArrayList<>();
    addIfPresent(parts, "cwd", firstText(text(root, "cwd"), text(payload, "cwd")));
    addIfPresent(parts, "branch", firstText(text(root, "gitBranch"), text(payload, "gitBranch"), text(payload, "git_branch")));
    return String.join(" | ", parts);
  }

  private void addIfPresent(List<String> values, String label, String value) {
    if (!value.isBlank()) {
      values.add(label + ": " + value);
    }
  }

  private JsonNode object(JsonNode node, String fieldName) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return objectMapper.missingNode();
    }
    JsonNode child = node.get(fieldName);
    if (child != null && child.isObject()) {
      return child;
    }
    return objectMapper.missingNode();
  }

  private JsonNode firstObject(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node != null && node.isObject() && !node.isMissingNode()) {
        return node;
      }
    }
    return objectMapper.missingNode();
  }

  private String text(JsonNode node, String fieldName) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    JsonNode child = node.get(fieldName);
    if (child == null || child.isNull() || child.isMissingNode()) {
      return "";
    }
    if (child.isTextual() || child.isNumber() || child.isBoolean()) {
      return child.asText();
    }
    return "";
  }

  private String firstText(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }
}
