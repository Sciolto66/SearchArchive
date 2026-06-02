package nl.rowendu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import java.util.logging.LogManager;

public class ArchiveSearcher extends Application {
  private static final System.Logger LOGGER = System.getLogger(ArchiveSearcher.class.getName());
  private static final String LAST_JSONL_FOLDER_KEY = "jsonlHistory.lastFolder";
  private static final String LAST_SEARCH_MODE_KEY = "search.lastMode";

  private final ArchiveFileSearcher archiveFileSearcher = new ArchiveFileSearcher();
  private final JsonlHistorySearcher jsonlHistorySearcher = new JsonlHistorySearcher();
  private final JsonlTranscriptParser jsonlTranscriptParser = new JsonlTranscriptParser();
  private final Preferences preferences = Preferences.userNodeForPackage(ArchiveSearcher.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ObjectWriter prettyJsonWriter = objectMapper.writerWithDefaultPrettyPrinter();

  private ComboBox<SearchMode> modeBox;
  private Label searchLabel;
  private TextField searchTextField;
  private Label pathLabel;
  private TextField pathField;
  private Button browseButton;
  private Button startButton;
  private Button cancelButton;
  private ProgressIndicator progressIndicator;
  private TableView<SearchResult> resultsTable;
  private Task<List<SearchResult>> currentSearchTask;

  public static void main(String[] args) {
    File appDir = new File(System.getProperty("user.home"), ".archivesearcher");
    if (!appDir.exists()) {
      appDir.mkdirs();
    }

    try (InputStream is = ArchiveSearcher.class.getResourceAsStream("/logging.properties")) {
      if (is != null) {
        LogManager.getLogManager().readConfiguration(is);
      } else {
        System.err.println("WARNING: logging.properties not found in classpath.");
      }
    } catch (Exception e) {
      System.err.println("Failed to initialize custom logging configuration: " + e.getMessage());
    }

    launch(args);
  }

  @Override
  public void start(Stage primaryStage) {
    MenuItem quitMenuItem = new MenuItem("Quit");
    quitMenuItem.setOnAction(e -> Platform.exit());
    MenuBar menuBar = new MenuBar(new Menu("File", null, quitMenuItem));

    modeBox = new ComboBox<>(FXCollections.observableArrayList(SearchMode.values()));
    modeBox.getSelectionModel().select(lastSearchMode());
    modeBox.setPrefWidth(180);
    modeBox.valueProperty().addListener((obs, oldMode, newMode) -> applyMode());

    searchLabel = new Label();
    searchTextField = new TextField();
    searchTextField.setPrefWidth(520);
    searchTextField.setOnAction(e -> runSearchIfReady());

    pathLabel = new Label();
    pathField = new TextField();
    pathField.setEditable(false);
    pathField.setPrefWidth(520);

    browseButton = new Button("Browse...");
    browseButton.setOnAction(e -> choosePath(primaryStage));

    startButton = new Button("Start Search");
    startButton.setDisable(true);
    startButton.setOnAction(e -> runSearchTask());

    cancelButton = new Button("Cancel");
    cancelButton.setDisable(true);
    cancelButton.setOnAction(
        e -> {
          if (currentSearchTask != null) {
            currentSearchTask.cancel();
          }
        });

    progressIndicator = new ProgressIndicator();
    progressIndicator.setVisible(false);
    progressIndicator.setPrefSize(20, 20);

    GridPane inputGrid = new GridPane();
    inputGrid.setHgap(10);
    inputGrid.setVgap(12);
    inputGrid.add(new Label("Search Mode:"), 0, 0);
    inputGrid.add(modeBox, 1, 0);
    inputGrid.add(searchLabel, 0, 1);
    inputGrid.add(searchTextField, 1, 1);
    inputGrid.add(pathLabel, 0, 2);
    inputGrid.add(pathField, 1, 2);
    inputGrid.add(browseButton, 2, 2);

    HBox actionBox = new HBox(12, startButton, cancelButton, progressIndicator);
    actionBox.setAlignment(Pos.CENTER_LEFT);

    VBox inputBox = new VBox(14, inputGrid, actionBox);
    inputBox.setPadding(new Insets(15));

    resultsTable = createResultsTable();

    BorderPane topContainer = new BorderPane();
    topContainer.setTop(menuBar);
    topContainer.setCenter(inputBox);

    BorderPane root = new BorderPane();
    root.setTop(topContainer);
    root.setCenter(resultsTable);
    BorderPane.setMargin(resultsTable, new Insets(10));

    searchTextField.textProperty().addListener((obs, oldValue, newValue) -> updateButtonState());
    pathField.textProperty().addListener((obs, oldValue, newValue) -> updateButtonState());
    applyMode();

    Scene scene = new Scene(root, 1225, 560);
    primaryStage.setTitle("Archive Searcher");
    primaryStage.setScene(scene);
    primaryStage.show();
  }

  private TableView<SearchResult> createResultsTable() {
    TableView<SearchResult> table = new TableView<>();
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setPlaceholder(new Label("No results"));

    TableColumn<SearchResult, String> titleColumn = new TableColumn<>("Title");
    titleColumn.setCellValueFactory(
        data -> new ReadOnlyStringWrapper(data.getValue().getTitle()));
    titleColumn.setPrefWidth(760);

    TableColumn<SearchResult, String> fileColumn = new TableColumn<>("File");
    fileColumn.setCellValueFactory(
        data -> new ReadOnlyStringWrapper(data.getValue().getFilePath().getFileName().toString()));
    fileColumn.setPrefWidth(360);

    TableColumn<SearchResult, String> lineColumn = new TableColumn<>("Line");
    lineColumn.setCellValueFactory(
        data ->
           new ReadOnlyStringWrapper(data.getValue().getLineLabel())
        );
    lineColumn.setPrefWidth(70);

    table.getColumns().add(titleColumn);
    table.getColumns().add(fileColumn);
    table.getColumns().add(lineColumn);
    table.setRowFactory(
        view -> {
          var row = new javafx.scene.control.TableRow<SearchResult>();
          row.setOnMouseClicked(
              event -> {
                if (!row.isEmpty()
                    && event.getButton() == MouseButton.PRIMARY
                    && event.getClickCount() == 2) {
                  showResult(row.getItem());
                }
              });
          row.setOnContextMenuRequested(
              event -> {
                if (!row.isEmpty() && row.getItem().getMode() == SearchMode.JSONL_HISTORY) {
                  ContextMenu menu = new ContextMenu();
                  MenuItem chatViewItem = new MenuItem("Open Chat View");
                  chatViewItem.setOnAction(e -> showChatTranscript(row.getItem()));
                  MenuItem rawJsonItem = new MenuItem("Open Raw JSON");
                  rawJsonItem.setOnAction(e -> showJsonlResult(row.getItem()));
                  menu.getItems().addAll(chatViewItem, rawJsonItem);
                  menu.show(row, event.getScreenX(), event.getScreenY());
                  event.consume();
                }
              });
          return row;
        });
    return table;
  }

  private void applyMode() {
    SearchMode mode = modeBox.getValue();
    preferences.put(LAST_SEARCH_MODE_KEY, mode.name());
    searchLabel.setText(mode.getSearchLabel());
    pathLabel.setText(mode.getPathLabel());

    if (mode == SearchMode.JSONL_HISTORY) {
      searchTextField.setPromptText("Text in JSONL history");
      pathField.setText(preferences.get(LAST_JSONL_FOLDER_KEY, ""));
    } else {
      searchTextField.setPromptText("e.g., config (partial filename match)");
      pathField.clear();
    }

    resultsTable.getItems().clear();
    updateButtonState();
  }

  private SearchMode lastSearchMode() {
    String storedMode = preferences.get(LAST_SEARCH_MODE_KEY, SearchMode.ARCHIVE_FILENAME.name());
    try {
      return SearchMode.valueOf(storedMode);
    } catch (IllegalArgumentException e) {
      return SearchMode.ARCHIVE_FILENAME;
    }
  }

  private void choosePath(Stage stage) {
    if (modeBox.getValue() == SearchMode.JSONL_HISTORY) {
      chooseJsonlHistoryFolder(stage);
    } else {
      chooseArchiveFile(stage);
    }
  }

  private void chooseArchiveFile(Stage stage) {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Select Archive");
    fileChooser
        .getExtensionFilters()
        .add(
            new FileChooser.ExtensionFilter(
                "Archives (*.zip, *.jar, *.ear, *.sar)", "*.zip", "*.jar", "*.ear", "*.sar"));

    File selectedFile = fileChooser.showOpenDialog(stage);
    if (selectedFile != null) {
      pathField.setText(selectedFile.getAbsolutePath());
    }
  }

  private void chooseJsonlHistoryFolder(Stage stage) {
    DirectoryChooser directoryChooser = new DirectoryChooser();
    directoryChooser.setTitle("Select JSONL History Folder");

    String lastFolder = preferences.get(LAST_JSONL_FOLDER_KEY, "");
    if (!lastFolder.isBlank()) {
      File initialDirectory = new File(lastFolder);
      if (initialDirectory.isDirectory()) {
        directoryChooser.setInitialDirectory(initialDirectory);
      }
    }

    File selectedDirectory = directoryChooser.showDialog(stage);
    if (selectedDirectory != null) {
      pathField.setText(selectedDirectory.getAbsolutePath());
      preferences.put(LAST_JSONL_FOLDER_KEY, selectedDirectory.getAbsolutePath());
    }
  }

  private void updateButtonState() {
    boolean hasSearch = !searchTextField.getText().isBlank();
    boolean hasPath = !pathField.getText().isBlank();
    boolean isRunning = currentSearchTask != null && currentSearchTask.isRunning();
    startButton.setDisable(!(hasSearch && hasPath) || isRunning);
  }

  private void runSearchTask() {
    SearchMode mode = modeBox.getValue();
    String searchText = searchTextField.getText().trim();
    Path selectedPath = Path.of(pathField.getText().trim());

    if (mode == SearchMode.ARCHIVE_FILENAME
        && !archiveFileSearcher.isArchiveSupported(selectedPath.toString())) {
      showError("Unsupported archive type.");
      return;
    }

    setRunningState(true);
    resultsTable.getItems().clear();

    currentSearchTask =
        new Task<>() {
          @Override
          protected List<SearchResult> call() throws Exception {
            if (mode == SearchMode.JSONL_HISTORY) {
              return jsonlHistorySearcher.search(selectedPath, searchText, this::isCancelled);
            }
            return archiveFileSearcher.search(selectedPath, searchText, this::isCancelled);
          }

          @Override
          protected void succeeded() {
            resultsTable.setItems(FXCollections.observableArrayList(getValue()));
            setRunningState(false);
          }

          @Override
          protected void cancelled() {
            setRunningState(false);
          }

          @Override
          protected void failed() {
            LOGGER.log(System.Logger.Level.ERROR, "Search task failed", getException());
            showError(getException().getMessage());
            setRunningState(false);
          }
        };

    Thread backgroundThread = new Thread(currentSearchTask, "archive-searcher-task");
    backgroundThread.setDaemon(true);
    backgroundThread.start();
  }

  private void runSearchIfReady() {
    if (!startButton.isDisabled()) {
      runSearchTask();
    }
  }

  private void setRunningState(boolean running) {
    modeBox.setDisable(running);
    searchTextField.setDisable(running);
    pathField.setDisable(running);
    browseButton.setDisable(running);
    startButton.setDisable(running);
    cancelButton.setDisable(!running);
    progressIndicator.setVisible(running);
    if (!running) {
      updateButtonState();
    }
  }

  private void showResult(SearchResult result) {
    if (result.getMode() == SearchMode.JSONL_HISTORY) {
      showChatTranscript(result);
    } else {
      showArchiveResult(result);
    }
  }

  private void showJsonlResult(SearchResult result) {
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

  private void showArchiveResult(SearchResult result) {
    String content =
        "Archive:\n"
            + result.getFilePath()
            + "\n\nMatched entry:\n"
            + result.getLocation();
    showTextWindow("Archive Match", result.getLocation(), content);
  }

  private void showChatTranscript(SearchResult selectedResult) {
    try {
      ChatTranscript transcript = jsonlTranscriptParser.parse(selectedResult.getFilePath());
      showChatWindow(transcript, selectedResult);
    } catch (Exception e) {
      LOGGER.log(System.Logger.Level.ERROR, "Failed to parse JSONL transcript", e);
      showError("Could not open chat view: " + e.getMessage());
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
    AtomicReference<javafx.scene.Node> highlightedNode = new AtomicReference<>();
    for (ChatTurn turn : transcript.getTurns()) {
      boolean highlighted = turnMatchesResult(turn, selectedResult);
      javafx.scene.Node turnNode = createTurnNode(turn, highlighted);
      if (highlighted) {
        highlightedNode.set(turnNode);
      }
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

  private void scrollToNode(ScrollPane scrollPane, VBox content, javafx.scene.Node node) {
    if (node == null) {
      return;
    }
    Platform.runLater(
        () -> {
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

  private javafx.scene.Node createTurnNode(ChatTurn turn, boolean highlighted) {
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
    if (!turn.getTimestamp().isBlank()) {
      header.append(" - ").append(turn.getTimestamp());
    }
    return header.toString();
  }

  private String emptyFallback(String value) {
    if (value == null || value.isBlank()) {
      return "(no displayable content)";
    }
    return value;
  }

  private String cardStyle(ChatRole role, boolean highlighted) {
    String background =
        switch (role) {
          case USER -> "#eef5ff";
          case ASSISTANT -> "#f7f7f4";
          case SYSTEM -> "#f1f3f4";
          case TOOL -> "#fff7e6";
          case UNKNOWN -> "#f8f1f7";
        };
    String border = highlighted ? "#d93025" : "#d7dce1";
    String width = highlighted ? "2" : "1";
    return "-fx-background-color: "
        + background
        + "; -fx-border-color: "
        + border
        + "; -fx-border-width: "
        + width
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

  private void showError(String message) {
    javafx.scene.control.Alert alert =
        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
    alert.setTitle("Search Error");
    alert.setHeaderText("Search failed");
    alert.setContentText(message);
    alert.showAndWait();
  }
}
