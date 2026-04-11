# Tamed-Animal Defense Design

**Date:** 2026-04-11
**Status:** Design approved, pending spec review
**Author:** collaborative brainstorming session

## Overview

When a hostile mob attacks an animal owned by the commander (or deemed "owned" by
proximity rules), the bot should prioritize engaging the attacker through its
existing combat system. Attacks happening farther away should fire an overhead
warning so the commander knows something is wrong, without the bot abandoning its
current task to chase.

This is **PvE-only for v1**. Other human players are explicitly out-of-scope as
attack targets — a future "alliances" system will define how allied vs hostile
players are handled. The v1 behaviour for player attackers is a best-effort
overhead warning using the existing `"Engaging threats against allies"` voiced
line.

## Motivation

Bots currently stand by while a zombie chews on the commander's pet wolf, a
skeleton shoots arrows into a leashed cow, or a creeper blows up a named horse.
Vanilla wolves auto-defend their owner; vanilla iron golems auto-defend
villagers. The bot should offer similar protection for the commander's owned
animals — with enough guardrails to avoid breaking iron farms, trading halls,
and other intentional mob-grinders.

## Scope

**In scope:**
- Defending against hostile mobs targeting owned animals within the bot's normal
  combat radius
- Overhead warnings for attacks on owned animals outside combat radius
- Overhead warnings when non-commander players attack owned animals (no
  engagement)
- Iron-golem-specific avoidance rules (don't retaliate, flee if targeted)
- Protection for villagers inside `MappedVillageService` named villages
- Named-entity override (any name-tagged entity within ~16 blocks gets defended)

**Out of scope (v1):**
- Engaging player attackers (pending "alliances" system)
- Defending farm animals against accidental splash/AOE hits they weren't
  explicitly targeted for
- Cross-dimension or cross-chunk defense (bot only acts on entities in its own
  loaded-chunk neighbourhood)
- Pillager patrol-specific alertness (belongs to the separate pillager-alert
  feature; see future design doc)
- Bot-vs-bot accidental defense (another bot hitting an owned animal is treated
  as a non-engagement event; handled ad-hoc later)

## Defended Categories

An attacker triggers defense only if the victim entity passes at least one of
the following gates:

1. **Commander-owned tameable.** `target instanceof TameableEntity &&
   tameable.isTamed() && tameable.getOwnerUuid() == commanderUuid`. Covers cat,
   wolf, parrot. Effective within the bot's ~16-block scan range.

2. **Commander-owned horse family.** `target instanceof AbstractHorseEntity &&
   horseOwner == commanderUuid`. Covers horse, donkey, mule, llama, camel.
   Effective within ~16 blocks.

3. **Leashed-to-commander.** `mob.isLeashed() && mob.getLeashHolder() ==
   commander`. Leashed to a fence or to another player does NOT count — the
   lead must be physically held by the commander entity at detection time.
   Effective within ~16 blocks.

4. **Base-proximity farm animal.** `target instanceof AnimalEntity && target
   is within 32 blocks of a commander-registered base (via BotHomeService) &&
   target is within 8 blocks of a HAY_BLOCK that is itself within the base
   radius`. Covers unnamed farm animals (cow, sheep, pig, chicken, goat, etc.)
   only when both the hay bale and the animal are on the commander's
   registered-base footprint. Requires an active registered base — bots without
   a base simply don't defend fuzzy-category farm animals.

5. **Named entity override.** Any entity with a name tag (`hasCustomName()`)
   within the bot's ~16-block scan range is defended, regardless of class.
   Includes villagers, iron golems, cows, foxes, axolotls — anything the
   commander explicitly named. Bounded by scan range; does not chase
   across the map.

6. **Named-village villager.** `target instanceof VillagerEntity &&
   MappedVillageService.isInsideMappedVillage(world, target.blockPos) && bot is
   inside or adjacent to the same mapped village`. Protects villagers only in
   villages the commander has explicitly mapped — unnamed/unmapped villages
   are unprotected, which keeps iron farms in random wild villages safe.

## Excluded Categories

Even if a rule above would trigger, defense is suppressed in these cases:

- **Iron golems** (unless name-tagged). Keeps iron farms working, since iron
  farms rely on unnamed golems being attacked by zombies. Name-tagging a
  specific golem still opts it in.

- **Villagers outside any mapped village** (unless name-tagged). Keeps
  trading halls and unmapped villages safe.

- **Snow golems, raiders, hostile mobs.** Never defended.

- **Attacker in a vehicle.** If the attacker entity is riding another entity
  (boat, minecart, strider, mounted on another mob), skip defense. This is a
  farm-machinery heuristic — zombies in boats used as iron-farm scarers,
  minecart-trapped mobs in spawner grinders, AFK-farm mob-mounts.

- **Tamed-vs-tamed skip.** If the attacker is itself a defended entity (e.g.,
  llama spitting at an owned wolf, owned wolf accidentally attacking an owned
  sheep), skip defense. Prevents llama-spit cascades from causing the bot to
  attack its own pets.

## Iron Golem Special Rules

Iron golems in named villages can misidentify the bot as a threat. The bot
must avoid fighting them:

- If an iron golem damages the bot but `ironGolem.getTarget() != bot`
  (accidental-hit case — golem was targeting a villager or another mob and
  clipped the bot via momentum or AOE), the bot does **not** retaliate,
  does **not** add the golem to its combat queue, and continues whatever
  task it was doing. Treat as background noise.

- If an iron golem damages the bot AND `ironGolem.getTarget() == bot`
  (actively aggroed), the bot **flees** via existing flee logic. It does not
  fight back regardless of weapon or HP. Iron golems are tanky and the bot
  will lose; running away de-aggroes the golem when the bot leaves its
  detection range.

Rationale: the existing combat system would cheerfully attack an iron golem
if provoked. The special-case guards prevent the bot from destroying a named
village's defensive golems.

## Operational Behaviour

Two branches based on distance from the bot to the attacker:

### Attacker within ~16 blocks (engage)

- Add `(bot, attacker)` to a time-limited `DEFEND_TARGETS` map with a 100-tick
  (5 second) expire time. Each new defense event extends the expiry.
- `BotAnimalDefenseService.defenseBoost(bot, entity)` returns a significant
  threat-score bonus (`DEFENSE_SCORE_BOOST = 50.0`) when invoked for a bot/
  attacker pair that is in the map and unexpired. Otherwise returns 0.
- `BotEventHandler.scoreThreat` adds this bonus to its normal score, so the
  attacker jumps to the top of the combat priority queue naturally.
- `BotAnimalDefenseService.augmentHostilesWithDefenseTargets(bot, hostileList)`
  inserts any in-map attackers that aren't already in `hostileList` (dedupes
  by UUID). This is necessary because normal hostile scans only pick up
  `HostileEntity` subclasses; if the attacker is itself a `TameableEntity`
  (wolf gone wild, another bot's pet) it wouldn't otherwise be in the list.
- Self-preservation still wins. If `bot.getHealth() <=
  bot.getMaxHealth() * SELF_PRESERVATION_HP_FRACTION` (default 0.30), defense
  does not engage; bot continues fleeing/sheltering. No heroics below 30% HP.
- No skill interruption. If the bot is running a skill (woodcut, fish, farm),
  the skill continues. Defense only fires during the bot's normal idle /
  follow / guard combat tick. The existing engagement path already runs in
  the same tick as skills, so the defense boost just reprioritizes targets
  without stopping whatever the bot is doing.

### Attacker outside ~16 blocks (warn)

- Emit a one-shot overhead line via
  `CompanionOverheadDialogueService.showOverheadLine`: "Something's attacking
  your {entityName}!" (exact wording TBD, likely reuses existing voiced line
  families).
- Throttled per `(attacker UUID, victim UUID)` pair with
  `OVERHEAD_WARN_COOLDOWN_MS = 60_000`. The same attacker beating on the same
  animal won't spam; attacks on different animals each get their own warning.
- No engagement, no movement, no skill interruption.

### Player attacker (PvE scope caveat)

- If the attacker is a non-commander `PlayerEntity`, defense is suppressed
  but a different overhead warning fires, reusing the existing
  `"Engaging threats against allies"` voiced dialogue family. Marks the
  player as a registered `PLAYER_ATTACKER` event for the devlog system.
- Future "alliances" feature (out of scope) will re-gate this via a
  pluggable `isAttackerAllied(bot, player)` predicate.

## Detection Architecture

The naive approach — scan all animals within radius every tick and check their
`getAttacker()` — scales with farm density, which is bad for players with large
livestock operations (100+ animals in radius is realistic). The revised
detection is **hostile-forward**:

### Step 1: hostile-forward scan (primary path)

```
hostiles = getHostilesNear(bot, HOSTILE_SCAN_RADIUS)       // typically 0–5
for each hostile:
    target = hostile.getTarget()                            // vanilla AI target
    if target != null && isDefendedEntity(target, bot):
        if !isExcludedByFarmHeuristic(hostile):
            if !isTamedVsTamedCase(hostile, target):
                markAttackerForDefense(bot, hostile, DEFEND_EXPIRE_TICKS)
```

Properties:
- **Bounded by hostile count, not farm size.** The 100-animal farm is never
  iterated.
- **Piggybacks on existing hostile-scan infrastructure.** The same
  `world.getEntitiesByClass(HostileEntity.class, box, ...)` call already
  happens in `engageHostiles`; we can either share the result or run a
  similar query in the new service.
- **`mob.getTarget()` is maintained live by vanilla AI.** No staleness — if
  the mob currently has a target, it's the target right now.
- **Proactive.** Defense fires when the hostile acquires the target, often
  before the animal loses HP. Commander sees the bot engage early.

### Step 2: watch-list reverse scan (for edge cases)

Players don't have AI targets (`getTarget()` on a PlayerEntity returns null),
so Step 1 can't detect player-on-animal attacks. A few other edge cases also
slip through:

```
watchList = [
    commander's tameables within ~16 blocks,             // typically 0–5
    commander's horses within ~16 blocks,                // typically 0–3
    mobs leashed to commander within ~16 blocks,         // typically 0–5
    named entities within ~16 blocks,                    // typically 0–3
]
// Cap: WATCH_LIST_HARD_CAP = 12 (first-12 belt-and-suspenders)

for each watched in watchList:
    attacker = watched.getAttacker()
    if attacker == null || attacker == commander: continue
    if !recentlyAttacked(watched): continue
    if attacker instanceof PlayerEntity:
        emitPlayerAttackerWarning(bot, watched, attacker)
    else:
        markAttackerForDefense(bot, attacker, DEFEND_EXPIRE_TICKS)
```

The watch list is **always small** because it only contains the commander's
personal pets and named entities — not the farm. Even with a very invested
player, the watch list caps at 12 entities. Scan cost is O(1) for all
practical purposes.

### Deliberate gap: farm animals and accidental hits

Farm animals (hay-bale-radius category) are defended **only** via Step 1 —
a hostile that has explicitly acquired the cow as a target. If a creeper AOE
clips a cow without targeting it, or a stray skeleton arrow grazes a sheep,
defense does not fire for that specific event. In practice this is
acceptable:
- Creepers only explode near the bot or a player, so the bot is already
  engaging the creeper via existing combat.
- Stray arrows usually mean a skeleton is nearby, and the skeleton will
  target a living entity within a few ticks — covered by Step 1.

An optional "farm sentinel" v2 mode could round-robin-poll farm animals
(1 per tick) to cover the gap, but is deferred.

## Service Architecture

New service: `BotAnimalDefenseService` in
`net.wcfcarolina13.GameAI.services`.

```java
public final class BotAnimalDefenseService {
    // per-bot defense tracking
    private static final Map<UUID, Map<UUID, Long>> DEFEND_TARGETS;

    // per-bot overhead warn throttle
    private static final Map<UUID, Long> LAST_OVERHEAD_WARN_MS;

    public static void onServerTick(MinecraftServer server) { ... }

    // Hook for BotEventHandler.scoreThreat (one line added there)
    public static double defenseBoost(ServerPlayerEntity bot, Entity candidate);

    // Hook for hostile-list augmentation (called from engageHostiles path)
    public static void augmentHostilesWithDefenseTargets(
        ServerPlayerEntity bot,
        List<Entity> hostileList);

    // Internal classification
    private static boolean isDefendedEntity(Entity target, UUID commanderUuid);
    private static boolean isExcludedByFarmHeuristic(Entity attacker);
    private static boolean isTamedVsTamedCase(Entity attacker, Entity target);
}
```

Registration: alongside existing `END_SERVER_TICK` registrations in
`Frens.java`. Throttle: 10 ticks (`server.getTicks() % SCAN_INTERVAL_TICKS == 0`).

### Integration points

Three touchpoints in existing code:

1. **`BotEventHandler.scoreThreat`** — one line added at the bottom of the
   scoring function: `return baseScore +
   BotAnimalDefenseService.defenseBoost(bot, entity);`. Lazy map cleanup
   happens inside `defenseBoost` (expired entries dropped on read).

2. **`BotEventHandler.engageHostiles`** (and its upstream hostile-list
   builders) — one line added just before the hostile list is filtered:
   `BotAnimalDefenseService.augmentHostilesWithDefenseTargets(bot,
   augmentedHostiles);`. Ensures non-`HostileEntity` attackers (tameable
   gone wild, another bot's pet) are visible to the combat system.

3. **`Frens.java`** — one line added to the `END_SERVER_TICK` registration
   block: `ServerTickEvents.END_SERVER_TICK.register(
   BotAnimalDefenseService::onServerTick);`.

No mixins. No new event listeners. No modifications to vanilla classes.

## Tunable Constants

| Constant | Value | Notes |
|---|---|---|
| `SCAN_INTERVAL_TICKS` | 10 | 0.5s cadence, cheap at new cost |
| `HOSTILE_SCAN_RADIUS` | 16.0 blocks | matches existing hostile-scan range |
| `WATCH_LIST_SCAN_RADIUS` | 16.0 blocks | matches `HOSTILE_SCAN_RADIUS` |
| `WATCH_LIST_HARD_CAP` | 12 | safety cap on reverse-scan |
| `DEFENSE_ENGAGE_RADIUS` | 16.0 blocks | within this = engage; outside = overhead warn |
| `BASE_HOME_RADIUS_FOR_FUZZY` | 32 blocks | hay-bale / fuzzy-category filter |
| `HAY_BALE_RADIUS` | 8 blocks | original spec from user |
| `DEFEND_EXPIRE_TICKS` | 100 (5s) | threat-boost lifetime |
| `OVERHEAD_WARN_COOLDOWN_MS` | 60_000 | one warning per (attacker,animal) pair per minute |
| `SELF_PRESERVATION_HP_FRACTION` | 0.30 | flee instead of defending below 30% HP |
| `DEFENSE_SCORE_BOOST` | 50.0 | threat boost value (creepers at close range still win via proximity term) |

All tunable at the top of `BotAnimalDefenseService`. In-game tuning pass
expected once real playtesting begins.

## Manual Verification Checklist

No automated tests — the mod has none. Verification is in-game after deploy:

1. **Owned-wolf defense.** Spawn wolf next to commander, tame it, spawn
   zombie 6 blocks away, verify bot runs at zombie and attacks.

2. **Base-proximity farm defense.** Register base, place hay bale inside
   base radius, spawn sheep 10 blocks from hay bale, send skeleton to attack
   sheep; verify bot engages skeleton.

3. **Out-of-range overhead warning.** Spawn sheep 50 blocks from base, same
   attack; verify overhead warn only, no engagement.

4. **Iron farm safety.** Place zombie in boat near iron golem in an
   unmapped village; verify bot does NOT intervene. Remove zombie from boat;
   verify bot still does NOT intervene (unmapped village = iron golem
   unprotected).

5. **Named-village villagers.** `/base map` a village; have zombie attack
   villager inside mapped village; verify bot engages zombie.

6. **Iron golem accidental hit.** In a named village, stand near a
   villager being attacked; cause the golem to swing and clip the bot;
   verify bot does NOT retaliate against the golem.

7. **Iron golem direct aggro.** Anger a golem (punch a villager); verify
   bot flees instead of fighting.

8. **Llama-spit skip.** Two owned llamas standing next to an owned wolf;
   cause one llama to spit and hit the wolf; verify bot does NOT engage
   the llama.

9. **Player attacker overhead warning.** Another player punches an owned
   cow; verify overhead line fires with `"Engaging threats against allies"`
   voiced line, no engagement.

10. **Tamed-vs-tamed via bot attack.** Commander's wolf attacks an
    unnamed sheep; verify the bot does NOT engage the wolf (tamed-vs-tamed
    skip on the attacker side).

11. **Self-preservation lockout.** Reduce bot to 25% HP; spawn hostile
    targeting owned wolf; verify bot flees instead of engaging.

12. **Farm scan cost.** Stand in a 100-animal farm with 2 hostiles; run
    for 60 seconds; verify no tick-lag (anecdotal but worth noting).

## Devlog: future alliances system

Player attackers currently receive overhead warnings only, reusing the
existing `"Engaging threats against allies"` voiced line. When the
"alliances" feature lands, `BotAnimalDefenseService.isAttackerAllied(bot,
player)` will gate whether the attacker is treated as:

- **(a) Commander-equivalent** (trusted — no warning, no engagement, same
  treatment as the commander hitting their own animal);
- **(b) Allied** (warning only, no engagement — current default for all
  non-commander players);
- **(c) Hostile** (full engagement via `augmentHostilesWithDefenseTargets`).

The hook point is `BotAnimalDefenseService.maybeWarnPlayerAttacker` —
currently unconditional, pending the gate. This is intentional forward
compatibility: v1 is friendlier-than-needed so a future hostile-ally
designation can only _reduce_ defense actions, never expand them.

No changes to existing `ALLY_BOT` fast-travel fallback semantics — the
"ally" concept there is bot-to-bot navigation, separate from animal
defense.

## Out of Scope / Future Work

- **Farm sentinel v2.** Round-robin polling of farm animals for
  accidental-hit coverage. Deferred; gap is small in practice.
- **PvP engagement.** Pending alliances system.
- **Cross-dimension defense.** Not supported; bot only acts on
  loaded-chunk entities near itself.
- **Multi-commander / ally-bot defense.** Ally bots currently ignore each
  other's animals. Extending to "defend any registered bot's commander's
  animals" is a larger multi-bot coordination question, deferred.
- **Defense memory persistence.** `DEFEND_TARGETS` map is in-memory only,
  not persisted across server restarts. Intentional — 5-second expiry
  makes persistence pointless.
- **Cancellation on target death.** If the attacker dies while on the
  defense list, the entry will expire naturally in ≤5 seconds. No
  explicit cleanup needed.

## Rationale Notes

- **Why the hostile-forward scan.** Animal-backward scans scale with farm
  density, which is the exact wrong direction for a feature that targets
  farm protection. Hostile-forward scales with threats, which is the
  actually-bounded resource.

- **Why ~16 blocks.** Matches the existing hostile-scan radius used by
  `engageHostiles`. Consistency with existing combat means the bot never
  has to chase a fight it wouldn't normally see, and the overhead-vs-
  engage boundary aligns with the boundary the bot already treats as
  "nearby combat zone".

- **Why 30% HP self-preservation.** Matches vanilla wolf flee threshold.
  Below this, the bot is already in danger and adding another fight to
  its queue is a death sentence.

- **Why the `DEFENSE_SCORE_BOOST = 50.0`.** The existing scoreThreat
  formula scales to ~60 for very-close creepers. 50 is large enough to
  promote a defended attacker above most normal threats but not so large
  that an active creeper at point-blank loses to a distant wolf-chaser.

- **Why no mixin.** The mod has a convention of avoiding mixins for
  per-tick behavior (see `BotCampfireAvoidanceService`,
  `BotHazardService`). Polling `getTarget()` and `getAttacker()` is
  sufficient and keeps the change scope-local.

- **Why `defenseBoost` lazy cleanup.** Avoids a separate sweep pass. The
  map is small (usually 0-3 entries per bot), so per-read scanning for
  expired entries is effectively free.
