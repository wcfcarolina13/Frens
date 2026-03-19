# Handoff: Shelter System + Auto-Return Improvements

> Created 2026-03-19. Context for a fresh Claude Code session.

## What Was Done This Session (2026-03-18 → 2026-03-19)

### Bugs Fixed from Previous HANDOFF
1. **Immortal bot after respawn** — Zeroed `timeUntilRegen`/`hurtTime` on respawn; fixed stale entity ref in `ensureRespawnHandled()`; diagnostic logging for 200 ticks post-respawn.
2. **Stale shelter threads** — Generation counter (`SHELTER_GENERATION`) incremented on death/respawn. Shelter threads capture gen at start, bail via `isStaleShelter()` before each blocking step.
3. **Phantom flee** — `shouldFlee()` triggers for phantom-only threats (no ranged/shield). `tryProactiveShelter()` bypasses 30s cooldown for phantom-only.

### Shelter System Overhaul
4. **ShelterInfo metadata** — `SHELTER_ACTIVE` stores `ShelterInfo(tick, type, capPos, entryDir)` instead of just tick. Both shelter types record cap position and entry direction.
5. **Type-aware break-free** — Cliff: mine seal blocks + pathfind outward. Dig-down: mine cap + pillar up with sneaking. Generic fallback for unknown types.
6. **Surface escape** — `escapeToSurface()` pillars up (mine overhead + jump + place block below, sneaking) until sky visible. Max 30 blocks.
7. **Skylight pathfinding** — `findNearestSkylight(world, center, 16)` scans 16-block radius for sky-visible positions. Used in `escapeToSurface()` (before pillar), `BotStuckService` (before mine-escape), and `ReturnBaseStuck` (before mining toward base).
8. **Command-triggered breakout** — `setMode()` calls `clearShelterAndBreakFree()`. Skill commands join break-free thread before executing.
9. **Shelter mutex** — `AtomicBoolean` per-bot prevents duplicate shelter threads.
10. **Dig-down cap validation** — `SHELTER_ACTIVE` only set when `capPlaced=true`.
11. **Torch in dig-down** — Both shelter types place torch inside. Dig-down uses `digPos.down(3)` (bunker floor), not `bot.getBlockPos()` (race fix). 500ms settle delay.
12. **Torch collection on break-free** — `collectNearbyTorch()` mines adjacent torches before exit.

### Stuck Detection + Escape
13. **Bounded-movement stuck** — Tracks if bot stays within 2-block radius for 60 ticks (walking into wall). Triggers mine-escape or skylight pathfinding.
14. **Horizontal enclosure detection** — `analyzeEnvironment()` tracks `horizontallyEnclosed` flag. Mine-escape fires when all 4 directions blocked.
15. **On-join trap detection** — 2 seconds after spawn, if bot hasn't moved >2 blocks and no sky visible, launches `forceBreakFree()`. Skips if bot already left IDLE mode (prevents race with auto-return).

### Mining Fixes
16. **MiningTool LoS check** — Raycasts from bot eye to block center; rejects if different block hit first.
17. **Progressive break animation** — `world.setBlockBreakingInfo()` sends crack stages 0-9 each tick.
18. **Mining timeout scales** — `max(12s, requiredTicks * 50ms + 5s)` instead of fixed 12s.
19. **ReturnBaseStuck delegated to MiningTool** — Was calling `tryBreakBlock()` directly (instant removal). Now uses `MiningTool.mineBlock().join()`.

### Auto-Return + Notifications
20. **Notification queue** — `NavigationHudOverlay` uses `ConcurrentLinkedQueue`. Sequential display, "(N more)" indicator, 60s auto-expire.
21. **Skip-permission toggle** — `BotHomeService.isAutoReturnSkipPermission()` persisted toggle. Config UI button with tooltip. Command: `/bot auto_return_skip_permission toggle <target>`.
22. **Shelter validation all modes** — `validateAndTickShelter()` runs before mode switch (was IDLE-only). Clears stale shelter at dawn in FOLLOW/GUARD/PATROL.
23. **Shelter self-clear in hobbies/hunt** — Auto-clears stale shelter if daytime + not thundering.

### Other
24. **"Terminating" spam suppressed** — `AutoFaceEntity` skips danger alert for non-diving phantoms at night.
25. **Night break-free guard** — On-join trap detection at night sets shelter instead of breaking free into danger.
26. **Wander robustness** — 3-4 steps (was 1-2), `allowPursuit=true`, step clamp ceiling 5.
27. **Compromised shelter override** — `tickFlee()` clears shelter if bot taking hostile damage.

## Current Architecture

### SHELTER_ACTIVE Map
```
ConcurrentHashMap<UUID, ShelterInfo>
record ShelterInfo(long enteredTick, ShelterType type, BlockPos capPos, Direction entryDir)
enum ShelterType { CLIFF, DIG_DOWN }
```

### Shelter Entry Flow
```
tryProactiveShelter() [server tick, IDLE mode]
  ├── isInShelter? → return true
  ├── SHELTER_LOCK mutex check
  ├── night check, difficulty, base check, phantom-cooldown-bypass
  └── spawn daemon thread (with generation counter) →
       ├── stabilizeEat(2 bites)
       └── runEmergencyTacticChain()
            ├── findNearbyCliffFace(6) → emergencyCliffDig(gen)
            │    ├── Mine 6 blocks (3-deep tunnel)
            │    ├── Pathfind to back
            │    ├── Seal entrance (SEAL_BLOCKS)
            │    ├── Place torch
            │    └── SHELTER_ACTIVE.put(ShelterInfo(CLIFF, wallFeet, digDir))
            └── emergencyDigDown(gen) [fallback]
                 ├── Mine 3 blocks down
                 ├── Cap hole (3 candidates)
                 ├── Place torch at digPos.down(3)
                 └── SHELTER_ACTIVE.put(ShelterInfo(DIG_DOWN, capPos, null)) [only if capped]
```

### Shelter Exit Flow
```
validateAndTickShelter() [every tick, ALL modes]
  └── tod >= 23460 OR tod < 12000 (not thundering)
      → clear SHELTER_ACTIVE + launch breakFreeFromShelter(info)

breakFreeFromShelter(bot, ShelterInfo):
  1. collectNearbyTorch()
  2. CLIFF: mine seal blocks, pathfind outward
     DIG_DOWN: mine cap block
     GENERIC: try 4 horizontal dirs, mine up
  3. escapeToSurface():
     a. findNearestSkylight(16) → pathfind to daylight
     b. Fallback: pillar up (sneak, mine overhead, jump+place) x30

clearShelterAndBreakFree(bot) — for commands:
  removes ShelterInfo, launches break-free thread

clearShelter(UUID) — flag-only removal:
  teleport, damage, mode change
```

### Key State Maps (BotFleeService)
| Map | Type | Purpose |
|-----|------|---------|
| `SHELTER_ACTIVE` | `UUID → ShelterInfo` | Active shelter metadata |
| `SHELTER_COOLDOWN` | `UUID → Long` | 30s cooldown between shelter attempts |
| `SHELTER_GENERATION` | `UUID → Long` | Incremented on death/respawn, invalidates threads |
| `SHELTER_LOCK` | `UUID → AtomicBoolean` | Mutex preventing duplicate shelter threads |

## Key Files

| File | Role |
|------|------|
| [BotFleeService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java) | Shelter state, flee, break-free, skylight scan, escapeToSurface |
| [BotEventHandler.java](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) | Tick orchestrator, mode management, on-join trap detection |
| [BotStuckService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotStuckService.java) | Bounded-stuck detection, horizontal enclosure, mine-escape |
| [ReturnBaseStuckService.java](src/main/java/net/wcfcarolina13/GameAI/services/ReturnBaseStuckService.java) | Return-to-base stuck handling, skylight-before-mining |
| [MiningTool.java](src/main/java/net/wcfcarolina13/PlayerUtils/MiningTool.java) | All bot mining — LoS check, progressive animation, scaled timeout |
| [BotAutoReturnSunsetService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java) | Sunset auto-return, skip-permission check |
| [NavigationHudOverlay.java](src/main/java/net/wcfcarolina13/GraphicalUserInterface/NavigationHudOverlay.java) | HUD notification queue for auto-return |
| [BotHomeService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java) | Persisted toggles: auto-return, skip-permission, hobbies, hunt |
| [AutoFaceEntity.java](src/main/java/net/wcfcarolina13/Entity/AutoFaceEntity.java) | Phantom-aware danger alerts |
| [WanderSkill.java](src/main/java/net/wcfcarolina13/GameAI/skills/impl/WanderSkill.java) | Wander hobby with pursuit mode |

## Build & Deploy

```bash
./gradlew build -x test
cp build/libs/frens-*.jar "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/mods/"
```

## Next Session Tasks

### 1. Check Up on Recent Changes
Review in-game behavior of all the shelter, stuck, mining, and auto-return changes. Look at logs and confirm:
- Bots properly shelter at night, break free at dawn
- Skylight pathfinding works (bots navigate to tunnel exits)
- Mining shows progressive cracks, proper timing, LoS enforced
- Auto-return notifications queue properly, skip-permission toggle works
- Wander hobby succeeds more often

### 2. Distance-Based Auto-Return Instead of Shelter
**Feature:** If the bot is near its base at nightfall, it should return home instead of sheltering in place. Need to decide a reasonable distance threshold.

**Design considerations:**
- Check distance from base in `tryProactiveShelter()` — if within N blocks of home, skip shelter and trigger return-to-base instead
- Reasonable distance: 64-128 blocks? (pathfinding range, ~30s-60s walk)
- Should respect the existing `isAutoReturnAtSunset` toggle
- The base check at `tryProactiveShelter()` line 200 already skips shelter if `state.baseTarget != null` — but that only applies when the bot is actively returning, not when it COULD return

### 3. Bed Placement + Sleep in Tactical Shelters
**Feature:** After building a tactical shelter (cliff or dig-down), the bot should:
1. Expand the interior if needed (bed requires 2 blocks of floor space)
2. Place a bed
3. Ensure a torch is placed (for light, prevents mob spawns)
4. Sleep in the bed
5. On waking/dawn exit: break and collect bed + torches before leaving

**Design details:**
- **Bed placement:** After shelter is built and torch placed, check if bot has a bed in inventory. If so, expand interior (mine 1 additional block for bed footprint if needed), place bed on the floor.
- **Sleep:** Call `bot.sleep(bedPos)` or equivalent to put the bot in the bed. Need to check vanilla's `trySleep` conditions (only at night, no hostiles nearby, etc.).
- **Recovery on exit:** When shelter is cleared (dawn, command, teleport):
  - If teleported: skip recovery (bot is already elsewhere)
  - If command or dawn break-free: mine bed + torches, collect drops, then proceed
  - Implementation: in `breakFreeFromShelter()`, after collecting torch, also mine the bed block. `collectNearbyTorch()` could be extended to `collectShelterFurnishings()` that mines both torches and beds.
- **If shelter cancelled but not teleported:** Bot should attempt to collect bed/torches from nearby. Could check if bed/torch blocks exist within 3 blocks and mine them before moving on.
- **Edge cases:** Bed destroyed by mobs, bed obstructed, bot has no bed in inventory (skip bed step), two-part bed block (need to mine the right half).
