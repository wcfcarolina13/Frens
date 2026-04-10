# Idle Honey Collection Hobby

**Date:** 2026-04-10
**Status:** Approved

## Problem

The bot has no way to collect honey or honeycombs from beehives during idle time, even when the player has set up a proper bee farm with campfires for smoke. This is a low-effort, high-reward idle activity that fits naturally into the hobby system.

## Design

### Overview

A new idle hobby that harvests honey bottles or honeycombs from nearby beehives/bee nests, but only when the hive is full (honey_level=5) and calmed by smoke (`BeehiveBlockEntity.isSmoked()` returns true). The bot never breaks the hive and never harvests an unsmoked hive.

### Harvest Rules

1. **Scan** nearby beehives/bee nests within 16-block radius using `BotBeehiveRegistryService.discoverBeehivesNear()`
2. **Filter** to hives where:
   - `honey_level == 5` (full, dripping honey)
   - `BeehiveBlockEntity.isSmoked()` returns true (campfire within 5 blocks below)
3. **Tool selection** determines harvest type:
   - **Glass bottles in inventory** (preferred): equip bottle, right-click hive -> honey bottle (food: 6 hunger, 1.2 saturation)
   - **Shears in inventory** (fallback): equip shears, right-click hive -> 3 honeycombs (crafting material)
   - **Neither available**: hobby is not eligible for selection
4. **Walk** to the hive using `MovementService.execute(DIRECT)`
5. **Harvest** via `ItemStack.useOnBlock()` pattern (same as hoe usage in FarmSkill)
6. **Collect** dropped honeycombs with `DropSweeper.sweep()` (honey bottles go directly into the bottle slot)
7. **Deposit** extras in nearby chests via `ChestStoreService.depositMatchingWalkOnly()`
8. Process 1-3 hives per session, then return to idle

### Hard Rules

- **Never break the hive.** No `mineBlock`, `tryBreakBlock`, or any destruction of beehive/bee_nest blocks.
- **Never harvest an unsmoked hive.** If `isSmoked()` is false, skip the hive entirely. The bot does not place campfires — the player is responsible for farm setup.
- **Never harvest a non-full hive.** `honey_level < 5` means not ready; skip it.
- **Tool requirement is absolute.** No shears AND no glass bottles = hobby not picked.

### Hobby Registration

**In `BotIdleHobbiesService.pickHobby()`:**
- Eligibility: bot has shears or glass bottles, and at least one beehive within 16 blocks has `honey_level == 5` and `isSmoked() == true`
- Weight: low (1 entry in the weighted list) — honey is a niche hobby, should not compete heavily with woodcutting/fishing/etc.

**In `BotIdleHobbiesService.startAmbientSkill()`:**
- Parameters: `count: 1-3` (random), `radius: 16`

### Calm Detection

Uses `BeehiveBlockEntity.isSmoked()` — a public vanilla method that internally checks for a lit campfire within 5 blocks directly below the hive. This handles carpet, slabs, trapdoors, or any other blocks between the campfire and the hive. No custom campfire scanning needed.

### New File

`src/main/java/net/wcfcarolina13/GameAI/skills/impl/HoneyCollectSkill.java`

Follows the LeafLitterSkill pattern:
- Implements `Skill` interface (`name()` + `execute(SkillContext)`)
- Registered in `SkillManager`
- Short-lived: scan, walk, harvest 1-3 hives, return

### Files Modified

- `BotIdleHobbiesService.java` — add hobby eligibility check in `pickHobby()`, parameter setup in `startAmbientSkill()`
- `SkillManager.java` — register `HoneyCollectSkill` in the skill registry

## Testing

Manual in-game verification:
- Place beehive with campfire below (with carpet between) and wait for honey_level=5
- Give bot shears -> verify it harvests honeycombs
- Give bot glass bottles (no shears) -> verify it harvests honey bottles
- Give bot both -> verify it prefers glass bottles
- Remove campfire -> verify bot refuses to harvest
- Harvest a hive manually (reset to level 0) -> verify bot skips it
- Give bot neither shears nor bottles -> verify hobby never triggers
