package nl.rowendu;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
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

    private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(
            Arrays.asList(".zip", ".jar", ".ear", ".sar")
    );
    private static final String SKIP_DIRECTORY = "META-INF";
    private static final String LOG_FILE = "debug.log";

    private TextField searchFileField;
    private TextField archivePathField;
    private TextArea outputArea;
    private ScrollPane scrollPane;
    private Button startButton;
    private PrintWriter logWriter;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, true));
        } catch (IOException e) {
            System.err.println("Could not create log file: " + e.getMessage());
        }
    }

    @Override
    public void start(Stage primaryStage) {
        // --- Menu Bar ---
        MenuItem quitMenuItem = new MenuItem("Quit");
        quitMenuItem.setOnAction(e -> Platform.exit());
        MenuBar menuBar = new MenuBar(new Menu("File", null, quitMenuItem));

        // --- Input Section ---
        Label searchLabel = new Label("Filename to Search:");
        searchFileField = new TextField();
        searchFileField.setPromptText("e.g., config.properties");
        searchFileField.setPrefWidth(300);

        Label archiveLabel = new Label("Archive File:");
        archivePathField = new TextField();
        archivePathField.setEditable(false);
        archivePathField.setPrefWidth(300);

        Button browseButton = new Button("Browse...");
        browseButton.setOnAction(e -> chooseArchiveFile(primaryStage));

        HBox searchBox = new HBox(10, searchLabel, searchFileField);
        searchBox.alignmentProperty().set(javafx.geometry.Pos.CENTER_LEFT);

        HBox archiveBox = new HBox(10, archiveLabel, archivePathField, browseButton);
        archiveBox.alignmentProperty().set(javafx.geometry.Pos.CENTER_LEFT);

        startButton = new Button("Start Search");
        startButton.setDisable(true);
        startButton.setOnAction(e -> runSearchTask());

        VBox inputBox = new VBox(15, searchBox, archiveBox, startButton);
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
        FileChooser.ExtensionFilter filter = new FileChooser.ExtensionFilter(
                "Archives (*.zip, *.jar, *.ear, *.sar)",
                "*.zip", "*.jar", "*.ear", "*.sar"
        );
        fileChooser.getExtensionFilters().add(filter);

        File selectedFile = fileChooser.showOpenDialog(stage);
        if (selectedFile != null) {
            archivePathField.setText(selectedFile.getAbsolutePath());
        }
    }

    private void updateButtonState() {
        boolean hasSearch = !searchFileField.getText().isBlank();
        boolean hasArchive = !archivePathField.getText().isBlank();
        startButton.setDisable(!(hasSearch && hasArchive));
    }

    private void runSearchTask() {
        String searchFile = searchFileField.getText().trim();
        String archivePath = archivePathField.getText().trim();

        if (!isArchiveSupported(archivePath)) {
            appendOutput("Error: Unsupported archive type. Supported types: " +
                    String.join(", ", SUPPORTED_EXTENSIONS));
            return;
        }

        // Disable inputs during search to prevent concurrent runs
        startButton.setDisable(true);
        searchFileField.setDisable(true);
        browseButtonToggle(true);

        outputArea.clear();
        appendOutput("--- Starting Search ---");

        // Task returns an Integer representing the total number of matches found
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                File archiveFile = new File(archivePath);
                if (!archiveFile.exists()) {
                    throw new IOException("Archive file does not exist: " + archivePath);
                }
                return searchInArchive(archiveFile, searchFile, "");
            }

            @Override
            protected void succeeded() {
                int matches = getValue();
                if (matches == 0) {
                    appendOutput("No matches found in the archive.");
                } else {
                    appendOutput("--- Search Complete (" + matches + " matches found) ---");
                }
                resetUI();
            }

            @Override
            protected void failed() {
                appendOutput("Error: " + getException().getMessage());
                resetUI();
            }
        };

        new Thread(task).start();
    }

    private void browseButtonToggle(boolean disable) {
        // Find browse button via the archive field's parent
        if (archivePathField.getParent() instanceof HBox) {
            HBox box = (HBox) archivePathField.getParent();
            box.getChildren().stream()
               .filter(node -> node instanceof Button)
               .forEach(node -> node.setDisable(disable));
        }
    }

    private void resetUI() {
        startButton.setDisable(false);
        searchFileField.setDisable(false);
        browseButtonToggle(false);
    }

    private void log(String message) {
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    private void appendOutput(String message) {
        Platform.runLater(() -> {
            outputArea.appendText(message + "\n");
            if (scrollPane != null) {
                scrollPane.setVvalue(1.0);
            }
        });
    }

    private boolean isArchiveSupported(String fileName) {
        return SUPPORTED_EXTENSIONS.stream()
                .anyMatch(ext -> fileName.toLowerCase().endsWith(ext));
    }

    private int searchInArchive(File archiveFile, String searchFile, String parentPath) throws IOException {
        int matchCount = 0;
        log("Checking archive: " + (parentPath.isEmpty() ? archiveFile.getName() : parentPath));

        try (ZipFile zip = new ZipFile(archiveFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String currentPath = parentPath.isEmpty() ?
                        entry.getName() : parentPath + "/" + entry.getName();

                if (entry.isDirectory()) {
                    log("Traversing folder: " + currentPath);
                } else {
                    String fileName = new File(entry.getName()).getName();
                    if (fileName.equals(searchFile)) {
                        appendOutput("Found: " + currentPath);
                        log("Found match: " + currentPath);
                        matchCount++;
                    }
                }

                if (!entry.isDirectory() && isArchiveSupported(entry.getName())) {
                    if (entry.getName().startsWith(SKIP_DIRECTORY + "/")) {
                        log("Skipping META-INF archive: " + currentPath);
                        continue;
                    }

                    try {
                        File tempFile = extractToTempFile(zip, entry);
                        try {
                            // Recursively add matches found in nested archives
                            matchCount += searchInArchive(tempFile, searchFile, currentPath);
                        } finally {
                            tempFile.delete();
                        }
                    } catch (Exception e) {
                        log("Failed to process archive: " + currentPath + " - " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Error processing archive: " + e.getMessage(), e);
        }
        return matchCount;
    }

    private File extractToTempFile(ZipFile zip, ZipEntry entry) throws IOException {
        File tempFile = File.createTempFile("nested", ".tmp");
        tempFile.deleteOnExit();

        try (InputStream in = zip.getInputStream(entry);
             OutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        return tempFile;
    }

    @Override
    public void stop() {
        if (logWriter != null) {
            logWriter.close();
        }
    }
}