# Auto-Buyer Mod - Practice Version

**Version:** 0.1.7  
**Status:** **Stable** - Ready for regular use  
**Author:** Rin  
**Game Version:** Beta 2 (0.21.0) - Experimental Branch  
**Minimum Modloader Version:** 0.11.0

---

## Overview

**This is a Practice/Training Version** - This mod has been prepared as an educational resource for learning how to create script mods for Space Haven. The code includes extensive educational comments explaining "why" design decisions were made, not just "what" the code does.

### For Modders and Learners

This version is designed to help you:
- **Learn AspectJ** - See examples of AspectJ hooks in action
- **Understand Game Systems** - Learn how to interact with Space Haven's trading system
- **Study Best Practices** - See patterns for error handling, state management, and more
- **Reference Implementation** - Use as a template for your own mods

### Documentation

Comprehensive documentation is available in the `HelpFiles/` directory:
- **Getting Started** - Setup your development environment
- **Project Structure** - Understand mod directory layout
- **AspectJ Basics** - Learn how to hook into game events
- **Game Hooks** - See all hooks used in this mod
- **Core Concepts** - Understand trading system and game objects
- **Code Walkthrough** - Step-by-step explanation of the codebase
- **Common Patterns** - Best practices and coding patterns
- **Troubleshooting** - Common issues and solutions

See `HelpFiles/00_Index.md` for a complete guide to all documentation.

### Original Mod Description

The Auto-Buyer Mod is designed for Space Haven's new station mode gameplay, where station services automate outgoing resources. This mod automatically purchases items from NPC ships based on configurable target stock levels, using your station's credits to maintain optimal inventory levels.

The mod uses **event-driven logic** that respects all game physics and trade limits, calculating needs based on current stock and items already in transit. It operates entirely in-memory with no persistent state, making it completely safe to remove at any time without affecting your save files.

---

## Features

### Core Functionality
- **Automatic Trade Creation** - Automatically creates trades with NPC ships when items are needed
- **Multi-Ship Prioritization** - Intelligently selects the best ship for each trade (Discounted > Neutral > Markup > Premium)
- **Per-Ship Trade Limits** - Respects game limits: 8 trades per ship per visit, 4 concurrent trades max
- **Smart Need Calculation** - Accounts for current stock AND items already in transit to avoid over-buying
- **Trade Mode Prioritization** - Prefers Discounted items, then Neutral, then Markup (only if need is low)
- **Credit Management** - Configurable credit limits and minimum balance guardrails

### Advanced Features
- **Automatic Retry System** - Retries new ships up to 3 times before marking as unavailable
- **10-Second Initialization Delay** - Gives new ships time to fully load before attempting trades
- **Shuttle Docking Trigger** - Rechecks trade opportunities when NPC shuttles dock
- **Smart Offer Caching** - Caches NPC offers to reduce API calls and improve performance
- **Logistics-Aware Trading** - Automatically slows/pauses trading when logistics are overwhelmed
- **Config Presets** - Save and load multiple configuration profiles for different playstyles

### Supported Items
The mod supports **50+ item types** across multiple categories:
- **Food:** Root Vegetables, Processed Food, Fruits, Artificial Meat, Space Food, Monster Meat, Nuts/Seeds, Beer, Grains/Hops
- **Materials:** Carbon, Raw Chemicals, Plastics, Chemicals, Fabrics, Fibers
- **Metals & Ores:** Basic Metals, Noble Metals, Exotic Ore, Basic Ore
- **Energy:** Energium, Hyperium, Energy Rod, Hyperfuel, Energy Cell
- **Components:** Electronic, Optronics, Quantronics
- **Medical:** Medical Supplies, IV Fluid, Painkillers, Combat Stimulant, Bandage, Nano Wound Dressing
- **Resources:** Water, Ice Block, Biomass, Fertilizer
- **Scrap:** Rubble, Infra Scrap, Hull Scrap, Soft Scrap, Tech Scrap, Energy Scrap
- **Blocks:** Infra Block, Tech Block, Hull Block, Energy Block, Super Block, Soft Block, Steel Plates

---

## Installation

### Prerequisites
- Space Haven Beta 2 (0.21.0) - Experimental Branch
- Modloader version 0.11.0 or higher
- [Space Haven Modloader](https://github.com/CyanBlob/spacehaven-modloader)

### Installation Steps

1. **Download the Mod**
   - Download `AutoBuyerMod_0.1.7.jar` and `info.xml`

2. **Install to Game Directory**
   - Navigate to your Space Haven game directory
   - On Steam: Right-click game → Manage → Browse Local Files
   - Create a `mods` folder if it doesn't exist
   - Create `mods/AutoBuyerMod/` folder
   - Copy the following files into `mods/AutoBuyerMod/`:
     - `AutoBuyerMod_0.1.7.jar`
     - `info.xml`
     - `presets/` folder (if included)

3. **Configure Modloader**
   - Ensure the modloader is properly installed
   - The mod should appear in the modloader UI
   - Enable the mod in the modloader interface

4. **Launch Game**
   - Start Space Haven
   - The mod will load automatically
   - Check the modloader UI to confirm it's active

### File Structure
```
[Game Directory]/mods/AutoBuyerMod/
├── AutoBuyerMod_0.1.7.jar    (mod JAR file)
├── info.xml                   (mod configuration)
├── presets/                   (preset configuration files)
│   ├── EarlyGame.json
│   ├── MidGame.json
│   ├── LateGame.json
│   └── README.md
└── logs/                      (runtime log files - created automatically)
```

### For Developers

If you're using this as a learning resource, the source code is in `src/main/java/`:
- **AutoBuyerAspect.java** - AspectJ hooks into game events
- **AutoBuyerCore.java** - Core trading logic
- **AutoBuyerConfig.java** - Configuration management
- **ModLog.java** - Logging utility

All code includes extensive educational comments explaining design decisions and implementation details.

---

## Configuration

### Quick Start
1. Open `info.xml` in a text editor
2. Adjust target stock levels for items you want to auto-buy
3. Configure mod settings (credit limits, cooldowns, etc.)
4. Save the file
5. Restart the game

### Configuration Variables

#### Mod Settings

| Variable | Description | Default | Notes |
|----------|-------------|---------|-------|
| `{allow_markup}` | **DEPRECATED** - Allow buying items with Markup trade mode | `true` | Use `{markup_buy_threshold}` instead. Kept for backward compatibility only. |
| `{max_credits_per_trade}` | Maximum credits per trade | `2000` | Set to `0` for unlimited |
| `{min_credit_balance}` | Minimum credit balance required | `10000` | No trades if credits ≤ this amount |
| `{cooldown_ticks}` | Cooldown between trade attempts (per ship) | `120` | 120 ticks ≈ 2 seconds. Set to `0` to disable |
| `{enable_logging}` | Enable logging to file | `false` | Logs saved to `logs/` directory |
| `{config_preset}` | Preset name to load (optional) | `""` | Leave empty to use `info.xml` values |

#### Item Target Stocks

All item target stocks follow the pattern: `{category_item_name}`

**Food Items:**
- `{food_root_vegetables}` - Root Vegetables (default: 25)
- `{food_processed}` - Processed Food (default: 20)
- `{food_fruits}` - Fruits (default: 15)
- `{food_artificial_meat}` - Artificial Meat (default: 15)
- `{food_space_food}` - Space Food (default: 10)
- `{food_monster_meat}` - Monster Meat (default: 10)
- `{food_nuts_seeds}` - Nuts and Seeds (default: 12)
- `{food_beer}` - Beer (default: 10)
- `{food_grains_hops}` - Grains and Hops (default: 10)

**Materials:**
- `{material_carbon}` - Carbon (default: 50)
- `{material_raw_chemicals}` - Raw Chemicals (default: 40)
- `{material_plastics}` - Plastics (default: 40)
- `{material_chemicals}` - Chemicals (default: 30)
- `{material_fabrics}` - Fabrics (default: 30)
- `{material_fibers}` - Fibers (default: 25)

**Metals & Ores:**
- `{metal_basic}` - Basic Metals (default: 75)
- `{metal_noble}` - Noble Metals (default: 25)
- `{ore_exotic}` - Exotic Ore (default: 15)
- `{ore_basic}` - Basic Ore (default: 50)

**Energy:**
- `{energy_energium}` - Energium (default: 50)
- `{energy_hyperium}` - Hyperium (default: 40)
- `{energy_rod}` - Energy Rod (default: 25)
- `{fuel_hyperfuel}` - Hyperfuel (default: 30)
- `{energy_cell}` - Energy Cell (default: 10)

**Components:**
- `{component_electronic}` - Electronic Component (default: 20)
- `{component_optronics}` - Optronics Component (default: 15)
- `{component_quantronics}` - Quantronics Component (default: 10)

**Medical:**
- `{medical_supplies}` - Medical Supplies (default: 15)
- `{medical_iv_fluid}` - IV Fluid (default: 10)
- `{medical_painkillers}` - Painkillers (default: 7)
- `{medical_combat_stimulant}` - Combat Stimulant (default: 7)
- `{medical_bandage}` - Bandage (default: 7)
- `{medical_nano_dressing}` - Nano Wound Dressing (default: 5)

**Resources:**
- `{water}` - Water (default: 50)
- `{ice_block}` - Ice Block (default: 25)
- `{botany_biomass}` - Biomass (default: 25)
- `{botany_fertilizer}` - Fertilizer (default: 10)

**Scrap:**
- `{scrap_rubble}` - Rubble (default: 50)
- `{scrap_infra}` - Infra Scrap (default: 40)
- `{scrap_hull}` - Hull Scrap (default: 40)
- `{scrap_soft}` - Soft Scrap (default: 25)
- `{scrap_tech}` - Tech Scrap (default: 25)
- `{scrap_energy}` - Energy Scrap (default: 25)

**Blocks:**
- `{block_infra}` - Infra Block (default: 25)
- `{block_tech}` - Tech Block (default: 20)
- `{block_hull}` - Hull Block (default: 15)
- `{block_energy}` - Energy Block (default: 15)
- `{block_super}` - Super Block (default: 10)
- `{block_soft}` - Soft Block (default: 15)
- `{block_steel_plates}` - Steel Plates (default: 20)

### Configuration Priority

The mod uses a hybrid configuration system with the following priority order:

1. **Preset values** (if `{config_preset}` is set) - Highest priority
2. **User input from modloader UI** - Overrides `info.xml` defaults
3. **`info.xml` defaults** - Base configuration

This ensures that:
- User input from the modloader UI is always respected
- Presets can override everything for quick configuration switching
- `info.xml` provides sensible defaults if nothing else is configured

---

## Presets

Presets allow you to save and load different configurations for different playstyles or game stages.

### Using Presets

1. **Copy an Existing Preset**
   - Navigate to `mods/AutoBuyerMod/presets/`
   - Copy `EarlyGame.json` and rename it (e.g., `MyStation.json`)

2. **Edit the Preset**
   - Open the preset file in a text editor
   - Edit values in the `"config"` section
   - Save the file

3. **Activate the Preset**
   - Edit `info.xml`
   - Set `{config_preset}` to the preset name (without `.json`)
   - Example: `{config_preset}` = `MyStation`
   - Restart the game

### Preset File Format

```json
{
  "name": "Preset Name",
  "description": "Description of the preset",
  "config": {
    "{food_root_vegetables}": "30",
    "{metal_basic}": "100",
    "{max_credits_per_trade}": "5000",
    "{allow_markup}": "true"
  }
}
```

### Available Presets

- **EarlyGame.json** - Conservative targets for new stations (based on mod defaults)

### Preset Tips

- You can create multiple presets for different scenarios (early game, late game, scrap focus, etc.)
- Presets can be shared with other players - just copy the `.json` file
- Only the `"config"` section values are used - `"name"` and `"description"` are for your reference
- Missing variables in a preset will fall back to `info.xml` defaults
- To stop using a preset, set `{config_preset}` to an empty string: `""`

See `presets/README.md` for detailed preset instructions.

---

## How It Works

### Event-Driven Architecture

The mod uses **AspectJ bytecode weaving** to intercept game events. It does not poll or run continuous loops - it only acts when specific game events occur:

- **Trade Completion** - When a trade finishes, the mod attempts the next purchase
- **Trade Cancellation** - When a trade is cancelled, the mod retries
- **Ship Arrival** - When an NPC ship enters the sector, the mod initializes tracking
- **Ship Departure** - When a ship leaves, the mod cleans up its state
- **Shuttle Docking** - When an NPC shuttle docks, the mod rechecks trade opportunities

### Trade Logic Flow

1. **Eligibility Check**
   - Verify minimum credit balance
   - Check ship cooldown period
   - Verify trade limits (8 per ship, 4 concurrent)

2. **Ship Evaluation**
   - Score all eligible ships based on:
     - Has Discounted items (highest priority)
     - Total need value
     - Active trade count (fewer is better)

3. **Item Selection**
   - For each item in the best ship's offers:
     - Calculate need: `targetStock - (currentStock + itemsInTransit)`
     - Check if need > 0
     - Check markup threshold: Only buy if stock percentage < threshold for that trade mode
     - Prioritize by trade mode: Discounted > Neutral > Markup > Premium
     - Premium items can be purchased if stock is below Premium threshold (default: 20% of target)

4. **Trade Creation**
   - Calculate quantity (respects 10-unit cap and credit limits)
   - Create trade agreement via game API
   - Trade appears in game UI and notifications

### Need Calculation

The mod calculates how much of each item is needed using:

```
need = targetStock - (currentStock + itemsInTransit)
```

This ensures the mod:
- Accounts for items already in transit (won't over-buy)
- Maintains stock at target levels
- Avoids unnecessary trades

### Performance Optimizations

- **Offer Caching** - NPC offers are cached and only refreshed when needed (default: every 120 ticks)
- **Skip Flags** - Ships with no items to purchase are marked and skipped
- **Automatic Cleanup** - Ship state is released when ships leave or reach trade limits
- **Async Execution** - All mod logic runs in separate threads to avoid blocking the game

### Safety Features

- **No Save File Modifications** - All state is in-memory only
- **Non-Blocking** - Mod logic never blocks game execution
- **Error Isolation** - Errors are caught and logged, never crash the game
- **Respects Game Limits** - All game trade limits and constraints are honored

---

## Logging

### Enabling Logs

To enable logging, set `{enable_logging}` to `true` in `info.xml`:

```xml
<var value="true" name="{enable_logging}">Enable Logging</var>
```

### Log File Location

Logs are saved to: `[Game Directory]/mods/AutoBuyerMod/logs/`

Format: `AutoBuyerMod_YYYY-MM-DD_HH-MM-SS.log` (one file per game session)

### Log Contents

Logs include:
- Trade creation events
- Ship arrival/departure events
- Configuration loading
- Error messages with stack traces
- Discovery of new items in NPC offers

### Performance Note

Logging is **disabled by default** for better performance. Only enable it when debugging or testing.

---

## Troubleshooting

### Mod Not Creating Trades

**Check:**
1. Is the mod enabled in the modloader UI?
2. Do you have a player station (not just a ship)?
3. Are NPC ships arriving in your sector?
4. Do you have sufficient credits (above `{min_credit_balance}`)?
5. Are target stocks configured in `info.xml`?
6. Check log files if logging is enabled

### Trades Not Being Created for Specific Items

**Possible Causes:**
1. Item target stock is set to 0 or not configured
2. Current stock + items in transit already meets target
3. Item markup level doesn't meet threshold requirements:
   - Premium items: Only bought if stock < `{premium_buy_threshold}`% of target (default: 20%)
   - Markup items: Only bought if stock < `{markup_buy_threshold}`% of target (default: 40%)
   - Normal items: Only bought if stock < `{normal_buy_threshold}`% of target (default: 70%)
   - Discounted items: Always bought if stock < `{discount_buy_threshold}`% of target (default: 100%)
4. Credit limit per trade is too low

### Configuration Not Loading

**Solutions:**
1. Verify `info.xml` syntax is correct (no typos in variable names)
2. Check that preset file exists if using `{config_preset}`
3. Ensure preset JSON syntax is valid
4. Restart the game after changing configuration
5. Check log files for error messages

### Ships Not Being Traded With

**Possible Causes:**
1. Ship has reached max trades (8 per ship per visit)
2. Ship has 4 concurrent trades already active
3. Ship is still in initialization delay (10 seconds for new ships)
4. Ship has no items the mod needs
5. Ship is derelict or claimable (mod skips these)

### Performance Issues

**If the game is running slowly:**
1. Disable logging (`{enable_logging}` = `false`)
2. Increase cooldown ticks (`{cooldown_ticks}` = `240` or higher)
3. Reduce number of items with target stocks configured
4. Check log file size (if logging is enabled)

### Mod Not Loading

**Check:**
1. Modloader version is 0.11.0 or higher
2. Game version is 0.21.0 (Beta 2)
3. JAR file and `info.xml` are in correct location
4. Modloader is properly installed
5. Check modloader logs for errors

---

## Technical Details

### Architecture

- **Language:** Java 8
- **Framework:** AspectJ (Load-Time Weaving)
- **Build System:** Maven
- **Package:** `com.rinswiftwings.autobuyermod`

### Core Components

1. **AutoBuyerAspect** - AspectJ pointcuts and event hooks
2. **AutoBuyerCore** - Business logic and trade evaluation
3. **AutoBuyerConfig** - Configuration management
4. **ModLog** - File-based logging utility

### Event Hooks

The mod intercepts these game methods:
- `World.setTradeDone()` - Trade completion
- `World.cancelTrade()` - Trade cancellation
- `World.addShip()` - Ship arrival
- `World.shipJumped()` - Ship departure
- `Ship.entityBoarded()` - Shuttle docking

### State Management

- **In-Memory Only** - No persistent state, safe to remove anytime
- **Per-Ship Tracking** - Each NPC ship has its own state
- **Automatic Cleanup** - State released when ships leave or become inactive

### Trade Limits

The mod respects all game limits:
- **10 units max per trade** (shuttle capacity)
- **4 concurrent trades max per NPC ship**
- **8 total trades per ship per visit**
- **Credit availability** (respects `{max_credits_per_trade}` and `{min_credit_balance}`)

### Logistics Integration

The mod automatically responds to logistics status:
- **20 items in transit:** Slows trading
- **40 items in transit:** Pauses trading
- **60 items in transit:** Cancels pending trades

---

## Version History

### Version 0.1.7 (January 2026) - **Stable Release**

**Status:** This version is stable and ready for regular use. All core functionality has been tested and verified.

**Major Changes:**
- **User-Configurable Markup Thresholds** - Replaced deprecated `allow_markup` boolean with threshold-based system
  - `{discount_buy_threshold}` - Buy Discounted items if stock < this % of target (Default: 100)
  - `{normal_buy_threshold}` - Buy Normal items if stock < this % of target (Default: 70)
  - `{markup_buy_threshold}` - Buy Markup items if stock < this % of target (Default: 40)
  - `{premium_buy_threshold}` - Buy Premium items if stock < this % of target (Default: 20)
- **Default Stock Quantities** - Aligned with AutoTrader mod's min stock values for consistency
- **Credit Limits Updated** - Better scaling across game stages (EarlyGame: 2000/3000, MidGame: 4000/5000, LateGame: 6000/10000)
- **Preset Files** - All presets updated with new threshold variables and scaled quantities

**Code Improvements:**
- Consistent error logging (replaced `e.printStackTrace()` with `ModLog.log(e)`)
- Removed unused static fields
- Added Javadoc comments to previously uncommented fields
- Code cleanup and formatting improvements

**Configuration Changes:**
- Added four new threshold configuration variables
- `{allow_markup}` marked as deprecated (still works for backward compatibility)
- Default stock quantities updated to match AutoTrader
- Credit limits adjusted per game stage in all presets

**Testing:**
- Threshold system working correctly
- All markup levels (Discounted, Normal, Markup, Premium) respected
- Stock percentage calculations accurate
- Preset loading working correctly
- No errors or exceptions in extended testing

### Version 0.1.6 (December 29, 2025) - **Stable Release**

**Status:** This version is stable and ready for regular use. All core functionality has been tested and verified.

**Bug Fixes:**
- Fixed crew sorting issue: Disabled `guiAspect` that was interfering with alphabetical crew name sorting
- Crew members now sort correctly by name when renamed (restored default game behavior)
- Renaming crew with job/shift prefixes now works as expected

**Technical Changes:**
- Disabled `guiAspect` in `aop.xml` (not needed for AutoBuyerMod functionality)
- Removed custom crew comparator that was overriding game's default name-based sorting

### Version 0.1.5 (December 23, 2025) - **Stable Release**

**Status:** This version is stable and ready for regular use. All core functionality has been tested and verified.

**Major Changes:**
- Fixed preset loading: JSON parsing now correctly extracts variable names with closing braces
- Added delayed config re-read mechanism to handle modloader timing issues
- Enhanced diagnostic logging for preset loading troubleshooting
- Added timestamped diagnostic file support (disabled for production)
- Improved preset case-insensitive matching
- All preset values now correctly applied to configuration

**Bug Fixes:**
- Fixed JSON parsing bug that prevented preset values from being applied
- Fixed timing issue where modloader UI input wasn't being read before game launch
- Preset values now correctly override defaults from info.xml

### Version 0.1.4 (December 20, 2025)

**Major Changes:**
- Hybrid config loading system (respects modloader UI input)
- Preset system implementation
- Logging system fixes
- File storage consolidated to mod folder
- Build system improvements

**Configuration Changes:**
- Default target stocks halved (early game friendly)
- `{max_credits_per_trade}` reduced from 999999 to 2000
- `{enable_logging}` disabled by default

**Bug Fixes:**
- User input from modloader UI now respected
- Log file creation respects `enable_logging` setting
- Config values properly applied

### Version 0.1.3 (December 20, 2025)

- Shuttle docking hook improvements
- Better ship state management
- Performance optimizations

### Version 0.1.2 (December 20, 2025)

- Beta release
- Core functionality complete
- Code cleanup

---

## Known Limitations

1. **No In-Game UI** - Configuration must be done via `info.xml` or presets
2. **Preset JSON Parsing** - Simple line-by-line parsing (may not handle all edge cases)
3. **Markup Thresholds** - Items at different markup levels (Premium, Markup, Normal, Discounted) are only purchased if current stock is below the configured threshold percentage for that markup level. Premium items default to 20% threshold (very low stock required), which may limit purchases of Premium items unless stock is critically low.
4. **No Custom Notification Text** - Trade notifications use the game's default notification format. The mod cannot customize the notification text to indicate that a trade was created automatically (e.g., "AutoTrade" label). Notifications appear identical to manual trades.

---

## Safety & Compatibility

### Save File Safety
- **No save file modifications** - Mod never writes to save files
- **No persistent state** - All state is in-memory only
- **Safe to remove** - Can be removed at any time without affecting saves

### Game Compatibility
- **Non-blocking** - Never blocks game execution
- **Error isolation** - Errors never crash the game
- **Respects game limits** - All trade limits and constraints honored

### Performance
- **Event-driven** - Only runs on game events (no polling)
- **Optimized** - Caching, skip flags, and cooldowns reduce overhead
- **Async execution** - All logic runs in separate threads

---

## Support & Contributing

### Reporting Issues

If you encounter bugs or have suggestions:
1. Check the troubleshooting section above
2. Enable logging and check log files
3. Note your game version and modloader version
4. Describe the issue with steps to reproduce

### Contributing

Contributions are welcome! Areas that could use improvement:
- Additional item types
- More preset configurations
- Performance optimizations
- Documentation improvements

---

## License

See `LICENSE` file in the project root.

---

## Credits

**Author:** Rin  
**Game:** Space Haven by Bugbyte Ltd.  
**Modloader:** [Space Haven Modloader](https://github.com/CyanBlob/spacehaven-modloader) by CyanBlob

---

**Last Updated:** January 2026  
**Mod Version:** 0.1.7 (Stable)

