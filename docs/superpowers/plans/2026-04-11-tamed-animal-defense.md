# Tamed-Animal Defense Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `BotAnimalDefenseService` so the bot prioritizes hostile mobs that target commander-owned animals (cats/wolves/horses/leashed/farm/named/named-village villagers), with overhead warnings for out-of-range and player attackers, plus iron-golem accidental-hit and direct-aggro special rules.

**Architecture:** Single new service in `GameAI.services/` with three single-line hooks into existing code (`BotEventHandler.scoreThreat`, `BotEventHandler.engageHostiles`, `Frens.java` tick + cleanup registration). Detection uses a hostile-forward scan (`mob.getTarget()` against the small hostile list) plus a small reverse-scan watch list for player attackers and accidental hits. No mixins, no new event listeners.

**Tech Stack:** Java 21, Minecraft 1.21.11, Fabric 0.18.4, Yarn mappings, existing `GameAI.services` patterns.

**Spec:** [docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md](../specs/2026-04-11-tamed-animal-defense-design.md) (revision 3, approved)

---

## ⚠️ Pre-execution constraints (read first)

This plan executes against `main` (no isolated worktree), and the working tree currently contains **unrelated in-progress work** by the user that must NOT be touched:

- `changelog.md` (modified)
- `src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java` (modified)
- `src/main/java/net/wcfcarolina13/Frens.java` (modified)
- `src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java` (modified)
- `src/main/java/net/wcfcarolina13/GameAI/services/DropSweepService.java` (modified)
- `src/main/java/net/wcfcarolina13/GameAI/services/CommanderActivityService.java` (untracked)

**Rules during execution:**

1. Before each commit, use `git status --short` to verify ONLY animal-defense files are staged. NEVER use `git add -A` or `git add .` — always stage files explicitly by name.
2. The user explicitly modified `BotEventHandler.java` and `Frens.java` already, so when this plan modifies those same files, the patches must rebase cleanly on top of the user's edits. Re-read each file fresh before each modification (do not trust cached views).
3. **No JAR deploy.** Build only. The user will deploy when ready.
4. If conflicts appear with the user's in-progress work, STOP and ask for guidance. Do not auto-resolve.

**Manual verification only.** This codebase has no test infrastructure. From `CLAUDE.md`: "No automated tests exist; CI runs `./gradlew build -x test`. Verification is manual in-game." Each task uses **build-passes** as the equivalent of "tests pass" — the spec's 15-item manual verification checklist runs after the JAR deploys.

---

## Chunk 1: Foundation (skeleton + constants + commander resolver)

Builds the service skeleton with state maps, constants, the `WarnKey` record, and stub methods that compile and do nothing. Promotes `CompanionCommunicationPolicy.resolveOwnerUuid` to public so the new service can call it. After this chunk, the codebase still behaves identically — no logic is wired in yet.

### Task 1.1: Promote `resolveOwnerUuid` to public

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java` (around line 122)

**Why:** The new service needs to resolve a bot's commander UUID. The mod already has the canonical resolver in `CompanionCommunicationPolicy.resolveOwnerUuid` but it's `private`. Promoting to `public static` is the smallest change that satisfies the spec without duplicating logic.

- [ ] **Step 1: Re-read the file and find the current method**

Run: use the Read tool with `file_path: /Users/roti/AI-Player-checkpoint/src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java` and `offset: 115, limit: 35`.

Confirm the method is currently:

```java
private static UUID resolveOwnerUuid(ServerPlayerEntity bot) {
```

If the signature has drifted (different name, different parameters, or already public), STOP and surface the discrepancy. Do not blindly patch.

- [ ] **Step 2: Change `private` to `public`**

Use the Edit tool. `old_string`:

```java
    private static UUID resolveOwnerUuid(ServerPlayerEntity bot) {
```

`new_string`:

```java
    public static UUID resolveOwnerUuid(ServerPlayerEntity bot) {
```

- [ ] **Step 3: Build to verify nothing else broke**

Run: `./gradlew compileJava 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`. The only change is visibility, so no callers need to change.

- [ ] **Step 4: Commit**

```bash
git status --short
# Verify ONLY CompanionCommunicationPolicy.java is in the modified list.
git add src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java
git commit -m "$(cat <<'EOF'
refactor: promote CompanionCommunicationPolicy.resolveOwnerUuid to public

Needed by the upcoming BotAnimalDefenseService which resolves the bot's
commander UUID for ownership-gated rules in tamed-animal defense. No
behavior change, just visibility.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 1.2: Create `BotAnimalDefenseService` skeleton

**Files:**

- Create: `src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java`

**Why:** Establishes the file with all imports, constants, state maps, the `WarnKey` record, and stub methods. The stubs return safe no-op values (false, 0.0, empty list) so the file compiles even before logic lands. Subsequent chunks will fill in the stubs.

- [ ] **Step 1: Write the file**

Use the Write tool. `file_path: /Users/roti/AI-Player-checkpoint/src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java`. Content:

```java
package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bot defends commander-owned animals from non-commander attackers.
 *
 * <p>See docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md for the
 * full design. Briefly:</p>
 *
 * <ul>
 *   <li>Per-tick (10-tick throttle) per-bot scan that uses a hostile-forward
 *       primary path ({@code mob.getTarget()} against nearby hostiles) plus a
 *       small watch-list reverse scan for player attackers and accidental hits.</li>
 *   <li>Defended categories: commander-owned tameables (cat/wolf/parrot),
 *       commander-owned horses, mobs leashed to commander, animals on the bot's
 *       preferred home base near a hay bale, name-tagged entities, and villagers
 *       inside mapped villages. Hostile mob classes are excluded from being
 *       victims (Victim Sanity Gates).</li>
 *   <li>When an attacker is in range (~16 blocks), it gets a threat-score boost
 *       via {@link #defenseBoost(ServerPlayerEntity, Entity)} which {@code
 *       BotEventHandler.scoreThreat} adds to its normal score. The attacker is
 *       also injected into the hostile list via
 *       {@link #augmentHostilesWithDefenseTargets(ServerPlayerEntity, List)} so
 *       non-{@code HostileEntity} attackers (e.g. wolves gone wild) are visible
 *       to the combat system.</li>
 *   <li>When the attacker is out of range or is a player, an overhead warning
 *       fires via {@code CompanionOverheadDialogueService.showOverheadLine},
 *       throttled per (bot,victim,attacker) tuple.</li>
 *   <li>Iron golem special rules and the alliances forward-compat hook live
 *       here as well — see {@link #isAttackerAllied(ServerPlayerEntity,
 *       ServerPlayerEntity)} and the iron-golem helper called from
 *       {@code BotEventHandler}.</li>
 * </ul>
 */
public final class BotAnimalDefenseService {

    private static final Logger LOGGER = LoggerFactory.getLogger("animal-defense");

    // ─────────────────────────────────────────────────────────────────────────
    // Tunable constants — units strictly enforced.
    //   *_TICKS / *_TICK / *_INTERVAL_TICKS = server game-ticks (server.getTicks())
    //   *_MS                                = wall-clock milliseconds (System.currentTimeMillis())
    // ─────────────────────────────────────────────────────────────────────────

    /** How often the per-tick scan runs. 10 ticks = 0.5 seconds. */
    private static final int SCAN_INTERVAL_TICKS = 10;

    /** Radius (blocks) for the hostile-forward scan. Matches existing combat range. */
    private static final double HOSTILE_SCAN_RADIUS = 16.0D;

    /** Radius (blocks) for the watch-list reverse scan. */
    private static final double WATCH_LIST_SCAN_RADIUS = 16.0D;

    /** Hard cap on watch-list size — defends against pathological setups with many named pets. */
    private static final int WATCH_LIST_HARD_CAP = 12;

    /** Within this distance, defense engages; outside, only an overhead warning fires. */
    private static final double DEFENSE_ENGAGE_RADIUS = 16.0D;

    /** Hay bale "farm marker" radius for rule 4. */
    private static final int HAY_BALE_RADIUS = 8;

    /** Threat-boost lifetime in server ticks. 100 = 5 seconds. */
    private static final long DEFEND_EXPIRE_TICKS = 100L;

    /** Per (bot,victim,attacker) overhead warn cooldown in milliseconds. 60_000 = 1 minute. */
    private static final long OVERHEAD_WARN_COOLDOWN_MS = 60_000L;

    /** Bot will not engage in defense below this fraction of max HP (flees instead). */
    private static final float SELF_PRESERVATION_HP_FRACTION = 0.30F;

    /** Additive boost added to scoreThreat for defended attackers. */
    private static final double DEFENSE_SCORE_BOOST = 50.0D;

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * botUuid -> (attackerUuid -> expireGameTick). Values are server-ticks
     * (server.getTicks() + DEFEND_EXPIRE_TICKS at insert time), NOT milliseconds.
     * Cleaned lazily on read inside {@link #defenseBoost}.
     */
    private static final Map<UUID, Map<UUID, Long>> DEFEND_TARGETS = new ConcurrentHashMap<>();

    /**
     * Throttle key for overhead warnings. Three-UUID tuple uniquely identifies
     * a (bot, victim, attacker) combination.
     */
    private record WarnKey(UUID botUuid, UUID victimUuid, UUID attackerUuid) {}

    /**
     * (botUuid, victimUuid, attackerUuid) -> lastWarnEpochMillis. Values are
     * System.currentTimeMillis(), NOT game-ticks. Used to enforce
     * OVERHEAD_WARN_COOLDOWN_MS.
     */
    private static final Map<WarnKey, Long> LAST_OVERHEAD_WARN_MS = new ConcurrentHashMap<>();

    private BotAnimalDefenseService() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Per-tick entry point. Registered in {@code Frens.java} alongside the other
     * END_SERVER_TICK services. Throttled internally to {@link #SCAN_INTERVAL_TICKS}.
     */
    public static void onServerTick(MinecraftServer server) {
        // Stub: filled in chunk 3.
    }

    /**
     * Hook for {@code BotEventHandler.scoreThreat}. Returns the additive score
     * boost for a candidate attacker against a particular bot, or {@code 0.0}
     * if the candidate is not currently a defended attacker for that bot.
     * Lazy expiry sweep happens here on read.
     */
    public static double defenseBoost(ServerPlayerEntity bot, Entity candidate) {
        // Stub: filled in chunk 3.
        return 0.0D;
    }

    /**
     * Hook for the top of {@code BotEventHandler.engageHostiles}. Returns a list
     * containing all original hostiles plus any defense-target attackers from the
     * map that aren't already in the list (dedup by UUID). Returns the same
     * reference if the defense map is empty for this bot (zero allocation common
     * case). Never mutates the input list — defends against {@code Stream.toList()}
     * immutable callers (see feedback_stream_tolist_mutation.md memory).
     */
    public static List<Entity> augmentHostilesWithDefenseTargets(
            ServerPlayerEntity bot, List<Entity> hostileList) {
        // Stub: filled in chunk 3.
        return hostileList;
    }

    /**
     * Forward-compat hook for the future "alliances" system. v1 always returns
     * {@code false} — player attackers are never treated as allied, so
     * {@link #maybeWarnPlayerAttacker} always fires the overhead warning when a
     * non-commander player hits an owned animal.
     *
     * <p>When alliances lands, this method will gate behavior per-player.</p>
     */
    public static boolean isAttackerAllied(
            ServerPlayerEntity bot, ServerPlayerEntity attacker) {
        return false;
    }

    /**
     * Cleanup hook called from the {@code Frens.SERVER_STOPPING} handler.
     * Mirrors the pattern used by the 8 other services documented in CLAUDE.md.
     */
    public static void reset() {
        DEFEND_TARGETS.clear();
        LAST_OVERHEAD_WARN_MS.clear();
        LOGGER.info("BotAnimalDefenseService reset (server stopping)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal — stubs to be filled in chunks 2-3
    // ─────────────────────────────────────────────────────────────────────────

    /** True if the candidate victim passes all sanity gates (chunk 2). */
    @SuppressWarnings("unused")
    private static boolean passesVictimSanityGates(Entity victim, ServerPlayerEntity bot) {
        return false;
    }

    /** True if the victim is a defended entity for this bot (chunk 2, rules 1-6). */
    @SuppressWarnings("unused")
    private static boolean isDefendedEntity(
            Entity target, UUID commanderUuid, ServerPlayerEntity bot) {
        return false;
    }

    /** Farm-machinery exclusion: attacker is in a vehicle (boat, minecart, mounted) (chunk 2). */
    @SuppressWarnings("unused")
    private static boolean isExcludedByFarmHeuristic(Entity attacker) {
        return false;
    }

    /** Tamed-vs-tamed skip: attacker is itself a defended entity (chunk 2). */
    @SuppressWarnings("unused")
    private static boolean isTamedVsTamedCase(
            Entity attacker, Entity victim, UUID commanderUuid, ServerPlayerEntity bot) {
        return false;
    }

    /** True if the victim was hit recently (vanilla hurtTime field, chunk 2). */
    @SuppressWarnings("unused")
    private static boolean recentlyAttacked(LivingEntity victim) {
        return false;
    }

    /** Resolves the bot's commander UUID via CompanionCommunicationPolicy. */
    @SuppressWarnings("unused")
    private static UUID resolveCommanderUuid(ServerPlayerEntity bot) {
        return CompanionCommunicationPolicy.resolveOwnerUuid(bot);
    }

    /** Resolves the live commander entity in the bot's world, or null if offline/cross-dim. */
    @SuppressWarnings("unused")
    private static ServerPlayerEntity resolveCommanderEntity(
            MinecraftServer server, UUID commanderUuid, ServerWorld botWorld) {
        if (server == null || commanderUuid == null || botWorld == null) {
            return null;
        }
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(commanderUuid);
        if (p == null || p.isRemoved() || !p.isAlive()) {
            return null;
        }
        if (p.getEntityWorld() != botWorld) {
            return null;
        }
        return p;
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew compileJava 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`. There should be no warnings about unused imports because the file uses everything it imports (the `@SuppressWarnings("unused")` covers the stub methods).

If you get an "unused import" warning for `ArrayList`, ignore it for now — chunk 3 will use it. Or temporarily comment out the import and re-add it in chunk 3.

- [ ] **Step 3: Commit**

```bash
git status --short
# Verify ONLY BotAnimalDefenseService.java is in the untracked list.
git add src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java
git commit -m "$(cat <<'EOF'
feat: BotAnimalDefenseService skeleton (no logic yet)

Empty service file with constants, state maps, WarnKey record, public API
stubs (onServerTick, defenseBoost, augmentHostilesWithDefenseTargets,
isAttackerAllied, reset), and private classification stubs.

All stubs return safe no-op values (false, 0.0, unchanged list) so
behavior is unchanged. Logic lands in chunks 2-3.

Spec: docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

**End of chunk 1.** Service file exists, compiles, does nothing yet. The codebase behaves identically to before this chunk.

---

## Chunk 2: Defended-category gates (rules 1–6 + sanity gates + helpers)

Implements the six defended-category rules from the spec, the Victim Sanity Gates that run before any rule, the farm-machinery heuristic, the tamed-vs-tamed skip, and the `recentlyAttacked` helper. After this chunk the service can correctly answer "is this entity a defended victim of this attacker for this bot?", but it isn't called from anywhere yet — the per-tick scan and integration hooks land in chunks 3–4.

Each rule is implemented as a small static helper inside `BotAnimalDefenseService`, so the public `isDefendedEntity` is just a chain of `if (rule1(...)) return true; if (rule2(...)) return true; ...`.

### Task 2.1: Add imports needed for chunk 2

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java`

- [ ] **Step 1: Re-read the file's import block to confirm current state**

Use Read tool with `offset: 1, limit: 25`.

- [ ] **Step 2: Add the new imports**

Use the Edit tool. `old_string`:

```java
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
```

`new_string`:

```java
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MagmaCubeEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.BotHomeService.BaseEntry;
```

- [ ] **Step 3: Build to verify all imports resolve**

Run: `./gradlew compileJava 2>&1 | tail -25`

Expected: `BUILD SUCCESSFUL`. If any import fails to resolve (most likely candidates: `WitherEntity` package, `MagmaCubeEntity` package), STOP and grep the codebase to find the correct package — DO NOT guess. Common failure: `MagmaCubeEntity` is at `net.minecraft.entity.mob.MagmaCubeEntity` and `SlimeEntity` is at `net.minecraft.entity.mob.SlimeEntity`. `WitherEntity` is at `net.minecraft.entity.boss.WitherEntity`. `EnderDragonEntity` is at `net.minecraft.entity.boss.dragon.EnderDragonEntity`.

If `BotHomeService.BaseEntry` fails to resolve (because `BaseEntry` might be a top-level class instead of a nested record), drop the import and use the fully-qualified name `BotHomeService.BaseEntry` in the code instead.

- [ ] **Step 4: Do not commit yet** — task 2.2 fills in code that uses these imports. Commit at the end of task 2.2 so we don't have an "unused imports" warning in the middle.

### Task 2.2: Implement Victim Sanity Gates + the six defended-category rules

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java`

**Why:** These are the deterministic classification helpers — given a candidate victim, they return true/false based on the spec's rules. They need no per-tick state; they're pure functions plus a couple of world lookups.

- [ ] **Step 1: Replace the `passesVictimSanityGates` stub**

Use the Edit tool. `old_string`:

```java
    /** True if the candidate victim passes all sanity gates (chunk 2). */
    @SuppressWarnings("unused")
    private static boolean passesVictimSanityGates(Entity victim, ServerPlayerEntity bot) {
        return false;
    }
```

`new_string`:

```java
    /**
     * Hard exclusions that run before any defended-category rule. A candidate
     * victim must pass all of these or it is not a defended entity, regardless
     * of name tag, ownership, or village membership. Closes the "named
     * hostile" loophole in rule 5.
     */
    private static boolean passesVictimSanityGates(Entity victim, ServerPlayerEntity bot) {
        if (victim == null || victim.isRemoved() || !victim.isAlive()) return false;
        if (bot == null || bot.getEntityWorld() != victim.getEntityWorld()) return false;
        if (victim instanceof HostileEntity) return false;
        if (victim instanceof RaiderEntity) return false;
        if (victim instanceof SlimeEntity) return false;
        if (victim instanceof MagmaCubeEntity) return false;
        if (victim instanceof EnderDragonEntity) return false;
        if (victim instanceof WitherEntity) return false;
        return true;
    }
```

- [ ] **Step 2: Replace the `isDefendedEntity` stub with the six-rule chain**

`old_string`:

```java
    /** True if the victim is a defended entity for this bot (chunk 2, rules 1-6). */
    @SuppressWarnings("unused")
    private static boolean isDefendedEntity(
            Entity target, UUID commanderUuid, ServerPlayerEntity bot) {
        return false;
    }
```

`new_string`:

```java
    /**
     * Returns true if {@code target} is a defended victim for {@code bot}.
     * Runs the six defended-category rules from the spec in priority order;
     * the first rule that matches wins. {@link #passesVictimSanityGates}
     * must be true or no rule fires (closes the named-hostile loophole).
     */
    private static boolean isDefendedEntity(
            Entity target, UUID commanderUuid, ServerPlayerEntity bot) {
        if (!passesVictimSanityGates(target, bot)) return false;
        if (matchesRule1Tameable(target, commanderUuid, bot)) return true;
        if (matchesRule2Horse(target, commanderUuid)) return true;
        if (matchesRule3Leashed(target, commanderUuid, bot)) return true;
        if (matchesRule4PreferredHomeBaseFarm(target, bot)) return true;
        if (matchesRule5NameTag(target)) return true;
        if (matchesRule6NamedVillageVillager(target, bot)) return true;
        return false;
    }

    /**
     * Rule 1 — commander-owned tameable (cat, wolf, parrot).
     * Tameable.isTamed() distinct from horse.isTame() (no 'd').
     */
    private static boolean matchesRule1Tameable(
            Entity target, UUID commanderUuid, ServerPlayerEntity bot) {
        if (commanderUuid == null) return false;
        if (!(target instanceof TameableEntity tameable)) return false;
        if (!tameable.isTamed()) return false;
        // Prefer the entity-aware check when commander is online in the same world.
        ServerPlayerEntity commander = resolveCommanderEntity(
                bot.getCommandSource().getServer(),
                commanderUuid,
                (ServerWorld) bot.getEntityWorld());
        if (commander != null) {
            return tameable.isOwner(commander);
        }
        // Offline or cross-dimension: compare UUIDs via getOwnerReference.
        if (tameable.getOwnerReference() == null) return false;
        UUID ownerUuid = tameable.getOwnerReference().getUuid();
        return commanderUuid.equals(ownerUuid);
    }

    /**
     * Rule 2 — commander-owned horse family (horse, donkey, mule, llama, camel,
     * skeleton/zombie horse). AbstractHorseEntity uses {@code isTame()} (no 'd'),
     * distinct from TameableEntity.isTamed().
     */
    private static boolean matchesRule2Horse(Entity target, UUID commanderUuid) {
        if (commanderUuid == null) return false;
        if (!(target instanceof AbstractHorseEntity horse)) return false;
        if (!horse.isTame()) return false;
        if (horse.getOwnerReference() == null) return false;
        UUID ownerUuid = horse.getOwnerReference().getUuid();
        return commanderUuid.equals(ownerUuid);
    }

    /**
     * Rule 3 — leashed to commander. Requires the commander to be a live
     * LivingEntity in the same world. Leashed-to-fence-post or leashed-to-
     * another-player does not count.
     */
    private static boolean matchesRule3Leashed(
            Entity target, UUID commanderUuid, ServerPlayerEntity bot) {
        if (commanderUuid == null) return false;
        if (!(target instanceof MobEntity mob)) return false;
        if (!mob.isLeashed()) return false;
        ServerPlayerEntity commander = resolveCommanderEntity(
                bot.getCommandSource().getServer(),
                commanderUuid,
                (ServerWorld) bot.getEntityWorld());
        if (commander == null) return false;
        return mob.getLeashHolder() == commander;
    }

    /**
     * Rule 4 — base-proximity farm animal. The bot must have a preferred home
     * base set (commander-scoped implicitly via WorldData.preferredHomeBaseByBot),
     * and both the victim and a hay bale must be inside that base's radius.
     * The hay bale must be within HAY_BALE_RADIUS of the victim.
     */
    private static boolean matchesRule4PreferredHomeBaseFarm(
            Entity target, ServerPlayerEntity bot) {
        if (!(target instanceof AnimalEntity)) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        java.util.Optional<BlockPos> preferredBaseOpt =
                BotHomeService.resolvePreferredHomeBase(bot);
        if (preferredBaseOpt.isEmpty()) return false;
        BlockPos basePos = preferredBaseOpt.get();
        java.util.Optional<BaseEntry> baseEntryOpt =
                BotHomeService.findBaseNearPosition(world.getServer(), world, basePos);
        if (baseEntryOpt.isEmpty()) return false;
        BaseEntry base = baseEntryOpt.get();
        int baseRadius = base.radius() > 0
                ? base.radius()
                : BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS;
        BlockPos victimPos = target.getBlockPos();
        if (!base.pos().isWithinDistance(victimPos, baseRadius)) return false;
        // Find a hay bale within HAY_BALE_RADIUS of the victim that is also
        // inside the base radius. Scan a small box around the victim.
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -HAY_BALE_RADIUS; dx <= HAY_BALE_RADIUS; dx++) {
            for (int dy = -HAY_BALE_RADIUS; dy <= HAY_BALE_RADIUS; dy++) {
                for (int dz = -HAY_BALE_RADIUS; dz <= HAY_BALE_RADIUS; dz++) {
                    cursor.set(victimPos.getX() + dx, victimPos.getY() + dy, victimPos.getZ() + dz);
                    if (!world.getBlockState(cursor).isOf(Blocks.HAY_BLOCK)) continue;
                    if (!base.pos().isWithinDistance(cursor, baseRadius)) continue;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Rule 5 — named-entity override. Any name-tagged entity that already
     * passed the Victim Sanity Gates qualifies. The sanity gates excluded
     * hostile classes, so a name-tagged zombie still won't be defended.
     */
    private static boolean matchesRule5NameTag(Entity target) {
        return target.hasCustomName();
    }

    /**
     * Rule 6 — villager inside a mapped village. Both the victim and the bot
     * must be inside the same mapped village (label equality, not reference).
     */
    private static boolean matchesRule6NamedVillageVillager(Entity target, ServerPlayerEntity bot) {
        if (!(target instanceof VillagerEntity)) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        java.util.Optional<MappedVillageService.MappedVillage> botVillageOpt =
                MappedVillageService.getVillageAt(world, bot.getBlockPos());
        if (botVillageOpt.isEmpty()) return false;
        java.util.Optional<MappedVillageService.MappedVillage> victimVillageOpt =
                MappedVillageService.getVillageAt(world, target.getBlockPos());
        if (victimVillageOpt.isEmpty()) return false;
        return botVillageOpt.get().getName().equalsIgnoreCase(victimVillageOpt.get().getName());
    }
```

- [ ] **Step 3: Replace the `isExcludedByFarmHeuristic` stub**

`old_string`:

```java
    /** Farm-machinery exclusion: attacker is in a vehicle (boat, minecart, mounted) (chunk 2). */
    @SuppressWarnings("unused")
    private static boolean isExcludedByFarmHeuristic(Entity attacker) {
        return false;
    }
```

`new_string`:

```java
    /**
     * Farm-machinery heuristic: if the attacker is riding another entity (boat,
     * minecart, mounted on another mob), it's almost certainly a farm component
     * (zombie-in-boat for iron farms, minecart-trapped mobs in spawner grinders,
     * AFK mob mounts). Skip defense for these cases.
     */
    private static boolean isExcludedByFarmHeuristic(Entity attacker) {
        return attacker != null && attacker.hasVehicle();
    }
```

- [ ] **Step 4: Replace the `isTamedVsTamedCase` stub**

`old_string`:

```java
    /** Tamed-vs-tamed skip: attacker is itself a defended entity (chunk 2). */
    @SuppressWarnings("unused")
    private static boolean isTamedVsTamedCase(
            Entity attacker, Entity victim, UUID commanderUuid, ServerPlayerEntity bot) {
        return false;
    }
```

`new_string`:

```java
    /**
     * If the attacker is itself a defended entity (e.g., llama spitting at owned
     * wolf, owned wolf attacking owned sheep), skip defense. Prevents llama-spit
     * cascades from causing the bot to attack its own pets.
     */
    private static boolean isTamedVsTamedCase(
            Entity attacker, Entity victim, UUID commanderUuid, ServerPlayerEntity bot) {
        if (attacker == null) return false;
        return isDefendedEntity(attacker, commanderUuid, bot);
    }
```

- [ ] **Step 5: Replace the `recentlyAttacked` stub**

`old_string`:

```java
    /** True if the victim was hit recently (vanilla hurtTime field, chunk 2). */
    @SuppressWarnings("unused")
    private static boolean recentlyAttacked(LivingEntity victim) {
        return false;
    }
```

`new_string`:

```java
    /**
     * True if the victim took damage within the last ~10 ticks. Reads the
     * vanilla {@code LivingEntity.hurtTime} public int field (NOT a getter).
     * Used by the watch-list reverse scan to gate against stale getAttacker()
     * values that vanilla preserves for ~100 ticks after the last hit.
     */
    private static boolean recentlyAttacked(LivingEntity victim) {
        return victim != null && victim.hurtTime > 0;
    }
```

- [ ] **Step 6: Remove the now-unused `@SuppressWarnings("unused")` annotations from the helpers we filled in, but keep them on `resolveCommanderUuid`/`resolveCommanderEntity` since they're still only called by stubs**

Use the Edit tool with `replace_all: false` for each of the four annotations we still want to leave in place. Actually, simpler: re-read the file at this point and verify the stubs we kept (`resolveCommanderUuid`, `resolveCommanderEntity`) still have their `@SuppressWarnings` annotation. If chunk 3 uses them, those annotations will be removable in chunk 3. Leave them for now.

- [ ] **Step 7: Build**

Run: `./gradlew compileJava 2>&1 | tail -25`

Expected: `BUILD SUCCESSFUL`. If you get an "unused method" warning for `isExcludedByFarmHeuristic` or `isTamedVsTamedCase` or `recentlyAttacked`, that's expected — they aren't called yet. Add `@SuppressWarnings("unused")` to those methods to silence the warning until chunk 3 calls them.

If you get `cannot find symbol: BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS`, grep:

```bash
grep -n "DEFAULT_BASE_PROTECTION_RADIUS" /Users/roti/AI-Player-checkpoint/src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java
```

It must exist; if it's a different name, update the rule 4 code to match.

- [ ] **Step 8: Commit**

```bash
git status --short
git add src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java
git commit -m "$(cat <<'EOF'
feat: BotAnimalDefenseService rules 1-6 + Victim Sanity Gates

Implements all six defended-category rules from the spec, the Victim Sanity
Gates that run before any rule (excluding HostileEntity, RaiderEntity,
slimes, magma cubes, ender dragon, wither), the farm-machinery exclusion
(attacker hasVehicle), the tamed-vs-tamed skip, and recentlyAttacked
(reads LivingEntity.hurtTime as a public field).

Rule 1 (TameableEntity.isTamed + isOwner/UUID), Rule 2 (AbstractHorseEntity.
isTame, no 'd'), Rule 3 (mob.isLeashed + getLeashHolder == commander),
Rule 4 (preferred home base + hay bale within base radius + within
HAY_BALE_RADIUS of victim), Rule 5 (hasCustomName), Rule 6 (mapped village
label equality).

No callers yet — chunk 3 wires the per-tick scan, chunk 4 hooks into
BotEventHandler.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

**End of chunk 2.** Service can answer "is this entity a defended victim for this bot under this commander UUID", but nothing calls it yet. Code compiles, behavior unchanged.

---

## Chunk 3: Scan + escape orchestration (per-tick logic + integration helpers)

Implements the per-tick scan that ties everything together: Step 1 (hostile-forward), Step 2 (watch-list reverse scan), the threat-boost map maintenance (`markAttackerForDefense`, `defenseBoost`), the hostile-list augmentation (`augmentHostilesWithDefenseTargets`), the overhead warning emitter (`maybeWarnPlayerAttacker` and the out-of-range warning), and the self-preservation HP gate.

After this chunk, the service is functionally complete but still not wired into `BotEventHandler` or `Frens.java`. That happens in chunk 4.

### Task 3.1: Add imports needed for chunk 3

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java`

- [ ] **Step 1: Re-read the file's import block**

Use Read tool with `offset: 1, limit: 35`.

- [ ] **Step 2: Add imports for the per-tick scan**

`old_string`:

```java
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.BotHomeService.BaseEntry;
```

`new_string`:

```java
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotHomeService.BaseEntry;
```

`Vec3d` is required because `Entity.getPos()` does not exist in 1.21.11 yarn — `collectWatchList` builds its bounding box from `new Vec3d(bot.getX(), bot.getY(), bot.getZ())` instead of `bot.getPos()`. (Same trap that bit `RescueTeleportNetworkManager` earlier — verified by javap on `Entity.class`: only `getEntityPos()`, `getBlockPos()`, `getEyePos()`, etc. exist.)

- [ ] **Step 3: Build to verify imports resolve**

Run: `./gradlew compileJava 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL` with possibly some "unused import" warnings on `Text`, `Box`, `BotEventHandler` — those land in task 3.2.

### Task 3.2: Implement `onServerTick`, scan steps, and the boost/augment plumbing

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java`

- [ ] **Step 1: Replace the `onServerTick` stub with the throttled scan loop**

`old_string`:

```java
    public static void onServerTick(MinecraftServer server) {
        // Stub: filled in chunk 3.
    }
```

`new_string`:

```java
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (server.getTicks() % SCAN_INTERVAL_TICKS != 0) return;
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld)) continue;
            if (bot.hasVehicle()) continue; // mounted bots are passengers, not defenders
            tickOneBot(server, bot);
        }
    }

    /**
     * Per-bot tick body. Self-preservation gates first; then hostile-forward
     * scan (Step 1); then watch-list reverse scan (Step 2). Both steps may
     * mark attackers for the defense boost map.
     */
    private static void tickOneBot(MinecraftServer server, ServerPlayerEntity bot) {
        // Self-preservation: bots below the HP threshold do not engage in defense.
        // They may still emit overhead warnings (the warning is informational and
        // doesn't put the bot at additional risk), so the HP gate only suppresses
        // markAttackerForDefense, not the warning emitter.
        boolean canEngage = bot.getHealth() > bot.getMaxHealth() * SELF_PRESERVATION_HP_FRACTION;

        UUID commanderUuid = resolveCommanderUuid(bot);

        scanHostilesStep1(server, bot, commanderUuid, canEngage);
        scanWatchListStep2(server, bot, commanderUuid, canEngage);
    }

    /**
     * Step 1 — hostile-forward scan. For each nearby hostile, look at its
     * vanilla AI target. If the target is a defended entity for this bot
     * (and the attacker isn't excluded by the farm-machinery heuristic or
     * the tamed-vs-tamed skip), mark it for defense.
     */
    private static void scanHostilesStep1(
            MinecraftServer server,
            ServerPlayerEntity bot,
            UUID commanderUuid,
            boolean canEngage) {
        List<Entity> hostiles = BotThreatService.findHostilesAround(bot, HOSTILE_SCAN_RADIUS);
        if (hostiles.isEmpty()) return;
        for (Entity hostile : hostiles) {
            if (!(hostile instanceof MobEntity hostileMob)) continue;
            LivingEntity target = hostileMob.getTarget();
            if (target == null) continue;
            if (!isDefendedEntity(target, commanderUuid, bot)) continue;
            if (isExcludedByFarmHeuristic(hostile)) continue;
            if (isTamedVsTamedCase(hostile, target, commanderUuid, bot)) continue;
            // Distance gate: within DEFENSE_ENGAGE_RADIUS = engage; outside = warn.
            double distToBot = Math.sqrt(hostile.squaredDistanceTo(bot));
            if (distToBot <= DEFENSE_ENGAGE_RADIUS) {
                if (canEngage) {
                    markAttackerForDefense(server, bot, hostile);
                }
            } else {
                maybeOverheadWarn(bot, target, hostile, "out-of-range");
            }
        }
    }

    /**
     * Step 2 — watch-list reverse scan for player attackers and accidental
     * hits that Step 1 cannot catch (players don't have an AI target;
     * skeleton arrows clipping a cow won't show up as the skeleton's target).
     * Watch list is small by construction (commander's pets, leashed mobs,
     * named entities) — capped at WATCH_LIST_HARD_CAP.
     */
    private static void scanWatchListStep2(
            MinecraftServer server,
            ServerPlayerEntity bot,
            UUID commanderUuid,
            boolean canEngage) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;
        List<LivingEntity> watchList = collectWatchList(world, bot, commanderUuid);
        if (watchList.isEmpty()) return;
        ServerPlayerEntity commander = resolveCommanderEntity(server, commanderUuid, world);
        for (LivingEntity watched : watchList) {
            if (!passesVictimSanityGates(watched, bot)) continue;
            if (!recentlyAttacked(watched)) continue;
            LivingEntity attacker = watched.getAttacker();
            if (attacker == null) continue;
            if (commander != null && attacker == commander) continue; // commander butchering
            if (attacker instanceof PlayerEntity attackerPlayer
                    && attackerPlayer instanceof ServerPlayerEntity sp
                    && !isAttackerAllied(bot, sp)) {
                maybeOverheadWarn(bot, watched, attacker, "player-attacker");
                continue;
            }
            if (isExcludedByFarmHeuristic(attacker)) continue;
            if (isTamedVsTamedCase(attacker, watched, commanderUuid, bot)) continue;
            double distToBot = Math.sqrt(attacker.squaredDistanceTo(bot));
            if (distToBot <= DEFENSE_ENGAGE_RADIUS) {
                if (canEngage) {
                    markAttackerForDefense(server, bot, attacker);
                }
            } else {
                maybeOverheadWarn(bot, watched, attacker, "out-of-range");
            }
        }
    }

    /**
     * Builds the watch list from the bot's surroundings. Combines four
     * sub-categories (commander's tameables, horses, leashed mobs, name-tagged
     * entities) into a single deduplicated list capped at WATCH_LIST_HARD_CAP.
     */
    private static List<LivingEntity> collectWatchList(
            ServerWorld world, ServerPlayerEntity bot, UUID commanderUuid) {
        // Vec3d ctor instead of bot.getPos() — getPos() does not exist on Entity
        // in 1.21.11 yarn (same trap that bit RescueTeleportNetworkManager earlier).
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Box box = Box.of(botPos, WATCH_LIST_SCAN_RADIUS * 2, WATCH_LIST_SCAN_RADIUS * 2,
                WATCH_LIST_SCAN_RADIUS * 2);
        List<LivingEntity> result = new ArrayList<>();
        // We use a single broad query and filter, rather than four separate queries.
        // This is cheaper than four separate getEntitiesByClass calls.
        for (LivingEntity living : world.getEntitiesByClass(LivingEntity.class, box, e -> true)) {
            if (result.size() >= WATCH_LIST_HARD_CAP) break;
            if (living == bot) continue;
            if (living.squaredDistanceTo(bot)
                    > WATCH_LIST_SCAN_RADIUS * WATCH_LIST_SCAN_RADIUS) continue;
            // Quick category match: any of rules 1-3 or rule 5 (named entity).
            if (commanderUuid != null) {
                if (living instanceof TameableEntity tameable
                        && tameable.isTamed()
                        && tameable.getOwnerReference() != null
                        && commanderUuid.equals(tameable.getOwnerReference().getUuid())) {
                    result.add(living);
                    continue;
                }
                if (living instanceof AbstractHorseEntity horse
                        && horse.isTame()
                        && horse.getOwnerReference() != null
                        && commanderUuid.equals(horse.getOwnerReference().getUuid())) {
                    result.add(living);
                    continue;
                }
                if (living instanceof MobEntity mob && mob.isLeashed()) {
                    Entity holder = mob.getLeashHolder();
                    if (holder instanceof ServerPlayerEntity holderPlayer
                            && commanderUuid.equals(holderPlayer.getUuid())) {
                        result.add(living);
                        continue;
                    }
                }
            }
            // Rule 5: name-tagged entity (sanity gates run later in the caller).
            if (living.hasCustomName()) {
                result.add(living);
            }
        }
        return result;
    }

    /**
     * Records (bot, attacker) in the defense map with an expiry of
     * {@code now + DEFEND_EXPIRE_TICKS}. The boost lifetime extends on each
     * new mark, so a continuously-attacking mob stays prioritized.
     */
    private static void markAttackerForDefense(
            MinecraftServer server, ServerPlayerEntity bot, Entity attacker) {
        if (server == null || bot == null || attacker == null) return;
        long expireAt = server.getTicks() + DEFEND_EXPIRE_TICKS;
        DEFEND_TARGETS
                .computeIfAbsent(bot.getUuid(), k -> new ConcurrentHashMap<>())
                .put(attacker.getUuid(), expireAt);
    }

    /**
     * Throttled overhead warning emitter. Calls
     * CompanionOverheadDialogueService.showOverheadLine with a message
     * tailored to the warning kind.
     */
    private static void maybeOverheadWarn(
            ServerPlayerEntity bot,
            LivingEntity victim,
            Entity attacker,
            String reason) {
        if (bot == null || victim == null || attacker == null) return;
        WarnKey key = new WarnKey(bot.getUuid(), victim.getUuid(), attacker.getUuid());
        long now = System.currentTimeMillis();
        Long lastWarnedAt = LAST_OVERHEAD_WARN_MS.get(key);
        if (lastWarnedAt != null && now - lastWarnedAt < OVERHEAD_WARN_COOLDOWN_MS) {
            return;
        }
        LAST_OVERHEAD_WARN_MS.put(key, now);
        String victimName = victim.getName().getString();
        String message = "player-attacker".equals(reason)
                ? "Engaging threats against allies."
                : "Something's attacking your " + victimName + "!";
        CompanionOverheadDialogueService.showOverheadLine(
                bot, message, 2_800, 32.0D, "animal-defense", reason);
        LOGGER.info("animal-defense overhead-warn bot={} victim={} attacker={} reason={}",
                bot.getName().getString(), victimName,
                attacker.getName().getString(), reason);
    }
```

- [ ] **Step 2: Replace the `defenseBoost` stub**

`old_string`:

```java
    public static double defenseBoost(ServerPlayerEntity bot, Entity candidate) {
        // Stub: filled in chunk 3.
        return 0.0D;
    }
```

`new_string`:

```java
    public static double defenseBoost(ServerPlayerEntity bot, Entity candidate) {
        if (bot == null || candidate == null) return 0.0D;
        Map<UUID, Long> botMap = DEFEND_TARGETS.get(bot.getUuid());
        if (botMap == null || botMap.isEmpty()) return 0.0D;
        Long expireAt = botMap.get(candidate.getUuid());
        if (expireAt == null) return 0.0D;
        long now = bot.getCommandSource().getServer() == null
                ? -1L
                : bot.getCommandSource().getServer().getTicks();
        if (now < 0) return 0.0D;
        if (now >= expireAt) {
            // Lazy cleanup on read.
            botMap.remove(candidate.getUuid());
            if (botMap.isEmpty()) DEFEND_TARGETS.remove(bot.getUuid());
            return 0.0D;
        }
        return DEFENSE_SCORE_BOOST;
    }
```

- [ ] **Step 3: Replace the `augmentHostilesWithDefenseTargets` stub**

`old_string`:

```java
    public static List<Entity> augmentHostilesWithDefenseTargets(
            ServerPlayerEntity bot, List<Entity> hostileList) {
        // Stub: filled in chunk 3.
        return hostileList;
    }
```

`new_string`:

```java
    public static List<Entity> augmentHostilesWithDefenseTargets(
            ServerPlayerEntity bot, List<Entity> hostileList) {
        if (bot == null || hostileList == null) return hostileList;
        Map<UUID, Long> botMap = DEFEND_TARGETS.get(bot.getUuid());
        if (botMap == null || botMap.isEmpty()) return hostileList;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return hostileList;
        // Build a UUID set of entities already in the input list, so we dedup.
        java.util.Set<UUID> existing = new java.util.HashSet<>(hostileList.size());
        for (Entity e : hostileList) {
            existing.add(e.getUuid());
        }
        long now = bot.getCommandSource().getServer() == null
                ? -1L
                : bot.getCommandSource().getServer().getTicks();
        if (now < 0) return hostileList;
        // Defensive copy — never mutate the input list (some callers pass
        // Stream.toList() which is unmodifiable; see feedback_stream_tolist_mutation.md).
        List<Entity> augmented = null;
        java.util.Iterator<Map.Entry<UUID, Long>> it = botMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now >= entry.getValue()) {
                it.remove(); // lazy cleanup
                continue;
            }
            UUID attackerUuid = entry.getKey();
            if (existing.contains(attackerUuid)) continue;
            // Resolve the attacker entity in this world via O(1) UUID lookup.
            // ServerWorld.getEntityAnyDimension(UUID) is the correct 1.21.11
            // API — much cheaper than iterating world entities for one match.
            Entity attackerEntity = world.getEntityAnyDimension(attackerUuid);
            if (attackerEntity == null) continue;
            if (attackerEntity.isRemoved() || !attackerEntity.isAlive()) continue;
            // Sanity: only inject attackers that are in the bot's current world.
            // getEntityAnyDimension can return entities from other dimensions and
            // the combat system expects same-world entities.
            if (attackerEntity.getEntityWorld() != world) continue;
            if (augmented == null) {
                augmented = new ArrayList<>(hostileList);
            }
            augmented.add(attackerEntity);
            existing.add(attackerUuid);
        }
        if (botMap.isEmpty()) DEFEND_TARGETS.remove(bot.getUuid());
        return augmented != null ? augmented : hostileList;
    }
```

- [ ] **Step 4: Remove the `@SuppressWarnings("unused")` annotations from `resolveCommanderUuid` and `resolveCommanderEntity` since they're now called**

Re-read the file's helper section and locate the two annotations. Edit each to drop the annotation line. Use replace_all: false for safety.

- [ ] **Step 5: Build**

Run: `./gradlew compileJava 2>&1 | tail -25`

Expected: `BUILD SUCCESSFUL`. The code already uses `new Vec3d(bot.getX(), bot.getY(), bot.getZ())` (NOT `bot.getPos()` which doesn't exist in 1.21.11) and `world.getEntityAnyDimension(UUID)` for O(1) UUID lookup (verified to exist in 1.21.11 yarn). If `Box.of(Vec3d, double, double, double)` complains about ambiguity, double-check `BotCampfireAvoidanceService.java` for the existing idiom — they use the same signature and it compiles, so the import path is `net.minecraft.util.math.Box` and the call shape `Box.of(centerVec3d, sizeX, sizeY, sizeZ)` is correct.

- [ ] **Step 6: Commit**

```bash
git status --short
git add src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java
git commit -m "$(cat <<'EOF'
feat: BotAnimalDefenseService per-tick scan + boost plumbing

Implements onServerTick (10-tick throttle), tickOneBot, scanHostilesStep1
(hostile-forward primary scan via mob.getTarget against
BotThreatService.findHostilesAround), scanWatchListStep2 (small reverse
scan over commander's tameables/horses/leashed/named entities), the
markAttackerForDefense / defenseBoost / augmentHostilesWithDefenseTargets
plumbing with lazy expiry sweeping, and the maybeOverheadWarn emitter
that uses CompanionOverheadDialogueService.showOverheadLine and the
"Engaging threats against allies" voiced line for player attackers.

Self-preservation HP gate suppresses defense engagement below 30% HP
but still allows overhead warnings (the warning doesn't put the bot at
additional risk).

Service is functionally complete but still not wired into BotEventHandler
or Frens.java — chunk 4.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

**End of chunk 3.** Service is functionally complete and standalone. Build passes. No behavior change yet because nothing calls `onServerTick` and nothing reads the boost map.

---

## Chunk 4: BotEventHandler + Frens.java integration (the three hooks)

After this chunk, the feature is fully active in-game. Three hooks:

1. `BotEventHandler.scoreThreat` — additive defense boost line.
2. `BotEventHandler.engageHostiles` — augmentHostiles call at the top of the funnel.
3. `BotEventHandler` iron-golem accidental-hit/aggro special rules — new helper called from the appropriate damage-receive site.
4. `Frens.java` — END_SERVER_TICK registration + SERVER_STOPPING reset call.

### Task 4.1: Hook `defenseBoost` into `scoreThreat`

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java` (around line 3605, the bottom of `scoreThreat`)

**⚠️ IMPORTANT:** This file has unrelated user-in-progress modifications. Re-read it fresh before patching.

- [ ] **Step 1: Re-read `scoreThreat` to find its current bottom line**

Run: `grep -n "private static double scoreThreat" /Users/roti/AI-Player-checkpoint/src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java`

Take the line number it returns. Use the Read tool with `offset: <line - 2>, limit: 50` to view the full method body. Find the final `return ... ;` statement.

- [ ] **Step 2: Patch the return line to add the defense boost**

Use the Edit tool. The current return statement (verified earlier) is:

```java
        return typeDanger * (1.0 + proximityBonus) * stateMult + stickinessBonus;
```

Edit `old_string`:

```java
        return typeDanger * (1.0 + proximityBonus) * stateMult + stickinessBonus;
    }
```

`new_string`:

```java
        double baseScore = typeDanger * (1.0 + proximityBonus) * stateMult + stickinessBonus;
        // Tamed-animal defense boost: if this bot has marked the candidate as a
        // defended attacker (within DEFEND_EXPIRE_TICKS), add the boost so the
        // candidate jumps to the top of the combat priority queue. Returns 0
        // when not defended (zero allocation, zero state in the common case).
        // See BotAnimalDefenseService for the boost map and expiry rules.
        return baseScore + net.wcfcarolina13.GameAI.services.BotAnimalDefenseService
                .defenseBoost(bot, entity);
    }
```

- [ ] **Step 3: Build**

Run: `./gradlew compileJava 2>&1 | tail -15`

Expected: `BUILD SUCCESSFUL`. If the `old_string` doesn't match (because the user's in-progress edits altered the return line), STOP and surface the conflict to the user. Do NOT auto-resolve — their changes might be deliberate.

- [ ] **Step 4: Commit**

```bash
git status --short
# Verify ONLY BotEventHandler.java is in the modified list and the diff shows
# only the scoreThreat patch we made, not any unrelated user edits.
git diff src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java
git add src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java
git commit -m "$(cat <<'EOF'
feat: hook BotAnimalDefenseService.defenseBoost into scoreThreat

One-line additive boost at the bottom of scoreThreat. Returns 0 when the
candidate isn't a defended attacker, otherwise adds DEFENSE_SCORE_BOOST
(50.0). With normal scores topping out around 96 for close-range ignited
creepers, the +50 boost promotes a defended attacker above routine
zombies/wolves but not above point-blank creepers.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4.2: Hook `augmentHostilesWithDefenseTargets` into `engageHostiles`

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java` (around line 3605, top of `engageHostiles`)

- [ ] **Step 1: Re-read `engageHostiles` to find its current top**

Run: `grep -n "private static boolean engageHostiles" /Users/roti/AI-Player-checkpoint/src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java`

Use Read tool with `offset: <line - 1>, limit: 15`.

The current top is (verified earlier):

```java
    private static boolean engageHostiles(ServerPlayerEntity bot, MinecraftServer server, List<Entity> hostileEntities) {
        if (hostileEntities.isEmpty()) {
            COMBAT_TARGET.remove(bot.getUuid());
            return false;
        }
```

- [ ] **Step 2: Inject the augment line right after the empty-list short-circuit, AND make `hostileEntities` mutable since we may reassign it**

Use the Edit tool. `old_string`:

```java
    private static boolean engageHostiles(ServerPlayerEntity bot, MinecraftServer server, List<Entity> hostileEntities) {
        if (hostileEntities.isEmpty()) {
            COMBAT_TARGET.remove(bot.getUuid());
            return false;
        }
```

`new_string`:

```java
    private static boolean engageHostiles(ServerPlayerEntity bot, MinecraftServer server, List<Entity> hostileEntities) {
        // Tamed-animal defense: inject any defended attackers into the hostile
        // list (dedup by UUID, returns same reference if no defense targets).
        // Always safe to reassign — augmentHostilesWithDefenseTargets never
        // mutates the input list.
        hostileEntities = net.wcfcarolina13.GameAI.services.BotAnimalDefenseService
                .augmentHostilesWithDefenseTargets(bot, hostileEntities);
        if (hostileEntities.isEmpty()) {
            COMBAT_TARGET.remove(bot.getUuid());
            return false;
        }
```

- [ ] **Step 3: Build**

Run: `./gradlew compileJava 2>&1 | tail -25`

Expected: `BUILD SUCCESSFUL`. Possible compile error: `hostileEntities` is a method parameter and parameters are effectively final in some style guides — but Java allows reassignment. If you get `cannot assign a value to final variable`, the parameter is declared `final` somewhere — change the assignment to use a new local variable instead:

```java
    private static boolean engageHostiles(ServerPlayerEntity bot, MinecraftServer server, List<Entity> hostileEntities) {
        List<Entity> hostiles = net.wcfcarolina13.GameAI.services.BotAnimalDefenseService
                .augmentHostilesWithDefenseTargets(bot, hostileEntities);
        if (hostiles.isEmpty()) {
            COMBAT_TARGET.remove(bot.getUuid());
            return false;
        }
```

…and then replace all subsequent references in the method body to `hostileEntities` with `hostiles`. This is more invasive but unambiguous.

- [ ] **Step 4: Commit**

```bash
git status --short
git diff src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java
git add src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java
git commit -m "$(cat <<'EOF'
feat: hook BotAnimalDefenseService.augmentHostilesWithDefenseTargets into engageHostiles

Single hook at the top of the engageHostiles funnel ensures all upstream
hostile-list builders pick up defended attackers without per-call-site
patching. The augment helper dedupes by UUID and returns the same
reference if there are no defense targets, so cost is zero in the
common case.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4.3: Iron golem accidental-hit / direct-aggro special rules

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java` (find the appropriate damage-receive or combat-decision site)

**⚠️ This is the trickiest task in the plan.** The spec says: if an iron golem hits the bot but `golem.getTarget() != bot`, do NOT retaliate. If the golem's target IS the bot, flee.

The cleanest place to implement this is **in `engageHostiles` or `scoreThreat`**: filter iron golems out of the engage list when the accidental-hit condition holds, and add a flee branch when the direct-aggro condition holds.

- [ ] **Step 1: Decide whether the rule lives in `scoreThreat` (filter via score=0), in `engageHostiles` (filter via list removal), or as a separate damage-event handler**

Run: `grep -n "IronGolemEntity\|iron_golem" /Users/roti/AI-Player-checkpoint/src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java`

If iron golem isn't currently special-cased anywhere, the simplest approach is to filter it inside `engageHostiles` right after the augment hook. Two cases:

1. **Accidental hit:** `golem.getTarget() != bot` → drop the golem from `actionable` so the bot doesn't engage.
2. **Direct aggro:** `golem.getTarget() == bot` → set the bot to flee mode and return early.

- [ ] **Step 2: Implement the filter in `engageHostiles`**

Right after the augment hook from task 4.2, add an iron golem filter pass. Use the Edit tool. Search for the existing line:

```java
        hostileEntities = net.wcfcarolina13.GameAI.services.BotAnimalDefenseService
                .augmentHostilesWithDefenseTargets(bot, hostileEntities);
        if (hostileEntities.isEmpty()) {
```

`new_string`:

```java
        hostileEntities = net.wcfcarolina13.GameAI.services.BotAnimalDefenseService
                .augmentHostilesWithDefenseTargets(bot, hostileEntities);
        // Iron golem special rules: accidental hits get ignored entirely, and
        // direct aggro forces a flee response (golems are tanky and the bot
        // will lose). See spec section "Iron Golem Special Rules".
        boolean golemAggroFlee = false;
        if (!hostileEntities.isEmpty()) {
            java.util.List<Entity> filtered = new java.util.ArrayList<>(hostileEntities.size());
            for (Entity e : hostileEntities) {
                if (e instanceof net.minecraft.entity.passive.IronGolemEntity golem) {
                    LivingEntity golemTarget = golem.getTarget();
                    if (golemTarget == bot) {
                        golemAggroFlee = true;
                        // Don't add to the engage list — flee branch handles it below.
                        continue;
                    }
                    // Accidental hit: drop the golem from the engage list.
                    continue;
                }
                filtered.add(e);
            }
            hostileEntities = filtered;
        }
        if (golemAggroFlee) {
            // Reuse the existing creeper flee pattern from earlier in this
            // method to retreat from the golem. We need a Vec3d retreat target.
            // Find the closest iron golem in the original (pre-filter) input
            // and flee from it.
            Entity closestGolem = null;
            double bestSq = Double.MAX_VALUE;
            // We dropped the iron golems from hostileEntities; rescan via a
            // small box query so we still know where the angry golem is.
            if (bot.getEntityWorld() instanceof ServerWorld golemWorld) {
                for (Entity g : golemWorld.getEntitiesByClass(
                        net.minecraft.entity.passive.IronGolemEntity.class,
                        Box.of(new Vec3d(bot.getX(), bot.getY(), bot.getZ()), 32, 16, 32),
                        golem -> golem != null && golem.getTarget() == bot)) {
                    double sq = g.squaredDistanceTo(bot);
                    if (sq < bestSq) {
                        bestSq = sq;
                        closestGolem = g;
                    }
                }
            }
            if (closestGolem != null) {
                double dx = bot.getX() - closestGolem.getX();
                double dz = bot.getZ() - closestGolem.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 0.01) { dx = 1; dz = 0; len = 1; }
                Vec3d fleeTarget = new Vec3d(
                        bot.getX() + (dx / len) * 12,
                        bot.getY(),
                        bot.getZ() + (dz / len) * 12);
                BotActions.sprint(bot, true);
                FollowMovementService.moveToward(bot, fleeTarget, 1.0, true, null);
                COMBAT_TARGET.remove(bot.getUuid());
                return true;
            }
        }
        if (hostileEntities.isEmpty()) {
```

- [ ] **Step 3: Verify imports for `IronGolemEntity`, `Box`, `Vec3d` are present in BotEventHandler.java**

Run:

```bash
grep -n "import net.minecraft.entity.passive.IronGolemEntity\|import net.minecraft.util.math.Box\|import net.minecraft.util.math.Vec3d" /Users/roti/AI-Player-checkpoint/src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java
```

If any are missing, add them. Most likely `Box` and `Vec3d` are already there because the file has tons of combat code; `IronGolemEntity` may need adding.

- [ ] **Step 4: Build**

Run: `./gradlew compileJava 2>&1 | tail -30`

Expected: `BUILD SUCCESSFUL`. Possible issues:

- `IronGolemEntity` package may differ — try `net.minecraft.entity.passive.IronGolemEntity` first; if not, grep for `IronGolemEntity` across the codebase to find an existing usage and copy its import.
- `Box.of(...)` signature: same notes as chunk 3.
- `BotActions.sprint` and `FollowMovementService.moveToward` exist (verified in the spec); same pattern used by the creeper flee branch nearby.

- [ ] **Step 5: Commit**

```bash
git status --short
git diff src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java
git add src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java
git commit -m "$(cat <<'EOF'
feat: iron golem accidental-hit + direct-aggro special rules

Iron golems in named villages can misidentify the bot as a threat. Two
new behaviours:

1. Accidental hit (golem.getTarget != bot): drop the golem from the
   engage list so the bot doesn't retaliate against momentum/AOE clipping.

2. Direct aggro (golem.getTarget == bot): force a flee branch — sprint
   away 12 blocks horizontally from the closest aggroed golem, similar
   to the existing creeper flee pattern. Bot does not fight back; golems
   are too tanky and the bot will lose.

See spec section "Iron Golem Special Rules". Implemented as a small
filter pass right after the augmentHostilesWithDefenseTargets hook in
engageHostiles.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 4.4: Register `onServerTick` and `reset` in `Frens.java`

**Files:**

- Modify: `src/main/java/net/wcfcarolina13/Frens.java` (END_SERVER_TICK registration block + SERVER_STOPPING handler)

**⚠️ User has unrelated edits to this file. Re-read fresh before patching.**

- [ ] **Step 1: Find the existing END_SERVER_TICK block**

Run: `grep -n "ServerTickEvents.END_SERVER_TICK.register" /Users/roti/AI-Player-checkpoint/src/main/java/net/wcfcarolina13/Frens.java | head -20`

Pick a stable insertion point. The campfire avoidance line (`BotCampfireAvoidanceService::onServerTick`) is a natural neighbor — insert right after it.

- [ ] **Step 2: Insert the registration line**

Use Edit tool. `old_string`:

```java
        ServerTickEvents.END_SERVER_TICK.register(BotCampfireAvoidanceService::onServerTick);
```

`new_string`:

```java
        ServerTickEvents.END_SERVER_TICK.register(BotCampfireAvoidanceService::onServerTick);
        ServerTickEvents.END_SERVER_TICK.register(net.wcfcarolina13.GameAI.services.BotAnimalDefenseService::onServerTick);
```

If the user's edits removed that line, find another END_SERVER_TICK registration to anchor on (e.g., `BotHazardService::onServerTick`).

- [ ] **Step 3: Find the SERVER_STOPPING handler and add `BotAnimalDefenseService.reset()`**

Run: `grep -n "SERVER_STOPPING\|shutdownExecutors\|\\.reset()" /Users/roti/AI-Player-checkpoint/src/main/java/net/wcfcarolina13/Frens.java | head -20`

Find the existing block of `Service.reset()` calls (per the CLAUDE.md note that 8 services have a shutdownExecutors hook). Add `BotAnimalDefenseService.reset();` to that block.

- [ ] **Step 4: Insert the reset call**

The exact `old_string`/`new_string` depends on what the existing block looks like. Read it first, then choose a stable anchor (e.g., the line that calls `BotCampfireAvoidanceService.reset()` or whichever similar service is reset). If no obvious neighbor, find any service that calls `.reset()` in the SERVER_STOPPING handler and add right after it:

```java
            BotCampfireAvoidanceService.reset();
            net.wcfcarolina13.GameAI.services.BotAnimalDefenseService.reset();
```

If `BotCampfireAvoidanceService` doesn't have a reset, just add the line by itself in the SERVER_STOPPING block.

- [ ] **Step 5: Build**

Run: `./gradlew build -x test 2>&1 | tail -25`

Expected: `BUILD SUCCESSFUL`. This is the full build, not just compile, because we want to verify the JAR builds end-to-end before declaring chunk 4 done.

- [ ] **Step 6: Commit**

```bash
git status --short
git diff src/main/java/net/wcfcarolina13/Frens.java
git add src/main/java/net/wcfcarolina13/Frens.java
git commit -m "$(cat <<'EOF'
feat: register BotAnimalDefenseService tick + cleanup hooks

Wires the new service into Frens.java alongside the other 40+
END_SERVER_TICK services and adds the SERVER_STOPPING reset call so
DEFEND_TARGETS and LAST_OVERHEAD_WARN_MS clear on shutdown (mirroring
the pattern used by the 8 other services documented in CLAUDE.md).

After this commit, the tamed-animal defense feature is fully active.
Manual verification per the spec's 15-item checklist runs after the
JAR is deployed.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

**End of chunk 4.** Feature is fully active in-game. Build passes. JAR ready to deploy when the user is ready.

---

## Chunk 5: Docs + memory + manual verification checklist

After this chunk, the changelog, RALPH_TASK note, and project memory entry are all in place. The 15-item manual verification checklist from the spec is run AFTER the user deploys the JAR — this chunk just documents the checklist and the expected behavior, it doesn't run it.

### Task 5.1: Add changelog entry

**Files:**

- Modify: `changelog.md` (top of file, new dated section)

**⚠️ User has uncommitted changes to changelog.md. Read it fresh.**

- [ ] **Step 1: Re-read the top of the changelog**

Use Read tool with `offset: 1, limit: 10`.

- [ ] **Step 2: Add the new entry above the existing top dated section**

Use the Edit tool to insert a new section above the most recent existing dated section. The old_string should be the line immediately following the title block (the existing first dated section heading), and the new_string should prepend the new entry.

The new entry text:

```markdown
## 2026-04-11 — Tamed-animal defense (Feature A)

- **New:** `BotAnimalDefenseService` consolidates "defend the commander's owned animals from non-commander attackers". Hostile-forward primary scan via `BotThreatService.findHostilesAround` + small reverse-scan watch list for player attackers and accidental hits. Threat-score boost via one-line hook in `BotEventHandler.scoreThreat`; non-`HostileEntity` attackers (wolves gone wild, etc.) injected into the engage list via one-line hook at the top of `BotEventHandler.engageHostiles`. Per-tick (10-tick throttle) registered in `Frens.java` alongside the other tick services.
- **Defended categories:** commander-owned tameables (cat/wolf/parrot via `TameableEntity.isTamed` + UUID match), commander-owned horses (`AbstractHorseEntity.isTame`, no 'd'), mobs leashed to the commander (live entity required), animals on the bot's preferred home base near a hay bale (using `BotHomeService.resolvePreferredHomeBase` + `findBaseNearPosition`, implicitly commander-scoped via `WorldData.preferredHomeBaseByBot`), name-tagged entities (`hasCustomName`), and villagers inside mapped villages (label equality via `MappedVillageService.getVillageAt`).
- **Excluded:** `HostileEntity`, `RaiderEntity`, slimes, magma cubes, ender dragon, wither, and the named-hostile loophole (Victim Sanity Gates run before any rule). Iron golems in unmapped villages stay safe (no farm-grief). Attackers riding vehicles (boat/minecart/mounted) skipped as a farm-machinery heuristic (iron-farm scarers, spawner grinders).
- **Iron golem special rules:** accidental hits (golem.target != bot) get silently ignored — bot does not retaliate. Direct aggro (golem.target == bot) triggers a sprint-flee 12 blocks away from the closest aggroed golem; bot does not fight back (golems are too tanky).
- **PvE only in v1.** Player attackers receive an overhead warning ("Engaging threats against allies") instead of engagement, pending the future "alliances" feature. The overhead warning hook (`maybeWarnPlayerAttacker`) and the alliance gate (`isAttackerAllied`, currently always false) are wired in as forward-compat stubs.
- **Self-preservation:** bot below 30% HP suppresses defense engagement (still emits overhead warnings, which don't put it at additional risk).
- **Spec:** `docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md` (rev 3, approved). Plan: `docs/superpowers/plans/2026-04-11-tamed-animal-defense.md`.
```

- [ ] **Step 3: Verify only changelog.md is modified**

```bash
git status --short
```

Expected: `M changelog.md` plus any pre-existing user modifications. Only commit the changelog edit; do NOT touch the user's other changes.

- [ ] **Step 4: Commit ONLY the changelog file**

```bash
git add changelog.md
git diff --cached changelog.md
# Verify the diff shows ONLY the new 2026-04-11 entry, no other changes.
git commit -m "$(cat <<'EOF'
docs: changelog entry for tamed-animal defense (Feature A)

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

**⚠️ If `git diff --cached changelog.md` shows ANY change other than the new entry, STOP and run `git restore --staged changelog.md` to unstage. The user's in-progress changelog edits must not be swept up in this commit.**

### Task 5.2: Add RALPH_TASK session note

**Files:**

- Modify: `RALPH_TASK.md` (insert a new "Session Notes 2026-04-11" block after the existing top notes)

- [ ] **Step 1: Read the top of RALPH_TASK.md to find the convention**

Use Read tool with `offset: 1, limit: 30`.

- [ ] **Step 2: Insert a new session notes section at the top**

The existing convention (verified in earlier sessions) is `## Session Notes YYYY-MM-DD — <topic>` at the top of the task file, before the older notes. Use Edit tool to insert the new section.

Content:

```markdown
## Session Notes 2026-04-11 — Tamed-animal defense (Feature A)

- **New service:** `BotAnimalDefenseService` consolidates owned-animal defense with hostile-forward scan + small reverse watch list. See `docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md` (rev 3) and `docs/superpowers/plans/2026-04-11-tamed-animal-defense.md` for full design + plan.
- **Three integration hooks** in existing code: `BotEventHandler.scoreThreat` (additive defense boost), top of `BotEventHandler.engageHostiles` (augmentHostilesWithDefenseTargets call), `Frens.java` END_SERVER_TICK + SERVER_STOPPING. Iron-golem accidental-hit + direct-aggro special rules added inline in `engageHostiles`.
- **PvE only.** Player attackers get an overhead warning (existing "Engaging threats against allies" voiced line) instead of engagement. Alliances feature is the planned PvP gate, not yet built.
- **JAR built but not deployed** — user will deploy when ready. Manual verification checklist (15 items) is in the spec under "Manual Verification Checklist".
```

- [ ] **Step 3: Commit**

```bash
git status --short
git diff --cached RALPH_TASK.md
git add RALPH_TASK.md
git commit -m "$(cat <<'EOF'
docs: RALPH_TASK session note for Feature A completion

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 5.3: Create project memory entry

**Files:**

- Create: `/Users/roti/.claude/projects/-Users-roti-AI-Player-checkpoint/memory/project_animal_defense.md`
- Modify: `/Users/roti/.claude/projects/-Users-roti-AI-Player-checkpoint/memory/MEMORY.md` (add index entry)

- [ ] **Step 1: Write the new memory file**

Use Write tool. Content:

```markdown
---
name: BotAnimalDefenseService — owned-animal defense
description: Bot defends commander-owned animals (tameables, horses, leashed, base-near-hay-bale, named, mapped-village villagers) from non-commander attackers via scoreThreat boost; PvE only; iron golem aggro = flee not fight
type: project
---

`BotAnimalDefenseService` in `GameAI.services` is the per-tick scan + classification service that decides "is this hostile attacking one of the commander's animals" and boosts that hostile's threat score so the existing combat system engages it as the top priority.

**Why:** 2026-04-11 — user wanted vanilla-wolf-style "defend the owner's stuff" behaviour for the bot, scoped to commander-owned animals + named entities + villagers in named villages, without breaking iron farms / trading halls / other intentional mob-grinder farms.

**Architecture (the only thing you need to remember):**

- Per-tick scan (10-tick throttle) does TWO passes:
  1. **Hostile-forward** — `BotThreatService.findHostilesAround(bot, 16)` then `mob.getTarget()` for each. If the target is a defended entity, mark the hostile for defense. Cheap because hostile count is low (0-5 typical) regardless of farm size.
  2. **Watch-list reverse** — small bounded list of (commander's pets, horses, leashed mobs, named entities) capped at 12. For each, check `victim.hurtTime > 0` (vanilla public field, NOT a getter) and `victim.getAttacker()`. Catches player attackers (PvE warning only) and accidental arrow/AOE hits that the hostile-forward scan misses.

- **Defense map:** `Map<botUuid, Map<attackerUuid, expireGameTick>>` with 100-tick (5s) lifetime. Lazy expiry on read.

- **Three single-line hooks** in existing code:
  1. `BotEventHandler.scoreThreat` returns `baseScore + BotAnimalDefenseService.defenseBoost(bot, entity)`. Boost is 50.0 additive — large enough to promote defended attackers above routine zombies but not above point-blank ignited creepers.
  2. Top of `BotEventHandler.engageHostiles` calls `augmentHostilesWithDefenseTargets(bot, hostileEntities)` — returns a new list (never mutates input — defends against `Stream.toList()` immutable callers per `feedback_stream_tolist_mutation.md`).
  3. `Frens.java` END_SERVER_TICK registers `BotAnimalDefenseService::onServerTick` and SERVER_STOPPING calls `reset()`.

**Defended categories** (rule order matters — first match wins):

1. Commander-owned `TameableEntity` (cat/wolf/parrot): `isTamed()` + `isOwner(commander)` if online or `getOwnerReference().getUuid() == commanderUuid` if offline.
2. Commander-owned `AbstractHorseEntity`: `isTame()` (no 'd' — distinct from `TameableEntity.isTamed()`!) + UUID match via `getOwnerReference()`.
3. Leashed-to-commander: `mob.isLeashed() && mob.getLeashHolder() == commanderEntity`. Requires commander to be a live LivingEntity in the same world.
4. Preferred-home-base farm animal: `AnimalEntity` + `BotHomeService.resolvePreferredHomeBase(bot)` is set + animal is inside that base radius + a `Blocks.HAY_BLOCK` exists within 8 blocks of the animal AND inside the same base radius. **No explicit ownership check** — implicit via `WorldData.preferredHomeBaseByBot` (only commander sets it).
5. Name-tagged entity (`hasCustomName()`) — overrides class checks BUT runs after Victim Sanity Gates so a named hostile doesn't qualify.
6. Villager inside a mapped village (label equality via `MappedVillageService.getVillageAt(world, pos).get().getName()`, NOT reference equality — `load()` returns fresh instances).

**Victim Sanity Gates (run before all rules):** excludes `HostileEntity`, `RaiderEntity`, `SlimeEntity`, `MagmaCubeEntity`, `EnderDragonEntity`, `WitherEntity`, dead/removed entities, and entities in a different world from the bot. Closes the named-hostile loophole.

**Iron golem special rules:**

- `golem.getTarget() != bot` (accidental hit / AOE clip): silently drop the golem from the engage list. Bot does NOT retaliate.
- `golem.getTarget() == bot` (direct aggro): force a sprint-flee 12 blocks away from the closest aggroed golem (mirror of the existing creeper flee pattern). Bot does NOT fight back — golems are too tanky.

**PvE-only scope:** player attackers receive an overhead warning (existing "Engaging threats against allies" voiced line) instead of engagement. The `isAttackerAllied(bot, player)` predicate is wired in as a forward-compat hook for the future "alliances" system, currently always returns false.

**How to apply:** When the user reports "the bot didn't defend my X", check:
1. Was X actually a defended entity? (run through the 6 rules + sanity gates)
2. Was the attacker within 16 blocks of the bot? (out-of-range = warning only, no engagement)
3. Was the bot below 30% HP? (self-preservation suppresses defense engagement, only warnings fire)
4. Was the attacker riding a vehicle? (farm-machinery heuristic skip)
5. Was the bot mounted itself? (`bot.hasVehicle()` check at the top of tickOneBot — mounted bots skip the scan entirely)
6. Was the attacker itself a defended entity? (tamed-vs-tamed skip — llama spitting at owned wolf does NOT cause the bot to engage the llama)

**Files:**
- `src/main/java/net/wcfcarolina13/GameAI/services/BotAnimalDefenseService.java` — service file
- `src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java` — `resolveOwnerUuid` promoted to public for the service to call
- `src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java` — `scoreThreat` hook + `engageHostiles` hook + iron golem rules
- `src/main/java/net/wcfcarolina13/Frens.java` — END_SERVER_TICK registration + SERVER_STOPPING reset
- `docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md` — full spec (rev 3)
- `docs/superpowers/plans/2026-04-11-tamed-animal-defense.md` — implementation plan
```

- [ ] **Step 2: Add the index entry to MEMORY.md**

Use Read tool to view MEMORY.md, find the chronologically-correct insertion point (top of the dated entries list), then Edit to insert:

```markdown
- [BotAnimalDefenseService](project_animal_defense.md) — defends commander-owned animals via hostile-forward scan + scoreThreat boost; PvE only; iron golem aggro = flee not fight (2026-04-11)
```

The format mirrors the existing index entries.

- [ ] **Step 3: No commit needed** — memory files are local-only, not in git.

### Task 5.4: Manual verification checklist (post-deploy, user-driven)

This task is **not executed by the implementer** — it runs after the user deploys the JAR. The implementer's job here is just to confirm the spec's 15-item checklist is reachable and accurate, and to add any missing item.

- [ ] **Step 1: Re-read the spec's "Manual Verification Checklist" section**

Use Read tool with offset/limit to read just the manual verification section of `docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md`.

Confirm it has 15 items covering:
1. Owned-wolf defense, 2. Base-proximity farm defense, 3. Out-of-range overhead warning, 4. Iron farm safety, 5. Named-village villagers, 6. Iron golem accidental hit, 7. Iron golem direct aggro, 8. Llama-spit skip, 9. Player attacker overhead warning, 10. Tamed-vs-tamed via bot attack, 11. Self-preservation lockout, 12. Commander offline, 13. Commander cross-dimension, 14. Farm scan cost, 15. Bot with no commander.

- [ ] **Step 2: Output a "ready to test" summary message to the user**

Tell the user:

> Tamed-animal defense (Feature A) is implemented and committed. Build passes; JAR not deployed. When you're ready to deploy and test, the manual verification checklist is in the spec at `docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md` under "Manual Verification Checklist" — 15 items covering owned-wolf defense, farm-proximity defense, iron farm safety, iron golem aggro, llama-spit skip, player-attacker warning, self-preservation, commander offline/cross-dim, and the no-commander case.

- [ ] **Step 3: No commit needed** — verification is a runtime activity, not a code change.

**End of chunk 5.** Implementation is complete, documented, and ready for the user to deploy and verify.

---

## Post-execution notes

- **Do NOT deploy the JAR** — the user explicitly said they would deploy when ready.
- **Do NOT touch the user's unrelated in-progress files** (`DropSweeper.java`, `DropSweepService.java`, `CommanderActivityService.java`).
- **If any chunk's build fails**, surface the error to the user with the exact `compileJava` output. Do NOT auto-fix beyond what the chunk's own steps specify.
- **If any of the patches in chunks 4 or 5 conflict with the user's in-progress edits to `BotEventHandler.java` / `Frens.java` / `changelog.md`**, STOP and surface the conflict. Do NOT auto-resolve.
- **Reference skills:** none beyond `superpowers:executing-plans` or `superpowers:subagent-driven-development` for execution. The codebase has no test infrastructure so `superpowers:test-driven-development` does not apply (per the user's CLAUDE.md instruction priority).
