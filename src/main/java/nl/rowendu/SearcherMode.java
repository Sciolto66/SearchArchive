package nl.rowendu;

enum SearcherMode {
  ARCHIVE_FILENAME("Archive filename", "Filename to Search:", "Archive File:") {
    @Override
    Searcher createSearcher() {
      return new ArchiveFileSearcher();
    }
  },
  JSONL_HISTORY("JSONL history", "Text to Search:", "History Folder:") {
    @Override
    Searcher createSearcher() {
      return new JsonlHistorySearcher();
    }
  };

  private final String displayName;
  private final String searchLabel;
  private final String pathLabel;

  SearcherMode(String displayName, String searchLabel, String pathLabel) {
    this.displayName = displayName;
    this.searchLabel = searchLabel;
    this.pathLabel = pathLabel;
  }

  abstract Searcher createSearcher();

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
