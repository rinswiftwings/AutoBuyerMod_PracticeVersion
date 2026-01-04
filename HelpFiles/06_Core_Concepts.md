# Core Concepts - Trade System, World/Ship Objects, Configuration

This reference guide explains the core concepts used in AutoBuyerMod: the trading system, World/Ship objects, and configuration loading.

## Trading System

### Trade Agreement Structure

A `Trading.TradeAgreement` represents a trade between two ships:

```java
Trading.TradeAgreement trade = new Trading.TradeAgreement();
trade.id = world.getNextElementId();           // Unique trade ID
trade.shipId1 = playerStation.getShipId();     // First ship (player)
trade.shipId2 = npcShip.getShipId();           // Second ship (NPC)
trade.creditsToShip1 = 0;                      // Credits NPC pays player
trade.creditsToShip2 = 100;                    // Credits player pays NPC
trade.toShip1.add(new Trading.TradeItem(itemId, quantity)); // Items to player
trade.toShip2.add(new Trading.TradeItem(itemId, quantity)); // Items to NPC
trade.setPlayerTrade(true);                    // Mark as player trade
```

### Trade Lifecycle

1. **Create Trade Agreement** - Build `TradeAgreement` object with items and credits
2. **Reserve Items** - Use `JobManager.reserveItemForTrade()` to reserve items
3. **Reserve Credits** - Use `Bank.reserve()` to reserve credits
4. **Add Trade** - Call `World.addNewTradeAgreement()` to create the trade
5. **Game Processes** - Game handles item transfer, credit exchange, logistics
6. **Trade Completes** - Game calls `World.setTradeDone()` when finished
7. **Hook Fires** - Our `onTradeDone` hook detects completion

### Trade Limits

**Concurrent Trades:**
- Maximum 4 concurrent trades per ship pair
- Enforced by checking `world.getTrades()` before creating new trade
- Prevents overwhelming the game's trade system

**Total Trades Per Ship:**
- Maximum 8 trades per NPC ship per visit
- Tracks via `ShipState.totalTradesCreated`
- Prevents excessive trading with single ship

**Credit Limits:**
- `maxCreditsPerTrade` - Maximum credits per single trade
- `minCreditBalance` - Minimum credits required to initiate trades
- Both configurable in `info.xml`

### Trade Item Modes

Items can be sold at different price levels:

- **Discounted** - Item sold at discount (best price for buyer)
- **Neutral** - Normal price
- **Markup** - Item sold at markup (higher price)
- **Premium** - Item sold at premium price (highest price, but can be purchased if dire need)

**Threshold System:**
- Buy Discounted items if stock < 100% of target (always buy if under target)
- Buy Normal items if stock < 70% of target
- Buy Markup items if stock < 40% of target
- Buy Premium items if stock < 20% of target (only when dire need - stock is critically low)

### Price Calculation

```java
TradingHelper.Bank npcBank = npcShip.getShipCreditBank();
int price = npcBank.getSellPriceToPlayer(itemId, quantity, TradeItemMode.Neutral);
```

**Price factors:**
- Base item value
- Trade item mode (Discounted/Neutral/Markup)
- Quantity
- NPC's current stock levels

## World Object

### Getting the World

The `World` object is the central game state manager:

```java
// From AspectJ hook
@After("execution(* World.method(..)) && this(world)")
public void hook(World world) {
    // Use world object
}

// From Ship object
World world = ship.getWorld();
```

### Key World Methods

**Ship Management:**
```java
Array<Ship> ships = world.getShips();           // Get all ships
Ship ship = world.getShip(shipId);              // Get specific ship
```

**Trade Management:**
```java
Array<TradeAgreement> trades = world.getTrades(); // Get all trades
world.addNewTradeAgreement(trade);              // Create new trade
world.setTradeDone(trade);                      // Mark trade complete
world.cancelTrade(trade, playerJumped);          // Cancel trade
```

**Credit Management:**
```java
TradingHelper.Bank playerBank = world.getPlayerBank();
int credits = playerBank.getCreditsAvailable();
playerBank.reserve(amount);                     // Reserve credits
```

**ID Generation:**
```java
int tradeId = world.getNextElementId();         // Generate unique ID
```

### Finding Player Station

```java
private Ship findPlayerStation(World world) {
    Array<Ship> ships = world.getShips();
    for (int i = 0; i < ships.size; i++) {
        Ship ship = ships.get(i);
        if (ship.isPlayerShip() && ship.isStation()) {
            return ship;
        }
    }
    return null;
}
```

## Ship Object

### Ship Identification

```java
int shipId = ship.getShipId();                  // Unique ship ID
String name = ship.getName();                   // Ship name (may throw exception)
boolean isPlayer = ship.isPlayerShip();         // Is player-controlled
boolean isStation = ship.isStation();           // Is a station
```

### Ship State Checks

```java
boolean isDerelict = ship.isDerelict();         // Is derelict (abandoned)
boolean isClaimable = ship.isClaimable();       // Can be claimed
FactionSide side = ship.getCurrentOwnerSide();  // Faction side
```

### Ship Inventory

```java
int count = ship.getItemsOf(itemId, includeReserved);
// itemId: Elementary ID (e.g., 16 for Water)
// includeReserved: true = total, false = available
```

### Ship Trading

```java
TradingHelper.Bank bank = ship.getShipCreditBank();
int credits = bank.getCreditsAvailable();
int price = bank.getSellPriceToPlayer(itemId, qty, mode);
```

### Ship Job Management

```java
JobManager jobManager = ship.getJobManager();
boolean reserved = jobManager.reserveItemForTrade(itemId, tradeId);
jobManager.freeItemReservationForTrade(itemId, tradeId);
jobManager.cancelAllReservationsForTrade(tradeId);
int freeItems = jobManager.getFreeItemsSize();
```

### Ship Trade Eligibility

```java
EncounterAI.AiShipInfo aiInfo = ship.getAiShipInfo(false);
if (aiInfo != null && aiInfo.canTradeWith(playerStationId)) {
    // Ship can trade
}
```

## Configuration System

### Configuration Loading Flow

1. **Initial Load** - `AutoBuyerAspect` static block loads config
2. **Parse info.xml** - Extract default values from `info.xml`
3. **Load Modloader Input** - Try to get user input from modloader
4. **Merge Values** - Defaults first, then user input overrides
5. **Check Preset** - If preset specified, load and override
6. **Apply Config** - Call `config.loadFromConfig(mergedValues)`
7. **Delayed Recheck** - After 2 seconds, recheck for preset (modloader timing)

### Configuration Variables

**Format:**
- Variable names: `{variable_name}` (must include braces)
- Values: Strings (parsed to appropriate types)
- Defaults: Specified in `info.xml` `value` and `default` attributes

**Example:**
```xml
<var value="30" default="30" name="{food_root_vegetables}">
    Root Vegetables - Target Stock
</var>
```

### Preset System

**Preset Files:**
- Location: `presets/PresetName.json`
- Format: JSON with `config` object
- Case-sensitive: Recommended to use exact case

**Preset Structure:**
```json
{
    "config": {
        "{food_root_vegetables}": "15",
        "{water}": "30",
        "{max_credits_per_trade}": "2000"
    }
}
```

**Loading Presets:**
1. Check `{config_preset}` in merged config values
2. Load preset file from `presets/` directory
3. Merge preset values (override defaults and user input)
4. Apply final merged config

### Configuration Priority

1. **Defaults** (lowest priority) - From `info.xml`
2. **User Input** - From modloader UI (overrides defaults)
3. **Preset** (highest priority) - From preset file (overrides everything)

## Item IDs

### Elementary IDs

Items are identified by **elementary IDs** (integers):

- `15` - Root Vegetables
- `16` - Water
- `158` - Energium
- `1926` - Energy Cell
- etc.

**Finding Item IDs:**
- Decompile `spacehaven.jar`
- Search for item definitions
- Check `Elementary` or `Item` classes
- Use logging to discover IDs during testing

### Item Categories

Items are organized into categories:
- **Food** - Root vegetables, fruits, processed food, etc.
- **Materials** - Carbon, chemicals, plastics, fabrics, etc.
- **Metals & Ores** - Basic metals, noble metals, exotic ore, etc.
- **Energy & Fuel** - Energium, hyperium, energy cells, hyperfuel
- **Components** - Electronic, optronics, quantronics components
- **Medical** - Medical supplies, IV fluid, painkillers, etc.
- **Resources** - Water, ice blocks
- **Botany** - Biomass, fertilizer
- **Scrap** - Various scrap types (rubble, infra, hull, etc.)
- **Blocks** - Building blocks (infra, tech, hull, energy, etc.)

## State Management

### Ship State

Each NPC ship has a `ShipState` object tracking:
- **Offers Cache** - Cached list of items for sale
- **Last Refresh Time** - When offers were last refreshed
- **Last Attempt Time** - When we last tried to trade
- **Total Trades Created** - Count of trades with this ship
- **Flags** - `maxTradesReached`, `nothingToPurchase`, `isNewShip`
- **New Ship Tracking** - Retry count, first seen time

**Why State Management:**
- Prevents redundant offer queries (caching)
- Tracks trade limits per ship
- Manages retry logic for new ships
- Prevents duplicate trade attempts

### Trade ID Tracking

```java
Set<Integer> createdTradeIds = new HashSet<>();
```

**Purpose:**
- Prevents duplicate trades (race conditions)
- Tracks which trades we created (vs manual trades)
- Memory management (remove when trade completes)

## Logistics Awareness

### Logistics Item Count

Tracks number of items waiting for logistics:
```java
int freeItems = jobManager.getFreeItemsSize();
int tryFerryAgain = jobManager.getTryFerryAgainItemsSize();
int total = freeItems + tryFerryAgain;
```

### Logistics Thresholds

- **20 items:** Slow down trading (skip some attempts)
- **40 items:** Pause trading (don't create new trades)
- **60 items:** Cancel all trades and release all ships

**Why:**
- Too many items waiting causes performance issues
- Game's logistics system can get overwhelmed
- Prevents creating more work when system is busy

## Priority Scoring

### Ship Priority

Ships are scored based on:
- **Dire need items** - Items < 20% of target (massive bonus)
- **Discounted items** - Items sold at discount (bonus)
- **Total need value** - Weighted sum of items we need
- **Active trades** - Penalty for ships with many active trades

**Formula:**
```
score = (direNeedItems * 5000) + (discountedItems * 100) + 
        (totalNeedValue / 10) - (activeTrades * 20)
```

### Item Priority

Items are prioritized by:
- **Trade mode** - Discounted > Neutral > Markup > Premium (lower priority, but can be purchased if dire need)
- **Need amount** - More need = higher priority
- **Stock percentage** - Lower stock = higher priority

## Error Handling

### Try-Catch Patterns

All hooks and core methods use try-catch:
```java
try {
    // Code that might fail
} catch (Exception e) {
    ModLog.log("Error: " + e.getMessage());
    ModLog.log(e); // Log stack trace
    // Don't crash the game
}
```

### Validation Checks

Always validate before use:
```java
if (ship == null || world == null) {
    return; // Early exit
}
if (!ship.isPlayerShip() || ship.isDerelict()) {
    return; // Filter unwanted ships
}
```

### Smart Retry

If something fails, continue with reduced functionality:
```java
try {
    gui.getGuiNotes().addNewTrade(trade);
} catch (Exception e) {
    // Trade still created, just notification failed
    ModLog.log("Notification failed: " + e.getMessage());
}
```

## Next Steps

- Read **07_Code_Walkthrough.md** to see these concepts in action
- Review **08_Common_Patterns.md** for implementation patterns
- Study **09_Troubleshooting.md** for debugging techniques

---

**Previous:** [05_Game_Hooks_Used.md](05_Game_Hooks_Used.md)  
**Next:** [07_Code_Walkthrough.md](07_Code_Walkthrough.md)

