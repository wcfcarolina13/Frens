# Handoff: Bot Shelter & Combat Bugs

> Created 2026-03-18. Context for a fresh Claude Code session continuing this work.

## What Was Done This Session

### Fixed (verified working in-game)
1. **Cliff-dig tunnel** — Now 3-deep with `MovementService.execute()` pathfinding + `nudgeTowardUntilClose` fallback. Bots mine all 6 blocks from outside, pathfind to entrance (picks up drops), mine deeper, pathfind to back, seal.
2. **Tunnel sealing** — `allowIntersecting=true` on `tryPlaceBlockAt` + `SEAL_BLOCKS` priority list (cobblestone, stone, dirt, planks, etc.). Prevents wheat seeds and fixes bounding-box rejection. Log confirms `sealed=true` for Jake and Steve.
3. **Shelter timeout** — Replaced tick-duration timeout with time-of-day check: clears when `tod >= 23460` (undead burn) or `tod < 12000` (daytime), unless thundering.
4. **Phantom night behavior** — Non-diving phantom + nighttime + no ground threats → `engageHostiles()` returns false, letting shelter logic trigger instead of "raise shield and wait."
5. **Hostile damage clears shelter** — In `ALLOW_DAMAGE` handler, hostile mob hits now call `clearShelter()` so the bot fights back if mobs breach the shelter.

### Fixes from earlier in this session (prior to context compaction)
6. **Traversability-aware flee** — 5 candidate directions probed for wall clearance; null = stand and fight.
7. **Faster stuck detection** — 60→25 ticks, 5→2 blocks.
8. **Post-respawn combat state reset** — `BotCombatCalloutService.resetCombatState()` on death and respawn.
9. **Teleport clears shelter** — `createFakePlayer.teleportTo()` override calls `clearShelter()`.
10. **Shelter re-dig prevention** — `isInShelter()` check at top of `tryProactiveShelter()`.
11. **HealingService.stabilizeEat()** — Blocking eat loop for worker threads; caps rotten flesh to 1 bite.

## Open Bugs (Unresolved)

### Bug 1: Immortal Bot After Respawn (CRITICAL)
**Symptom**: After death+respawn, the bot takes zero damage from both players and mobs. Persists until server restart.

**What we know**:
- `setInvulnerable(true)` is never called anywhere in current code
- Three safety nets exist that call `setInvulnerable(false)`: `onBotRespawn()` (line 1273), `updateBehavior()` tick (line 1324), `ALLOW_DAMAGE` handler (line 681)
- The `ALLOW_DAMAGE` handler returns `true` (allows damage) — it never blocks damage
- No `invulnerableTime` manipulation exists in our code
- Bot health stays at 20.0/20.0 with no `hurt_grunt` sounds after respawn

**Investigation leads**:
- `createFakePlayer.onDeath()` calls `setHealth(20)` then `kill(deathMessage)` — could `kill()` be interfering? ([createFakePlayer.java:225-232](src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java#L225))
- `AFTER_RESPAWN did not fire` warning appears — respawn is force-triggered by `ensureRespawnHandled()` at tick+5. Race condition? ([BotEventHandler.java:1293-1315](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L1293))
- Is the fake player actually being ticked after respawn? Check if `updateBehavior()` runs (the invulnerability safety net depends on it)
- Could vanilla `ServerPlayerEntity` respawn set `invulnerableTime` (int counter, different from `invulnerable` boolean)? Our safety nets only check `isInvulnerable()` which reads the boolean flag, NOT the timer.
- Try: add `bot.timeUntilRegen = 0;` in `onBotRespawn()` — vanilla sets damage immunity ticks after damage
- Try: log `bot.isInvulnerable()`, `bot.invulnerableTime`, `bot.timeUntilRegen` on every tick after respawn

### Bug 2: Shelter Thread Survives Death/Respawn
**Symptom**: The `proactive-shelter-*` daemon thread started before death continues running after respawn with stale coordinates. The thread tried to pathfind from the new spawn position (15, 90, -48) to the OLD tunnel location (57, 50, -50) — 2925 blocks² away.

**Evidence** (latest.log):
```
[proactive-shelter-Test] Movement execute: dest=(59, 48, -49) player=Test
[proactive-shelter-Test] [BaritonePathFinder] Start=(15,90,-48) Target=(57,50,-50)  ← NEW spawn, OLD tunnel
[proactive-shelter-Test] Bot Test tunnel position: at 18, 84, -48 dest=59, 48, -49 distSq=2925.9
```

**Fix needed**: At the start of `emergencyCliffDig()` and `emergencyDigDown()`, check if the bot died/respawned since the shelter was initiated. Options:
- Store `bot.deathTime` or a generation counter at shelter start, check it during execution
- Check `bot.getBlockPos()` distance from the originally computed tunnel positions; abort if wildly different
- In `onBotRespawn()` / `reset()`, interrupt/cancel the shelter thread (store thread reference in a map)

### Bug 3: Bot Doesn't Shelter After Respawn
**Symptom**: Test respawned at world spawn, didn't attempt dig-down shelter, just stood there.

**Root cause**: The stale shelter thread from Bug 2 was still running. It set `SHELTER_ACTIVE` at the wrong coordinates. When the new tick cycle checked `isInShelter()`, it returned true (stale state from old thread), so `tryProactiveShelter()` returned early.

**Fix**: Fixing Bug 2 (killing stale shelter threads on respawn) should fix this. Also, `reset()` already clears `SHELTER_ACTIVE`, but it may run before the stale thread sets it again. Need to ensure `SHELTER_ACTIVE.put()` in the shelter methods checks if the bot is still alive and hasn't respawned.

### Bug 4: Phantom Flee for Unarmed/Unshielded Bots
**Symptom**: Unarmed bot with no shield stands still when phantoms attack instead of running to cover.

**Current behavior**: In `engageHostiles()`, phantom-only + nighttime now returns false (this session's fix). But the bot needs to actively flee to a tree/cliff, not just wait for `tryProactiveShelter()` to trigger on the next 30s cooldown cycle.

**Fix needed**: In `shouldFlee()` ([BotFleeService.java:210](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java#L210)), add phantom-specific flee logic:
- If all hostiles are phantoms AND bot has no ranged weapon AND no shield → return true (flee)
- The flee direction should prefer trees/overhangs (already searched in `findNearbyCliffFace` and `findNearbyTree`)
- Or: in `tryProactiveShelter()`, skip the cooldown check when phantoms are the only threat

## Key Files

| File | Role | Size |
|------|------|------|
| [BotFleeService.java](src/main/java/net/wcfcarolina13/GameAI/services/BotFleeService.java) | Flee direction, shelter state machine, cliff/dig-down shelter, daylight break-free | ~1100 lines |
| [BotEventHandler.java](src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java) | Central tick orchestrator, combat dispatch, respawn, mode management | ~199KB |
| [createFakePlayer.java](src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java) | Fake player entity, `onDeath()`, `teleportTo()` override | ~300 lines |
| [Frens.java](src/main/java/net/wcfcarolina13/Frens.java) | Server entry, `ALLOW_DAMAGE` handler, `AFTER_DEATH` handler | ~800 lines |
| [HealingService.java](src/main/java/net/wcfcarolina13/GameAI/services/HealingService.java) | `autoEat()`, `stabilizeEat()`, hunger warnings | ~400 lines |
| [BotActions.java](src/main/java/net/wcfcarolina13/GameAI/BotActions.java) | `placeBlockAt`, `tryPlaceBlockAt`, combat primitives, phantom diving detection | ~70KB |
| [MovementService.java](src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java) | Pathfinding + execution, `nudgeTowardUntilClose`, door traversal | ~2300 lines |

## Key State Maps (BotFleeService.java)

| Map | Type | Purpose |
|-----|------|---------|
| `SHELTER_ACTIVE` | `ConcurrentHashMap<UUID, Long>` | Tick when bot entered shelter. Cleared by: daylight, teleport, damage, mode change, respawn |
| `SHELTER_COOLDOWN` | `ConcurrentHashMap<UUID, Long>` | Tick of last shelter attempt. 30s (600 tick) cooldown between attempts |
| `FLEE_STATES` | `ConcurrentHashMap<UUID, FleeState>` | Active flee direction, start tick, etc. |

## Shelter Entry Flow

```
tryProactiveShelter() [server tick, IDLE mode]
  ├── isInShelter? → return true (already sheltered)
  ├── night check, difficulty check, base check, cooldown check
  └── spawn daemon thread → stabilizeEat(2 bites) → runEmergencyTacticChain()
       ├── findNearbyCliffFace(6 blocks) → emergencyCliffDig()
       │    ├── Mine entrance (2 blocks)
       │    ├── Pathfind to entrance (picks up drops)
       │    ├── Mine middle + back (4 blocks)
       │    ├── Pathfind to back + nudge
       │    ├── Seal entrance (SEAL_BLOCKS, allowIntersecting=true)
       │    ├── Place torch
       │    ├── SHELTER_ACTIVE.put()
       │    └── stabilizeEat(5 bites)
       └── emergencyDigDown() [fallback if no cliff]
```

## Shelter Exit Flow

```
validateAndTickShelter() [every tick]
  └── tod >= 23460 OR tod < 12000 (and not thundering) → clear SHELTER_ACTIVE

checkDaylightBreakFree() [every tick, IDLE mode]
  └── day + not thundering + tod 1000-12000 → clear + breakFreeFromShelter thread

clearShelter() [called from]:
  ├── createFakePlayer.teleportTo()
  ├── ALLOW_DAMAGE: environmental damage (explosion, drown, lava, fire, suffocation)
  ├── ALLOW_DAMAGE: hostile mob damage
  ├── BotEventHandler.setMode() (any command)
  └── BotFleeService.reset() (death/removal)
```

## Build & Deploy

```bash
./gradlew build -x test
cp build/libs/frens-*.jar "/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/mods/"
```

## Priority Order for Next Session

1. **Bug 1 (Immortal)** — Most critical. Bots are useless if unkillable. Start by adding diagnostic logging: `isInvulnerable()`, `invulnerableTime`, `timeUntilRegen` on every tick for 100 ticks after respawn.
2. **Bug 2 (Stale shelter thread)** — Causes Bug 3. Store a "generation" counter incremented on death; shelter threads check it before each blocking step.
3. **Bug 4 (Phantom flee)** — Quality of life. Add phantom-specific flee trigger in `shouldFlee()`.
