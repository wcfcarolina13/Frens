# Pillager Patrol Alert Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `BotPillagerAlertService` so the bot detects illager groups (2+ visible within 16 blocks), goes defensive (shield up, don't chase), and sends a one-shot alert via the best available channel (goat horn > signal fire > direct message > fallback), escalating to normal combat when illagers aggro.

**Architecture:** Single new service in `GameAI.services/` piggybacking on the existing `engageHostiles` combat path. Two hooks in `BotEventHandler.engageHostiles` (patrol detection + pursuit suppression) and one hook in `Frens.java` (SERVER_STOPPING cleanup). A small `canLongRangeComm` helper added to `CompanionCommunicationPolicy` for the magic-comm inventory/proximity gate.

**Tech Stack:** Java 21, Minecraft 1.21.11, Fabric 0.18.4, Yarn mappings, existing `GameAI.services` patterns.

**Spec:** [docs/superpowers/specs/2026-04-11-pillager-patrol-alert-design.md](../specs/2026-04-11-pillager-patrol-alert-design.md) (approved)

---

## Pre-execution constraints

Same constraints as the Feature A plan:

1. Working tree should be clean. Verify with `git status --short` before starting.
2. Stage files explicitly by name. NEVER `git add -A` or `git add .`.
3. **No JAR deploy.** Build only.
4. Re-read each file fresh before patching — line numbers drift between commits.
5. If any patch conflicts with in-progress user edits, STOP and surface the conflict.

**Manual verification only.** No test infrastructure in this codebase. Build-passes = verification. The spec's 17-item manual verification checklist runs after the user deploys.

---

## File structure

| File | Action | Responsibility |
|---|---|---|
| `src/.../GameAI/services/BotPillagerAlertService.java` | **Create** | Patrol detection state, alert channel dispatch, pursuit suppression. ~350 LOC. |
| `src/.../GameAI/services/CompanionCommunicationPolicy.java` | **Modify** | Promote `isNearEnchantingTable` to public; add `canLongRangeComm` helper. |
| `src/.../GameAI/BotEventHandler.java` | **Modify** | Add patrol check + pursuit suppression guards at three `moveToward` sites. |
| `src/.../Frens.java` | **Modify** | Add `reset()` to SERVER_STOPPING. |
| `changelog.md` | **Modify** | Add entry. |

All paths are relative to `src/main/java/net/wcfcarolina13/`.

---

## Chunk 1: Foundation (service skeleton + comm helper)

### Task 1.1: Add `canLongRangeComm` to CompanionCommunicationPolicy

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java`

- [ ] **Step 1: Promote `isNearEnchantingTable` from private to public**

Re-read lines 203-224. Use Edit tool:

`old_string`:
```java
    private static boolean isNearEnchantingTable(ServerPlayerEntity player, int radius) {
```

`new_string`:
```java
    public static boolean isNearEnchantingTable(ServerPlayerEntity player, int radius) {
```

- [ ] **Step 2: Add `canLongRangeComm` helper**

Insert after the `isNearEnchantingTable` method (after line ~224). Use Edit tool:

`old_string` (the closing brace of `isNearEnchantingTable`):
```java
            if (st != null && st.isOf(Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }
```

`new_string`:
```java
            if (st != null && st.isOf(Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the bot can send a long-range comm message to the commander.
     * Four gates — any one passing unlocks:
     * <ul>
     *   <li>Bot OR commander has {@code Items.ENDER_EYE}</li>
     *   <li>Bot OR commander has the wizard's tome item</li>
     *   <li>Bot AND commander both have {@code Items.ENDER_PEARL}</li>
     *   <li>Bot OR commander is within 8 blocks of an enchanting table</li>
     * </ul>
     */
    public static boolean canLongRangeComm(ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null || commander == null) return false;
        // Eye of ender (either)
        ItemStack eyeStack = new ItemStack(Items.ENDER_EYE);
        if (bot.getInventory().contains(eyeStack) || commander.getInventory().contains(eyeStack)) {
            return true;
        }
        // Wizard's tome (either)
        if (hasWizardTome(bot) || hasWizardTome(commander)) {
            return true;
        }
        // Both have ender pearl
        ItemStack pearlStack = new ItemStack(Items.ENDER_PEARL);
        if (bot.getInventory().contains(pearlStack) && commander.getInventory().contains(pearlStack)) {
            return true;
        }
        // Either near enchanting table (radius 8 for long-range comm)
        if (isNearEnchantingTable(bot, 8) || isNearEnchantingTable(commander, 8)) {
            return true;
        }
        return false;
    }
```

If `Items` is not already imported in the file, add `import net.minecraft.item.Items;` and `import net.minecraft.item.ItemStack;`. Check first:

```bash
grep -n "import net.minecraft.item.Items" src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java
```

- [ ] **Step 3: Build**

Run: `./gradlew compileJava 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java
git commit -m "feat: add canLongRangeComm helper + promote isNearEnchantingTable

Four-gate check for long-range bot-to-commander communication:
eye of ender (either), wizard's tome (either), both have ender pearl,
or either near an enchanting table (radius 8). Reusable for the
pillager patrol alert and future features.

Also promoted isNearEnchantingTable from private to public so external
callers can use it with custom radii.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

### Task 1.2: Create BotPillagerAlertService skeleton

**Files:**

- Create: `src/main/java/net/wcfcarolina13/GameAI/services/BotPillagerAlertService.java`

- [ ] **Step 1: Write the skeleton file**

```java
package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.IllagerEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.RavagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects illager groups (2+ visible within 16 blocks, LOS-gated), goes
 * defensive (shield up, don't chase), and sends a one-shot alert via the
 * best available channel. Escalates to normal combat when illagers aggro.
 *
 * <p>Piggybacks on the existing {@code engageHostiles} combat path — no
 * separate tick service or world scan. See
 * docs/superpowers/specs/2026-04-11-pillager-patrol-alert-design.md.</p>
 */
public final class BotPillagerAlertService {

    private static final Logger LOGGER = LoggerFactory.getLogger("pillager-alert");

    // ── Tunable constants ───────────────────────────────────────────────
    // _TICKS = server game-ticks; _MS = System.currentTimeMillis()

    /** Minimum visible illagers for "patrol detected". */
    private static final int ILLAGER_COUNT_THRESHOLD = 2;

    /** Only count illagers every N ticks inside engageHostiles. */
    private static final int SCAN_CADENCE_TICKS = 20;

    /** How long the patrol-detected flag persists after last detection. */
    private static final long PATROL_FLAG_DECAY_TICKS = 200L;

    /** Per-bot cooldown between alerts. */
    private static final long ALERT_COOLDOWN_MS = 60_000L;

    /** Goat horn overhead message range — both above ground. */
    private static final double HORN_OVERHEAD_RANGE_ABOVE = 128.0D;

    /** Goat horn overhead message range — either underground. */
    private static final double HORN_OVERHEAD_RANGE_BELOW = 48.0D;

    /** Max distance bot will travel to a signal fire. */
    private static final int SIGNAL_FIRE_SEARCH_RADIUS = 24;

    /** Overhead message range from the campfire position. */
    private static final double SIGNAL_FIRE_OVERHEAD_RANGE = 64.0D;

    /** Direct message overhead range (magic-comm gated). */
    private static final double DIRECT_MSG_RANGE = 48.0D;

    /** Fallback overhead range (always available, must be near bot). */
    private static final double FALLBACK_RANGE = 16.0D;

    /** How close to an enchanting table counts for the comm gate. */
    private static final int ENCHANT_TABLE_PROXIMITY = 8;

    /** Bot won't chase illagers beyond this distance in defensive mode. */
    private static final double PURSUIT_SUPPRESS_LEASH_SQ = 6.0D * 6.0D;

    // ── State ───────────────────────────────────────────────────────────

    /** botUuid -> expireGameTick. The patrol-detected flag. */
    private static final Map<UUID, Long> PATROL_DETECTED = new ConcurrentHashMap<>();

    /** botUuid -> lastAlertEpochMillis. Alert cooldown tracker. */
    private static final Map<UUID, Long> LAST_ALERT_MS = new ConcurrentHashMap<>();

    /** botUuid -> lastScanGameTick. Scan cadence tracker. */
    private static final Map<UUID, Long> LAST_SCAN_TICK = new ConcurrentHashMap<>();

    private BotPillagerAlertService() {}

    // ── Public API (called from BotEventHandler.engageHostiles) ─────────

    /**
     * Called from {@code engageHostiles} after the hostile list is built.
     * Counts visible illagers, manages the patrol-detected flag, fires
     * one-shot alerts on first detection. Returns true if pursuit of
     * illager targets should be suppressed (defensive posture active AND
     * no illager has aggroed on the bot, commander, or defended animals).
     */
    public static boolean checkForPatrolAndSuppressPursuit(
            ServerPlayerEntity bot, MinecraftServer server,
            List<Entity> hostileList) {
        // Stub: filled in chunk 2.
        return false;
    }

    /**
     * Called from {@code engageHostiles} before each {@code moveToward}
     * that chases the target. Returns true if the target is an illager AND
     * the bot is in patrol-defensive mode (pursuit should be suppressed).
     */
    public static boolean shouldSuppressPursuit(
            ServerPlayerEntity bot, Entity target) {
        // Stub: filled in chunk 2.
        return false;
    }

    /** Cleanup for SERVER_STOPPING. */
    public static void reset() {
        PATROL_DETECTED.clear();
        LAST_ALERT_MS.clear();
        LAST_SCAN_TICK.clear();
        LOGGER.info("BotPillagerAlertService reset (server stopping)");
    }

    // ── Alert channel dispatch (stubs, chunk 2) ─────────────────────────

    @SuppressWarnings("unused")
    private static void fireAlert(
            ServerPlayerEntity bot, MinecraftServer server) {
        // Stub: filled in chunk 2.
    }

    @SuppressWarnings("unused")
    private static boolean tryGoatHorn(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        return false;
    }

    @SuppressWarnings("unused")
    private static boolean trySignalFire(
            ServerPlayerEntity bot, ServerWorld world) {
        return false;
    }

    @SuppressWarnings("unused")
    private static boolean tryDirectMessage(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        return false;
    }

    @SuppressWarnings("unused")
    private static void fireFallback(ServerPlayerEntity bot) {
        // Stub: filled in chunk 2.
    }

    // ── Helpers (stubs, chunk 2) ────────────────────────────────────────

    /** True if the entity is an illager or ravager. */
    @SuppressWarnings("unused")
    private static boolean isIllagerOrRavager(Entity entity) {
        return entity instanceof IllagerEntity || entity instanceof RavagerEntity;
    }

    /** True if any illager in the list has aggroed on bot/commander/defended animal. */
    @SuppressWarnings("unused")
    private static boolean anyIllagerAggroed(
            ServerPlayerEntity bot, List<Entity> illagers,
            MinecraftServer server) {
        return false;
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew compileJava 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`. If any imports fail (particularly `IllagerEntity` at `net.minecraft.entity.mob.IllagerEntity` or `RavagerEntity` at `net.minecraft.entity.mob.RavagerEntity`), grep the codebase for the correct package and fix.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotPillagerAlertService.java
git commit -m "feat: BotPillagerAlertService skeleton (no logic yet)

Service file with constants, state maps, public API stubs
(checkForPatrolAndSuppressPursuit, shouldSuppressPursuit, reset),
private alert channel stubs, and isIllagerOrRavager helper.

All stubs return safe no-op values. Logic lands in chunk 2.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

**End of chunk 1.** Two new methods on CompanionCommunicationPolicy + service skeleton. Compiles, does nothing.

---

## Chunk 2: Detection + alert channels + pursuit suppression

Fills in all stubs: illager counting with LOS check, patrol flag management, alert channel dispatch (goat horn use-item, signal fire pathfind, direct message, fallback), and the pursuit suppression query.

### Task 2.1: Implement detection + patrol flag + pursuit suppression

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotPillagerAlertService.java`

- [ ] **Step 1: Replace `checkForPatrolAndSuppressPursuit` stub**

`old_string`:
```java
    public static boolean checkForPatrolAndSuppressPursuit(
            ServerPlayerEntity bot, MinecraftServer server,
            List<Entity> hostileList) {
        // Stub: filled in chunk 2.
        return false;
    }
```

`new_string`:
```java
    public static boolean checkForPatrolAndSuppressPursuit(
            ServerPlayerEntity bot, MinecraftServer server,
            List<Entity> hostileList) {
        if (bot == null || server == null || hostileList == null || hostileList.isEmpty()) {
            return isPatrolDetected(bot, server);
        }
        long now = server.getTicks();
        UUID botId = bot.getUuid();

        // Scan cadence: only count illagers every SCAN_CADENCE_TICKS.
        Long lastScan = LAST_SCAN_TICK.get(botId);
        if (lastScan != null && now - lastScan < SCAN_CADENCE_TICKS) {
            return isPatrolDetected(bot, server);
        }
        LAST_SCAN_TICK.put(botId, now);

        // Count visible illagers in the existing hostile list.
        int visibleIllagers = 0;
        for (Entity e : hostileList) {
            if (!isIllagerOrRavager(e)) continue;
            if (!(e instanceof LivingEntity living)) continue;
            if (!bot.canSee(living)) continue;
            visibleIllagers++;
        }

        boolean wasDetected = isPatrolDetected(bot, server);

        if (visibleIllagers >= ILLAGER_COUNT_THRESHOLD) {
            // Refresh or set the patrol-detected flag.
            PATROL_DETECTED.put(botId, now + PATROL_FLAG_DECAY_TICKS);
            if (!wasDetected) {
                // First detection — fire one-shot alert if cooldown allows.
                long lastAlert = LAST_ALERT_MS.getOrDefault(botId, 0L);
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastAlert >= ALERT_COOLDOWN_MS) {
                    LAST_ALERT_MS.put(botId, nowMs);
                    fireAlert(bot, server);
                }
            }
            return !anyIllagerAggroed(bot, hostileList, server);
        }

        return isPatrolDetected(bot, server) && !anyIllagerAggroed(bot, hostileList, server);
    }

    /** True if the patrol-detected flag is set and not expired. */
    private static boolean isPatrolDetected(ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null) return false;
        Long expiry = PATROL_DETECTED.get(bot.getUuid());
        if (expiry == null) return false;
        if (server.getTicks() >= expiry) {
            PATROL_DETECTED.remove(bot.getUuid());
            return false;
        }
        return true;
    }
```

- [ ] **Step 2: Replace `shouldSuppressPursuit` stub**

`old_string`:
```java
    public static boolean shouldSuppressPursuit(
            ServerPlayerEntity bot, Entity target) {
        // Stub: filled in chunk 2.
        return false;
    }
```

`new_string`:
```java
    public static boolean shouldSuppressPursuit(
            ServerPlayerEntity bot, Entity target) {
        if (bot == null || target == null) return false;
        if (!isIllagerOrRavager(target)) return false;
        Long expiry = PATROL_DETECTED.get(bot.getUuid());
        if (expiry == null) return false;
        // Check distance: only suppress if bot is within leash range.
        // Beyond the leash, the bot would already need to chase.
        double distSq = bot.squaredDistanceTo(target);
        return distSq > PURSUIT_SUPPRESS_LEASH_SQ;
    }
```

- [ ] **Step 3: Replace `anyIllagerAggroed` stub**

`old_string`:
```java
    private static boolean anyIllagerAggroed(
            ServerPlayerEntity bot, List<Entity> illagers,
            MinecraftServer server) {
        return false;
    }
```

`new_string`:
```java
    /**
     * Returns true if any illager in the hostile list has aggroed on the bot,
     * the commander, or any Feature A defended animal. When this returns true,
     * pursuit suppression lifts and the bot fights normally.
     */
    private static boolean anyIllagerAggroed(
            ServerPlayerEntity bot, List<Entity> hostileList,
            MinecraftServer server) {
        if (bot == null || hostileList == null) return false;
        UUID commanderUuid = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        for (Entity e : hostileList) {
            if (!isIllagerOrRavager(e)) continue;
            if (!(e instanceof MobEntity mob)) continue;
            LivingEntity target = mob.getTarget();
            if (target == null) continue;
            // Aggroed on the bot itself.
            if (target == bot) return true;
            // Aggroed on the commander.
            if (commanderUuid != null && target instanceof ServerPlayerEntity sp
                    && commanderUuid.equals(sp.getUuid())) return true;
            // Aggroed on a defended animal (Feature A check).
            if (commanderUuid != null
                    && BotAnimalDefenseService.defenseBoost(bot, e) > 0.0D) return true;
        }
        return false;
    }
```

Note: the `defenseBoost > 0` check is a shortcut — if the illager is already a defended attacker in Feature A's map, it means it's targeting an owned animal and Feature A has already flagged it. This avoids re-running the full classification. If Feature A hasn't flagged it yet (the illager just acquired the animal target this tick), the next tick will catch it.

- [ ] **Step 4: Build**

Run: `./gradlew compileJava 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`.

### Task 2.2: Implement alert channel dispatch

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotPillagerAlertService.java`

- [ ] **Step 1: Replace `fireAlert` stub**

`old_string`:
```java
    @SuppressWarnings("unused")
    private static void fireAlert(
            ServerPlayerEntity bot, MinecraftServer server) {
        // Stub: filled in chunk 2.
    }
```

`new_string`:
```java
    /**
     * Dispatches the best available alert channel (first match wins):
     * goat horn > signal fire > direct message > fallback.
     */
    private static void fireAlert(
            ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null) return;
        UUID commanderUuid = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        ServerPlayerEntity commander = commanderUuid != null
                ? server.getPlayerManager().getPlayer(commanderUuid) : null;

        if (tryGoatHorn(bot, commander)) {
            LOGGER.info("pillager-alert: bot={} channel=goat-horn", bot.getName().getString());
            return;
        }
        if (bot.getEntityWorld() instanceof ServerWorld world && trySignalFire(bot, world)) {
            LOGGER.info("pillager-alert: bot={} channel=signal-fire", bot.getName().getString());
            return;
        }
        if (commander != null && tryDirectMessage(bot, commander)) {
            LOGGER.info("pillager-alert: bot={} channel=direct-message", bot.getName().getString());
            return;
        }
        fireFallback(bot);
        LOGGER.info("pillager-alert: bot={} channel=fallback", bot.getName().getString());
    }
```

- [ ] **Step 2: Replace `tryGoatHorn` stub**

`old_string`:
```java
    @SuppressWarnings("unused")
    private static boolean tryGoatHorn(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        return false;
    }
```

`new_string`:
```java
    /**
     * Priority 1: Goat horn. Bot equips, uses (plays vanilla instrument
     * sound ~256 blocks), re-equips original item. Overhead message range
     * depends on above/below ground.
     */
    private static boolean tryGoatHorn(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null) return false;
        // Find a goat horn in the bot's inventory.
        int hornSlot = -1;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(Items.GOAT_HORN)) {
                hornSlot = i;
                break;
            }
        }
        if (hornSlot < 0) return false;

        // Determine overhead range based on above/below ground.
        boolean botAbove = bot.getEntityWorld().isSkyVisible(bot.getBlockPos().up());
        boolean commanderAbove = commander != null
                && commander.getEntityWorld().isSkyVisible(commander.getBlockPos().up());
        double overheadRange = (botAbove && commanderAbove)
                ? HORN_OVERHEAD_RANGE_ABOVE
                : HORN_OVERHEAD_RANGE_BELOW;

        // Equip the horn, use it, re-equip original. If the horn is in the
        // main inventory (slot 9+), swap it to hotbar slot 0 temporarily and
        // reverse the swap after use so the bot's inventory layout is preserved.
        int originalSlot = bot.getInventory().getSelectedSlot();
        boolean swappedFromMain = false;
        if (hornSlot < 9) {
            // Horn is in hotbar — select it directly.
            bot.getInventory().setSelectedSlot(hornSlot);
        } else {
            // Horn is in main inventory — swap to hotbar slot 0 temporarily.
            ItemStack hornStack = bot.getInventory().getStack(hornSlot);
            ItemStack displaced = bot.getInventory().getStack(0);
            bot.getInventory().setStack(0, hornStack);
            bot.getInventory().setStack(hornSlot, displaced);
            bot.getInventory().setSelectedSlot(0);
            swappedFromMain = true;
        }
        // Use the horn (plays the vanilla instrument sound event).
        ItemStack held = bot.getMainHandStack();
        if (held != null && !held.isEmpty()) {
            held.use(bot.getEntityWorld(), bot, Hand.MAIN_HAND);
        }
        // Reverse the swap if we moved the horn from main inventory.
        if (swappedFromMain) {
            ItemStack hornInZero = bot.getInventory().getStack(0);
            ItemStack displacedInOrig = bot.getInventory().getStack(hornSlot);
            bot.getInventory().setStack(hornSlot, hornInZero);
            bot.getInventory().setStack(0, displacedInOrig);
        }
        // Re-equip original slot.
        bot.getInventory().setSelectedSlot(originalSlot);

        CompanionOverheadDialogueService.showOverheadLine(
                bot, "Patrol spotted \u2014 sounding the horn!",
                3_500, overheadRange, "pillager-alert", "goat-horn");
        return true;
    }
```

- [ ] **Step 3: Replace `trySignalFire` stub**

`old_string`:
```java
    @SuppressWarnings("unused")
    private static boolean trySignalFire(
            ServerPlayerEntity bot, ServerWorld world) {
        return false;
    }
```

`new_string`:
```java
    /**
     * Priority 2: Signal fire (lit campfire + hay bale below, within 24
     * blocks). Suppressed in FOLLOW mode. Bot walks to the campfire
     * non-blockingly via MovementService, then emits overhead line.
     */
    private static boolean trySignalFire(
            ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) return false;
        // Mode gate: FOLLOW mode = skip (don't leave the commander).
        if (BotEventHandler.isFollowingPlayer(bot)) return false;

        // Search for the nearest signal fire within radius.
        BlockPos botPos = bot.getBlockPos();
        BlockPos bestFire = null;
        double bestDistSq = Double.MAX_VALUE;
        int r = SIGNAL_FIRE_SEARCH_RADIUS;
        for (BlockPos pos : BlockPos.iterate(botPos.add(-r, -3, -r), botPos.add(r, 3, r))) {
            if (!world.isChunkLoaded(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof CampfireBlock)) continue;
            if (!state.get(CampfireBlock.LIT)) continue;
            // Hay bale directly below = signal fire.
            BlockPos below = pos.down();
            if (!world.getBlockState(below).isOf(Blocks.HAY_BLOCK)) continue;
            double dSq = botPos.getSquaredDistance(pos);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestFire = pos.toImmutable();
            }
        }
        if (bestFire == null) return false;

        // Non-blocking walk to the campfire. MovementService.nudgeTowardUntilClose
        // is async and returns immediately — the bot will walk over the next few
        // ticks. The overhead line fires immediately since the alert is urgent;
        // the bot arriving at the campfire provides the visual (smoke column).
        MovementService.nudgeTowardUntilClose(
                bot, bestFire, 4.0D, 5_000L, 0.22D, "patrol-signal-fire");

        CompanionOverheadDialogueService.showOverheadLine(
                bot, "Patrol nearby \u2014 signaling from the fire!",
                3_500, SIGNAL_FIRE_OVERHEAD_RANGE, "pillager-alert", "signal-fire");
        return true;
    }
```

If `MovementService.nudgeTowardUntilClose` doesn't exist or has a different signature, grep for it:

```bash
grep -n "nudgeTowardUntilClose" src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java | head -5
```

The expected signature is `(ServerPlayerEntity bot, BlockPos target, double closeEnoughSq, long timeoutMs, double impulse, String reason)`. Adjust parameters if needed.

- [ ] **Step 4: Replace `tryDirectMessage` stub**

`old_string`:
```java
    @SuppressWarnings("unused")
    private static boolean tryDirectMessage(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        return false;
    }
```

`new_string`:
```java
    /**
     * Priority 3: Direct message via magic-comm gate. Uses the
     * canLongRangeComm helper on CompanionCommunicationPolicy.
     */
    private static boolean tryDirectMessage(
            ServerPlayerEntity bot, ServerPlayerEntity commander) {
        if (bot == null || commander == null) return false;
        if (!CompanionCommunicationPolicy.canLongRangeComm(bot, commander)) return false;
        CompanionOverheadDialogueService.showOverheadLine(
                bot, "I see a patrol \u2014 stay alert.",
                3_000, DIRECT_MSG_RANGE, "pillager-alert", "direct-message");
        return true;
    }
```

- [ ] **Step 5: Replace `fireFallback` stub**

`old_string`:
```java
    @SuppressWarnings("unused")
    private static void fireFallback(ServerPlayerEntity bot) {
        // Stub: filled in chunk 2.
    }
```

`new_string`:
```java
    /**
     * Priority 4: Fallback — always available, short range (16 blocks).
     * The "you should have brought a comm item" scenario.
     */
    private static void fireFallback(ServerPlayerEntity bot) {
        if (bot == null) return;
        CompanionOverheadDialogueService.showOverheadLine(
                bot, "Something's not right...",
                2_800, FALLBACK_RANGE, "pillager-alert", "fallback");
    }
```

- [ ] **Step 6: Remove remaining `@SuppressWarnings("unused")` annotations**

The `isIllagerOrRavager` helper is now called. Remove its `@SuppressWarnings` annotation. Re-read the file to verify no other stale annotations remain.

- [ ] **Step 7: Build**

Run: `./gradlew compileJava 2>&1 | tail -25`

Expected: `BUILD SUCCESSFUL`. Potential friction points:

- `CampfireBlock.LIT` might need `import net.minecraft.state.property.Properties;` and `state.get(Properties.LIT)` instead of `state.get(CampfireBlock.LIT)`. Check which form the existing codebase uses:
  ```bash
  grep -n "CampfireBlock.LIT\|Properties.LIT\|get(CampfireBlock" src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java | head -3
  ```
  Mirror whatever compiles there.

- `MovementService.nudgeTowardUntilClose` parameter types — verify via grep. If the method takes `BlockPos` vs `Vec3d`, adjust accordingly.

- `stack.isOf(Items.GOAT_HORN)` — verify `isOf(Item)` exists on `ItemStack`. Alternative: `stack.getItem() == Items.GOAT_HORN`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotPillagerAlertService.java
git commit -m "feat: BotPillagerAlertService detection + alert channels

Implements patrol detection (2+ visible illagers via LOS-gated canSee
check, 20-tick cadence), patrol-detected flag with 10s decay, one-shot
alert dispatch (goat horn > signal fire > direct message > fallback),
and pursuit suppression query.

Goat horn: equip-use-re-equip pattern with above/below ground overhead
range gating (128/48 blocks). Signal fire: nearest lit campfire + hay
bale within 24 blocks, mode-gated (FOLLOW suppressed), non-blocking
walk via MovementService. Direct message: canLongRangeComm gate.
Fallback: 16-block overhead line, always available.

Service is functionally complete but not yet hooked into BotEventHandler
or Frens.java — chunk 3.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

**End of chunk 2.** Service fully implemented, compiles, not wired in.

---

## Chunk 3: Integration + docs

### Task 3.1: Hook into BotEventHandler.engageHostiles

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java`

**⚠️ Re-read the file fresh.** The Feature A implementation just added hooks at the top of `engageHostiles`. Our hooks go right after.

- [ ] **Step 1: Add the patrol check call after the augmentHostiles + iron golem block**

Grep to find the current top of `engageHostiles`:

```bash
grep -n "augmentHostilesWithDefenseTargets\|golemAggroFlee\|hostileEntities.isEmpty" src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java | head -10
```

Find the LAST line of the iron-golem filter block (the `if (hostileEntities.isEmpty()) {` check that comes after the golem filtering). Read ~10 lines around it to get the exact context. Then insert the patrol check right after. Use Edit tool:

The insertion point is just before the existing `boolean botArmed = ...` line that comes after the empty-list check post-golem-filter. Find the exact line by reading, then:

`old_string` (the first non-empty-check line after the iron-golem block):
```java
        boolean botArmed = BotActions.hasMeleeWeapon(bot) || BotActions.hasRangedWeapon(bot);
```

`new_string`:
```java
        // Pillager patrol alert: count visible illagers, manage patrol-detected
        // flag, fire one-shot alerts. Returns true if pursuit of illager targets
        // should be suppressed (bot is in defensive posture, no illager has aggroed).
        boolean patrolSuppressPursuit = net.wcfcarolina13.GameAI.services.BotPillagerAlertService
                .checkForPatrolAndSuppressPursuit(bot, server, hostileEntities);

        boolean botArmed = BotActions.hasMeleeWeapon(bot) || BotActions.hasRangedWeapon(bot);
```

- [ ] **Step 2: Wrap the THREE `moveToward` chase calls with pursuit suppression**

There are three sites where the bot chases the closest target. All three need the same guard. Find each by line number (re-grep to confirm):

**Site 1 (~line 3918): primary approach**

`old_string`:
```java
        if (distance > preferredEngageDistance) {
            lowerShieldTracking(bot);
            moveToward(bot, positionOf(closest), preferredStopDistance, true);
```

`new_string`:
```java
        if (distance > preferredEngageDistance) {
            if (patrolSuppressPursuit && net.wcfcarolina13.GameAI.services.BotPillagerAlertService
                    .shouldSuppressPursuit(bot, closest)) {
                BotActions.raiseShieldFacing(bot, closest);
            } else {
                lowerShieldTracking(bot);
                moveToward(bot, positionOf(closest), preferredStopDistance, true);
            }
```

**Site 2 (~line 3997): spear charge**

`old_string`:
```java
                if (BotActions.shouldPressSpearCharge(bot, closest)) {
                    BotActions.sprint(bot, true);
                    moveToward(bot, positionOf(closest), BotActions.getPreferredMeleeStopDistance(bot.getMainHandStack()), true);
                }
```

`new_string`:
```java
                if (BotActions.shouldPressSpearCharge(bot, closest)) {
                    if (patrolSuppressPursuit && net.wcfcarolina13.GameAI.services.BotPillagerAlertService
                            .shouldSuppressPursuit(bot, closest)) {
                        BotActions.raiseShieldFacing(bot, closest);
                    } else {
                        BotActions.sprint(bot, true);
                        moveToward(bot, positionOf(closest), BotActions.getPreferredMeleeStopDistance(bot.getMainHandStack()), true);
                    }
                }
```

**Site 3 (~line 4009): kiting step-forward**

Read the exact context around line 4009. The kiting re-engage moveToward:

`old_string`:
```java
                        moveToward(bot, positionOf(closest), 1.5D, false);
```

This line might not be unique — verify context. Read 5 lines before and after. If it's inside a kiting block, wrap it:

`new_string`:
```java
                        if (patrolSuppressPursuit && net.wcfcarolina13.GameAI.services.BotPillagerAlertService
                                .shouldSuppressPursuit(bot, closest)) {
                            BotActions.raiseShieldFacing(bot, closest);
                        } else {
                            moveToward(bot, positionOf(closest), 1.5D, false);
                        }
```

**⚠️ IMPORTANT:** site 3 might be deeply nested. Read the surrounding code carefully to ensure the replacement doesn't break the brace structure. If unsure, read 20 lines of context and include more surrounding lines in the old_string to guarantee uniqueness.

- [ ] **Step 3: Build**

Run: `./gradlew compileJava 2>&1 | tail -25`

Expected: `BUILD SUCCESSFUL`. If any `old_string` doesn't match (user's in-progress edits altered the lines), STOP and surface the conflict.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java
git commit -m "feat: hook BotPillagerAlertService into engageHostiles

Patrol check call after the augmentHostiles + iron golem block.
Pursuit suppression wraps all three moveToward chase calls (primary
approach, spear charge, kiting step-forward) so the bot raises
shield instead of chasing illager targets while in defensive posture.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

### Task 3.2: Register reset in Frens.java

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/Frens.java`

- [ ] **Step 1: Add reset call in SERVER_STOPPING block**

Grep to find the current last reset call:

```bash
grep -n "BotAnimalDefenseService.reset\|\.reset()" src/main/java/net/wcfcarolina13/Frens.java | tail -5
```

Insert right after `BotAnimalDefenseService.reset();` (line ~768):

`old_string`:
```java
            net.wcfcarolina13.GameAI.services.BotAnimalDefenseService.reset();
```

`new_string`:
```java
            net.wcfcarolina13.GameAI.services.BotAnimalDefenseService.reset();
            net.wcfcarolina13.GameAI.services.BotPillagerAlertService.reset();
```

- [ ] **Step 2: Full build**

Run: `./gradlew build -x test 2>&1 | tail -25`

Expected: `BUILD SUCCESSFUL`. Full build (not just compile) to verify JAR packaging works.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Frens.java
git commit -m "feat: register BotPillagerAlertService cleanup in SERVER_STOPPING

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

### Task 3.3: Changelog entry

**Files:**

- Modify: `changelog.md`

- [ ] **Step 1: Read top of changelog, add new entry**

Read the first 10 lines. Insert a new section above the existing top dated section:

```markdown
## 2026-04-11 — Pillager patrol alert system (Feature B)

- **New:** `BotPillagerAlertService` detects illager groups (2+ visible within 16 blocks via LOS-gated `canSee` check) and goes on the defensive — shield up facing the patrol, pursuit suppressed until any illager aggros on the bot, commander, or a Feature A defended animal. One-shot alert fires through the best available channel: goat horn (plays vanilla instrument sound, 128/48 block overhead range above/below ground) > signal fire (lit campfire + hay bale within 24 blocks, bot walks to it, FOLLOW mode suppressed) > direct message (magic-comm gated: eye of ender, wizard's tome, both-have-ender-pearl, or near enchanting table, 48 block range) > fallback (always available, 16 block range).
- **Alert-then-escalate:** bot starts defensive on first sighting. If an illager aggros (targets bot, commander, or owned animal), pursuit suppression lifts and normal combat + Feature A defense takes over seamlessly. If the patrol passes without engaging, the bot relaxes after ~10 seconds.
- **Integration:** two hooks in `BotEventHandler.engageHostiles` (patrol check + three moveToward pursuit-suppression guards), one hook in `Frens.java` (SERVER_STOPPING cleanup). New `canLongRangeComm` helper on `CompanionCommunicationPolicy` with four gates for magic-comm items/proximity.
```

- [ ] **Step 2: Commit**

```bash
git add changelog.md
git commit -m "docs: changelog for pillager patrol alert system (Feature B)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```

### Task 3.4: Push

- [ ] **Step 1: Push all commits**

```bash
git push
```

- [ ] **Step 2: Output summary**

> Pillager patrol alert system (Feature B) is implemented and pushed. Build passes; JAR not deployed. When you're ready to deploy and test, the manual verification checklist is in the spec at `docs/superpowers/specs/2026-04-11-pillager-patrol-alert-design.md` under "Manual Verification Checklist" — 17 items.

**End of chunk 3.** Feature B is fully active. Build passes. JAR not deployed.

---

## Post-execution notes

- **Do NOT deploy the JAR** — user is playing Minecraft.
- **Do NOT use `git add -A`** — stage files explicitly.
- If the `moveToward` wrapping in task 3.1 step 2 doesn't match due to code drift from Feature A's iron-golem block, re-read the surrounding context. The three sites are: primary approach (~line 3918), spear charge (~line 3997), kiting step-forward (~line 4009). All three need the same pattern: `if (patrolSuppressPursuit && shouldSuppressPursuit(bot, closest)) { raiseShieldFacing } else { original moveToward }`.
- The goat horn equip-use-re-equip in task 2.2 step 2 might need adjustment if `setSelectedSlot` doesn't work for non-hotbar items. The plan handles this with the hotbar-swap fallback (swap to slot 0 temporarily). If `setSelectedSlot` only accepts 0-8, the swap path handles inventory slots 9+.
