package nl.rowendu;

import java.nio.file.Path;
import java.util.List;

final class ChatTranscript {
  private final Path sourceFile;
  private final List<ChatTurn> turns;

  ChatTranscript(Path sourceFile, List<ChatTurn> turns) {
    this.sourceFile = sourceFile;
    this.turns = List.copyOf(turns);
  }

  Path getSourceFile() {
    return sourceFile;
  }

  List<ChatTurn> getTurns() {
    return turns;
  }
}
