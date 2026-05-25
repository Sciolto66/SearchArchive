package nl.rowendu;

import java.nio.file.Path;

final class SearchResult {
  private final SearchMode mode;
  private final Path filePath;
  private final String location;
  private final int lineNumber;
  private final String displayText;
  private final String rawContent;

  SearchResult(
      SearchMode mode,
      Path filePath,
      String location,
      int lineNumber,
      String displayText,
      String rawContent) {
    this.mode = mode;
    this.filePath = filePath;
    this.location = location;
    this.lineNumber = lineNumber;
    this.displayText = displayText;
    this.rawContent = rawContent;
  }

  SearchMode getMode() {
    return mode;
  }

  Path getFilePath() {
    return filePath;
  }

  String getLocation() {
    return location;
  }

  int getLineNumber() {
    return lineNumber;
  }

  String getDisplayText() {
    return displayText;
  }

  String getRawContent() {
    return rawContent;
  }
}
