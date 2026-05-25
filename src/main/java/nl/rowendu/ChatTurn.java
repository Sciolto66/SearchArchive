package nl.rowendu;

final class ChatTurn {
  private final int lineNumber;
  private final ChatRole role;
  private final String timestamp;
  private final String environment;
  private final String content;
  private final boolean collapsed;

  ChatTurn(
      int lineNumber,
      ChatRole role,
      String timestamp,
      String environment,
      String content,
      boolean collapsed) {
    this.lineNumber = lineNumber;
    this.role = role;
    this.timestamp = timestamp;
    this.environment = environment;
    this.content = content;
    this.collapsed = collapsed;
  }

  int getLineNumber() {
    return lineNumber;
  }

  ChatRole getRole() {
    return role;
  }

  String getTimestamp() {
    return timestamp;
  }

  String getEnvironment() {
    return environment;
  }

  String getContent() {
    return content;
  }

  boolean isCollapsed() {
    return collapsed;
  }
}
