# Decompiled Research Files - Useful Game Classes

This reference guide lists useful classes from the decompiled `spacehaven.jar` that were used in AutoBuyerMod. These classes provide access to game functionality.

## How to Use This Guide

1. **Decompile spacehaven.jar** using JD-GUI, Fernflower, or similar tool
2. **Navigate to these package paths** in the decompiled code
3. **Study the class methods** to understand available functionality
4. **Import classes** in your mod code to use them

## Core Game Classes

### fi.bugbyte.spacehaven.world.World

**Package:** `fi.bugbyte.spacehaven.world`  
**Purpose:** Main game world object. Provides access to ships, trades, and world state.

**Key Methods:**
- `getShips()` - Returns `Array<Ship>` of all ships in the sector
- `getShip(int shipId)` - Get a specific ship by ID
- `getTrades()` - Returns `Array<TradeAgreement>` of all active trades
- `getPlayerBank()` - Returns `TradingHelper.Bank` for player credits
- `setTradeDone(TradeAgreement)` - Marks a trade as completed (hooked in AutoBuyerMod)
- `cancelTrade(TradeAgreement, boolean)` - Cancels a trade (hooked in AutoBuyerMod)
- `addShip(Ship)` - Adds a ship to the world (hooked in AutoBuyerMod)
- `shipJumped(Ship)` - Called when a ship leaves sector (hooked in AutoBuyerMod)
- `getNextElementId()` - Generates unique IDs for new game objects

**Usage Example:**
```java
World world = // Get from hook or method parameter
Array<Ship> ships = world.getShips();
for (int i = 0; i < ships.size; i++) {
    Ship ship = ships.get(i);
    // Process ship
}
```

### fi.bugbyte.spacehaven.world.Ship

**Package:** `fi.bugbyte.spacehaven.world`  
**Purpose:** Represents a ship (player or NPC). Provides ship state and methods.

**Key Methods:**
- `getShipId()` - Returns unique ship ID
- `getName()` - Returns ship name (may throw exception)
- `isPlayerShip()` - Returns true if player-controlled
- `isStation()` - Returns true if this is a station
- `isDerelict()` - Returns true if ship is derelict
- `isClaimable()` - Returns true if ship can be claimed
- `getItemsOf(int elementaryId, boolean includeReserved)` - Get item count
- `getShipCreditBank()` - Returns `TradingHelper.Bank` for ship credits
- `getJobManager()` - Returns `JobManager` for logistics/item management
- `getAiShipInfo(boolean)` - Returns `EncounterAI.AiShipInfo` for trade eligibility
- `getWorld()` - Returns the `World` object
- `entityBoarded(...)` - Called when entity boards ship (hooked in AutoBuyerMod)

**Usage Example:**
```java
Ship ship = world.getShip(shipId);
if (ship != null && !ship.isPlayerShip() && !ship.isDerelict()) {
    int waterCount = ship.getItemsOf(16, true); // Item ID 16 = Water
    // Process NPC ship
}
```

## Trading System Classes

### fi.bugbyte.spacehaven.ai.Trading

**Package:** `fi.bugbyte.spacehaven.ai`  
**Purpose:** Trading system classes and data structures.

**Key Classes:**
- `Trading.TradeAgreement` - Represents a trade between two ships
- `Trading.TradeItem` - Represents an item in a trade

**TradeAgreement Fields:**
- `int id` - Unique trade ID
- `int shipId1` - First ship ID
- `int shipId2` - Second ship ID
- `int creditsToShip1` - Credits paid to ship 1
- `int creditsToShip2` - Credits paid to ship 2
- `Array<TradeItem> toShip1` - Items going to ship 1
- `Array<TradeItem> toShip2` - Items going to ship 2
- `boolean isPlayerTrade()` - Returns true if player is involved

**TradeItem Fields:**
- `int elementaryId` - Item ID
- `int howMuch` - Quantity

**Usage Example:**
```java
Trading.TradeAgreement trade = new Trading.TradeAgreement();
trade.id = world.getNextElementId();
trade.shipId1 = playerShipId;
trade.shipId2 = npcShipId;
trade.toShip1.add(new Trading.TradeItem(itemId, quantity));
trade.creditsToShip2 = cost;
world.addNewTradeAgreement(trade);
```

### fi.bugbyte.spacehaven.ai.TradingHelper

**Package:** `fi.bugbyte.spacehaven.ai`  
**Purpose:** Helper utilities for trading, including price calculation and bank management.

**Key Classes:**
- `TradingHelper.Bank` - Manages credits and item prices
- `TradingHelper.TradeItemMode` - Enum for trade modes (Discounted, Neutral, Markup, Premium)

**Bank Methods:**
- `getCreditsAvailable()` - Get available credits
- `reserve(int amount)` - Reserve credits for a trade
- `getSellPriceToPlayer(int itemId, int quantity, TradeItemMode mode)` - Get sell price
- `getBuyPriceFromPlayer(int itemId, int quantity, TradeItemMode mode, Object)` - Get buy price
- `createOffersList(Ship, Array, boolean, Object)` - Create list of items for sale

**TradeItemMode Values:**
- `Discounted` - Item sold at discount
- `Neutral` - Normal price
- `Markup` - Item sold at markup
- `Premium` - Item sold at premium (not for sale, only buying)

**Usage Example:**
```java
TradingHelper.Bank npcBank = npcShip.getShipCreditBank();
int price = npcBank.getSellPriceToPlayer(itemId, quantity, TradingHelper.TradeItemMode.Neutral);
if (price > 0 && playerBank.getCreditsAvailable() >= price) {
    // Can afford to buy
}
```

## Faction and Entity Classes

### fi.bugbyte.spacehaven.stuff.FactionUtils

**Package:** `fi.bugbyte.spacehaven.stuff`  
**Purpose:** Faction relationship utilities.

**Key Classes:**
- `FactionUtils.FactionSide` - Enum for faction sides (Player, Enemy, etc.)

**FactionSide Methods:**
- `isEnemy(FactionSide)` - Check if two factions are enemies

**Usage Example:**
```java
FactionUtils.FactionSide npcSide = npcShip.getCurrentOwnerSide();
if (npcSide.isEnemy(FactionUtils.FactionSide.Player)) {
    // NPC is enemy, skip trading
    return;
}
```

### fi.bugbyte.spacehaven.stuff.Entity

**Package:** `fi.bugbyte.spacehaven.stuff`  
**Purpose:** Represents game entities (characters, robots, etc.).

**Key Methods:**
- `getWorkSide()` - Returns `FactionSide` of the entity

**Usage Example:**
```java
FactionUtils.FactionSide entitySide = entity.getWorkSide();
if (entitySide != FactionUtils.FactionSide.Player) {
    // Entity is from NPC faction
}
```

### fi.bugbyte.spacehaven.stuff.crafts.Craft

**Package:** `fi.bugbyte.spacehaven.stuff.crafts`  
**Purpose:** Represents craft objects (shuttles, etc.).

**Key Methods:**
- `getType()` - Returns `Craft.Type` (Shuttle, etc.)
- `getSide()` - Returns `FactionSide` of the craft

**Usage Example:**
```java
if (fromCraft.getType() == Craft.Type.Shuttle) {
    FactionUtils.FactionSide shuttleSide = fromCraft.getSide();
    if (shuttleSide != FactionUtils.FactionSide.Player) {
        // NPC shuttle
    }
}
```

## AI and Encounter Classes

### fi.bugbyte.spacehaven.ai.EncounterAI.AiShipInfo

**Package:** `fi.bugbyte.spacehaven.ai`  
**Purpose:** AI ship information, including trade eligibility.

**Key Methods:**
- `canTradeWith(int playerShipId)` - Returns true if ship can trade with player

**Usage Example:**
```java
EncounterAI.AiShipInfo aiInfo = npcShip.getAiShipInfo(false);
if (aiInfo != null && aiInfo.canTradeWith(playerStation.getShipId())) {
    // Ship can trade
}
```

### fi.bugbyte.spacehaven.ai.JobManager

**Package:** `fi.bugbyte.spacehaven.ai`  
**Purpose:** Manages jobs, item logistics, and item reservations.

**Key Methods:**
- `reserveItemForTrade(int itemId, int tradeId)` - Reserve item for trade
- `freeItemReservationForTrade(int itemId, int tradeId)` - Free item reservation
- `cancelAllReservationsForTrade(int tradeId)` - Cancel all reservations for a trade
- `getFreeItemsSize()` - Get count of free items waiting for logistics
- `getTryFerryAgainItemsSize()` - Get count of items waiting to be ferried again

**Usage Example:**
```java
JobManager jobManager = ship.getJobManager();
if (jobManager.reserveItemForTrade(itemId, tradeId)) {
    // Item reserved successfully
} else {
    // Item not available
}
```

## GUI Classes

### fi.bugbyte.spacehaven.gui.GUI

**Package:** `fi.bugbyte.spacehaven.gui`  
**Purpose:** Main GUI instance.

**Key Fields:**
- `static GUI instance` - Singleton GUI instance

**Usage Example:**
```java
if (GUI.instance != null) {
    GUIHelper.GuiNotes notes = GUI.instance.getGuiNotes();
    if (notes != null) {
        notes.addNewTrade(trade);
    }
}
```

### fi.bugbyte.spacehaven.gui.Indicators.ShipTileIconManager

**Package:** `fi.bugbyte.spacehaven.gui.Indicators`  
**Purpose:** Manages ship tile icons and logistics indicators.

**Key Methods:**
- `setLogisticsSwamped(boolean)` - Sets logistics overwhelmed state (hooked in AutoBuyerMod)

**Usage Example:**
```java
// Hooked to detect when logistics get overwhelmed
@After("execution(* ShipTileIconManager.setLogisticsSwamped(boolean)) && args(swamped)")
public void onLogisticsSwamped(boolean swamped) {
    // Handle logistics state change
}
```

## Utility Classes

### com.badlogic.gdx.utils.Array

**Package:** `com.badlogic.gdx.utils`  
**Purpose:** LibGDX array class used throughout Space Haven.

**Key Methods:**
- `size` - Field: number of elements
- `get(int index)` - Get element at index
- `add(T element)` - Add element
- `removeIndex(int index)` - Remove element at index

**Usage Example:**
```java
Array<Ship> ships = world.getShips();
for (int i = 0; i < ships.size; i++) {
    Ship ship = ships.get(i);
    // Process ship
}
```

## Finding More Classes

### Search Strategy

1. **Start with what you need:**
   - Need trading? → Search for "Trading" or "Trade"
   - Need ships? → Search for "Ship"
   - Need items? → Search for "Item" or "Elementary"

2. **Check package structure:**
   - `fi.bugbyte.spacehaven.world.*` - World, ships, stations
   - `fi.bugbyte.spacehaven.ai.*` - AI, trading, jobs
   - `fi.bugbyte.spacehaven.stuff.*` - Items, entities, factions
   - `fi.bugbyte.spacehaven.gui.*` - GUI components

3. **Use IDE search:**
   - Search for class names in decompiled code
   - Look for method signatures that match what you need
   - Check return types to find related classes

### Common Patterns

- **Manager classes** often end with "Manager" (e.g., `JobManager`)
- **Helper classes** often end with "Helper" (e.g., `TradingHelper`)
- **Data classes** are often inner classes (e.g., `Trading.TradeAgreement`)
- **Enum classes** are often inner classes (e.g., `TradingHelper.TradeItemMode`)

## Next Steps

- Review **05_Game_Hooks_Used.md** to see how these classes are used in hooks
- Study **06_Core_Concepts.md** for deeper understanding of game systems
- Read **07_Code_Walkthrough.md** to see classes in context

---

**Previous:** [03_AspectJ_Basics.md](03_AspectJ_Basics.md)  
**Next:** [05_Game_Hooks_Used.md](05_Game_Hooks_Used.md)

