# Game Hooks Used - AspectJ Hooks in AutoBuyerMod

This reference guide documents all AspectJ hooks used in AutoBuyerMod, explaining when they fire, what they do, and why they're needed.

## Hook Overview

AutoBuyerMod uses **6 AspectJ hooks** to intercept game events:

1. **Trade Completion Hook** - Detects when trades finish
2. **Trade Cancellation Hook** - Detects when trades are cancelled
3. **Ship Jumped Hook** - Detects when ships leave the sector
4. **Ship Added Hook** - Detects when new ships arrive
5. **Entity Boarded Hook** - Detects when NPC shuttles dock
6. **Logistics Swamped Hook** - Detects when logistics get overwhelmed

## Hook 1: Trade Completion

### Pointcut
```java
@After("execution(* fi.bugbyte.spacehaven.world.World.setTradeDone(..)) && args(tradeAgreement) && this(world)")
public void onTradeDone(Trading.TradeAgreement tradeAgreement, World world)
```

### When It Fires
- **Trigger:** Game calls `World.setTradeDone()` when a trade completes successfully
- **Frequency:** Once per completed trade
- **Timing:** After trade items are transferred and credits are exchanged

### What It Does
1. **Validates trade:** Checks if trade is a player trade (not NPC-to-NPC)
2. **Identifies NPC ship:** Determines which ship was the NPC (the one we bought from)
3. **Cleans up tracking:** Removes trade ID from tracking to prevent memory leaks
4. **Resets ship flags:** Clears "nothing to purchase" flag (trade slot freed up)
5. **Triggers next trade:** Calls `attemptBestTrade()` to find the next best trade opportunity

### Why It's Needed
- **Trade slots are limited:** Only 4 concurrent trades allowed
- **Automatic continuation:** When one trade finishes, we want to start another immediately
- **State management:** Need to clean up tracking and reset ship state

### Code Flow
```
Trade completes → Hook fires → Clean up → Reset flags → Find next best trade
```

## Hook 2: Trade Cancellation

### Pointcut
```java
@After("execution(* fi.bugbyte.spacehaven.world.World.cancelTrade(..)) && args(tradeAgreement, playerJumped) && this(world)")
public void onTradeCancelled(Trading.TradeAgreement tradeAgreement, boolean playerJumped, World world)
```

### When It Fires
- **Trigger:** Game calls `World.cancelTrade()` when a trade is cancelled
- **Frequency:** Once per cancelled trade
- **Timing:** After trade is cancelled (items not transferred, credits not exchanged)

### What It Does
1. **Validates trade:** Checks if trade is a player trade
2. **Logs cancellation:** Detailed logging of why trade was cancelled
3. **Checks player jump:** If `playerJumped` is true, ship left sector (don't retry)
4. **Cleans up tracking:** Removes trade ID from tracking
5. **Resets ship flags:** Clears flags so ship can be retried
6. **Triggers next trade:** Calls `attemptBestTrade()` if ship is still valid

### Why It's Needed
- **Error recovery:** Trades can fail for various reasons (items unavailable, credits insufficient)
- **Slot management:** Cancelled trades free up slots that should be reused
- **State cleanup:** Need to reset ship state so it can be retried

### Special Handling
- **Player jump detection:** If player jumped, ship is gone - don't retry
- **Ship state validation:** Only retry if ship is still valid (not derelict/claimable)

## Hook 3: Ship Jumped

### Pointcut
```java
@After("execution(* fi.bugbyte.spacehaven.world.World.shipJumped(..)) && args(ship) && this(world)")
public void onShipJumped(Ship ship, World world)
```

### When It Fires
- **Trigger:** Game calls `World.shipJumped()` when a ship leaves the sector
- **Frequency:** Once per ship that leaves
- **Timing:** After ship has left the sector

### What It Does
1. **Validates ship:** Checks if ship is not null
2. **Logs departure:** Logs ship name and ID for debugging
3. **Flushes state:** Removes all tracking data for this ship (state, offers cache, etc.)

### Why It's Needed
- **Memory management:** Ships that leave should have their state cleaned up
- **Prevent stale data:** Old ship state could cause issues if ship returns later
- **Fresh start:** If ship returns, it should be treated as a new ship

### State Cleanup
- Removes ship from `shipStates` map
- Clears offers cache
- Resets all flags and counters

## Hook 4: Ship Added

### Pointcut
```java
@After("execution(* fi.bugbyte.spacehaven.world.World.addShip(..)) && args(ship) && this(world)")
public void onShipAdded(Ship ship, World world)
```

### When It Fires
- **Trigger:** Game calls `World.addShip()` when a new ship enters the sector
- **Frequency:** Once per ship that arrives
- **Timing:** After ship is added to the world

### What It Does
1. **Validates ship:** Checks if ship is not null
2. **Filters NPC ships:** Only processes NPC ships (not player ships, derelicts, claimables)
3. **Marks as new:** Sets ship state to "new" (starts 10-second initialization delay)
4. **Triggers trade check:** Calls `attemptBestTrade()` to evaluate all ships

### Why It's Needed
- **Immediate response:** Want to start trading as soon as NPC ships arrive
- **Initialization delay:** New ships need time for offers to initialize (10-second delay)
- **Multi-ship prioritization:** When new ship arrives, re-evaluate all ships to find best trade

### Initialization Delay
- **Why:** New ships may not have offers ready immediately
- **How:** Ship marked as "new" and checked after 10 seconds
- **Retry logic:** Up to 3 retries before marking as "nothing to purchase"

## Hook 5: Entity Boarded

### Pointcut
```java
@After("execution(* fi.bugbyte.spacehaven.world.Ship.entityBoarded(..)) && args(entity, at, moveToCrewSpot, fromCraft) && this(ship)")
public void onEntityBoarded(Entity entity, BaseShipDockingPort at, boolean moveToCrewSpot, Craft fromCraft, Ship ship)
```

### When It Fires
- **Trigger:** Game calls `Ship.entityBoarded()` when an entity boards a ship
- **Frequency:** Once per entity that boards
- **Timing:** After entity has boarded the ship

### What It Does
1. **Validates player station:** Only processes if `ship` is a player station
2. **Checks for shuttle:** Only processes if entity came from a craft (shuttle)
3. **Filters shuttle type:** Only processes if craft is a Shuttle (not other craft types)
4. **Filters NPC shuttles:** Only processes if shuttle is from NPC faction (not player)
5. **Filters NPC entities:** Only processes if entity is from NPC faction (not player crew)
6. **Triggers trade check:** Calls `attemptBestTrade()` to recheck ships

### Why It's Needed
- **Shuttle docking detection:** NPC shuttles docking indicates ships are ready to trade
- **Timing workaround:** Helps with single-ship scenarios where no active trades exist
- **Reliable trigger:** More reliable than polling or other methods

### Complex Filtering
This hook has many filters because `entityBoarded` fires for many events:
- Player crew boarding player station (not relevant)
- NPC crew boarding NPC ships (not relevant)
- NPC crew boarding player station from NPC shuttle (relevant!)

## Hook 6: Logistics Swamped

### Pointcut
```java
@After("execution(* fi.bugbyte.spacehaven.gui.Indicators.ShipTileIconManager.setLogisticsSwamped(boolean)) && args(swamped) && target(tileIconManager)")
public void onLogisticsSwamped(boolean swamped, ShipTileIconManager tileIconManager)
```

### When It Fires
- **Trigger:** Game calls `ShipTileIconManager.setLogisticsSwamped()` when logistics state changes
- **Frequency:** When logistics overwhelmed state changes (true → false or false → true)
- **Timing:** After game updates logistics indicator

### What It Does
1. **Gets ship object:** Uses reflection to access ship from `TileIconManager`
2. **Gets item count:** Reads actual item count from `JobManager`
3. **Updates tracking:** Calls `updateLogisticsItemCount()` with actual count
4. **Fallback handling:** If reflection fails, uses conservative estimate

### Why It's Needed
- **Logistics awareness:** Too many items waiting for logistics can cause performance issues
- **Gradual slowdown:** Mod slows trading at 20 items, pauses at 40, cancels at 60
- **Prevents overload:** Avoids creating more trades when logistics are overwhelmed

### Logistics Thresholds
- **20 items:** Slow down trading (skip some trade attempts)
- **40 items:** Pause trading (don't create new trades)
- **60 items:** Cancel all trades and release all ships

### Reflection Usage
This hook uses reflection because `ShipTileIconManager` doesn't expose the ship directly:
```java
Field shipField = tileIconManager.getClass().getDeclaredField("ship");
shipField.setAccessible(true);
Ship ship = (Ship) shipField.get(tileIconManager);
```

**Why reflection:**
- Game class doesn't provide public accessor
- Reflection allows accessing private fields
- Fallback to conservative estimate if reflection fails

## Hook Design Patterns

### Pattern 1: Null Checking
All hooks start with null checks:
```java
if (tradeAgreement == null || !tradeAgreement.isPlayerTrade()) {
    return;
}
```

### Pattern 2: Try-Catch Wrapping
All hooks wrap code in try-catch:
```java
try {
    // Hook logic
} catch (Exception e) {
    ModLog.log("Exception in hook: " + e.getMessage());
    ModLog.log(e);
}
```

### Pattern 3: Early Returns
Hooks use early returns to filter unwanted calls:
```java
if (ship.isPlayerShip() || ship.isDerelict()) {
    return; // Skip player ships and derelicts
}
```

### Pattern 4: State Management
Hooks update state and trigger actions:
```java
core.removeTradeId(trade.id);        // Clean up
core.resetShipFlags(shipId, ship);   // Reset state
core.attemptBestTrade(world);         // Trigger action
```

## Finding Hooks to Use

### Method 1: Search Decompiled Code
1. Decompile `spacehaven.jar`
2. Search for method names that match events you need
3. Check method signatures
4. Create pointcut matching the signature

### Method 2: Trial and Error
1. Create hook with best-guess pointcut
2. Add logging to verify hook fires
3. Adjust pointcut if hook doesn't fire
4. Test with different method signatures

### Method 3: Community Knowledge
1. Check modding community for known hooks
2. Study other mods' hook usage
3. Ask experienced modders

## Common Hook Issues

### Issue: Hook Doesn't Fire
**Causes:**
- Pointcut doesn't match method signature
- Aspect not registered in `aop.xml`
- Package name incorrect

**Solutions:**
- Enable verbose AspectJ logging (`-verbose -showWeaveInfo`)
- Check method signature matches exactly
- Verify aspect is in `aop.xml`

### Issue: Hook Fires Too Often
**Causes:**
- Pointcut too broad (matches too many methods)
- Not filtering unwanted calls

**Solutions:**
- Narrow pointcut (specific method, not wildcard)
- Add filters in hook method (early returns)

### Issue: Hook Causes Performance Issues
**Causes:**
- Expensive operations in hook
- Hook fires very frequently

**Solutions:**
- Defer expensive work (schedule for later)
- Add throttling (only process every N calls)
- Optimize hook logic

## Next Steps

- Review **06_Core_Concepts.md** to understand game systems these hooks interact with
- Study **07_Code_Walkthrough.md** to see how hooks integrate with core logic
- Read **08_Common_Patterns.md** for best practices

---

**Previous:** [04_Decompiled_Research_Files.md](04_Decompiled_Research_Files.md)  
**Next:** [06_Core_Concepts.md](06_Core_Concepts.md)

