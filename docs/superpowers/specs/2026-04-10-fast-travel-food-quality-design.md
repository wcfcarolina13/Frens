# Fast-Travel Food Quality & Magic Bypass

**Date:** 2026-04-10
**Status:** Approved

## Problem

The fast-travel food budget calculation has several gaps:
1. It counts all food indiscriminately — rotten flesh and golden apples inflate the budget even though the bot won't (or shouldn't) eat them
2. Food inside bundles and shulker boxes is invisible to the budget calculation
3. The "not enough energy" rejection message is confusing and lacks actionable detail
4. Magic-based travel (spells consuming ender pearls) still enforces food requirements despite having a separate reagent cost

## Changes

### 1. Food Quality Filtering

**Two new food classification sets in HealingService:**

- **`FORBIDDEN_FOODS`** (existing, unchanged): `rotten_flesh`, `poisonous_potato`, `spider_eye`, `pufferfish`, `suspicious_stew` — toxic foods with negative effects. Skipped by normal eating. Only consumed at starvation emergency via `findDesperateFood()`.

- **`PRECIOUS_FOODS`** (new): `golden_apple`, `enchanted_golden_apple`, `golden_carrot` — too valuable for casual consumption. Skipped by `findCheapestSafeFood()` and fast-travel budget calculation. CAN be eaten at starvation emergency (same desperation path as forbidden foods).

**Modify `findDesperateFood()`** to also consider PRECIOUS_FOODS when no forbidden food (rotten flesh etc.) is available. Current implementation only returns forbidden foods; after this change, the desperation fallback chain is: forbidden foods first, then precious foods.

**New public predicate:** `HealingService.isTravelUsableFood(ItemStack)` — returns true only if the item has a FoodComponent AND is not in FORBIDDEN_FOODS or PRECIOUS_FOODS. Used by both NavigationArtifactService (budget calculation) and any future food-related travel logic.

**Files:** `HealingService.java`, `NavigationArtifactService.java`

### 2. Container Food Extraction

Before the fast-travel budget is calculated, the bot extracts food from containers in its inventory to its main inventory slots.

**Scan targets (recursive, max depth 2 — depth 3 as safety margin):**
1. **Bundles** — via `DataComponentTypes.BUNDLE_CONTENTS`, `bundle.iterate()`
2. **Shulker boxes** — via `DataComponentTypes.CONTAINER` component; use `ContainerComponent.streamNonEmpty()` to iterate, `ContainerComponent.fromStacks()` to rebuild after extraction (no Builder class — unlike bundles, the component is rebuilt from a filtered list)
3. **Nested containers** — shulker inside bundle, bundle inside shulker. Vanilla prevents shulker-in-shulker and bundle-in-bundle (since 1.21.2), so real nesting depth is 2.

**NOT scanned (deferred):**
- **Ender chests** — requires investigation into how `EnderChestInventory` interacts with fake player entities. Note for future work.

**Extraction logic:**
1. Calculate needed nutrition: `ceil(hungerCost) + MIN_POST_TRAVEL_FOOD - mainInventoryNutrition`
2. If needed <= 0, skip extraction (main inventory has enough)
3. Recursively scan containers, build list of (food ItemStack, nutrition score, container reference)
4. Sort by nutrition score ascending (cheapest first, same scoring as `findCheapestSafeFood`)
5. Extract items cheapest-first until needed nutrition is met
6. For bundles: rebuild `BundleContentsComponent` without the extracted items
7. For shulker boxes: update `CONTAINER` component with the food removed
8. Place extracted food in bot's main inventory (first empty/stackable slot). If main inventory is full, skip extraction — the budget check will fail naturally and the provisions message will guide the player
9. All mutations happen on the server thread

**Files:** `NavigationArtifactService.java`

### 3. Provisions Message Rephrase

**Current message:** `"[botAlias] doesn't have enough energy to travel that far. Feed them first."`

**New message:**
```
[botAlias] needs provisions for this journey — roughly [N] cooked steak worth of food (~[X] hunger points). Pack extra before sending them off.
```

**Calculation:**
- `shortfall = ceil(hungerCost) + MIN_POST_TRAVEL_FOOD - totalBudget`
- `steakEstimate = ceil(shortfall / 8.0)` (cooked steak = 8 nutrition)
- Both the steak count and raw hunger points are displayed so the player can use whatever food they have

**Files:** `NavigationArtifactService.java`

### 4. Magic Bypasses Food Requirements

When travel is triggered by a spell that consumes reagents (ender pearls, chorus fruit), food requirements are bypassed entirely. The reagent cost IS the price.

**Implementation:**
- Add `boolean magicTravel` parameter to the private `beginDelayedTravel()` overload
- New public entry point: `beginMagicTravel(...)` that sets `magicTravel=true`
- **Extend `PendingTravel` record** with a `boolean magicTravel` field so the arrival handler knows to skip hunger drain (the record is created at departure and read at arrival)
- When `magicTravel=true`:
  - **Food safety gate is skipped** — the bot can depart regardless of food level
  - **Hunger drain on arrival is skipped** — no hunger cost applied after teleportation
- Cooldown gate and other gates remain enforced

**Note:** The private overload already has 3 boolean flags (`skipGates`, `suppressOwnerNotify`, `skipArtifactGate`). Adding a 4th is not ideal but follows the established pattern. A future cleanup could bundle these into an options enum set.

**Callers:**
- `SpellNavigationNetworkManager.handleRemoteGuidance()` → uses `beginMagicTravel()` instead of `beginDelayedTravel()`
- Chorus Recall → already bypasses everything (instant teleport, no `beginDelayedTravel` call) — unchanged
- Emergency travel → already uses `skipGates=true` — unchanged
- Normal/sunset travel → unchanged (`magicTravel=false`)

**Files:** `NavigationArtifactService.java`, `SpellNavigationNetworkManager.java`

### 5. Bot-to-Bot Artifact Scan (Already Implemented)

`tryBotToBotArtifactTeleport()` in NavigationArtifactService already scans all registered bots within 32 blocks of the destination, checks `hasReceiverTier2Access()`, and triggers fast-travel if a suitable receiver is found. The sunset return path calls this before falling through to normal travel. **No changes needed.**

## Items Deferred

- **Item 2** (pre-travel confirmation showing food cost): Shelved — current HUD confirmation works fine without extra data
- **Item 5** (admin sunset fast-travel toggle): Existing "Auto Home @ Sunset" toggle is sufficient for now
- **Item 8** (tooltips and guide entries): Deferred until all code changes above are complete
- **Ender chest food scanning**: Requires investigation into fake player `EnderChestInventory` behavior

## Testing

Manual in-game verification:
- Give bot only rotten flesh + golden apples → verify fast-travel is rejected (0 usable food)
- Give bot food inside a bundle → verify it's extracted and counted for travel
- Give bot food inside a shulker box → verify extraction works
- Verify provisions message shows steak estimate
- Cast Remote Guidance with starving bot → verify travel proceeds (magic bypass)
- Verify normal sunset return still requires food
- Verify starvation-level eating still allows golden/precious food as last resort
