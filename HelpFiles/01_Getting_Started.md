# Getting Started - Development Environment Setup

This guide will walk you through setting up your development environment to create script mods for Space Haven. Follow these steps in order.

## ⚠️ IMPORTANT: Package Naming Convention

**Before you begin, you must understand package naming conventions.**

### Why Package Names Matter

The example code in this practice mod uses the package name `com.rinswiftwings.autobuyermod`. **You MUST change this to your own unique package name** when creating your own mod.

**Reasons:**
1. **Avoid Conflicts**: If multiple mods use the same package name, they can conflict with each other
2. **Java Convention**: Package names should be unique identifiers (typically reverse domain name)
3. **Class Loading**: Java uses package names to organize and load classes - duplicates cause issues
4. **Modloader Requirements**: The modloader may reject mods with conflicting package names

### How to Choose Your Package Name

**Recommended Format:** `com.yourname.modname` or `org.yourname.modname`

**Examples:**
- If your name is John Smith and mod is "AutoMiner": `com.johnsmith.autominer`
- If you have a domain: `com.yourdomain.spacehavenmod`
- If using GitHub username: `com.github.yourusername.modname`

**Best Practices:**
- Use lowercase letters only
- Use your real name, username, or domain
- Make it unique to you
- Keep it descriptive but not too long

### Where to Change Package Names

You'll need to change the package name in:
1. **All Java files** - The `package` declaration at the top of each `.java` file
2. **Directory structure** - Move files to match new package name (e.g., `com/yourname/modname/`)
3. **aop.xml** - Update aspect registration to use new package name
4. **pom.xml** - Update groupId if desired (though this is less critical)

**Example:**
```java
// OLD (example from this practice mod):
package com.rinswiftwings.autobuyermod;

// NEW (your unique package name):
package com.yourname.yourmodname;
```

**⚠️ Remember:** This is one of the first things you should do when creating your own mod. Don't copy the example package name directly!

## Prerequisites

Before you begin, ensure you have:
- **Java Development Kit (JDK) 8 or higher** - Space Haven mods require Java 8 compatibility
- **Maven 3.6+** - Build tool for managing dependencies and compiling
- **A Java IDE** - IntelliJ IDEA, Eclipse, or VS Code with Java extensions
- **Space Haven game** - Installed and accessible
- **Space Haven Modloader** - Installed and working (version 0.11.0+)

## Step 1: Install Java Development Kit (JDK)

### Windows
1. Download JDK 8 or higher from [Oracle](https://www.oracle.com/java/technologies/downloads/) or [OpenJDK](https://adoptium.net/)
2. Run the installer and follow the prompts
3. Verify installation:
   ```powershell
   java -version
   javac -version
   ```
4. Set `JAVA_HOME` environment variable to your JDK installation path (e.g., `C:\Program Files\Java\jdk-1.8.0_XXX`)

### Linux/Mac
1. Install via package manager:
   ```bash
   # Ubuntu/Debian
   sudo apt-get install openjdk-8-jdk
   
   # macOS (using Homebrew)
   brew install openjdk@8
   ```
2. Verify installation:
   ```bash
   java -version
   javac -version
   ```

## Step 2: Install Maven

### Windows
1. Download Maven from [Apache Maven](https://maven.apache.org/download.cgi)
2. Extract to a location like `C:\Program Files\Apache\maven`
3. Add Maven's `bin` directory to your `PATH` environment variable
4. Verify installation:
   ```powershell
   mvn -version
   ```

### Linux/Mac
1. Install via package manager:
   ```bash
   # Ubuntu/Debian
   sudo apt-get install maven
   
   # macOS (using Homebrew)
   brew install maven
   ```
2. Verify installation:
   ```bash
   mvn -version
   ```

## Step 3: Set Up Your IDE

### IntelliJ IDEA (Recommended)
1. Download and install [IntelliJ IDEA Community Edition](https://www.jetbrains.com/idea/download/)
2. Open IntelliJ and create a new project:
   - Select "Maven" as the project type
   - Choose JDK 8 or higher
   - Use the `pom.xml` from AutoBuyerMod as a template
3. Import the project:
   - File → Open → Select the mod directory
   - IntelliJ will automatically detect Maven and download dependencies
4. Configure AspectJ:
   - Install the "AspectJ" plugin (File → Settings → Plugins)
   - The AspectJ Maven plugin in `pom.xml` will handle compilation

### Eclipse
1. Download and install [Eclipse IDE for Java Developers](https://www.eclipse.org/downloads/)
2. Install AspectJ Development Tools (AJDT):
   - Help → Eclipse Marketplace
   - Search for "AspectJ" and install
3. Import the project:
   - File → Import → Maven → Existing Maven Projects
   - Select the mod directory
4. Configure AspectJ:
   - Right-click project → Configure → Convert to AspectJ Project

### VS Code
1. Install [VS Code](https://code.visualstudio.com/)
2. Install extensions:
   - Java Extension Pack
   - Maven for Java
3. Open the mod directory as a workspace
4. VS Code will detect Maven and prompt to install dependencies

## Step 4: Obtain Space Haven Game Classes

Space Haven mods need access to the game's classes. You have two options:

### Option A: Use Decompiled JAR (Recommended for Learning)
1. Locate `spacehaven.jar` in your Space Haven installation directory
2. Decompile using a tool like [JD-GUI](http://java-decompiler.com/) or [Fernflower](https://github.com/JetBrains/intellij-community/tree/master/plugins/java-decompiler/engine)
3. Extract the decompiled classes to a directory (e.g., `decompiled/`)
4. Create a local Maven repository:
   ```bash
   mvn install:install-file \
     -Dfile=spacehaven.jar \
     -DgroupId=fi.bugbyte \
     -DartifactId=spacehaven \
     -Dversion=1.0.0 \
     -Dpackaging=jar
   ```

### Option B: Use Provided Dependency (If Available)
Some modding communities provide pre-packaged dependencies. Check the Space Haven modding community for available packages.

## Step 5: Configure Maven (pom.xml)

Your `pom.xml` should include:

```xml
<dependencies>
    <!-- AspectJ Runtime -->
    <dependency>
        <groupId>org.aspectj</groupId>
        <artifactId>aspectjrt</artifactId>
        <version>1.9.19</version>
    </dependency>
    
    <!-- Space Haven Game Classes -->
    <dependency>
        <groupId>fi.bugbyte</groupId>
        <artifactId>spacehaven</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <!-- AspectJ Maven Plugin -->
        <plugin>
            <groupId>dev.aspectj</groupId>
            <artifactId>aspectj-maven-plugin</artifactId>
            <version>1.13.1</version>
            <configuration>
                <complianceLevel>1.8</complianceLevel>
                <source>1.8</source>
                <target>1.8</target>
            </configuration>
            <dependencies>
                <dependency>
                    <groupId>org.aspectj</groupId>
                    <artifactId>aspectjtools</artifactId>
                    <version>1.9.19</version>
                </dependency>
            </dependencies>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

## Step 6: Create AspectJ Configuration (aop.xml)

Create `src/main/resources/META-INF/aop.xml`:

```xml
<aspectj>
    <aspects>
        <!-- Register your aspect classes here -->
        <!-- ⚠️ IMPORTANT: Replace with YOUR unique package name! -->
        <aspect name="com.yourpackage.YourAspect"/>
    </aspects>
    
    <weaver options="-verbose -showWeaveInfo">
        <!-- Include game classes -->
        <include within="fi.bugbyte..*" />
        <!-- Include your mod classes -->
        <!-- ⚠️ IMPORTANT: Replace with YOUR unique package name! -->
        <include within="com.yourpackage..*" />
    </weaver>
</aspectj>
```

**⚠️ Remember:** Replace `com.yourpackage` with your own unique package name (e.g., `com.yourname.yourmodname`).

## Step 7: Build Your First Mod

1. Navigate to your mod directory in terminal/command prompt
2. Build the project:
   ```bash
   mvn clean compile
   ```
3. Package the mod:
   ```bash
   mvn clean package
   ```
4. The compiled JAR will be in `target/` directory

## Step 8: Test Your Mod

1. Copy your mod JAR and `info.xml` to Space Haven's mods directory:
   ```
   [Space Haven Directory]/mods/YourModName/
   ├── YourModName_0.1.0.jar
   └── info.xml
   ```
2. Launch Space Haven with the modloader
3. Enable your mod in the modloader UI
4. Start a game and test your mod

## Common Issues and Solutions

### Issue: "Cannot find symbol" errors
**Solution:** Ensure `spacehaven.jar` is properly installed in your local Maven repository and the dependency is correctly configured in `pom.xml`.

### Issue: AspectJ compilation errors
**Solution:** 
- Verify AspectJ Maven plugin version matches AspectJ runtime version
- Check that `aop.xml` correctly references your aspect classes
- Ensure AspectJ plugin is configured in `pom.xml`

### Issue: Mod doesn't load in game
**Solution:**
- Verify `info.xml` has correct structure (see 02_Project_Structure.md)
- Check that JAR file is in correct location
- Enable modloader logging to see error messages
- Verify `minimumLoaderVersion` in `info.xml` matches your modloader version

### Issue: Hooks not firing
**Solution:**
- Check AspectJ pointcut syntax (see 03_AspectJ_Basics.md)
- Verify method signatures match exactly (including package names)
- Check `aop.xml` includes the correct packages
- Enable verbose AspectJ logging in `aop.xml` (`-verbose -showWeaveInfo`)

## Next Steps

Once your environment is set up:
1. Read **02_Project_Structure.md** to understand the mod layout
2. Study **03_AspectJ_Basics.md** to learn how to create hooks
3. Review **05_Game_Hooks_Used.md** for real examples
4. Follow **07_Code_Walkthrough.md** to understand the codebase

## Additional Resources

- [Maven Getting Started Guide](https://maven.apache.org/guides/getting-started/)
- [AspectJ Programming Guide](https://www.eclipse.org/aspectj/doc/released/progguide/index.html)
- [Space Haven Modloader Documentation](https://github.com/bugbyte-dev/spacehaven-modloader)

---

**Next:** [02_Project_Structure.md](02_Project_Structure.md)

