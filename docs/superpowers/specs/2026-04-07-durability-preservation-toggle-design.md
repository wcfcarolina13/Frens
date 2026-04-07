# Durability Preservation Toggle — Design Spec

**Date:** 2026-04-07
**Status:** Draft

## Problem

Bots treat every tool, weapon, and armor piece as disposable. Give a bot a diamond pickaxe and it will mine cobble with it down to zero durability; hand it an Unbreaking III fishing rod and it'll yank kelp with it until the rod snaps. Players who invest time into enchanted or precious gear have no way to tell their companions "use the cheap stuff for chores — save the shiny for when it matters."

There is no runtime check on material tier, enchantment presence, or remaining durability at any of the bot's tool/armor/weapon selection sites. Every selection helper in `PlayerUtils/` (`ToolSelector`, `armorUtils`, `CombatInventoryManager`) picks purely on effectiveness — durability is ignored entirely, material tier is only considered in the "stronger = better" direction.

## Solution

A per-player opt-in toggle — **Preserve Expensive Gear** — that makes every bot owned by that player refuse to use items matching *either* of:

- **Preserved material**: gold, diamond, or netherite tools/armor, plus the turtle-shell helmet
- **Enchanted**: any `ItemStack` with at least one enchantment, regardless of material tier

...once the item's remaining durability drops below **11%** (normal) or **3%** (while the bot is in combat).

When a selection site is blocked by the policy, a new fallback service walks the bot through: inventory re-scan → registered chest retrieval → crafting table fallback → stand-down. Overhead dialogue fires at each transition so the player gets in-character feedback instead of a silent bot.

The setting is **per-player** (one flag keyed by player UUID) and applies to every bot that player owns. Default: **OFF**.

## Rule Specification

### Preserved material set (hardcoded 28 items)

Gold tier: `GOLDEN_PICKAXE`, `GOLDEN_AXE`, `GOLDEN_SHOVEL`, `GOLDEN_HOE`, `GOLDEN_SWORD`, `GOLDEN_HELMET`, `GOLDEN_CHESTPLATE`, `GOLDEN_LEGGINGS`, `GOLDEN_BOOTS`.

Diamond tier: `DIAMOND_PICKAXE`, `DIAMOND_AXE`, `DIAMOND_SHOVEL`, `DIAMOND_HOE`, `DIAMOND_SWORD`, `DIAMOND_HELMET`, `DIAMOND_CHESTPLATE`, `DIAMOND_LEGGINGS`, `DIAMOND_BOOTS`.

Netherite tier: `NETHERITE_PICKAXE`, `NETHERITE_AXE`, `NETHERITE_SHOVEL`, `NETHERITE_HOE`, `NETHERITE_SWORD`, `NETHERITE_HELMET`, `NETHERITE_CHESTPLATE`, `NETHERITE_LEGGINGS`, `NETHERITE_BOOTS`.

Special: `TURTLE_HELMET`.

Leather, chainmail, copper, stone, wood, iron tools/armor are **not** preserved by material. (They are still preserved if enchanted — see next section.)

### Enchantment detection

Any `ItemStack` where `stack.getEnchantments().getSize() > 0` is considered enchanted. Curse enchantments count. The rule is literal: any enchantment flags the item as preserved.

### Non-material-tiered items

Items without a material tier — bow, crossbow, fishing rod, shield, shears, flint & steel, trident, elytra, mace, brush, carrot-on-stick, warped-fungus-on-stick — are **never** preserved by material. They **are** preserved if enchanted. A Mending fishing rod gets preserved; an unenchanted one does not.

### Threshold resolution

- `threshold = 0.03` if `BotCombatCalloutService.isInCombat(bot.getUuid())` is true
- `threshold = 0.11` otherwise

The combat threshold applies to **every** durability item the bot *newly selects* during combat, not just combat-relevant items. If a bot happens to pick up a diamond pickaxe mid-combat (rare but possible during a forced swap), the 3% rule applies to that pickaxe too.

**Important clarification — the threshold is evaluated at selection time, not continuously.** A tool already equipped when combat starts is not re-selected; the bot keeps using it until it's retired for another reason (slot swap, skill transition, fallback trigger). The combat threshold therefore only affects *new* selection decisions made while `isInCombat()` is true. Conversely, when combat ends mid-skill, a previously-filtered item becomes eligible again on the next selection call — the bot does not auto-swap.

**Armor corollary.** Because armor is equip-time only (see Hook Sites section), the combat threshold has no practical effect on armor. A diamond chestplate equipped at 80% before combat stays on through combat even if it drops below 3%; it is never re-evaluated. This is intentional — mid-combat armor stripping is worse than taking the hit.

### Durability ratio

```text
ratio = (max - damage) / max      // non-damageable items return 1.0
```

An item is "below threshold" when `ratio < threshold` (strict less-than).

### Main predicate

```text
shouldAvoid(bot, stack) := stack is non-empty
                        && stack is damageable
                        && isPolicyEnabled(bot)
                        && isPreserved(stack)
                        && durabilityRatio(stack) < currentThreshold(bot)
```

Where `isPreserved(stack) := isPreservedMaterial(stack) || isEnchanted(stack)`.

The `stack is damageable` check comes first, which naturally eliminates enchanted books, enchanted golden apples, potions, and other enchanted-but-non-damageable items — the toggle does not interfere with those. The `stack is non-empty` check is a convenience guard for callers that pass raw hotbar slots.

When `shouldAvoid` returns true, selection sites skip the stack as if it didn't exist.

### Mending enchantment interaction

Mending is treated as any other enchantment — an item with Mending is preserved. This is a deliberate choice: if the player wants a Mending item used continuously to benefit from XP self-repair, they should disable the toggle (it's a per-player preference, flipping it takes one click). The guide entry explicitly calls this out so players aren't surprised when a Mending diamond sword gets filtered at 10%.

### Offhand coverage

Both main hand and offhand selection sites are filtered. The shield selection in `CombatInventoryManager` is the primary offhand hook; totem-of-undying is non-damageable so it is unaffected.

## Data Model

### Player preference storage

New field in `FilingSystem/ManualConfig.java`:

```java
private final Map<String, Boolean> playerPreserveExpensiveGear = new HashMap<>();
```

- Keyed by player UUID `.toString()` (not raw `UUID`; matches the existing `botOwnership` map convention in the same file)
- Default value (absent key) = `false` (toggle OFF)
- Persists to the same JSON as the rest of ManualConfig
- No migration needed — absent keys just default to OFF

Accessors convert `UUID` to string internally:

```java
public boolean getPreserveExpensiveGear(UUID playerUuid)  // internally: playerPreserveExpensiveGear.getOrDefault(playerUuid.toString(), false)
public void setPreserveExpensiveGear(UUID playerUuid, boolean value)  // internally: playerPreserveExpensiveGear.put(playerUuid.toString(), value)
```

### Fallback cooldown state (transient, in-memory)

In `DurabilityFallbackService`:

```java
// bot UUID → category → last attempt timestamp (ms)
private static final Map<UUID, EnumMap<GearCategory, Long>> LAST_ATTEMPT = new ConcurrentHashMap<>();
private static final long COOLDOWN_MS = 20_000L;
```

**Lifecycle rules:**

- Cleared on server stop (SERVER_STOPPING handler)
- Cleared on bot removal from the world (despawn, kick, world unload)
- **Cleared on bot death.** A respawned bot drops its inventory and starts empty; it should be allowed to immediately re-attempt gear refresh without sitting out a stale 20s cooldown.
- **Cleared on toggle-on.** When a player flips their preference from OFF → ON, any stale cooldowns for bots owned by that player are cleared so the first selection after the flip gets a fresh attempt. (Flipping OFF → ON is the user-visible event; no clearing needed on ON → OFF because the policy is simply disabled.)
- Not persisted across server restart — fresh start on reload is fine.

## Services

### DurabilityPolicyService (new, stateless)

`GameAI/services/DurabilityPolicyService.java`

Pure static, no instance state. Fully unit-reasonable in isolation.

**Public API:**

```java
public static boolean isPreservedMaterial(ItemStack stack)
public static boolean isEnchanted(ItemStack stack)
public static boolean isPreserved(ItemStack stack)
public static double durabilityRatio(ItemStack stack)
public static double currentThreshold(ServerPlayerEntity bot)
public static boolean isPolicyEnabled(ServerPlayerEntity bot)
public static boolean shouldAvoid(ServerPlayerEntity bot, ItemStack stack)
```

**Internal:**

- `PRESERVED_ITEMS` — `Set<Item>` of the 28 hardcoded items
- Owner resolution via `BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot)`; null owner ⇒ policy disabled

### DurabilityFallbackService (new)

`GameAI/services/DurabilityFallbackService.java`

Orchestrates the chest/craft/stand-down chain when a selection site finds no compliant option.

**Gear categories (enum):**

```text
PICKAXE, AXE, SHOVEL, HOE, SWORD, MACE, SHIELD,
BOW, CROSSBOW, TRIDENT, FISHING_ROD,
HELMET, CHESTPLATE, LEGGINGS, BOOTS, ELYTRA,
SHEARS
```

Items not in the enum (carrot-on-stick, warped-fungus-on-stick, brush, flint & steel) are still filtered at their selection sites via `DurabilityPolicyService.shouldAvoid`, but they do not trigger the fallback chain when filtered. For those items, the selection site simply returns "no tool" and the calling code handles it via its existing no-tool path. These items are either niche (brush, carrot stick) or absent from the codebase (flint & steel — confirmed via grep at design time).

**Entry point:**

```java
public static void requestRefresh(ServerPlayerEntity bot, GearCategory category, SkillSource source)
```

- Checks the per-bot per-category cooldown; if within 20s of the last attempt, returns immediately.
- Schedules the actual work on a worker thread (threading rules: chest walks and crafting cannot block the server thread).
- Marks the attempt timestamp even on failure so the cooldown applies to all paths.

**Worker-thread flow:**

1. **Inventory re-scan** — sweep all 36 inventory slots for any stack matching the category that passes `!shouldAvoid(bot, stack)`. If found, schedule a hotbar swap (or `equipStack` for armor) via `server.execute(...)`. Done.
2. **Chest retrieval** — call `ToolProvisionService.retrieveToolFromChests(bot, world, source, snapshotFilter, stackPredicate, comparator, maxRange)` with:
   - `stackPredicate` = matches category AND `!DurabilityPolicyService.shouldAvoid(bot, stack)` AND `durabilityRatio(stack) >= 0.25`
   - The 25% floor prevents swap-thrashing between two near-threshold items: without it, a 10% diamond pickaxe could be "replaced" by a 15% diamond pickaxe which itself would be filtered on the next skill tick. The 25% floor ensures the replacement has enough headroom to matter.
3. **Crafting fallback** — for tool categories with an `ensureX` helper in `ToolProvisionService` (pickaxe, axe, shovel, sword), call it with `allowWoodenFallback=true`. **Armor categories (helmet, chestplate, leggings, boots, elytra) and non-tool categories (shield, bow, crossbow, trident, fishing rod, mace, shears) have no `ensureX` helper — skip the crafting step entirely and proceed to step 4.**
4. **Stand down** — log the reason, fire `CompanionOverheadDialogueService.tryShowGearNoReplacement(bot)`, and return. The calling skill will see `null` from its selector, bail via its existing no-tool path, and `SkillResumeService` will re-attempt naturally.

**Race conditions across bots.** Two bots owned by the same (or different) players can simultaneously hit `shouldAvoid` and race to retrieve the last matching item from the same chest. This is accepted as designed: `ToolProvisionService.retrieveToolFromChests` is independently invoked per bot; whichever arrives first wins. The loser walks to the chest, finds nothing matching, and stands down with the 20s cooldown applied — preventing a thrash loop. No reservation or locking is added.

**Threading:** all chest-walks, crafting calls, and inventory mutations that must run on the server thread are dispatched via `server.execute(...)`. The worker thread does I/O-free scanning and decision logic only.

**Executor.** `DurabilityFallbackService` uses a dedicated single-thread `ExecutorService fallbackExecutor` (created via `Executors.newSingleThreadExecutor` with a named thread factory `"frens-durability-fallback"`). Work is queued unbounded; fallback tasks are infrequent due to the 20s cooldown so the queue cannot realistically grow. A static `shutdownExecutors()` method is called from the `SERVER_STOPPING` handler in `Frens.java`, matching the existing pattern for the 8 other mod executors listed in the CLAUDE.md memory note on executor shutdown.

### CompanionOverheadDialogueService additions

New line pools (append to the existing constants block):

```java
GEAR_PRESERVE_SWAP_LINES      // ~10 lines, 11% swap trigger
GEAR_COMBAT_EDGE_LINES        // ~6 lines, 3% combat edge
GEAR_NO_REPLACEMENT_LINES     // ~8 lines, fallback exhausted
```

New methods, mirroring `tryShowLeafStuck`:

```java
public static boolean tryShowGearPreserveSwap(ServerPlayerEntity bot)
public static boolean tryShowGearCombatEdge(ServerPlayerEntity bot)
public static boolean tryShowGearNoReplacement(ServerPlayerEntity bot)
```

Each uses a **per-bot 30s cooldown** and the standard 4s global suppression. Each gates on `isGlobalTextDialogueEnabled()` and picks a random line from its pool. Duration matches existing dialogue (~2.8s).

**Line copy** (final):

`GEAR_PRESERVE_SWAP_LINES`:

- "Been using this one a while. Gonna hang it up."
- "Not risking the good stuff on this."
- "This blade's getting thin — grabbing something else."
- "Yeah no, too nice to wreck out here."
- "Saving the shiny for when it matters."
- "I'll spare this one. Held up well."
- "Don't want to snap it doing grunt work."
- "Shelving the fancy kit. Back to basics."
- "This one's earned a break."
- "Swapping — rather not push my luck."

`GEAR_COMBAT_EDGE_LINES`:

- "One more hit and she's gone!"
- "Careful — gear's on the edge!"
- "This thing's about to snap!"
- "Running on fumes over here!"
- "Almost spent — pull back!"
- "Hang on, I'm out of good stuff!"

`GEAR_NO_REPLACEMENT_LINES`:

- "Got nothing else. Give me a minute."
- "Can't find a spare anywhere."
- "Need to restock — this was my last one."
- "I'm out. Someone grab me a new one?"
- "Tried the chests, no luck."
- "No replacements around. I'm stuck."
- "Looked everywhere. Nothing."
- "Hands are empty. I'll wait."

## Hook Sites

Every existing "which item should the bot pick?" helper gets a policy filter. The filter is a one-line guard in each selection loop:

```java
if (DurabilityPolicyService.shouldAvoid(bot, candidate)) continue;
```

If the loop completes with zero compliant candidates, the hook calls `DurabilityFallbackService.requestRefresh(bot, category, source)` and returns `null` (or equivalent "no tool" sentinel) for this tick.

### Core selection sites (Phase 2)

- **Mining tool** — `PlayerUtils/ToolSelector.java`, inside `selectBestToolForBlock` (filter during hotbar + inventory scan).
- **Armor** — `PlayerUtils/armorUtils.java`, inside `isBetterArmor` / `findBestArmorSlot` (equip-time filter only, no continuous re-check).
- **Combat sword/shield/mace** — `PlayerUtils/CombatInventoryManager.java`, inside `ensureCombatLoadout` weapon/shield selection. Also covers offhand shield via the same helper.

### Ranged and utility selection sites (Phase 3)

Hook count confirmed by design-time grep for `Items.BOW|CROSSBOW|TRIDENT|SHEARS|ELYTRA|MACE|FLINT_AND_STEEL`. Files that need a filter:

- **Bow / crossbow / trident** — `GameAI/BotActions.java` at the ranged-weapon selection sites around lines 1386, 1475, 1489, 1517, 2168 (all part of the central ranged/combat dispatch). Filter during the "pick ranged weapon" loop.
- **Fishing rod** — `GameAI/skills/impl/FishingSkill.java`, rod selection before `cast()`.
- **Shears for wool harvesting** — `GameAI/skills/impl/WoolSkill.java`, shears selection at lines 312, 333, 790.
- **Elytra for flight** — `GameAI/services/ElytraFlightService.java`, chest-slot equip sites at lines 320, 335, 686, 877, 933, 1033. This is intentional flight equip, not emergency use.

**Intentionally NOT hooked** (safety / escape contexts — preservation must not trap a bot in danger):

- `GameAI/services/MountedLeafClearingService.java` — emergency leaf clearing on stuck mount.
- `GameAI/services/TreeStuckEscapeService.java` — emergency escape from a tree.
- `GameAI/services/BotArrowRecoveryService.java` — arrow/trident pickup, no use.
- `GameAI/services/CraftingHelper.java` — creates new items, does not consume existing durability.
- Shears site in `PlayerUtils/ToolSelector.java` lines 122–128 — already covered by the Phase 2 ToolSelector hook (the same filter applies).

**Not present in codebase:** `Items.FLINT_AND_STEEL`, `Items.MACE`, `Items.BRUSH`, carrot-on-stick, warped-fungus-on-stick. Confirmed absent via grep. No hook needed.

Phase 3 includes a discovery step: grep for `flint_and_steel`, `shears`, `trident`, `bow`, `crossbow` usages in the skill tree to confirm hook count.

### Armor equip-time only (not continuous)

Armor is checked **only at equip time**. Once the bot puts on a diamond chestplate, that chestplate stays on through combat even if it drops below 3%. Yanking armor off mid-fight is worse than taking the hit. This is a deliberate simplification agreed during design.

## UI — AdminPlayerSettingsScreen

Add a new **Personal Preferences** section rendered above the existing permission matrix in `GraphicalUserInterface/AdminPlayerSettingsScreen.java`.

### Layout

```text
┌──────────────────────────────────────────────────────────┐
│ Personal Preferences                                      │
│ ──────────────────────────────────────────────────────── │
│ Preserve Expensive Gear                         [ OFF ]  │
│                                                           │
│ Admin Permissions                                         │
│ (existing permission matrix, unchanged)                   │
└──────────────────────────────────────────────────────────┘
```

### Behavior

- The section is scoped to **the viewing player only** — no bot scope, no cross-player editing.
- The toggle is marked as **player-visible** using the existing screen's player-visible-vs-admin-only flag on permission definitions, so regular players (not just OPs) see and can flip it.
- Tooltip (1.7s hover delay, wrapped text box — matches existing tooltip system):

  > Your bots will refuse to use enchanted gear or items made of gold, diamond, netherite, or turtle shell once durability drops below 11% — or 3% in combat. They'll try to switch to a cheaper alternative, check a nearby chest, or craft a new one. Applies to every bot you own. Default: OFF.

### Network payload

New payload: `network/payloads/UpdatePlayerPreservePayload.java`

```java
public record UpdatePlayerPreservePayload(boolean enabled) implements CustomPayload {
    // codec + id
}
```

C2S only. Server handler:

1. Gets sender's UUID from the server player.
2. Calls `Frens.CONFIG.setPreserveExpensiveGear(sender.getUuid(), enabled)`.
3. Saves config.

No cross-player editing possible — the payload has no target UUID field, the sender is always the subject. No broadcast needed because the setting only affects the owner's own bots.

## Guide Entry

Add to `baseTopics()` in `GraphicalUserInterface/BotGuideScreen.java`:

```yaml
id:       "settings_preserve_expensive_gear"
category: "Settings"
title:    "Preserve Expensive Gear"
summary:  "Keep your bots from wearing out your best tools."
details:
  - "When enabled, your bots refuse to use enchanted items and gear made
     of gold, diamond, netherite, or turtle shell once durability drops
     below 11%."
  - "In combat, the threshold drops to 3% — the bot will push a risky
     item harder when lives are on the line."
  - "When a tool is locked out, the bot tries to swap to a cheaper
     alternative from its inventory, walks to a registered chest for a
     replacement, or crafts a new one at a nearby crafting table."
  - "If nothing is available, the bot pauses and speaks up — check its
     inventory and give it a refill."
  - "Toggle this under the Admin screen → Personal Preferences. The
     setting is per player and applies to every bot you own. Default: OFF."
command:  ""
shortcuts: ""
tags: "durability tools gear preserve diamond netherite gold enchanted
       expensive shield armor"
```

## Threading

- `DurabilityPolicyService` is pure-function: no I/O, safe on any thread, inlinable inside hotbar scan loops on worker threads.
- `DurabilityFallbackService.requestRefresh` is called from selection sites (worker threads, typically). It schedules its own work to a dedicated worker via an internal executor so the calling thread returns immediately.
- All inventory mutations, hotbar swaps, and `equipStack` calls inside the fallback worker dispatch to the server thread via `server.execute(...)`.
- The fallback service's executor is shut down from the existing `SERVER_STOPPING` handler block in `Frens.java` (alongside the 8 other mod executors already handled there — per the memory note on executor shutdown).

## Non-Goals

- **No continuous armor re-check.** Armor is filtered at equip time only. Once equipped, it stays on.
- **No new skill-pause primitive.** When the fallback chain fully fails, the calling skill bails via its existing no-tool path. `SkillResumeService` handles re-attempts.
- **No cross-player overrides.** OPs cannot force another player's preference on or off via this toggle. This is a player preference, not an admin permission.
- **No bot-level override.** Per the design decision, the toggle is one flag per player that applies to all their bots. No per-bot override.
- **No automatic tier-down during combat.** Combat does not trigger gear swaps on its own; it only lowers the threshold for *new* selections made during combat.
- **No vanilla durability bar recoloring.** The feature does not touch rendering.

## Edge Cases

- **Unowned bot** — `resolveBotOwnerUuid` returns null → policy disabled → normal behavior.
- **Unenchanted iron pickaxe at 5%** — not preserved (cheap material, no enchant) → used normally.
- **Enchanted wooden pickaxe at 5%** — preserved (any enchantment counts) → filtered.
- **Diamond sword at 4% in combat** — ratio 4% ≥ 3% threshold → still used.
- **Diamond sword at 2% in combat** — ratio 2% < 3% threshold → filtered, `tryShowGearCombatEdge` fires.
- **Turtle helmet at 10%** — preserved (special material) → filtered.
- **Mending fishing rod at 5%** — preserved (any enchantment) → filtered. The player is expected to disable the toggle if they want XP-based self-repair; this is documented in the guide.
- **Diamond chestplate equipped at 80% enters combat, drops to 2%** — still worn (armor is equip-time only, never re-evaluated).
- **Diamond pickaxe equipped at 80% when combat starts** — still used (already-equipped items are not re-selected; the 3% combat threshold only affects new selection calls).
- **Enchanted book, enchanted golden apple, potion** — non-damageable; predicate short-circuits at the `damageable` check; unaffected by the policy.
- **Bot dies with a preserved tool in inventory** — inventory drops per vanilla; respawned bot is empty. The fallback cooldown map is cleared on death, so the respawned bot's first selection call can immediately trigger a fresh fallback attempt.
- **Player flips toggle OFF → ON mid-session** — all cooldowns for bots owned by that player are cleared so the first post-flip selection gets a fresh attempt.
- **Player has no chests registered** — chest step is a no-op, proceeds to craft step.
- **Bot mid-skill with no alternative anywhere** — fallback fires once, stands down, 20s cooldown prevents retry loop, skill bails via no-tool path.
- **Two bots race the same last iron pickaxe in a chest** — accepted as designed. Loser walks to the chest, finds nothing, stands down, 20s cooldown applies. No reservation or locking is added.
- **Toggle flipped ON → OFF mid-skill** — policy is disabled immediately; next selection call uses the full set again, bot resumes normally.
- **Two bots of two different owners in same area** — each resolves its own owner's preference independently.
- **Owner logs out** — preference persists (stored in ManualConfig), applies on next login.
- **Combat ends mid-skill** — the 11% threshold returns on the next selection call. A previously-filtered 5% diamond sword becomes eligible again; the bot does not auto-swap back to it, but the next natural re-selection will pick it up.

## File Changes Summary

### New files (3)

- `GameAI/services/DurabilityPolicyService.java`
- `GameAI/services/DurabilityFallbackService.java`
- `network/payloads/UpdatePlayerPreservePayload.java`

### Modified files (by phase)

**Phase 1 (5 files):**

- `FilingSystem/ManualConfig.java`
- `GameAI/services/DurabilityPolicyService.java` (new)
- `network/payloads/UpdatePlayerPreservePayload.java` (new)
- `Frens.java` (payload registration + handler)
- `GraphicalUserInterface/AdminPlayerSettingsScreen.java`

**Phase 2 — Core selection sites (4 files):**

- `PlayerUtils/ToolSelector.java` — filter in `selectBestToolForBlock`
- `PlayerUtils/armorUtils.java` — filter in `isBetterArmor` / `findBestArmorSlot`
- `PlayerUtils/CombatInventoryManager.java` — filter in `ensureCombatLoadout` weapon/shield selection
- `GameAI/services/DurabilityFallbackService.java` (new, stub with inventory-rescan step only; full chest+craft chain deferred to Phase 3)

**Phase 3 — Ranged/utility sites + full fallback chain (5 files):**

- `GameAI/services/DurabilityFallbackService.java` (fill in chest retrieval, crafting fallback, cooldown, race handling, executor + shutdown hook)
- `GameAI/BotActions.java` — filter at ranged-weapon selection sites (bow/crossbow/trident lines 1386, 1475, 1489, 1517, 2168)
- `GameAI/skills/impl/FishingSkill.java` — filter rod selection before `cast()`
- `GameAI/skills/impl/WoolSkill.java` — filter shears selection (lines 312, 333, 790)
- `GameAI/services/ElytraFlightService.java` — filter elytra equip (lines 320, 335, 686, 877, 933, 1033)

**Phase 4 (3 files):**

- `GameAI/services/CompanionOverheadDialogueService.java`
- `GraphicalUserInterface/BotGuideScreen.java`
- `changelog.md`

Total: **3 new files, 11 modified files**, split across 4 phases each fitting within the 5-files-per-phase limit from CLAUDE.md.

## Verification Plan

No automated tests exist in this project (per CLAUDE.md). Verification is in-game + build check.

**Build check per phase:** `./gradlew build -x test`

**In-game test cases (run at end of each phase as applicable):**

1. **Default OFF baseline** — Bot with diamond pickaxe at 10% mines cobble normally. Existing behavior unchanged.
2. **Toggle ON, inventory alternative** — Diamond pickaxe at 10% + iron pickaxe at 80% → bot swaps to iron. `GEAR_PRESERVE_SWAP_LINES` dialogue fires.
3. **Toggle ON, chest alternative** — Only diamond pickaxe in inventory, iron pickaxe at 80% in registered chest → bot walks to chest, withdraws, swaps.
4. **Toggle ON, craft fallback** — No alternative in inventory or chest, but wood in inventory → bot crafts wooden pickaxe at nearby table, uses that.
5. **Toggle ON, stand down** — No alternative anywhere → bot bails current skill, `GEAR_NO_REPLACEMENT_LINES` fires, 20s cooldown applies.
6. **Combat threshold** — Diamond sword at 5% in combat → still used (above 3%). At 2% → filtered, `GEAR_COMBAT_EDGE_LINES` fires.
7. **Enchanted cheap item** — Unbreaking III iron pickaxe at 10% → filtered (enchanted).
8. **Unenchanted cheap item** — Vanilla iron pickaxe at 5% → used normally (not preserved).
9. **Turtle helmet** — Turtle helmet at 10% → filtered.
10. **Per-player scope** — Player A toggle ON, Player B toggle OFF → Player A's bots preserve, Player B's bots use everything normally.
11. **Persistence** — Toggle ON, `/reload` or restart server → toggle still ON.
12. **Tooltip** — Hover toggle in admin screen → tooltip appears after 1.7s with the wrapped text.
13. **Guide searchability** — Open guide, search "durability" or "preserve" → topic appears under Settings.
14. **Death/respawn cooldown clear** — Trigger fallback, wait 5s, kill bot, respawn → next selection immediately attempts a fresh fallback (no stale 20s cooldown).
15. **Toggle ON clears stale cooldown** — Trigger fallback (cooldown set), toggle OFF, toggle back ON → next selection immediately attempts fresh fallback.
16. **Mending item lockout** — Mending diamond pickaxe at 10% with toggle ON → filtered. Document as expected behavior in guide.
17. **Race between two bots** — Two bots same owner, single iron pickaxe in one chest, both trigger fallback simultaneously → one swaps, the other stands down with cooldown. No crash, no thrash.

## Open Items (none at design time)

All open questions resolved during the brainstorming session:

- Scope: one toggle per player ✓
- Item coverage: all durability items ✓
- Fallback chain: alt → chest → craft → stand down ✓
- Combat threshold: applies to all gear ✓
- Armor check timing: equip time only ✓
- Material tier boundary: gold+ / diamond / netherite / turtle helmet ✓
- Enchantment rule: any enchantment on any item ✓
- Default state: OFF ✓
- Admin screen access: use existing player-visible permission flag ✓
