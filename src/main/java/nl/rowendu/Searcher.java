package nl.rowendu;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

interface Searcher {
  SearcherMode getMode();
  List<SearchResult> search(Path path, String query, CancellationToken token) throws IOException;

  default boolean isArchiveSupported(String fileName) {
    return true;
  }
}
