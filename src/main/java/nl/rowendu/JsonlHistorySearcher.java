package nl.rowendu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class JsonlHistorySearcher {
  private static final System.Logger LOGGER =
      System.getLogger(JsonlHistorySearcher.class.getName());

  private final JsonlTranscriptParser jsonlTranscriptParser = new JsonlTranscriptParser();

  List<SearchResult> search(Path rootFolder, String searchText, CancellationToken token)
      throws IOException {
    if (!Files.isDirectory(rootFolder)) {
      throw new IOException("History folder does not exist: " + rootFolder);
    }

    String needle = searchText.toLowerCase(Locale.ROOT);
    List<SearchResult> results = new ArrayList<>();
    Map<Path, String> titleCache = new LinkedHashMap<>();

    Files.walkFileTree(
        rootFolder,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return token.isCancelled() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (token.isCancelled()) {
              return FileVisitResult.TERMINATE;
            }
            if (attrs.isRegularFile() && isJsonlFile(file)) {
              try {
                searchFile(file, needle, token, titleCache, results);
              } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Could not search JSONL file: " + file, e);
              }
            }
            return token.isCancelled() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            LOGGER.log(
                System.Logger.Level.WARNING,
                "Could not access path during JSONL search: " + file,
                exc);
            return token.isCancelled() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
          }
        });

    return deduplicateResults(results);
  }

  private String titleFor(Path jsonlFile, Map<Path, String> titleCache) throws IOException {
    try {
      return titleCache.computeIfAbsent(jsonlFile, this::sessionTitleUnchecked);
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  private String sessionTitleUnchecked(Path jsonlFile) {
    try {
      return jsonlTranscriptParser.sessionTitle(jsonlFile);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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
      Path jsonlFile,
      String needle,
      CancellationToken token,
      Map<Path, String> titleCache,
      List<SearchResult> results)
      throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
      String title = null;
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
            title = titleFor(jsonlFile, titleCache);
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
