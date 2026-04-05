# Chest Tool Retrieval Design

**Date:** 2026-04-04
**Status:** Approved
**Scope:** General tool retrieval from registered chests; woodcut axe retrieval as first consumer

## Problem

When a bot runs out of axes during woodcut, it degrades to mining with bare hands. The player may have stashed axes in nearby chests, but the bot has no way to retrieve them. This wastes time and breaks the early-game survival flow.

## Decisions

- **Allowed axes:** wooden, stone, copper only. No iron/diamond/netherite (too valuable for early-game tree chopping). No enchanted axes.
- **Trigger:** Both at woodcut start (`prepareWoodcutTooling`) AND mid-session when the last axe breaks (`ensureAxeEquipped` calls).
- **Range:** 32 blocks from bot's current position.
- **Priority:** Best tier first (copper > stone > wooden), then nearest chest.
- **Scope:** General `ToolProvisionService` method, not woodcut-specific. Other skills can reuse it with different predicates.

## Design

### New API in ToolProvisionService

```java
/**
 * Walk to a registered chest and retrieve one tool matching the given criteria.
 *
 * @param bot            the bot
 * @param world          server world
 * @param source         command source for movement/interaction
 * @param snapshotFilter pre-filter on ItemSnapshot (cheap, avoids walking to wrong chests)
 * @param stackPredicate validates actual ItemStack (enchantment checks, tier filtering)
 * @param stackComparator ranks matching tools (best first)
 * @param maxRange       max block distance to search
 * @return true if a tool was retrieved and is now in the bot's inventory
 */
public static boolean retrieveToolFromChests(
        ServerPlayerEntity bot,
        ServerWorld world,
        ServerCommandSource source,
        Predicate<ItemSnapshot> snapshotFilter,
        Predicate<ItemStack> stackPredicate,
        Comparator<ItemSnapshot> snapshotComparator,
        int maxRange)
```

### Flow

1. Refresh snapshots synchronously on the server thread using `CompletableFuture` + `server.execute()` (the same `callOnServer` pattern used throughout `ChestStoreService`). Block entities must be read on the server thread to avoid races with chunk unloading. The worker thread blocks until the refresh completes before proceeding to step 2.
2. `BotChestRegistryService.listChestsForOwner(bot, world)` — get all non-destroyed chests (uses owner-level sharing, so sibling bot chests are included)
3. Filter by distance <= maxRange from bot's current position
4. Filter chests whose `contentsSnapshot` is non-null and contains items matching `snapshotFilter`
5. Sort candidates: best tool tier first (via `snapshotComparator` on best matching snapshot item), then nearest chest as tiebreaker
6. For each candidate chest (in order):
   a. `ChestStoreService.withdrawMatchingWalkOnly(source, bot, chestPos, 1, stackPredicate)`
   b. If withdrawal > 0 → log success, send chat message ("Found an axe in a nearby chest"), return true
   c. If fails (empty/stale snapshot, destroyed, unreachable) → log, try next candidate
7. All candidates exhausted → return false

**Note on snapshot staleness:** `refreshAllSnapshots` uses `botKey(bot)` (bot's own chests only), while `listChestsForOwner` uses owner-level sharing (includes sibling bot chests). Sibling chests may have stale snapshots. This is acceptable — the actual `withdrawMatchingWalkOnly` call validates against real chest contents, and the candidate loop moves on if the axe is gone.

### Axe-specific helpers (static, in ToolProvisionService)

```java
// Snapshot pre-filter: matches "minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:copper_axe"
private static final Set<String> ALLOWED_AXE_IDS = Set.of(
        "minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:copper_axe");

public static Predicate<ItemSnapshot> allowedAxeSnapshotFilter() {
    return snap -> snap != null && ALLOWED_AXE_IDS.contains(snap.itemId);
}

// Stack validation: must be one of the allowed axes AND not enchanted
public static Predicate<ItemStack> allowedWoodcutAxePredicate() {
    return stack -> {
        if (stack == null || stack.isEmpty()) return false;
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        if (!ALLOWED_AXE_IDS.contains(id)) return false;
        // Reject enchanted axes
        if (stack.hasEnchantments()) return false;
        // Reject near-broken (< 8 durability remaining)
        if (stack.isDamageable()) {
            int remaining = stack.getMaxDamage() - stack.getDamage();
            if (remaining < 8) return false;
        }
        return true;
    };
}

// Snapshot ranking: copper > stone > wooden
private static final Map<String, Integer> AXE_TIER_RANK = Map.of(
        "minecraft:copper_axe", 3,
        "minecraft:stone_axe", 2,
        "minecraft:wooden_axe", 1);

public static Comparator<ItemSnapshot> axeTierComparator() {
    return Comparator.comparingInt(
            snap -> -AXE_TIER_RANK.getOrDefault(snap.itemId, 0));
}
```

### WoodcutSkill integration

**`prepareWoodcutTooling()`** updated sequence:

1. `selectAxe(bot)` → return if found in inventory
2. `ToolProvisionService.ensureAxe(bot, source, commander)` → return if crafted
3. **NEW:** `ToolProvisionService.retrieveToolFromChests(bot, world, source, allowedAxeSnapshotFilter(), allowedWoodcutAxePredicate(), axeTierComparator(), 32)` → if success, `selectAxe(bot)` and return
4. Fall back to bare hands with chat message (existing)

**New `ensureAxeOrRetrieve()` method** — thin wrapper for 4 mid-session call sites where the bot is actively felling trees:

```java
private boolean ensureAxeOrRetrieve(ServerPlayerEntity bot) {
    if (ensureAxeEquipped(bot)) return true;
    if (isAbortRequested(bot)) return false;
    if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
    ServerCommandSource source = bot.getCommandSource();
    boolean retrieved = ToolProvisionService.retrieveToolFromChests(
            bot, world, source,
            ToolProvisionService.allowedAxeSnapshotFilter(),
            ToolProvisionService.allowedWoodcutAxePredicate(),
            ToolProvisionService.axeTierComparator(),
            32);
    if (retrieved) return ensureAxeEquipped(bot);
    return false;
}
```

**Call site conversion** (4 of 6 `ensureAxeEquipped` calls become `ensureAxeOrRetrieve`):

| Line | Context | Convert? | Reason |
|------|---------|----------|--------|
| ~1959 | descent sweep mining | Yes | Mid-tree, axe needed for logs |
| ~2229 | branch mining | Yes | Mid-tree, axe needed for logs |
| ~3917 | breakLeaf log case | Yes | Active felling |
| ~4484 | mineBlockDetailed preferAxe | Yes | Active felling |
| ~4791 | `selectAxe()` delegate | **No** | Called from `prepareWoodcutTooling` which has its own explicit chest retrieval step — converting would bypass craft-first order |
| ~4874 | `selectAdaptiveToolOrHands` | **No** | Misc axe-mineable blocks during cleanup — not worth a 32-block walk |

### Abort awareness

The chest retrieval involves walking (potentially up to 32 blocks). The `withdrawMatchingWalkOnly` path already respects movement abort signals. The `ensureAxeOrRetrieve` wrapper checks `isAbortRequested(bot)` before attempting retrieval to avoid starting a walk when the skill is being cancelled.

## Files touched

| File | Change |
|---|---|
| `ToolProvisionService.java` | `retrieveToolFromChests()`, axe snapshot filter, axe stack predicate, axe tier comparator |
| `WoodcutSkill.java` | `ensureAxeOrRetrieve()` wrapper, update `prepareWoodcutTooling()`, convert 4 of 6 call sites |

## Not included

- Material retrieval for crafting (future backlog)
- Bundle-aware inventory scanning (separate P1 item)
- UI toggle for allowed tool tiers (future)
- Other skill consumers (mining pickaxes, etc.) — infrastructure is ready, just needs predicates
