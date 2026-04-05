# Fishing: Sunrise Resume Loop + Vanilla Open Water Positioning

**Date:** 2026-04-05
**Status:** Draft

## Problem

FishingSkill stops at sunset but does not save session state or resume at sunrise like HuntSkill does. The bot also lacks vanilla-accurate open water detection, meaning it never optimizes for treasure-quality catches.

## Scope

1. **Sunrise resume loop** -- save fishing session at sunset, resume at the same spot next morning
2. **Vanilla open water positioning** -- pre-cast 5x4x5 area validation per Minecraft's treasure catch requirements

Chest offload already works via `handleFullInventory()` and needs no changes.

## Feature 1: FishingSessionService + Sunrise Resume

### Architecture: How Sunset/Sunrise Works

Understanding the existing pattern is critical to getting the integration right.

**Sunset abort flow (BotAutoReturnSunsetService):**
1. `onServerTick()` runs every tick for registered bots
2. At tick 12000 (`SUNSET_START_TICK`), detects sunset
3. For hunt: `isHunt` check (line 363) skips generic resume save
4. For all other skills: saves generic `SunriseResumeRecord` via `SkillResumeService.saveSunriseResume()`
5. Calls `TaskService.forceAbort()` (line 404) -- sets ABORT_LATCH, kills the running skill

**Hunt's approach:** HuntSkill has its own `isSunset()` check at tick 13000 inside its loop (line 288). This fires on the worker thread. There is a 1000-tick window (12000-13000) where the abort latch might propagate before the skill's own check fires. In practice, the worker thread may be mid-iteration when the latch is set and completes the current iteration (including the sunset check) before seeing the latch on the next iteration. This is the same timing FishingSkill will use.

**Sunrise resume flow (BotAutoReturnSunsetService):**
1. At sunrise (tick < 1000), checks `HuntSessionService.hasSession()` (line 306)
2. If hunt session found: calls `SkillResumeService.tryAutoResume()` (line 313)
3. Otherwise: falls through to generic sunrise resume for other skills (line 318)

**Fishing must mirror hunt exactly:**
1. FishingSkill saves its own session internally (has access to state variables)
2. BotAutoReturnSunsetService skips generic resume for fish (like it does for hunt)
3. BotAutoReturnSunsetService handles fish-specific sunrise resume (like it does for hunt)

### New File: `GameAI/services/FishingSessionService.java`

Mirrors `HuntSessionService` pattern exactly.

**Session record:**

```java
public record FishingSession(
    UUID botId,
    BlockPos standPos,
    BlockPos waterPos,
    BlockPos castTarget,
    int fishCaught,
    int targetFish,
    String rawArgs
)
```

**Persistence:** `config/frens/fishing_sessions.json`

Uses Gson with a `SessionData` wrapper class that serializes each `BlockPos` as individual x/y/z int fields (same pattern as `HuntSessionService.SessionData`). The `SessionData` wrapper includes a `savedAtMs` field for expiry tracking. Lazy-loaded on first access.

**Methods:**

| Method | Behavior |
|--------|----------|
| `saveSession(bot, stand, water, castTarget, caught, target, rawArgs)` | Creates session, flushes to JSON |
| `getSession(UUID)` | Returns session or null; removes if expired |
| `consumeSession(UUID)` | Returns and removes session; flushes |
| `hasSession(UUID)` | Boolean check with expiry cleanup |
| `clearSession(UUID)` | Explicit removal + flush |

**Expiry:** 24 hours (same as HuntSessionService).

**Thread safety:** All methods synchronized on a static lock object.

### BotAutoReturnSunsetService Changes

**Sunset exclusion (near line 363):**

```java
boolean isHunt = "hunt".equalsIgnoreCase(skillName);
boolean isFish = "fish".equalsIgnoreCase(skillName);

if (!isHunt && !isFish) {
    // existing generic sunrise resume save...
}
```

Fish gets excluded from the generic resume path, just like hunt. The FishingSkill saves its own session internally.

**Sunrise resume (near line 304-316):**

Add fish session check alongside hunt:

```java
if (HuntSessionService.hasSession(bot.getUuid())) {
    // existing hunt resume...
} else if (FishingSessionService.hasSession(bot.getUuid())) {
    long lastResumed = LAST_RESUMED_DAY.getOrDefault(bot.getUuid(), Long.MIN_VALUE);
    if (lastResumed < day) {
        LAST_RESUMED_DAY.put(bot.getUuid(), day);
        var taskInfo = TaskService.getActiveTaskInfo(bot.getUuid());
        if (taskInfo.isEmpty()) {
            LOGGER.info("Sunrise fishing resume for {} (day={})", bot.getName().getString(), day);
            SkillResumeService.tryAutoResume(bot);
        }
    }
} else {
    // existing generic sunrise resume...
}
```

### FishingSkill Changes: Sunset Save

**Location:** The sunset check block (~line 244-250).

Current behavior:
```java
if (timeOfDay >= 13000 && timeOfDay < 23000) {
    ChatUtils.sendSystemMessage(source, "Sun has set. Stopping fishing.");
    break;
}
```

**Loop reordering:** Move the sunset check BEFORE the `shouldAbortSkill` check in the main while-loop. This ensures the session is saved before the abort latch (set at tick 12000 by BotAutoReturnSunsetService) can kill the loop on the next iteration. HuntSkill has the same latent race but it can be fixed separately.

New behavior:
```java
// SUNSET CHECK -- must come before abort check to save session
// before the abort latch (set at tick 12000) fires.
if (checkSunset) {
    long timeOfDay = world.getTimeOfDay() % 24000;
    if (timeOfDay >= 13000 && timeOfDay < 23000) {
        retractBobberIfPresent(bot);
        if (!hobby && caught < targetFish && BotHomeService.isAutoReturnAtSunset(bot)) {
            FishingSessionService.saveSession(bot, stand, spot.water(),
                    castTarget, caught, targetFish, rawArgs);
            SkillResumeService.recordExecution(bot, "fish", rawArgs, source);
            SkillResumeService.requestAutoResume(bot);
            ChatUtils.sendSystemMessage(source,
                    "Sun's setting. Heading home. I'll resume fishing tomorrow. ("
                    + caught + " catch" + (caught != 1 ? "es" : "") + " so far)");
        } else {
            ChatUtils.sendSystemMessage(source, "Sun has set. Stopping fishing.");
        }
        break;
    }
}

// ABORT CHECK -- after sunset check
if (SkillManager.shouldAbortSkill(bot)) {
    return SkillExecutionResult.failure("Fishing paused by another task.");
}
```

**Gates:**
- `!hobby` -- hobby fishing (ambient idle) should NOT save sessions; it's short-lived leisure
- `caught < targetFish` -- don't resume if target already met
- `BotHomeService.isAutoReturnAtSunset(bot)` -- only if auto-return is enabled

**rawArgs reconstruction:** Built from context parameters early in `execute()`. Examples:
- `/bot skill fish 5 BotName` -> rawArgs = `"5"`
- `/bot skill fish BotName` (open-ended) -> rawArgs = `""`
- `/bot skill fish until_sunset BotName` -> rawArgs = `"until_sunset"`

Reconstructed from the `count` and `options` parameters, not stored as a raw input string.

### FishingSkill Changes: Sunrise Resume

**Location:** Top of `execute()`, before `findFishingSpot()` (~line 128).

```
1. Check FishingSessionService.hasSession(botId)
2. If session exists:
   a. Consume session
   b. Validate saved spot:
      - Water block still contains water
      - Stand block still standable (solid below, not water)
   c. If valid:
      - Use saved FishingSpot (stand, water, castTarget)
      - Re-evaluate castTarget via chooseCastTargetAlongLine() (cast target
        is volatile -- lily pads, water changes overnight. The saved stand
        and water anchor are stable; the cast target should be re-derived.)
      - Set caught = session.fishCaught (preserve progress)
      - Skip findFishingSpot()
   d. If invalid:
      - Log: "Saved fishing spot no longer valid, scanning for new spot"
      - Fall through to normal findFishingSpot()
      - Set caught = session.fishCaught (progress still preserved)
   e. Carry over targetFish from session
```

### Session Cleanup on Completion

When the fishing loop ends normally (target reached or no more attempts), call `FishingSessionService.clearSession(botId)` after the while-loop. This prevents a stale session from triggering a spurious sunrise resume. The 24h expiry is a safety net, not the primary cleanup mechanism.

### Death Behavior

Fishing sessions survive death (same as hunt sessions). `SkillResumeService.handleDeath()` clears the generic `SunriseResumeRecord` but does NOT clear `HuntSessionService` or `FishingSessionService` sessions. This is intentional -- the bot can resume at sunrise even after dying, via the yes/no prompt flow.

## Feature 2: Vanilla Open Water Positioning

### Vanilla Spec

For a bobber to qualify for treasure catches, Minecraft checks a 5x4x5 area centered on the bobber block position:

| Y offset | Requirement |
|----------|-------------|
| Y-1 (below surface) | All 5x5 blocks must be water source blocks (`Blocks.WATER`) |
| Y+0 (water surface) | All 5x5 blocks must be water source blocks |
| Y+1 (above surface) | All 5x5 blocks must be non-opaque and not lily pads |
| Y+2 (two above) | All 5x5 blocks must be non-opaque and not lily pads |

Additionally: no opaque block directly above the bobber column (sky access).

Note: Vanilla also allows `Blocks.BUBBLE_COLUMN` in the water layers. We omit this -- bubble columns near fishing spots are extremely rare and excluding them just means one fewer valid spot might get the bonus.

### New Method: `isVanillaOpenWater(ServerWorld, BlockPos)`

**Location:** Static method in `FishingSkill.java`.

```java
private static boolean isVanillaOpenWater(ServerWorld world, BlockPos waterSurface) {
    // Check 5x5 at water surface and one below: must be Blocks.WATER
    for (int dy = -1; dy <= 0; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos check = waterSurface.add(dx, dy, dz);
                if (!world.getBlockState(check).isOf(Blocks.WATER)) {
                    return false;
                }
            }
        }
    }
    // Check 5x5 at Y+1 and Y+2: must be non-opaque, not lily pad
    for (int dy = 1; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos check = waterSurface.add(dx, dy, dz);
                BlockState state = world.getBlockState(check);
                if (state.isOpaque() || state.isOf(Blocks.LILY_PAD)) {
                    return false;
                }
            }
        }
    }
    // Sky access: use heightmap for O(1) check instead of looping
    return world.isSkyVisible(waterSurface.up());
}
```

**Cost:** ~100 block state reads per call. Only applied to cast target candidates (not every water block in the scan).

**Sky access:** Uses `world.isSkyVisible()` (heightmap O(1) lookup) instead of a capped Y-loop. This correctly handles all heights without missing tall structures.

### Integration into Cast Target Scoring

**Location:** `chooseCastTargetAlongLine()` (~line 1014).

After existing validation (distance check, cast path clear), add:

```java
boolean vanillaOpen = isVanillaOpenWater(world, surface);
double openWaterBonus = vanillaOpen ? -3.0 : 0.0;
double score = distPenalty - openness * 0.35 - depth * 0.55 + openWaterBonus;
```

The `-3.0` bonus dominates scoring (existing scores range roughly -2 to +2), so the bot strongly prefers treasure-eligible spots but silently falls back to the best available if none qualify.

### What Doesn't Change

- **`findFishingSpot()`** -- water anchor scan stays cheap with `isOpenWaterSurface()` as pre-filter
- **`isOpenWaterSurface()`** -- remains as the fast 2-block-read filter (water + clear above)
- **`findStandOptions()` scoring** -- unchanged; vanilla check happens at cast target selection time
- **No entity check for boats** -- boats are transient; block check covers the dominant failure cases (lily pads, solid blocks, overhangs)
- **No chat message on fallback** -- silent fallback per user preference

## Files Changed

| File | Change |
|------|--------|
| `GameAI/services/FishingSessionService.java` | **New.** Session persistence, mirrors HuntSessionService |
| `GameAI/skills/impl/FishingSkill.java` | Sunset save + sunrise resume + `isVanillaOpenWater()` + scoring integration |
| `GameAI/services/BotAutoReturnSunsetService.java` | Add `isFish` exclusion from generic resume; add fish-specific sunrise resume path |

## Testing

Manual in-game verification:
1. Start open-ended fishing, wait for sunset -- bot should announce resume and return home
2. Wait for sunrise -- bot should resume at the same spot with preserved catch count
3. Break the saved fishing spot (fill water with blocks) before sunrise -- bot should re-scan
4. Fish near a lake with lily pads vs open ocean -- verify bot prefers open water cast targets
5. Fish in a small pond with no open water -- verify silent fallback, no crash or hang
6. Hobby fishing at sunset -- should NOT save session (just stops normally)
7. Bot dies during fishing session -- session survives; bot can resume at sunrise
