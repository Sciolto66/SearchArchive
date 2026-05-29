package nl.rowendu;

import java.util.Set;
import java.util.TreeSet;

final class ChatTurn {
  private final int lineNumber;
  private final Set<Integer> sourceLineNumbers;
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
    this(lineNumber, Set.of(lineNumber), role, timestamp, environment, content, collapsed);
  }

  private ChatTurn(
      int lineNumber,
      Set<Integer> sourceLineNumbers,
      ChatRole role,
      String timestamp,
      String environment,
      String content,
      boolean collapsed) {
    this.lineNumber = lineNumber;
    this.sourceLineNumbers = Set.copyOf(sourceLineNumbers);
    this.role = role;
    this.timestamp = timestamp;
    this.environment = environment;
    this.content = content;
    this.collapsed = collapsed;
  }

  ChatTurn withAdditionalSourceLine(int sourceLineNumber) {
    TreeSet<Integer> mergedLineNumbers = new TreeSet<>(sourceLineNumbers);
    mergedLineNumbers.add(sourceLineNumber);
    return new ChatTurn(
        lineNumber, mergedLineNumbers, role, timestamp, environment, content, collapsed);
  }

  int getLineNumber() {
    return lineNumber;
  }

  boolean includesSourceLine(int sourceLineNumber) {
    return sourceLineNumbers.contains(sourceLineNumber);
  }

  String getSourceLineLabel() {
    if (sourceLineNumbers.size() == 1) {
      return Integer.toString(lineNumber);
    }
    return new TreeSet<>(sourceLineNumbers).stream()
        .map(String::valueOf)
        .reduce((left, right) -> left + ", " + right)
        .orElse(Integer.toString(lineNumber));
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
