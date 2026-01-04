# Code Walkthrough - Step-by-Step Explanation

This guide walks through AutoBuyerMod's codebase step-by-step, explaining how components work together and why design decisions were made.

## Architecture Overview

AutoBuyerMod uses a **three-layer architecture**:

1. **Aspect Layer** (`AutoBuyerAspect.java`) - Hooks into game events
2. **Core Layer** (`AutoBuyerCore.java`) - Business logic and trade creation
3. **Config Layer** (`AutoBuyerConfig.java`) - Configuration management

**Data Flow:**
```
Game Event → AspectJ Hook → AutoBuyerCore → Trade Creation → Game
```

## Step 1: Mod Initialization

### AutoBuyerAspect Static Block

When the mod loads, the static block in `AutoBuyerAspect` runs:

```java
static {
    ModLog.setConfig(config);
    loadConfigFromInfoXml();
    scheduleDelayedConfigRecheck();
}
```

**What happens:**
1. **Set config in ModLog** - Allows ModLog to check if logging is enabled
2. **Load config from info.xml** - Reads defaults, user input, and presets
3. **Schedule delayed recheck** - Waits 2 seconds, then rechecks for preset (modloader timing)

**Why this order:**
- Config must be loaded before core logic can run
- Delayed recheck handles modloader's asynchronous config writing
- ModLog needs config to know if logging is enabled

### Configuration Loading Process

```java
private static void loadConfigFromInfoXml() {
    // Step 1: Try to load user input from modloader
    Map<String, String> userInputValues = loadModloaderConfig(modFolder);
    
    // Step 2: Load defaults from info.xml
    Map<String, String> defaultValues = parseInfoXml(infoXmlFile);
    
    // Step 3: Merge (defaults first, then user input overrides)
    Map<String, String> mergedValues = new HashMap<>(defaultValues);
    mergedValues.putAll(userInputValues);
    
    // Step 4: Check for preset (preset overrides everything)
    String presetName = mergedValues.get("{config_preset}");
    if (presetName != null && !presetName.trim().isEmpty()) {
        Map<String, String> presetValues = loadPreset(presetName);
        mergedValues.putAll(presetValues);
    }
    
    // Step 5: Apply final merged config
    config.loadFromConfig(mergedValues);
}
```

**Why this approach:**
- **Defaults first:** Ensures all values have defaults even if user input is missing
- **User input overrides:** Respects user's choices from modloader UI
- **Preset overrides:** Presets are complete configurations that should override everything
- **Delayed recheck:** Handles timing issue where modloader writes config after mod reads it

## Step 2: Ship Arrival Detection

### onShipAdded Hook

When a new ship enters the sector:

```java
@After("execution(* World.addShip(..)) && args(ship) && this(world)")
public void onShipAdded(Ship ship, World world) {
    // Filter: Only process NPC ships
    if (ship.isPlayerShip() || ship.isDerelict() || ship.isClaimable()) {
        return;
    }
    
    // Mark ship as new (starts 10-second delay timer)
    core.markShipAsNew(ship.getShipId());
    
    // Trigger trade check (ship will be skipped until delay passes)
    core.attemptBestTrade(world);
}
```

**Why 10-second delay:**
- New ships may not have offers ready immediately
- Game needs time to initialize ship's trading system
- Prevents failed trade attempts on ships that aren't ready

**Why mark as new:**
- Allows retries (up to 3) before marking as "nothing to purchase"
- Tracks initialization delay timer
- Different handling than established ships

## Step 3: Trade Opportunity Detection

### attemptBestTrade Method

This is the main entry point for finding and creating trades:

```java
public void attemptBestTrade(World world) {
    // Step 1: Check logistics load
    if (logisticsItemCount >= LOGISTICS_PAUSE_THRESHOLD) {
        return; // Too many items waiting, pause trading
    }
    
    // Step 2: Find player station
    Ship playerStation = findPlayerStation(world);
    
    // Step 3: Check credit balance
    if (playerBank.getCreditsAvailable() <= config.getMinCreditBalance()) {
        return; // Credits too low
    }
    
    // Step 4: Get all eligible ships with priority scores
    List<ShipPriority> eligibleShips = getAllEligibleShips(world, playerStation);
    
    // Step 5: Sort by priority (highest score first)
    eligibleShips.sort((a, b) -> Integer.compare(b.score, a.score));
    
    // Step 6: Try to create trade with best ship
    Ship bestShip = eligibleShips.get(0).ship;
    attemptAutoBuy(world, bestShip);
}
```

**Why multi-ship prioritization:**
- Multiple NPC ships may be in sector
- Want to trade with best ship (discounted items, dire need)
- Re-evaluate after each trade to find next best option

## Step 4: Ship Eligibility Check

### shouldAttempt Method

Before attempting a trade, check if ship is eligible:

```java
private boolean shouldAttempt(Ship npcShip, Ship playerStation, World world) {
    // Basic checks
    if (npcShip.isPlayerShip()) return false;
    if (npcShip.isDerelict()) return false;
    if (npcShip.isClaimable()) return false;
    
    // Faction check
    FactionSide npcSide = npcShip.getCurrentOwnerSide();
    if (npcSide.isEnemy(FactionSide.Player)) return false;
    
    // Trade eligibility check
    AiShipInfo aiInfo = npcShip.getAiShipInfo(false);
    if (aiInfo != null && !aiInfo.canTradeWith(playerStation.getShipId())) {
        return false; // Already traded this sector
    }
    
    return true;
}
```

**Why these checks:**
- **Player ship check:** Can't trade with own ships
- **Derelict/claimable:** These ships can't trade
- **Enemy check:** Don't trade with enemies
- **Trade eligibility:** Game tracks if ship already traded this sector

## Step 5: Offer Refresh and Caching

### refreshOffersIfNeeded Method

Gets list of items for sale from NPC ship:

```java
private Map<Integer, Trading.TradeItem> refreshOffersIfNeeded(
    World world, Ship npcShip, Ship playerStation, ShipState state
) {
    // Check if we should refresh (cache empty or cooldown passed)
    if (state.getOffersSnapshot().isEmpty() || 
        state.shouldRefreshOffers(world, config.getRefreshCooldownTicks())) {
        
        // Build mustOffer list (items we're interested in)
        Set<Integer> allItemIds = new HashSet<>();
        allItemIds.addAll(config.getAllTargetStocks().keySet());
        
        Array<TradeItem> mustOffer = new Array<>();
        for (Integer eid : allItemIds) {
            TradeItem item = new TradeItem();
            item.elementaryId = eid;
            mustOffer.add(item);
        }
        
        // Get offers from NPC ship
        TradingHelper.Bank bank = npcShip.getShipCreditBank();
        Array<TradeItem> offers = bank.createOffersList(npcShip, mustOffer, false, null);
        
        // Convert to map and cache
        Map<Integer, TradeItem> offerMap = new HashMap<>();
        for (int i = 0; i < offers.size; i++) {
            TradeItem offer = offers.get(i);
            offerMap.put(offer.elementaryId, offer);
        }
        
        state.setOffersSnapshot(offerMap);
        return offerMap;
    }
    
    // Return cached offers
    return state.getOffersSnapshot();
}
```

**Why caching:**
- **Performance:** Querying offers is expensive
- **Consistency:** Use same offers for entire trade building process
- **Refresh logic:** Refresh when cache is empty or cooldown passed

## Step 6: Trade Building

### buildTradeAgreement Method

This is the core trade creation logic:

```java
private Trading.TradeAgreement buildTradeAgreement(
    World world, Ship npcShip, Ship playerStation, Map<Integer, TradeItem> offers
) {
    // Step 1: Create trade agreement object
    int tradeId = world.getNextElementId();
    Trading.TradeAgreement t = new Trading.TradeAgreement();
    t.id = tradeId;
    t.setPlayerTrade(true);
    t.shipId1 = playerStation.getShipId();
    t.shipId2 = npcShip.getShipId();
    
    // Step 2: Build list of eligible items
    List<ItemOffer> eligibleItems = new ArrayList<>();
    for (Map.Entry<Integer, Integer> entry : config.getAllTargetStocks().entrySet()) {
        int eid = entry.getKey();
        int target = entry.getValue();
        
        TradeItem offer = offers.get(eid);
        if (offer == null) continue;
        
        // Calculate need
        int current = playerStation.getItemsOf(eid, true);
        int queued = getAlreadyQueuedInbound(world, playerStation, eid);
        int need = Math.max(0, target - current - queued);
        if (need <= 0) continue;
        
        // Check markup threshold
        // This determines if we should buy at this markup level based on current stock percentage
        // Premium items CAN be purchased if stock is below the Premium threshold (indicating dire need)
        // Example: Premium threshold = 20% means buy Premium items only if stock < 20% of target
        double stockPercent = ((double)(current + queued) / target) * 100.0;
        int threshold = config.getBuyThreshold(offer.getTradeItemMode());
        if (stockPercent >= threshold) continue; // Stock too high for this markup level
        
        // Add to eligible items
        eligibleItems.add(new ItemOffer(eid, target, offer, need, avail));
    }
    
    // Step 3: Sort by priority (Discounted > Neutral > Markup > Premium)
    // Premium items have lowest priority but can still be purchased if stock is below threshold
    eligibleItems.sort((a, b) -> {
        int priorityA = getTradeModePriority(a.offer.getTradeItemMode());
        int priorityB = getTradeModePriority(b.offer.getTradeItemMode());
        return Integer.compare(priorityA, priorityB);
    });
    
    // Step 4: Build trade (up to 10 items max)
    int remainingCapacity = 10;
    int totalCost = 0;
    
    for (ItemOffer itemOffer : eligibleItems) {
        if (remainingCapacity <= 0) break;
        
        // Reserve items on NPC ship
        int actualReservedQty = 0;
        for (int i = 0; i < desiredQty; i++) {
            if (!npcJobManager.reserveItemForTrade(eid, tradeId)) {
                break; // Item not available
            }
            actualReservedQty++;
        }
        
        if (actualReservedQty == 0) continue;
        
        // Calculate cost
        int itemCost = 0;
        for (int i = 0; i < actualReservedQty; i++) {
            int unitPrice = npcBank.getSellPriceToPlayer(eid, avail - i, mode);
            itemCost += unitPrice;
        }
        
        // Check if we can afford it
        if (totalCost + itemCost > playerBank.getCreditsAvailable()) {
            // Free reservations and break
            npcJobManager.cancelAllReservationsForTrade(tradeId);
            break;
        }
        
        // Check max credits per trade limit
        if (config.getMaxCreditsPerTrade() < Integer.MAX_VALUE) {
            if (totalCost + itemCost > config.getMaxCreditsPerTrade()) {
                npcJobManager.cancelAllReservationsForTrade(tradeId);
                break;
            }
        }
        
        // Add item to trade
        t.toShip1.add(new TradeItem(eid, actualReservedQty));
        totalCost += itemCost;
        remainingCapacity -= actualReservedQty;
    }
    
    // Step 5: Finalize trade
    if (t.toShip1.size == 0) {
        // No items in trade - free all reservations
        npcJobManager.cancelAllReservationsForTrade(tradeId);
        return null;
    }
    
    t.creditsToShip2 = totalCost;
    return t;
}
```

**Key Design Decisions:**

1. **Reserve items first:** Must reserve before adding to trade (game requirement)
2. **Per-unit price calculation:** Prices may vary by quantity
3. **Capacity limit:** Maximum 10 items per trade (game limit)
4. **Credit checks:** Check both available credits and max per trade limit
5. **Free reservations on failure:** If trade can't be completed, free all reservations

## Step 7: Trade Creation

### attemptAutoBuy Method

Creates and commits the trade:

```java
public boolean attemptAutoBuy(World world, Ship npcShip) {
    // Step 1: Validate ship eligibility
    if (!shouldAttempt(npcShip, playerStation, world)) {
        return false;
    }
    
    // Step 2: Check trade limits
    if (state.getTotalTradesCreated() >= 8) {
        state.setNothingToPurchase(true);
        return false; // Max trades per ship reached
    }
    
    int activeTrades = countActiveTradesWithNpc(world, npcShip.getShipId(), playerStation.getShipId());
    if (activeTrades >= 4) {
        return false; // Max concurrent trades reached
    }
    
    // Step 3: Check cooldown
    if (state.isInCooldown(world, config.getCooldownTicks())) {
        return false;
    }
    
    // Step 4: Get offers
    Map<Integer, TradeItem> offers = refreshOffersIfNeeded(world, npcShip, playerStation, state);
    
    // Step 5: Build trade agreement
    Trading.TradeAgreement trade = buildTradeAgreement(world, npcShip, playerStation, offers);
    if (trade == null) {
        return false; // Couldn't build trade
    }
    
    // Step 6: Reserve credits and create trade
    synchronized (tradeCreationLock) {
        // Double-check for duplicates (race condition protection)
        if (createdTradeIds.contains(trade.id)) {
            return false;
        }
        
        // Reserve credits
        playerBank.reserve(trade.creditsToShip2);
        
        // Create trade
        world.addNewTradeAgreement(trade);
        createdTradeIds.add(trade.id);
        
        // Update state
        state.incrementTotalTradesCreated();
        state.clearOffersSnapshot(); // Invalidate cache
        return true;
    }
}
```

**Why synchronization:**
- **Race conditions:** Multiple hooks could try to create trades simultaneously
- **Duplicate prevention:** Check trade ID before creating
- **State consistency:** Ensure state updates are atomic

## Step 8: Trade Completion

### onTradeDone Hook

When a trade completes:

```java
@After("execution(* World.setTradeDone(..)) && args(trade) && this(world)")
public void onTradeDone(Trading.TradeAgreement trade, World world) {
    // Validate trade
    if (trade == null || !trade.isPlayerTrade()) {
        return;
    }
    
    // Identify NPC ship
    Ship npcShip = // Determine from trade.shipId1 and trade.shipId2
    
    // Clean up
    core.removeTradeId(trade.id);
    core.resetShipFlags(npcShip.getShipId(), npcShip);
    
    // Trigger next trade
    core.attemptBestTrade(world);
}
```

**Why trigger next trade:**
- Trade slot freed up (was 4/4, now 3/4)
- Want to immediately start next trade
- Re-evaluate all ships to find best option

## Step 9: Priority Scoring

### calculateShipPriority Method

Scores ships based on their trade value:

```java
private ShipPriority calculateShipPriority(World world, Ship npcShip, Ship playerStation, ShipState state) {
    Map<Integer, TradeItem> offers = refreshOffersIfNeeded(world, npcShip, playerStation, state);
    
    int discountedItems = 0;
    int totalNeedValue = 0;
    int direNeedItems = 0;
    
    // Analyze buying opportunities
    for (Map.Entry<Integer, Integer> entry : config.getAllTargetStocks().entrySet()) {
        int eid = entry.getKey();
        int target = entry.getValue();
        
        TradeItem offer = offers.get(eid);
        if (offer == null) continue;
        
        // Calculate need
        int current = playerStation.getItemsOf(eid, true);
        int queued = getAlreadyQueuedInbound(world, playerStation, eid);
        int need = Math.max(0, target - current - queued);
        if (need <= 0) continue;
        
        // Check markup threshold
        // Premium items are included here - they can be purchased if stock is below Premium threshold
        double stockPercent = ((double)(current + queued) / target) * 100.0;
        int threshold = config.getBuyThreshold(offer.getTradeItemMode());
        if (stockPercent >= threshold) continue;
        
        // Count discounted items
        if (offer.getTradeItemMode() == TradeItemMode.Discounted) {
            discountedItems++;
        }
        
        // Count dire need items (stock < 20% of target)
        // Note: Premium items often trigger dire need since their threshold is typically 20%
        if (stockPercent < 20.0) {
            direNeedItems++;
        }
        
        // Add to total need value
        // Premium items have priority 3 (lowest), but are still included in scoring
        int priority = getTradeModePriority(offer.getTradeItemMode());
        int value = Math.min(need, avail) * (4 - priority);
        totalNeedValue += value;
    }
    
    // Calculate score
    int score = (direNeedItems * 5000) + (discountedItems * 100) + 
                (totalNeedValue / 10) - (activeTrades * 20);
    
    return new ShipPriority(npcShip, score, discountedItems, totalNeedValue, activeTrades);
}
```

**Scoring Formula Explained:**
- **Dire need bonus (5000):** Massive priority for items < 20% of target
- **Discounted bonus (100):** Priority for discounted items
- **Need value (totalNeedValue / 10):** Weighted by trade mode priority
- **Active trades penalty (-20):** Prefer ships with fewer active trades

## Step 10: State Management

### ShipState Class

Tracks per-ship state:

```java
private static class ShipState {
    private Map<Integer, TradeItem> offersSnapshot;      // Cached offers
    private int lastOffersRefreshTime;                    // When offers were refreshed
    private long lastAttemptTimeMillis;                   // When we last tried to trade
    private boolean maxTradesReached;                     // Has 4 concurrent trades
    private boolean nothingToPurchase;                    // No items to buy
    private int totalTradesCreated;                       // Total trades with this ship
    private boolean isNewShip;                            // New ship (10-second delay)
    private int newShipRetryCount;                        // Retry attempts
    private long newShipFirstSeenTimeMillis;              // When ship was first seen
}
```

**Why track all this:**
- **Offers cache:** Avoid redundant queries
- **Cooldown tracking:** Prevent too-frequent trade attempts
- **Trade limits:** Enforce 8 trades per ship limit
- **New ship handling:** Manage initialization delay and retries

## Design Patterns Used

### Pattern 1: Early Returns

```java
if (ship == null) return false;
if (ship.isPlayerShip()) return false;
if (ship.isDerelict()) return false;
// Continue with valid ship
```

**Why:** Reduces nesting, makes code more readable

### Pattern 2: Try-Catch Wrapping

```java
try {
    // Risky operation
} catch (Exception e) {
    ModLog.log("Error: " + e.getMessage());
    ModLog.log(e);
    // Don't crash game
}
```

**Why:** Mods must never crash the game, even on errors

### Pattern 3: State Caching

```java
if (state.getOffersSnapshot().isEmpty() || shouldRefresh()) {
    // Refresh offers
    state.setOffersSnapshot(offers);
} else {
    // Use cached offers
    return state.getOffersSnapshot();
}
```

**Why:** Performance optimization, consistency

### Pattern 4: Synchronization

```java
synchronized (tradeCreationLock) {
    // Critical section
    if (createdTradeIds.contains(trade.id)) {
        return false; // Duplicate
    }
    createdTradeIds.add(trade.id);
    world.addNewTradeAgreement(trade);
}
```

**Why:** Prevent race conditions in concurrent execution

## Next Steps

- Review **08_Common_Patterns.md** for more design patterns
- Read **09_Troubleshooting.md** for debugging techniques
- Study the actual code in `src/main/java/` with these concepts in mind

---

**Previous:** [06_Core_Concepts.md](06_Core_Concepts.md)  
**Next:** [08_Common_Patterns.md](08_Common_Patterns.md)

