package nl.rowendu;

import java.io.*;
import java.util.*;
import java.util.zip.*;

public class ArchiveSearcher {
    private static final Set<String> SUPPORTED_EXTENSIONS = new HashSet<>(
        Arrays.asList(".zip", ".jar", ".ear", ".sar")
    );
    private static final String SKIP_DIRECTORY = "META-INF";
    private static final String LOG_FILE = "debug.log";
    private static PrintWriter logWriter;

    static {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, true));
        } catch (IOException e) {
            System.err.println("Could not create log file: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java -jar archivesearcher.jar \"<file to search>\" <archive file>");
            System.exit(1);
        }

        String searchFile = args[0];
        String archiveFile = args[1];

        if (!isArchiveSupported(archiveFile)) {
            System.out.println("Unsupported archive type. Supported types: " + 
                String.join(", ", SUPPORTED_EXTENSIONS));
            System.exit(1);
        }

        try {
            searchInArchive(new File(archiveFile), searchFile, "");
        } catch (IOException e) {
            System.err.println("Error processing archive: " + e.getMessage());
            System.exit(1);
        } finally {
            if (logWriter != null) {
                logWriter.close();
            }
        }
    }

    private static void log(String message) {
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    private static boolean isArchiveSupported(String fileName) {
        return SUPPORTED_EXTENSIONS.stream()
            .anyMatch(ext -> fileName.toLowerCase().endsWith(ext));
    }

    private static void searchInArchive(File archiveFile, String searchFile, String parentPath) 
            throws IOException {
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
                    // Get just the filename part for comparison
                    String fileName = new File(entry.getName()).getName();
                    if (fileName.equals(searchFile)) {
                        System.out.println("Found: " + currentPath);
                        log("Found match: " + currentPath);
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
                            searchInArchive(tempFile, searchFile, currentPath);
                        } finally {
                            tempFile.delete();
                        }
                    } catch (Exception e) {
                        log("Failed to process archive: " + currentPath + " - " + e.getMessage());
                        continue;
                    }
                }
            }
        }
    }

    private static File extractToTempFile(ZipFile zip, ZipEntry entry) throws IOException {
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
} 