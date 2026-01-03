# Presets Directory

This directory contains preset configuration files for the Auto-Buyer Mod.

**Location:** `[Game Directory]/mods/AutoBuyerMod/presets/`

## How to Use Presets

### Step 1: Copy a Preset
1. Copy one of the preset files (e.g., `EarlyGame.json`)
2. Rename it to your desired preset name (e.g., `MyCustomPreset.json`)
3. Keep it in this `presets/` directory

### Step 2: Edit the Preset
1. Open the copied preset file in any text editor
2. Edit the values in the `"config"` section to match your preferences
3. Change the `"name"` and `"description"` fields if desired
4. Save the file

### Step 3: Use the Preset
1. Edit `info.xml` in the mod folder
2. Find the `{config_preset}` variable
3. Set its value to your preset name (without `.json`), e.g., `EarlyGame` or `MyCustomPreset`
   - **Important:** Use the exact case as the filename (case-sensitive)
4. Save `info.xml`
5. Restart the game

## Example: Creating a Custom Preset

**File:** `presets/MyStation.json`
```json
{
  "name": "My Station Setup",
  "description": "Optimized for my playstyle",
  "config": {
    "{food_root_vegetables}": "30",
    "{metal_basic}": "100",
    "{max_credits_per_trade}": "5000",
    "{allow_markup}": "true"
  }
}
```

Then set `{config_preset}` in `info.xml` to: `MyStation`

## Available Presets

- **EarlyGame.json** - Conservative targets for new stations (based on mod defaults)
  - Use `EarlyGame` (exact case) in `info.xml`
- **LateGame.json** - Higher targets for established stations
  - Use `LateGame` (exact case) in `info.xml`

## Preset File Format

Presets are JSON files with this structure:
```json
{
  "name": "Preset Name",
  "description": "Description of the preset",
  "config": {
    "{variable_name}": "value",
    ...
  }
}
```

## Notes

- **Preset names are case-sensitive** - Use the exact case as the filename (e.g., `EarlyGame` not `earlygame`)
  - On Windows, different cases may work, but it's not guaranteed
  - On Linux/Mac, only the exact case will work
  - **Recommended:** Always use the exact case: `EarlyGame` or `LateGame`
- Preset names should be valid file names (no special characters, spaces are OK)
- You can have multiple presets - just use different file names
- Only the `"config"` section values are used - `"name"` and `"description"` are for your reference
- If a config variable is missing from the preset, the mod will use the default value from `info.xml`
- To stop using a preset, set `{config_preset}` in `info.xml` to an empty string: `""`

## Sharing Presets

You can share preset files with other players! Just:
1. Copy the `.json` file
2. Send it to them
3. They place it in their `presets/` directory
4. They set `{config_preset}` in their `info.xml` to the preset name

