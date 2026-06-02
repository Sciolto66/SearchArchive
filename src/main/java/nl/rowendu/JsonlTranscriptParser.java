package nl.rowendu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class JsonlTranscriptParser {
  private static final System.Logger LOGGER =
      System.getLogger(JsonlTranscriptParser.class.getName());

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ObjectWriter prettyJsonWriter = objectMapper.writerWithDefaultPrettyPrinter();

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
        ChatTurn turn = parseLine(lineNumber, line);
        if (turn != null) {
          turns.add(turn);
        }
      }
    }

    return new ChatTranscript(jsonlFile, deduplicateTurns(filterToolTurns(turns)));
  }

  private List<ChatTurn> filterToolTurns(List<ChatTurn> turns) {
    return turns.stream().filter(turn -> turn.getRole() != ChatRole.TOOL).toList();
  }

  private List<ChatTurn> deduplicateTurns(List<ChatTurn> turns) {
    Map<String, ChatTurn> uniqueTurns = new LinkedHashMap<>();
    for (ChatTurn turn : turns) {
      String key = dedupeKey(turn);
      ChatTurn existingTurn = uniqueTurns.get(key);
      if (!isDeduplicatable(turn)) {
        uniqueTurns.put(uniqueKey(key, turn.getLineNumber()), turn);
      } else if (existingTurn == null) {
        uniqueTurns.put(key, turn);
      } else {
        uniqueTurns.put(key, existingTurn.withAdditionalSourceLine(turn.getLineNumber()));
      }
    }
    return new ArrayList<>(uniqueTurns.values());
  }

  private boolean isDeduplicatable(ChatTurn turn) {
    return !turn.getContent().isBlank()
        && (turn.getRole() == ChatRole.USER
            || turn.getRole() == ChatRole.ASSISTANT
            || turn.getRole() == ChatRole.SYSTEM);
  }

  private String dedupeKey(ChatTurn turn) {
    return turn.getRole()
        + "\n"
        + normalizeForDedupe(turn.getContent())
        + "\n"
        + normalizeForDedupe(turn.getEnvironment());
  }

  private String uniqueKey(String key, int lineNumber) {
    return key + "\n#line:" + lineNumber;
  }

  private String normalizeForDedupe(String value) {
    return value.trim().replaceAll("\\s+", " ");
  }

  String dedupeKeyForRawLine(String rawLine) {
    ChatTurn turn = parseLine(0, rawLine);
    if (turn == null) {
      return normalizeForDedupe(rawLine);
    }
    return dedupeKey(turn);
  }

  boolean isSearchResultLine(String rawLine) {
    ChatTurn turn = parseLine(0, rawLine);
    return turn != null && turn.getRole() != ChatRole.TOOL;
  }

  String sessionTitle(Path jsonlFile) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank()) {
          continue;
        }
        ChatTurn turn = parseLine(lineNumber, line);
        if (turn == null || turn.getRole() != ChatRole.USER) {
          continue;
        }
        String title = titleFromContent(turn.getContent());
        if (!title.isBlank()) {
          return title;
        }
      }
    }

    return jsonlFile.getFileName().toString();
  }

  private String titleFromContent(String content) {
    if (isLowValueTitleContent(content)) {
      return "";
    }
    String title =
        content
            .replaceAll("<[^>]+>", " ")
            .replaceAll("```[a-zA-Z0-9_-]*", " ")
            .trim()
            .replaceAll("\\s+", " ");
    if (isLowValueTitleContent(title)) {
      return "";
    }
    if (title.length() <= 90) {
      return title;
    }
    return title.substring(0, 87) + "...";
  }

  private boolean isLowValueTitleContent(String content) {
    String normalized = content.trim().toLowerCase(Locale.ROOT);
    return normalized.isBlank()
        || isInjectedCodexContext(normalized)
        || normalized.contains("<command-name>")
        || normalized.contains("<command-message>")
        || normalized.startsWith("tool result")
        || normalized.matches("/[a-z0-9_-]+");
  }

  private boolean isInjectedCodexContext(String normalized) {
    return normalized.startsWith("# agents.md instructions for ")
        || (normalized.contains("<instructions>")
            && normalized.contains("</instructions>")
            && normalized.contains("<environment_context>"));
  }

  private ChatTurn parseLine(int lineNumber, String rawLine) {
    try {
      JsonNode root = objectMapper.readTree(rawLine);
      return parseJsonLine(lineNumber, root, rawLine);
    } catch (Exception e) {
      LOGGER.log(System.Logger.Level.DEBUG, "Could not parse JSONL line " + lineNumber, e);
      return new ChatTurn(lineNumber, ChatRole.UNKNOWN, "", "", rawLine, false);
    }
  }

  private ChatTurn parseJsonLine(int lineNumber, JsonNode root, String rawLine) {
    String type = text(root, "type");
    if (isCodexRolloutType(type)) {
      return parseCodexLine(lineNumber, root, rawLine, type);
    }
    return parseClaudeLine(lineNumber, root, rawLine, type);
  }

  private boolean isLowValueCodexRoot(JsonNode root) {
    String type = text(root, "type");
    if ("turn_context".equals(type)) {
      return true;
    }
    if (!isCodexRolloutType(type)) {
      return false;
    }

    JsonNode payload = object(root, "payload");
    if ("event_msg".equals(type)) {
      return isLowValueCodexEvent(payload);
    }
    if ("response_item".equals(type)) {
      JsonNode item = firstObject(object(payload, "item"), object(payload, "response_item"), payload);
      return isLowValueCodexResponseItem(item);
    }
    return false;
  }

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

    if (isLowValueCodexRoot(root)) {
      return null;
    }

    if ("session_meta".equals(type)) {
      return new ChatTurn(
          lineNumber,
          ChatRole.SYSTEM,
          timestamp(root, payload),
          codexEnvironment(payload),
          metadataSummary(payload),
          true);
    }
    if ("compacted".equals(type)) {
      return new ChatTurn(
          lineNumber,
          ChatRole.SYSTEM,
          timestamp(root, payload),
          codexEnvironment(payload),
          firstText(contentFrom(payload.get("content")), text(payload, "message"), "Context compacted"),
          true);
    }
    if ("event_msg".equals(type)) {
      return parseCodexEventMsg(lineNumber, root, payload, rawLine);
    }
    return parseCodexResponseItem(lineNumber, root, candidate, rawLine);
  }

  private ChatTurn parseCodexEventMsg(
      int lineNumber, JsonNode root, JsonNode payload, String rawLine) {
    if (isLowValueCodexEvent(payload)) {
      return null;
    }

    String eventType = text(payload, "type");
    ChatRole role = roleForCodexEvent(eventType);
    boolean collapsed = role == ChatRole.TOOL || role == ChatRole.SYSTEM;
    String content =
        firstText(
            text(payload, "message"),
            text(payload, "last_agent_message"),
            text(payload, "aggregated_output"),
            commandSummary(payload),
            contentFrom(payload.get("content")),
            contentFrom(payload.get("output")),
            fallbackSummary(root, rawLine));

    return new ChatTurn(
        lineNumber,
        role,
        timestamp(root, payload),
        codexEnvironment(payload),
        content,
        collapsed);
  }

  private ChatTurn parseCodexResponseItem(
      int lineNumber, JsonNode root, JsonNode item, String rawLine) {
    if (isLowValueCodexResponseItem(item)) {
      return null;
    }

    ChatRole role = roleForCodexResponseItem(item);
    boolean collapsed = role == ChatRole.TOOL || role == ChatRole.SYSTEM;
    String content =
        firstText(
            contentFrom(item.get("content")),
            reasoningSummary(item),
            commandSummary(item),
            toolOutputSummary(item),
            fallbackSummary(root, rawLine));

    return new ChatTurn(
        lineNumber, role, timestamp(root, item), codexEnvironment(item), content, collapsed);
  }

  private boolean isLowValueCodexFunctionCall(JsonNode item) {
    if (!"function_call".equals(text(item, "type"))) {
      return false;
    }
    return !text(item, "name").isBlank() && !text(item, "arguments").isBlank();
  }

  private boolean isLowValueCodexEvent(JsonNode payload) {
    String eventType = text(payload, "type");
    if ("agent_reasoning".equals(eventType)) {
      return true;
    }
    return ("task_started".equals(eventType) || "token_count".equals(eventType))
        && !hasVisibleCodexEventContent(payload);
  }

  private boolean hasVisibleCodexEventContent(JsonNode payload) {
    return !firstText(
            text(payload, "message"),
            text(payload, "last_agent_message"),
            text(payload, "aggregated_output"),
            commandSummary(payload),
            contentFrom(payload.get("content")),
            contentFrom(payload.get("output")))
        .isBlank();
  }

  private boolean isLowValueCodexResponseItem(JsonNode item) {
    return isLowValueCodexFunctionCall(item)
        || isEmptyCodexReasoning(item)
        || isInjectedCodexContextItem(item);
  }

  private boolean isEmptyCodexReasoning(JsonNode item) {
    return "reasoning".equals(text(item, "type"))
        && firstText(contentFrom(item.get("content")), reasoningSummary(item)).isBlank();
  }

  private boolean isInjectedCodexContextItem(JsonNode item) {
    return "message".equals(text(item, "type"))
        && "user".equals(text(item, "role"))
        && isInjectedCodexContext(contentFrom(item.get("content")).trim().toLowerCase(Locale.ROOT));
  }

  private ChatRole roleForCodexEvent(String eventType) {
    String normalized = eventType.toLowerCase(Locale.ROOT);
    if (normalized.contains("user_message")) {
      return ChatRole.USER;
    }
    if (normalized.contains("agent_message") || normalized.contains("assistant")) {
      return ChatRole.ASSISTANT;
    }
    if (isToolType(normalized)) {
      return ChatRole.TOOL;
    }
    return ChatRole.SYSTEM;
  }

  private ChatRole roleForCodexResponseItem(JsonNode item) {
    String itemType = text(item, "type").toLowerCase(Locale.ROOT);
    String role = text(item, "role").toLowerCase(Locale.ROOT);
    if ("message".equals(itemType)) {
      return roleFromString(role);
    }
    if ("reasoning".equals(itemType) || itemType.contains("compaction")) {
      return ChatRole.SYSTEM;
    }
    if (isToolType(itemType)) {
      return ChatRole.TOOL;
    }
    return roleFromString(firstText(role, itemType));
  }

  private ChatTurn parseClaudeLine(int lineNumber, JsonNode root, String rawLine, String type) {
    if ("file-history-snapshot".equals(type)
        || "progress".equals(type)
        || ("system".equals(type) && "turn_duration".equals(text(root, "subtype")))) {
      return null;
    }

    JsonNode message = object(root, "message");
    ChatRole role = roleFromString(firstText(text(message, "role"), text(root, "role"), type));
    boolean toolLike = claudeLineHasToolPayload(root, message);
    if (toolLike) {
      role = ChatRole.TOOL;
    }
    boolean collapsed = toolLike || role == ChatRole.SYSTEM;
    String messageContent = contentFrom(message.get("content"));
    if (message.has("content") && messageContent.isBlank()) {
      return null;
    }
    String content =
        firstText(
            messageContent,
            contentFrom(root.get("content")),
            toolUseResultSummary(root.get("toolUseResult")),
            text(message, "content"),
            text(root, "content"),
            text(root, "summary"),
            fallbackSummary(root, rawLine));
    if (content.isBlank()) {
      return null;
    }

    return new ChatTurn(
        lineNumber,
        role,
        timestamp(root, message),
        claudeEnvironment(root),
        content,
        collapsed);
  }

  private boolean claudeLineHasToolPayload(JsonNode root, JsonNode message) {
    return !toolUseResultSummary(root.get("toolUseResult")).isBlank()
        || contentArrayHasOnlyToolBlocks(message.get("content"));
  }

  private boolean contentArrayHasOnlyToolBlocks(JsonNode content) {
    if (content == null || !content.isArray() || content.isEmpty()) {
      return false;
    }
    boolean sawToolBlock = false;
    for (JsonNode block : content) {
      String type = text(block, "type").toLowerCase(Locale.ROOT);
      if ("tool_use".equals(type) || "tool_result".equals(type)) {
        sawToolBlock = true;
        continue;
      }
      if (!type.isBlank() && !"thinking".equals(type)) {
        return false;
      }
    }
    return sawToolBlock;
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
    if (!node.isObject()) {
      return "";
    }

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
      default -> firstText(
          text(node, "text"),
          text(node, "content"),
          text(node, "input"),
          text(node, "output"),
          contentFrom(node.get("message")),
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
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isTextual()) {
      return node.asText();
    }
    return prettyJson(node);
  }

  private String codexToolCallSummary(JsonNode node) {
    String type = text(node, "type");
    if ("local_shell_call".equals(type)) {
      return commandSummary(node);
    }
    String name = firstText(text(node, "name"), text(node, "type"), "tool");
    String details =
        firstText(
            text(node, "arguments"),
            text(node, "input"),
            prettyJson(node.get("action")),
            prettyJson(node.get("arguments")));
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
    if (!renderedCommand.isBlank()) {
      return "Command: " + renderedCommand;
    }
    return output;
  }

  private String commandArray(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (!node.isArray()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (JsonNode part : node) {
      if (part.isTextual() || part.isNumber() || part.isBoolean()) {
        parts.add(part.asText());
      }
    }
    return String.join(" ", parts);
  }

  private String toolOutputSummary(JsonNode node) {
    JsonNode output = node.get("output");
    return firstText(
        contentFrom(output),
        text(node, "output"),
        text(node, "content"),
        contentFrom(node.get("content_items")),
        prettyJson(output));
  }

  private String reasoningSummary(JsonNode node) {
    return firstText(contentFrom(node.get("summary")), contentFrom(node.get("content")));
  }

  private String labeledBlock(String label, String content) {
    if (content == null || content.isBlank()) {
      return "[" + label + "]";
    }
    return "[" + label + "]\n" + content;
  }

  private String metadataSummary(JsonNode payload) {
    List<String> lines = new ArrayList<>();
    addIfPresent(lines, "Working directory", text(payload, "cwd"));
    addIfPresent(lines, "Git branch", gitBranch(payload));
    addIfPresent(lines, "Model provider", text(payload, "model_provider"));
    addIfPresent(lines, "Model", text(payload, "model"));
    addIfPresent(lines, "CLI version", firstText(text(payload, "cli_version"), text(payload, "version")));
    addIfPresent(lines, "Source", text(payload, "source"));
    addIfPresent(lines, "Originator", text(payload, "originator"));
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

  private ChatRole roleFromString(String value) {
    String normalized = value.toLowerCase(Locale.ROOT);
    if (normalized.contains("user")) {
      return ChatRole.USER;
    }
    if (normalized.contains("assistant") || normalized.contains("agent")) {
      return ChatRole.ASSISTANT;
    }
    if (normalized.contains("developer")
        || normalized.contains("system")
        || normalized.contains("meta")
        || normalized.contains("summary")) {
      return ChatRole.SYSTEM;
    }
    if (isToolType(normalized)) {
      return ChatRole.TOOL;
    }
    return ChatRole.UNKNOWN;
  }

  private boolean isToolType(String value) {
    return value.contains("tool")
        || value.contains("function")
        || value.contains("shell")
        || value.contains("exec")
        || value.contains("patch")
        || value.contains("command")
        || value.contains("web_search")
        || value.contains("image_generation");
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
    return firstText(
        text(node, "gitBranch"),
        text(node, "git_branch"),
        text(object(node, "git"), "branch"),
        text(object(node, "git_info"), "branch"));
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

  private JsonNode firstNode(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node != null && !node.isMissingNode() && !node.isNull()) {
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

  private String prettyJson(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return "";
    }
    if (node.isTextual() || node.isNumber() || node.isBoolean()) {
      return node.asText();
    }
    try {
      return prettyJsonWriter.writeValueAsString(node);
    } catch (Exception e) {
      return node.toString();
    }
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
