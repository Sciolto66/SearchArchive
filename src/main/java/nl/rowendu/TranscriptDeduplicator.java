package nl.rowendu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TranscriptDeduplicator {
  List<ChatTurn> deduplicateTurns(List<ChatTurn> turns) {
    Map<String, ChatTurn> uniqueTurns = new LinkedHashMap<>();
    for (ChatTurn turn : turns) {
      String key = dedupeKey(turn);
      ChatTurn existingTurn = uniqueTurns.get(key);
      if (!isDeduplicatable(turn)) {
        uniqueTurns.put(uniqueKey(key, turn.getLineNumber()), turn);
      } else if (existingTurn == null) {
        uniqueTurns.put(key, turn);
      } else {
        uniqueTurns.put(key, existingTurn.withAdditionalSourceLine(turn.getLineNumber()));
      }
    }
    return new ArrayList<>(uniqueTurns.values());
  }

  private boolean isDeduplicatable(ChatTurn turn) {
    return !turn.getContent().isBlank()
        && (turn.getRole() == ChatRole.USER
            || turn.getRole() == ChatRole.ASSISTANT
            || turn.getRole() == ChatRole.SYSTEM);
  }

  private String dedupeKey(ChatTurn turn) {
    return turn.getRole().name()
        + "\n"
        + normalizeForDedupe(turn.getContent())
        + "\n"
        + normalizeForDedupe(turn.getEnvironment());
  }

  private String uniqueKey(String key, int lineNumber) {
    return key + "\n#line:" + lineNumber;
  }

  private String normalizeForDedupe(String value) {
    return value.trim().replaceAll("\\s+", " ");
  }
}
