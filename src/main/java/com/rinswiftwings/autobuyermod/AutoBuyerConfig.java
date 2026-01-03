/**
 * ⚠️ IMPORTANT: PACKAGE NAMING CONVENTION
 * 
 * This package name (com.rinswiftwings.autobuyermod) is an EXAMPLE for this practice mod.
 * 
 * WHEN CREATING YOUR OWN MOD, YOU MUST change this to your own unique package name.
 * See the package declaration in AutoBuyerCore.java for detailed instructions.
 */
package com.rinswiftwings.autobuyermod;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for Auto-Buyer Mod.
 * Manages target stock levels and other settings.
 * 
 * DESIGN DECISIONS:
 * - This class is separate from Core to keep configuration logic isolated
 * - All configuration is loaded from info.xml (modloader standard)
 * - Default values are provided for all settings (works out of the box)
 * - Threshold-based system provides fine-grained control over buying behavior
 * - Deprecated fields are kept for backward compatibility (don't break existing configs)
 */
public class AutoBuyerConfig {
    
    /**
     * Target stock levels: elementaryId -> desired stock level.
     * WHY: Using a Map allows O(1) lookup by item ID. We only store items that have
     * target levels configured, so the map is sparse (only configured items, not all items).
     * This is more memory-efficient than an array indexed by item ID.
     */
    private final Map<Integer, Integer> targetStocks = new HashMap<>();
    
    /**
     * DEPRECATED: Minimum buy mode allowed (skip Markup items).
     * Replaced by threshold-based system - kept for backward compatibility.
     * 
     * WHY DEPRECATED: The boolean approach was too restrictive. Users wanted fine-grained
     * control over when to buy at different price levels. The threshold system allows
     * buying Markup items when stock is very low, but skipping them when stock is higher.
     * 
     * WHY KEPT: Existing config files may still reference this. We keep it to avoid
     * breaking old configurations, but log a warning when it's used.
     */
    @Deprecated
    private boolean allowMarkup = true;
    
    /**
     * Maximum markup thresholds for buying (percentage of target stock).
     * Only buy items at each markup level if stock is below this percentage of target stock.
     * 
     * WHY THRESHOLD SYSTEM: Provides fine-grained control over buying behavior:
     * - Discounted (100%): Always buy if under target (good price, no reason to wait)
     * - Normal (70%): Buy if stock < 70% of target (normal price, buy when getting low)
     * - Markup (40%): Only buy if stock < 40% of target (expensive, only when desperate)
     * - Premium (20%): Only buy if stock < 20% of target (very expensive, last resort)
     * 
     * This allows users to be more selective about expensive items while still buying
     * discounted items aggressively.
     * 
     * Example: discountBuyThreshold = 100 means always buy Discounted items if under target
     *          markupBuyThreshold = 40 means only buy Markup items if stock < 40% of target
     */
    private int discountBuyThreshold = 100;  // Always buy Discounted (100% of target)
    private int normalBuyThreshold = 70;     // Buy Normal if stock < 70% of target
    private int markupBuyThreshold = 40;     // Buy Markup if stock < 40% of target
    private int premiumBuyThreshold = 20;     // Buy Premium if stock < 20% of target
    
    /**
     * Optional: Maximum credits per trade.
     * WHY: Prevents single trades from consuming too many credits. Useful for:
     * - Early game: Keep trades small to preserve credits
     * - Budget control: Limit spending per trade
     * - Risk management: Don't put all credits in one trade
     * 
     * Set to Integer.MAX_VALUE (or 0 in config) for unlimited.
     */
    private int maxCreditsPerTrade = 2000;
    
    /**
     * Optional: Cooldown between attempts per ship (in game ticks, 0 = disabled).
     * WHY: Prevents too-frequent trade attempts with the same ship. This:
     * - Reduces API calls (better performance)
     * - Prevents spam if ship has no items we need
     * - Gives game time to process previous trades
     * 
     * 120 ticks ≈ 2 seconds (game runs at ~60 ticks/second)
     */
    private int cooldownTicks = 0;
    
    /**
     * Optional: Minimum refresh cooldown (in game ticks).
     * WHY: Offers from NPC ships don't change instantly. Refreshing too frequently
     * is wasteful. This cooldown prevents excessive offer queries.
     * 
     * Note: Offers are also cached and invalidated after trades (see AutoBuyerCore).
     */
    private int refreshCooldownTicks = 0;
    
    /**
     * Optional: Minimum credit balance required to initiate trades.
     * WHY: Maintains a credit reserve for emergencies. This prevents the mod from
     * spending all credits, leaving the player with no buffer for:
     * - Manual trades
     * - Emergency purchases
     * - Other mods or game features
     * 
     * Trades are skipped if credits are at or below this amount.
     */
    private int minCreditBalance = 10000;
    
    /**
     * Optional: Enable/disable logging (default: false for better performance).
     * WHY: Logging has performance overhead (file I/O). Disabled by default to:
     * - Improve game performance
     * - Reduce disk usage
     * - Keep logs clean (only enable when debugging)
     * 
     * When enabled, logs are written to [Game]/mods/AutoBuyerMod/logs/
     */
    private boolean enableLogging = false;
    
    public AutoBuyerConfig() {
        // Initialize with some common items as examples
        // User can modify these via config file or in-game UI later
        initializeDefaultTargets();
    }
    
    /**
     * Load configuration from info.xml config section.
     * This method should be called after mod initialization if config values are available.
     * 
     * DESIGN: This method follows a specific loading order:
     * 1. Load enable_logging FIRST (so subsequent logs work)
     * 2. Load item target stocks (all 50+ items)
     * 3. Load deprecated fields (with warnings)
     * 4. Load new threshold fields
     * 5. Load mod settings (credits, cooldowns, etc.)
     * 6. Log final configuration summary
     * 
     * WHY THIS ORDER: enable_logging must be loaded first so that all subsequent
     * logging operations work correctly. If logging is disabled, we still want to
     * load config, but we won't spam logs.
     * 
     * @param configValues Map of config variable names (from info.xml) to their string values
     *                     Example: {"{food_root_vegetables}", "50"}
     * 
     * NOTE: Integration with modloader config API needed.
     * The modloader should provide a way to access config values from info.xml.
     * Once we know the API, this method can be called during mod initialization.
     */
    public void loadFromConfig(Map<String, String> configValues) {
        ModLog.updateDiagnostic("loadFromConfig() called with " + 
            (configValues != null ? configValues.size() : "null") + " config values");
        
        /**
         * Load enable_logging FIRST before any other logging happens.
         * WHY: All subsequent code uses ModLog.log(), which checks enableLogging.
         * If we load this after other config, we might miss important log messages
         * or log when logging is disabled. Loading it first ensures logging state
         * is correct for the rest of the method.
         */
        if (configValues != null) {
            String enableLoggingStr = configValues.get("{enable_logging}");
            ModLog.updateDiagnostic("enable_logging value from config: " + enableLoggingStr);
            if (enableLoggingStr != null) {
                boolean wasEnabled = enableLogging;
                enableLogging = Boolean.parseBoolean(enableLoggingStr);
                ModLog.updateDiagnostic("Logging was " + (wasEnabled ? "enabled" : "disabled") + 
                    ", now " + (enableLogging ? "enabled" : "disabled"));
                // If logging was just enabled, write a test log entry to verify file creation
                if (!wasEnabled && enableLogging) {
                    String logPath = ModLog.getLogFilePath();
                    ModLog.updateDiagnostic("Logging just enabled! Log file path: " + 
                        (logPath != null ? logPath : "null"));
                    ModLog.log("AutoBuyerConfig: Logging enabled - test entry to verify log file creation");
                    ModLog.log("AutoBuyerConfig: Log file path: " + (logPath != null ? logPath : "null"));
                }
            } else {
                ModLog.updateDiagnostic("WARNING: {enable_logging} not found in config values!");
            }
        } else {
            ModLog.updateDiagnostic("WARNING: configValues is null!");
        }
        
        if (configValues == null || configValues.isEmpty()) {
            ModLog.log("AutoBuyerConfig: No config values provided, using defaults");
            return;
        }
        
        ModLog.log("AutoBuyerConfig: Loading configuration from info.xml");
        
        // Load target stock levels from config
        loadConfigValue(configValues, "{food_root_vegetables}", 15);
        loadConfigValue(configValues, "{food_processed}", 179);
        loadConfigValue(configValues, "{food_fruits}", 706);
        loadConfigValue(configValues, "{food_artificial_meat}", 707);
        loadConfigValue(configValues, "{food_space_food}", 712);
        loadConfigValue(configValues, "{food_monster_meat}", 984);
        loadConfigValue(configValues, "{food_nuts_seeds}", 2657);
        loadConfigValue(configValues, "{food_beer}", 3366);
        loadConfigValue(configValues, "{food_grains_hops}", 3378);
        
        loadConfigValue(configValues, "{material_carbon}", 170);
        loadConfigValue(configValues, "{material_raw_chemicals}", 171);
        loadConfigValue(configValues, "{material_plastics}", 175);
        loadConfigValue(configValues, "{material_chemicals}", 176);
        loadConfigValue(configValues, "{material_fabrics}", 177);
        loadConfigValue(configValues, "{material_fibers}", 1932);
        
        loadConfigValue(configValues, "{metal_basic}", 157);
        loadConfigValue(configValues, "{metal_noble}", 169);
        loadConfigValue(configValues, "{ore_exotic}", 3512);
        loadConfigValue(configValues, "{ore_basic}", 3513);
        
        loadConfigValue(configValues, "{energy_energium}", 158);
        loadConfigValue(configValues, "{energy_hyperium}", 172);
        loadConfigValue(configValues, "{energy_rod}", 174);
        loadConfigValue(configValues, "{fuel_hyperfuel}", 178);
        loadConfigValue(configValues, "{energy_cell}", 1926);
        
        loadConfigValue(configValues, "{component_electronic}", 173);
        loadConfigValue(configValues, "{component_optronics}", 1924);
        loadConfigValue(configValues, "{component_quantronics}", 1925);
        
        loadConfigValue(configValues, "{medical_supplies}", 2053);
        loadConfigValue(configValues, "{medical_iv_fluid}", 2058);
        loadConfigValue(configValues, "{medical_painkillers}", 4005);
        loadConfigValue(configValues, "{medical_combat_stimulant}", 4006);
        loadConfigValue(configValues, "{medical_bandage}", 4007);
        loadConfigValue(configValues, "{medical_nano_dressing}", 4030);
        
        loadConfigValue(configValues, "{water}", 16);
        loadConfigValue(configValues, "{ice_block}", 40);
        
        // Botany
        loadConfigValue(configValues, "{botany_biomass}", 71);
        loadConfigValue(configValues, "{botany_fertilizer}", 2475);
        
        // Scrap
        loadConfigValue(configValues, "{scrap_rubble}", 127);
        loadConfigValue(configValues, "{scrap_infra}", 1873);
        loadConfigValue(configValues, "{scrap_hull}", 1886);
        loadConfigValue(configValues, "{scrap_soft}", 1874);
        loadConfigValue(configValues, "{scrap_tech}", 1946);
        loadConfigValue(configValues, "{scrap_energy}", 1947);
        
        // Blocks
        loadConfigValue(configValues, "{block_infra}", 162);
        loadConfigValue(configValues, "{block_tech}", 930);
        loadConfigValue(configValues, "{block_hull}", 1759);
        loadConfigValue(configValues, "{block_energy}", 1919);
        loadConfigValue(configValues, "{block_super}", 1920);
        loadConfigValue(configValues, "{block_soft}", 1921);
        loadConfigValue(configValues, "{block_steel_plates}", 1922);
        
        /**
         * DEPRECATED: Load allow_markup for backward compatibility (ignored in favor of thresholds).
         * WHY: We still load this value to avoid breaking old config files, but we:
         * 1. Log a warning so users know to update their config
         * 2. Don't use the value (threshold system replaces it)
         * 3. Keep the field for potential future use or migration
         * 
         * This is a common pattern for deprecation: load but don't use, warn user.
         */
        String allowMarkupStr = configValues.get("{allow_markup}");
        if (allowMarkupStr != null) {
            allowMarkup = Boolean.parseBoolean(allowMarkupStr);
            ModLog.log("AutoBuyerConfig: WARNING - {allow_markup} is deprecated and ignored. Use buy threshold variables instead.");
        }
        
        /**
         * Load buy markup thresholds (percentage of target stock).
         * WHY THIS PATTERN: Each threshold is loaded with the same pattern:
         * 1. Get string value from config map
         * 2. Parse to integer (with try-catch for invalid format)
         * 3. Validate range (0-100 for percentages)
         * 4. Set value if valid, log error if invalid
         * 
         * This pattern ensures:
         * - Invalid values don't crash the mod (graceful degradation)
         * - Users get feedback about invalid config (logged)
         * - Default values are used if config is invalid (safe fallback)
         * - Each threshold is independent (one bad value doesn't break others)
         */
        String discountBuyStr = configValues.get("{discount_buy_threshold}");
        if (discountBuyStr != null) {
            try {
                int threshold = Integer.parseInt(discountBuyStr);
                if (threshold >= 0 && threshold <= 100) {
                    discountBuyThreshold = threshold;
                    ModLog.log("AutoBuyerConfig: DiscountBuyThreshold from config: " + discountBuyThreshold + "%");
                } else {
                    ModLog.log("AutoBuyerConfig: Invalid discount_buy_threshold value (must be 0-100): " + discountBuyStr);
                }
            } catch (NumberFormatException e) {
                ModLog.log("AutoBuyerConfig: Invalid discount_buy_threshold value: " + discountBuyStr);
            }
        }
        
        String normalBuyStr = configValues.get("{normal_buy_threshold}");
        if (normalBuyStr != null) {
            try {
                int threshold = Integer.parseInt(normalBuyStr);
                if (threshold >= 0 && threshold <= 100) {
                    normalBuyThreshold = threshold;
                    ModLog.log("AutoBuyerConfig: NormalBuyThreshold from config: " + normalBuyThreshold + "%");
                } else {
                    ModLog.log("AutoBuyerConfig: Invalid normal_buy_threshold value (must be 0-100): " + normalBuyStr);
                }
            } catch (NumberFormatException e) {
                ModLog.log("AutoBuyerConfig: Invalid normal_buy_threshold value: " + normalBuyStr);
            }
        }
        
        String markupBuyStr = configValues.get("{markup_buy_threshold}");
        if (markupBuyStr != null) {
            try {
                int threshold = Integer.parseInt(markupBuyStr);
                if (threshold >= 0 && threshold <= 100) {
                    markupBuyThreshold = threshold;
                    ModLog.log("AutoBuyerConfig: MarkupBuyThreshold from config: " + markupBuyThreshold + "%");
                } else {
                    ModLog.log("AutoBuyerConfig: Invalid markup_buy_threshold value (must be 0-100): " + markupBuyStr);
                }
            } catch (NumberFormatException e) {
                ModLog.log("AutoBuyerConfig: Invalid markup_buy_threshold value: " + markupBuyStr);
            }
        }
        
        String premiumBuyStr = configValues.get("{premium_buy_threshold}");
        if (premiumBuyStr != null) {
            try {
                int threshold = Integer.parseInt(premiumBuyStr);
                if (threshold >= 0 && threshold <= 100) {
                    premiumBuyThreshold = threshold;
                    ModLog.log("AutoBuyerConfig: PremiumBuyThreshold from config: " + premiumBuyThreshold + "%");
                } else {
                    ModLog.log("AutoBuyerConfig: Invalid premium_buy_threshold value (must be 0-100): " + premiumBuyStr);
                }
            } catch (NumberFormatException e) {
                ModLog.log("AutoBuyerConfig: Invalid premium_buy_threshold value: " + premiumBuyStr);
            }
        }
        
        String maxCreditsStr = configValues.get("{max_credits_per_trade}");
        if (maxCreditsStr != null) {
            try {
                int maxCredits = Integer.parseInt(maxCreditsStr);
                if (maxCredits > 0) {
                    maxCreditsPerTrade = maxCredits;
                } else {
                    maxCreditsPerTrade = Integer.MAX_VALUE;
                }
                ModLog.log("AutoBuyerConfig: MaxCreditsPerTrade from config: " + maxCreditsPerTrade);
            } catch (NumberFormatException e) {
                ModLog.log("AutoBuyerConfig: Invalid max_credits_per_trade value: " + maxCreditsStr);
            }
        }
        
        String cooldownStr = configValues.get("{cooldown_ticks}");
        if (cooldownStr != null) {
            try {
                cooldownTicks = Integer.parseInt(cooldownStr);
                ModLog.log("AutoBuyerConfig: CooldownTicks from config: " + cooldownTicks);
            } catch (NumberFormatException e) {
                ModLog.log("AutoBuyerConfig: Invalid cooldown_ticks value: " + cooldownStr);
            }
        }
        
        String minCreditStr = configValues.get("{min_credit_balance}");
        if (minCreditStr != null) {
            try {
                int minCredit = Integer.parseInt(minCreditStr);
                if (minCredit >= 0) {
                    minCreditBalance = minCredit;
                    ModLog.log("AutoBuyerConfig: MinCreditBalance from config: " + minCreditBalance);
                } else {
                    ModLog.log("AutoBuyerConfig: Invalid min_credit_balance value (must be >= 0): " + minCreditStr);
                }
            } catch (NumberFormatException e) {
                ModLog.log("AutoBuyerConfig: Invalid min_credit_balance value: " + minCreditStr);
            }
        }
        
        // enable_logging was already loaded at the start of this method
        
        ModLog.log("AutoBuyerConfig: Configuration loaded from info.xml");
        
        // Log final configuration summary
        logFinalConfiguration();
    }
    
    /**
     * Log the final configuration settings to the log file.
     * This provides a clear summary of all active config values at startup.
     * Public so it can be called from AutoBuyerAspect after delayed preset loading.
     */
    public void logFinalConfiguration() {
        ModLog.log("========================================");
        ModLog.log("FINAL CONFIGURATION SUMMARY");
        ModLog.log("========================================");
        ModLog.log("Mod Settings:");
        ModLog.log("  Buy Markup Thresholds: Discount=" + discountBuyThreshold + "%, Normal=" + normalBuyThreshold + 
                  "%, Markup=" + markupBuyThreshold + "%, Premium=" + premiumBuyThreshold + "%");
        ModLog.log("  Max Credits Per Trade: " + maxCreditsPerTrade);
        ModLog.log("  Min Credit Balance: " + minCreditBalance);
        ModLog.log("  Cooldown Ticks: " + cooldownTicks);
        ModLog.log("  Logging Enabled: " + enableLogging);
        ModLog.log("");
        ModLog.log("Target Stock Levels:");
        
        // Map of item ID to config variable name for readable output
        java.util.Map<Integer, String> itemNames = new java.util.HashMap<>();
        itemNames.put(15, "{food_root_vegetables}");
        itemNames.put(179, "{food_processed}");
        itemNames.put(706, "{food_fruits}");
        itemNames.put(707, "{food_artificial_meat}");
        itemNames.put(712, "{food_space_food}");
        itemNames.put(984, "{food_monster_meat}");
        itemNames.put(2657, "{food_nuts_seeds}");
        itemNames.put(3366, "{food_beer}");
        itemNames.put(3378, "{food_grains_hops}");
        itemNames.put(170, "{material_carbon}");
        itemNames.put(171, "{material_raw_chemicals}");
        itemNames.put(175, "{material_plastics}");
        itemNames.put(176, "{material_chemicals}");
        itemNames.put(177, "{material_fabrics}");
        itemNames.put(1932, "{material_fibers}");
        itemNames.put(157, "{metal_basic}");
        itemNames.put(169, "{metal_noble}");
        itemNames.put(3512, "{ore_exotic}");
        itemNames.put(3513, "{ore_basic}");
        itemNames.put(158, "{energy_energium}");
        itemNames.put(172, "{energy_hyperium}");
        itemNames.put(174, "{energy_rod}");
        itemNames.put(178, "{fuel_hyperfuel}");
        itemNames.put(1926, "{energy_cell}");
        itemNames.put(173, "{component_electronic}");
        itemNames.put(1924, "{component_optronics}");
        itemNames.put(1925, "{component_quantronics}");
        itemNames.put(2053, "{medical_supplies}");
        itemNames.put(2058, "{medical_iv_fluid}");
        itemNames.put(4005, "{medical_painkillers}");
        itemNames.put(4006, "{medical_combat_stimulant}");
        itemNames.put(4007, "{medical_bandage}");
        itemNames.put(4030, "{medical_nano_dressing}");
        itemNames.put(16, "{water}");
        itemNames.put(40, "{ice_block}");
        itemNames.put(71, "{botany_biomass}");
        itemNames.put(2475, "{botany_fertilizer}");
        itemNames.put(127, "{scrap_rubble}");
        itemNames.put(1873, "{scrap_infra}");
        itemNames.put(1886, "{scrap_hull}");
        itemNames.put(1874, "{scrap_soft}");
        itemNames.put(1946, "{scrap_tech}");
        itemNames.put(1947, "{scrap_energy}");
        itemNames.put(162, "{block_infra}");
        itemNames.put(930, "{block_tech}");
        itemNames.put(1759, "{block_hull}");
        itemNames.put(1919, "{block_energy}");
        itemNames.put(1920, "{block_super}");
        itemNames.put(1921, "{block_soft}");
        itemNames.put(1922, "{block_steel_plates}");
        
        // Sort by category for better readability
        java.util.List<Integer> sortedIds = new java.util.ArrayList<>(targetStocks.keySet());
        java.util.Collections.sort(sortedIds);
        
        String currentCategory = "";
        for (Integer itemId : sortedIds) {
            Integer target = targetStocks.get(itemId);
            String itemName = itemNames.getOrDefault(itemId, "{item_" + itemId + "}");
            
            // Group by category
            String category = "";
            if (itemName.contains("food_")) category = "Food";
            else if (itemName.contains("material_")) category = "Materials";
            else if (itemName.contains("metal_") || itemName.contains("ore_")) category = "Metals & Ores";
            else if (itemName.contains("energy_") || itemName.contains("fuel_")) category = "Energy";
            else if (itemName.contains("component_")) category = "Components";
            else if (itemName.contains("medical_")) category = "Medical";
            else if (itemName.contains("water") || itemName.contains("ice")) category = "Resources";
            else if (itemName.contains("botany_")) category = "Botany";
            else if (itemName.contains("scrap_")) category = "Scrap";
            else if (itemName.contains("block_")) category = "Blocks";
            else category = "Other";
            
            if (!category.equals(currentCategory)) {
                if (!currentCategory.isEmpty()) {
                    ModLog.log("");
                }
                ModLog.log("  [" + category + "]");
                currentCategory = category;
            }
            
            ModLog.log("    " + itemName + " (ID: " + itemId + "): " + target);
        }
        
        ModLog.log("========================================");
        ModLog.log("Total items configured: " + targetStocks.size());
        ModLog.log("========================================");
        ModLog.log("");
    }
    
    /**
     * Helper method to load a single config value for a target stock level.
     */
    private void loadConfigValue(Map<String, String> configValues, String configKey, int elementaryId) {
        String valueStr = configValues.get(configKey);
        if (valueStr != null) {
            try {
                int value = Integer.parseInt(valueStr);
                if (value >= 0) {
                    targetStocks.put(elementaryId, value);
                    ModLog.log("AutoBuyerConfig: Loaded " + configKey + " = " + value + " for item " + elementaryId);
                } else {
                    targetStocks.remove(elementaryId);
                    ModLog.log("AutoBuyerConfig: Removed target for item " + elementaryId + " (negative value)");
                }
            } catch (NumberFormatException e) {
                ModLog.log("AutoBuyerConfig: Invalid value for " + configKey + ": " + valueStr);
            }
        }
    }
    
    /**
     * Initialize default target stock levels.
     * Based on common tradeable items from community item ID list.
     * Users can modify these via setTargetStock() or clear and reconfigure.
     */
    private void initializeDefaultTargets() {
        // Food items - essential for station operations (halved for early game)
        targetStocks.put(15, 25);   // Root Vegetables
        targetStocks.put(179, 20);  // Processed Food
        targetStocks.put(706, 15);  // Fruits
        targetStocks.put(707, 15);  // Artificial Meat
        targetStocks.put(712, 10);  // Space Food
        targetStocks.put(984, 10);  // Monster Meat
        targetStocks.put(2657, 12); // Nuts and seeds
        targetStocks.put(3366, 10); // Beer
        targetStocks.put(3378, 10); // Grains and Hops
        
        // Basic Materials - commonly needed (halved for early game)
        targetStocks.put(170, 50); // Carbon
        targetStocks.put(171, 40);  // Raw Chemicals
        targetStocks.put(175, 40);  // Plastics
        targetStocks.put(176, 30);  // Chemicals
        targetStocks.put(177, 30);  // Fabrics
        targetStocks.put(1932, 25); // Fibers
        
        // Metals/Ore - construction materials (halved for early game)
        targetStocks.put(157, 75); // Basic Metals
        targetStocks.put(169, 25);  // Noble Metals
        targetStocks.put(3512, 15); // Exotic Ore
        targetStocks.put(3513, 50); // Basic Ore
        
        // Energy/Fuel - power generation (halved for early game)
        targetStocks.put(158, 50); // Energium
        targetStocks.put(172, 40);  // Hyperium
        targetStocks.put(174, 25);  // Energy Rod
        targetStocks.put(178, 30);  // Hyperfuel
        
        // Components - manufacturing (halved for early game)
        targetStocks.put(173, 20);  // Electronic Component
        targetStocks.put(1924, 15); // Optronics Component
        targetStocks.put(1925, 10); // Quantronics Component
        
        // Blocks - construction (halved for early game)
        targetStocks.put(162, 25);  // Infra Block
        targetStocks.put(930, 20);  // Tech Block
        targetStocks.put(1759, 15); // Hull Block
        targetStocks.put(1919, 15); // Energy Block
        targetStocks.put(1920, 10); // Super Block
        targetStocks.put(1921, 15); // Soft Block
        targetStocks.put(1922, 20); // Steel Plates
        
        // Medical - crew health (halved for early game)
        targetStocks.put(2053, 15); // Medical Supplies
        targetStocks.put(2058, 10); // IV Fluid
        targetStocks.put(4005, 7); // Painkillers
        targetStocks.put(4006, 7); // Combat Stimulant
        targetStocks.put(4007, 7); // Bandage
        targetStocks.put(4030, 5); // Nano Wound Dressing
        
        // Ice/Water - life support (halved for early game)
        targetStocks.put(16, 50);  // Water
        targetStocks.put(40, 25);   // Ice Block
        
        // Botany (halved for early game)
        targetStocks.put(71, 25);   // Biomass
        targetStocks.put(2475, 10); // Fertilizer
        
        // Scrap (for recycling/construction) (halved for early game)
        targetStocks.put(127, 50); // Rubble
        targetStocks.put(1873, 40); // Infra Scrap
        targetStocks.put(1886, 40); // Hull Scrap
        targetStocks.put(1874, 25); // Soft Scrap
        targetStocks.put(1946, 25); // Tech Scrap
        targetStocks.put(1947, 25); // Energy Scrap
        
        // Energy Cell (for weapons/equipment) (halved for early game)
        targetStocks.put(1926, 10); // Energy Cell
        
        // Note: Logging removed from here - this is called during construction
        // before ModLog.setConfig() is called, so logging flag isn't available yet
    }
    
    /**
     * Set target stock level for an item.
     */
    public void setTargetStock(int elementaryId, int targetLevel) {
        if (targetLevel < 0) {
            targetStocks.remove(elementaryId);
            ModLog.log("AutoBuyerConfig: Removed target for item " + elementaryId);
        } else {
            targetStocks.put(elementaryId, targetLevel);
            ModLog.log("AutoBuyerConfig: Set target for item " + elementaryId + " to " + targetLevel);
        }
    }
    
    /**
     * Get target stock level for an item.
     * Returns 0 if not configured.
     */
    public int getTargetStock(int elementaryId) {
        return targetStocks.getOrDefault(elementaryId, 0);
    }
    
    /**
     * Check if an item has a target stock configured.
     */
    public boolean hasTargetStock(int elementaryId) {
        return targetStocks.containsKey(elementaryId);
    }
    
    /**
     * Get all configured target stocks.
     */
    public Map<Integer, Integer> getAllTargetStocks() {
        return new HashMap<>(targetStocks);
    }
    
    /**
     * Clear all target stocks.
     */
    public void clearAllTargets() {
        targetStocks.clear();
        ModLog.log("AutoBuyerConfig: Cleared all targets");
    }
    
    /**
     * @deprecated This method is deprecated. Use getBuyThreshold() instead.
     */
    @Deprecated
    public boolean isAllowMarkup() {
        return allowMarkup;
    }
    
    /**
     * @deprecated This method is deprecated. Use buy threshold variables instead.
     */
    @Deprecated
    public void setAllowMarkup(boolean allowMarkup) {
        this.allowMarkup = allowMarkup;
        ModLog.log("AutoBuyerConfig: WARNING - setAllowMarkup() is deprecated and ignored. Use buy threshold variables instead.");
    }
    
    /**
     * Get the buy threshold for a specific trade mode.
     * @param mode The trade mode
     * @return The threshold percentage (0-100)
     */
    public int getBuyThreshold(fi.bugbyte.spacehaven.ai.TradingHelper.TradeItemMode mode) {
        if (mode == null) {
            return normalBuyThreshold; // Default to Normal
        }
        switch (mode) {
            case Discounted:
                return discountBuyThreshold;
            case Neutral:
                return normalBuyThreshold;
            case Markup:
                return markupBuyThreshold;
            case Premium:
                return premiumBuyThreshold;
            default:
                return normalBuyThreshold;
        }
    }
    
    public int getDiscountBuyThreshold() {
        return discountBuyThreshold;
    }
    
    public int getNormalBuyThreshold() {
        return normalBuyThreshold;
    }
    
    public int getMarkupBuyThreshold() {
        return markupBuyThreshold;
    }
    
    public int getPremiumBuyThreshold() {
        return premiumBuyThreshold;
    }
    
    public int getMaxCreditsPerTrade() {
        return maxCreditsPerTrade;
    }
    
    public void setMaxCreditsPerTrade(int maxCreditsPerTrade) {
        this.maxCreditsPerTrade = maxCreditsPerTrade;
        ModLog.log("AutoBuyerConfig: MaxCreditsPerTrade set to " + maxCreditsPerTrade);
    }
    
    public int getCooldownTicks() {
        return cooldownTicks;
    }
    
    public void setCooldownTicks(int cooldownTicks) {
        this.cooldownTicks = cooldownTicks;
    }
    
    public int getRefreshCooldownTicks() {
        return refreshCooldownTicks;
    }
    
    /**
     * Get minimum credit balance required to initiate trades.
     * If player credits are at or below this amount, no trades will be initiated.
     */
    public int getMinCreditBalance() {
        return minCreditBalance;
    }
    
    /**
     * Check if logging is enabled.
     */
    public boolean isLoggingEnabled() {
        return enableLogging;
    }
    
    public void setRefreshCooldownTicks(int refreshCooldownTicks) {
        this.refreshCooldownTicks = refreshCooldownTicks;
    }
    
    /**
     * Get the mod directory (where the JAR file and info.xml are located).
     * This is a helper method that can be used by other classes.
     * 
     * @return File object pointing to the mod directory, or null if not determined
     */
    public static java.io.File getModDirectory() {
        try {
            // Get the JAR file location
            java.net.URL jarUrl = AutoBuyerConfig.class.getProtectionDomain()
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
            java.io.File jarFile = new java.io.File(jarUrl.toURI());
            
            // The JAR's parent directory IS the mod folder
            java.io.File modFolder = jarFile.getParentFile();
            
            if (modFolder != null) {
                return modFolder;
            }
        } catch (Exception e) {
            // JAR location detection failed
        }
        
        return null;
    }
    
    /**
     * Get the presets directory (where preset JSON files are stored).
     * 
     * @return File object pointing to the presets directory
     */
    public static java.io.File getPresetsDirectory() {
        java.io.File modFolder = getModDirectory();
        if (modFolder != null) {
            // Create presets folder in mod directory
            java.io.File presetsFolder = new java.io.File(modFolder, "presets");
            presetsFolder.mkdirs();
            return presetsFolder;
        }
        
        // If we can't determine mod folder, return null (shouldn't happen in normal use)
        return null;
    }
}

