package nl.rowendu;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.*;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// RC by Gemini 3 Pro
public class ArchiveSearcher extends Application {

  // 1. Replaced PrintWriter with JDK System.Logger
  private static final System.Logger LOGGER = System.getLogger(ArchiveSearcher.class.getName());

  private static final Set<String> SUPPORTED_EXTENSIONS =
      new HashSet<>(Arrays.asList(".zip", ".jar", ".ear", ".sar"));
  private static final String SKIP_DIRECTORY = "META-INF";

  private TextField searchFileField;
  private TextField archivePathField;
  private TextArea outputArea;
  private ScrollPane scrollPane;
  private Button startButton;
  private Button cancelButton;
  private ProgressIndicator progressIndicator;
  private Task<Integer> currentSearchTask;

  public static void main(String[] args) {
    launch(args);
  }

  // Note: init() and stop() were completely removed, as System.Logger manages its own lifecycle
  // natively.

  @Override
  public void start(Stage primaryStage) {
    // --- Menu Bar ---
    MenuItem quitMenuItem = new MenuItem("Quit");
    quitMenuItem.setOnAction(e -> Platform.exit());
    MenuBar menuBar = new MenuBar(new Menu("File", null, quitMenuItem));

    // --- Input Section ---
    Label searchLabel = new Label("Filename to Search:");
    searchFileField = new TextField();
    searchFileField.setPromptText("e.g., config (supports partial match)");
    searchFileField.setPrefWidth(300);

    Label archiveLabel = new Label("Archive File:");
    archivePathField = new TextField();
    archivePathField.setEditable(false);
    archivePathField.setPrefWidth(300);

    Button browseButton = new Button("Browse...");
    browseButton.setOnAction(e -> chooseArchiveFile(primaryStage));

    HBox searchBox = new HBox(10, searchLabel, searchFileField);
    searchBox.setAlignment(Pos.CENTER_LEFT);

    HBox archiveBox = new HBox(10, archiveLabel, archivePathField, browseButton);
    archiveBox.setAlignment(Pos.CENTER_LEFT);

    // --- Action Buttons & Progress ---
    startButton = new Button("Start Search");
    startButton.setDisable(true);
    startButton.setOnAction(e -> runSearchTask());

    cancelButton = new Button("Cancel");
    cancelButton.setDisable(true);
    cancelButton.setOnAction(
        e -> {
          if (currentSearchTask != null) currentSearchTask.cancel();
        });

    progressIndicator = new ProgressIndicator();
    progressIndicator.setVisible(false);
    progressIndicator.setPrefSize(20, 20);

    HBox actionBox = new HBox(15, startButton, cancelButton, progressIndicator);
    actionBox.setAlignment(Pos.CENTER_LEFT);

    VBox inputBox = new VBox(15, searchBox, archiveBox, actionBox);
    inputBox.setPadding(new Insets(15));

    // --- Output Section ---
    outputArea = new TextArea();
    outputArea.setEditable(false);
    outputArea.setWrapText(true);

    scrollPane = new ScrollPane(outputArea);
    scrollPane.setFitToWidth(true);
    scrollPane.setPadding(new Insets(10));

    // --- Layout Assembly ---
    BorderPane topContainer = new BorderPane();
    topContainer.setTop(menuBar);
    topContainer.setCenter(inputBox);

    BorderPane root = new BorderPane();
    root.setTop(topContainer);
    root.setCenter(scrollPane);

    // --- Reactivity ---
    searchFileField.textProperty().addListener((obs, old, newVal) -> updateButtonState());
    archivePathField.textProperty().addListener((obs, old, newVal) -> updateButtonState());

    Scene scene = new Scene(root, 700, 500);
    primaryStage.setTitle("Archive Searcher");
    primaryStage.setScene(scene);
    primaryStage.show();
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
      archivePathField.setText(selectedFile.getAbsolutePath());
    }
  }

  private void updateButtonState() {
    boolean hasSearch = !searchFileField.getText().isBlank();
    boolean hasArchive = !archivePathField.getText().isBlank();

    // Only re-enable start button if a task isn't currently running
    boolean isRunning = currentSearchTask != null && currentSearchTask.isRunning();
    startButton.setDisable(!(hasSearch && hasArchive) || isRunning);
  }

  private void runSearchTask() {
    // 2. Partial Matching: Force lowercase once here to save CPU cycles inside the recursive loop
    String searchFileLower = searchFileField.getText().trim().toLowerCase();
    String archivePath = archivePathField.getText().trim();

    if (!isArchiveSupported(archivePath)) {
      appendOutput("Error: Unsupported archive type.");
      return;
    }

    // Set UI to "Running" state
    startButton.setDisable(true);
    searchFileField.setDisable(true);
    browseButtonToggle(true);
    cancelButton.setDisable(false);
    progressIndicator.setVisible(true);

    outputArea.clear();
    appendOutput("--- Starting Search for '*" + searchFileLower + "*' ---");

    currentSearchTask =
        new Task<>() {
          @Override
          protected Integer call() throws Exception {
            File archiveFile = new File(archivePath);
            if (!archiveFile.exists()) {
              throw new IOException("Archive file does not exist: " + archivePath);
            }
            return searchInArchive(archiveFile, searchFileLower, "", this);
          }

          @Override
          protected void succeeded() {
            int matches = getValue();
            appendOutput(
                matches == 0
                    ? "No matches found."
                    : "--- Search Complete (" + matches + " matches) ---");
            resetUI();
          }

          @Override
          protected void cancelled() {
            appendOutput("--- Search Cancelled by User ---");
            resetUI();
          }

          @Override
          protected void failed() {
            LOGGER.log(System.Logger.Level.ERROR, "Search Task Failed", getException());
            appendOutput("Error: " + getException().getMessage());
            resetUI();
          }
        };

    Thread backgroundThread = new Thread(currentSearchTask);
    backgroundThread.setDaemon(true);
    backgroundThread.start();
  }

  private void browseButtonToggle(boolean disable) {
    if (archivePathField.getParent() instanceof HBox box) {
      box.getChildren().stream()
          .filter(node -> node instanceof Button)
          .forEach(node -> node.setDisable(disable));
    }
  }

  private void resetUI() {
    updateButtonState();
    searchFileField.setDisable(false);
    browseButtonToggle(false);
    cancelButton.setDisable(true);
    progressIndicator.setVisible(false);
  }

  private void appendOutput(String message) {
    Platform.runLater(
        () -> {
          outputArea.appendText(message + "\n");
          if (scrollPane != null) {
            scrollPane.setVvalue(1.0);
          }
        });
  }

  private boolean isArchiveSupported(String fileName) {
    return SUPPORTED_EXTENSIONS.stream().anyMatch(ext -> fileName.toLowerCase().endsWith(ext));
  }

  // 3. Cognitive Complexity Reduction
  // Extracted the deep nesting. Used early returns and loop 'continue' statements.
  private int searchInArchive(
      File archiveFile, String searchFileLower, String parentPath, Task<?> task)
      throws IOException {
    int matchCount = 0;
    LOGGER.log(
        System.Logger.Level.INFO,
        "Checking archive: {0}",
        parentPath.isEmpty() ? archiveFile.getName() : parentPath);

    try (ZipFile zip = new ZipFile(archiveFile)) {
      Enumeration<? extends ZipEntry> entries = zip.entries();

      while (entries.hasMoreElements()) {
        // Graceful exit if user clicked cancel
        if (task.isCancelled()) {
          LOGGER.log(System.Logger.Level.INFO, "Search aborted early due to cancellation.");
          break;
        }

        ZipEntry entry = entries.nextElement();
        String currentPath =
            parentPath.isEmpty() ? entry.getName() : parentPath + "/" + entry.getName();

        if (entry.isDirectory()) {
          // System.Logger supports lazy evaluation via Supplier lambdas.
          // The string is ONLY concatenated if DEBUG level is enabled!
          LOGGER.log(System.Logger.Level.DEBUG, () -> "Traversing folder: " + currentPath);
          continue;
        }

        // Check for match (Partial & Case-Insensitive)
        String fileNameLower = new File(entry.getName()).getName().toLowerCase();
        if (fileNameLower.contains(searchFileLower)) {
          appendOutput("Found: " + currentPath);
          LOGGER.log(System.Logger.Level.INFO, "Found match: {0}", currentPath);
          matchCount++;
        }

        // Check if we need to recurse into a nested archive
        if (isArchiveSupported(entry.getName())) {
          if (entry.getName().startsWith(SKIP_DIRECTORY + "/")) {
            LOGGER.log(
                System.Logger.Level.DEBUG, () -> "Skipping META-INF archive: " + currentPath);
            continue;
          }

          // Extracted recursive logic to a separate helper method to flatten code
          matchCount += processNestedArchive(zip, entry, searchFileLower, currentPath, task);
        }
      }
    } catch (Exception e) {
      throw new IOException("Error processing archive: " + e.getMessage(), e);
    }
    return matchCount;
  }

  // Helper method to keep searchInArchive clean and handle the temp file lifecycle safely
  private int processNestedArchive(
      ZipFile zip, ZipEntry entry, String searchFileLower, String currentPath, Task<?> task) {
    try {
      File tempFile = extractToTempFile(zip, entry);
      try {
        return searchInArchive(tempFile, searchFileLower, currentPath, task);
      } finally {
        tempFile.delete(); // Guarantee cleanup
      }
    } catch (Exception e) {
      LOGGER.log(System.Logger.Level.ERROR, "Failed to process nested archive: " + currentPath, e);
      return 0; // Fail gracefully for this specific nested archive and keep searching
    }
  }

  private File extractToTempFile(ZipFile zip, ZipEntry entry) throws IOException {
    File tempFile = File.createTempFile("nested", ".tmp");
    tempFile.deleteOnExit();

    try (InputStream in = zip.getInputStream(entry);
        OutputStream out = new FileOutputStream(tempFile)) {
      byte[] buffer = new byte[8192]; // Bumped buffer size to 8kb for slightly faster I/O
      int bytesRead;
      while ((bytesRead = in.read(buffer)) != -1) {
        out.write(buffer, 0, bytesRead);
      }
    }
    return tempFile;
  }
}
