package nl.rowendu;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

final class SearchResult {
  private final SearcherMode mode;
  private final Path filePath;
  private final String location;
  private final String title;
  private final int lineNumber;
  private final SortedSet<Integer> sourceLineNumbers;
  private final String displayText;
  private final String rawContent;

  SearchResult(SearcherMode mode, Path filePath, String location,
      int lineNumber, String displayText, String rawContent) {
    this(mode, filePath, location, mode.toString(), lineNumber, displayText, rawContent);
  }

  SearchResult(SearcherMode mode, Path filePath, String location, String title,
      int lineNumber, String displayText, String rawContent) {
    this(mode, filePath, location, title, lineNumber, Set.of(lineNumber), displayText, rawContent);
  }

  private SearchResult(SearcherMode mode, Path filePath, String location, String title,
      int lineNumber, Set<Integer> sourceLineNumbers,
      String displayText, String rawContent) {
    this.mode = mode;
    this.filePath = filePath;
    this.location = location;
    this.title = title;
    this.lineNumber = lineNumber;
    this.sourceLineNumbers = sortedCopy(sourceLineNumbers);
    this.displayText = displayText;
    this.rawContent = rawContent;
  }

  SearchResult withAdditionalSourceLine(int sourceLineNumber) {
    TreeSet<Integer> merged = new TreeSet<>(sourceLineNumbers);
    merged.add(sourceLineNumber);
    return new SearchResult(mode, filePath, location, title, lineNumber, merged, displayText, rawContent);
  }

  SearcherMode getMode() {
    return mode;
  }

  Path getFilePath() {
    return filePath;
  }

  String getLocation() {
    return location;
  }

  String getTitle() {
    return title;
  }

  int getLineNumber() {
    return lineNumber;
  }

  boolean includesSourceLine(int sourceLineNumber) {
    return sourceLineNumbers.contains(sourceLineNumber);
  }

  String getLineLabel() {
    if (sourceLineNumbers.size() == 1) {
      return lineNumber > 0 ? Integer.toString(lineNumber) : "";
    }
    return sourceLineNumbers.stream()
        .map(String::valueOf)
        .reduce((left, right) -> left + ", " + right)
        .orElse(Integer.toString(lineNumber));
  }

  String getDisplayText() {
    return displayText;
  }

  String getRawContent() {
    return rawContent;
  }

  private SortedSet<Integer> sortedCopy(Set<Integer> values) {
    return Collections.unmodifiableSortedSet(new TreeSet<>(values));
  }

  @Override
  public String toString() {
    return "SearchResult{"
        + "mode=" + mode + ", filePath=" + filePath
        + ", location='" + location + '\''
        + ", title='" + title + '\''
        + ", lineNumber=" + lineNumber
        + ", sourceLineNumbers=" + sourceLineNumbers
        + ", displayText='" + displayText + '\''
        + ", rawContentLength=" + rawContent.length() + '}';
  }
}
