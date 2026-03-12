package nl.rowendu;

import java.io.*;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * JavaFX Archive Searcher Application A graphical interface for searching files within archive
 * files recursively. Supports .zip, .jar, .ear, and .sar file formats.
 *
 * @author Rowendu
 * @version 1.0.0
 */
public class ArchiveSearcher extends Application {

  // Supported archive extensions
  private static final Set<String> SUPPORTED_EXTENSIONS =
      new HashSet<>(java.util.Arrays.asList(".zip", ".jar", ".ear", ".sar"));

  // Directory to skip during recursive search
  private static final String SKIP_DIRECTORY = "META-INF";

  // UI Components
  private TextField searchTermField;
  private TextField archiveFilePathField;
  private Button selectFileButton;
  private Button searchButton;
  private Button clearButton;
  private TextArea resultsArea;
  private ProgressBar progressBar;
  private Label statusLabel;

  // Background task for searching
  private SearchTask searchTask;

  private Stage primaryStageRef;

  /** Main entry point - launches the JavaFX application. */
  public static void main(String[] args) {
    launch(args);
  }

  /** Called when the JavaFX application starts. Sets up the UI. */
  @Override
  public void start(Stage primaryStage) {

    primaryStageRef = primaryStage;
    // Configure the stage
    primaryStage.setTitle("Archive File Searcher");
    primaryStage.setMinWidth(700);
    primaryStage.setMinHeight(500);

    // Create the main layout
    VBox mainLayout = createMainLayout();

    // Create and configure the scene
    Scene scene = new Scene(mainLayout, 800, 600);

    // Set CSS styling
    scene
        .getStylesheets()
        .add(
            this.getClass().getResource("/css/styles.css") != null
                ? this.getClass().getResource("/css/styles.css").toExternalForm()
                : "");

    // Apply basic inline styles if CSS not available
    applyBasicStyles(mainLayout);

    primaryStage.setScene(scene);
    primaryStage.show();

    // Set up window close handler
    primaryStage.setOnCloseRequest(
        event -> {
          if (searchTask != null && !searchTask.isDone()) {
            searchTask.cancel();
          }
        });
  }

  /** Creates the main user interface layout. */
  private VBox createMainLayout() {
    VBox root = new VBox(15);
    root.setPadding(new Insets(20));

    // Title Section
    Label titleLabel = new Label("Archive File Searcher");
    titleLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-align: center;");

    // Search Term Section
    GridPane searchGrid = new GridPane();
    searchGrid.setHgap(10);
    searchGrid.setVgap(10);

    Label searchTermLabel = new Label("Search Term:");
    searchGrid.add(searchTermLabel, 0, 0);

    searchTermField = new TextField();
    searchTermField.setPromptText("Enter filename to search for...");
    searchTermField.setMinWidth(400);
    searchGrid.add(searchTermField, 1, 0);

    // Archive File Selection Section
    GridPane fileGrid = new GridPane();
    fileGrid.setHgap(10);

    Label fileLabel = new Label("Archive File:");
    fileGrid.add(fileLabel, 0, 0);

    archiveFilePathField = new TextField();
    archiveFilePathField.setPromptText("Select an archive file...");
    archiveFilePathField.setEditable(false);
    archiveFilePathField.setMinWidth(450);
    fileGrid.add(archiveFilePathField, 1, 0);

    selectFileButton = new Button("Browse...");
    selectFileButton.setMinWidth(100);
    fileGrid.add(selectFileButton, 2, 0);

    // Set up file chooser
    selectFileButton.setOnAction(e -> openFileChooser());

    // Action Buttons Section
    HBox buttonBox = new HBox(10);

    searchButton = new Button("🔍 Search");
    searchButton.setMinWidth(120);
    searchButton.setStyle("-fx-font-size: 14px; -fx-padding: 8 20;");
    searchButton.setDisable(true); // Disabled until valid inputs

    clearButton = new Button("Clear Results");
    clearButton.setMinWidth(120);

    buttonBox.getChildren().addAll(searchButton, clearButton);

    // Set up search button action
    searchButton.setOnAction(e -> performSearch());

    // Status and Progress Section
    statusLabel = new Label("Ready");

    progressBar = new ProgressBar(0);
    progressBar.setVisible(false);

    // Results Area Section
    Label resultsLabel = new Label("Search Results:");

    resultsArea = new TextArea();
    resultsArea.setPrefRowCount(20);
    resultsArea.setEditable(false);
    resultsArea.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 12px;");

    ScrollPane resultsScrollPane = new ScrollPane(resultsArea);
    resultsScrollPane.setFitToWidth(true);
    resultsScrollPane.setPrefHeight(250);

    // Clear button action
    clearButton.setOnAction(
        e -> {
          resultsArea.clear();
          statusLabel.setText("Results cleared");
        });

    // Add all sections to the main layout
    root.getChildren()
        .addAll(
            titleLabel,
            new Separator(),
            searchGrid,
            fileGrid,
            buttonBox,
            statusLabel,
            progressBar,
            new Separator(),
            resultsLabel,
            resultsScrollPane);

    // Set up input validation listeners
    setupInputValidation();

    return root;
  }

  /** Applies basic CSS styling to the layout. */
  private void applyBasicStyles(VBox root) {
    root.setStyle("-fx-background-color: #f5f5f5;");
  }

  /** Sets up listeners to validate user input and enable/disable the search button. */
  private void setupInputValidation() {
    Runnable validateInputs =
        () -> {
          boolean hasSearchTerm =
              searchTermField.getText() != null && !searchTermField.getText().trim().isEmpty();
          boolean hasArchiveFile =
              archiveFilePathField.getText() != null
                  && !archiveFilePathField.getText().trim().isEmpty();

          searchButton.setDisable(!(hasSearchTerm && hasArchiveFile));
        };

    searchTermField.textProperty().addListener((obs, oldVal, newVal) -> validateInputs.run());
    archiveFilePathField.textProperty().addListener((obs, oldVal, newVal) -> validateInputs.run());
  }

  /** Opens a file chooser dialog to select an archive file. */
  private void openFileChooser() {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Select Archive File");

    // Add file type filters
    fileChooser
        .getExtensionFilters()
        .addAll(
            new FileChooser.ExtensionFilter("Archive Files", "*.zip", "*.jar", "*.ear", "*.sar"),
            new FileChooser.ExtensionFilter("ZIP Files", "*.zip"),
            new FileChooser.ExtensionFilter("JAR Files", "*.jar"),
            new FileChooser.ExtensionFilter("EAR Files", "*.ear"),
            new FileChooser.ExtensionFilter("SAR Files", "*.sar"),
            new FileChooser.ExtensionFilter("All Files", "*.*"));

    File selectedFile = fileChooser.showOpenDialog(primaryStageRef);

    if (selectedFile != null) {
      archiveFilePathField.setText(selectedFile.getAbsolutePath());

      // Validate the selected file
      if (!isArchiveSupported(selectedFile.getName())) {
        showAlert(
            Alert.AlertType.WARNING,
            "Unsupported File Type",
            "This file type is not supported. Please select a .zip, .jar, .ear, or .sar file.");
      } else {
        statusLabel.setText("Selected: " + selectedFile.getName());
      }
    }
  }

  /** Validates if the given filename has a supported archive extension. */
  private boolean isArchiveSupported(String fileName) {
    return SUPPORTED_EXTENSIONS.stream().anyMatch(ext -> fileName.toLowerCase().endsWith(ext));
  }

  /** Starts the archive search operation. */
  private void performSearch() {
    String searchTerm = searchTermField.getText().trim();
    String archivePath = archiveFilePathField.getText().trim();

    // Validate inputs
    if (searchTerm.isEmpty()) {
      showAlert(Alert.AlertType.WARNING, "Validation Error", "Please enter a search term.");
      return;
    }

    File archiveFile = new File(archivePath);
    if (!archiveFile.exists()) {
      showAlert(
          Alert.AlertType.ERROR, "File Not Found", "The selected archive file does not exist.");
      return;
    }

    if (!archiveFile.isFile()) {
      showAlert(Alert.AlertType.ERROR, "Invalid File", "The selected path is not a file.");
      return;
    }

    // Disable UI during search
    setSearchingState(true);

    // Update status
    statusLabel.setText("Searching for: " + searchTerm);
    progressBar.setVisible(true);

    // Create and execute the background search task
    searchTask = new SearchTask(archiveFile, searchTerm);

    // Track progress
    searchTask
        .progressProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              progressBar.setProgress(newVal.doubleValue());
            });

    // Update results as they are found
    searchTask
        .messageProperty()
        .addListener(
            (obs, oldVal, newVal) -> {
              if (newVal != null && !newVal.isEmpty()) {
                Platform.runLater(
                    () -> {
                      resultsArea.appendText(newVal + "\n");
                    });
              }
            });

    // Handle task completion
    searchTask.setOnSucceeded(
        event -> {
          setSearchingState(false);
          progressBar.setVisible(false);

          // Use getter instead of getValue()
          int matchesFound = searchTask.getMatchCount();
          statusLabel.setText(
              "Search complete. Found "
                  + matchesFound
                  + (matchesFound == 1 ? " match" : " matches"));

          if (searchTask.getErrorMessage() != null) {
            showAlert(Alert.AlertType.ERROR, "Search Error", searchTask.getErrorMessage());
          }
        });

    searchTask.setOnFailed(
        event -> {
          setSearchingState(false);
          progressBar.setVisible(false);
          Throwable exception = searchTask.getException();
          statusLabel.setText(
              "Search failed: " + (exception != null ? exception.getMessage() : "Unknown error"));

          if (exception != null) {
            showAlert(Alert.AlertType.ERROR, "Search Failed", exception.getMessage(), exception);
          }
        });

    // Run the task in a new thread
    new Thread(searchTask).start();
  }

  /** Enables or disables UI elements based on search state. */
  private void setSearchingState(boolean searching) {
    searchButton.setDisable(searching);
    selectFileButton.setDisable(searching);
    searchTermField.setEditable(!searching);
    archiveFilePathField.setEditable(false); // Always read-only, but visually indicate state
  }

  /** Shows a popup alert dialog. */
  private void showAlert(Alert.AlertType type, String title, String message) {
    Alert alert = new Alert(type);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.setContentText(message);
    alert.showAndWait();
  }

  private void showAlert(Alert.AlertType type, String title, String message, Throwable cause) {
    Alert alert = new Alert(type);
    alert.setTitle(title);
    alert.setHeaderText(null);

    StringBuilder content = new StringBuilder(message);
    if (cause != null) {
      content.append("\n\nDetails: ").append(cause.getMessage());
    }
    alert.setContentText(content.toString());

    // Create expandable exception details area
    TextArea logTextArea = new TextArea();
    logTextArea.setEditable(false);
    logTextArea.setWrapText(true);

    if (cause != null) {
      StringWriter writer = new StringWriter();
      cause.printStackTrace(new PrintWriter(writer));
      logTextArea.setText(writer.toString());
    }

    ScrollPane logScrollPane = new ScrollPane(logTextArea);
    logScrollPane.setFitToWidth(true);
    logScrollPane.setPrefHeight(150);

    // ✅ JavaFX 17 compatible way to set expandable content
    alert.getDialogPane().setExpandableContent(logScrollPane);

    alert.showAndWait();
  }

  /** Background task that performs the recursive archive search. */
  private class SearchTask extends javafx.concurrent.Task<String> {

    private final File archiveFile;
    private final String searchTerm;
    private int matchCount = 0;           // Store result here
    private String errorMessage = null;   // For errors
    private int totalEntries = 0;
    private int processedEntries = 0;

    public SearchTask(File archiveFile, String searchTerm) {
        this.archiveFile = archiveFile;
        this.searchTerm = searchTerm;
    }

    // Getters for retrieving results after completion
    public int getMatchCount() {
        return matchCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    protected String call() throws Exception {
        updateMessage("Starting search...");

        try {
            matchCount = searchInArchive(archiveFile, searchTerm, "");
            return "SUCCESS";  // Return simple success indicator as String
        } catch (Exception e) {
            errorMessage = e.getMessage();
            updateMessage("Error: " + e.getMessage());
            throw e;
        } finally {
            updateProgress(processedEntries, totalEntries);
        }
    }

    private int searchInArchive(File archiveFile, String searchTerm,
                                String parentPath) throws IOException {
        int localMatchCount = 0;

        updateMessage("Checking: " + (parentPath.isEmpty() ? archiveFile.getName() : parentPath));

        try (ZipFile zip = new ZipFile(archiveFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                if (isCancelled()) return -1;

                ZipEntry entry = entries.nextElement();
                String currentPath = parentPath.isEmpty() ?
                    entry.getName() : parentPath + "/" + entry.getName();

                if (entry.isDirectory()) {
                    updateMessage("  Traversing folder: " + currentPath);
                } else {
                    String fileName = new File(entry.getName()).getName();
                    if (fileName.equals(searchTerm)) {
                        localMatchCount++;
                        updateMessage("✓ FOUND: " + currentPath);
                    }
                }

                processedEntries++;
                updateProgress(processedEntries, totalEntries);

                if (!entry.isDirectory() && isArchiveSupported(entry.getName())) {
                    if (entry.getName().startsWith(SKIP_DIRECTORY + "/")) {
                        continue;
                    }

                    try {
                        File tempFile = extractToTempFile(zip, entry);
                        int nestedMatches = searchInArchive(tempFile, searchTerm, currentPath);
                        localMatchCount += nestedMatches;
                        tempFile.delete();
                    } catch (Exception e) {
                        updateMessage("  Failed to process: " + currentPath);
                    }
                }
            }
        }

        return localMatchCount;
    }

    /** Extracts a ZipEntry to a temporary file. */
    private File extractToTempFile(ZipFile zip, ZipEntry entry) throws IOException {
      File tempFile = File.createTempFile("nested_archive_", ".tmp");

      // Ensure cleanup even if exception occurs
      try {
        try (InputStream in = zip.getInputStream(entry);
            OutputStream out = new FileOutputStream(tempFile)) {
          byte[] buffer = new byte[4096];
          int bytesRead;
          while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
          }
        }
      } catch (Exception e) {
        tempFile.delete();
        throw e;
      }

      return tempFile;
    }

    @Override
    protected void cancelled() {
      // Cleanup can be added here if needed
      System.out.println("Search task cancelled");
    }
  }
}
