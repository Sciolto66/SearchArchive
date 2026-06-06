package nl.rowendu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.concurrent.atomic.AtomicReference;

final class ResultRenderer {
  private static final System.Logger LOGGER = System.getLogger(ResultRenderer.class.getName());

  private final JsonlTranscriptParser transcriptParser;
  private final ErrorReporter errorReporter;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ObjectWriter prettyJsonWriter = objectMapper.writerWithDefaultPrettyPrinter();

  @FunctionalInterface
  public interface ErrorReporter {
    void reportError(String message);
  }

  public ResultRenderer(JsonlTranscriptParser transcriptParser, ErrorReporter errorReporter) {
    this.transcriptParser = transcriptParser;
    this.errorReporter = errorReporter;
  }

  void showResult(SearchResult result) {
    if (result.getMode() == SearcherMode.JSONL_HISTORY) {
      showChatTranscript(result);
    } else {
      showArchiveResult(result);
    }
  }

  void showJsonlContextMenu(SearchResult result, javafx.scene.control.TableRow<?> row,
                             javafx.scene.input.ContextMenuEvent event) {
    ContextMenu menu = new ContextMenu();
    MenuItem chatViewItem = new MenuItem("Open Chat View");
    chatViewItem.setOnAction(e -> showChatTranscript(result));
    MenuItem rawJsonItem = new MenuItem("Open Raw JSON");
    rawJsonItem.setOnAction(e -> showJsonlResult(result));
    menu.getItems().addAll(chatViewItem, rawJsonItem);
    menu.show(row, event.getScreenX(), event.getScreenY());
    event.consume();
  }

  void showJsonlResult(SearchResult result) {
    String content;
    try {
      Object json = objectMapper.readValue(result.getRawContent(), Object.class);
      content = prettyJsonWriter.writeValueAsString(json);
    } catch (Exception e) {
      content = "Could not parse JSON line:\n" + e.getMessage() + "\n\n" + result.getRawContent();
    }
    showTextWindow(
        "JSONL Match - line " + result.getLineNumber(),
        result.getFilePath() + ":" + result.getLineNumber(),
        content);
  }

  void showArchiveResult(SearchResult result) {
    String content = "Archive:\n" + result.getFilePath() + "\n\nMatched entry:\n" + result.getLocation();
    showTextWindow("Archive Match", result.getLocation(), content);
  }

  void showChatTranscript(SearchResult selectedResult) {
    try {
      ChatTranscript transcript = transcriptParser.parse(selectedResult.getFilePath());
      showChatWindow(transcript, selectedResult);
    } catch (Exception e) {
      LOGGER.log(System.Logger.Level.ERROR, "Failed to parse JSONL transcript", e);
      errorReporter.reportError("Could not open chat view: " + e.getMessage());
    }
  }

  private void showChatWindow(ChatTranscript transcript, SearchResult selectedResult) {
    Stage stage = new Stage();
    stage.setTitle("Chat View");

    Label headerLabel = new Label(transcript.getSourceFile().toString());
    headerLabel.setWrapText(true);
    headerLabel.setStyle("-fx-font-weight: bold;");

    Button rawJsonButton = new Button("Raw JSON");
    rawJsonButton.setOnAction(event -> showJsonlResult(selectedResult));

    HBox headerBox = new HBox(10, headerLabel, rawJsonButton);
    headerBox.setAlignment(Pos.CENTER_LEFT);
    HBox.setHgrow(headerLabel, Priority.ALWAYS);

    VBox turnList = new VBox(10);
    turnList.setPadding(new Insets(12));
    AtomicReference<Node> highlightedNode = new AtomicReference<>();
    for (ChatTurn turn : transcript.getTurns()) {
      boolean highlighted = turnMatchesResult(turn, selectedResult);
      Node turnNode = createTurnNode(turn, highlighted);
      if (highlighted) highlightedNode.set(turnNode);
      turnList.getChildren().add(turnNode);
    }

    ScrollPane scrollPane = new ScrollPane(turnList);
    scrollPane.setFitToWidth(true);

    VBox root = new VBox(10, headerBox, scrollPane);
    root.setPadding(new Insets(12));
    VBox.setVgrow(scrollPane, Priority.ALWAYS);

    stage.setScene(new Scene(root, 920, 720));
    stage.show();
    scrollToNode(scrollPane, turnList, highlightedNode.get());
  }

  private boolean turnMatchesResult(ChatTurn turn, SearchResult selectedResult) {
    return turn.getLineNumber() > 0 && selectedResult.includesSourceLine(turn.getLineNumber());
  }

  private void scrollToNode(ScrollPane scrollPane, VBox content, Node node) {
    if (node == null) return;
    Platform.runLater(() -> {
      double viewportHeight = scrollPane.getViewportBounds().getHeight();
      double contentHeight = content.getBoundsInLocal().getHeight();
      double nodeY = node.getBoundsInParent().getMinY();
      if (contentHeight <= viewportHeight) {
        scrollPane.setVvalue(0);
        return;
      }
      double target = nodeY / (contentHeight - viewportHeight);
      scrollPane.setVvalue(Math.max(0, Math.min(1, target)));
    });
  }

  private Node createTurnNode(ChatTurn turn, boolean highlighted) {
    TextArea contentArea = selectableTextArea(emptyFallback(turn.getContent()));

    VBox card = new VBox(6, contentArea);
    if (!turn.getEnvironment().isBlank()) {
      Label environmentLabel = new Label(turn.getEnvironment());
      environmentLabel.setWrapText(true);
      environmentLabel.setStyle("-fx-text-fill: #5f6368; -fx-font-size: 11px;");
      card.getChildren().add(0, environmentLabel);
    }
    card.setPadding(new Insets(10));

    TitledPane pane = new TitledPane(turnHeader(turn), card);
    pane.setExpanded(!turn.isCollapsed());
    pane.setStyle(cardStyle(turn.getRole(), highlighted));
    return pane;
  }

  private TextArea selectableTextArea(String content) {
    TextArea textArea = new TextArea(content);
    textArea.setEditable(false);
    textArea.setWrapText(true);
    textArea.setPrefRowCount(Math.max(2, Math.min(14, content.split("\\R", -1).length + 1)));
    return textArea;
  }

  private String turnHeader(ChatTurn turn) {
    StringBuilder header = new StringBuilder();
    header.append(turn.getRole().getDisplayName()).append(" - line ").append(turn.getSourceLineLabel());
    if (!turn.getTimestamp().isBlank()) header.append(" - ").append(turn.getTimestamp());
    return header.toString();
  }

  private String emptyFallback(String value) {
    return value == null || value.isBlank() ? "(no displayable content)" : value;
  }

  private String cardStyle(ChatRole role, boolean highlighted) {
    String background = switch (role) {
      case USER -> "#eef5ff";
      case ASSISTANT -> "#f7f7f4";
      case SYSTEM -> "#f1f3f4";
      case TOOL -> "#fff7e6";
      case UNKNOWN -> "#f8f1f7";
    };
    String border = highlighted ? "#d93025" : "#d7dce1";
    String width = highlighted ? "2" : "1";
    return "-fx-background-color: " + background
        + "; -fx-border-color: " + border
        + "; -fx-border-width: " + width
        + "; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8;";
  }

  private void showTextWindow(String title, String header, String content) {
    Stage stage = new Stage();
    stage.setTitle(title);

    Label headerLabel = new Label(header);
    headerLabel.setWrapText(true);

    TextArea textArea = new TextArea(content);
    textArea.setEditable(false);
    textArea.setWrapText(false);

    VBox root = new VBox(10, headerLabel, textArea);
    root.setPadding(new Insets(12));
    VBox.setVgrow(textArea, Priority.ALWAYS);

    stage.setScene(new Scene(root, 820, 620));
    stage.show();
  }
}
