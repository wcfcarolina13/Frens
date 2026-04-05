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

Uses Gson with a `SessionData` wrapper class that serializes each `BlockPos` as individual x/y/z int fields (same pattern as `HuntSessionService.SessionData`). Lazy-loaded on first access.

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

### FishingSkill Changes: Sunset Save

**Location:** The sunset check block (~line 244-250).

Current behavior:
```java
if (timeOfDay >= 13000 && timeOfDay < 23000) {
    ChatUtils.sendSystemMessage(source, "Sun has set. Stopping fishing.");
    break;
}
```

New behavior:
```java
if (timeOfDay >= 13000 && timeOfDay < 23000) {
    retractBobberIfPresent(bot);
    if (caught < targetFish && BotHomeService.isAutoReturnAtSunset(bot)) {
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
```

**Gate:** Only saves session + requests resume when `BotHomeService.isAutoReturnAtSunset(bot)` is true. Otherwise falls through to the existing stop behavior.

**rawArgs:** Captured early in `execute()` from context parameters, reconstructed as the skill argument string (e.g., `"5"`, `"until_sunset"`, or empty for open-ended).

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
      - Set caught = session.fishCaught (preserve progress)
      - Skip findFishingSpot()
   d. If invalid:
      - Log: "Saved fishing spot no longer valid, scanning for new spot"
      - Fall through to normal findFishingSpot()
      - Set caught = session.fishCaught (progress still preserved)
   e. Carry over targetFish from session
```

## Feature 2: Vanilla Open Water Positioning

### Vanilla Spec

For a bobber to qualify for treasure catches, Minecraft checks a 5x4x5 area centered on the bobber block position:

| Y offset | Requirement |
|----------|-------------|
| Y-1 (below surface) | All 5x5 blocks must be water source blocks (`Blocks.WATER`) |
| Y+0 (water surface) | All 5x5 blocks must be water source blocks |
| Y+1 (above surface) | All 5x5 blocks must be non-opaque and not lily pads |
| Y+2 (two above) | All 5x5 blocks must be non-opaque and not lily pads |

Additionally: no opaque block directly above the bobber column (sky access for rain).

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
    // Sky access: no opaque block above bobber column
    for (int dy = 1; dy <= 10; dy++) {
        BlockPos above = waterSurface.up(dy);
        if (world.getBlockState(above).isOpaque()) {
            return false;
        }
    }
    return true;
}
```

**Cost:** ~100 block state reads per call. Only applied to cast target candidates (not every water block in the scan).

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

## Testing

Manual in-game verification:
1. Start open-ended fishing, wait for sunset -- bot should announce resume and return home
2. Wait for sunrise -- bot should resume at the same spot with preserved catch count
3. Break the saved fishing spot (fill water with blocks) before sunrise -- bot should re-scan
4. Fish near a lake with lily pads vs open ocean -- verify bot prefers open water cast targets
5. Fish in a small pond with no open water -- verify silent fallback, no crash or hang
