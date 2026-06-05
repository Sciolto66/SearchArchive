package nl.rowendu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class TranscriptNoiseFilter {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  boolean isSearchResultLine(String rawLine) {
    JsonNode root = parseSafe(rawLine);
    if (root == null) return false;
    return !isLowValueCodexRoot(root) && !isNoiseLine(root);
  }

  String dedupeKeyForRawLine(String rawLine) {
    JsonNode root = parseSafe(rawLine);
    if (root != null) {
      ChatRole role = extractRole(root);
      String content = extractContent(root);
      String environment = extractEnvironment(root);
      return role.name() + "\n" + normalize(content) + "\n" + normalize(environment);
    }
    return normalize(rawLine);
  }

  private JsonNode parseSafe(String rawLine) {
    try {
      return MAPPER.readTree(rawLine);
    } catch (Exception e) {
      return null;
    }
  }

  private ChatRole extractRole(JsonNode root) {
    String type = t(root, "type");

    // Codex rollout types: delegate to codex role extraction
    if (isCodexRolloutType(type)) {
      return extractCodexRole(root, type);
    }

    // Simple message format: {"type":"message","role":"developer","content":"..."}
    if ("message".equals(type)) {
      String role = t(root, "role");
      return roleFromString(role);
    }

    // Claude-style: role in message sub-object or root
    JsonNode message = obj(root, "message");
    String role = t(message, "role");
    if (role.isBlank()) role = t(root, "role");
    if (role.isBlank()) role = type;
    return roleFromString(role);
  }

  private ChatRole extractCodexRole(JsonNode root, String type) {
    JsonNode payload = obj(root, "payload");
    JsonNode item = firstObj(obj(payload, "item"), obj(payload, "response_item"));

    if ("event_msg".equals(type)) {
      return roleForEvent(t(payload, "type"));
    }

    // response_item, session_meta, compacted
    if (item != null && !item.isMissingNode()) {
      String it = t(item, "type").toLowerCase(java.util.Locale.ROOT);
      String role = t(item, "role").toLowerCase(java.util.Locale.ROOT);
      if ("message".equals(it)) return roleFromString(role);
      if ("reasoning".equals(it) || it.contains("compaction")) return ChatRole.SYSTEM;
      if (isToolType(it)) return ChatRole.TOOL;
    }
    return ChatRole.SYSTEM;
  }

  private String extractContent(JsonNode root) {
    String type = t(root, "type");

    // Codex response_item
    if ("response_item".equals(type)) {
      JsonNode payload = obj(root, "payload");
      JsonNode item = firstObj(obj(payload, "item"), obj(payload, "response_item"), payload);
      String content = contentFrom(item.get("content"));
      if (!content.isBlank()) return content;
      return t(item, "text");
    }

    // Codex event_msg
    if ("event_msg".equals(type)) {
      JsonNode payload = obj(root, "payload");
      return firstText(t(payload, "message"), t(payload, "last_agent_message"),
          t(payload, "aggregated_output"), contentFrom(payload.get("content")),
          contentFrom(payload.get("output")));
    }

    // Simple message: {"type":"message","role":"developer","content":"..."}
    if ("message".equals(type)) {
      return contentFrom(root.get("content"));
    }

    // Claude-style
    JsonNode message = obj(root, "message");
    String content = contentFrom(message.get("content"));
    if (!content.isBlank()) return content;
    return contentFrom(root.get("content"));
  }

  private String contentFrom(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) return "";
    if (node.isTextual()) return node.asText();
    if (node.isArray()) {
      StringBuilder sb = new StringBuilder();
      for (JsonNode child : node) {
        String bt = t(child, "type");
        if ("text".equals(bt) || "input_text".equals(bt) || "output_text".equals(bt)) {
          String v = firstText(t(child, "text"), t(child, "content"));
          if (!v.isBlank()) { if (sb.length() > 0) sb.append("\n\n"); sb.append(v); }
        }
      }
      return sb.toString();
    }
    if (node.isObject()) {
      return firstText(t(node, "text"), t(node, "content"));
    }
    return "";
  }

  private String extractEnvironment(JsonNode root) {
    return firstText(t(obj(root, "payload"), "cwd"), t(root, "cwd"));
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
        && firstText(t(payload, "message"), t(payload, "last_agent_message"),
            t(payload, "aggregated_output")).isBlank();
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
        && firstText(t(item, "summary"), t(item, "content")).isBlank();
  }

  private static boolean isInjectedContextItem(JsonNode item) {
    if (!"message".equals(t(item, "type")) || !"user".equals(t(item, "role"))) return false;
    JsonNode content = item.get("content");
    String body = "";
    if (content != null && content.isArray()) {
      for (JsonNode block : content) {
        if ("input_text".equals(t(block, "type"))) {
          body = t(block, "text");
          break;
        }
      }
    }
    return isInjectedContext(body.trim().toLowerCase(java.util.Locale.ROOT));
  }

  private static boolean isNoiseLine(JsonNode root) {
    String type = t(root, "type");
    if ("file-history-snapshot".equals(type) || "progress".equals(type)) return true;
    if ("system".equals(type) && "turn_duration".equals(t(root, "subtype"))) return true;
    return contentArrayToolOnly(obj(root, "message").get("content"));
  }

  private static boolean contentArrayToolOnly(JsonNode content) {
    if (content == null || !content.isArray() || content.isEmpty()) return false;
    boolean sawTool = false;
    for (JsonNode block : content) {
      String bt = t(block, "type").toLowerCase(java.util.Locale.ROOT);
      if ("tool_use".equals(bt) || "tool_result".equals(bt)) { sawTool = true; continue; }
      if (!bt.isBlank() && !"thinking".equals(bt)) return false;
    }
    return sawTool;
  }

  private static boolean isInjectedContext(String n) {
    return n.startsWith("# agents.md instructions for ")
        || (n.contains("<instructions>") && n.contains("</instructions>")
            && n.contains("<environment_context>"));
  }

  private static boolean isToolType(String v) {
    return v.contains("tool") || v.contains("function") || v.contains("shell")
        || v.contains("exec") || v.contains("patch") || v.contains("command")
        || v.contains("web_search") || v.contains("image_generation");
  }

  private static ChatRole roleFromString(String v) {
    String n = v.toLowerCase(java.util.Locale.ROOT);
    if (n.contains("user")) return ChatRole.USER;
    if (n.contains("assistant") || n.contains("agent")) return ChatRole.ASSISTANT;
    if (n.contains("developer") || n.contains("system")
        || n.contains("meta") || n.contains("summary")) return ChatRole.SYSTEM;
    if (isToolType(n)) return ChatRole.TOOL;
    return ChatRole.UNKNOWN;
  }

  private static ChatRole roleForEvent(String eventType) {
    String n = eventType.toLowerCase(java.util.Locale.ROOT);
    if (n.contains("user_message")) return ChatRole.USER;
    if (n.contains("agent_message") || n.contains("assistant")) return ChatRole.ASSISTANT;
    if (isToolType(n)) return ChatRole.TOOL;
    return ChatRole.SYSTEM;
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

  private static String firstText(String... values) {
    for (String v : values) { if (v != null && !v.isBlank()) return v; }
    return "";
  }

  private static String normalize(String value) {
    return value.trim().replaceAll("\\s+", " ");
  }
}
