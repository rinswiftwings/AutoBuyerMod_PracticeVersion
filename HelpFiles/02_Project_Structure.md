# Project Structure - Mod Directory Layout

This reference guide explains the structure of a Space Haven script mod and what each file and directory does.

## Directory Structure

```
AutoBuyerMod/
├── src/                          # Source code directory
│   └── main/
│       ├── java/                 # Java source files
│       │   └── com/
│       │       └── rinswiftwings/
│       │           └── autobuyermod/
│       │               ├── AutoBuyerAspect.java    # AspectJ hooks
│       │               ├── AutoBuyerCore.java      # Core logic
│       │               ├── AutoBuyerConfig.java    # Configuration
│       │               └── ModLog.java             # Logging utility
│       └── resources/
│           └── META-INF/
│               └── aop.xml       # AspectJ configuration
```

**⚠️ IMPORTANT: Package Naming**

The directory structure `com/rinswiftwings/autobuyermod/` matches the package name `com.rinswiftwings.autobuyermod`. 

**When creating your own mod:**
- **You MUST change the package name** to your own unique identifier
- **You MUST update the directory structure** to match your new package name
- Example: If your package is `com.yourname.modname`, your directory should be `com/yourname/modname/`

See **01_Getting_Started.md** for detailed instructions on package naming conventions.
├── presets/                      # Configuration presets (JSON files)
│   ├── EarlyGame.json
│   ├── MidGame.json
│   └── LateGame.json
├── mod_output/                   # Build output (copied to game directory)
│   ├── AutoBuyerMod_0.1.7.jar   # Compiled mod JAR
│   ├── info.xml                  # Mod metadata and config
│   └── presets/                  # Preset files (copied here)
├── target/                       # Maven build output (temporary)
├── pom.xml                       # Maven project configuration
├── info.xml                      # Mod metadata and default config
└── README.md                     # Mod documentation
```

## Key Files Explained

### pom.xml

Maven Project Object Model file. Defines:
- **Project metadata**: Group ID, artifact ID, version
- **Dependencies**: AspectJ, Space Haven game classes
- **Build configuration**: AspectJ Maven plugin, compiler settings
- **Build tasks**: Compilation, packaging, file copying

**Key Sections:**
- `<dependencies>` - External libraries (AspectJ, game classes)
- `<build>` - Build plugins and configuration
- `<properties>` - Java version, encoding settings

### info.xml

Mod metadata and configuration file. Contains:
- **Mod information**: Name, author, description, version
- **Game compatibility**: Minimum modloader version, supported game versions
- **Configuration variables**: User-configurable settings with defaults

**Structure:**
```xml
<mod>
    <name>Mod Name</name>
    <author>Author Name</author>
    <description>Mod description...</description>
    <minimumLoaderVersion>0.11.0</minimumLoaderVersion>
    <gameVersions>
        <v>0.21.0</v>
    </gameVersions>
    <config>
        <var value="30" default="30" name="{item_name}">Description</var>
    </config>
    <version>0.1.7</version>
</mod>
```

**Configuration Variables:**
- Format: `{variable_name}` (must include braces)
- `value` attribute: Current/default value
- `default` attribute: Default value (usually same as `value`)
- Displayed in modloader UI for user configuration

### src/main/java/

Java source code directory. Organized by package:
- **⚠️ IMPORTANT:** The package structure `com.rinswiftwings.autobuyermod` is an EXAMPLE
- **You MUST change this to your own unique package name** when creating your own mod
- Each `.java` file is a class
- AspectJ aspects must be in this directory
- Directory structure must match package name (e.g., `com/yourname/modname/`)

See **01_Getting_Started.md** for detailed package naming instructions.

**Main Classes:**
- **AutoBuyerAspect.java**: AspectJ aspect class with `@Aspect` annotation. Contains hooks into game events.
- **AutoBuyerCore.java**: Core business logic. Handles trade creation, ship state management, priority scoring.
- **AutoBuyerConfig.java**: Configuration management. Loads from `info.xml`, handles presets, stores settings.
- **ModLog.java**: Logging utility. Handles file logging, diagnostic output, log file creation.

### src/main/resources/META-INF/aop.xml

AspectJ configuration file. Defines:
- **Aspects**: Which aspect classes to use
- **Weaving**: Which packages to include in AspectJ weaving
- **Options**: Verbose logging, show weave info

**Structure:**
```xml
<aspectj>
    <aspects>
        <!-- ⚠️ IMPORTANT: Replace with YOUR unique package name! -->
        <aspect name="com.rinswiftwings.autobuyermod.AutoBuyerAspect"/>
    </aspects>
    <weaver options="-verbose -showWeaveInfo">
        <include within="fi.bugbyte..*" />
        <!-- ⚠️ IMPORTANT: Replace with YOUR unique package name! -->
        <include within="com.rinswiftwings..*" />
    </weaver>
</aspectj>
```

**⚠️ IMPORTANT:** When creating your own mod, replace `com.rinswiftwings` with your own unique package name (e.g., `com.yourname`).

**Why it's needed:**
- Tells AspectJ which aspect classes to use
- Specifies which game classes can be woven
- Enables AspectJ to intercept method calls

### presets/

Directory containing JSON preset files. Each preset is a complete configuration:
- **Format**: JSON with `config` object containing variable-value pairs
- **Naming**: `PresetName.json` (case-sensitive recommended)
- **Usage**: Set `{config_preset}` in `info.xml` to load a preset

**Example Preset Structure:**
```json
{
    "config": {
        "{food_root_vegetables}": "15",
        "{water}": "30",
        "{max_credits_per_trade}": "2000"
    }
}
```

### mod_output/

Build output directory. Contains files that get copied to the game's mod directory:
- **JAR file**: Compiled mod (must match `info.xml` version)
- **info.xml**: Mod metadata (copied from root)
- **presets/**: Preset files (copied from `presets/`)

**Maven Configuration:**
The `pom.xml` includes an Ant task to copy files to `mod_output/` during build.

### target/

Maven build directory (temporary):
- **classes/**: Compiled `.class` files
- **AutoBuyerMod_0.1.7.jar**: Final JAR (before copying to `mod_output/`)
- **generated-sources/**: Generated code (if any)
- Can be safely deleted (recreated on next build)

## Build Process

1. **Compile**: `mvn compile`
   - Compiles Java source to `.class` files
   - Runs AspectJ weaver to inject hooks
   - Output: `target/classes/`

2. **Package**: `mvn package`
   - Creates JAR file from compiled classes
   - Includes resources (`aop.xml`)
   - Output: `target/AutoBuyerMod_0.1.7.jar`

3. **Copy Files**: (configured in `pom.xml`)
   - Copies JAR to `mod_output/`
   - Copies `info.xml` to `mod_output/`
   - Copies presets to `mod_output/presets/`

## Package Structure

Java packages organize code by functionality:
- **`com.rinswiftwings.autobuyermod`**: Main mod package
  - All mod classes are in this package
  - Prevents naming conflicts with game classes

**Why reverse domain naming?**
- Standard Java convention
- Ensures unique package names
- Prevents conflicts with other mods

## File Naming Conventions

- **Java classes**: PascalCase (e.g., `AutoBuyerCore.java`)
- **Aspect classes**: PascalCase with "Aspect" suffix (e.g., `AutoBuyerAspect.java`)
- **Preset files**: PascalCase with `.json` extension (e.g., `EarlyGame.json`)
- **Config variables**: Lowercase with underscores, wrapped in braces (e.g., `{food_root_vegetables}`)

## Dependencies

External libraries required by the mod:

1. **AspectJ Runtime** (`aspectjrt`)
   - Provides AspectJ annotations and runtime support
   - Version: 1.9.19 (must match AspectJ Maven plugin version)

2. **Space Haven Game Classes** (`spacehaven`)
   - Decompiled game classes
   - Group ID: `fi.bugbyte`
   - Artifact ID: `spacehaven`
   - Version: 1.0.0 (arbitrary, doesn't match game version)

3. **AspectJ Weaver** (`aspectjweaver`)
   - Used during compilation (test scope)
   - Not included in final JAR

## Maven Build Configuration

### AspectJ Maven Plugin

Compiles Java and weaves AspectJ code:
```xml
<plugin>
    <groupId>dev.aspectj</groupId>
    <artifactId>aspectj-maven-plugin</artifactId>
    <version>1.13.1</version>
    <configuration>
        <complianceLevel>1.8</complianceLevel>
    </configuration>
</plugin>
```

### Antrun Plugin

Copies files to `mod_output/` after packaging:
- Copies JAR file
- Copies `info.xml`
- Copies preset files

## Version Management

Version numbers must match across:
- `pom.xml`: `<version>0.1.7</version>`
- `info.xml`: `<version>0.1.7</version>`
- JAR filename: `AutoBuyerMod_0.1.7.jar`

**Why consistency matters:**
- Modloader checks version in `info.xml`
- Users identify mod version from JAR filename
- Build process uses version from `pom.xml`

## Common File Locations

### During Development
- Source code: `src/main/java/`
- Configuration: `info.xml` (root)
- Presets: `presets/`
- Build output: `target/`

### In Game Directory
- Mod JAR: `[Game]/mods/AutoBuyerMod/AutoBuyerMod_0.1.7.jar`
- Mod config: `[Game]/mods/AutoBuyerMod/info.xml`
- Presets: `[Game]/mods/AutoBuyerMod/presets/`
- Logs: `[Game]/mods/AutoBuyerMod/logs/`

## Next Steps

- Read **03_AspectJ_Basics.md** to understand AspectJ configuration
- Review **04_Decompiled_Research_Files.md** to find game classes
- Study **07_Code_Walkthrough.md** to see how files work together

---

**Previous:** [01_Getting_Started.md](01_Getting_Started.md)  
**Next:** [03_AspectJ_Basics.md](03_AspectJ_Basics.md)

