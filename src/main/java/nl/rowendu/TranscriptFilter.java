package nl.rowendu;

import java.util.List;

final class TranscriptFilter {
  List<ChatTurn> filterToolTurns(List<ChatTurn> turns) {
    return turns.stream().filter(turn -> turn.getRole() != ChatRole.TOOL).toList();
  }
}
