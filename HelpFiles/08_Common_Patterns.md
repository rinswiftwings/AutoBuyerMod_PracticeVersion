# Common Patterns - Best Practices and Coding Patterns

This reference guide documents common coding patterns and best practices used in AutoBuyerMod.

## Error Handling Patterns

### Pattern 1: Try-Catch Wrapping

**Always wrap risky operations in try-catch:**

```java
@After("execution(* World.method(..))")
public void hook() {
    try {
        // Your code that might fail
        processSomething();
    } catch (Exception e) {
        ModLog.log("Error in hook: " + e.getMessage());
        ModLog.log(e); // Log stack trace
        // Don't re-throw - mods must never crash the game
    }
}
```

**Why:**
- Game stability: Mods must never crash the game
- Debugging: Logging errors helps identify issues
- Smart Retry: Continue with reduced functionality

### Pattern 2: Null Checking

**Always check for null before use:**

```java
if (ship == null || world == null) {
    return; // Early exit
}

// Safe to use ship and world here
processShip(ship, world);
```

**Why:**
- Prevents NullPointerException
- Early returns reduce nesting
- Makes code more readable

### Pattern 3: Validation Before Use

**Validate objects before using their methods:**

```java
if (ship != null && !ship.isPlayerShip() && !ship.isDerelict()) {
    // Safe to use ship
    int items = ship.getItemsOf(itemId, true);
}
```

**Why:**
- Prevents exceptions from invalid objects
- Filters unwanted cases early
- Clear intent in code

## State Management Patterns

### Pattern 4: Per-Object State Tracking

**Use maps to track state per object:**

```java
private final Map<Integer, ShipState> shipStates = new HashMap<>();

private ShipState getShipState(int shipId) {
    return shipStates.computeIfAbsent(shipId, k -> new ShipState());
}
```

**Why:**
- Each ship needs independent state
- Easy lookup by ID
- Automatic creation on first access

### Pattern 5: State Flags

**Use boolean flags to track state:**

```java
private boolean nothingToPurchase = false;
private boolean maxTradesReached = false;
private boolean isNewShip = false;

public void setNothingToPurchase(boolean value) {
    this.nothingToPurchase = value;
}
```

**Why:**
- Simple state tracking
- Easy to check and update
- Clear boolean semantics

### Pattern 6: State Cleanup

**Clean up state when objects are removed:**

```java
@After("execution(* World.shipJumped(..)) && args(ship)")
public void onShipJumped(Ship ship) {
    // Clean up state when ship leaves
    shipStates.remove(ship.getShipId());
}
```

**Why:**
- Prevents memory leaks
- Removes stale data
- Fresh start if object returns

## Caching Patterns

### Pattern 7: Offer Caching

**Cache expensive queries:**

```java
private Map<Integer, TradeItem> offersSnapshot = new HashMap<>();

private Map<Integer, TradeItem> refreshOffersIfNeeded(...) {
    if (offersSnapshot.isEmpty() || shouldRefresh()) {
        // Expensive query
        Map<Integer, TradeItem> offers = queryOffers();
        offersSnapshot = offers;
        return offers;
    }
    return offersSnapshot; // Use cache
}
```

**Why:**
- Performance: Queries are expensive
- Consistency: Use same data for entire operation
- Refresh logic: Update when needed

### Pattern 8: Cache Invalidation

**Invalidate cache when data changes:**

```java
// After creating trade, invalidate offers cache
state.clearOffersSnapshot();

// Why: NPC stock changed, need fresh data for next trade
```

**Why:**
- Data consistency: Cache may be stale
- Fresh data: Need current availability
- Prevents using outdated information

## Synchronization Patterns

### Pattern 9: Synchronized Blocks

**Use synchronized blocks for critical sections:**

```java
private final Object tradeCreationLock = new Object();

synchronized (tradeCreationLock) {
    // Critical section
    if (createdTradeIds.contains(trade.id)) {
        return false; // Duplicate
    }
    createdTradeIds.add(trade.id);
    world.addNewTradeAgreement(trade);
}
```

**Why:**
- Race conditions: Multiple threads may access simultaneously
- Atomic operations: Ensure operations complete together
- Thread safety: Prevent data corruption

### Pattern 10: Double-Check Pattern

**Check conditions twice (before and inside synchronized block):**

```java
// First check (outside synchronized - fast path)
if (createdTradeIds.contains(trade.id)) {
    return false;
}

synchronized (tradeCreationLock) {
    // Second check (inside synchronized - accurate)
    if (createdTradeIds.contains(trade.id)) {
        return false;
    }
    createdTradeIds.add(trade.id);
}
```

**Why:**
- Performance: Fast path for common case
- Accuracy: Synchronized check ensures correctness
- Race condition prevention: Handles concurrent access

## Logging Patterns

### Pattern 11: Conditional Logging

**Check if logging is enabled before expensive operations:**

```java
if (config.isLoggingEnabled()) {
    ModLog.log("Expensive log message: " + expensiveStringOperation());
}
```

**Why:**
- Performance: Avoid expensive operations when logging disabled
- User choice: Respect user's logging preference
- Efficiency: Only do work when needed

### Pattern 12: Structured Logging

**Use consistent log message format:**

```java
ModLog.log("AutoBuyerCore: Created trade " + trade.id + 
          " with ship " + shipName + " (ID: " + shipId + ") " +
          "for " + credits + " credits");
```

**Why:**
- Readability: Consistent format is easier to parse
- Debugging: Structured info helps identify issues
- Searchability: Easy to grep for specific patterns

## Filtering Patterns

### Pattern 13: Early Returns

**Use early returns to filter unwanted cases:**

```java
public boolean shouldAttempt(Ship ship) {
    if (ship == null) return false;
    if (ship.isPlayerShip()) return false;
    if (ship.isDerelict()) return false;
    if (ship.isClaimable()) return false;
    
    // Continue with valid ship
    return true;
}
```

**Why:**
- Readability: Reduces nesting
- Performance: Exit early for invalid cases
- Clarity: Each condition is clear

### Pattern 14: Compound Filters

**Combine multiple conditions:**

```java
if (ship != null && !ship.isPlayerShip() && !ship.isDerelict() && 
    ship.getCurrentOwnerSide() != FactionSide.Enemy) {
    // Process valid NPC ship
}
```

**Why:**
- Efficiency: Single if statement
- Readability: All conditions visible
- Performance: Short-circuit evaluation

## Resource Management Patterns

### Pattern 15: Reservation Management

**Reserve resources before use, free on failure:**

```java
// Reserve items
int reserved = 0;
for (int i = 0; i < desiredQty; i++) {
    if (!jobManager.reserveItemForTrade(itemId, tradeId)) {
        break; // Can't reserve more
    }
    reserved++;
}

// If trade fails, free reservations
if (trade == null) {
    for (int i = 0; i < reserved; i++) {
        jobManager.freeItemReservationForTrade(itemId, tradeId);
    }
}
```

**Why:**
- Resource cleanup: Free resources on failure
- Game requirement: Must reserve before using
- Prevents resource leaks

### Pattern 16: Credit Reservation

**Reserve credits before creating trade:**

```java
// Check if we can afford it
if (playerBank.getCreditsAvailable() < trade.creditsToShip2) {
    return false; // Can't afford
}

// Reserve credits
playerBank.reserve(trade.creditsToShip2);

// Create trade (game will use reserved credits)
world.addNewTradeAgreement(trade);
```

**Why:**
- Atomic operation: Reserve before commit
- Prevents overspending: Check before reserve
- Game requirement: Credits must be reserved

## Priority and Scoring Patterns

### Pattern 17: Priority Scoring

**Score items/ships by multiple factors:**

```java
int score = 0;
score += direNeedItems * 5000;        // Massive bonus for dire need
score += discountedItems * 100;       // Bonus for discounted items
score += totalNeedValue / 10;          // Weighted need value
score -= activeTrades * 20;            // Penalty for active trades
```

**Why:**
- Multi-factor decisions: Consider multiple criteria
- Weighted importance: Different factors have different weights
- Clear prioritization: Higher score = higher priority

### Pattern 18: Sorting by Priority

**Sort collections by priority score:**

```java
List<ShipPriority> ships = getAllEligibleShips();
ships.sort((a, b) -> Integer.compare(b.score, a.score));
// Highest score first
Ship bestShip = ships.get(0).ship;
```

**Why:**
- Always pick best option
- Easy to find top N items
- Clear ordering

## Timing Patterns

### Pattern 19: Cooldown Management

**Track last attempt time and enforce cooldown:**

```java
private long lastAttemptTimeMillis = 0;

public boolean isInCooldown(World world, int cooldownTicks) {
    if (lastAttemptTimeMillis == 0) {
        return false; // Never attempted
    }
    long cooldownMillis = (long)(cooldownTicks * 16.67); // Convert ticks to ms
    long timeSinceLastAttempt = System.currentTimeMillis() - lastAttemptTimeMillis;
    return timeSinceLastAttempt < cooldownMillis;
}
```

**Why:**
- Rate limiting: Prevent too-frequent attempts
- Performance: Avoid spamming game systems
- User control: Configurable cooldown

### Pattern 20: Delayed Operations

**Schedule operations for later:**

```java
new Thread(() -> {
    try {
        Thread.sleep(2000); // Wait 2 seconds
        // Do delayed operation
        recheckConfig();
    } catch (InterruptedException e) {
        // Handle interruption
    }
}, "DelayedOperation").start();
```

**Why:**
- Timing issues: Handle asynchronous operations
- Non-blocking: Don't block main thread
- Retry logic: Try again after delay

## Configuration Patterns

### Pattern 21: Default Values

**Always provide defaults:**

```java
private int maxCreditsPerTrade = 2000; // Default value

public void loadFromConfig(Map<String, String> configValues) {
    String value = configValues.get("{max_credits_per_trade}");
    if (value != null) {
        maxCreditsPerTrade = Integer.parseInt(value);
    }
    // If not found, use default
}
```

**Why:**
- User-friendly: Works without configuration
- Safety: Always has valid value
- Flexibility: User can override

### Pattern 22: Configuration Merging

**Merge defaults, user input, and presets:**

```java
// Step 1: Start with defaults
Map<String, String> merged = new HashMap<>(defaults);

// Step 2: User input overrides defaults
merged.putAll(userInput);

// Step 3: Preset overrides everything
merged.putAll(presetValues);
```

**Why:**
- Priority system: Clear override order
- Flexibility: Multiple configuration sources
- User control: User input respected

## Best Practices Summary

### Do's

**Always use try-catch** in hooks and core methods  
**Check for null** before using objects  
**Validate conditions** before operations  
**Log errors** for debugging  
**Clean up resources** on failure  
**Use synchronization** for shared state  
**Cache expensive operations**  
**Invalidate cache** when data changes  
**Provide default values** for configuration  
**Use early returns** to reduce nesting  

### Don'ts

**Don't crash the game** - catch all exceptions  
**Don't block game thread** - use async operations for long tasks  
**Don't spam logs** - use conditional logging  
**Don't ignore errors** - log and handle gracefully  
**Don't create memory leaks** - clean up state  
**Don't assume objects exist** - always check for null  
**Don't use stale data** - invalidate cache when needed  
**Don't ignore race conditions** - use synchronization  
**Don't hardcode values** - use configuration  
**Don't skip validation** - validate before use  

## Next Steps

- Review **09_Troubleshooting.md** for debugging techniques
- Study actual code in `src/main/java/` to see these patterns
- Apply patterns to your own mod development

---

**Previous:** [07_Code_Walkthrough.md](07_Code_Walkthrough.md)  
**Next:** [09_Troubleshooting.md](09_Troubleshooting.md)

