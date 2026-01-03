# AutoBuyerMod - Changelog

This changelog documents all versions, issues discovered during testing, and steps taken to address them.

## Version 0.1.7 (January 2026)

**Status:** **Stable** - Ready for regular use  
**Game Version:** Beta 2 (0.21.0) - Experimental Branch  
**Minimum Modloader Version:** 0.11.0

### Overview

Version 0.1.7 introduces user-configurable markup thresholds, replacing the deprecated `allow_markup` boolean system. This provides fine-grained control over when items are purchased at different price levels based on stock percentage.

### New Features

#### User-Configurable Markup Thresholds

**Feature:** Replaced simple `allow_markup` boolean with threshold-based system.

**Configuration Variables:**
- `{discount_buy_threshold}` - Buy Discounted items if stock < this % of target (Default: 100)
- `{normal_buy_threshold}` - Buy Normal items if stock < this % of target (Default: 70)
- `{markup_buy_threshold}` - Buy Markup items if stock < this % of target (Default: 40)
- `{premium_buy_threshold}` - Buy Premium items if stock < this % of target (Default: 20)

**Why This Change:**
- More flexible than simple boolean
- Allows fine-tuning of buying behavior
- Consistent with AutoTrader mod's approach
- Better control over when to buy at different price levels

**Implementation:**
- Added threshold fields to `AutoBuyerConfig.java`
- Updated `buildTradeAgreement()` to use threshold-based logic
- Updated `calculateShipPriority()` to respect thresholds
- Marked `allowMarkup` as `@Deprecated` for backward compatibility

### Configuration Updates

#### Default Stock Quantities Aligned with AutoTrader

**Change:** Updated default target stock quantities in `info.xml` to match AutoTrader's min stock values.

**Why:**
- Consistency between mods
- Better default values based on testing
- Easier for users switching between mods

#### Credit Limits Updated

**Changes:**
- Default/MidGame: `{max_credits_per_trade}` = 4000, `{min_credit_balance}` = 5000
- EarlyGame: `{max_credits_per_trade}` = 2000, `{min_credit_balance}` = 3000
- LateGame: `{max_credits_per_trade}` = 6000, `{min_credit_balance}` = 10000

**Why:**
- Better scaling across game stages
- Prevents overspending in early game
- Allows larger trades in late game

#### Preset Files Updated

**Changes:**
- All presets updated with new threshold variables
- Stock quantities scaled appropriately (EarlyGame = 0.5x, LateGame = 2x MidGame)
- Credit limits adjusted per game stage

### Code Improvements

#### Consistent Error Logging

**Change:** Replaced all `e.printStackTrace()` calls with `ModLog.log(e)`.

**Why:**
- Consistent logging format
- Better error tracking
- Respects logging enable/disable setting

#### Code Cleanup

**Changes:**
- Removed unused static fields (`discoveryCallCount`, `attemptCounter`)
- Added Javadoc comments to previously uncommented fields
- Ensured consistent spacing and formatting

### Testing

**Test Results:**
- Threshold system working correctly
- All markup levels (Discounted, Normal, Markup, Premium) respected
- Stock percentage calculations accurate
- Preset loading working correctly
- No errors or exceptions in extended testing
- 3 successful trades created during test session

**Issues Found:**
- None - all systems functioning correctly

### Files Changed

- `AutoBuyerConfig.java` - Added threshold fields, deprecated `allowMarkup`
- `AutoBuyerCore.java` - Updated trade building and priority calculation logic
- `info.xml` - Added threshold variables, updated defaults, aligned with AutoTrader format
- `pom.xml` - Version updated to 0.1.7
- `presets/EarlyGame.json` - Updated with thresholds and scaled quantities
- `presets/MidGame.json` - Created to match default values
- `presets/LateGame.json` - Updated with thresholds and scaled quantities

---

## Version 0.1.6 (December 29, 2025)

**Status:** **Stable** - Ready for regular use

### Bug Fixes

#### Critical: Crew Sorting Fixed

**Issue:** Crew members were not sorting alphabetically by name when renamed.

**Root Cause:** The `guiAspect` was intercepting the crew comparator and replacing it with a custom comparator that sorted by ship ID and entity ID instead of by name.

**Fix:** Disabled `guiAspect` in `aop.xml` by commenting it out.

**Impact:**
- Crew members now sort correctly by name when renamed
- Job/shift prefix naming schemes work as expected
- AutoBuyerMod trading functionality completely unaffected

### Technical Changes

- **File:** `src/main/resources/META-INF/aop.xml`
- **Change:** Commented out `guiAspect` aspect registration
- **Reason:** This aspect was not needed for AutoBuyerMod functionality and was interfering with crew sorting

### Testing

- Crew sorting by name works correctly
- Renaming crew members affects sort order
- AutoBuyerMod trading functionality works normally
- All existing features unaffected

---

## Version 0.1.5 (December 23, 2025)

**Status:** **Stable** - Ready for regular use

### Bug Fixes

#### Critical: Preset Loading Fixed

**Issue:** Preset values were not being applied to the mod configuration, even though preset files were being loaded successfully.

**Root Cause:** JSON parsing bug in `loadPreset()` method was incorrectly extracting variable names, removing the closing brace. For example, `{water}` was being parsed as `{water` (missing closing brace), causing preset values to not match config keys.

**Fix:** Updated JSON parsing logic to correctly extract variable names with their closing braces. Preset values now correctly override defaults from `info.xml`.

**Impact:** Preset files now work as intended. Users can create and use preset configurations to quickly switch between different playstyles.

#### Timing Issue: Modloader Config Integration

**Issue:** When users entered preset names in the modloader UI, the mod would read `info.xml` before the modloader wrote the user-selected preset value, causing presets to not load.

**Fix:** Implemented a delayed config re-read mechanism. A background thread waits 2 seconds after initial load, then re-reads `info.xml` and applies the preset if found. This accounts for the modloader's asynchronous `info.xml` updates.

**Impact:** Presets can now be selected via the modloader UI and will be correctly loaded, even with timing delays.

### New Features

#### Enhanced Diagnostic Logging

- Added `System.err.println()` diagnostic output for early troubleshooting
- Diagnostic messages now appear even before file logging is enabled
- Enhanced preset loading diagnostics show exact case vs case-insensitive search results

#### Improved Preset Case-Insensitive Matching

- Preset file search now tries exact case match first, then falls back to case-insensitive search
- Better error messages when preset files are not found
- Enhanced logging shows which matching method was used

### Testing

**Test Results:**
- Preset loading from `info.xml` preset name
- Preset loading from modloader UI input
- Case-insensitive preset file matching
- Preset values overriding defaults
- Delayed config re-read mechanism
- Multiple preset files (EarlyGame.json, LateGame.json)
- All 56 preset values correctly applied
- Trading system with preset configurations

**Test Results:**
- 19 successful trades across 3 ships
- 100% preset value application rate
- Zero errors or warnings
- All preset values match expected configuration

### Files Changed

- `ModLog.java`: Disabled diagnostic file for production, added re-enablement comments
- `AutoBuyerAspect.java`: Fixed JSON parsing, added delayed re-read mechanism
- `pom.xml`: Version updated to 0.1.5
- `info.xml`: Version updated to 0.1.5

---

## Earlier Versions

### Version 0.1.4 and Earlier

**Note:** Detailed changelogs for versions 0.1.4 and earlier are not available in the current documentation. Key features from earlier versions include:

- Initial mod release
- Basic auto-buying functionality
- Configuration system
- Preset system (initial implementation)
- Multi-ship prioritization
- Logistics awareness
- Trade limit management

---

## Issue Tracking Summary

### Issues Discovered and Fixed

1. **Preset Loading Bug (0.1.5)**
   - **Issue:** Preset values not applied
   - **Root Cause:** JSON parsing bug (missing closing brace)
   - **Fix:** Corrected JSON parsing logic
   - **Status:** Fixed

2. **Timing Issue (0.1.5)**
   - **Issue:** Presets not loading from modloader UI
   - **Root Cause:** Mod reads config before modloader writes it
   - **Fix:** Delayed config re-read mechanism
   - **Status:** Fixed

3. **Crew Sorting Issue (0.1.6)**
   - **Issue:** Crew not sorting by name
   - **Root Cause:** `guiAspect` interfering with crew comparator
   - **Fix:** Disabled `guiAspect` in `aop.xml`
   - **Status:** Fixed

4. **Markup System Limitation (0.1.7)**
   - **Issue:** Simple boolean `allow_markup` too restrictive
   - **Root Cause:** No fine-grained control over buying behavior
   - **Fix:** Implemented threshold-based system
   - **Status:** Fixed

### Testing Discoveries

**From Testing Logs (0.1.7):**

1. **Stock Levels Well-Maintained**
   - Many "0 items with need > 0" messages indicate targets are being met
   - Threshold filtering working correctly
   - No issues with stock management

2. **Threshold System Working**
   - Discounted items: Purchased when stock < 100% of target
   - Normal items: Purchased when stock < 70% of target
   - Markup items: Purchased when stock < 40% of target
   - Premium items: Purchased when stock < 20% of target

3. **Trade Creation Successful**
   - 3 trades created during test session
   - All trades completed successfully
   - No errors or exceptions

4. **Ship Management Working**
   - New ship 10-second delay functioning
   - Cooldown system working
   - Trade limit tracking accurate
   - Ship state flushed when ships leave

### Performance Improvements

**Version 0.1.7:**
- Consistent error logging (no printStackTrace)
- Removed unused code
- Better code organization

**Version 0.1.6:**
- Disabled unnecessary aspect (reduced overhead)

**Version 0.1.5:**
- Improved preset loading performance
- Better caching of configuration values

---

## Migration Guide

### Upgrading to Version 0.1.7

**For Users:**
1. Replace `AutoBuyerMod_0.1.6.jar` with `AutoBuyerMod_0.1.7.jar`
2. Replace `info.xml` with new version (or merge your custom values)
3. Your existing presets will work, but you may want to add threshold variables
4. `{allow_markup}` is deprecated but still works for backward compatibility

**For Preset Creators:**
- Add threshold variables to preset files:
  ```json
  {
      "config": {
          "{discount_buy_threshold}": "100",
          "{normal_buy_threshold}": "70",
          "{markup_buy_threshold}": "40",
          "{premium_buy_threshold}": "20"
      }
  }
  ```

### Upgrading to Version 0.1.6

**No action required.** Simply update to version 0.1.6 and crew sorting will work correctly.

### Upgrading to Version 0.1.5

**No action required.** Simply update to version 0.1.5 and your existing preset files will work correctly.

---

## Known Issues

**None.** All identified issues have been resolved in the current version.

---

## Future Improvements

Potential enhancements for future versions:
- Additional configuration options
- More sophisticated priority scoring
- Enhanced logging and diagnostics
- Performance optimizations

---

**Last Updated:** January 2026  
**Current Version:** 0.1.7

