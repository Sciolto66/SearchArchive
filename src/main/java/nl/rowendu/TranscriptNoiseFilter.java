package nl.rowendu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.util.ArrayList;
import java.util.List;

final class TranscriptNoiseFilter {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ObjectWriter PRETTY_JSON_WRITER = MAPPER.writerWithDefaultPrettyPrinter();
  private final JsonlLineParser lineParser = new JsonlLineParser();

  boolean isSearchResultLine(String rawLine) {
    ChatTurn turn = lineParser.parseLine(0, rawLine);
    return turn != null && turn.getRole() != ChatRole.TOOL;
  }

  String dedupeKeyForRawLine(String rawLine) {
    ChatTurn turn = lineParser.parseLine(0, rawLine);
    if (turn == null) return normalize(rawLine);
    return turn.getRole().name()
        + "\n"
        + normalize(turn.getContent())
        + "\n"
        + normalize(turn.getEnvironment());
  }

  // ---- Static noise-checking (shared with JsonlLineParser) ----

  static boolean isLowValueCodexRoot(JsonNode root) {
    String type = t(root, "type");
    if ("turn_context".equals(type)) return true;
    if (!isCodexRolloutType(type)) return false;
    JsonNode payload = obj(root, "payload");
    if ("event_msg".equals(type)) return isLowValueEvent(payload);
    if ("response_item".equals(type)) {
      JsonNode item = firstObj(obj(payload, "item"), obj(payload, "response_item"), payload);
      return isLowValueItem(item);
    }
    return false;
  }

  static boolean isCodexRolloutType(String type) {
    return "session_meta".equals(type) || "response_item".equals(type)
        || "event_msg".equals(type) || "compacted".equals(type)
        || "turn_context".equals(type);
  }

  private static boolean isLowValueEvent(JsonNode payload) {
    String et = t(payload, "type");
    if ("agent_reasoning".equals(et)) return true;
    return ("task_started".equals(et) || "token_count".equals(et))
        && !hasVisibleEventContent(payload);
  }

  private static boolean hasVisibleEventContent(JsonNode payload) {
    return !firstText(
        t(payload, "message"),
        t(payload, "last_agent_message"),
        t(payload, "aggregated_output"),
        commandSummary(payload),
        contentFrom(payload.get("content")),
        contentFrom(payload.get("output"))).isBlank();
  }

  private static String commandSummary(JsonNode node) {
    JsonNode action = obj(node, "action");
    JsonNode command = firstNode(node.get("command"), action.get("command"));
    String renderedCommand = commandArray(command);
    String output = firstText(t(node, "aggregated_output"), t(node, "stdout"), t(node, "stderr"));
    if (!renderedCommand.isBlank() && !output.isBlank()) {
      return "Command: " + renderedCommand + "\n\n" + output;
    }
    if (!renderedCommand.isBlank()) return "Command: " + renderedCommand;
    return output;
  }

  private static String commandArray(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    if (node.isTextual()) return node.asText();
    if (!node.isArray()) return "";
    List<String> parts = new ArrayList<>();
    for (JsonNode part : node) {
      if (part.isTextual() || part.isNumber() || part.isBoolean()) parts.add(part.asText());
    }
    return String.join(" ", parts);
  }

  private static String contentFrom(JsonNode node) {
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

  private static String contentBlock(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    if (node.isTextual()) return node.asText();
    if (!node.isObject()) return "";
    String type = t(node, "type");
    return switch (type) {
      case "text", "input_text", "output_text" -> firstText(t(node, "text"), t(node, "content"));
      case "thinking" -> labeledBlock("Thinking", firstText(t(node, "thinking"), t(node, "text")));
      case "tool_use" -> labeledBlock("Tool use: " + firstText(t(node, "name"), "tool"),
          prettyJson(node.get("input")));
      case "tool_result" -> labeledBlock("Tool result",
          firstText(contentFrom(node.get("content")), prettyJson(node)));
      case "function_call", "custom_tool_call", "local_shell_call", "web_search_call",
          "tool_search_call" -> toolCallSummary(node);
      case "function_call_output", "custom_tool_call_output", "tool_search_output" ->
          firstText(contentFrom(node.get("output")), t(node, "output"), t(node, "content"),
              contentFrom(node.get("content_items")), prettyJson(node.get("output")));
      default -> firstText(t(node, "text"), t(node, "content"), t(node, "input"),
          t(node, "output"), contentFrom(node.get("message")),
          !type.isBlank() ? "[" + type + "]" : "");
    };
  }

  private static String toolCallSummary(JsonNode node) {
    if ("local_shell_call".equals(t(node, "type"))) return commandSummary(node);
    String name = firstText(t(node, "name"), t(node, "type"), "tool");
    String details = firstText(t(node, "arguments"), t(node, "input"),
        prettyJson(node.get("action")), prettyJson(node.get("arguments")));
    return labeledBlock("Tool call: " + name, details);
  }

  private static boolean isLowValueItem(JsonNode item) {
    return isLowValueFncall(item) || isEmptyReasoning(item) || isInjectedContextItem(item);
  }

  private static boolean isLowValueFncall(JsonNode item) {
    if (!"function_call".equals(t(item, "type"))) return false;
    return !t(item, "name").isBlank() && !t(item, "arguments").isBlank();
  }

  private static boolean isEmptyReasoning(JsonNode item) {
    return "reasoning".equals(t(item, "type"))
        && firstText(contentFrom(item.get("content")), reasoningSummary(item)).isBlank();
  }

  private static String reasoningSummary(JsonNode node) {
    return firstText(contentFrom(node.get("summary")), contentFrom(node.get("content")));
  }

  private static boolean isInjectedContextItem(JsonNode item) {
    if (!"message".equals(t(item, "type")) || !"user".equals(t(item, "role"))) return false;
    String body = contentFrom(item.get("content"));
    return isInjectedContext(body.trim().toLowerCase(java.util.Locale.ROOT));
  }

  private static boolean isInjectedContext(String n) {
    return n.startsWith("# agents.md instructions for ")
        || (n.contains("<instructions>") && n.contains("</instructions>")
            && n.contains("<environment_context>"));
  }

  // ---- Static JSON helpers ----

  private static String t(JsonNode node, String field) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    JsonNode child = node.get(field);
    if (child == null || child.isNull() || child.isMissingNode()) return "";
    if (child.isTextual() || child.isNumber() || child.isBoolean()) return child.asText();
    return "";
  }

  private static JsonNode obj(JsonNode node, String field) {
    if (node == null || node.isMissingNode() || node.isNull()) return MAPPER.missingNode();
    JsonNode child = node.get(field);
    if (child != null && child.isObject()) return child;
    return MAPPER.missingNode();
  }

  private static JsonNode firstObj(JsonNode... nodes) {
    for (JsonNode n : nodes) {
      if (n != null && n.isObject() && !n.isMissingNode()) return n;
    }
    return MAPPER.missingNode();
  }

  private static JsonNode firstNode(JsonNode... nodes) {
    for (JsonNode n : nodes) {
      if (n != null && !n.isMissingNode() && !n.isNull()) return n;
    }
    return MAPPER.missingNode();
  }

  private static String prettyJson(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    if (node.isTextual() || node.isNumber() || node.isBoolean()) return node.asText();
    try {
      return PRETTY_JSON_WRITER.writeValueAsString(node);
    } catch (Exception e) {
      return node.toString();
    }
  }

  private static String firstText(String... values) {
    for (String v : values) { if (v != null && !v.isBlank()) return v; }
    return "";
  }

  private static String labeledBlock(String label, String content) {
    if (content == null || content.isBlank()) return "[" + label + "]";
    return "[" + label + "]\n" + content;
  }

  private static String normalize(String value) {
    return value.trim().replaceAll("\\s+", " ");
  }
}
