package nl.rowendu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class JsonlLineParser {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ObjectWriter prettyJsonWriter = objectMapper.writerWithDefaultPrettyPrinter();

  ObjectWriter getPrettyJsonWriter() {
    return prettyJsonWriter;
  }

  ChatTurn parseLine(int lineNumber, String rawLine) {
    try {
      JsonNode root = objectMapper.readTree(rawLine);
      return parseJsonLine(lineNumber, root, rawLine);
    } catch (Exception e) {
      return new ChatTurn(lineNumber, ChatRole.UNKNOWN, "", "", rawLine, false);
    }
  }

  ChatTurn parseJsonLine(int lineNumber, JsonNode root, String rawLine) {
    String type = text(root, "type");
    if (isCodexRolloutType(type)) {
      return parseCodexLine(lineNumber, root, rawLine, type);
    }
    return parseClaudeLine(lineNumber, root, rawLine, type);
  }

  // ---- Codex parsing ----

  private boolean isCodexRolloutType(String type) {
    return "session_meta".equals(type)
        || "response_item".equals(type)
        || "event_msg".equals(type)
        || "compacted".equals(type)
        || "turn_context".equals(type);
  }

  private ChatTurn parseCodexLine(int lineNumber, JsonNode root, String rawLine, String type) {
    JsonNode payload = object(root, "payload");
    JsonNode item = firstObject(object(payload, "item"), object(payload, "response_item"));
    JsonNode candidate = firstObject(item, payload);

    if (TranscriptNoiseFilter.isLowValueCodexRoot(root)) {
      return null;
    }

    if ("session_meta".equals(type)) {
      return new ChatTurn(
          lineNumber, ChatRole.SYSTEM, timestamp(root, payload),
          codexEnvironment(payload), metadataSummary(payload), true);
    }
    if ("compacted".equals(type)) {
      return new ChatTurn(
          lineNumber, ChatRole.SYSTEM, timestamp(root, payload),
          codexEnvironment(payload),
          firstText(contentFrom(payload.get("content")), text(payload, "message"), "Context compacted"),
          true);
    }
    if ("event_msg".equals(type)) {
      return parseCodexEventMsg(lineNumber, root, payload, rawLine);
    }
    return parseCodexResponseItem(lineNumber, root, candidate, rawLine);
  }

  private ChatTurn parseCodexEventMsg(int lineNumber, JsonNode root, JsonNode payload, String rawLine) {
    String eventType = text(payload, "type");
    ChatRole role = roleForCodexEvent(eventType);
    boolean collapsed = role == ChatRole.TOOL || role == ChatRole.SYSTEM;
    String content = firstText(
        text(payload, "message"), text(payload, "last_agent_message"),
        text(payload, "aggregated_output"), commandSummary(payload),
        contentFrom(payload.get("content")), contentFrom(payload.get("output")),
        fallbackSummary(root, rawLine));
    return new ChatTurn(lineNumber, role, timestamp(root, payload),
        codexEnvironment(payload), content, collapsed);
  }

  private ChatTurn parseCodexResponseItem(int lineNumber, JsonNode root, JsonNode item, String rawLine) {
    ChatRole role = roleForCodexResponseItem(item);
    boolean collapsed = role == ChatRole.TOOL || role == ChatRole.SYSTEM;
    String content = firstText(
        contentFrom(item.get("content")), reasoningSummary(item),
        commandSummary(item), toolOutputSummary(item),
        fallbackSummary(root, rawLine));
    return new ChatTurn(lineNumber, role, timestamp(root, item),
        codexEnvironment(item), content, collapsed);
  }

  private ChatRole roleForCodexEvent(String eventType) {
    String normalized = eventType.toLowerCase(Locale.ROOT);
    if (normalized.contains("user_message")) return ChatRole.USER;
    if (normalized.contains("agent_message") || normalized.contains("assistant")) return ChatRole.ASSISTANT;
    if (isToolType(normalized)) return ChatRole.TOOL;
    return ChatRole.SYSTEM;
  }

  private ChatRole roleForCodexResponseItem(JsonNode item) {
    String itemType = text(item, "type").toLowerCase(Locale.ROOT);
    String role = text(item, "role").toLowerCase(Locale.ROOT);
    if ("message".equals(itemType)) return roleFromString(role);
    if ("reasoning".equals(itemType) || itemType.contains("compaction")) return ChatRole.SYSTEM;
    if (isToolType(itemType)) return ChatRole.TOOL;
    return roleFromString(firstText(role, itemType));
  }

  // ---- Claude parsing ----

  private ChatTurn parseClaudeLine(int lineNumber, JsonNode root, String rawLine, String type) {
    if ("file-history-snapshot".equals(type)
        || "progress".equals(type)
        || ("system".equals(type) && "turn_duration".equals(text(root, "subtype")))) {
      return null;
    }

    JsonNode message = object(root, "message");
    ChatRole role = roleFromString(firstText(text(message, "role"), text(root, "role"), type));
    boolean toolLike = claudeLineHasToolPayload(root, message);
    if (toolLike) role = ChatRole.TOOL;
    boolean collapsed = toolLike || role == ChatRole.SYSTEM;
    String messageContent = contentFrom(message.get("content"));
    if (message.has("content") && messageContent.isBlank()) {
      return null;
    }
    String content = firstText(
        messageContent, contentFrom(root.get("content")),
        toolUseResultSummary(root.get("toolUseResult")),
        text(message, "content"), text(root, "content"),
        text(root, "summary"), fallbackSummary(root, rawLine));
    if (content.isBlank()) return null;

    return new ChatTurn(lineNumber, role, timestamp(root, message),
        claudeEnvironment(root), content, collapsed);
  }

  private boolean claudeLineHasToolPayload(JsonNode root, JsonNode message) {
    return !toolUseResultSummary(root.get("toolUseResult")).isBlank()
        || contentArrayHasOnlyToolBlocks(message.get("content"));
  }

  private boolean contentArrayHasOnlyToolBlocks(JsonNode content) {
    if (content == null || !content.isArray() || content.isEmpty()) return false;
    boolean sawToolBlock = false;
    for (JsonNode block : content) {
      String type = text(block, "type").toLowerCase(Locale.ROOT);
      if ("tool_use".equals(type) || "tool_result".equals(type)) {
        sawToolBlock = true;
        continue;
      }
      if (!type.isBlank() && !"thinking".equals(type)) return false;
    }
    return sawToolBlock;
  }

  // ---- Content extraction ----

  String contentFrom(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    if (node.isTextual()) return node.asText();
    if (node.isArray()) {
      List<String> blocks = new ArrayList<>();
      for (JsonNode child : node) {
        String block = contentBlock(child);
        if (!block.isBlank()) blocks.add(block);
      }
      return String.join("\n\n", blocks);
    }
    if (node.isObject()) return contentBlock(node);
    return "";
  }

  private String contentBlock(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    if (node.isTextual()) return node.asText();
    if (!node.isObject()) return "";

    String type = text(node, "type");
    return switch (type) {
      case "text", "input_text", "output_text" -> firstText(text(node, "text"), text(node, "content"));
      case "thinking" -> labeledBlock("Thinking", firstText(text(node, "thinking"), text(node, "text")));
      case "tool_use" -> toolUseSummary(node);
      case "tool_result" -> toolResultSummary(node);
      case "function_call", "custom_tool_call", "local_shell_call", "web_search_call",
          "tool_search_call" -> codexToolCallSummary(node);
      case "function_call_output", "custom_tool_call_output", "tool_search_output" ->
          toolOutputSummary(node);
      default -> firstText(text(node, "text"), text(node, "content"), text(node, "input"),
          text(node, "output"), contentFrom(node.get("message")),
          !type.isBlank() ? "[" + type + "]" : "");
    };
  }

  private String toolUseSummary(JsonNode node) {
    String name = firstText(text(node, "name"), "tool");
    String input = prettyJson(node.get("input"));
    return labeledBlock("Tool use: " + name, input);
  }

  private String toolResultSummary(JsonNode node) {
    String id = text(node, "tool_use_id");
    String title = id.isBlank() ? "Tool result" : "Tool result: " + id;
    String content = firstText(contentFrom(node.get("content")), toolUseResultSummary(node));
    return labeledBlock(title, content);
  }

  private String toolUseResultSummary(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    if (node.isTextual()) return node.asText();
    return prettyJson(node);
  }

  private String codexToolCallSummary(JsonNode node) {
    String type = text(node, "type");
    if ("local_shell_call".equals(type)) return commandSummary(node);
    String name = firstText(text(node, "name"), text(node, "type"), "tool");
    String details = firstText(text(node, "arguments"), text(node, "input"),
        prettyJson(node.get("action")), prettyJson(node.get("arguments")));
    return labeledBlock("Tool call: " + name, details);
  }

  private String commandSummary(JsonNode node) {
    JsonNode action = object(node, "action");
    JsonNode command = firstNode(node.get("command"), action.get("command"));
    String renderedCommand = commandArray(command);
    String output = firstText(text(node, "aggregated_output"), text(node, "stdout"), text(node, "stderr"));
    if (!renderedCommand.isBlank() && !output.isBlank()) {
      return "Command: " + renderedCommand + "\n\n" + output;
    }
    if (!renderedCommand.isBlank()) return "Command: " + renderedCommand;
    return output;
  }

  private String commandArray(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    if (node.isTextual()) return node.asText();
    if (!node.isArray()) return "";
    List<String> parts = new ArrayList<>();
    for (JsonNode part : node) {
      if (part.isTextual() || part.isNumber() || part.isBoolean()) parts.add(part.asText());
    }
    return String.join(" ", parts);
  }

  private String toolOutputSummary(JsonNode node) {
    JsonNode output = node.get("output");
    return firstText(contentFrom(output), text(node, "output"), text(node, "content"),
        contentFrom(node.get("content_items")), prettyJson(output));
  }

  private String reasoningSummary(JsonNode node) {
    return firstText(contentFrom(node.get("summary")), contentFrom(node.get("content")));
  }

  // ---- Metadata helpers ----

  private String metadataSummary(JsonNode payload) {
    List<String> lines = new ArrayList<>();
    addIfPresent(lines, "Working directory", text(payload, "cwd"));
    addIfPresent(lines, "Git branch", gitBranch(payload));
    addIfPresent(lines, "Model provider", text(payload, "model_provider"));
    addIfPresent(lines, "Model", text(payload, "model"));
    addIfPresent(lines, "CLI version", firstText(text(payload, "cli_version"), text(payload, "version")));
    addIfPresent(lines, "Source", text(payload, "source"));
    addIfPresent(lines, "Originator", text(payload, "originator"));
    if (lines.isEmpty()) return "Session metadata";
    return String.join("\n", lines);
  }

  private String fallbackSummary(JsonNode root, String rawLine) {
    String type = text(root, "type");
    if (!type.isBlank()) return "[" + type + "]";
    return rawLine;
  }

  // ---- Role / environment / timestamp ----

  private ChatRole roleFromString(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    if (normalized.contains("user")) return ChatRole.USER;
    if (normalized.contains("assistant") || normalized.contains("agent")) return ChatRole.ASSISTANT;
    if (normalized.contains("developer") || normalized.contains("system")
        || normalized.contains("meta") || normalized.contains("summary")) return ChatRole.SYSTEM;
    if (isToolType(normalized)) return ChatRole.TOOL;
    return ChatRole.UNKNOWN;
  }

  private boolean isToolType(String value) {
    return value.contains("tool") || value.contains("function") || value.contains("shell")
        || value.contains("exec") || value.contains("patch") || value.contains("command")
        || value.contains("web_search") || value.contains("image_generation");
  }

  private String timestamp(JsonNode root, JsonNode payload) {
    return firstText(text(root, "timestamp"), text(payload, "timestamp"), text(root, "created_at"));
  }

  private String claudeEnvironment(JsonNode root) {
    List<String> parts = new ArrayList<>();
    addIfPresent(parts, "cwd", text(root, "cwd"));
    addIfPresent(parts, "branch", gitBranch(root));
    return String.join(" | ", parts);
  }

  private String codexEnvironment(JsonNode payload) {
    List<String> parts = new ArrayList<>();
    addIfPresent(parts, "cwd", text(payload, "cwd"));
    addIfPresent(parts, "branch", gitBranch(payload));
    return String.join(" | ", parts);
  }

  private String gitBranch(JsonNode node) {
    return firstText(text(node, "gitBranch"), text(node, "git_branch"),
        text(object(node, "git"), "branch"), text(object(node, "git_info"), "branch"));
  }

  // ---- Low-level JSON helpers ----

  private void addIfPresent(List<String> values, String label, String value) {
    if (!value.isBlank()) values.add(label + ": " + value);
  }

  JsonNode object(JsonNode node, String fieldName) {
    if (node == null || node.isMissingNode() || node.isNull()) return objectMapper.missingNode();
    JsonNode child = node.get(fieldName);
    if (child != null && child.isObject()) return child;
    return objectMapper.missingNode();
  }

  private JsonNode firstObject(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node != null && node.isObject() && !node.isMissingNode()) return node;
    }
    return objectMapper.missingNode();
  }

  private JsonNode firstNode(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node != null && !node.isMissingNode() && !node.isNull()) return node;
    }
    return objectMapper.missingNode();
  }

  private String text(JsonNode node, String fieldName) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    JsonNode child = node.get(fieldName);
    if (child == null || child.isNull() || child.isMissingNode()) return "";
    if (child.isTextual() || child.isNumber() || child.isBoolean()) return child.asText();
    return "";
  }

  private String prettyJson(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    if (node.isTextual() || node.isNumber() || node.isBoolean()) return node.asText();
    try {
      return prettyJsonWriter.writeValueAsString(node);
    } catch (Exception e) {
      return node.toString();
    }
  }

  private String firstText(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return "";
  }

  private String labeledBlock(String label, String content) {
    if (content == null || content.isBlank()) return "[" + label + "]";
    return "[" + label + "]\n" + content;
  }
}
