package nl.rowendu;

enum SearchMode {
  ARCHIVE_FILENAME("Archive filename", "Filename to Search:", "Archive File:"),
  JSONL_HISTORY("JSONL history", "Text to Search:", "History Folder:");

  private final String displayName;
  private final String searchLabel;
  private final String pathLabel;

  SearchMode(String displayName, String searchLabel, String pathLabel) {
    this.displayName = displayName;
    this.searchLabel = searchLabel;
    this.pathLabel = pathLabel;
  }

  String getSearchLabel() {
    return searchLabel;
  }

  String getPathLabel() {
    return pathLabel;
  }

  @Override
  public String toString() {
    return displayName;
  }
}
