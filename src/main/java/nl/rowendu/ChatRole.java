package nl.rowendu;

enum ChatRole {
  USER("User"),
  ASSISTANT("Assistant"),
  SYSTEM("System"),
  TOOL("Tool"),
  UNKNOWN("Unknown");

  private final String displayName;

  ChatRole(String displayName) {
    this.displayName = displayName;
  }

  String getDisplayName() {
    return displayName;
  }
}
