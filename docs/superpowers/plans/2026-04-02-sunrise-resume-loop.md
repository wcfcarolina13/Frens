# Sunrise Resume Loop Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable autonomous daily bot cycles (skill → sunset → home → sleep → sunrise → return → resume) with supporting GUI, bases menu, and smoke signal features.

**Architecture:** Four independent sub-features: (1) Woodcut "Until sunset" GUI toggle modifies the actions menu to match fishing's pattern. (2) Generic sunrise resume adds `SunriseResumeRecord` to `SkillResumeService` and extends the sunset/sunrise flow. (3) Lodestones in Bases menu auto-populates compass destinations in `BaseNetworkManager`. (4) Smoke signal beacon adds campfire+hay detection to the underground travel gate.

**Tech Stack:** Minecraft 1.21.11 Fabric, Java 21

**Spec:** `docs/superpowers/specs/2026-04-02-sunrise-resume-loop-design.md`

---

## Chunk 1: Woodcut "Until Sunset" GUI Toggle

### Task 1: Make woodcut open-ended when no count is provided

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java:330-333`

- [ ] **Step 1: Extend isOpenEnded() to include woodcut**

In `SkillManager.java`, find the fishing-specific check at line 330:

```java
        // Fishing is open-ended when no explicit count was provided.
        if ("fish".equalsIgnoreCase(skillName) || "fishing".equalsIgnoreCase(skillName)) {
            return !params.containsKey("count");
        }
```

Replace with:

```java
        // Fishing/Woodcut is open-ended when no explicit count was provided.
        if ("fish".equalsIgnoreCase(skillName) || "fishing".equalsIgnoreCase(skillName)
                || "woodcut".equalsIgnoreCase(skillName)) {
            return !params.containsKey("count");
        }
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java
git commit -m "feat: Woodcut is open-ended when no count provided (like fishing)"
```

---

### Task 2: Update woodcut GUI to support "Until sunset" mode

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java`

Woodcut has its own custom overlay rendering path (separate from the generic adjustable skill system used by fishing/wool/etc.). We modify it in-place.

- [ ] **Step 1: Change constants and initial value**

At line 90, change min from 1 to 0:
```java
private static final int WOODCUT_TREE_COUNT_MIN = 0;
```

At line 182, change initial value to SKILL_COUNT_UNSET (0):
```java
private int woodcutTreeCount = SKILL_COUNT_UNSET;
```

- [ ] **Step 2: Update overlay label rendering**

The woodcut overlay shows "Area min N" at three locations (lines 2792, 3010, 3235). At each location, replace:

```java
String countLabel = "Area min " + woodcutTreeCount;
```

With:

```java
String countLabel = woodcutTreeCount > 0 ? "Trees " + woodcutTreeCount : "Until sunset";
```

- [ ] **Step 3: Update adjustWoodcutTreeCount to allow reaching 0**

At lines 5769-5775, replace the method body:

```java
    private void adjustWoodcutTreeCount(int direction) {
        woodcutTreeCount = MathHelper.clamp(
                woodcutTreeCount + direction,
                WOODCUT_TREE_COUNT_MIN,
                WOODCUT_TREE_COUNT_MAX
        );
    }
```

With:

```java
    private void adjustWoodcutTreeCount(int direction) {
        int next = woodcutTreeCount + direction;
        if (next < WOODCUT_TREE_COUNT_MIN) {
            woodcutTreeCount = SKILL_COUNT_UNSET;
        } else {
            woodcutTreeCount = MathHelper.clamp(next, WOODCUT_TREE_COUNT_MIN, WOODCUT_TREE_COUNT_MAX);
        }
    }
```

Wait — since MIN is now 0 and SKILL_COUNT_UNSET is also 0, the simple clamp already handles it. The original code with min=0 is sufficient:

```java
    private void adjustWoodcutTreeCount(int direction) {
        woodcutTreeCount = MathHelper.clamp(
                woodcutTreeCount + direction,
                WOODCUT_TREE_COUNT_MIN,
                WOODCUT_TREE_COUNT_MAX
        );
    }
```

Actually keep the original method — just changing `WOODCUT_TREE_COUNT_MIN` to 0 makes the clamp allow 0 naturally.

- [ ] **Step 4: Update runWoodcutSkillCommand to pass null when count is 0**

At lines 5339-5341, replace:

```java
    private void runWoodcutSkillCommand() {
        runSkillCommand("woodcut", Integer.toString(woodcutTreeCount));
    }
```

With:

```java
    private void runWoodcutSkillCommand() {
        String arg = woodcutTreeCount > 0 ? Integer.toString(woodcutTreeCount) : null;
        runSkillCommand("woodcut", arg);
    }
```

- [ ] **Step 5: Update direct input commit handler**

At lines 5864-5865, the direct input handler clamps to MIN. Since MIN is now 0, this already works. No change needed — verify by reading the code.

- [ ] **Step 6: Update tooltip**

At lines 4022-4026, replace:

```java
            case SKILL_WOODCUT -> java.util.List.of(
                "Woodcut",
                "Fells natural trees and collects the wood.",
                "Set how many trees to cut before starting; standalone runs stop at sunset."
            );
```

With:

```java
            case SKILL_WOODCUT -> java.util.List.of(
                "Woodcut",
                "Fells natural trees and collects the wood.",
                "Default: runs until sunset. Use +/- to set a specific tree count instead."
            );
```

- [ ] **Step 7: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotPlayerInventoryScreen.java
git commit -m "feat: Woodcut 'Until sunset' mode in actions menu"
```

---

### Task 3: Update in-game guide

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java:865-877`

- [ ] **Step 1: Update woodcut guide entry**

At lines 865-877, replace:

```java
                new GuideTopic(
                        "gather_woodcut",
                        "Gathering",
                        "Woodcut",
                        "Clears nearby woodcut targets in the local area with a configurable minimum count.",
                        List.of(
                                "Use +/- to set the minimum number of targets to clear before checking whether the area is exhausted.",
                                "Use Cleanup after large runs to gather leftovers quickly."
                        ),
                        "bot skill woodcut <count> " + target,
                        "Actions menu count controls",
                        "tree lumber logs"
                ),
```

With:

```java
                new GuideTopic(
                        "gather_woodcut",
                        "Gathering",
                        "Woodcut",
                        "Fells nearby trees. Defaults to 'Until sunset' mode; use +/- to set a specific tree count.",
                        List.of(
                                "At the default (Until sunset), the bot cuts trees until dusk then stops.",
                                "Use +/- to set a minimum tree count instead.",
                                "Use Cleanup after large runs to gather leftovers quickly."
                        ),
                        "bot skill woodcut [count] " + target,
                        "Actions menu count controls",
                        "tree lumber logs"
                ),
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java
git commit -m "docs: Update woodcut guide for 'Until sunset' default"
```

---

## Chunk 2: Generic Sunrise Skill Resume

### Task 4: Add SunriseResumeRecord to SkillResumeService

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/SkillResumeService.java`

- [ ] **Step 1: Add the record and map**

After the existing field declarations (around line 26), add:

```java
    // ── Sunrise resume: generic skill resume after sunset interruption ────
    private static final long SUNRISE_RESUME_EXPIRY_TICKS = 24000L; // 1 in-game day
    private static final Map<UUID, SunriseResumeRecord> SUNRISE_RESUME_BY_BOT = new ConcurrentHashMap<>();

    public record SunriseResumeRecord(
            UUID botUuid,
            String skillName,
            String rawArgs,
            BlockPos interruptionPos,
            boolean shelteredInPlace,
            long savedTick
    ) {}
```

Add required import at the top:
```java
import net.minecraft.util.math.BlockPos;
```

- [ ] **Step 2: Add save/get/clear methods and rawArgs accessor**

Add after the existing `tryAutoResume` method (around line 134):

```java
    /** Save a sunrise resume record when sunset interrupts an open-ended skill. */
    public static void saveSunriseResume(UUID botUuid, String skillName, String rawArgs,
                                          BlockPos interruptionPos, boolean shelteredInPlace, long serverTick) {
        if (botUuid == null || skillName == null) return;
        SUNRISE_RESUME_BY_BOT.put(botUuid, new SunriseResumeRecord(
                botUuid, skillName, rawArgs, interruptionPos, shelteredInPlace, serverTick));
        LOGGER.info("Saved sunrise resume for {}: skill={} pos={} sheltered={}",
                botUuid, skillName, interruptionPos != null ? interruptionPos.toShortString() : "null", shelteredInPlace);
    }

    /** Get the sunrise resume record for a bot, or null if expired/absent. */
    public static SunriseResumeRecord getSunriseResume(UUID botUuid, long currentTick) {
        if (botUuid == null) return null;
        SunriseResumeRecord rec = SUNRISE_RESUME_BY_BOT.get(botUuid);
        if (rec == null) return null;
        if (currentTick - rec.savedTick() > SUNRISE_RESUME_EXPIRY_TICKS) {
            SUNRISE_RESUME_BY_BOT.remove(botUuid);
            return null;
        }
        return rec;
    }

    /** Clear the sunrise resume record for a bot. */
    public static void clearSunriseResume(UUID botUuid) {
        if (botUuid != null) {
            SUNRISE_RESUME_BY_BOT.remove(botUuid);
        }
    }

    /** Get the raw args of the last recorded skill for a bot, or null. */
    public static String getLastRawArgs(UUID botUuid) {
        if (botUuid == null) return null;
        PendingSkill pending = LAST_SKILL_BY_BOT.get(botUuid);
        return pending != null ? pending.rawArgs() : null;
    }
```

Note: `ActiveTaskInfo` does NOT have a `rawArgs()` field — it only has `name()`. The `rawArgs` lives in the private `PendingSkill` record inside `SkillResumeService`. This new `getLastRawArgs()` accessor exposes it for the sunset save path.

- [ ] **Step 3: Clear sunrise resume on death**

In `handleDeath()` (line 68), add at the top of the method:

```java
    public static void handleDeath(ServerPlayerEntity bot) {
        clearSunriseResume(bot.getUuid());
        PendingSkill pending = LAST_SKILL_BY_BOT.get(bot.getUuid());
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/SkillResumeService.java
git commit -m "feat: Add SunriseResumeRecord for generic sunrise skill resume"
```

---

### Task 5: Add findBaseNearPosition to BotHomeService

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java`

- [ ] **Step 1: Add the method**

After the existing `findNearestBase(ServerPlayerEntity)` method (around line 957), add:

```java
    /**
     * Find a saved base whose protection radius contains the given position.
     * Returns the first matching base, or empty if no base covers the position.
     */
    public static Optional<BaseEntry> findBaseNearPosition(MinecraftServer server, ServerWorld world, BlockPos pos) {
        if (server == null || world == null || pos == null) return Optional.empty();
        List<BaseEntry> bases = listBases(server, world);
        for (BaseEntry base : bases) {
            if (base == null || base.pos() == null) continue;
            int radius = base.radius() > 0 ? base.radius() : DEFAULT_BASE_PROTECTION_RADIUS;
            if (base.pos().isWithinDistance(pos, radius)) {
                return Optional.of(base);
            }
        }
        return Optional.empty();
    }
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java
git commit -m "feat: Add findBaseNearPosition for radius-based base lookup"
```

---

### Task 6: Add skipArtifactGate to beginDelayedTravel

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java`

- [ ] **Step 1: Add new public entry point for base-bypass travel**

After the existing `beginEmergencyTravel` method (around line 435), add:

```java
    /**
     * Travel from within a saved base, bypassing the artifact/underground gate.
     * Combat, cooldown, and food gates still apply. Delay uses 3x (no-artifact) multiplier.
     */
    public static boolean beginBaseBypassTravel(MinecraftServer server, ServerPlayerEntity bot,
                                                 String botAlias, BlockPos destination,
                                                 RegistryKey<World> dimension, UUID ownerUuid) {
        if (server == null || bot == null || destination == null || dimension == null) return false;
        double distance = bot.getBlockPos().getManhattanDistance(destination);
        boolean crossDim = !((ServerWorld) bot.getEntityWorld()).getRegistryKey().equals(dimension);
        int delayTicks = calculateDelayTicks(distance, crossDim, 3.0); // 3x no-artifact penalty
        return beginDelayedTravel(server, bot, botAlias, destination, dimension, delayTicks, ownerUuid, false, false, true);
    }
```

- [ ] **Step 2: Add skipArtifactGate parameter to private overload**

The private overload at line 483 currently has signature:
```java
    private static boolean beginDelayedTravel(MinecraftServer server, ServerPlayerEntity bot,
                                              String botAlias, BlockPos destination,
                                              RegistryKey<World> dimension, int delayTicks,
                                              UUID ownerUuid, boolean skipGates, boolean suppressOwnerNotify) {
```

Add a `skipArtifactGate` parameter. Change the signature to:
```java
    private static boolean beginDelayedTravel(MinecraftServer server, ServerPlayerEntity bot,
                                              String botAlias, BlockPos destination,
                                              RegistryKey<World> dimension, int delayTicks,
                                              UUID ownerUuid, boolean skipGates, boolean suppressOwnerNotify,
                                              boolean skipArtifactGate) {
```

- [ ] **Step 3: Update all existing callers to pass false for the new parameter**

Search for all calls to the private `beginDelayedTravel` with `skipGates` argument. Each one needs `, false` appended. These include:
- The public `beginDelayedTravel` (line 423): `..., false, false)` → `..., false, false, false)`
- `beginEmergencyTravel` (line 434): `..., true, true)` → `..., true, true, false)`
- `beginCoordinatedEmergencyTravel` (lines 464, 469): both `..., true, true)` → `..., true, true, false)`

- [ ] **Step 4: Use skipArtifactGate in the underground gate**

In the private `beginDelayedTravel`, at the underground gate section (around line 542), change:

```java
            if (!nearSurface) {
                    ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
                    double mult = artifactDelayMultiplier(bot, owner);
                    if (mult <= 1.0) {
```

To:

```java
            if (!nearSurface) {
                    if (skipArtifactGate) {
                        // Base bypass: skip underground artifact check entirely
                    } else {
                    ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
                    double mult = artifactDelayMultiplier(bot, owner);
                    if (mult <= 1.0) {
```

And close the else block at the end of the underground gate (after the "cannot fast-travel underground" refusal). The exact edit will depend on the indentation — the implementer should read the full underground gate block and wrap it in `if (!skipArtifactGate) { ... }`.

- [ ] **Step 5: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java
git commit -m "feat: Add base-bypass travel that skips underground artifact gate"
```

---

### Task 7: Save sunrise resume on sunset interruption (with case evaluation)

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java:335-340`

**Important API note:** `TaskService.ActiveTaskInfo` has `name()` (not `skillName()`) and does NOT have `rawArgs()`. Use `info.name()` for the skill name, and `SkillResumeService.getLastRawArgs(bot.getUuid())` for the raw args (added in Task 4).

- [ ] **Step 1: Replace sunset skill abort with case-evaluating save**

At lines 335-340, the existing code aborts open-ended skills unconditionally:

```java
                TaskService.getActiveTaskInfo(bot.getUuid()).ifPresent(info -> {
                    if (info.openEnded()) {
                        TaskService.forceAbort(bot.getUuid(), "§cSunset: heading home.");
                    }
                });
```

Replace with:

```java
                TaskService.getActiveTaskInfo(bot.getUuid()).ifPresent(info -> {
                    if (info.openEnded()) {
                        // Hunt has its own session system — skip generic resume
                        boolean isHunt = "hunt".equalsIgnoreCase(info.name());

                        if (!isHunt) {
                            String rawArgs = SkillResumeService.getLastRawArgs(bot.getUuid());
                            BlockPos interruptionPos = bot.getBlockPos().toImmutable();
                            long tick = server.getOverworld().getTime();

                            // Evaluate which sunset case applies:
                            // Case 1: Has lodestone compass → already handled by sunset anchor system
                            // Case 2: Inside a saved base → base-bypass travel
                            // Case 3: Tactical shelter ON → shelter in place
                            // Case 4: Nothing available → no resume
                            boolean hasLodestoneAnchor = false;
                            RegistryKey<World> botDim = world.getRegistryKey();
                            var compasses = LodestoneCompassService.findLodestoneCompasses(bot);
                            for (var lc : compasses) {
                                if (lc.target().dimension().equals(botDim)
                                        && LodestoneCompassService.validateLodestone(server, lc.target())) {
                                    hasLodestoneAnchor = true;
                                    break;
                                }
                            }

                            if (hasLodestoneAnchor) {
                                // Case 1: compass available — save resume, sunset system handles travel
                                SkillResumeService.saveSunriseResume(
                                        bot.getUuid(), info.name(), rawArgs,
                                        interruptionPos, false, tick);
                            } else {
                                Optional<BotHomeService.BaseEntry> nearBase =
                                        BotHomeService.findBaseNearPosition(server, world, interruptionPos);
                                if (nearBase.isPresent()) {
                                    // Case 2: at a base — save resume, sunset system walks home
                                    SkillResumeService.saveSunriseResume(
                                            bot.getUuid(), info.name(), rawArgs,
                                            interruptionPos, false, tick);
                                } else if (BotHomeService.isTacticalShelterEnabled(bot)) {
                                    // Case 3: tactical shelter — save resume as sheltered in place
                                    SkillResumeService.saveSunriseResume(
                                            bot.getUuid(), info.name(), rawArgs,
                                            interruptionPos, true, tick);
                                }
                                // Case 4: nothing available, tactical shelter OFF — no resume saved
                            }
                        }

                        TaskService.forceAbort(bot.getUuid(), "§cSunset: heading home.");
                    }
                });
```

Add required imports if not already present:
```java
import java.util.Optional;
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java
git commit -m "feat: Save sunrise resume on sunset with 4-case evaluation"
```

---

### Task 8: Add sunrise resume trigger

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java:304-315`

- [ ] **Step 1: Extend sunrise check to handle generic resume**

At lines 304-315, after the existing hunt resume block, add the generic resume check. Replace:

```java
            if (tod < SUNRISE_END_TICK && HuntSessionService.hasSession(bot.getUuid())) {
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
```

With:

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
                    long currentTick = server.getOverworld().getTime();
                    SkillResumeService.SunriseResumeRecord resume =
                            SkillResumeService.getSunriseResume(bot.getUuid(), currentTick);
                    if (resume != null) {
                        long lastResumed = LAST_RESUMED_DAY.getOrDefault(bot.getUuid(), Long.MIN_VALUE);
                        if (lastResumed < day) {
                            var taskInfo = TaskService.getActiveTaskInfo(bot.getUuid());
                            if (taskInfo.isEmpty()) {
                                LAST_RESUMED_DAY.put(bot.getUuid(), day);
                                executeSunriseResume(server, bot, resume);
                            }
                        }
                    }
                }
            }
```

- [ ] **Step 2: Add executeSunriseResume helper method**

Add as a private static method in `BotAutoReturnSunsetService`:

```java
    private static final int SUNRISE_RETURN_COMPASS_RANGE = 128;

    private static void executeSunriseResume(MinecraftServer server, ServerPlayerEntity bot,
                                              SkillResumeService.SunriseResumeRecord resume) {
        String botAlias = bot.getName().getString();
        SkillResumeService.clearSunriseResume(bot.getUuid());

        // Case 1: Bot sheltered in place — just re-run the skill
        if (resume.shelteredInPlace()) {
            LOGGER.info("Sunrise resume (sheltered): {} re-running '{}'", botAlias, resume.skillName());
            dispatchSkillCommand(server, bot, resume.skillName(), resume.rawArgs());
            return;
        }

        // Case 2: Try to find a lodestone compass near the interruption position
        if (resume.interruptionPos() != null) {
            var compasses = LodestoneCompassService.findLodestoneCompasses(bot);
            RegistryKey<World> botDim = bot.getEntityWorld().getRegistryKey();
            LodestoneCompassService.LodestoneCompassEntry bestCompass = null;
            double bestDistSq = Double.MAX_VALUE;

            for (var c : compasses) {
                if (!c.target().dimension().equals(botDim)) continue;
                if (!LodestoneCompassService.validateLodestone(server, c.target())) continue;
                double distSq = c.target().pos().getSquaredDistance(resume.interruptionPos());
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestCompass = c;
                }
            }

            if (bestCompass != null && bestDistSq <= (double) SUNRISE_RETURN_COMPASS_RANGE * SUNRISE_RETURN_COMPASS_RANGE) {
                // Fast-travel to the lodestone, then resume skill on arrival
                BlockPos dest = bestCompass.target().pos();
                RegistryKey<World> dim = bestCompass.target().dimension();
                boolean crossDim = !botDim.equals(dim);
                double distance = bot.getBlockPos().getManhattanDistance(dest);
                int delayTicks = NavigationArtifactService.calculateDelayTicks(distance, crossDim, 1.0);
                UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);

                // Attempt fast-travel first, THEN schedule post-arrival if it started
                boolean started = NavigationArtifactService.beginDelayedTravel(
                        server, bot, botAlias, dest, dim, delayTicks, ownerUuid);
                if (started) {
                    String command = "bot skill " + resume.skillName()
                            + (resume.rawArgs() != null && !resume.rawArgs().isBlank() ? " " + resume.rawArgs() : "")
                            + " " + botAlias;
                    NavigationArtifactService.schedulePostArrival(botAlias,
                            new NavigationArtifactService.PostArrivalAction("skill_resume:" + command, null, ownerUuid, null));
                    LOGGER.info("Sunrise resume: {} fast-traveling to lodestone '{}' then resuming '{}'",
                            botAlias, bestCompass.displayName(), resume.skillName());
                    return;
                }
            }
        }

        // Case 3: No compass near worksite — just resume at current location
        LOGGER.info("Sunrise resume (local): {} re-running '{}' at current position", botAlias, resume.skillName());
        dispatchSkillCommand(server, bot, resume.skillName(), resume.rawArgs());
    }

    private static void dispatchSkillCommand(MinecraftServer server, ServerPlayerEntity bot,
                                              String skillName, String rawArgs) {
        String botAlias = bot.getName().getString();
        String command = "bot skill " + skillName
                + (rawArgs != null && !rawArgs.isBlank() ? " " + rawArgs : "")
                + " " + botAlias;
        server.getCommandManager().executeWithPrefix(
                server.getCommandSource().withSilent().withMaxLevel(4), command);
    }
```

- [ ] **Step 3: Add PostArrivalAction handling for skill_resume in NavigationArtifactService**

In `NavigationArtifactService.java`, in the `tickPendingTravels` method where post-arrival actions are processed (around line 941), the existing code checks `action.type().startsWith("withdraw")`. Add an else-if for `"skill_resume:"`:

Find:
```java
        if (action != null && action.type() != null && action.type().startsWith("withdraw")) {
```

Before this line, add:

```java
        if (action != null && action.type() != null && action.type().startsWith("skill_resume:")) {
            String command = action.type().substring("skill_resume:".length());
            server.execute(() -> {
                ServerPlayerEntity arrBot = server.getPlayerManager().getPlayer(ps.botAlias());
                if (arrBot != null && !arrBot.isRemoved()) {
                    LOGGER.info("Post-arrival skill resume for '{}': {}", ps.botAlias(), command);
                    server.getCommandManager().executeWithPrefix(
                            server.getCommandSource().withSilent().withMaxLevel(4), command);
                }
            });
        } else if (action != null && action.type() != null && action.type().startsWith("withdraw")) {
```

Add required imports to `BotAutoReturnSunsetService.java`:
```java
import net.minecraft.registry.RegistryKey;
// (World and other imports likely already present)
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java \
       src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java
git commit -m "feat: Generic sunrise skill resume with lodestone compass return"
```

---

## Chunk 3: Lodestones in Bases Menu + Smoke Signal

### Task 9: Add lodestone entries to bases menu

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/network/BaseNetworkManager.java:534-537`
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BaseManagerScreen.java:54-66`

- [ ] **Step 1: Add isLodestone() to BaseManagerScreen.BaseDto**

At line 54-66 of `BaseManagerScreen.java`, add after the `isVillage()` method:

```java
        boolean isLodestone() {
            return "lodestone".equalsIgnoreCase(kind);
        }
```

- [ ] **Step 2: Add lodestone compass entries in BaseNetworkManager**

At line 534 of `BaseNetworkManager.java`, after the villages loop and before the JSON serialization (`String json = GSON.toJson(out);` at line 536), add:

```java
        // Include lodestone compass destinations
        if (selectedBot != null) {
            for (LodestoneCompassService.LodestoneCompassEntry lc : LodestoneCompassService.findLodestoneCompasses(selectedBot)) {
                String compassLabel = lc.displayName();
                BlockPos lPos = lc.target().pos();
                String dim = lc.target().dimension().getValue().toString();
                boolean botSameDim = world.getRegistryKey().getValue().toString().equals(dim);
                String detail = botSameDim ? "Lodestone" : "Lodestone (" + dim + ")";
                boolean isHome = compassLabel.equalsIgnoreCase(
                        LodestoneCompassService.getHomeCompassName(selectedBot));
                out.add(new BaseDto("lodestone", compassLabel, lPos.getX(), lPos.getY(), lPos.getZ(),
                        isHome, detail, null, 0));
            }
        }
```

**Important:** The bot variable in this handler is `selectedBot` (resolved at line 496), NOT `bot`.

Add required import:
```java
import net.wcfcarolina13.GameAI.services.LodestoneCompassService;
```

- [ ] **Step 3: Add lodestone handling to BaseManagerScreen action methods**

**Important:** `sendChatCommand` takes `(MinecraftClient client, String command)` — use `sendChatCommand(this.client, ...)`. The `client` field is inherited from `Screen`.

**`sendSetHomeSelected()` (line 1136):** The existing method guards with `!selected.isBase()` at line 1142, which would reject lodestones. Add lodestone handling BEFORE the `isBase()` guard:

```java
    private void sendSetHomeSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a base first.", STATUS_WARN);
            return;
        }
        // Lodestone: set as home compass (must come BEFORE isBase check)
        if (selected.isLodestone()) {
            sendChatCommand(this.client, "bot compass home " + botAlias + " " + selected.label);
            showStatus("Setting home compass...", STATUS_OK);
            requestRefresh();
            return;
        }
        if (!selected.isBase()) {
            showStatus("Only bases can be set as home.", STATUS_WARN);
            return;
        }
        // ... rest of existing method unchanged
```

**`sendGoToSelected()` (line 1157):** Same pattern — add lodestone handling BEFORE the `isBase()` guard:

```java
    private void sendGoToSelected() {
        BaseDto selected = getSelected();
        if (selected == null || selected.label == null || selected.label.isBlank()) {
            showStatus("Select a base first.", STATUS_WARN);
            return;
        }
        // Lodestone: use compass travel (must come BEFORE isBase check)
        if (selected.isLodestone()) {
            sendChatCommand(this.client, "bot compass travel " + botAlias + " " + selected.label);
            showStatus("Sending bot via compass...", STATUS_OK);
            requestRefresh();
            return;
        }
        if (!selected.isBase()) {
            showStatus("Only bases are valid navigation targets here.", STATUS_WARN);
            return;
        }
        // ... rest of existing method unchanged
```

**Edit controls to disable for lodestones:** In `sendSetHere()`, `sendRenameSelected()`, `sendRemoveSelected()`, and `sendSetRadiusSelected()`, add an early return after the null check:

```java
        if (selected != null && selected.isLodestone()) {
            showStatus("Lodestone entries are managed via compasses.", STATUS_WARN);
            return;
        }
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/network/BaseNetworkManager.java \
       src/main/java/net/wcfcarolina13/GraphicalUserInterface/BaseManagerScreen.java
git commit -m "feat: Show lodestone compass destinations in bases menu"
```

---

### Task 10: Add smoke signal navigation beacon

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java`

- [ ] **Step 1: Add smoke signal detection method**

Add constants and the detection method:

```java
    // ── Smoke signal navigation beacon ───────────────────────────────────
    private static final int SMOKE_SIGNAL_SCAN_RADIUS_H = 8;
    private static final int SMOKE_SIGNAL_SCAN_RADIUS_V = 8;
    private static final long SMOKE_SIGNAL_CACHE_TICKS = 1200L; // 60 seconds
    private static final Map<BlockPos, Long> SMOKE_SIGNAL_CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Boolean> SMOKE_SIGNAL_RESULT_CACHE = new ConcurrentHashMap<>();

    /** Check if a base position has a smoke signal (lit campfire on hay bale) nearby. Cached for 60s. */
    public static boolean hasSmokeSignal(ServerWorld world, BlockPos basePos) {
        if (world == null || basePos == null) return false;
        long now = world.getTime();
        Long cachedAt = SMOKE_SIGNAL_CACHE.get(basePos);
        if (cachedAt != null && now - cachedAt < SMOKE_SIGNAL_CACHE_TICKS) {
            return Boolean.TRUE.equals(SMOKE_SIGNAL_RESULT_CACHE.get(basePos));
        }
        boolean found = scanForSmokeSignal(world, basePos);
        SMOKE_SIGNAL_CACHE.put(basePos, now);
        SMOKE_SIGNAL_RESULT_CACHE.put(basePos, found);
        return found;
    }

    private static boolean scanForSmokeSignal(ServerWorld world, BlockPos center) {
        for (int dx = -SMOKE_SIGNAL_SCAN_RADIUS_H; dx <= SMOKE_SIGNAL_SCAN_RADIUS_H; dx++) {
            for (int dy = -SMOKE_SIGNAL_SCAN_RADIUS_V; dy <= SMOKE_SIGNAL_SCAN_RADIUS_V; dy++) {
                for (int dz = -SMOKE_SIGNAL_SCAN_RADIUS_H; dz <= SMOKE_SIGNAL_SCAN_RADIUS_H; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    net.minecraft.block.BlockState state = world.getBlockState(pos);
                    if ((state.isOf(net.minecraft.block.Blocks.CAMPFIRE)
                            || state.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE))
                            && state.get(net.minecraft.block.CampfireBlock.LIT)
                            && world.getBlockState(pos.down()).isOf(net.minecraft.block.Blocks.HAY_BLOCK)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Clear smoke signal cache (call from SERVER_STOPPING). */
    public static void clearSmokeSignalCache() {
        SMOKE_SIGNAL_CACHE.clear();
        SMOKE_SIGNAL_RESULT_CACHE.clear();
    }
```

- [ ] **Step 2: Integrate smoke signal into the underground gate**

In the underground gate section of `beginDelayedTravel` (around line 548), after the "Map + Compass" check and before the refusal message, add a smoke signal check. Find:

```java
                    } else {
                        notifyOwner(server, ownerUuid,
                                "\u00A7c" + botAlias + " cannot fast-travel underground without a Map and Compass.\u00A7r");
                        return false;
                    }
```

Replace with:

```java
                    } else {
                        // Check for smoke signal at destination base (underground: 2x radius)
                        ServerWorld destWorld = server.getWorld(dimension);
                        Optional<BotHomeService.BaseEntry> destBase = destWorld != null
                                ? BotHomeService.findBaseNearPosition(server, destWorld, destination)
                                : Optional.empty();
                        if (destBase.isPresent()
                                && hasSmokeSignal(destWorld, destBase.get().pos())) {
                            int baseRadius = destBase.get().radius() > 0
                                    ? destBase.get().radius() : BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS;
                            double maxRange = baseRadius * 2.0; // 2x radius underground
                            double distToBase = bot.getBlockPos().getManhattanDistance(destBase.get().pos());
                            if (distToBase <= maxRange) {
                                // Smoke signal allows underground travel with 3x penalty
                                delayTicks = (int) (delayTicks * 3.0);
                            } else {
                                notifyOwner(server, ownerUuid,
                                        "\u00A7c" + botAlias + " is too far underground to see the smoke signal.\u00A7r");
                                return false;
                            }
                        } else {
                            notifyOwner(server, ownerUuid,
                                    "\u00A7c" + botAlias + " cannot fast-travel underground without a Map and Compass.\u00A7r");
                            return false;
                        }
                    }
```

**Important:** Use `destWorld` (the destination world from `server.getWorld(dimension)`) for both `findBaseNearPosition` and `hasSmokeSignal`. The destination base and its smoke signal are in the destination dimension, not the bot's current world.

- [ ] **Step 3: Add above-ground smoke signal check**

The above-ground check goes AFTER the underground gate but BEFORE the mount evaluation (around line 565). When the bot is above ground and has no tier-2 artifacts, check if a smoke signal extends the range. Add this new block:

```java
        // ── Above-ground smoke signal range extension (no-artifact bots) ─
        if (!skipGates && !skipArtifactGate) {
            ServerPlayerEntity ownerPlayer = server.getPlayerManager().getPlayer(ownerUuid);
            double aboveMult = artifactDelayMultiplier(bot, ownerPlayer);
            if (aboveMult >= 3.0) {
                // Bot has no artifacts at all — check if destination has a smoke signal
                ServerWorld destWorldCheck = server.getWorld(dimension);
                Optional<BotHomeService.BaseEntry> smokeBase = destWorldCheck != null
                        ? BotHomeService.findBaseNearPosition(server, destWorldCheck, destination)
                        : Optional.empty();
                if (smokeBase.isPresent()
                        && hasSmokeSignal(destWorldCheck, smokeBase.get().pos())) {
                    int baseRadius = smokeBase.get().radius() > 0
                            ? smokeBase.get().radius() : BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS;
                    double maxRange = baseRadius * 5.0; // 5x radius above ground
                    double distToBase = bot.getBlockPos().getManhattanDistance(smokeBase.get().pos());
                    if (distToBase <= maxRange) {
                        // Override delay to 3x (smoke signal enables travel but doesn't speed it up)
                        double dist = bot.getBlockPos().getManhattanDistance(destination);
                        boolean crossDim = !((ServerWorld) bot.getEntityWorld()).getRegistryKey().equals(dimension);
                        delayTicks = calculateDelayTicks(dist, crossDim, 3.0);
                    }
                }
            }
        }
```

This check only activates when the bot has the worst artifact multiplier (3.0 = no artifacts). It recalculates delay with 3x multiplier if a smoke signal is in range.

- [ ] **Step 3: Register cache cleanup in SERVER_STOPPING**

In `Frens.java` (the server-side mod entry), find the `SERVER_STOPPING` handler. Add:

```java
NavigationArtifactService.clearSmokeSignalCache();
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java \
       src/main/java/net/wcfcarolina13/Frens.java
git commit -m "feat: Smoke signal navigation beacon for artifact-free travel"
```

---

### Task 11: Final build + changelog

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Full build verification**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update changelog**

Add an entry to `changelog.md` under the existing 2026-04-02 section:

```markdown
- **Feat: Woodcut 'Until sunset' mode.** Actions menu now defaults to "Until sunset" (like fishing). Use +/- to set a specific tree count. Updated tooltip and in-game guide.

- **Feat: Generic sunrise skill resume.** Any open-ended skill interrupted by sunset is saved and automatically resumed at sunrise. The bot fast-travels back to the worksite via lodestone compass if available, or resumes locally. Works with woodcut, fishing, farming, mining, etc. Hunt keeps its own session system.

- **Feat: Lodestones in Bases menu.** Lodestone compass destinations auto-populate in the bases list with a "Lodestone" tag. "Go To" triggers compass fast-travel; "Set Home" designates the compass as home. Read-only entries (rename/delete/radius disabled).

- **Feat: Smoke signal navigation beacon.** A lit campfire with a hay bale underneath acts as a navigation beacon. Extends artifact-free fast-travel range: 5× base radius above ground, 2× below ground. Both regular and soul campfires work.
```

- [ ] **Step 3: Commit**

```bash
git add changelog.md
git commit -m "docs: Add sunrise resume loop changelog entries"
```
