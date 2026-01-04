/**
 * ⚠️ IMPORTANT: PACKAGE NAMING CONVENTION
 * 
 * This package name (com.rinswiftwings.autobuyermod) is an EXAMPLE for this practice mod.
 * 
 * WHEN CREATING YOUR OWN MOD, YOU MUST:
 * 1. Change this package name to your own unique identifier
 * 2. Use the format: com.yourname.modname or com.yourdomain.modname
 * 3. Update ALL Java files in your mod to use the new package name
 * 4. Update the directory structure to match (move files to new package path)
 * 5. Update aop.xml to register aspects with the new package name
 * 
 * WHY: Package names must be unique to avoid conflicts between mods.
 * If multiple mods use the same package name, they can interfere with each other.
 * 
 * EXAMPLE: If your name is John Smith and your mod is "AutoMiner":
 *   Change to: package com.johnsmith.autominer;
 * 
 * See HelpFiles/01_Getting_Started.md for more details on package naming.
 */
package com.rinswiftwings.autobuyermod;

import com.badlogic.gdx.utils.Array;
import fi.bugbyte.spacehaven.ai.Trading;
import fi.bugbyte.spacehaven.ai.TradingHelper;
import fi.bugbyte.spacehaven.stuff.FactionUtils;
import fi.bugbyte.spacehaven.world.Ship;
import fi.bugbyte.spacehaven.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Core auto-buyer logic.
 * Handles trade creation, eligibility checks, and state management.
 * 
 * DESIGN DECISIONS:
 * - This class is separated from AutoBuyerAspect to keep business logic separate from AspectJ hooks
 * - State is managed per-ship to track individual ship behavior (offers, retries, trade limits)
 * - Synchronization is used to prevent race conditions when multiple hooks try to create trades simultaneously
 * - Logistics awareness prevents overwhelming the game's item logistics system
 */
public class AutoBuyerCore {
    
    private final AutoBuyerConfig config;
    
    /**
     * Per-ship state tracking.
     * WHY: Each NPC ship needs independent state (offers cache, retry count, trade limits).
     * Using a Map allows O(1) lookup by ship ID, and automatic cleanup when ships leave.
     */
    private final Map<Integer, ShipState> shipStates = new HashMap<>();
    
    /**
     * Track created trade IDs to prevent duplicates.
     * WHY: Multiple hooks/threads could try to create the same trade. This Set allows fast
     * duplicate detection before creating trades. Must be checked inside synchronized block.
     */
    private final java.util.Set<Integer> createdTradeIds = new java.util.HashSet<>();
    
    /**
     * Synchronization lock for trade creation to prevent race conditions.
     * WHY: AspectJ hooks can fire from multiple threads. Without synchronization, two hooks
     * could both check "no active trades" and both create trades, exceeding the 4-trade limit.
     * This lock ensures only one trade creation happens at a time.
     */
    private final Object tradeCreationLock = new Object();
    
    /**
     * Track logistics load (number of free items waiting for logistics).
     * WHY: Too many items waiting for logistics causes performance issues. We need to track
     * this to slow down or pause trading when logistics are overwhelmed.
     * 
     * volatile ensures visibility across threads (hooks may update from different threads).
     */
    private volatile int logisticsItemCount = 0;
    
    /**
     * Track previous logistics count for threshold crossing detection.
     * WHY: We only want to log/act when crossing thresholds (e.g., going from 19 to 20 items),
     * not on every update. This tracks the previous state to detect crossings.
     */
    private volatile int previousLogisticsItemCount = 0;
    
    /**
     * Thresholds for logistics slowdown.
     * WHY: These thresholds create a gradual response to logistics overload:
     * - 20 items: Start slowing (skip 50% of attempts) - early warning
     * - 40 items: Pause new trades (but don't cancel existing) - prevent more load
     * - 60 items: Cancel all trades - emergency response
     * 
     * The hysteresis (resume at 20, pause at 40) prevents rapid toggling when count hovers
     * around a threshold. Resume threshold is lower than pause threshold to avoid oscillation.
     */
    private static final int LOGISTICS_SLOWDOWN_THRESHOLD = 20; // Start slowing down at 20 items
    private static final int LOGISTICS_PAUSE_THRESHOLD = 40;    // Pause trading at 40 items (don't cancel trades)
    private static final int LOGISTICS_RELEASE_IDLE_THRESHOLD = 40; // Release ships with no trades at 40 items
    private static final int LOGISTICS_CANCEL_ALL_THRESHOLD = 60;   // Cancel all trades and release all ships at 60 items
    private static final int LOGISTICS_RESUME_THRESHOLD = 20;   // Resume trading when falls to 20 items
    
    /**
     * Maximum retries for new ships before marking as "nothing to purchase".
     * WHY: New ships may not have offers ready immediately. We give them 3 chances
     * (with delays) before giving up. This prevents false negatives from timing issues.
     */
    private static final int MAX_NEW_SHIP_RETRIES = 3;
    
    public AutoBuyerCore(AutoBuyerConfig config) {
        this.config = config;
        ModLog.log("AutoBuyerCore: Initialized");
    }
    
    /**
     * Attempt to create a trade with an NPC ship for the player station.
     * Called when a trade slot frees or when a ship becomes eligible.
     * 
     * DESIGN: This method performs a series of checks before attempting trade creation:
     * 1. Eligibility checks (ship state, faction, trade eligibility)
     * 2. Trade limit checks (concurrent trades, total trades per ship)
     * 3. Cooldown checks (prevent too-frequent attempts)
     * 4. Offer refresh (get current items for sale)
     * 5. Trade building (select items, calculate costs)
     * 6. Trade creation (reserve items/credits, commit trade)
     * 
     * WHY: Each check prevents wasted work and ensures we only create valid trades.
     * Early returns (false) are used extensively to exit quickly when conditions aren't met.
     * 
     * @return true if trade was successfully created, false otherwise
     */
    public boolean attemptAutoBuy(World world, Ship npcShip) {
        try {
            Ship playerStation = findPlayerStation(world);
            if (playerStation == null) {
                ModLog.log("AutoBuyerCore: No player station found");
                return false;
            }
            
            /**
             * Check minimum credit balance guardrail.
             * WHY: We want to maintain a minimum credit reserve for emergencies. This prevents
             * the mod from spending all credits, leaving the player with no buffer for manual trades
             * or emergencies. The guardrail is checked early to avoid wasted work if we can't trade anyway.
             */
            TradingHelper.Bank creditCheckBank = world.getPlayerBank();
            int availableCredits = creditCheckBank.getCreditsAvailable();
            if (availableCredits <= config.getMinCreditBalance()) {
                ModLog.log("AutoBuyerCore: Player credits (" + availableCredits + ") at or below minimum (" + 
                          config.getMinCreditBalance() + ") - skipping trade attempts");
                return false;
            }
            
            // Get or create ship state
            ShipState state = getShipState(npcShip.getShipId());
            
            String shipName = getShipName(npcShip);
            
            if (!shouldAttempt(npcShip, playerStation, world)) {
                ModLog.log("AutoBuyerCore: [FAILED] Ship " + shipName + " (ID: " + npcShip.getShipId() + 
                          ") failed shouldAttempt check - not eligible for trading");
                return false;
            }
            
            /**
             * Check total trades limit per ship (8 trades max per ship per time in system).
             * WHY: The game has a limit on how many trades can be made with a single ship per visit.
             * This prevents excessive trading with one ship and ensures we spread trades across
             * multiple ships. Once limit is reached, we mark ship as "nothing to purchase" so
             * it gets released from tracking (saves memory and processing).
             */
            if (state.getTotalTradesCreated() >= 8) {
                // Mark as nothing to purchase so it gets released by releaseInactiveShips()
                state.setNothingToPurchase(true);
                ModLog.log("AutoBuyerCore: Ship " + shipName + " (ID: " + npcShip.getShipId() + 
                          ") has reached maximum trades (8) for this visit - marking as nothing to purchase and skipping");
                return false;
            }
            
            // Check cooldown
            if (state.isInCooldown(world, config.getCooldownTicks())) {
                ModLog.log("AutoBuyerCore: Ship " + shipName + " (ID: " + npcShip.getShipId() + ") in cooldown");
                return false;
            }
            
            /**
             * Check active trade count FIRST - this is the real check.
             * WHY: The game limits concurrent trades to 4 per ship pair. We must check this
             * BEFORE attempting to create a trade, otherwise we'll waste time building trades
             * that can't be created. We check by counting active trades in world.getTrades(),
             * not by relying on cached state (which may be stale).
             * 
             * The flag update (maxTradesReached) is for optimization - allows quick skip
             * on subsequent attempts without re-counting, but we still verify with actual count.
             */
            int activeTrades = countActiveTradesWithNpc(world, npcShip.getShipId(), playerStation.getShipId());
            if (activeTrades >= 4) {
                // Update flag to reflect current state
                state.setMaxTradesReached(true);
                ModLog.log("AutoBuyerCore: Ship " + shipName + " (ID: " + npcShip.getShipId() + 
                          ") has max concurrent trades (4) - cannot create more trades with this ship");
                return false;
            }
            
            // Clear the flag if we have less than 4 trades (trades may have completed)
            if (activeTrades < 4) {
                state.setMaxTradesReached(false);
            }
            
            /**
             * Early exit: If last check found nothing to purchase, don't check again.
             * EXCEPTION: New ships get retries before being marked as nothing to purchase.
             * 
             * WHY: This optimization prevents repeatedly checking ships that have no items
             * we need. Once we determine a ship has nothing to purchase, we skip it on
             * subsequent attempts. However, new ships get special treatment - they may not
             * have offers ready yet, so we allow retries before giving up.
             */
            if (state.isNothingToPurchase() && !state.isNewShip()) {
                ModLog.log("AutoBuyerCore: Ship " + shipName + " (ID: " + npcShip.getShipId() + ") had nothing to purchase last check - skipping");
                return false;
            }
            
            // Refresh offers if needed (only if we don't have cached offers)
            Map<Integer, Trading.TradeItem> offers = refreshOffersIfNeeded(world, npcShip, playerStation, state);
            if (offers == null || offers.isEmpty()) {
                // For new ships, allow retries before marking as nothing to purchase
                if (state.isNewShip()) {
                    state.incrementNewShipRetryCount();
                    if (state.hasExceededNewShipRetries(MAX_NEW_SHIP_RETRIES)) {
                        state.setNothingToPurchase(true);
                        state.setNewShip(false); // No longer new after max retries
                        ModLog.log("AutoBuyerCore: New ship " + shipName + " (ID: " + npcShip.getShipId() + 
                                  ") had no offers after " + MAX_NEW_SHIP_RETRIES + " retries - marking as nothing to purchase");
                    } else {
                        ModLog.log("AutoBuyerCore: New ship " + shipName + " (ID: " + npcShip.getShipId() + 
                                  ") had no offers (retry " + state.getNewShipRetryCount() + "/" + MAX_NEW_SHIP_RETRIES + ") - will retry");
                    }
                } else {
                    state.setNothingToPurchase(true);
                    ModLog.log("AutoBuyerCore: No offers available from ship " + shipName + " (ID: " + npcShip.getShipId() + ")");
                }
                return false;
            }
            
            // Build trade agreement
            Trading.TradeAgreement trade = buildTradeAgreement(
                world, npcShip, playerStation, offers
            );
            
            if (trade == null) {
                // Could not build trade - for new ships, allow retries
                if (state.isNewShip()) {
                    state.incrementNewShipRetryCount();
                    if (state.hasExceededNewShipRetries(MAX_NEW_SHIP_RETRIES)) {
                        state.setNothingToPurchase(true);
                        state.setNewShip(false);
                        ModLog.log("AutoBuyerCore: New ship " + shipName + " (ID: " + npcShip.getShipId() + 
                                  ") could not build trade after " + MAX_NEW_SHIP_RETRIES + " retries - marking as nothing to purchase");
                    } else {
                        ModLog.log("AutoBuyerCore: New ship " + shipName + " (ID: " + npcShip.getShipId() + 
                                  ") could not build trade (retry " + state.getNewShipRetryCount() + "/" + MAX_NEW_SHIP_RETRIES + ") - will retry");
                    }
                } else {
                    state.setNothingToPurchase(true);
                    // Log why trade couldn't be built - check offers to see what was available
                    int eligibleItems = 0;
                    int itemsWithNeed = 0;
                    for (Map.Entry<Integer, Trading.TradeItem> offerEntry : offers.entrySet()) {
                        Trading.TradeItem offer = offerEntry.getValue();
                        if (offer != null && offer.howMuch > 0 && 
                            offer.getTradeItemMode() != TradingHelper.TradeItemMode.Premium) {
                            eligibleItems++;
                            int target = config.getTargetStock(offerEntry.getKey());
                            if (target > 0) {
                                int need = calculateNeed(world, playerStation, offerEntry.getKey(), target);
                                if (need > 0) {
                                    itemsWithNeed++;
                                }
                            }
                        }
                    }
                    ModLog.log("AutoBuyerCore: [FAILED] Could not build valid trade for ship " + shipName + 
                              " (ID: " + npcShip.getShipId() + ") - " + eligibleItems + " eligible items in offers, " + 
                              itemsWithNeed + " items with need > 0");
                }
                state.setLastAttemptTime(world);
                return false;
            }
            
            // Reserve credits and commit
            TradingHelper.Bank playerBank = world.getPlayerBank();
            if (playerBank.getCreditsAvailable() < trade.creditsToShip2) {
                ModLog.log("AutoBuyerCore: Insufficient credits. Need: " + trade.creditsToShip2 + 
                          ", Have: " + playerBank.getCreditsAvailable());
                // Free item reservations since we can't complete the trade
                npcShip.getJobManager().cancelAllReservationsForTrade(trade.id);
                return false;
            }
            
            /**
             * Synchronize trade creation to prevent race conditions and duplicate trades.
             * WHY: Multiple AspectJ hooks can fire simultaneously (e.g., trade completion
             * and entity boarding hooks both triggering attemptBestTrade). Without synchronization,
             * both could check "3 active trades" and both create trades, exceeding the 4-trade limit.
             * 
             * The synchronized block ensures only one thread creates a trade at a time.
             * We also double-check conditions inside the synchronized block because state
             * may have changed between the initial check and entering the synchronized block.
             */
            synchronized (tradeCreationLock) {
                /**
                 * Double-check: verify trade ID hasn't been created already.
                 * WHY: Another thread may have created a trade with this ID between when we
                 * generated it and when we enter the synchronized block. This prevents duplicate
                 * trades with the same ID (which would cause errors).
                 */
                if (createdTradeIds.contains(trade.id)) {
                    ModLog.log("AutoBuyerCore: Trade " + trade.id + " already exists - preventing duplicate creation");
                    // Free item reservations since we're not creating the trade
                    npcShip.getJobManager().cancelAllReservationsForTrade(trade.id);
                    return false;
                }
                
                /**
                 * Double-check active trades again (may have changed during concurrent execution).
                 * WHY: Between the initial check and entering synchronized block, another thread
                 * may have created a trade with this ship. We must verify the limit again inside
                 * the synchronized block to ensure accuracy.
                 */
                int activeTradesCheck = countActiveTradesWithNpc(world, npcShip.getShipId(), playerStation.getShipId());
                if (activeTradesCheck >= 4) {
                    ModLog.log("AutoBuyerCore: Ship " + shipName + " (ID: " + npcShip.getShipId() + 
                              ") now has " + activeTradesCheck + " concurrent trades - preventing duplicate trade creation");
                    // Free item reservations since we're not creating the trade
                    npcShip.getJobManager().cancelAllReservationsForTrade(trade.id);
                    return false;
                }
                
                try {
                    playerBank.reserve(trade.creditsToShip2);
                    world.addNewTradeAgreement(trade);
                    // Mark this trade ID as created to prevent duplicates
                    createdTradeIds.add(trade.id);
                    
                    /**
                     * Add notification to main notifications UI (without causing UI duplication).
                     * This is separate from the trade icon UI which is handled by addNewTradeAgreement().
                     * 
                     * WHY: The game's addNewTradeAgreement() creates a trade icon, but doesn't
                     * always create a notification in the main notifications panel. We add it
                     * manually so players see when auto-trades are created. We wrap in try-catch
                     * because GUI may not be initialized yet, and we don't want GUI failures to
                     * prevent trade creation (trade is more important than notification).
                     */
                    try {
                        fi.bugbyte.spacehaven.gui.GUI gui = fi.bugbyte.spacehaven.gui.GUI.instance;
                        if (gui != null && gui.getGuiNotes() != null) {
                            gui.getGuiNotes().addNewTrade(trade);
                        }
                    } catch (Exception guiException) {
                        // Log but don't fail the trade if GUI notification fails
                        ModLog.log("AutoBuyerCore: Failed to add trade notification (trade still created): " + guiException.getMessage());
                    }
                    
                    // Increment total trades counter for this ship
                    state.incrementTotalTradesCreated();
                    
                    ModLog.log("AutoBuyerCore: Created trade " + trade.id + " with ship " + shipName + " (ID: " + npcShip.getShipId() + 
                              ") for " + trade.creditsToShip2 + " credits, " + trade.toShip1.size + " item types " +
                              "(Total trades with this ship: " + state.getTotalTradesCreated() + "/8)");
                    
                    /**
                     * Update state: successful trade means we should check again when slot frees.
                     * 
                     * WHY each update:
                     * - setLastAttemptTime: Tracks when we last tried (for cooldown calculation)
                     * - setLastOffersRefreshTime: Tracks when offers were refreshed (for cache invalidation)
                     * - setNothingToPurchase(false): Clear flag - we found something to buy, so ship
                     *   is still viable for future trades
                     * - clearOffersSnapshot(): CRITICAL - NPC stock has changed after this trade,
                     *   so cached offers are stale. We must refresh on next attempt to get accurate
                     *   availability. Without this, we might try to buy items that are no longer available.
                     */
                    state.setLastAttemptTime(world);
                    state.setLastOffersRefreshTime(world);
                    state.setNothingToPurchase(false); // Clear flag - we found something to buy
                    // CRITICAL: Invalidate offers cache so we can check for more items to buy
                    // NPC stock has changed, so we need fresh availability data for next trade
                    state.clearOffersSnapshot();
                    if (state.isNewShip()) {
                        state.setNewShip(false); // Ship is no longer new after first successful trade
                        ModLog.log("AutoBuyerCore: Ship " + shipName + " (ID: " + npcShip.getShipId() + ") successfully traded - no longer marked as new");
                    }
                    // Note: Don't set maxTradesReached here - we'll check that on next attempt
                    // We want to continue checking for more trades until we hit 4 concurrent
                    
                    return true; // Successfully created trade
                    
                } catch (Exception e) {
                    // If trade creation fails, free item reservations and remove from tracking
                    ModLog.log("AutoBuyerCore: Failed to create trade, freeing item reservations: " + e.getMessage());
                    npcShip.getJobManager().cancelAllReservationsForTrade(trade.id);
                    createdTradeIds.remove(trade.id); // Remove in case it was added before exception
                    throw e; // Re-throw to be caught by outer try-catch
                }
            } // End synchronized block
            
        } catch (Exception e) {
            ModLog.log("AutoBuyerCore: Exception in attemptAutoBuy: " + e.getMessage());
            ModLog.log(e);
            return false;
        }
    }
    
    /**
     * Check if we should attempt to trade with this NPC ship.
     */
    private boolean shouldAttempt(Ship npcShip, Ship playerStation, World world) {
        if (npcShip == null || playerStation == null) {
            return false;
        }
        
        // Skip player's own station/ship - cannot trade with self
        if (npcShip.isPlayerShip()) {
            return false;
        }
        
        // Also check if this is the same ship as the player station
        if (npcShip.getShipId() == playerStation.getShipId()) {
            return false;
        }
        
        // Ship state checks
        if (npcShip.isDerelict()) {
            return false;
        }
        if (npcShip.isClaimable()) {
            return false;
        }
        
        // Faction check
        FactionUtils.FactionSide npcSide = npcShip.getCurrentOwnerSide();
        if (npcSide.isEnemy(FactionUtils.FactionSide.Player)) {
            return false;
        }
        
        // Trade eligibility check
        fi.bugbyte.spacehaven.ai.EncounterAI.AiShipInfo aiInfo = npcShip.getAiShipInfo(false);
        if (aiInfo != null && !aiInfo.canTradeWith(playerStation.getShipId())) {
            String shipName = getShipName(npcShip);
            ModLog.log("AutoBuyerCore: Ship " + shipName + " (ID: " + npcShip.getShipId() + ") cannot trade (already traded this sector)");
            return false;
        }
        
        return true;
    }
    
    /**
     * Find the player station in the world.
     */
    private Ship findPlayerStation(World world) {
        Array<Ship> ships = world.getShips();
        for (int i = 0; i < ships.size; i++) {
            Ship s = ships.get(i);
            if (s.isPlayerShip() && s.isStation()) {
                return s;
            }
        }
        return null;
    }
    
    /**
     * Count active trades between player station and NPC ship.
     */
    private int countActiveTradesWithNpc(World world, int npcShipId, int playerStationId) {
        int count = 0;
        Array<Trading.TradeAgreement> trades = world.getTrades();
        for (int i = 0; i < trades.size; i++) {
            Trading.TradeAgreement t = trades.get(i);
            if (t.isPlayerTrade() && 
                ((t.shipId1 == playerStationId && t.shipId2 == npcShipId) ||
                 (t.shipId1 == npcShipId && t.shipId2 == playerStationId))) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Refresh offers from NPC ship if needed (respects cooldown).
     */
    private Map<Integer, Trading.TradeItem> refreshOffersIfNeeded(
        World world, Ship npcShip, Ship playerStation, ShipState state
    ) {
        // Refresh if we don't have cached offers or if cache was invalidated
        // NOTE: We invalidate cache after each trade completes since NPC stock changes when we buy
        if (state.getOffersSnapshot().isEmpty() || state.shouldRefreshOffers(world, config.getRefreshCooldownTicks())) {
            // Build mustOffer list from target stocks
            Array<Trading.TradeItem> mustOffer = new Array<>(false, config.getAllTargetStocks().size());
            for (Integer eid : config.getAllTargetStocks().keySet()) {
                Trading.TradeItem item = new Trading.TradeItem();
                item.elementaryId = eid;
                mustOffer.add(item);
            }
            
            // Get offers
            TradingHelper.Bank bank = npcShip.getShipCreditBank();
            Array<Trading.TradeItem> offers = bank.createOffersList(npcShip, mustOffer, false, null);
            
            // Runtime discovery: Log all item IDs found in offers
            logDiscoveredItemIds(offers, npcShip.getShipId(), npcShip);
            
            // Also discover ALL items this ship has (not just our targets)
            discoverAllAvailableItems(world, npcShip);
            
            // Convert to map
            Map<Integer, Trading.TradeItem> offerMap = new HashMap<>();
            for (int i = 0; i < offers.size; i++) {
                Trading.TradeItem offer = offers.get(i);
                offerMap.put(offer.elementaryId, offer);
            }
            
            state.setOffersSnapshot(offerMap);
            state.setLastOffersRefreshTime(world);
            
            String shipName = getShipName(npcShip);
            ModLog.log("AutoBuyerCore: Refreshed offers for ship " + shipName + " (ID: " + npcShip.getShipId() + 
                      "), found " + offerMap.size() + " items");
            
            return offerMap;
        }
        
        // Return cached offers
        return state.getOffersSnapshot();
    }
    
    /**
     * Get ship name safely.
     * Returns "Unknown Ship (ID)" if ship is null or name cannot be retrieved.
     */
    private String getShipName(Ship ship) {
        if (ship == null) {
            return "Unknown Ship (null)";
        }
        try {
            return ship.getName();
        } catch (Exception e) {
            return "Unknown Ship (ID: " + ship.getShipId() + ")";
        }
    }
    
    /**
     * Runtime discovery: Log all item IDs found in NPC offers.
     * This helps discover new item IDs and verify existing ones.
     */
    private void logDiscoveredItemIds(Array<Trading.TradeItem> offers, int shipId, Ship npcShip) {
        if (offers == null || offers.size == 0) {
            return;
        }
        
        String shipName = getShipName(npcShip);
        ModLog.log("AutoBuyerCore: [DISCOVERY] Ship " + shipName + " (ID: " + shipId + ") offers " + offers.size + " items:");
        for (int i = 0; i < offers.size; i++) {
            Trading.TradeItem offer = offers.get(i);
            String modeStr = offer.getTradeItemMode() != null ? offer.getTradeItemMode().toString() : "null";
            ModLog.log("AutoBuyerCore: [DISCOVERY]   ID: " + offer.elementaryId + 
                      ", Qty: " + offer.howMuch + 
                      ", Mode: " + modeStr);
        }
    }
    
    /**
     * Discover ALL items available from an NPC ship (not just our targets).
     * This is called periodically to help build a complete item ID database.
     * Uses a static counter to avoid spamming logs.
     */
    private static int discoveryCallCount = 0;
    private void discoverAllAvailableItems(World world, Ship npcShip) {
        // Only run discovery every 10th call to avoid log spam
        discoveryCallCount++;
        if (discoveryCallCount % 10 != 0) {
            return;
        }
        
        try {
            TradingHelper.Bank bank = npcShip.getShipCreditBank();
            // Get ALL offers (pass null for mustOffer to get everything)
            Array<Trading.TradeItem> allOffers = bank.createOffersList(npcShip, null, true, null);
            
            if (allOffers != null && allOffers.size > 0) {
                String shipName = getShipName(npcShip);
                ModLog.log("AutoBuyerCore: [FULL DISCOVERY] Ship " + shipName + " (ID: " + npcShip.getShipId() + 
                          ") has " + allOffers.size + " total items available:");
                
                // Group by category for easier reading
                java.util.Set<Integer> discoveredIds = new java.util.HashSet<>();
                for (int i = 0; i < allOffers.size; i++) {
                    Trading.TradeItem offer = allOffers.get(i);
                    discoveredIds.add(offer.elementaryId);
                    
                    String modeStr = offer.getTradeItemMode() != null ? 
                        offer.getTradeItemMode().toString() : "null";
                    ModLog.log("AutoBuyerCore: [FULL DISCOVERY]   ID: " + offer.elementaryId + 
                              ", Qty: " + offer.howMuch + 
                              ", Mode: " + modeStr);
                }
                
                ModLog.log("AutoBuyerCore: [FULL DISCOVERY] Total unique item IDs discovered: " + discoveredIds.size());
            }
        } catch (Exception e) {
            ModLog.log("AutoBuyerCore: Exception in discoverAllAvailableItems: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Build a trade agreement (respects 10-unit cap).
     * CRITICAL: This method reserves items on the NPC ship before building the trade.
     * If reservation fails, all previous reservations are freed and null is returned.
     */
    private Trading.TradeAgreement buildTradeAgreement(
        World world, Ship npcShip, Ship playerStation, Map<Integer, Trading.TradeItem> offers
    ) {
        // Get trade ID FIRST - needed for reservations
        int tradeId = world.getNextElementId();
        
        Trading.TradeAgreement t = new Trading.TradeAgreement();
        t.id = tradeId;
        t.setPlayerTrade(true);
        t.shipId1 = playerStation.getShipId();
        t.shipId2 = npcShip.getShipId();
        
        TradingHelper.Bank bank = npcShip.getShipCreditBank();
        TradingHelper.Bank playerBank = world.getPlayerBank();
        fi.bugbyte.spacehaven.ai.JobManager npcJobManager = npcShip.getJobManager();
        
        int remainingCapacity = 10; // Hard cap
        int totalCost = 0;
        int totalUnits = 0;
        
        // Track reserved items so we can free them if trade building fails
        java.util.List<Integer> reservedItemIds = new java.util.ArrayList<>();
        
        // Build list of eligible items with their priority, then sort by priority
        java.util.List<ItemOffer> eligibleItems = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : config.getAllTargetStocks().entrySet()) {
            int eid = entry.getKey();
            int target = entry.getValue();
            
            Trading.TradeItem offer = offers.get(eid);
            if (offer == null) continue;
            
            // Premium items can be purchased if stock is below Premium threshold (threshold system handles this)
            // No need to skip Premium items - let threshold check determine eligibility
            
            // Calculate need and current stock percentage
            int current = playerStation.getItemsOf(eid, true);
            int queued = getAlreadyQueuedInbound(world, playerStation, eid);
            int need = Math.max(0, target - current - queued);
            if (need <= 0) continue;
            
            // Check markup threshold for buying
            // Calculate current stock as percentage of target stock
            double stockPercent = target > 0 ? ((double)(current + queued) / target) * 100.0 : 0.0;
            int threshold = config.getBuyThreshold(offer.getTradeItemMode());
            
            // Only buy if current stock percentage is below the threshold for this trade mode
            // Example: Discounted threshold = 100% means always buy if under target
            //          Markup threshold = 40% means only buy if stock < 40% of target
            if (stockPercent >= threshold) {
                // Stock is too high for this markup level - skip
                continue;
            }
            
            // Get available quantity from offer
            int avail = offer.howMuch;
            if (avail <= 0) continue;
            
            // Add to eligible items list with priority
            eligibleItems.add(new ItemOffer(eid, target, offer, need, avail));
        }
        
        // Sort by priority: Discounted (0) > Neutral (1) > Markup (2) > Premium (3)
        eligibleItems.sort((a, b) -> {
            int priorityA = getTradeModePriority(a.offer.getTradeItemMode());
            int priorityB = getTradeModePriority(b.offer.getTradeItemMode());
            return Integer.compare(priorityA, priorityB);
        });
        
        // Now iterate in priority order
        for (ItemOffer itemOffer : eligibleItems) {
            int eid = itemOffer.elementaryId;
            int target = itemOffer.target;
            Trading.TradeItem offer = itemOffer.offer;
            int need = itemOffer.need;
            int avail = itemOffer.avail;
            if (avail <= 0) continue;
            
            // Calculate desired quantity to buy (respect capacity)
            int desiredQty = Math.min(Math.min(need, avail), remainingCapacity);
            if (desiredQty <= 0) continue;
            
            // CRITICAL: Reserve items on NPC ship BEFORE adding to trade
            // The game requires this or trades will fail with "An item seems to be missing"
            // Try to reserve items one at a time, adapting to actual availability
            int actualReservedQty = 0;
            
            for (int i = 0; i < desiredQty; i++) {
                if (!npcJobManager.reserveItemForTrade(eid, tradeId)) {
                    // Reservation failed - this unit isn't available
                    // This can happen if items were reserved by other trades
                    if (actualReservedQty == 0) {
                        // Couldn't reserve even one - skip this item entirely
                        ModLog.log("AutoBuyerCore: Could not reserve any item ID: " + eid + 
                                  " - item may be unavailable or already reserved");
                        break;
                    } else {
                        // Reserved some but not all - use what we got
                        ModLog.log("AutoBuyerCore: Reserved " + actualReservedQty + " of " + desiredQty + 
                                  " units of item ID: " + eid);
                        break;
                    }
                }
                actualReservedQty++;
            }
            
            // If we couldn't reserve any, skip this item and continue
            if (actualReservedQty == 0) {
                continue;
            }
            
            // Use the actual reserved quantity (may be less than desired)
            int qty = actualReservedQty;
            
            // Track this item as reserved
            reservedItemIds.add(eid);
            
            // Calculate cost for this item (per-unit calculation)
            int itemCost = 0;
            int added = 0;
            for (int i = 0; i < qty; i++) {
                int unitPrice = bank.getSellPriceToPlayer(eid, avail - added, offer.getTradeItemMode());
                itemCost += unitPrice;
                added++;
            }
            
            // Check if we can afford it
            if (playerBank.getCreditsAvailable() < totalCost + itemCost) {
                // Try to reduce quantity to what we can afford
                int affordableQty = qty;
                int affordableCost = itemCost;
                while (affordableQty > 0 && playerBank.getCreditsAvailable() < totalCost + affordableCost) {
                    // Free one reservation
                    npcJobManager.freeItemReservationForTrade(eid, tradeId);
                    affordableQty--;
                    affordableCost = 0;
                    added = 0;
                    for (int i = 0; i < affordableQty; i++) {
                        affordableCost += bank.getSellPriceToPlayer(eid, avail - added, offer.getTradeItemMode());
                        added++;
                    }
                }
                if (affordableQty <= 0) {
                    // Free all reservations for this item and break
                    npcJobManager.cancelAllReservationsForTrade(tradeId);
                    break; // Can't afford any more
                }
                
                // Update quantity to what we can afford
                qty = affordableQty;
                itemCost = affordableCost;
            }
            
            // Check max credits per trade limit
            if (config.getMaxCreditsPerTrade() < Integer.MAX_VALUE) {
                if (totalCost + itemCost > config.getMaxCreditsPerTrade()) {
                    // Free reservations and break
                    npcJobManager.cancelAllReservationsForTrade(tradeId);
                    break; // Would exceed limit
                }
            }
            
            // Add item to trade
            t.toShip1.add(new Trading.TradeItem(eid, qty));
            totalCost += itemCost;
            totalUnits += qty;
            remainingCapacity -= qty;
            
            if (remainingCapacity <= 0) break;
        }
        
        if (t.toShip1.size == 0 || totalCost <= 0) {
            // Free all reservations before returning null
            npcJobManager.cancelAllReservationsForTrade(tradeId);
            return null; // No valid trade
        }
        
        t.creditsToShip2 = totalCost;
        
        // Build item list string for logging (IDs only - no names to avoid "Placeholder name" clutter)
        StringBuilder itemList = new StringBuilder();
        for (int i = 0; i < t.toShip1.size; i++) {
            Trading.TradeItem item = t.toShip1.get(i);
            if (i > 0) itemList.append(", ");
            itemList.append("ID: ").append(item.elementaryId).append(" x").append(item.howMuch);
        }
        
        ModLog.log("AutoBuyerCore: Built trade with " + t.toShip1.size + " item types, " + 
                  totalUnits + " total units, " + totalCost + " credits: " + itemList.toString());
        
        return t;
    }
    
    /**
     * Calculate how much of an item we need.
     */
    private int calculateNeed(World world, Ship station, int elementaryId, int targetStock) {
        int current = station.getItemsOf(elementaryId, true);
        int queued = getAlreadyQueuedInbound(world, station, elementaryId);
        int need = Math.max(0, targetStock - current - queued);
        return need;
    }
    
    /**
     * Get priority value for trade mode (lower = higher priority).
     * Discounted (0) > Neutral (1) > Markup (2) > Premium (3)
     */
    private int getTradeModePriority(TradingHelper.TradeItemMode mode) {
        if (mode == null) {
            return 1; // Neutral as default
        }
        switch (mode) {
            case Discounted:
                return 0; // Highest priority
            case Neutral:
                return 1;
            case Markup:
                return 2;
            case Premium:
                return 3; // Lowest priority (shouldn't be bought anyway)
            default:
                return 1;
        }
    }
    
    /**
     * Helper class to hold item offer information for sorting.
     */
    private static class ItemOffer {
        final int elementaryId;
        final int target;
        final Trading.TradeItem offer;
        final int need;
        final int avail;
        
        ItemOffer(int eid, int target, Trading.TradeItem offer, int need, int avail) {
            this.elementaryId = eid;
            this.target = target;
            this.offer = offer;
            this.need = need;
            this.avail = avail;
        }
    }
    
    /**
     * Get quantity of items already queued inbound from active trades.
     */
    private int getAlreadyQueuedInbound(World world, Ship station, int elementaryId) {
        int queued = 0;
        Array<Trading.TradeAgreement> trades = world.getTrades();
        for (int i = 0; i < trades.size; i++) {
            Trading.TradeAgreement t = trades.get(i);
            if (t.isPlayerTrade() && t.shipId1 == station.getShipId()) {
                // This is an inbound trade to our station
                for (int j = 0; j < t.toShip1.size; j++) {
                    Trading.TradeItem item = t.toShip1.get(j);
                    if (item.elementaryId == elementaryId) {
                        queued += item.howMuch;
                    }
                }
            }
        }
        return queued;
    }
    
    /**
     * Get or create ship state.
     */
    private ShipState getShipState(int shipId) {
        return shipStates.computeIfAbsent(shipId, k -> new ShipState());
    }
    
    /**
     * Mark a ship as new (allows retries before marking as "nothing to purchase").
     * Records timestamp for 10-second initialization delay.
     */
    public void markShipAsNew(int shipId) {
        ShipState state = getShipState(shipId);
        // Only log if this is the first time marking as new (timestamp was just set)
        boolean wasAlreadyNew = state.isNewShip();
        state.setNewShip(true);
        if (!wasAlreadyNew) {
            ModLog.log("AutoBuyerCore: Marked ship ID: " + shipId + " as new - 10-second initialization delay started");
        }
    }
    
    /**
     * Release ships that have no active trades and are marked as "nothing to purchase".
     * This helps free up memory and allows ships to be re-evaluated if they return.
     */
    public void releaseInactiveShips(World world, Ship playerStation) {
        if (playerStation == null) {
            return;
        }
        
        java.util.List<Integer> shipsToRelease = new java.util.ArrayList<>();
        
        for (Map.Entry<Integer, ShipState> entry : shipStates.entrySet()) {
            int shipId = entry.getKey();
            ShipState state = entry.getValue();
            
            // Check if ship has any active trades
            int activeTrades = countActiveTradesWithNpc(world, shipId, playerStation.getShipId());
            
            // Release if: no active trades, marked as nothing to purchase, and not a new ship
            if (activeTrades == 0 && state.isNothingToPurchase() && !state.isNewShip()) {
                shipsToRelease.add(shipId);
            }
        }
        
        // Release the ships
        for (Integer shipId : shipsToRelease) {
            Ship ship = world.getShip(shipId);
            String shipName = ship != null ? getShipName(ship) : "ID: " + shipId;
            ModLog.log("AutoBuyerCore: Releasing inactive ship " + shipName + " (no active trades, nothing to purchase)");
            shipStates.remove(shipId);
        }
    }
    
    /**
     * Get all eligible NPC ships in the sector and attempt trade with the best one.
     * This is called when a trade slot frees up to ensure we're trading with the best available ship.
     */
    public void attemptBestTrade(World world) {
        // Don't synchronize here - only synchronize around actual trade creation
        // Synchronizing here would block the game thread and cause UI issues
        try {
            // Check logistics load and adjust trading behavior
            // Resume trading when logistics fall to 20 or below
            if (logisticsItemCount < LOGISTICS_RESUME_THRESHOLD) {
                // Logistics are manageable - proceed with normal trading
            } else if (logisticsItemCount >= LOGISTICS_CANCEL_ALL_THRESHOLD) {
                // Pause completely at 60+ items (trades already cancelled when threshold was crossed)
                ModLog.log("AutoBuyerCore: [PAUSED] Logistics critical (" + logisticsItemCount + " free items >= " + LOGISTICS_CANCEL_ALL_THRESHOLD + ") - pausing auto-trading");
                return;
            } else if (logisticsItemCount >= LOGISTICS_PAUSE_THRESHOLD) {
                // Pause trading at 30+ items (but don't cancel trades - manual trades may be needed)
                ModLog.log("AutoBuyerCore: [PAUSED] Logistics overwhelmed (" + logisticsItemCount + " free items >= " + LOGISTICS_PAUSE_THRESHOLD + ") - pausing auto-trading");
                return;
            } else if (logisticsItemCount >= LOGISTICS_SLOWDOWN_THRESHOLD) {
                // Slow down trading if logistics are getting busy (20-29 items)
                // Skip 50% of trade attempts to reduce load
                if (System.currentTimeMillis() % 2 == 0) {
                    ModLog.log("AutoBuyerCore: [SLOWED] Logistics busy (" + logisticsItemCount + " free items >= " + LOGISTICS_SLOWDOWN_THRESHOLD + ") - skipping this trade attempt");
                    return;
                }
            }
            
            Ship playerStation = findPlayerStation(world);
            if (playerStation == null) {
                return;
            }
            
            // Check minimum credit balance guardrail
            TradingHelper.Bank creditCheckBank = world.getPlayerBank();
            int availableCredits = creditCheckBank.getCreditsAvailable();
            if (availableCredits <= config.getMinCreditBalance()) {
                return; // Silently skip if credits too low
            }
            
            // Release inactive ships (no active trades, nothing to purchase)
            releaseInactiveShips(world, playerStation);
            
            // Get all eligible ships and prioritize them
            // This includes new ships with no offers (they get low priority but are still eligible for retries)
            java.util.List<ShipPriority> eligibleShips = getAllEligibleShips(world, playerStation);
            if (eligibleShips.isEmpty()) {
                // Check if there are new ships that have now passed their delay
                // This handles the case where a new ship arrives and is the only ship in system
                java.util.List<Ship> newShipsReady = findNewShipsReadyForTrade(world, playerStation);
                if (!newShipsReady.isEmpty()) {
                    ModLog.log("AutoBuyerCore: [QUERY] Found " + newShipsReady.size() + " new ship(s) that have passed 10-second delay - retrying");
                    // Retry with these ships now eligible (delay has passed, so getAllEligibleShips will include them)
                    eligibleShips = getAllEligibleShips(world, playerStation);
                    if (eligibleShips.isEmpty()) {
                        return; // Still no eligible ships after retry
                    }
                } else {
                    // Check if there are new ships still waiting on delay
                    // Log this so we know why no trades are happening
                    java.util.List<Ship> newShipsWaiting = findNewShipsWaitingOnDelay(world, playerStation);
                    if (!newShipsWaiting.isEmpty()) {
                        for (Ship ship : newShipsWaiting) {
                            ShipState state = getShipState(ship.getShipId());
                            long timeSinceFirstSeen = System.currentTimeMillis() - state.getNewShipFirstSeenTimeMillis();
                            long remainingSeconds = (10000 - timeSinceFirstSeen) / 1000;
                            String shipName = getShipName(ship);
                            ModLog.log("AutoBuyerCore: [QUERY] New ship " + shipName + " (ID: " + ship.getShipId() + 
                                      ") still waiting on delay (" + remainingSeconds + "s remaining) - will retry when delay passes");
                        }
                    }
                    return; // No eligible ships and no new ships ready
                }
            }
            
            // Sort by priority (highest score first)
            eligibleShips.sort((a, b) -> Integer.compare(b.score, a.score));
            
            // Log which ships are being considered (helpful for debugging)
            if (eligibleShips.size() > 0) {
                ModLog.log("AutoBuyerCore: [QUERY] Checking " + eligibleShips.size() + " eligible ships for trade opportunity");
                // Log each ship being evaluated with its priority score
                for (ShipPriority sp : eligibleShips) {
                    String shipName = getShipName(sp.ship);
                    ModLog.log("AutoBuyerCore: [QUERY] Evaluating ship " + shipName + " (ID: " + sp.ship.getShipId() + 
                              ") - score: " + sp.score + ", discounted: " + sp.discountedItems + 
                              ", need value: " + sp.totalNeedValue + ", active trades: " + sp.activeTrades);
                }
            } else {
                ModLog.log("AutoBuyerCore: [QUERY] No eligible ships found - checking why ships were skipped");
                // Log why ships were skipped
                logSkippedShips(world, playerStation);
            }
            
            // Try to create as many trades as possible
            // After each trade, re-evaluate ALL ships to find the best option
            // If the same ship is still best, create another trade with it (up to 4 concurrent)
            // If a different ship becomes best, switch to it
            int maxIterations = 20; // Safety limit to prevent infinite loops
            int iteration = 0;
            boolean createdAnyTrade = false;
            
            while (iteration < maxIterations) {
                iteration++;
                
                // Re-evaluate ALL eligible ships (priorities may have changed after each trade)
                // This ensures we always pick the best ship, which might be the same one or a different one
                eligibleShips = getAllEligibleShips(world, playerStation);
                if (eligibleShips.isEmpty()) {
                    if (createdAnyTrade) {
                        ModLog.log("AutoBuyerCore: [QUERY] No more eligible ships after creating trade(s)");
                    }
                    break; // No more eligible ships
                }
                
                // Sort by priority (highest score first) - best ship is first
                eligibleShips.sort((a, b) -> Integer.compare(b.score, a.score));
                
                // Try the best ship first
                ShipPriority bestShipPriority = eligibleShips.get(0);
                Ship npcShip = bestShipPriority.ship;
                ShipState state = getShipState(npcShip.getShipId());
                String shipName = getShipName(npcShip);
                
                // Check if this ship already has 4 concurrent trades
                int activeTrades = countActiveTradesWithNpc(world, npcShip.getShipId(), playerStation.getShipId());
                if (activeTrades >= 4) {
                    // This ship is at max, try next best ship
                    if (eligibleShips.size() > 1) {
                        ModLog.log("AutoBuyerCore: [QUERY] Best ship " + shipName + " (ID: " + npcShip.getShipId() + 
                                  ") has 4 concurrent trades, trying next best ship");
                        bestShipPriority = eligibleShips.get(1);
                        npcShip = bestShipPriority.ship;
                        state = getShipState(npcShip.getShipId());
                        shipName = getShipName(npcShip);
                        activeTrades = countActiveTradesWithNpc(world, npcShip.getShipId(), playerStation.getShipId());
                        if (activeTrades >= 4) {
                            // Next ship also at max, no more trades possible
                            if (createdAnyTrade) {
                                ModLog.log("AutoBuyerCore: [QUERY] All best ships at max concurrent trades (4)");
                            }
                            break;
                        }
                    } else {
                        // Only one eligible ship and it's at max
                        if (createdAnyTrade) {
                            ModLog.log("AutoBuyerCore: [QUERY] Only eligible ship " + shipName + " (ID: " + npcShip.getShipId() + 
                                      ") has 4 concurrent trades");
                        }
                        break;
                    }
                }
                
                // Log query attempt
                long timeSinceLastQuery = state.getLastAttemptTimeMillis() > 0 ? 
                    (System.currentTimeMillis() - state.getLastAttemptTimeMillis()) / 1000 : -1;
                if (timeSinceLastQuery >= 0) {
                    ModLog.log("AutoBuyerCore: [QUERY] Best ship is " + shipName + " (ID: " + npcShip.getShipId() + 
                              ") - score: " + bestShipPriority.score + ", last queried " + timeSinceLastQuery + 
                              "s ago, active trades: " + activeTrades + "/4");
                } else {
                    ModLog.log("AutoBuyerCore: [QUERY] Best ship is " + shipName + " (ID: " + npcShip.getShipId() + 
                              ") - score: " + bestShipPriority.score + ", first query, active trades: " + activeTrades + "/4");
                }
                
                // Log retry attempts for new ships
                if (state.isNewShip() && state.getNewShipRetryCount() > 0) {
                    ModLog.log("AutoBuyerCore: [QUERY] Retrying new ship " + shipName + " (ID: " + npcShip.getShipId() + 
                              ") - attempt " + (state.getNewShipRetryCount() + 1) + "/" + MAX_NEW_SHIP_RETRIES);
                }
                
                boolean success = attemptAutoBuy(world, npcShip);
                if (success) {
                    createdAnyTrade = true;
                    int newActiveTrades = countActiveTradesWithNpc(world, npcShip.getShipId(), playerStation.getShipId());
                    ModLog.log("AutoBuyerCore: [QUERY] Created trade with best ship " + shipName + " (ID: " + npcShip.getShipId() + 
                              ") - active trades now: " + newActiveTrades + "/4, re-evaluating all ships for next best option");
                    // Continue loop to re-evaluate all ships and find next best option
                } else {
                    // Couldn't create trade with best ship - no more trades possible
                    if (createdAnyTrade) {
                        ModLog.log("AutoBuyerCore: [QUERY] Could not create trade with best ship " + shipName + 
                                  " (ID: " + npcShip.getShipId() + ") - no more trades can be created");
                    }
                    break;
                }
            }
            
            if (createdAnyTrade) {
                ModLog.log("AutoBuyerCore: [QUERY] Finished trade creation cycle - created trade(s) in " + iteration + " iteration(s)");
            }
            
        } catch (Exception e) {
            ModLog.log("AutoBuyerCore: Exception in attemptBestTrade: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Find new ships that have passed their 10-second initialization delay.
     * Used to retry ships when no eligible ships are found initially.
     */
    private java.util.List<Ship> findNewShipsReadyForTrade(World world, Ship playerStation) {
        java.util.List<Ship> readyShips = new java.util.ArrayList<>();
        Array<Ship> ships = world.getShips();
        
        for (int i = 0; i < ships.size; i++) {
            Ship ship = ships.get(i);
            
            // Basic eligibility check
            if (!shouldAttempt(ship, playerStation, world)) {
                continue;
            }
            
            // Check if this is a new ship that has passed its delay
            ShipState state = getShipState(ship.getShipId());
            if (state.isNewShip() && state.hasNewShipDelayPassed()) {
                // Check if ship hasn't exceeded retries and isn't at max trades
                if (!state.hasExceededNewShipRetries(MAX_NEW_SHIP_RETRIES)) {
                    int activeTrades = countActiveTradesWithNpc(world, ship.getShipId(), playerStation.getShipId());
                    if (activeTrades < 4 && state.getTotalTradesCreated() < 8) {
                        readyShips.add(ship);
                    }
                }
            }
        }
        
        return readyShips;
    }
    
    /**
     * Find new ships that are still waiting on their 10-second initialization delay.
     * Used for logging when no eligible ships are found.
     */
    private java.util.List<Ship> findNewShipsWaitingOnDelay(World world, Ship playerStation) {
        java.util.List<Ship> waitingShips = new java.util.ArrayList<>();
        Array<Ship> ships = world.getShips();
        
        for (int i = 0; i < ships.size; i++) {
            Ship ship = ships.get(i);
            
            // Basic eligibility check
            if (!shouldAttempt(ship, playerStation, world)) {
                continue;
            }
            
            // Check if this is a new ship that hasn't passed its delay yet
            ShipState state = getShipState(ship.getShipId());
            if (state.isNewShip() && !state.hasNewShipDelayPassed()) {
                // Check if ship hasn't exceeded retries and isn't at max trades
                if (!state.hasExceededNewShipRetries(MAX_NEW_SHIP_RETRIES)) {
                    int activeTrades = countActiveTradesWithNpc(world, ship.getShipId(), playerStation.getShipId());
                    if (activeTrades < 4 && state.getTotalTradesCreated() < 8) {
                        waitingShips.add(ship);
                    }
                }
            }
        }
        
        return waitingShips;
    }
    
    /**
     * Get all eligible NPC ships in the sector and calculate their priority scores.
     */
    private java.util.List<ShipPriority> getAllEligibleShips(World world, Ship playerStation) {
        java.util.List<ShipPriority> eligibleShips = new java.util.ArrayList<>();
        Array<Ship> ships = world.getShips();
        java.util.List<String> skipReasons = new java.util.ArrayList<>();
        
        for (int i = 0; i < ships.size; i++) {
            Ship ship = ships.get(i);
            String shipName = getShipName(ship);
            String skipReason = null;
            
            // Basic eligibility check
            if (!shouldAttempt(ship, playerStation, world)) {
                skipReason = "failed basic eligibility check (player/derelict/claimable/enemy/cannot trade)";
                skipReasons.add(shipName + " (ID: " + ship.getShipId() + "): " + skipReason);
                continue;
            }
            
            // Get ship state
            ShipState state = getShipState(ship.getShipId());
            
            // Skip if already at max trades
            if (state.getTotalTradesCreated() >= 8) {
                // Mark as nothing to purchase so it gets released by releaseInactiveShips()
                state.setNothingToPurchase(true);
                skipReason = "reached max trades (8/8)";
                skipReasons.add(shipName + " (ID: " + ship.getShipId() + "): " + skipReason);
                continue;
            }
            
            // Skip if in cooldown
            if (state.isInCooldown(world, config.getCooldownTicks())) {
                long timeSinceLastQuery = state.getLastAttemptTimeMillis() > 0 ? 
                    (System.currentTimeMillis() - state.getLastAttemptTimeMillis()) / 1000 : 0;
                skipReason = "in cooldown (last queried " + timeSinceLastQuery + "s ago)";
                skipReasons.add(shipName + " (ID: " + ship.getShipId() + "): " + skipReason);
                continue;
            }
            
            // Skip new ships that haven't waited 10 seconds yet (allows time for offers to initialize)
            if (state.isNewShip() && !state.hasNewShipDelayPassed()) {
                long timeSinceFirstSeen = System.currentTimeMillis() - state.getNewShipFirstSeenTimeMillis();
                long remainingSeconds = (10000 - timeSinceFirstSeen) / 1000;
                skipReason = "still in 10-second initialization delay (" + remainingSeconds + "s remaining)";
                skipReasons.add(shipName + " (ID: " + ship.getShipId() + "): " + skipReason);
                ModLog.log("AutoBuyerCore: [SKIP] New ship " + shipName + " (ID: " + ship.getShipId() + 
                          ") still in 10-second initialization delay (" + remainingSeconds + "s remaining)");
                continue;
            }
            
            // Skip if max concurrent trades reached
            int activeTrades = countActiveTradesWithNpc(world, ship.getShipId(), playerStation.getShipId());
            if (activeTrades >= 4) {
                skipReason = "max concurrent trades reached (4 active)";
                skipReasons.add(shipName + " (ID: " + ship.getShipId() + "): " + skipReason);
                continue;
            }
            
            // Skip if nothing to purchase (unless it's a new ship that hasn't exceeded retries)
            if (state.isNothingToPurchase() && !state.isNewShip()) {
                long timeSinceLastQuery = state.getLastAttemptTimeMillis() > 0 ? 
                    (System.currentTimeMillis() - state.getLastAttemptTimeMillis()) / 1000 : 0;
                skipReason = "marked as nothing to purchase (last checked " + timeSinceLastQuery + "s ago)";
                skipReasons.add(shipName + " (ID: " + ship.getShipId() + "): " + skipReason);
                continue;
            }
            
            // Calculate priority score for this ship
            ShipPriority priority = calculateShipPriority(world, ship, playerStation, state, activeTrades);
            if (priority != null) {
                eligibleShips.add(priority);
            } else {
                skipReason = "no eligible offers or priority calculation returned null";
                skipReasons.add(shipName + " (ID: " + ship.getShipId() + "): " + skipReason);
            }
        }
        
        // Log skipped ships if any
        if (!skipReasons.isEmpty() && eligibleShips.isEmpty()) {
            ModLog.log("AutoBuyerCore: [SKIP] All ships skipped - reasons:");
            for (String reason : skipReasons) {
                ModLog.log("AutoBuyerCore: [SKIP]   " + reason);
            }
        }
        
        return eligibleShips;
    }
    
    /**
     * Log why ships were skipped (called when no eligible ships found).
     */
    private void logSkippedShips(World world, Ship playerStation) {
        Array<Ship> ships = world.getShips();
        int npcShipCount = 0;
        int skippedCount = 0;
        
        for (int i = 0; i < ships.size; i++) {
            Ship ship = ships.get(i);
            if (ship.isPlayerShip() || ship.isDerelict() || ship.isClaimable()) {
                continue;
            }
            
            npcShipCount++;
            String shipName = getShipName(ship);
            java.util.List<String> reasons = new java.util.ArrayList<>();
            
            // Check each skip condition
            if (!shouldAttempt(ship, playerStation, world)) {
                reasons.add("basic eligibility failed");
            }
            
            ShipState state = getShipState(ship.getShipId());
            if (state.getTotalTradesCreated() >= 8) {
                reasons.add("max trades (8/8)");
            }
            if (state.isInCooldown(world, config.getCooldownTicks())) {
                reasons.add("in cooldown");
            }
            if (state.isNewShip() && !state.hasNewShipDelayPassed()) {
                reasons.add("10s delay active");
            }
            int activeTrades = countActiveTradesWithNpc(world, ship.getShipId(), playerStation.getShipId());
            if (activeTrades >= 4) {
                reasons.add("max concurrent trades (4)");
            }
            if (state.isNothingToPurchase() && !state.isNewShip()) {
                reasons.add("nothing to purchase");
            }
            
            if (!reasons.isEmpty()) {
                skippedCount++;
                ModLog.log("AutoBuyerCore: [SKIP] Ship " + shipName + " (ID: " + ship.getShipId() + 
                          ") skipped: " + String.join(", ", reasons));
            }
        }
        
        ModLog.log("AutoBuyerCore: [SKIP] Summary: " + npcShipCount + " NPC ships in sector, " + 
                  skippedCount + " skipped, " + (npcShipCount - skippedCount) + " eligible");
    }
    
    /**
     * Calculate priority score for a ship based on its offers.
     * Returns null if ship has no eligible offers (except new ships get a retry priority).
     */
    private ShipPriority calculateShipPriority(World world, Ship npcShip, Ship playerStation, ShipState state, int activeTrades) {
        try {
            // Get offers (use cached if available, otherwise refresh)
            Map<Integer, Trading.TradeItem> offers = refreshOffersIfNeeded(world, npcShip, playerStation, state);
            if (offers == null || offers.isEmpty()) {
                // For new ships that haven't exceeded retries, give them a very low priority
                // This allows them to be retried when trade slots free up
                if (state.isNewShip() && !state.hasExceededNewShipRetries(MAX_NEW_SHIP_RETRIES)) {
                    // Give a very low priority score so they're retried but after ships with actual offers
                    // Negative score ensures they're tried last, but still eligible
                    ModLog.log("AutoBuyerCore: New ship " + getShipName(npcShip) + " (ID: " + npcShip.getShipId() + 
                              ") has no offers but is eligible for retry " + (state.getNewShipRetryCount() + 1) + "/" + MAX_NEW_SHIP_RETRIES);
                    return new ShipPriority(npcShip, -1000, 0, 0, activeTrades);
                }
                return null;
            }
            
            int discountedItems = 0;
            int totalNeedValue = 0;
            
            // Analyze offers
            for (Map.Entry<Integer, Integer> entry : config.getAllTargetStocks().entrySet()) {
                int eid = entry.getKey();
                int target = entry.getValue();
                
                Trading.TradeItem offer = offers.get(eid);
                if (offer == null) continue;
                
                // Premium items can be purchased if stock is below Premium threshold (threshold system handles this)
                // No need to skip Premium items - let threshold check determine eligibility
                
                // Calculate need and current stock percentage
                int current = playerStation.getItemsOf(eid, true);
                int queued = getAlreadyQueuedInbound(world, playerStation, eid);
                int need = Math.max(0, target - current - queued);
                if (need <= 0) continue;
                
                // Check markup threshold for buying
                double stockPercent = target > 0 ? ((double)(current + queued) / target) * 100.0 : 0.0;
                int threshold = config.getBuyThreshold(offer.getTradeItemMode());
                
                // Only count if current stock percentage is below the threshold for this trade mode
                if (stockPercent >= threshold) {
                    continue; // Stock too high for this markup level
                }
                
                int avail = offer.howMuch;
                if (avail <= 0) continue;
                
                // Count discounted items
                if (offer.getTradeItemMode() == TradingHelper.TradeItemMode.Discounted) {
                    discountedItems++;
                }
                
                // Add to total need value (items we need × quantity available, weighted by priority)
                int priority = getTradeModePriority(offer.getTradeItemMode());
                int value = Math.min(need, avail) * (4 - priority); // Discounted=4, Neutral=3, Markup=2
                totalNeedValue += value;
            }
            
            // Calculate priority score
            // Higher score = better ship to trade with
            int score = (discountedItems * 100) + (totalNeedValue / 10) - (activeTrades * 20);
            
            return new ShipPriority(npcShip, score, discountedItems, totalNeedValue, activeTrades);
            
        } catch (Exception e) {
            ModLog.log("AutoBuyerCore: Exception calculating priority for ship " + getShipName(npcShip) + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Flush state for a ship (called when ship leaves).
     */
    public void flushShipState(int shipId) {
        flushShipState(shipId, null);
    }
    
    /**
     * Flush state for a ship (called when ship leaves).
     * Completely removes ship from memory. If ship returns, it will start fresh at trade 1 of 8.
     * Overload with Ship object for better logging.
     */
    public void flushShipState(int shipId, Ship ship) {
        ShipState removedState = shipStates.remove(shipId);
        String shipName = ship != null ? getShipName(ship) : "ID: " + shipId;
        if (removedState != null) {
            int tradesCreated = removedState.getTotalTradesCreated();
            ModLog.log("AutoBuyerCore: Flushed state for ship " + shipName + 
                      " (had " + tradesCreated + " trades). If ship returns, will start fresh at trade 1/8");
        } else {
            ModLog.log("AutoBuyerCore: Flushed state for ship " + shipName + " (no state found)");
        }
    }
    
    /**
     * Remove a trade ID from tracking (called when trade completes or is cancelled).
     * This prevents the trade ID set from growing indefinitely.
     */
    public void removeTradeId(int tradeId) {
        synchronized (tradeCreationLock) {
            createdTradeIds.remove(tradeId);
        }
    }
    
    /**
     * Update logistics item count from a player station.
     * This is called by the hook when logistics state changes.
     * We track the maximum count across all player stations.
     * @param itemCount number of free items waiting for logistics
     */
    public void updateLogisticsItemCount(int itemCount, World world) {
        int previousCount = this.logisticsItemCount;
        this.previousLogisticsItemCount = previousCount;
        
        // Track the maximum count (worst case across all stations)
        // Always update to the maximum we've seen, or reset if we get 0
        if (itemCount == 0 && this.logisticsItemCount > 0) {
            // If we get 0, reset (all stations cleared)
            this.logisticsItemCount = 0;
        } else if (itemCount > this.logisticsItemCount) {
            // Update to new maximum
            this.logisticsItemCount = itemCount;
        } else if (itemCount < this.logisticsItemCount && itemCount > 0) {
            // If we see a lower count, update it (logistics improved)
            // This allows us to detect when count falls to resume threshold
            this.logisticsItemCount = itemCount;
        }
        
        // Check if we crossed the cancel-all threshold (60 items) - cancel all trades and release all ships
        if (previousCount < LOGISTICS_CANCEL_ALL_THRESHOLD && itemCount >= LOGISTICS_CANCEL_ALL_THRESHOLD) {
            ModLog.log("AutoBuyerCore: Logistics cancel-all threshold reached (" + itemCount + " items >= " + LOGISTICS_CANCEL_ALL_THRESHOLD + ") - cancelling all trades and releasing all ships");
            if (world != null) {
                cancelAllTradesAndReleaseShips(world);
            }
        }
        
        // Check if we crossed the release-idle threshold (40 items) - release ships with no trades
        if (previousCount < LOGISTICS_RELEASE_IDLE_THRESHOLD && itemCount >= LOGISTICS_RELEASE_IDLE_THRESHOLD) {
            ModLog.log("AutoBuyerCore: Logistics release-idle threshold reached (" + itemCount + " items >= " + LOGISTICS_RELEASE_IDLE_THRESHOLD + ") - releasing ships with no open/pending trades");
            if (world != null) {
                releaseIdleShips(world);
            }
        }
        
        // Check if we crossed the pause threshold (40 items) - just pause, don't cancel trades
        if (previousCount < LOGISTICS_PAUSE_THRESHOLD && itemCount >= LOGISTICS_PAUSE_THRESHOLD) {
            ModLog.log("AutoBuyerCore: Logistics pause threshold reached (" + itemCount + " items >= " + LOGISTICS_PAUSE_THRESHOLD + ") - pausing auto-trading (keeping manual trades)");
        }
        
        // Check if we crossed the slowdown threshold (20 items) - slow down trading
        if (previousCount < LOGISTICS_SLOWDOWN_THRESHOLD && itemCount >= LOGISTICS_SLOWDOWN_THRESHOLD && itemCount < LOGISTICS_PAUSE_THRESHOLD) {
            ModLog.log("AutoBuyerCore: Logistics slowdown threshold reached (" + itemCount + " items >= " + LOGISTICS_SLOWDOWN_THRESHOLD + ") - slowing down auto-trading");
        }
        
        // Check if we fell back to resume threshold (20 items) - resume trading
        if (previousCount >= LOGISTICS_PAUSE_THRESHOLD && itemCount < LOGISTICS_RESUME_THRESHOLD) {
            ModLog.log("AutoBuyerCore: Logistics improved (" + itemCount + " items < " + LOGISTICS_RESUME_THRESHOLD + ") - resuming trade requests");
            if (itemCount < LOGISTICS_SLOWDOWN_THRESHOLD) {
                ModLog.log("AutoBuyerCore: Logistics cleared (" + itemCount + " items < " + LOGISTICS_SLOWDOWN_THRESHOLD + ") - resuming normal auto-trading");
            } else {
                ModLog.log("AutoBuyerCore: Logistics improved (" + itemCount + " items < " + LOGISTICS_PAUSE_THRESHOLD + ") - resuming slowed auto-trading");
            }
        } else if (previousCount >= LOGISTICS_SLOWDOWN_THRESHOLD && itemCount < LOGISTICS_SLOWDOWN_THRESHOLD) {
            // Logistics cleared below slowdown threshold
            ModLog.log("AutoBuyerCore: Logistics cleared (" + itemCount + " items < " + LOGISTICS_SLOWDOWN_THRESHOLD + ") - resuming normal auto-trading");
        }
    }
    
    /**
     * Release ships that have no active or pending trades.
     * Called when logistics reach 40 items - helps free memory without cancelling needed trades.
     */
    private void releaseIdleShips(World world) {
        try {
            Ship playerStation = findPlayerStation(world);
            if (playerStation == null) {
                ModLog.log("AutoBuyerCore: Cannot release idle ships - no player station found");
                return;
            }
            
            java.util.List<Integer> shipsToRelease = new java.util.ArrayList<>();
            
            // Find ships with no active trades
            for (Map.Entry<Integer, ShipState> entry : shipStates.entrySet()) {
                int shipId = entry.getKey();
                Ship ship = world.getShip(shipId);
                if (ship == null) {
                    shipsToRelease.add(shipId);
                    continue;
                }
                
                // Check if ship has any active trades
                int activeTrades = countActiveTradesWithNpc(world, shipId, playerStation.getShipId());
                
                // Release if no active trades
                if (activeTrades == 0) {
                    shipsToRelease.add(shipId);
                }
            }
            
            int releasedCount = 0;
            for (Integer shipId : shipsToRelease) {
                Ship ship = world.getShip(shipId);
                String shipName = ship != null ? getShipName(ship) : "ID: " + shipId;
                shipStates.remove(shipId);
                releasedCount++;
                ModLog.log("AutoBuyerCore: Released idle ship " + shipName + " (no active trades)");
            }
            
            ModLog.log("AutoBuyerCore: Released " + releasedCount + " idle ship(s) (ships with active trades kept)");
            
        } catch (Exception e) {
            ModLog.log("AutoBuyerCore: Exception releasing idle ships: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Cancel all active trades created by the mod and release all tracked ships.
     * Called when logistics reach critical threshold (60 items).
     */
    private void cancelAllTradesAndReleaseShips(World world) {
        try {
            Ship playerStation = findPlayerStation(world);
            if (playerStation == null) {
                ModLog.log("AutoBuyerCore: Cannot cancel trades - no player station found");
                return;
            }
            
            Array<Trading.TradeAgreement> allTrades = world.getTrades();
            int cancelledCount = 0;
            
            // Cancel all player trades (trades created by this mod)
            for (int i = allTrades.size - 1; i >= 0; i--) {
                Trading.TradeAgreement trade = allTrades.get(i);
                if (trade != null && trade.isPlayerTrade()) {
                    // Check if this trade involves the player station
                    Ship ship1 = world.getShip(trade.shipId1);
                    Ship ship2 = world.getShip(trade.shipId2);
                    
                    if ((ship1 != null && ship1.isPlayerShip() && ship1.isStation()) ||
                        (ship2 != null && ship2.isPlayerShip() && ship2.isStation())) {
                        // This is a trade created by the mod - cancel it
                        world.cancelTrade(trade, false);
                        removeTradeId(trade.id);
                        cancelledCount++;
                        ModLog.log("AutoBuyerCore: Cancelled trade " + trade.id + " due to logistics overload");
                    }
                }
            }
            
            ModLog.log("AutoBuyerCore: Cancelled " + cancelledCount + " trade(s) due to logistics overload");
            
            // Release all tracked ships
            java.util.List<Integer> shipsToRelease = new java.util.ArrayList<>();
            for (Map.Entry<Integer, ShipState> entry : shipStates.entrySet()) {
                shipsToRelease.add(entry.getKey());
            }
            
            int releasedCount = 0;
            for (Integer shipId : shipsToRelease) {
                Ship ship = world.getShip(shipId);
                String shipName = ship != null ? getShipName(ship) : "ID: " + shipId;
                shipStates.remove(shipId);
                releasedCount++;
                ModLog.log("AutoBuyerCore: Released ship " + shipName + " due to logistics overload");
            }
            
            ModLog.log("AutoBuyerCore: Released " + releasedCount + " ship(s) due to logistics overload");
            
        } catch (Exception e) {
            ModLog.log("AutoBuyerCore: Exception cancelling trades and releasing ships: " + e.getMessage());
            ModLog.log(e);
        }
    }
    
    /**
     * Get current logistics item count.
     * @return number of free items waiting for logistics
     */
    public int getLogisticsItemCount() {
        return logisticsItemCount;
    }
    
    /**
     * Reset flags for a ship when a trade completes or is cancelled.
     * This allows us to check again since a slot may have freed up.
     */
    public void resetShipFlags(int shipId) {
        resetShipFlags(shipId, null);
    }
    
    /**
     * Reset flags for a ship when a trade completes or is cancelled.
     * Overload with Ship object for better logging.
     */
    public void resetShipFlags(int shipId, Ship ship) {
        ShipState state = shipStates.get(shipId);
        if (state != null) {
            state.setMaxTradesReached(false);
            state.setNothingToPurchase(false);
            // CRITICAL: Invalidate cached offers since we just purchased items
            // NPC stock has changed, so we need fresh availability data
            state.clearOffersSnapshot();
            String shipName = ship != null ? getShipName(ship) : "ID: " + shipId;
            ModLog.log("AutoBuyerCore: Reset flags and invalidated offers cache for ship " + shipName + " (trade slot may have freed)");
        }
    }
    
    /**
     * Per-ship state tracking.
     */
    private static class ShipState {
        private Map<Integer, Trading.TradeItem> offersSnapshot = new HashMap<>();
        private int lastOffersRefreshTime = 0;
        private long lastAttemptTimeMillis = 0;
        private static long attemptCounter = 0; // Global counter for fallback timing
        
        // Optimization flags: avoid unnecessary checks
        private boolean maxTradesReached = false;  // True if we've hit 4 trades (don't check again)
        private boolean nothingToPurchase = false; // True if last check found nothing (don't check again)
        
        // Track total trades created with this ship (limit: 8 per ship per visit)
        private int totalTradesCreated = 0;
        
        // New ship handling: prevent premature "nothing to purchase" marking
        private boolean isNewShip = false; // True when ship first arrives
        private int newShipRetryCount = 0; // Number of retry attempts for new ships
        private long newShipFirstSeenTimeMillis = 0; // Timestamp when ship was first marked as new (for 10-second delay)
        
        public int getTotalTradesCreated() {
            return totalTradesCreated;
        }
        
        public void incrementTotalTradesCreated() {
            totalTradesCreated++;
        }
        
        public Map<Integer, Trading.TradeItem> getOffersSnapshot() {
            return offersSnapshot;
        }
        
        public void setOffersSnapshot(Map<Integer, Trading.TradeItem> offers) {
            this.offersSnapshot = new HashMap<>(offers);
        }
        
        public void clearOffersSnapshot() {
            this.offersSnapshot.clear();
        }
        
        public boolean shouldRefreshOffers(World world, int cooldownTicks) {
            if (cooldownTicks <= 0) {
                return offersSnapshot.isEmpty();
            }
            // Simple time-based check (world doesn't expose tick time directly, so use a simple approach)
            // For now, refresh if empty or if enough time has passed
            return offersSnapshot.isEmpty() || 
                   (world.getTrades().size == 0); // Simple heuristic: refresh when no trades
        }
        
        public void setLastOffersRefreshTime(World world) {
            // Store current time somehow - for now just mark as refreshed
            this.lastOffersRefreshTime = world.getTrades().size; // Simple heuristic
        }
        
        /**
         * Check if this ship is still in cooldown period.
         * Uses millisecond-based timing for accuracy.
         * 
         * @param world World instance (for potential future tick-based timing)
         * @param cooldownTicks Cooldown period in ticks (converted to milliseconds)
         * @return true if still in cooldown, false if cooldown expired or disabled
         */
        public boolean isInCooldown(World world, int cooldownTicks) {
            if (cooldownTicks <= 0) {
                return false; // Cooldown disabled
            }
            
            if (lastAttemptTimeMillis == 0) {
                return false; // Never attempted, no cooldown
            }
            
            // Convert ticks to milliseconds
            // Assuming ~60 ticks per second (common game tick rate)
            // 1 tick ≈ 16.67ms, so cooldownTicks * 16.67 ≈ milliseconds
            long cooldownMillis = (long)(cooldownTicks * 16.67);
            long currentTime = System.currentTimeMillis();
            long timeSinceLastAttempt = currentTime - lastAttemptTimeMillis;
            
            if (timeSinceLastAttempt < cooldownMillis) {
                return true; // Still in cooldown
            }
            
            return false; // Cooldown expired
        }
        
        public void setLastAttemptTime(World world) {
            this.lastAttemptTimeMillis = System.currentTimeMillis();
            attemptCounter++;
        }
        
        public long getLastAttemptTimeMillis() {
            return lastAttemptTimeMillis;
        }
        
        public boolean isMaxTradesReached() {
            return maxTradesReached;
        }
        
        public void setMaxTradesReached(boolean maxTradesReached) {
            this.maxTradesReached = maxTradesReached;
        }
        
        public boolean isNothingToPurchase() {
            return nothingToPurchase;
        }
        
        public void setNothingToPurchase(boolean nothingToPurchase) {
            this.nothingToPurchase = nothingToPurchase;
        }
        
        public boolean isNewShip() {
            return isNewShip;
        }
        
        public void setNewShip(boolean isNewShip) {
            this.isNewShip = isNewShip;
            if (isNewShip) {
                this.newShipRetryCount = 0; // Reset retry count when marking as new
                // Record timestamp when ship is first marked as new (for 10-second delay)
                if (this.newShipFirstSeenTimeMillis == 0) {
                    this.newShipFirstSeenTimeMillis = System.currentTimeMillis();
                }
            }
        }
        
        /**
         * Check if the 10-second delay has passed since this ship was first marked as new.
         * @return true if 10 seconds have passed (or ship is not new), false if still waiting
         */
        public boolean hasNewShipDelayPassed() {
            if (!isNewShip || newShipFirstSeenTimeMillis == 0) {
                return true; // Not a new ship or no timestamp recorded, allow query
            }
            long currentTime = System.currentTimeMillis();
            long timeSinceFirstSeen = currentTime - newShipFirstSeenTimeMillis;
            return timeSinceFirstSeen >= 10000; // 10 seconds = 10000 milliseconds
        }
        
        /**
         * Get the timestamp when this ship was first marked as new.
         * @return timestamp in milliseconds, or 0 if not a new ship
         */
        public long getNewShipFirstSeenTimeMillis() {
            return newShipFirstSeenTimeMillis;
        }
        
        public int getNewShipRetryCount() {
            return newShipRetryCount;
        }
        
        public void incrementNewShipRetryCount() {
            this.newShipRetryCount++;
        }
        
        public boolean hasExceededNewShipRetries(int maxRetries) {
            return newShipRetryCount >= maxRetries;
        }
    }
    
    /**
     * Helper class to hold ship priority information for sorting.
     */
    private static class ShipPriority {
        Ship ship;
        int score;
        int discountedItems;
        int totalNeedValue;
        int activeTrades;
        
        public ShipPriority(Ship ship, int score, int discountedItems, int totalNeedValue, int activeTrades) {
            this.ship = ship;
            this.score = score;
            this.discountedItems = discountedItems;
            this.totalNeedValue = totalNeedValue;
            this.activeTrades = activeTrades;
        }
    }
}

