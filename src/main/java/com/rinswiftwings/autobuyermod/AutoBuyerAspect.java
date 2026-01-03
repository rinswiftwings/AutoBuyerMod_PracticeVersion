/**
 * ⚠️ IMPORTANT: PACKAGE NAMING CONVENTION
 * 
 * This package name (com.rinswiftwings.autobuyermod) is an EXAMPLE for this practice mod.
 * 
 * WHEN CREATING YOUR OWN MOD, YOU MUST change this to your own unique package name.
 * See the package declaration in AutoBuyerCore.java for detailed instructions.
 */
package com.rinswiftwings.autobuyermod;

import com.badlogic.gdx.utils.Array;
import fi.bugbyte.spacehaven.ai.Trading;
import fi.bugbyte.spacehaven.world.Ship;
import fi.bugbyte.spacehaven.world.World;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;

/**
 * Helper method to get ship name safely.
 */
class AspectHelper {
    static String getShipName(Ship ship) {
        if (ship == null) {
            return "Unknown Ship (null)";
        }
        try {
            return ship.getName();
        } catch (Exception e) {
            return "Unknown Ship (ID: " + ship.getShipId() + ")";
        }
    }
}

/**
 * AspectJ aspect for hooking into game events to trigger auto-buy logic.
 * 
 * DESIGN DECISIONS:
 * - This class uses AspectJ's @Aspect annotation to intercept game method calls
 * - Static initialization block loads config before any hooks fire (ensures config is ready)
 * - Hooks are separated from business logic (AutoBuyerCore) for better organization
 * - All hooks use try-catch to prevent mod from crashing the game
 * 
 * WHY STATIC INITIALIZATION:
 * - AspectJ aspects are instantiated by the AspectJ weaver, not by our code
 * - Static block runs when class is first loaded (before any hooks fire)
 * - This ensures config is loaded before any game events trigger our hooks
 * - Config must be ready because hooks immediately need to check configuration values
 */
@Aspect
public class AutoBuyerAspect {
    
    /**
     * Configuration instance (shared across all hooks).
     * WHY STATIC: AspectJ creates one instance of the aspect, so static ensures
     * all hooks share the same config. This is more efficient than per-hook instances.
     */
    private static final AutoBuyerConfig config = new AutoBuyerConfig();
    
    /**
     * Track if preset was loaded during initial config load.
     * Initialize to false - will be set to true only if preset is successfully loaded.
     * 
     * WHY VOLATILE: This flag may be read/written from different threads (main game thread
     * and delayed recheck thread). volatile ensures visibility across threads.
     * 
     * WHY THIS FLAG: The delayed recheck needs to know if preset was already loaded,
     * so it doesn't reload unnecessarily or overwrite a preset that was already applied.
     */
    private static volatile boolean presetLoadedInitially = false;
    
    /**
     * Static initialization block - runs when class is first loaded.
     * 
     * EXECUTION ORDER:
     * 1. Set config in ModLog (so logging can check enable flag)
     * 2. Load config from info.xml (get defaults and user input)
     * 3. Schedule delayed recheck (catch modloader's async config writes)
     * 
     * WHY THIS ORDER:
     * - ModLog needs config first (to know if logging is enabled)
     * - Config must load before core (core needs config to function)
     * - Delayed recheck is last (it's a background operation, not blocking)
     * 
     * WHY STATIC BLOCK: This runs automatically when the class is loaded by AspectJ,
     * ensuring initialization happens before any hooks can fire. If we used a constructor
     * or init method, we'd have to ensure it's called, which is error-prone.
     */
    static {
        // Set config instance in ModLog so it can check logging enable flag
        ModLog.setConfig(config);
        
        // Log initialization attempt (will only write if logging is enabled after config loads)
        // This helps verify the log file path is correct
        ModLog.updateDiagnostic("AutoBuyerAspect: Static initialization block executed");
        ModLog.log("AutoBuyerAspect: Static initialization block executed");
        
        // Load config from info.xml directly (modloader doesn't call loadFromConfig)
        loadConfigFromInfoXml();
        
        /**
         * Schedule delayed re-read to catch modloader config changes.
         * WHY: Modloader writes config when "Launch" is clicked, but mod reads before that.
         * This creates a race condition where mod reads config before modloader writes it.
         * 
         * SOLUTION: Delayed recheck waits 2 seconds, then re-reads config. By then,
         * modloader has written the config, so we can apply it.
         * 
         * This is a workaround for modloader's asynchronous config writing. Ideally,
         * modloader would provide a callback or synchronous API, but this works reliably.
         */
        scheduleDelayedConfigRecheck();
    }
    
    /**
     * Core business logic instance (shared across all hooks).
     * WHY STATIC: Same reason as config - one instance shared by all hooks.
     * WHY FINAL: Core is created once and never changes (immutable reference).
     */
    private static final AutoBuyerCore core = new AutoBuyerCore(config);
    
    /**
     * Load configuration using hybrid approach:
     * 1. Try to get user input from modloader (config file or API)
     * 2. Load defaults from info.xml
     * 3. Merge: user input overrides defaults
     * 4. Apply preset if specified
     * 
     * WHY THIS APPROACH:
     * - Defaults ensure mod works out-of-the-box (user doesn't need to configure everything)
     * - User input overrides defaults (respects user's choices from modloader UI)
     * - Presets override everything (presets are complete configurations)
     * 
     * This priority system (defaults < user input < preset) gives users flexibility:
     * - Can use defaults (no configuration needed)
     * - Can customize individual values (user input)
     * - Can switch entire configurations (presets)
     * 
     * This ensures user input from modloader UI is respected while still having defaults.
     */
    private static void loadConfigFromInfoXml() {
        // Reset flag at start of each config load
        presetLoadedInitially = false;
        
        try {
            // Get mod directory
            java.io.File modFolder = AutoBuyerConfig.getModDirectory();
            if (modFolder == null) {
                ModLog.updateDiagnostic("ERROR: Could not determine mod directory for loading config");
                return;
            }
            
            // Step 1: Try to load user input from modloader config file
            // Common locations: mod_config.json, user_config.json, or config.json in mod folder
            java.util.Map<String, String> userInputValues = loadModloaderConfig(modFolder);
            
            // Log what we found from modloader
            if (userInputValues != null && !userInputValues.isEmpty()) {
                ModLog.log("AutoBuyerAspect: Found " + userInputValues.size() + " user input values from modloader:");
                for (java.util.Map.Entry<String, String> entry : userInputValues.entrySet()) {
                    ModLog.log("AutoBuyerAspect:   " + entry.getKey() + " = " + entry.getValue());
                }
            } else {
                ModLog.log("AutoBuyerAspect: No user input values found from modloader");
            }
            
            // Step 2: Load defaults from info.xml
            java.io.File infoXmlFile = new java.io.File(modFolder, "info.xml");
            if (!infoXmlFile.exists()) {
                ModLog.updateDiagnostic("WARNING: info.xml not found at: " + infoXmlFile.getAbsolutePath());
                // If no info.xml, try to use user input only
                if (userInputValues != null && !userInputValues.isEmpty()) {
                    ModLog.updateDiagnostic("Using user input values only (no info.xml found)");
                    config.loadFromConfig(userInputValues);
                }
                return;
            }
            
            ModLog.updateDiagnostic("Loading defaults from info.xml: " + infoXmlFile.getAbsolutePath());
            java.util.Map<String, String> defaultValues = parseInfoXml(infoXmlFile);
            
            if (defaultValues == null || defaultValues.isEmpty()) {
                ModLog.updateDiagnostic("WARNING: No config values found in info.xml");
                // Try to use user input only
                if (userInputValues != null && !userInputValues.isEmpty()) {
                    ModLog.updateDiagnostic("Using user input values only (info.xml empty)");
                    config.loadFromConfig(userInputValues);
                }
                return;
            }
            
            ModLog.updateDiagnostic("Parsed " + defaultValues.size() + " default values from info.xml");
            if (userInputValues != null && !userInputValues.isEmpty()) {
                ModLog.updateDiagnostic("Found " + userInputValues.size() + " user input values from modloader");
            }
            
            // Step 3: Merge - defaults first, then user input overrides
            java.util.Map<String, String> mergedValues = new java.util.HashMap<>(defaultValues);
            if (userInputValues != null && !userInputValues.isEmpty()) {
                mergedValues.putAll(userInputValues); // User input overrides defaults
                ModLog.log("AutoBuyerAspect: Merged config: " + mergedValues.size() + " total values (info.xml defaults + user input overrides)");
                ModLog.updateDiagnostic("Merged config: " + mergedValues.size() + " total values (info.xml defaults + user input overrides)");
            } else {
                ModLog.log("AutoBuyerAspect: No user input found, using info.xml defaults only");
                ModLog.updateDiagnostic("No user input found, using info.xml defaults only");
            }
            
            // Step 4: Check if a preset is specified (preset overrides everything)
            String presetName = mergedValues.get("{config_preset}");
            // Use diagnostic() for early diagnostic (writes to both System.err and diagnostic file)
            ModLog.diagnostic("Initial preset check - {config_preset} value: '" + (presetName != null ? presetName : "null") + "'");
            ModLog.log("AutoBuyerAspect: Checking for preset - {config_preset} value: '" + (presetName != null ? presetName : "null") + "'");
            if (presetName != null && !presetName.trim().isEmpty()) {
                presetName = presetName.trim();
                ModLog.diagnostic("Preset specified: " + presetName);
                ModLog.log("AutoBuyerAspect: Preset specified: " + presetName);
                ModLog.updateDiagnostic("Preset specified: " + presetName);
                java.util.Map<String, String> presetValues = loadPreset(presetName);
                if (presetValues != null && !presetValues.isEmpty()) {
                    ModLog.diagnostic("Loaded " + presetValues.size() + " values from preset: " + presetName);
                    ModLog.log("AutoBuyerAspect: Loaded " + presetValues.size() + " values from preset: " + presetName);
                    ModLog.updateDiagnostic("Loaded " + presetValues.size() + " values from preset: " + presetName);
                    // Preset values override merged values (user input + defaults)
                    mergedValues.putAll(presetValues);
                    ModLog.log("AutoBuyerAspect: Final merged config: " + mergedValues.size() + " total values (defaults + user input + preset overrides)");
                    ModLog.updateDiagnostic("Final merged config: " + mergedValues.size() + " total values (defaults + user input + preset overrides)");
                    presetLoadedInitially = true; // Mark that preset was loaded
                    ModLog.diagnostic("Preset loaded successfully, flag set to TRUE");
                } else {
                    ModLog.diagnostic("WARNING: Preset file not found or empty: " + presetName);
                    ModLog.log("AutoBuyerAspect: WARNING: Preset file not found or empty: " + presetName + ", using merged values (defaults + user input)");
                    ModLog.updateDiagnostic("WARNING: Preset file not found or empty, using merged values (defaults + user input)");
                    presetLoadedInitially = false; // Preset was specified but not loaded
                    ModLog.diagnostic("Preset NOT loaded, flag set to FALSE");
                }
            } else {
                // No preset found initially - will check again after delay
                ModLog.diagnostic("No preset specified initially (value is empty or null)");
                ModLog.log("AutoBuyerAspect: No preset specified initially (value is empty or null)");
                ModLog.updateDiagnostic("No preset specified initially - will check again after delay");
                presetLoadedInitially = false;
                ModLog.diagnostic("No preset found, flag set to FALSE");
            }
            
            // Load the final merged configuration
            config.loadFromConfig(mergedValues);
            
        } catch (Exception e) {
            ModLog.updateDiagnostic("ERROR loading config: " + e.getMessage());
            ModLog.log("AutoBuyerAspect: Exception in loadConfigFromInfoXml: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Schedule a delayed re-read of info.xml to catch modloader config changes.
     * Modloader writes config when "Launch" is clicked, but mod reads before that.
     * This re-read happens after a 2-second delay to catch the modloader's changes.
     * 
     * WHY THIS IS NEEDED:
     * - Modloader UI allows users to select presets
     * - When user clicks "Launch", modloader writes preset name to info.xml
     * - But mod's static block runs BEFORE modloader writes (race condition)
     * - Result: Mod reads config before preset name is written
     * 
     * SOLUTION: Background thread waits 2 seconds, then re-reads info.xml.
     * By then, modloader has written the preset name, so we can load it.
     * 
     * WHY BACKGROUND THREAD: We don't want to block the main game thread.
     * The delay is acceptable because config can be applied after game starts.
     */
    private static void scheduleDelayedConfigRecheck() {
        new Thread(() -> {
            try {
                /**
                 * Wait 2 seconds for modloader to finish writing info.xml.
                 * WHY 2 SECONDS: This is a conservative delay that gives modloader
                 * enough time to write config, but isn't so long that it feels slow.
                 * In practice, modloader writes config almost instantly, so 2 seconds
                 * is more than enough, but provides a safety margin.
                 */
                Thread.sleep(2000);
                
                ModLog.log("AutoBuyerAspect: Delayed config recheck - checking if preset was set by modloader");
                ModLog.updateDiagnostic("Delayed config recheck - checking if preset was set by modloader");
                
                recheckConfigForPreset();
            } catch (InterruptedException e) {
                // Thread interrupted - ignore
                ModLog.updateDiagnostic("Delayed config recheck thread interrupted");
            } catch (Exception e) {
                ModLog.updateDiagnostic("ERROR in delayed config recheck: " + e.getMessage());
                ModLog.log("AutoBuyerAspect: Exception in delayed config recheck: " + e.getMessage());
                ModLog.log(e);
            }
        }, "AutoBuyerMod-DelayedConfigRecheck").start();
    }
    
    /**
     * Re-check info.xml for preset value that may have been set by modloader.
     * If preset was not loaded initially but is now set, load it.
     */
    private static void recheckConfigForPreset() {
        try {
            // Get mod directory
            java.io.File modFolder = AutoBuyerConfig.getModDirectory();
            if (modFolder == null) {
                ModLog.updateDiagnostic("ERROR: Could not determine mod directory for delayed config recheck");
                return;
            }
            
            // Re-read info.xml
            java.io.File infoXmlFile = new java.io.File(modFolder, "info.xml");
            if (!infoXmlFile.exists()) {
                ModLog.updateDiagnostic("WARNING: info.xml not found during delayed recheck");
                return;
            }
            
            ModLog.log("AutoBuyerAspect: Re-reading info.xml for delayed preset check");
            ModLog.updateDiagnostic("Re-reading info.xml for delayed preset check");
            
            java.util.Map<String, String> currentValues = parseInfoXml(infoXmlFile);
            if (currentValues == null || currentValues.isEmpty()) {
                ModLog.updateDiagnostic("WARNING: No config values found in info.xml during delayed recheck");
                return;
            }
            
            // Check if {config_preset} is now set
            String presetName = currentValues.get("{config_preset}");
            ModLog.diagnostic("[DELAYED] Found {config_preset} value: '" + (presetName != null ? presetName : "null") + "'");
            ModLog.diagnostic("[DELAYED] presetLoadedInitially flag: " + presetLoadedInitially);
            ModLog.log("AutoBuyerAspect: [DELAYED] Found {config_preset} value: '" + (presetName != null ? presetName : "null") + "'");
            ModLog.log("AutoBuyerAspect: [DELAYED] presetLoadedInitially flag: " + presetLoadedInitially);
            if (presetName != null && !presetName.trim().isEmpty()) {
                presetName = presetName.trim();
                
                // Only load preset if it wasn't loaded initially
                if (!presetLoadedInitially) {
                    ModLog.log("AutoBuyerAspect: [DELAYED] Preset found in info.xml: " + presetName + " (was not loaded initially)");
                    ModLog.updateDiagnostic("[DELAYED] Preset found: " + presetName + " - loading now");
                    
                    // Load the preset
                    java.util.Map<String, String> presetValues = loadPreset(presetName);
                    if (presetValues != null && !presetValues.isEmpty()) {
                        ModLog.log("AutoBuyerAspect: [DELAYED] Loaded " + presetValues.size() + " values from preset: " + presetName);
                        ModLog.updateDiagnostic("[DELAYED] Loaded " + presetValues.size() + " values from preset: " + presetName);
                        
                        // Merge current config with preset values
                        // Get current config values from info.xml (includes modloader changes)
                        java.util.Map<String, String> mergedValues = new java.util.HashMap<>(currentValues);
                        
                        // Preset values override everything
                        mergedValues.putAll(presetValues);
                        
                        ModLog.log("AutoBuyerAspect: [DELAYED] Applying preset values to config");
                        ModLog.updateDiagnostic("[DELAYED] Applying preset values to config");
                        
                        // Reload config with preset values
                        config.loadFromConfig(mergedValues);
                        
                        // Mark preset as loaded
                        presetLoadedInitially = true;
                        
                        ModLog.log("AutoBuyerAspect: [DELAYED] Preset successfully loaded and applied: " + presetName);
                        ModLog.updateDiagnostic("[DELAYED] Preset successfully loaded: " + presetName);
                        
                        // Log final configuration to show preset was applied
                        config.logFinalConfiguration();
                    } else {
                        ModLog.log("AutoBuyerAspect: [DELAYED] WARNING: Preset file not found or empty: " + presetName);
                        ModLog.updateDiagnostic("[DELAYED] WARNING: Preset file not found: " + presetName);
                    }
                } else {
                    ModLog.log("AutoBuyerAspect: [DELAYED] Preset already loaded initially, skipping");
                    ModLog.updateDiagnostic("[DELAYED] Preset already loaded initially");
                }
            } else {
                ModLog.log("AutoBuyerAspect: [DELAYED] No preset found in info.xml (value is empty or null)");
                ModLog.updateDiagnostic("[DELAYED] No preset found in info.xml");
            }
            
        } catch (Exception e) {
            ModLog.updateDiagnostic("ERROR in delayed preset recheck: " + e.getMessage());
            ModLog.log("AutoBuyerAspect: Exception in delayed preset recheck: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Try to load user input values from modloader.
     * Checks multiple sources:
     * 1. System properties (modloader might set -Dconfig.variable=value)
     * 2. Environment variables (modloader might set CONFIG_variable=value)
     * 3. Config files in mod folder (modloader might write user input to a file)
     * 
     * @param modFolder The mod directory
     * @return Map of user input values, or null if not found
     */
    private static java.util.Map<String, String> loadModloaderConfig(java.io.File modFolder) {
        java.util.Map<String, String> userValues = new java.util.HashMap<>();
        
        // Method 1: Check System properties (e.g., -Dconfig.{variable}=value)
        try {
            java.util.Properties sysProps = System.getProperties();
            for (String propName : sysProps.stringPropertyNames()) {
                if (propName.startsWith("config.") || propName.startsWith("mod.config.")) {
                    String varName = propName.substring(propName.lastIndexOf('.') + 1);
                    // Convert to config variable format: {variable_name}
                    if (!varName.startsWith("{")) {
                        varName = "{" + varName + "}";
                    }
                    String value = sysProps.getProperty(propName);
                    if (value != null) {
                        userValues.put(varName, value);
                    }
                }
            }
            if (!userValues.isEmpty()) {
                ModLog.log("AutoBuyerAspect: Found " + userValues.size() + " config values from System properties");
                ModLog.updateDiagnostic("Found " + userValues.size() + " config values from System properties");
                return userValues;
            }
        } catch (Exception e) {
            // Ignore - not critical
        }
        
        // Method 2: Check Environment variables (e.g., CONFIG_{variable}=value)
        try {
            java.util.Map<String, String> env = System.getenv();
            for (java.util.Map.Entry<String, String> entry : env.entrySet()) {
                String envName = entry.getKey();
                if (envName.startsWith("CONFIG_") || envName.startsWith("MOD_CONFIG_")) {
                    String varName = envName.substring(envName.indexOf('_') + 1);
                    // Convert to config variable format: {variable_name}
                    if (!varName.startsWith("{")) {
                        varName = "{" + varName.toLowerCase().replace("_", "_") + "}";
                    }
                    userValues.put(varName, entry.getValue());
                }
            }
            if (!userValues.isEmpty()) {
                ModLog.log("AutoBuyerAspect: Found " + userValues.size() + " config values from Environment variables");
                ModLog.updateDiagnostic("Found " + userValues.size() + " config values from Environment variables");
                return userValues;
            }
        } catch (Exception e) {
            // Ignore - not critical
        }
        
        // Method 3: Check config files in mod folder
        // Common modloader config file names
        String[] configFileNames = {
            "mod_config.json",      // Most likely name
            "user_config.json",     // Alternative name
            "config.json",          // Generic name
            "modloader_config.json" // Explicit name
        };
        
        for (String configFileName : configFileNames) {
            java.io.File configFile = new java.io.File(modFolder, configFileName);
            if (configFile.exists() && configFile.isFile()) {
                try {
                    ModLog.log("AutoBuyerAspect: Found modloader config file: " + configFile.getAbsolutePath());
                    ModLog.updateDiagnostic("Found modloader config file: " + configFile.getAbsolutePath());
                    java.util.Map<String, String> fileValues = parseModloaderConfigJson(configFile);
                    if (fileValues != null && !fileValues.isEmpty()) {
                        userValues.putAll(fileValues);
                        ModLog.log("AutoBuyerAspect: Loaded " + fileValues.size() + " user input values from: " + configFileName);
                        ModLog.updateDiagnostic("Loaded " + fileValues.size() + " user input values from: " + configFileName);
                    }
                } catch (Exception e) {
                    ModLog.updateDiagnostic("ERROR reading modloader config file " + configFileName + ": " + e.getMessage());
                    // Continue to next file
                }
            }
        }
        
        // Also check parent directory (game mods folder) for a global config
        try {
            java.io.File parentDir = modFolder.getParentFile();
            if (parentDir != null) {
                java.io.File globalConfig = new java.io.File(parentDir, "modloader_config.json");
                if (globalConfig.exists() && globalConfig.isFile()) {
                    ModLog.updateDiagnostic("Found global modloader config: " + globalConfig.getAbsolutePath());
                    java.util.Map<String, String> globalValues = parseModloaderConfigJson(globalConfig);
                    if (globalValues != null && !globalValues.isEmpty()) {
                        userValues.putAll(globalValues);
                        ModLog.updateDiagnostic("Loaded " + globalValues.size() + " user input values from global config");
                    }
                }
            }
        } catch (Exception e) {
            // Ignore - not critical
        }
        
        if (!userValues.isEmpty()) {
            ModLog.log("AutoBuyerAspect: Total user input values found: " + userValues.size());
            ModLog.updateDiagnostic("Total user input values found: " + userValues.size());
            return userValues;
        }
        
        ModLog.log("AutoBuyerAspect: No modloader user input found - using info.xml defaults only");
        ModLog.updateDiagnostic("No modloader user input found - using info.xml defaults only");
        return null;
    }
    
    /**
     * Parse a modloader config JSON file.
     * Expected format: {"{variable_name}": "value", ...}
     * 
     * @param configFile The JSON config file
     * @return Map of config values, or null if parsing fails
     */
    private static java.util.Map<String, String> parseModloaderConfigJson(java.io.File configFile) {
        java.util.Map<String, String> configValues = new java.util.HashMap<>();
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(configFile))) {
            
            // Simple JSON parsing - look for key-value pairs
            // Format: "{variable_name}": "value" or "{variable_name}": value
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                    continue;
                }
                
                // Look for key-value pairs: "key": "value" or "key": value
                if (line.contains(":")) {
                    int colonIndex = line.indexOf(':');
                    String key = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();
                    
                    // Remove quotes from key
                    if (key.startsWith("\"") && key.endsWith("\"")) {
                        key = key.substring(1, key.length() - 1);
                    }
                    
                    // Remove quotes and trailing comma from value
                    if (value.startsWith("\"")) {
                        value = value.substring(1);
                    }
                    if (value.endsWith(",")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    if (value.endsWith("\"")) {
                        value = value.substring(0, value.length() - 1);
                    }
                    
                    // Only add if key looks like a config variable (starts with {)
                    if (key.startsWith("{") && key.endsWith("}")) {
                        configValues.put(key, value);
                    }
                }
            }
        } catch (java.io.IOException e) {
            ModLog.updateDiagnostic("ERROR reading modloader config JSON: " + e.getMessage());
            return null;
        }
        
        return configValues;
    }
    
    /**
     * Parse info.xml and extract config variable values.
     * Simple XML parsing - looks for <var> elements with name and value attributes.
     */
    private static java.util.Map<String, String> parseInfoXml(java.io.File infoXmlFile) {
        java.util.Map<String, String> configValues = new java.util.HashMap<>();
        
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(infoXmlFile))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Look for <var> elements
                if (line.startsWith("<var")) {
                    // Extract name attribute: name="{variable_name}"
                    int nameStart = line.indexOf("name=\"");
                    if (nameStart >= 0) {
                        nameStart += 6; // Skip "name=\""
                        int nameEnd = line.indexOf("\"", nameStart);
                        if (nameEnd > nameStart) {
                            String varName = line.substring(nameStart, nameEnd);
                            
                            // Extract value attribute: value="value_string"
                            int valueStart = line.indexOf("value=\"");
                            if (valueStart >= 0) {
                                valueStart += 7; // Skip "value=\""
                                int valueEnd = line.indexOf("\"", valueStart);
                                if (valueEnd > valueStart) {
                                    String varValue = line.substring(valueStart, valueEnd);
                                    configValues.put(varName, varValue);
                                }
                            }
                        }
                    }
                }
            }
        } catch (java.io.IOException e) {
            ModLog.updateDiagnostic("ERROR reading info.xml: " + e.getMessage());
            return null;
        }
        
        return configValues;
    }
    
    /**
     * Load a preset from a JSON file.
     * @param presetName The name of the preset (without .json extension)
     * @return Map of config values from the preset, or null if preset not found
     */
    private static java.util.Map<String, String> loadPreset(String presetName) {
        try {
            java.io.File presetsDir = AutoBuyerConfig.getPresetsDirectory();
            if (presetsDir == null) {
                ModLog.log("AutoBuyerAspect: ERROR: Could not determine presets directory");
                ModLog.updateDiagnostic("ERROR: Could not determine presets directory");
                return null;
            }
            
            // Try exact case first
            java.io.File presetFile = new java.io.File(presetsDir, presetName + ".json");
            
            // If not found, try case-insensitive search
            if (!presetFile.exists()) {
                ModLog.diagnostic("Preset file not found with exact case: " + presetName + ".json, trying case-insensitive search");
                ModLog.log("AutoBuyerAspect: Preset file not found with exact case: " + presetName + ".json, trying case-insensitive search");
                String targetName = presetName.toLowerCase() + ".json";
                java.io.File[] files = presetsDir.listFiles();
                if (files != null) {
                    ModLog.diagnostic("Searching in presets directory, found " + files.length + " files");
                    for (java.io.File file : files) {
                        if (file.isFile() && file.getName().toLowerCase().equals(targetName)) {
                            presetFile = file;
                            ModLog.diagnostic("Found preset file with different case: " + file.getName() + " (requested: " + presetName + ".json)");
                            ModLog.log("AutoBuyerAspect: Found preset file with different case: " + file.getName() + " (requested: " + presetName + ".json)");
                            break;
                        }
                    }
                } else {
                    ModLog.diagnostic("ERROR: Could not list files in presets directory");
                }
            } else {
                ModLog.diagnostic("Preset file found with exact case: " + presetFile.getName());
            }
            
            if (!presetFile.exists()) {
                ModLog.log("AutoBuyerAspect: Preset file not found: " + presetName + ".json in " + presetsDir.getAbsolutePath());
                ModLog.updateDiagnostic("Preset file not found: " + presetFile.getAbsolutePath());
                return null;
            }
            
            ModLog.log("AutoBuyerAspect: Loading preset from: " + presetFile.getAbsolutePath());
            ModLog.updateDiagnostic("Loading preset from: " + presetFile.getAbsolutePath());
            
            // Simple JSON parsing - look for "config" section
            java.util.Map<String, String> presetValues = new java.util.HashMap<>();
            
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(presetFile))) {
                
                boolean inConfigSection = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    
                    // Check if we're entering the config section
                    if (line.contains("\"config\"")) {
                        inConfigSection = true;
                        continue;
                    }
                    
                    // Check if we're leaving the config section
                    if (inConfigSection && line.startsWith("}")) {
                        break;
                    }
                    
                    // Parse config entries: "{variable_name}": "value"
                    if (inConfigSection && line.startsWith("\"{")) {
                        int colonIndex = line.indexOf(':');
                        if (colonIndex > 0) {
                            // Extract variable name: "{variable_name}" -> {variable_name}
                            // Line format: "    "{water}": "100","
                            // After trim: "{water}": "100",
                            // We want: {water}
                            String varName = line.substring(0, colonIndex).trim();
                            // Remove leading quote
                            if (varName.startsWith("\"")) {
                                varName = varName.substring(1);
                            }
                            // Remove trailing quote
                            if (varName.endsWith("\"")) {
                                varName = varName.substring(0, varName.length() - 1);
                            }
                            // varName should now be "{water}" (with braces)
                            
                            // Extract value (remove quotes and comma)
                            String value = line.substring(colonIndex + 1).trim();
                            if (value.startsWith("\"")) {
                                value = value.substring(1);
                            }
                            if (value.endsWith(",")) {
                                value = value.substring(0, value.length() - 1);
                            }
                            if (value.endsWith("\"")) {
                                value = value.substring(0, value.length() - 1);
                            }
                            
                            presetValues.put(varName, value);
                            ModLog.diagnostic("Preset parsing: " + varName + " = " + value);
                        }
                    }
                }
            }
            
            ModLog.log("AutoBuyerAspect: Successfully loaded " + presetValues.size() + " config values from preset: " + presetName);
            return presetValues;
        } catch (Exception e) {
            ModLog.log("AutoBuyerAspect: ERROR loading preset: " + presetName + " - " + e.getMessage());
            ModLog.updateDiagnostic("ERROR loading preset: " + e.getMessage());
            ModLog.log(e);
            return null;
        }
    }
    
    static {
        ModLog.log("AutoBuyerAspect: Loaded and initialized");
    }
    
    /**
     * Hook: Called when a trade completes successfully.
     * Attempts to enqueue the next purchase if slot is available.
     */
    /**
     * Hook: Trade completion detection.
     * 
     * POINTCUT EXPLANATION:
     * - `execution(* World.setTradeDone(..))` - Matches when setTradeDone() is called
     * - `args(tradeAgreement)` - Captures the trade agreement parameter
     * - `this(world)` - Captures the World instance (this in the method)
     * 
     * WHY @After: We want to run AFTER the trade is marked as done, so we know
     * the trade is fully complete and we can safely clean up state.
     * 
     * WHY THIS HOOK: When a trade completes, a trade slot frees up. We want to
     * immediately check for new trade opportunities with the best available ship.
     * This ensures we're always using all available trade slots efficiently.
     */
    @After("execution(* fi.bugbyte.spacehaven.world.World.setTradeDone(..)) && args(tradeAgreement) && this(world)")
    public void onTradeDone(Trading.TradeAgreement tradeAgreement, World world) {
        try {
            if (tradeAgreement == null || !tradeAgreement.isPlayerTrade()) {
                return;
            }
            
            ModLog.log("AutoBuyerAspect: Trade " + tradeAgreement.id + " completed");
            
            if (world == null) {
                return;
            }
            
            // Determine which ship was the NPC (the one we're buying from)
            Ship npcShip = null;
            
            // Check shipId2 (typically NPC when buying)
            Ship ship2 = world.getShip(tradeAgreement.shipId2);
            Ship ship1 = world.getShip(tradeAgreement.shipId1);
            
            if (ship1 != null && ship1.isPlayerShip() && ship1.isStation()) {
                npcShip = ship2;
            } else if (ship2 != null && ship2.isPlayerShip() && ship2.isStation()) {
                npcShip = ship1;
            }
            
            if (npcShip != null && !npcShip.isPlayerShip() && !npcShip.isDerelict() && !npcShip.isClaimable()) {
                // Remove trade ID from tracking to prevent memory leak
                core.removeTradeId(tradeAgreement.id);
                // Reset flags - a slot freed up, so we should check again
                core.resetShipFlags(npcShip.getShipId(), npcShip);
                // A trade slot freed up - check all eligible ships and pick the best one
                ModLog.log("AutoBuyerAspect: Trade completed, checking all eligible ships for best trade opportunity");
                core.attemptBestTrade(world);
            }
            
        } catch (Exception e) {
            ModLog.log("AutoBuyerAspect: Exception in onTradeDone: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Hook: Called when a trade is cancelled.
     * Attempts to enqueue purchase again if slot is freed.
     */
    @After("execution(* fi.bugbyte.spacehaven.world.World.cancelTrade(..)) && args(tradeAgreement, playerJumped) && this(world)")
    public void onTradeCancelled(Trading.TradeAgreement tradeAgreement, boolean playerJumped, World world) {
        try {
            if (tradeAgreement == null || !tradeAgreement.isPlayerTrade()) {
                return;
            }
            
            // Enhanced cancellation logging
            String shipName = "Unknown";
            int npcShipId = 0;
            Ship npcShip = null;
            Ship ship2 = world != null ? world.getShip(tradeAgreement.shipId2) : null;
            Ship ship1 = world != null ? world.getShip(tradeAgreement.shipId1) : null;
            
            if (ship1 != null && ship1.isPlayerShip() && ship1.isStation()) {
                npcShip = ship2;
            } else if (ship2 != null && ship2.isPlayerShip() && ship2.isStation()) {
                npcShip = ship1;
            }
            
            if (npcShip != null) {
                shipName = AspectHelper.getShipName(npcShip);
                npcShipId = npcShip.getShipId();
            }
            
            // Build detailed cancellation log
            StringBuilder cancelLog = new StringBuilder("AutoBuyerAspect: [TRADE CANCELLED] Trade " + tradeAgreement.id + 
                " with ship " + shipName + " (ID: " + npcShipId + ")");
            cancelLog.append(" - playerJumped: " + playerJumped);
            cancelLog.append(", Credits: " + tradeAgreement.creditsToShip2);
            cancelLog.append(", Items: " + tradeAgreement.toShip1.size + " types");
            
            // Log item details
            if (tradeAgreement.toShip1 != null && tradeAgreement.toShip1.size > 0) {
                cancelLog.append(" (");
                for (int i = 0; i < tradeAgreement.toShip1.size; i++) {
                    Trading.TradeItem item = tradeAgreement.toShip1.get(i);
                    if (i > 0) cancelLog.append(", ");
                    cancelLog.append("ID: ").append(item.elementaryId).append(" x").append(item.howMuch);
                }
                cancelLog.append(")");
            }
            
            // Check ship state if available
            if (npcShip != null && !npcShip.isPlayerShip()) {
                try {
                    boolean isDerelict = npcShip.isDerelict();
                    boolean isClaimable = npcShip.isClaimable();
                    cancelLog.append(", Ship State: derelict=").append(isDerelict).append(", claimable=").append(isClaimable);
                    
                    // Check if ship is still in sector
                    fi.bugbyte.spacehaven.ai.EncounterAI.AiShipInfo aiInfo = npcShip.getAiShipInfo(false);
                    if (aiInfo != null) {
                        Ship playerStation = null;
                        Array<Ship> ships = world.getShips();
                        for (int i = 0; i < ships.size; i++) {
                            Ship s = ships.get(i);
                            if (s.isPlayerShip() && s.isStation()) {
                                playerStation = s;
                                break;
                            }
                        }
                        if (playerStation != null) {
                            boolean canTrade = aiInfo.canTradeWith(playerStation.getShipId());
                            cancelLog.append(", CanTrade: ").append(canTrade);
                        }
                    }
                } catch (Exception e) {
                    cancelLog.append(", Ship State Check Failed: ").append(e.getMessage());
                }
            }
            
            ModLog.log(cancelLog.toString());
            
            // Don't attempt if player jumped (ship is gone)
            if (playerJumped) {
                ModLog.log("AutoBuyerAspect: [TRADE CANCELLED] Skipping retry - player jumped (ship left sector)");
                return;
            }
            
            if (world == null) {
                return;
            }
            
            if (npcShip != null && !npcShip.isPlayerShip() && !npcShip.isDerelict() && !npcShip.isClaimable()) {
                // Remove trade ID from tracking to prevent memory leak
                core.removeTradeId(tradeAgreement.id);
                // Reset flags - a slot freed up, so we should check again
                core.resetShipFlags(npcShip.getShipId(), npcShip);
                // A trade slot freed up - check all eligible ships and pick the best one
                ModLog.log("AutoBuyerAspect: [TRADE CANCELLED] Trade slot freed, checking all eligible ships for best trade opportunity");
                core.attemptBestTrade(world);
            } else {
                ModLog.log("AutoBuyerAspect: [TRADE CANCELLED] Skipping retry - ship invalid (derelict/claimable/player ship)");
            }
            
        } catch (Exception e) {
            ModLog.log("AutoBuyerAspect: Exception in onTradeCancelled: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Hook: Called when a ship jumps/leaves the sector.
     * Flushes bookkeeping for that ship.
     */
    @After("execution(* fi.bugbyte.spacehaven.world.World.shipJumped(..)) && args(ship) && this(world)")
    public void onShipJumped(Ship ship, World world) {
        try {
            if (ship == null) {
                return;
            }
            
            String shipName = AspectHelper.getShipName(ship);
            ModLog.log("AutoBuyerAspect: Ship " + shipName + " (ID: " + ship.getShipId() + ") jumped/left sector");
            
            // Flush state for this ship
            core.flushShipState(ship.getShipId(), ship);
            
        } catch (Exception e) {
            ModLog.log("AutoBuyerAspect: Exception in onShipJumped: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Optional Hook: Called when a ship is added to the sector.
     * Could trigger initial auto-buy attempt for newly arrived NPC ships.
     */
    @After("execution(* fi.bugbyte.spacehaven.world.World.addShip(..)) && args(ship) && this(world)")
    public void onShipAdded(Ship ship, World world) {
        try {
            if (ship == null) {
                return;
            }
            
            // Only process NPC ships
            if (ship.isPlayerShip() || ship.isDerelict() || ship.isClaimable()) {
                return;
            }
            
            String shipName = AspectHelper.getShipName(ship);
            ModLog.log("AutoBuyerAspect: NPC ship " + shipName + " (ID: " + ship.getShipId() + ") added to sector");
            
            // Mark ship as new and trigger initial trade attempt
            // Use attemptBestTrade to check all eligible ships (including retries for ships with no offers)
            if (world != null) {
                // Mark ship as new (allows retries before marking as "nothing to purchase")
                // This also starts the 10-second initialization delay timer
                core.markShipAsNew(ship.getShipId());
                // Check all eligible ships and pick the best one (this will also retry any ships that need it)
                // Note: New ships will be skipped until 10 seconds have passed
                core.attemptBestTrade(world);
            }
            
        } catch (Exception e) {
            ModLog.log("AutoBuyerAspect: Exception in onShipAdded: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Hook: Called when an entity boards a ship (e.g., when NPC characters exit a docked shuttle).
     * This is a reliable hook for detecting when NPC shuttles dock at player stations.
     * 
     * When NPC characters exit a shuttle that has docked at a player station, this method
     * is called with the player station as 'this', the character as 'e', and the shuttle as 'fromCraft'.
     */
    @After("execution(* fi.bugbyte.spacehaven.world.Ship.entityBoarded(..)) && args(entity, at, moveToCrewSpot, fromCraft) && this(ship)")
    public void onEntityBoarded(fi.bugbyte.spacehaven.stuff.Entity entity, fi.bugbyte.spacehaven.world.elements.Door.BaseShipDockingPort at, boolean moveToCrewSpot, fi.bugbyte.spacehaven.stuff.crafts.Craft fromCraft, fi.bugbyte.spacehaven.world.Ship ship) {
        try {
            // Only process if this is a player station
            if (ship == null || !ship.isPlayerShip() || !ship.isStation()) {
                return;
            }
            
            // Only process if entity came from a craft (shuttle)
            if (fromCraft == null) {
                return;
            }
            
            // Only process if it's a shuttle
            if (fromCraft.getType() != fi.bugbyte.spacehaven.stuff.crafts.Craft.Type.Shuttle) {
                return;
            }
            
            // Only process NPC shuttles (not player shuttles returning to station)
            fi.bugbyte.spacehaven.stuff.FactionUtils.FactionSide shuttleSide = fromCraft.getSide();
            if (shuttleSide == fi.bugbyte.spacehaven.stuff.FactionUtils.FactionSide.Player) {
                return;
            }
            
            // Only process NPC entities (characters/robots from NPC ships)
            if (entity == null) {
                return;
            }
            
            fi.bugbyte.spacehaven.stuff.FactionUtils.FactionSide entitySide = entity.getWorkSide();
            if (entitySide == null || entitySide == fi.bugbyte.spacehaven.stuff.FactionUtils.FactionSide.Player) {
                return;
            }
            
            // Get world from the ship
            fi.bugbyte.spacehaven.world.World world = ship.getWorld();
            if (world != null) {
                // Trigger recheck for new ships that may have passed their delay
                // This helps with the single-ship scenario where a new ship arrives and there are no active trades
                ModLog.log("AutoBuyerAspect: [entityBoarded hook] NPC character/robot from NPC shuttle boarded player station - triggering trade recheck");
                core.attemptBestTrade(world);
            }
            
        } catch (Exception e) {
            // Log but don't fail - this hook may not work if method signature is different
            ModLog.log("AutoBuyerAspect: Exception in onEntityBoarded (hook may need method signature adjustment): " + e.getMessage());
        }
    }
    
    /**
     * Get the config instance (for external access if needed).
     */
    public static AutoBuyerConfig getConfig() {
        return config;
    }
    
    /**
     * Public method for modloader to call with user input values.
     * This allows the modloader to pass config values directly via API.
     * 
     * @param userInputValues Map of config variable names to their user-specified values
     *                       Example: {"{food_root_vegetables}", "30"}
     * 
     * NOTE: This method should be called by the modloader if it provides an API.
     * If not called, the mod will attempt to load user input from files/System properties.
     */
    public static void setUserConfigValues(java.util.Map<String, String> userInputValues) {
        if (userInputValues == null || userInputValues.isEmpty()) {
            ModLog.updateDiagnostic("setUserConfigValues() called with empty/null values - ignoring");
            return;
        }
        
        ModLog.updateDiagnostic("setUserConfigValues() called by modloader with " + userInputValues.size() + " values");
        
        // Get current config (from info.xml defaults)
        java.util.Map<String, String> currentValues = new java.util.HashMap<>();
        
        // Try to get current values from info.xml
        try {
            java.io.File modFolder = AutoBuyerConfig.getModDirectory();
            if (modFolder != null) {
                java.io.File infoXmlFile = new java.io.File(modFolder, "info.xml");
                if (infoXmlFile.exists()) {
                    java.util.Map<String, String> defaults = parseInfoXml(infoXmlFile);
                    if (defaults != null) {
                        currentValues.putAll(defaults);
                    }
                }
            }
        } catch (Exception e) {
            ModLog.updateDiagnostic("ERROR loading defaults for setUserConfigValues: " + e.getMessage());
        }
        
        // Merge: defaults first, then user input overrides
        currentValues.putAll(userInputValues);
        
        // Check for preset
        String presetName = currentValues.get("{config_preset}");
        if (presetName != null && !presetName.trim().isEmpty()) {
            presetName = presetName.trim();
            ModLog.log("AutoBuyerAspect: Preset specified via modloader API: " + presetName);
            java.util.Map<String, String> presetValues = loadPreset(presetName);
            if (presetValues != null && !presetValues.isEmpty()) {
                ModLog.log("AutoBuyerAspect: Loaded " + presetValues.size() + " values from preset via modloader API: " + presetName);
                currentValues.putAll(presetValues); // Preset overrides everything
            } else {
                ModLog.log("AutoBuyerAspect: WARNING: Preset file not found or empty via modloader API: " + presetName);
            }
        }
        
        // Apply the merged config
        config.loadFromConfig(currentValues);
        ModLog.updateDiagnostic("Applied merged config via modloader API: " + currentValues.size() + " total values");
    }
    
    /**
     * Hook: Called when logistics overwhelmed state changes.
     * Checks actual item count and updates logistics load tracking.
     * This allows gradual slowdown at 15 items and pause at 20 items.
     */
    @After("execution(* fi.bugbyte.spacehaven.gui.Indicators.ShipTileIconManager.setLogisticsSwamped(boolean)) && args(swamped) && target(tileIconManager)")
    public void onLogisticsSwamped(boolean swamped, fi.bugbyte.spacehaven.gui.Indicators.ShipTileIconManager tileIconManager) {
        try {
            // Try to get the Ship object to check actual item count
            // We'll use reflection to access the ship from TileIconManager
            fi.bugbyte.spacehaven.world.Ship ship = null;
            try {
                java.lang.reflect.Field shipField = tileIconManager.getClass().getDeclaredField("ship");
                shipField.setAccessible(true);
                ship = (fi.bugbyte.spacehaven.world.Ship) shipField.get(tileIconManager);
            } catch (Exception reflectionException) {
                // If reflection fails, fall back to boolean check
                // This is less precise but still works
                // Try to get world from GUI or use a different approach
                fi.bugbyte.spacehaven.world.World world = null;
                try {
                    // Try to get world from GUI instance
                    if (fi.bugbyte.spacehaven.gui.GUI.instance != null) {
                        // World is not directly accessible from GUI, so we'll use a conservative estimate
                        int itemCount = swamped ? 30 : 0; // Assume >=30 if swamped (trigger cancel), 0 if not
                        core.updateLogisticsItemCount(itemCount, null); // Pass null for world - cancellation won't work but tracking will
                    } else {
                        int itemCount = swamped ? 30 : 0;
                        core.updateLogisticsItemCount(itemCount, null);
                    }
                } catch (Exception e) {
                    int itemCount = swamped ? 30 : 0;
                    core.updateLogisticsItemCount(itemCount, null);
                }
                return;
            }
            
            if (ship != null && ship.isPlayerShip() && !ship.isDerelict()) {
                // Get actual item count from JobManager
                fi.bugbyte.spacehaven.ai.JobManager jobManager = ship.getJobManager();
                if (jobManager != null) {
                    int freeItems = jobManager.getFreeItemsSize();
                    int tryFerryAgain = jobManager.getTryFerryAgainItemsSize();
                    int totalItems = freeItems + tryFerryAgain;
                    
                    // Get world from ship to pass to updateLogisticsItemCount
                    fi.bugbyte.spacehaven.world.World world = ship.getWorld();
                    
                    // Update logistics item count in core
                    core.updateLogisticsItemCount(totalItems, world);
                }
            }
        } catch (Exception e) {
            ModLog.log("AutoBuyerAspect: Exception in onLogisticsSwamped: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Get the core instance (for external access if needed).
     */
    public static AutoBuyerCore getCore() {
        return core;
    }
}

