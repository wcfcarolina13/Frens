# Tamed-Animal Defense Design

**Date:** 2026-04-11
**Status:** Design approved, post-review revision 1
**Author:** collaborative brainstorming session

> **Revision 1 (2026-04-11):** Incorporated spec-review feedback — fixed Yarn
> API names (`AbstractHorseEntity.isTame()` not `isTamed()`, owner access via
> `getOwnerReference().getUuid()`), added explicit commander-resolution
> section, consolidated the `engageHostiles` hook into a single call site,
> tightened rule-5 (named-entity override) to exclude hostile victims,
> replaced hardcoded `BASE_HOME_RADIUS_FOR_FUZZY = 32` with
> `BotHomeService.findBaseNearPosition` (honors per-base radius), corrected
> helper name `BotThreatService.findHostilesAround`, added cleanup-on-stop,
> and added commander-offline / cross-dimension edge cases.

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

## Commander Resolution

Defense categories 1–3 require knowing "who is this bot's commander". The mod
already has a canonical resolver in
[CompanionCommunicationPolicy.java:122](../../src/main/java/net/wcfcarolina13/GameAI/services/CompanionCommunicationPolicy.java#L122),
currently `private`:

```java
private static UUID resolveOwnerUuid(ServerPlayerEntity bot) {
    String alias = bot.getName().getString();
    ManualConfig.BotOwnership o = Frens.CONFIG.getOwner(alias);
    if (o == null) return null;
    String uuid = o.ownerUuid();
    if (uuid == null || uuid.isBlank()) return null;
    return UUID.fromString(uuid);
}
```

**The implementation must promote this helper (or its exact copy) to a public
static method** — either on `CompanionCommunicationPolicy` itself or mirrored
verbatim inside `BotAnimalDefenseService`. The commander UUID is the durable
`ManualConfig.BotOwnership` identity, **not** the transient
`BotCommandStateService.followTargetUuid` (which is only set during FOLLOW
mode and clears on `/bot stop`).

The returned `UUID` is a **handle, not an entity**. It works even when the
commander is logged out or in a different dimension — ownership checks are
UUID-comparisons that don't need the commander entity to be present. The only
rule that needs the live commander entity is rule 3 (leashed-to-commander),
which compares `mob.getLeashHolder() == commanderEntity` — if the commander
is offline, that comparison silently fails and rule 3 skips.

When the bot has no registered owner at all (guard/patrol bots,
never-recruited bots), `resolveOwnerUuid` returns `null`. In that case:

- Rules 1–3 silently skip (no owner to compare against).
- Rule 4 also skips (base-proximity uses the bot's own `BotHomeService` base,
  which typically requires a commander to have registered it — but in the
  future an ally-bot with its own base would still work).
- Rules 5–6 still fire (name-tag and mapped-village rules don't require a
  commander).

This is the intended behaviour: ownerless bots defend name-tagged entities
and mapped villages but don't invent an owner for ownership-only rules.

## Defended Categories

An attacker triggers defense only if the victim entity passes at least one of
the following gates. Victims must additionally satisfy **Victim Sanity
Gates** (below) before defense is considered — a named creeper is not a
defended entity.

1. **Commander-owned tameable.** Victim `instanceof TameableEntity` AND
   `tameable.isTamed()` AND one of:
   - If the commander is online in the same world: `tameable.isOwner(commander)`.
   - Otherwise: `tameable.getOwnerReference() != null &&
     tameable.getOwnerReference().getUuid().equals(commanderUuid)`.
   Covers cat, wolf, parrot. Effective within ~16 blocks.

2. **Commander-owned horse family.** Victim `instanceof AbstractHorseEntity`
   AND `horse.isTame()` (**note: `isTame()` — no `d` — for horses in
   1.21.11 Yarn, distinct from `TameableEntity.isTamed()`**) AND
   `horse.getOwnerReference() != null &&
   horse.getOwnerReference().getUuid().equals(commanderUuid)`. Covers
   horse, donkey, mule, llama, skeleton horse, zombie horse, camel. Effective
   within ~16 blocks.

3. **Leashed-to-commander.** `mob.isLeashed() && mob.getLeashHolder() ==
   commanderEntity`. Requires the commander to be a live `LivingEntity` in
   the same world. Leashed to a fence post, to another player, or to a
   different bot does NOT count. If commander is offline or cross-dimension,
   this rule silently skips. Effective within ~16 blocks.

4. **Base-proximity farm animal.** Victim `instanceof AnimalEntity` AND
   `BotHomeService.findBaseNearPosition(server, botWorld, target.getBlockPos())`
   returns a present `BaseEntry` whose owner is the commander AND the victim
   is within `HAY_BALE_RADIUS` (8 blocks) of a `Blocks.HAY_BLOCK` that is
   **also** inside that same `BaseEntry`. Using `findBaseNearPosition` means
   the per-base `radius` field is honored — not a hardcoded 32. Covers
   unnamed farm animals (cow, sheep, pig, chicken, goat, etc.) only when
   both animal and hay bale are on a commander-registered base. Requires a
   registered base — bots without a base simply don't defend farm animals.

5. **Named entity override.** Victim has a name tag (`entity.hasCustomName()`)
   AND is within the bot's ~16-block scan range AND passes the **Victim
   Sanity Gates** (so a name-tagged hostile is NOT eligible as a victim —
   see below). A named iron golem, named villager, named cow, named axolotl
   all qualify. Bounded by scan range; does not chase across dimensions.

6. **Named-village villager.** Victim `instanceof VillagerEntity` AND
   `MappedVillageService.isInsideMappedVillage(botWorld, target.getBlockPos())`
   is true AND the bot itself is also inside or adjacent to the same mapped
   village (same `MappedVillage` instance via
   `MappedVillageService.getVillageAt(world, botPos)`). Unnamed/unmapped
   villages are unprotected — keeps iron farms in random wild villages safe.

### Victim Sanity Gates

A candidate victim must pass **all** of the following before any defense
rule can fire:

- `victim != null && victim.isAlive() && !victim.isRemoved()`
- `victim.getEntityWorld() == bot.getEntityWorld()` (same dimension)
- `!(victim instanceof HostileEntity)` — no defending zombies, skeletons, etc.
- `!(victim instanceof net.minecraft.entity.raid.RaiderEntity)` — no
  defending illagers/pillagers/ravagers (raiders do not all extend
  `HostileEntity`, so the explicit class check is required)
- `!(victim instanceof SlimeEntity || victim instanceof MagmaCubeEntity)` —
  no defending slimes/magma cubes
- `!(victim instanceof EnderDragonEntity || victim instanceof WitherEntity)` —
  defensive sanity

These gates apply **before** any of rules 1–6 are checked, which
specifically closes rule 5's "named hostile" loophole — a commander who
name-tags a zombie still won't get that zombie defended.

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

```text
hostiles = BotThreatService.findHostilesAround(bot, HOSTILE_SCAN_RADIUS)
         // returns List<Entity>; typically 0–5 entities in normal play
for each hostile:
    if !(hostile instanceof MobEntity): continue
    target = ((MobEntity) hostile).getTarget()              // live vanilla AI target
    if target != null && isDefendedEntity(target, commanderUuid, bot):
        if !isExcludedByFarmHeuristic(hostile):
            if !isTamedVsTamedCase(hostile, target, commanderUuid):
                markAttackerForDefense(bot, hostile, DEFEND_EXPIRE_TICKS)
```

Note on scan sharing: `BotThreatService.findHostilesAround` is already called
once per combat tick by `BotEventHandler` and `BotMutualAidService`. Our
service runs on a **separate** 10-tick cadence and makes an independent
query. Cost is negligible (single AABB entity fetch) and simpler than
trying to share a cached snapshot across unrelated services.

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

Players are not `MobEntity` and therefore don't have an AI `getTarget()`,
so Step 1 can't detect player-on-animal attacks. A few other edge cases
also slip through:

```text
watchList = collectWatchList(bot, commanderUuid)
  = [
      commander's tameables within ~16 blocks,             // typically 0–5
      commander's horses within ~16 blocks,                // typically 0–3
      mobs leashed to commander within ~16 blocks,         // typically 0–5
      name-tagged entities within ~16 blocks,              // typically 0–3
    ]
  capped at WATCH_LIST_HARD_CAP (12) entries total

for each watched in watchList:
    if watched doesn't pass Victim Sanity Gates: continue
    attacker = watched.getAttacker()                // vanilla LivingEntity field
    if attacker == null: continue
    if commander != null && attacker == commander: continue      // commander
                                                                  // butchering own pet
    if !recentlyAttacked(watched): continue         // HURT_TIMER check below
    if attacker instanceof PlayerEntity:
        emitPlayerAttackerWarning(bot, watched, attacker)
    else:
        if !isTamedVsTamedCase(attacker, watched, commanderUuid):
            markAttackerForDefense(bot, attacker, DEFEND_EXPIRE_TICKS)
```

`recentlyAttacked(victim)` uses `victim.getHurtTime()` (vanilla field,
positive immediately after damage, decays over ~10 ticks) to avoid
re-triggering on stale `getAttacker()` values that vanilla keeps for
~100 ticks after the last hit. Without this gate, the bot would keep
reacting to an attacker who stopped attacking 5 seconds ago.

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
    // botUuid -> (attackerUuid -> expireGameTick), values are server-ticks,
    // NOT milliseconds. Cleaned lazily on read inside defenseBoost().
    private static final Map<UUID, Map<UUID, Long>> DEFEND_TARGETS =
        new ConcurrentHashMap<>();

    // (botUuid, victimUuid, attackerUuid) -> lastWarnEpochMillis, used to
    // throttle overhead warnings for out-of-range and player attackers.
    // Key is a small tuple wrapper; value is System.currentTimeMillis().
    private static final Map<WarnKey, Long> LAST_OVERHEAD_WARN_MS =
        new ConcurrentHashMap<>();

    private BotAnimalDefenseService() {}

    public static void onServerTick(MinecraftServer server) { ... }

    // Hook for BotEventHandler.scoreThreat (one line added there).
    // Returns 0 if the candidate is not a defended attacker; returns
    // DEFENSE_SCORE_BOOST if it is. Lazy expiry sweep on read.
    public static double defenseBoost(ServerPlayerEntity bot, Entity candidate);

    // Hook for hostile-list augmentation, called from inside engageHostiles.
    // Appends any in-map attackers not already in hostileList (dedupes by UUID).
    public static void augmentHostilesWithDefenseTargets(
        ServerPlayerEntity bot,
        List<Entity> hostileList);

    // Cleanup hook called from Frens.SERVER_STOPPING handler.
    public static void reset();

    // Internal classification
    private static boolean isDefendedEntity(Entity target, UUID commanderUuid,
                                            ServerPlayerEntity bot);
    private static boolean isExcludedByFarmHeuristic(Entity attacker);
    private static boolean isTamedVsTamedCase(Entity attacker, Entity target,
                                               UUID commanderUuid);
    private static boolean passesVictimSanityGates(Entity victim,
                                                    ServerPlayerEntity bot);
    private static UUID resolveCommanderUuid(ServerPlayerEntity bot);
    private static ServerPlayerEntity resolveCommanderEntity(
        MinecraftServer server, UUID commanderUuid, ServerWorld botWorld);
}
```

Registration: alongside existing `END_SERVER_TICK` registrations in
`Frens.java`. Throttle: 10 ticks (`server.getTicks() % SCAN_INTERVAL_TICKS == 0`).
Cleanup: `reset()` called from the `SERVER_STOPPING` handler.

### Integration points

Three touchpoints in existing code:

1. **`BotEventHandler.scoreThreat`** — one line added at the bottom of the
   scoring function (currently around
   [BotEventHandler.java:3541](../../src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3541)):
   `return baseScore +
   BotAnimalDefenseService.defenseBoost(bot, entity);`. Lazy map cleanup
   happens inside `defenseBoost` (expired entries dropped on read). The boost
   is **additive**, consistent with the existing `stickinessBonus` term. With
   `DEFENSE_SCORE_BOOST = 50.0`, a defended attacker at 16 blocks gets roughly
   `(6.0 + 1.0) * 1.0 + 50 = 57.0`, while an ignited creeper at 3 blocks gets
   `8 * (1 + 3.5) * 2 = 72.0`. Close-range creepers still take priority (the
   explicit intent from the Q2 discussion), but any routine hostile attacker
   jumps above normal wolves/zombies.

2. **`BotEventHandler.engageHostiles`** — **one line added at the top of
   `engageHostiles`** (currently around
   [BotEventHandler.java:3586](../../src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java#L3586)),
   before the `actionable = ...` filter:
   `BotAnimalDefenseService.augmentHostilesWithDefenseTargets(bot,
   hostileEntities);`. This is a **single hook inside the funnel**, not at
   the multiple upstream list-builders (`1559`, `2322`, `3208`, `3280`) —
   all paths flow through `engageHostiles` eventually, so hooking inside the
   funnel catches every caller for one edit. The augmentation dedupes by
   UUID before appending, so existing `HostileEntity` instances already in
   the list are not duplicated. Ensures non-`HostileEntity` attackers
   (tameable wolf gone wild, another bot's pet, ranged-weapon-wielding
   goats if that's ever a thing) are visible to the combat system.

3. **`Frens.java`** — one line added to the `END_SERVER_TICK` registration
   block: `ServerTickEvents.END_SERVER_TICK.register(
   BotAnimalDefenseService::onServerTick);`. Additionally, one line in the
   existing `SERVER_STOPPING` cleanup handler:
   `BotAnimalDefenseService.reset();` — clears `DEFEND_TARGETS` and
   `LAST_OVERHEAD_WARN_MS` on shutdown, mirroring the pattern the other
   8 services documented in CLAUDE.md use.

No mixins. No new event listeners. No modifications to vanilla classes.

## Tunable Constants

| Constant | Value | Notes |
|---|---|---|
| `SCAN_INTERVAL_TICKS` | `10` | 0.5s cadence, cheap at revised hostile-forward cost |
| `HOSTILE_SCAN_RADIUS` | `16.0D` | matches existing hostile-scan range used by `BotThreatService.findHostilesAround` |
| `WATCH_LIST_SCAN_RADIUS` | `16.0D` | matches `HOSTILE_SCAN_RADIUS` |
| `WATCH_LIST_HARD_CAP` | `12` | safety cap on reverse-scan even if commander has 50 named cats |
| `DEFENSE_ENGAGE_RADIUS` | `16.0D` | within this = engage; outside = overhead warn (Step 1 hostile) |
| `HAY_BALE_RADIUS` | `8` blocks | original user spec |
| `DEFEND_EXPIRE_TICKS` | `100` (5s in ticks, **not ms**) | threat-boost lifetime, stored in `DEFEND_TARGETS` as `server.getTicks() + DEFEND_EXPIRE_TICKS` |
| `OVERHEAD_WARN_COOLDOWN_MS` | `60_000` (**ms, not ticks**) | one warning per `(bot,victim,attacker)` tuple per minute, stored in `LAST_OVERHEAD_WARN_MS` as `System.currentTimeMillis()` |
| `SELF_PRESERVATION_HP_FRACTION` | `0.30` | flee instead of defending below 30% HP |
| `DEFENSE_SCORE_BOOST` | `50.0` | additive term in `scoreThreat`, alongside existing `stickinessBonus` |

**Base radius:** intentionally **NOT** a tunable constant. Use
`BotHomeService.findBaseNearPosition(server, world, pos)` to obtain the
`BaseEntry` (if any) covering the victim's position; the per-base `radius`
field is authoritative. If the returned base's owner UUID does not match
the bot's commander UUID, reject — the animal is on someone else's base.

**Units discipline:** any field ending in `_MS` is wall-clock milliseconds
(`System.currentTimeMillis()`); any field ending in `_TICKS` or `_TICK` is
server game-ticks (`server.getTicks()`). The `DEFEND_TARGETS` inner-map
`Long` value is a game-tick; the `LAST_OVERHEAD_WARN_MS` value is
milliseconds. These must not be mixed.

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

12. **Commander offline.** Tame a wolf; commander logs out; spawn zombie
    targeting the wolf while commander is offline. Expected: rules 1
    (UUID compare via `getOwnerReference().getUuid()`) still matches, bot
    engages the zombie. Rule 3 (leashed-to-commander) silently skips
    because commander entity is null.

13. **Commander in different dimension.** Commander teleports to Nether,
    leaves tamed wolf in Overworld; zombie attacks wolf. Expected: bot
    in Overworld still engages via rule 1 (UUID match).

14. **Farm scan cost.** Stand in a 100-animal farm with 2 hostiles; run
    for 60 seconds; monitor server log for `"Running behind, skipping"`
    tick-lag warnings. Expected: no warnings. If any appear, review the
    scan cadence or bounding box size.

15. **Bot with no commander (guard/patrol).** Un-recruited guard bot with
    zero ownership; tame a wolf; spawn zombie. Expected: rules 1–4
    silently skip (no commander UUID), rule 5 still fires for
    name-tagged victims, rule 6 still fires inside mapped villages.

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
