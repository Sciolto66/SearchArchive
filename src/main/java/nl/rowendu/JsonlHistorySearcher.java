package nl.rowendu;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

final class JsonlHistorySearcher {
  private final JsonlTranscriptParser jsonlTranscriptParser = new JsonlTranscriptParser();

  List<SearchResult> search(Path rootFolder, String searchText, CancellationToken token)
      throws IOException {
    if (!Files.isDirectory(rootFolder)) {
      throw new IOException("History folder does not exist: " + rootFolder);
    }

    String needle = searchText.toLowerCase(Locale.ROOT);
    List<SearchResult> results = new ArrayList<>();

    try (Stream<Path> paths = Files.walk(rootFolder)) {
      List<Path> jsonlFiles =
          paths
              .filter(Files::isRegularFile)
              .filter(JsonlHistorySearcher::isJsonlFile)
              .sorted(Comparator.naturalOrder())
              .toList();

      for (Path jsonlFile : jsonlFiles) {
        if (token.isCancelled()) {
          break;
        }
        searchFile(jsonlFile, needle, token, results);
      }
    }

    return deduplicateResults(results);
  }

  private List<SearchResult> deduplicateResults(List<SearchResult> results) {
    Map<String, SearchResult> uniqueResults = new LinkedHashMap<>();
    for (SearchResult result : results) {
      String key =
          result.getFilePath()
              + "\n"
              + jsonlTranscriptParser.dedupeKeyForRawLine(result.getRawContent());
      SearchResult existingResult = uniqueResults.get(key);
      if (existingResult == null) {
        uniqueResults.put(key, result);
      } else {
        uniqueResults.put(key, existingResult.withAdditionalSourceLine(result.getLineNumber()));
      }
    }
    return new ArrayList<>(uniqueResults.values());
  }

  private static boolean isJsonlFile(Path path) {
    return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jsonl");
  }

  private void searchFile(
      Path jsonlFile, String needle, CancellationToken token, List<SearchResult> results)
      throws IOException {
    String title = null;
    try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        if (token.isCancelled()) {
          return;
        }
        lineNumber++;
        if (line.toLowerCase(Locale.ROOT).contains(needle)
            && jsonlTranscriptParser.isSearchResultLine(line)) {
          if (title == null) {
            title = jsonlTranscriptParser.sessionTitle(jsonlFile);
          }
          results.add(
              new SearchResult(
                  SearchMode.JSONL_HISTORY,
                  jsonlFile,
                  jsonlFile.toString(),
                  title,
                  lineNumber,
                  preview(line),
                  line));
        }
      }
    }
  }

  private String preview(String line) {
    String compact = line.trim().replaceAll("\\s+", " ");
    if (compact.length() <= 180) {
      return compact;
    }
    return compact.substring(0, 177) + "...";
  }
}
