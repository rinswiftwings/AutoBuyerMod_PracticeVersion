/**
 * ⚠️ IMPORTANT: PACKAGE NAMING CONVENTION
 * 
 * This package name (com.rinswiftwings.autobuyermod) is an EXAMPLE for this practice mod.
 * 
 * WHEN CREATING YOUR OWN MOD, YOU MUST change this to your own unique package name.
 * See the package declaration in AutoBuyerCore.java for detailed instructions.
 */
package com.rinswiftwings.autobuyermod;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logging utility for AutoBuyerMod.
 * 
 * DESIGN DECISIONS:
 * - Final class with private constructor: Utility class, no instantiation needed
 * - Static methods: Can be called from anywhere without instance
 * - Timestamped log files: One log file per game session (easier to track)
 * - Conditional logging: Respects user's enable_logging setting (performance)
 * - Diagnostic file: Optional, disabled in production (can be enabled for debugging)
 * 
 * WHY THIS DESIGN:
 * - Centralized logging: All mod code uses ModLog.log(), consistent format
 * - Performance: Only writes to disk if logging enabled (avoids I/O overhead)
 * - User control: Users can disable logging for better performance
 * - Debugging: Diagnostic file can be enabled for troubleshooting
 */
public final class ModLog {

    /**
     * Create timestamped log file name when class is first loaded.
     * WHY STATIC FINAL: Log file is created once when class loads, not on every log call.
     * This is more efficient than checking/creating file on every log() call.
     * 
     * WHY TIMESTAMPED: Each game session gets its own log file, making it easier to:
     * - Track issues across sessions
     * - Find logs from specific dates/times
     * - Avoid log files growing too large
     */
    private static final File LOG_FILE = createTimestampedLogFile();
    
    /**
     * Diagnostic file that's always created (regardless of logging settings).
     * DISABLED FOR PRODUCTION: Commented out for production builds.
     * 
     * WHY DISABLED: Diagnostic file writes on every mod load, even when logging disabled.
     * This creates unnecessary disk I/O in production. It's kept as an option for
     * debugging when needed.
     * 
     * To enable diagnostic file creation for debugging:
     * 1. Change DIAGNOSTIC_FILE initialization from `null` to `createDiagnosticFile()`
     * 2. Uncomment the code in updateDiagnostic() and diagnostic() methods
     * 3. Diagnostic files will be created as: diagnostic_YYYY-MM-DD_HH-MM-SS.txt
     */
    // private static final File DIAGNOSTIC_FILE = createDiagnosticFile();
    private static final File DIAGNOSTIC_FILE = null; // Disabled for production
    
    /**
     * Static reference to config for checking logging enable flag.
     * WHY: ModLog needs to check if logging is enabled before writing to disk.
     * This reference allows ModLog to check config.isLoggingEnabled() without
     * needing to pass config to every log() call.
     * 
     * WHY STATIC: Config is set once (in AutoBuyerAspect static block) and shared
     * across all logging operations. This is more efficient than passing config
     * as a parameter to every log() call.
     */
    private static AutoBuyerConfig configInstance = null;

    /**
     * Private constructor prevents instantiation.
     * WHY: This is a utility class with only static methods. There's no need
     * to create instances, so we prevent it with a private constructor.
     */
    private ModLog() { }
    
    /**
     * Create a diagnostic file that's always written, regardless of logging settings.
     * This helps verify the mod directory detection and file system access.
     * Uses timestamped filename like the regular log file.
     */
    private static File createDiagnosticFile() {
        File modFolder = getModDirectory();
        if (modFolder == null) {
            return null;
        }
        
        File logsFolder = new File(modFolder, "logs");
        if (!logsFolder.exists()) {
            logsFolder.mkdirs();
        }
        
        // Create timestamped filename like the log file: diagnostic_YYYY-MM-DD_HH-MM-SS.txt
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        String timestamp = now.format(formatter);
        File diagFile = new File(logsFolder, "diagnostic_" + timestamp + ".txt");
        
        // Always write diagnostic info immediately
        try (PrintWriter out = new PrintWriter(new FileWriter(diagFile, false))) {
            out.println("AutoBuyerMod Diagnostic File");
            out.println("===========================");
            out.println("Timestamp: " + LocalDateTime.now());
            out.println("Mod Directory: " + (modFolder != null ? modFolder.getAbsolutePath() : "null"));
            out.println("Logs Folder: " + logsFolder.getAbsolutePath());
            out.println("Log File Path: " + (LOG_FILE != null ? LOG_FILE.getAbsolutePath() : "null"));
            out.println("Config Instance: " + (configInstance != null ? "set" : "null"));
            out.println("Logging Enabled: " + (configInstance != null ? configInstance.isLoggingEnabled() : "unknown (config not set)"));
            out.println("");
            out.println("This file is created every time the mod loads.");
            out.println("If you see this file, the mod directory detection is working.");
            out.flush();
        } catch (IOException e) {
            // Can't write diagnostic file - this is a problem
            System.err.println("[AutoBuyerMod] CRITICAL: Could not write diagnostic file!");
            System.err.println("[AutoBuyerMod] Error: " + e.getMessage());
            e.printStackTrace(System.err);
        }
        
        return diagFile;
    }
    
    /**
     * Update the diagnostic file with current status.
     * DISABLED FOR PRODUCTION: This method is kept for future debugging use.
     * To enable, uncomment the code below and enable DIAGNOSTIC_FILE initialization above.
     */
    public static void updateDiagnostic(String message) {
        // Diagnostic file creation is disabled for production
        // Uncomment the code below and enable DIAGNOSTIC_FILE to use this feature
        /*
        if (DIAGNOSTIC_FILE == null) {
            return;
        }
        
        try (PrintWriter out = new PrintWriter(new FileWriter(DIAGNOSTIC_FILE, true))) {
            out.println(LocalDateTime.now() + "  " + message);
            out.flush();
        } catch (IOException e) {
            // Ignore - diagnostic file writing failures shouldn't crash the game
        }
        */
    }
    
    /**
     * Write a diagnostic message to both System.err and diagnostic file.
     * DISABLED FOR PRODUCTION: This method is kept for future debugging use.
     * To enable, uncomment the code below and enable DIAGNOSTIC_FILE initialization above.
     */
    public static void diagnostic(String message) {
        // Write to System.err (console) - always enabled for early diagnostics
        System.err.println("[AutoBuyerMod] " + message);
        
        // Diagnostic file writing is disabled for production
        // Uncomment the code below and enable DIAGNOSTIC_FILE to use this feature
        /*
        // Also write to diagnostic file
        if (DIAGNOSTIC_FILE != null) {
            try (PrintWriter out = new PrintWriter(new FileWriter(DIAGNOSTIC_FILE, true))) {
                out.println(LocalDateTime.now() + "  [DIAG] " + message);
                out.flush();
            } catch (IOException e) {
                // Ignore - diagnostic file writing failures shouldn't crash the game
            }
        }
        */
    }
    
    /**
     * Set the config instance so ModLog can check if logging is enabled.
     * Should be called during mod initialization.
     */
    public static void setConfig(AutoBuyerConfig config) {
        configInstance = config;
        updateDiagnostic("Config instance set. Logging enabled: " + config.isLoggingEnabled());
    }
    
    /**
     * Get the log file path for debugging purposes.
     * @return The absolute path to the log file, or null if not determined
     */
    public static String getLogFilePath() {
        if (LOG_FILE != null) {
            return LOG_FILE.getAbsolutePath();
        }
        return null;
    }

    /**
     * Get the mod directory (where the JAR file is located).
     */
    private static File getModDirectory() {
        try {
            // Get the JAR file location
            java.net.URL jarUrl = ModLog.class.getProtectionDomain()
                .getCodeSource()
                .getLocation();
            
            // Handle both file:/ and jar:file:/ URLs
            String urlString = jarUrl.toString();
            if (urlString.startsWith("jar:file:")) {
                // Extract file path from jar:file:/path/to/file.jar!/...
                urlString = urlString.substring(9); // Remove "jar:file:"
                int exclamationIndex = urlString.indexOf('!');
                if (exclamationIndex > 0) {
                    urlString = urlString.substring(0, exclamationIndex);
                }
                jarUrl = new java.net.URL("file:" + urlString);
            }
            
            // Convert URL to File
            File jarFile = new File(jarUrl.toURI());
            
            // The JAR's parent directory IS the mod folder
            File modFolder = jarFile.getParentFile();
            
            // Ensure mod folder exists
            if (modFolder != null) {
                modFolder.mkdirs();
                return modFolder;
            }
        } catch (Exception e) {
            // JAR location detection failed
        }
        
        // If we can't determine mod folder, this is a critical error
        // Return null and let the calling code handle it
        return null;
    }
    
    /**
     * Create a log file with timestamp in the filename.
     * Format: AutoBuyerMod_YYYY-MM-DD_HH-MM-SS.log
     * Location: mod folder/logs/
     * Each game session gets a new timestamped log file.
     */
    private static File createTimestampedLogFile() {
        File modFolder = getModDirectory();
        if (modFolder == null) {
            // Critical error: mod folder can't be determined (shouldn't happen in normal use)
            // Log to stderr for debugging
            System.err.println("[AutoBuyerMod] ERROR: Could not determine mod directory!");
            // Return a file in temp directory to prevent NullPointerException
            File tempFile = new File(System.getProperty("java.io.tmpdir"), "AutoBuyerMod_error.log");
            System.err.println("[AutoBuyerMod] Using fallback log location: " + tempFile.getAbsolutePath());
            return tempFile;
        }
        
        File logsFolder = new File(modFolder, "logs");
        // Ensure logs folder exists (create if it doesn't, but don't fail if it already exists)
        if (!logsFolder.exists()) {
            boolean created = logsFolder.mkdirs();
            System.err.println("[AutoBuyerMod] Logs folder " + (created ? "created" : "creation failed") + 
                " at: " + logsFolder.getAbsolutePath());
        } else {
            System.err.println("[AutoBuyerMod] Using existing logs folder: " + logsFolder.getAbsolutePath());
        }
        
        // Create timestamped filename for this session
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        String timestamp = now.format(formatter);
        String filename = "AutoBuyerMod_" + timestamp + ".log";
        File logFile = new File(logsFolder, filename);
        
        System.err.println("[AutoBuyerMod] Log file will be: " + logFile.getAbsolutePath());
        
        return logFile;
    }

    /**
     * Write a log message to the log file (if logging is enabled).
     * This is the main logging method used throughout the mod.
     * 
     * DESIGN:
     * - Checks if logging is enabled before writing (performance optimization)
     * - Appends to file (preserves previous log entries in same session)
     * - Includes timestamp (helps track when events occurred)
     * - Wraps in try-catch (logging failures shouldn't crash the game)
     * 
     * WHY CHECK ENABLED FIRST: File I/O is expensive. If logging is disabled,
     * we skip the file operations entirely, improving performance.
     * 
     * WHY APPEND MODE: We want all log entries in the same file for a session.
     * Append mode ensures we don't overwrite previous entries.
     */
    public static void log(String msg) {
        // Always update diagnostic file
        updateDiagnostic("Log call: " + msg + " (enabled: " + 
            (configInstance != null ? configInstance.isLoggingEnabled() : "config not set") + ")");
        
        // Check if logging is enabled (default to false if config not set yet)
        if (configInstance == null) {
            System.err.println("[AutoBuyerMod] Log call ignored: config not set yet. Message: " + msg);
            return;
        }
        
        if (!configInstance.isLoggingEnabled()) {
            // Logging is disabled - don't write, but log first attempt to stderr for debugging
            if (!loggedDisabledWarning) {
                System.err.println("[AutoBuyerMod] Logging is DISABLED. Enable it in info.xml with {enable_logging}=true");
                System.err.println("[AutoBuyerMod] Log file would be: " + LOG_FILE.getAbsolutePath());
                updateDiagnostic("WARNING: Logging is DISABLED. Enable it in info.xml with {enable_logging}=true");
                loggedDisabledWarning = true;
            }
            return;
        }
        
        try {
            // Ensure parent directory exists (should already exist, but create if needed)
            File parentDir = LOG_FILE.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                boolean created = parentDir.mkdirs();
                if (!created) {
                    System.err.println("[AutoBuyerMod] WARNING: Could not create logs directory: " + parentDir.getAbsolutePath());
                }
            }
            
            // Append to log file (creates file if it doesn't exist)
            try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE, true))) {
                out.println(LocalDateTime.now() + "  " + msg);
                out.flush();
            }
        } catch (IOException e) {
            // Log to stderr as fallback (only if logging was enabled, to avoid spam)
            System.err.println("[AutoBuyerMod] Failed to write to log file: " + LOG_FILE.getAbsolutePath());
            System.err.println("[AutoBuyerMod] Error: " + e.getMessage());
            e.printStackTrace(System.err);
            // logging must never crash the game
        }
    }
    
    // Track if we've already warned about disabled logging (to avoid spam)
    private static boolean loggedDisabledWarning = false;

    public static void log(Throwable t) {
        // Check if logging is enabled (default to false if config not set yet)
        if (configInstance == null || !configInstance.isLoggingEnabled()) {
            return;
        }
        
        try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            out.println(LocalDateTime.now() + "  EXCEPTION:");
            t.printStackTrace(out);
            out.flush();
        } catch (IOException ignored) { }
    }
}
