package nl.rowendu;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.logging.LogManager;

public class ArchiveSearcher extends Application {
  private static final System.Logger LOGGER = System.getLogger(ArchiveSearcher.class.getName());
  private static final String LAST_JSONL_FOLDER_KEY = "jsonlHistory.lastFolder";
  private static final String LAST_SEARCH_MODE_KEY = "search.lastMode";

  private final JsonlTranscriptParser transcriptParser = new JsonlTranscriptParser();
  private final ResultRenderer resultRenderer = new ResultRenderer(transcriptParser, this::showError);
  private final Preferences preferences = Preferences.userNodeForPackage(ArchiveSearcher.class);

  // UI state — declarative binding instead of manual toggle
  private final javafx.beans.property.BooleanProperty isRunning =
      new javafx.beans.property.SimpleBooleanProperty(false);

  // UI components
  private ComboBox<SearcherMode> modeBox;
  private Label searchLabel;
  private Label pathLabel;
  private TextField searchTextField;
  private TextField pathField;
  private Button browseButton;
  private Button startButton;
  private Button cancelButton;
  private ProgressIndicator progressIndicator;
  private TableView<SearchResult> resultsTable;
  private Task<List<SearchResult>> currentSearchTask;

  public static void main(String[] args) {
    File appDir = new File(System.getProperty("user.home"), ".archivesearcher");
    if (!appDir.exists()) appDir.mkdirs();

    try (InputStream is = ArchiveSearcher.class.getResourceAsStream("/logging.properties")) {
      if (is != null) {
        LogManager.getLogManager().readConfiguration(is);
      } else {
        System.err.println("WARNING: logging.properties not found in classpath.");
      }
    } catch (Exception e) {
      System.err.println("Failed to initialize custom logging: " + e.getMessage());
    }

    launch(args);
  }

  @Override
  public void start(Stage primaryStage) {
    MenuItem quitMenuItem = new MenuItem("Quit");
    quitMenuItem.setOnAction(e -> Platform.exit());
    MenuBar menuBar = new MenuBar(new Menu("File", null, quitMenuItem));

    modeBox = new ComboBox<>(FXCollections.observableArrayList(SearcherMode.values()));
    modeBox.getSelectionModel().select(lastSearchMode());
    modeBox.setPrefWidth(180);
    modeBox.valueProperty().addListener((obs, old, neo) -> applyMode());

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
    startButton.setOnAction(e -> runSearchTask());

    cancelButton = new Button("Cancel");
    cancelButton.setOnAction(e -> {
      if (currentSearchTask != null) currentSearchTask.cancel();
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

    // Text-field listeners for start-button enable state
    javafx.beans.value.ChangeListener<String> textListener = (obs, o, n) -> updateButtonState();
    searchTextField.textProperty().addListener(textListener);
    pathField.textProperty().addListener(textListener);

    // Declarative running-state binding
    isRunning.addListener((obs, wasRunning, nowRunning) -> {
      modeBox.setDisable(nowRunning);
      searchTextField.setDisable(nowRunning);
      pathField.setDisable(nowRunning);
      browseButton.setDisable(nowRunning);
      cancelButton.setDisable(!nowRunning);
      progressIndicator.setVisible(nowRunning);
      if (!nowRunning) updateButtonState();
    });

    BorderPane topContainer = new BorderPane();
    topContainer.setTop(menuBar);
    topContainer.setCenter(inputBox);

    BorderPane root = new BorderPane();
    root.setTop(topContainer);
    root.setCenter(resultsTable);
    BorderPane.setMargin(resultsTable, new Insets(10));

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
    titleColumn.setCellValueFactory(data ->
        new ReadOnlyStringWrapper(data.getValue().getTitle()));
    titleColumn.setPrefWidth(760);

    TableColumn<SearchResult, String> fileColumn = new TableColumn<>("File");
    fileColumn.setCellValueFactory(data ->
        new ReadOnlyStringWrapper(data.getValue().getFilePath().getFileName().toString()));
    fileColumn.setPrefWidth(360);

    TableColumn<SearchResult, String> lineColumn = new TableColumn<>("Line");
    lineColumn.setCellValueFactory(data ->
        new ReadOnlyStringWrapper(data.getValue().getLineLabel()));
    lineColumn.setPrefWidth(70);

    table.getColumns().addAll(titleColumn, fileColumn, lineColumn);

    table.setRowFactory(view -> {
      javafx.scene.control.TableRow<SearchResult> row = new javafx.scene.control.TableRow<>();
      row.setOnMouseClicked(event -> {
        if (!row.isEmpty() && event.getButton() == MouseButton.PRIMARY
            && event.getClickCount() == 2) {
          resultRenderer.showResult(row.getItem());
        }
      });
      row.setOnContextMenuRequested(event -> {
        SearchResult item = row.getItem();
        if (item != null && item.getMode() == SearcherMode.JSONL_HISTORY) {
          resultRenderer.showJsonlContextMenu(item, row, event);
        }
      });
      return row;
    });
    return table;
  }

  private void applyMode() {
    SearcherMode mode = modeBox.getValue();
    preferences.put(LAST_SEARCH_MODE_KEY, mode.name());
    searchLabel.setText(mode.getSearchLabel());
    pathLabel.setText(mode.getPathLabel());

    if (mode == SearcherMode.JSONL_HISTORY) {
      searchTextField.setPromptText("Text in JSONL history");
      pathField.setText(preferences.get(LAST_JSONL_FOLDER_KEY, ""));
    } else {
      searchTextField.setPromptText("e.g., config (partial filename match)");
      pathField.clear();
    }

    resultsTable.getItems().clear();
    updateButtonState();
  }

  private SearcherMode lastSearchMode() {
    String stored = preferences.get(LAST_SEARCH_MODE_KEY, SearcherMode.ARCHIVE_FILENAME.name());
    try {
      return SearcherMode.valueOf(stored);
    } catch (IllegalArgumentException e) {
      return SearcherMode.ARCHIVE_FILENAME;
    }
  }

  private void choosePath(Stage stage) {
    if (modeBox.getValue() == SearcherMode.JSONL_HISTORY) {
      chooseJsonlHistoryFolder(stage);
    } else {
      chooseArchiveFile(stage);
    }
  }

  private void chooseArchiveFile(Stage stage) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Select Archive");
    chooser.getExtensionFilters().add(
        new FileChooser.ExtensionFilter("Archives (*.zip, *.jar, *.ear, *.sar)",
            "*.zip", "*.jar", "*.ear", "*.sar"));
    File selected = chooser.showOpenDialog(stage);
    if (selected != null) pathField.setText(selected.getAbsolutePath());
  }

  private void chooseJsonlHistoryFolder(Stage stage) {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle("Select JSONL History Folder");
    String lastFolder = preferences.get(LAST_JSONL_FOLDER_KEY, "");
    if (!lastFolder.isBlank()) {
      File initial = new File(lastFolder);
      if (initial.isDirectory()) chooser.setInitialDirectory(initial);
    }
    File selected = chooser.showDialog(stage);
    if (selected != null) {
      pathField.setText(selected.getAbsolutePath());
      preferences.put(LAST_JSONL_FOLDER_KEY, selected.getAbsolutePath());
    }
  }

  private void updateButtonState() {
    boolean hasSearch = !searchTextField.getText().isBlank();
    boolean hasPath = !pathField.getText().isBlank();
    startButton.setDisable(!(hasSearch && hasPath) || isRunning.get());
  }

  private void runSearchTask() {
    SearcherMode mode = modeBox.getValue();
    Searcher searcher = mode.createSearcher();
    String searchText = searchTextField.getText().trim();
    Path selectedPath = Path.of(pathField.getText().trim());

    if (!searcher.isArchiveSupported(selectedPath.toString())) {
      showError("Unsupported archive type.");
      return;
    }

    isRunning.set(true);
    resultsTable.getItems().clear();

    currentSearchTask = new Task<>() {
      @Override
      protected List<SearchResult> call() throws Exception {
        CancellationToken token = () -> currentSearchTask.isCancelled();
        return searcher.search(selectedPath, searchText, token);
      }

      @Override
      protected void succeeded() {
        resultsTable.setItems(FXCollections.observableArrayList(getValue()));
        isRunning.set(false);
      }

      @Override
      protected void cancelled() {
        isRunning.set(false);
      }

      @Override
      protected void failed() {
        LOGGER.log(System.Logger.Level.ERROR, "Search task failed", getException());
        showError(getException().getMessage());
        isRunning.set(false);
      }
    };


    Thread bg = new Thread(currentSearchTask, "archive-searcher-task");
    bg.setDaemon(true);
    bg.start();
  }

  private void runSearchIfReady() {
    if (!startButton.isDisabled()) runSearchTask();
  }

  private void showError(String message) {
    Platform.runLater(() -> {
      Alert alert = new Alert(AlertType.ERROR);
      alert.setTitle("Search Error");
      alert.setHeaderText("Search failed");
      alert.setContentText(message);
      alert.showAndWait();
    });
  }
}
