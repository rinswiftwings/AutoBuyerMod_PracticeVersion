# Troubleshooting - Common Issues and Solutions

This reference guide covers common issues encountered when developing Space Haven mods and how to solve them.

## AspectJ Hook Issues

### Issue: Hook Doesn't Fire

**Symptoms:**
- Hook method never executes
- No log messages from hook
- Expected behavior doesn't happen

**Possible Causes:**
1. Pointcut doesn't match method signature
2. Aspect not registered in `aop.xml`
3. Package name incorrect in pointcut
4. Method signature changed in game update

**Solutions:**

1. **Enable verbose AspectJ logging:**
   ```xml
   <weaver options="-verbose -showWeaveInfo">
   ```
   This shows which methods are being woven.

2. **Verify pointcut syntax:**
   ```java
   // Check package name matches exactly
   @After("execution(* fi.bugbyte.spacehaven.world.World.method(..))")
   // Not: execution(* spacehaven.World.method(..))
   ```

3. **Check aspect registration:**
   ```xml
   <aspects>
       <aspect name="com.yourpackage.YourAspect"/>
   </aspects>
   ```

4. **Test with simple pointcut:**
   ```java
   @After("execution(* fi.bugbyte.spacehaven.world.World.*(..))")
   public void testHook() {
       System.out.println("Hook fired!"); // Simple test
   }
   ```

### Issue: Hook Fires Too Often

**Symptoms:**
- Hook executes for unwanted calls
- Performance issues
- Log spam

**Solutions:**

1. **Narrow pointcut:**
   ```java
   // Too broad:
   @After("execution(* World.*(..))")
   
   // Better:
   @After("execution(* World.setTradeDone(..))")
   ```

2. **Add filters in hook:**
   ```java
   @After("execution(* World.method(..))")
   public void hook(Object arg) {
       if (arg == null) return; // Filter unwanted
       if (!isRelevant(arg)) return; // More filtering
       // Process only relevant cases
   }
   ```

3. **Use more specific pointcut:**
   ```java
   @After("execution(* World.method(Ship)) && args(ship) && if(ship != null && !ship.isPlayerShip())")
   ```

## Configuration Issues

### Issue: Configuration Not Loading

**Symptoms:**
- Default values always used
- User input ignored
- Presets don't work

**Solutions:**

1. **Check info.xml format:**
   ```xml
   <var value="30" default="30" name="{variable_name}">Description</var>
   ```
   - Variable name must include braces: `{variable_name}`
   - Value and default should match

2. **Verify file location:**
   - `info.xml` must be in mod directory root
   - Presets must be in `presets/` subdirectory

3. **Check parsing logic:**
   ```java
   // Verify config values are being read
   ModLog.log("Config value: " + configValues.get("{variable_name}"));
   ```

4. **Test preset loading:**
   - Enable logging
   - Check log for preset loading messages
   - Verify preset file format (JSON)

### Issue: Preset Not Loading

**Symptoms:**
- Preset file exists but values not applied
- Default values used instead of preset

**Solutions:**

1. **Check preset file name:**
   - Must match `{config_preset}` value exactly (case-sensitive recommended)
   - File extension must be `.json`

2. **Verify preset file format:**
   ```json
   {
       "config": {
           "{variable_name}": "value"
       }
   }
   ```

3. **Check preset loading timing:**
   - Modloader may write preset name after mod reads config
   - Delayed recheck should handle this (2-second delay)

4. **Enable diagnostic logging:**
   ```java
   ModLog.diagnostic("Preset check: " + presetName);
   ```

## Trade Creation Issues

### Issue: "An item seems to be missing" Error

**Symptoms:**
- Trade created but fails with error message
- Items not transferred

**Cause:**
Items not reserved before adding to trade.

**Solution:**
```java
// WRONG: Add to trade without reserving
t.toShip1.add(new TradeItem(itemId, quantity));

// CORRECT: Reserve first, then add
for (int i = 0; i < quantity; i++) {
    if (!jobManager.reserveItemForTrade(itemId, tradeId)) {
        break; // Can't reserve more
    }
}
t.toShip1.add(new TradeItem(itemId, reservedQuantity));
```

### Issue: Duplicate Trades Created

**Symptoms:**
- Same trade created multiple times
- Race condition errors

**Cause:**
Multiple hooks/threads creating trades simultaneously.

**Solution:**
```java
synchronized (tradeCreationLock) {
    // Double-check for duplicates
    if (createdTradeIds.contains(trade.id)) {
        return false; // Already created
    }
    createdTradeIds.add(trade.id);
    world.addNewTradeAgreement(trade);
}
```

### Issue: Trade Not Created (No Error)

**Symptoms:**
- `attemptAutoBuy` returns false
- No trade created, no error message

**Debugging Steps:**

1. **Check eligibility:**
   ```java
   if (!shouldAttempt(npcShip, playerStation, world)) {
       ModLog.log("Ship not eligible: " + reason);
       return false;
   }
   ```

2. **Check trade limits:**
   ```java
   if (activeTrades >= 4) {
       ModLog.log("Max concurrent trades reached");
       return false;
   }
   ```

3. **Check offers:**
   ```java
   if (offers == null || offers.isEmpty()) {
       ModLog.log("No offers available");
       return false;
   }
   ```

4. **Check need calculation:**
   ```java
   int need = calculateNeed(...);
   if (need <= 0) {
       ModLog.log("No need for items (stock at target)");
       return false;
   }
   ```

## Performance Issues

### Issue: Mod Slows Down Game

**Symptoms:**
- Game becomes laggy
- FPS drops
- UI freezes

**Solutions:**

1. **Reduce logging:**
   ```java
   // Disable logging in production
   if (config.isLoggingEnabled()) {
       ModLog.log("Message");
   }
   ```

2. **Cache expensive operations:**
   ```java
   // Cache offers instead of querying every time
   if (offersCache.isEmpty()) {
       offersCache = queryOffers(); // Expensive
   }
   ```

3. **Throttle operations:**
   ```java
   // Only run every N calls
   if (callCount % 10 != 0) {
       return; // Skip this call
   }
   ```

4. **Use early returns:**
   ```java
   if (ship == null) return; // Exit early
   if (ship.isPlayerShip()) return; // Filter early
   ```

### Issue: Memory Leaks

**Symptoms:**
- Memory usage grows over time
- Game becomes slower after long play

**Solutions:**

1. **Clean up state:**
   ```java
   @After("execution(* World.shipJumped(..))")
   public void onShipJumped(Ship ship) {
       shipStates.remove(ship.getShipId()); // Clean up
   }
   ```

2. **Remove tracking when done:**
   ```java
   @After("execution(* World.setTradeDone(..))")
   public void onTradeDone(TradeAgreement trade) {
       createdTradeIds.remove(trade.id); // Remove tracking
   }
   ```

3. **Limit cache size:**
   ```java
   if (cache.size() > MAX_CACHE_SIZE) {
       cache.clear(); // Clear old entries
   }
   ```

## Build and Compilation Issues

### Issue: "Cannot find symbol" Errors

**Symptoms:**
- Compilation fails
- Game classes not found

**Solutions:**

1. **Verify dependency:**
   ```xml
   <dependency>
       <groupId>fi.bugbyte</groupId>
       <artifactId>spacehaven</artifactId>
       <version>1.0.0</version>
   </dependency>
   ```

2. **Install game JAR to local Maven repo:**
   ```bash
   mvn install:install-file \
     -Dfile=spacehaven.jar \
     -DgroupId=fi.bugbyte \
     -DartifactId=spacehaven \
     -Dversion=1.0.0 \
     -Dpackaging=jar
   ```

3. **Check package names:**
   ```java
   import fi.bugbyte.spacehaven.world.World; // Correct
   // Not: import spacehaven.World;
   ```

### Issue: AspectJ Weaving Fails

**Symptoms:**
- Build succeeds but hooks don't work
- AspectJ warnings in build output

**Solutions:**

1. **Check AspectJ plugin version:**
   ```xml
   <plugin>
       <groupId>dev.aspectj</groupId>
       <artifactId>aspectj-maven-plugin</artifactId>
       <version>1.13.1</version>
   </plugin>
   ```

2. **Verify aop.xml:**
   ```xml
   <aspectj>
       <aspects>
           <aspect name="com.yourpackage.YourAspect"/>
       </aspects>
       <weaver>
           <include within="fi.bugbyte..*" />
       </weaver>
   </aspectj>
   ```

3. **Enable verbose logging:**
   ```xml
   <weaver options="-verbose -showWeaveInfo">
   ```

## Runtime Issues

### Issue: Mod Doesn't Load

**Symptoms:**
- Mod not visible in modloader
- No errors, just doesn't appear

**Solutions:**

1. **Check info.xml structure:**
   ```xml
   <mod>
       <name>Mod Name</name>
       <version>0.1.0</version>
       <minimumLoaderVersion>0.11.0</minimumLoaderVersion>
   </mod>
   ```

2. **Verify JAR location:**
   - JAR must be in `[Game]/mods/ModName/` directory
   - Filename must match version in `info.xml`

3. **Check modloader version:**
   - Ensure modloader version >= `minimumLoaderVersion`

### Issue: NullPointerException

**Symptoms:**
- Game crashes or errors
- NullPointerException in logs

**Solutions:**

1. **Always check for null:**
   ```java
   if (ship == null) {
       return; // Early exit
   }
   ```

2. **Use safe access:**
   ```java
   String name = ship != null ? ship.getName() : "Unknown";
   ```

3. **Validate before use:**
   ```java
   if (world != null && ship != null) {
       // Safe to use
   }
   ```

### Issue: ClassNotFoundException

**Symptoms:**
- Game crashes on load
- ClassNotFoundException in logs

**Solutions:**

1. **Check imports:**
   ```java
   import fi.bugbyte.spacehaven.world.World; // Verify package
   ```

2. **Verify game JAR:**
   - Ensure `spacehaven.jar` is properly installed
   - Check if game version changed (classes may have moved)

3. **Check AspectJ weaving:**
   - Ensure game classes are included in weaving
   - Check `aop.xml` includes correct packages

## Debugging Techniques

### Technique 1: Enable Logging

**Add logging to track execution:**

```java
ModLog.log("Entering method: attemptAutoBuy");
ModLog.log("Ship: " + shipName + ", ID: " + shipId);
ModLog.log("Offers: " + offers.size());
```

### Technique 2: Use Diagnostic Output

**Write to System.err for early debugging:**

```java
System.err.println("[Mod] Debug: " + message);
```

### Technique 3: Add Breakpoints

**Use IDE debugger:**
- Set breakpoints in hook methods
- Step through code execution
- Inspect variable values

### Technique 4: Test in Isolation

**Create test methods:**

```java
public static void testMethod() {
    // Test specific functionality
    World world = getTestWorld();
    Ship ship = getTestShip();
    attemptAutoBuy(world, ship);
}
```

### Technique 5: Log State Changes

**Track state transitions:**

```java
ModLog.log("State change: nothingToPurchase " + oldValue + " -> " + newValue);
```

## Common Error Messages

### "An item seems to be missing"
**Cause:** Items not reserved before trade creation  
**Fix:** Reserve items using `JobManager.reserveItemForTrade()`

### "Cannot find symbol: class World"
**Cause:** Game classes not in classpath  
**Fix:** Install `spacehaven.jar` to local Maven repository

### "Aspect not found"
**Cause:** Aspect not registered in `aop.xml`  
**Fix:** Add aspect to `<aspects>` section in `aop.xml`

### "Trade already exists"
**Cause:** Duplicate trade creation (race condition)  
**Fix:** Use synchronization and check `createdTradeIds` before creating

### "Insufficient credits"
**Cause:** Not enough credits for trade  
**Fix:** Check credit balance before creating trade, or reduce trade size

## Getting Help

### Check Logs

1. Enable logging: `{enable_logging} = true` in `info.xml`
2. Check log file: `[Game]/mods/AutoBuyerMod/logs/AutoBuyerMod_*.log`
3. Look for error messages and stack traces

### Enable Verbose AspectJ Logging

In `aop.xml`:
```xml
<weaver options="-verbose -showWeaveInfo">
```

### Use Diagnostic Output

Check `System.err` output for early diagnostic messages.

### Community Resources

- Space Haven modding community
- AspectJ documentation
- Maven documentation
- Java documentation

## Next Steps

- Review code examples in `src/main/java/`
- Study **07_Code_Walkthrough.md** for implementation details
- Apply debugging techniques to your own issues

---

**Previous:** [08_Common_Patterns.md](08_Common_Patterns.md)  
**Back to:** [00_Index.md](00_Index.md)

