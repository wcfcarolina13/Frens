# Fishing Sunrise Resume + Open Water Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add sunrise resume loop and vanilla open water positioning to FishingSkill.

**Architecture:** New `FishingSessionService` persists fishing state at sunset for sunrise resume (mirrors `HuntSessionService`). `BotAutoReturnSunsetService` gets fish-specific exclusion and sunrise resume path. `FishingSkill` gets loop reorder (sunset before abort), session save/restore, `isVanillaOpenWater()` check, and scoring integration.

**Tech Stack:** Java 21, Fabric 1.21.11, Gson for JSON persistence.

**Spec:** `docs/superpowers/specs/2026-04-05-fishing-sunrise-resume-open-water-design.md`

---

## Chunk 1: FishingSessionService + BotAutoReturnSunsetService Integration

### Task 1: Create FishingSessionService

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/services/FishingSessionService.java`

- [ ] **Step 1: Write FishingSessionService.java**

Template from `HuntSessionService.java` (208 lines). Adapt the record, SessionData, and public API for fishing state.

```java
package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent fishing session tracking for multi-day fishing.
 * When fishing is paused at sunset, the session state is saved here so it can be
 * resumed at sunrise via SkillResumeService.
 * Persisted to config/frens/fishing_sessions.json so sessions survive server restarts.
 */
public final class FishingSessionService {
    private static final Logger LOGGER = LoggerFactory.getLogger("fishing-session");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "fishing_sessions.json";
    private static final Object LOCK = new Object();
    private static final long EXPIRY_MS = 24L * 60 * 60 * 1000; // 24 hours

    private static Map<String, SessionData> DATA = new HashMap<>();
    private static boolean loaded = false;

    private FishingSessionService() {}

    // ── Data model ──────────────────────────────────────────────────────

    public record FishingSession(
            UUID botId,
            BlockPos standPos,
            BlockPos waterPos,
            BlockPos castTarget,
            int fishCaught,
            int targetFish,
            String rawArgs
    ) {}

    /** JSON-serializable wrapper since records with BlockPos don't serialize cleanly. */
    private static final class SessionData {
        String botId;
        int standX, standY, standZ;
        int waterX, waterY, waterZ;
        int castX, castY, castZ;
        boolean hasCastTarget;
        int fishCaught;
        int targetFish;
        String rawArgs;
        long savedAtMs;

        SessionData() {}

        static SessionData from(FishingSession s) {
            SessionData d = new SessionData();
            d.botId = s.botId.toString();
            d.standX = s.standPos != null ? s.standPos.getX() : 0;
            d.standY = s.standPos != null ? s.standPos.getY() : 64;
            d.standZ = s.standPos != null ? s.standPos.getZ() : 0;
            d.waterX = s.waterPos != null ? s.waterPos.getX() : 0;
            d.waterY = s.waterPos != null ? s.waterPos.getY() : 64;
            d.waterZ = s.waterPos != null ? s.waterPos.getZ() : 0;
            d.hasCastTarget = s.castTarget != null;
            d.castX = s.castTarget != null ? s.castTarget.getX() : 0;
            d.castY = s.castTarget != null ? s.castTarget.getY() : 0;
            d.castZ = s.castTarget != null ? s.castTarget.getZ() : 0;
            d.fishCaught = s.fishCaught;
            d.targetFish = s.targetFish;
            d.rawArgs = s.rawArgs;
            d.savedAtMs = System.currentTimeMillis();
            return d;
        }

        FishingSession toSession() {
            UUID id;
            try { id = UUID.fromString(botId); }
            catch (Exception e) { return null; }
            BlockPos stand = new BlockPos(standX, standY, standZ);
            BlockPos water = new BlockPos(waterX, waterY, waterZ);
            BlockPos cast = hasCastTarget ? new BlockPos(castX, castY, castZ) : null;
            return new FishingSession(id, stand, water, cast, fishCaught, targetFish, rawArgs);
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private static Path stateFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("frens").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) return;
            Path file = stateFile();
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    @SuppressWarnings("unchecked")
                    Map<String, SessionData> parsed = GSON.fromJson(reader,
                            new com.google.gson.reflect.TypeToken<Map<String, SessionData>>() {}.getType());
                    if (parsed != null) DATA = parsed;
                } catch (Exception e) {
                    LOGGER.warn("Failed to load fishing sessions: {}", e.getMessage());
                    DATA = new HashMap<>();
                }
            }
            long now = System.currentTimeMillis();
            DATA.entrySet().removeIf(e -> now - e.getValue().savedAtMs > EXPIRY_MS);
            loaded = true;
        }
    }

    private static void flush() {
        Path file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(DATA, writer);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to save fishing sessions: {}", e.getMessage());
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    public static void saveSession(ServerPlayerEntity bot, BlockPos stand, BlockPos water,
                                    BlockPos castTarget, int fishCaught, int targetFish,
                                    String rawArgs) {
        if (bot == null) return;
        FishingSession session = new FishingSession(
                bot.getUuid(), stand, water, castTarget,
                fishCaught, targetFish, rawArgs);
        ensureLoaded();
        synchronized (LOCK) {
            DATA.put(bot.getUuid().toString(), SessionData.from(session));
        }
        flush();
        LOGGER.info("Fishing session saved for {}: caught={}/{} stand={}",
                bot.getName().getString(), fishCaught, targetFish,
                stand != null ? stand.toShortString() : "null");
    }

    public static FishingSession getSession(UUID botId) {
        if (botId == null) return null;
        ensureLoaded();
        synchronized (LOCK) {
            SessionData d = DATA.get(botId.toString());
            if (d == null) return null;
            if (System.currentTimeMillis() - d.savedAtMs > EXPIRY_MS) {
                DATA.remove(botId.toString());
                flush();
                return null;
            }
            return d.toSession();
        }
    }

    public static FishingSession consumeSession(UUID botId) {
        if (botId == null) return null;
        ensureLoaded();
        synchronized (LOCK) {
            SessionData d = DATA.remove(botId.toString());
            if (d == null) return null;
            flush();
            if (System.currentTimeMillis() - d.savedAtMs > EXPIRY_MS) return null;
            return d.toSession();
        }
    }

    public static boolean hasSession(UUID botId) {
        if (botId == null) return false;
        ensureLoaded();
        synchronized (LOCK) {
            SessionData d = DATA.get(botId.toString());
            if (d == null) return false;
            if (System.currentTimeMillis() - d.savedAtMs > EXPIRY_MS) {
                DATA.remove(botId.toString());
                flush();
                return false;
            }
            return true;
        }
    }

    public static void clearSession(UUID botId) {
        if (botId == null) return;
        ensureLoaded();
        synchronized (LOCK) {
            if (DATA.remove(botId.toString()) != null) {
                flush();
                LOGGER.info("Fishing session cleared for {}", botId);
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/FishingSessionService.java
git commit -m "feat: Add FishingSessionService for multi-day fishing persistence"
```

---

### Task 2: Wire BotAutoReturnSunsetService for Fishing

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java`

The service needs two changes:
1. **Sunset exclusion (near line 363):** Add `isFish` alongside `isHunt` so fishing skips the generic resume path
2. **Sunrise resume (near line 304-316):** Add fish session check alongside hunt session check

- [ ] **Step 1: Add isFish exclusion at sunset**

No import needed -- `FishingSessionService` is in the same package (`net.wcfcarolina13.GameAI.services`).

At line 363, the current code is:

```java
boolean isHunt = "hunt".equalsIgnoreCase(skillName);

if (!isHunt) {
```

Change to:

```java
boolean isHunt = "hunt".equalsIgnoreCase(skillName);
boolean isFish = "fish".equalsIgnoreCase(skillName);

if (!isHunt && !isFish) {
```

This prevents the generic `SkillResumeService.saveSunriseResume()` from firing for fishing. FishingSkill saves its own session internally (like HuntSkill does).

- [ ] **Step 2: Add fish-specific sunrise resume**

At lines 306-316, the current code checks for hunt sessions and falls through to generic resume. Add a fish session check between hunt and generic:

Current (lines 304-332):
```java
if (tod < SUNRISE_END_TICK) {
    // Hunt-specific sunrise resume (has its own session with kill counters)
    if (HuntSessionService.hasSession(bot.getUuid())) {
        long lastResumed = LAST_RESUMED_DAY.getOrDefault(bot.getUuid(), Long.MIN_VALUE);
        if (lastResumed < day) {
            LAST_RESUMED_DAY.put(bot.getUuid(), day);
            var taskInfo = TaskService.getActiveTaskInfo(bot.getUuid());
            if (taskInfo.isEmpty()) {
                LOGGER.info("Sunrise hunt resume for {} (day={})", bot.getName().getString(), day);
                SkillResumeService.tryAutoResume(bot);
            }
        }
    }
    // Generic sunrise resume (woodcut, fish, farm, etc.)
    else {
```

Change to:
```java
if (tod < SUNRISE_END_TICK) {
    // Hunt-specific sunrise resume (has its own session with kill counters)
    if (HuntSessionService.hasSession(bot.getUuid())) {
        long lastResumed = LAST_RESUMED_DAY.getOrDefault(bot.getUuid(), Long.MIN_VALUE);
        if (lastResumed < day) {
            LAST_RESUMED_DAY.put(bot.getUuid(), day);
            var taskInfo = TaskService.getActiveTaskInfo(bot.getUuid());
            if (taskInfo.isEmpty()) {
                LOGGER.info("Sunrise hunt resume for {} (day={})", bot.getName().getString(), day);
                SkillResumeService.tryAutoResume(bot);
            }
        }
    }
    // Fishing-specific sunrise resume (has its own session with catch counters)
    else if (FishingSessionService.hasSession(bot.getUuid())) {
        long lastResumed = LAST_RESUMED_DAY.getOrDefault(bot.getUuid(), Long.MIN_VALUE);
        if (lastResumed < day) {
            LAST_RESUMED_DAY.put(bot.getUuid(), day);
            var taskInfo = TaskService.getActiveTaskInfo(bot.getUuid());
            if (taskInfo.isEmpty()) {
                LOGGER.info("Sunrise fishing resume for {} (day={})", bot.getName().getString(), day);
                SkillResumeService.tryAutoResume(bot);
            }
        }
    }
    // Generic sunrise resume (woodcut, farm, etc.)
    else {
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java
git commit -m "feat: Wire BotAutoReturnSunsetService for fishing sunrise resume"
```

---

## Chunk 2: FishingSkill Sunrise Resume + Sunset Save

### Task 3: Add Imports and rawArgs Capture to FishingSkill

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java`

- [ ] **Step 1: Add new imports**

After line 30 (`import net.wcfcarolina13.GameAI.services.FollowPathService;`), add:

```java
import net.wcfcarolina13.GameAI.services.FishingSessionService;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.SkillResumeService;
```

- [ ] **Step 2: Add rawArgs reconstruction**

After the `boolean hobby = isHobby(context);` line (line 101), before the null check for bot, add a rawArgs helper extraction. Actually, rawArgs should be built after parameters are parsed (after line 185). Insert after line 189 (`targetFish = Integer.MAX_VALUE;`):

```java
        // Reconstruct rawArgs for sunrise resume
        String rawArgs = "";
        if (explicitSunset) {
            rawArgs = "until_sunset";
        } else if (targetFish != Integer.MAX_VALUE) {
            rawArgs = String.valueOf(targetFish);
        }
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java
git commit -m "feat: Add fishing session imports and rawArgs capture"
```

---

### Task 4: Add Sunrise Resume to FishingSkill execute()

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java`

The sunrise resume logic goes before `findFishingSpot()` (line 135-136). When a saved session exists, validate the spot and reuse it.

- [ ] **Step 1: Add session resume before spot finding**

Replace the spot-finding block (lines 135-143) which currently is:

```java
        long spotStart = System.nanoTime();
        FishingSpot spot = findFishingSpot(bot, WATER_SEARCH_RADIUS);
        long spotElapsedMs = (System.nanoTime() - spotStart) / 1_000_000L;
        if (spot == null) {
            if (hasNearbyWater(bot, WATER_SEARCH_RADIUS)) {
                return SkillExecutionResult.failure("No safe shoreline block to stand on.");
            }
            return SkillExecutionResult.failure("I need to be standing near open water to fish.");
        }
```

With:

```java
        // Sunrise resume: restore saved session if available
        FishingSpot spot = null;
        int resumedCaught = 0;
        int resumedTarget = -1;
        FishingSessionService.FishingSession savedSession =
                FishingSessionService.consumeSession(bot.getUuid());
        if (savedSession != null) {
            LOGGER.info("Found saved fishing session: caught={}/{} stand={}",
                    savedSession.fishCaught(), savedSession.targetFish(),
                    savedSession.standPos() != null ? savedSession.standPos().toShortString() : "null");
            resumedCaught = savedSession.fishCaught();
            resumedTarget = savedSession.targetFish();
            // Validate saved spot
            ServerWorld validateWorld = bot.getEntityWorld() instanceof ServerWorld sw ? sw : null;
            if (validateWorld != null && savedSession.standPos() != null && savedSession.waterPos() != null) {
                boolean waterValid = validateWorld.getFluidState(savedSession.waterPos()).isIn(FluidTags.WATER);
                boolean standNotWater = !validateWorld.getFluidState(savedSession.standPos()).isIn(FluidTags.WATER);
                BlockPos standBelow = savedSession.standPos().down();
                boolean groundSolid = !validateWorld.getBlockState(standBelow).isReplaceable()
                        && !validateWorld.getFluidState(standBelow).isIn(FluidTags.WATER);
                if (waterValid && standNotWater && groundSolid) {
                    spot = new FishingSpot(savedSession.waterPos(), savedSession.standPos(),
                            savedSession.castTarget(), List.of(savedSession.standPos()));
                    LOGGER.info("Resumed fishing at saved spot: stand={} water={}",
                            savedSession.standPos().toShortString(), savedSession.waterPos().toShortString());
                } else {
                    LOGGER.info("Saved fishing spot no longer valid (water={} stand={} ground={}), scanning for new spot",
                            waterValid, standNotWater, groundSolid);
                }
            }
        }

        if (spot == null) {
            long spotStart = System.nanoTime();
            spot = findFishingSpot(bot, WATER_SEARCH_RADIUS);
            long spotElapsedMs = (System.nanoTime() - spotStart) / 1_000_000L;
            if (spot == null) {
                if (hasNearbyWater(bot, WATER_SEARCH_RADIUS)) {
                    return SkillExecutionResult.failure("No safe shoreline block to stand on.");
                }
                return SkillExecutionResult.failure("I need to be standing near open water to fish.");
            }
            if (spotElapsedMs > 250L) {
                LOGGER.info("Fishing spot search took {}ms", spotElapsedMs);
            }
        }
```

Also remove the `spotElapsedMs` log that was at line 172-173 since it's now inside the `if (spot == null)` block.

- [ ] **Step 2: Apply resumed state to targetFish and caught**

After the rawArgs reconstruction (added in Task 3), and after `int caught = 0;` (line 192), add:

```java
        // Apply resumed session state
        if (resumedCaught > 0) {
            caught = resumedCaught;
            LOGGER.info("Resumed with {} prior catches", caught);
        }
        if (resumedTarget > 0 && resumedTarget != Integer.MAX_VALUE) {
            targetFish = resumedTarget;
        }
```

- [ ] **Step 3: Re-evaluate cast target on resume**

The existing cast target selection at line 166 already calls `chooseCastTarget()` which will re-derive the cast target. The saved `spot.castTarget()` is just a hint passed to it. No additional changes needed here -- the existing code handles this.

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java
git commit -m "feat: FishingSkill sunrise resume from saved session"
```

---

### Task 5: Sunset Save + Loop Reorder + Session Cleanup

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java`

Three changes in the main loop and post-loop.

- [ ] **Step 1: Reorder sunset check before abort check and add session save**

The current main loop starts (line 201-204):

```java
        while (caught < targetFish && attempts < maxAttempts) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Fishing paused by another task.");
            }
```

And the sunset check is at lines 244-250:

```java
            if (checkSunset) {
                long timeOfDay = world.getTimeOfDay() % 24000;
                if (timeOfDay >= 13000 && timeOfDay < 23000) {
                    ChatUtils.sendSystemMessage(source, "Sun has set. Stopping fishing.");
                    break;
                }
            }
```

**Replace the abort check (lines 202-204)** with the sunset check first, then abort:

```java
        while (caught < targetFish && attempts < maxAttempts) {
            // Sunset check BEFORE abort check -- ensures session is saved before
            // the abort latch (set at tick 12000 by BotAutoReturnSunsetService) fires.
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

            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Fishing paused by another task.");
            }
```

**Then remove the old sunset check block (original lines 244-250)** since it's now at the top of the loop.

- [ ] **Step 2: Add session cleanup after the loop**

After the while-loop ends (after line 396 `}`), before the final sweep, add:

```java
        // Clear any stale session on normal completion
        FishingSessionService.clearSession(bot.getUuid());
```

So the post-loop code becomes:

```java
            BotActions.stop(bot); // Ensure we don't drift
        }

        // Clear any stale session on normal completion
        FishingSessionService.clearSession(bot.getUuid());

        // Final Sweep
        performSweep(source, bot, stand);
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java
git commit -m "feat: FishingSkill sunset session save with loop reorder"
```

---

## Chunk 3: Vanilla Open Water Positioning

### Task 6: Add isVanillaOpenWater Method

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java`

- [ ] **Step 1: Add isVanillaOpenWater method**

Add after the existing `isOpenWaterSurface` method (after line 1132). The method goes in the same area as other water-check utilities:

```java
    /**
     * Vanilla Minecraft open water check for treasure-quality catches.
     * Checks a 5x4x5 area centered on the bobber position:
     * - Water surface (Y) and below (Y-1): all 5x5 must be Blocks.WATER
     * - Above (Y+1, Y+2): all 5x5 must be non-opaque, no lily pads
     * - Sky access above the bobber column
     * Cost: ~100 block reads. Only use on cast target candidates.
     */
    private static boolean isVanillaOpenWater(ServerWorld world, BlockPos waterSurface) {
        if (world == null || waterSurface == null) return false;
        // Water layers: surface and one below must be water source blocks
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
        // Air layers: Y+1 and Y+2 must be non-opaque, no lily pads
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
        // Sky access: heightmap O(1) check
        return world.isSkyVisible(waterSurface.up());
    }
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java
git commit -m "feat: Add isVanillaOpenWater() for treasure catch detection"
```

---

### Task 7: Integrate Open Water into Cast Target Scoring

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java`

- [ ] **Step 1: Add open water bonus to scoring**

In `chooseCastTargetAlongLine()`, the scoring block is at lines 1049-1053:

```java
                    double distSq = stand.getSquaredDistance(surface);
                    double distPenalty = Math.abs(distSq - IDEAL_CAST_DISTANCE_SQ) / (double) IDEAL_CAST_DISTANCE_SQ;
                    int openness = countOpenWaterSurface(world, surface, 1);
                    int depth = estimateWaterDepth(world, surface, 6);
                    double score = distPenalty - openness * 0.35 - depth * 0.55;
```

Replace with:

```java
                    double distSq = stand.getSquaredDistance(surface);
                    double distPenalty = Math.abs(distSq - IDEAL_CAST_DISTANCE_SQ) / (double) IDEAL_CAST_DISTANCE_SQ;
                    int openness = countOpenWaterSurface(world, surface, 1);
                    int depth = estimateWaterDepth(world, surface, 6);
                    double openWaterBonus = isVanillaOpenWater(world, surface) ? -3.0 : 0.0;
                    double score = distPenalty - openness * 0.35 - depth * 0.55 + openWaterBonus;
```

The `-3.0` bonus dominates the scoring range (existing scores range roughly -2 to +2), so the bot strongly prefers treasure-eligible targets but silently falls back to the best available.

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/impl/FishingSkill.java
git commit -m "feat: Integrate vanilla open water bonus into cast target scoring"
```

---

### Task 8: Final Build Verification + Changelog

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Full build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update changelog**

Add entry to `changelog.md`:

```markdown
## 2026-04-05 — Fishing Sunrise Resume + Open Water Positioning

- **FishingSessionService**: New persistence service for multi-day fishing sessions (mirrors HuntSessionService). Saves stand, water, cast target, catch count. 24h expiry.
- **Sunrise resume**: FishingSkill saves session at sunset, resumes at the same spot next morning. Cast target re-evaluated on resume. Progress (catch count) preserved.
- **BotAutoReturnSunsetService**: Fish-specific sunrise resume path alongside existing hunt path.
- **Vanilla open water**: `isVanillaOpenWater()` checks 5x4x5 area per Minecraft spec (water layers, air layers, sky access). Cast targets passing the check get -3.0 scoring bonus for treasure-quality catches. Silent fallback if no open water available.
- **Loop reorder**: Sunset check moved before abort check to prevent session loss from abort latch race.
```

- [ ] **Step 3: Commit changelog**

```bash
git add changelog.md
git commit -m "docs: Update changelog for fishing sunrise resume + open water"
```
