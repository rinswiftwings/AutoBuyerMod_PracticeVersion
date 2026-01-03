# Mod Output Directory

This directory mirrors the final mod folder structure that will be installed in the game.

## Structure

```
AutoBuyerMod/
  ├── AutoBuyerMod_0.1.6.jar      (mod JAR file)
  ├── info.xml                     (mod configuration)
  ├── presets/                     (preset configuration files)
  │   ├── EarlyGame.json
  │   └── README.md
  └── logs/                        (runtime log files - created automatically)
```

## Usage

This directory is automatically populated when you run `mvn package`. The Maven build process will:

1. Build the JAR file to `target/AutoBuyerMod_0.1.6.jar`
2. Copy the JAR to `mod_output/AutoBuyerMod_0.1.6.jar`
3. Copy `info.xml` to `mod_output/info.xml`
4. Copy all preset files from `presets/` to `mod_output/presets/`
5. Create the `logs/` directory (empty, for runtime use)

## Installation

To install this mod, copy the entire `AutoBuyerMod/` folder to:
```
[Game Directory]/mods/AutoBuyerMod/
```

The mod will then be available in the game's modloader.

