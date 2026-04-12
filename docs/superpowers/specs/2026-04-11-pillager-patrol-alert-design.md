# Pillager Patrol Alert Design

**Date:** 2026-04-11
**Status:** Design approved, pending spec review
**Author:** collaborative brainstorming session

## Overview

When the bot sees 2+ illagers within line of sight, it goes on the defensive
(shield up, don't charge) and sends a one-shot alert through the best
available channel — goat horn, signal fire, magic-comm direct message, or a
short-range fallback. If the patrol aggros on the bot, commander, or an owned
animal, normal combat + Feature A (tamed-animal defense) kicks in seamlessly.
If the patrol passes without engaging, the bot relaxes.

## Motivation

Pillager patrols are an early-game surprise encounter that can devastate an
unprepared player. The bot currently treats pillagers as individual hostile
targets and charges at them — often getting itself killed against a group of
5. Players want advance warning and a bot that holds position defensively
instead of suicidally rushing a patrol.

The alert system also gives mechanical value to comm items (eye of ender,
wizard's tome, ender pearls, enchanting table) and environmental
infrastructure (signal fires) — players who invest in these get longer-range
early warnings. Without them, the bot still warns but only at close range.

## Scope

**In scope:**
- Detection of illager groups (2+ visible illagers within 16 blocks, LOS-gated)
- Defensive posture: pursuit suppression until aggro'd
- One-shot alert via tiered channels (goat horn > signal fire > direct message > fallback)
- Channel gating: goat horn = inventory, signal fire = lit campfire + hay bale + not FOLLOW mode, direct message = magic-comm items/proximity, fallback = always
- Goat horn above/below ground range differentiation
- Alert-then-escalate to normal combat when illagers aggro
- Integration with Feature A (tamed-animal defense) for owned-animal protection

**Out of scope (v1):**
- Raid-specific detection or raid-bar tracking (raids are detected as illager groups naturally — the alert system fires the same way, but there's no raid-specific UI or countdown)
- Outpost proximity detection (pillager outposts are static structures — the bot doesn't recognize them as special)
- Multi-bot coordinated alerts (multiple bots in the same area will each fire their own alert independently)
- Persistent patrol tracking (the bot doesn't remember "I saw a patrol 5 minutes ago heading north" — it only knows "I see illagers right now")

## Detection

**Piggybacked on existing hostile scan.** No new world query or tick service.
Inside the existing `engageHostiles` call path (which already runs
`BotThreatService.findHostilesAround(bot, 16)` every combat tick), the
patrol alert service adds a lightweight filter step:

1. Every `SCAN_CADENCE_TICKS` (20 ticks = 1 second), filter the hostile list
   for `IllagerEntity` + `RavagerEntity` instances.
2. For each illager, check `bot.canSee(illager)` — vanilla eye-to-eye
   raycast using `RaycastContext.ShapeType.COLLIDER`. Cost: one raycast per
   illager found (typically 0–5). Negligible.
3. If 2+ illagers are visible → set `PATROL_DETECTED` flag with a
   `PATROL_FLAG_DECAY_TICKS` (200 = 10 second) decay timer. Each
   re-detection refreshes the timer.
4. When the visible count drops below 2 and the timer expires → clear the
   flag.

**LOS gating means:**
- Bot in an open field, patrol 14 blocks away → detected
- Bot inside a walled house, patrol outside → NOT detected
- Bot in a cave, patrol on surface → NOT detected
- Bot on a hilltop, patrol in the valley → detected

This gives the bot the same awareness horizon a player would have — no
cheaty x-ray detection.

**Why LOS-gated when normal hostile detection is NOT:** the existing
`BotThreatService.findHostilesAround` returns all hostiles regardless of
LOS so the bot can prepare for ambushes. The patrol alert is different — it
represents "I SEE a patrol" not "I sense a nearby threat." The two systems
don't conflict because they serve different purposes (combat prep vs.
situational awareness + alert).

## Defensive Posture (alert-then-escalate)

When `PATROL_DETECTED` is true:

**Pursuit suppression:** the bot does NOT chase illager targets beyond
`PURSUIT_SUPPRESS_LEASH` (6 blocks). The `moveToward(positionOf(closest),
...)` call in `engageHostiles` is suppressed for targets that are
`IllagerEntity` or `RavagerEntity` while the flag is set and no illager
has aggroed. Bot raises shield facing the nearest visible illager and holds
position:
- In FOLLOW mode: stays near the commander
- In GUARD/PATROL mode: stays near its guard point
- In IDLE/skill mode: stays where it is

**Escalation trigger:** the moment ANY illager in the detected group has
`target == bot` OR `target == commander` OR `target == any Feature A
defended animal` → pursuit suppression lifts. The `PATROL_DETECTED` flag
stays set (the bot still knows there's a patrol), but normal combat
behavior takes over. Feature A's `defenseBoost` + `augmentHostiles` kicks
in for animal attacks; the existing `scoreThreat` handles direct bot/
commander threats.

**De-escalation:** if the patrol walks away without aggroing and the flag
decays after `PATROL_FLAG_DECAY_TICKS` (10 seconds of no visible illagers),
the bot relaxes back to normal. No fight happened, no resources spent.

## Alert Channels

When `PATROL_DETECTED` transitions from `false → true` (first sighting),
the bot evaluates channels top-to-bottom and uses the **first one it can**.
One channel per alert event — no stacking.

### Priority 1: Goat Horn

**Gate:** bot has a goat horn (`Items.GOAT_HORN`) in inventory.

**Mechanic:** bot equips the horn, triggers `useItem` (plays the vanilla
`instrument` sound event, audible ~256 blocks), then re-equips its previous
held item. The 7-second vanilla item cooldown prevents double-blowing.

**Overhead message range (mod-controlled, gated on vertical position):**
- Both bot and commander above ground (`world.isSkyVisible(pos)`):
  `HORN_OVERHEAD_RANGE_ABOVE` = **128 blocks**.
- Either bot or commander underground (sky NOT visible):
  `HORN_OVERHEAD_RANGE_BELOW` = **48 blocks**.
- The vanilla sound still travels ~256 blocks regardless — the range
  gating only affects the mod's overhead text. A distant commander may
  hear the horn but not see the text, which is atmospheric.

**Overhead line:** "Patrol spotted — sounding the horn!"

**Travel required:** No (instant). Available in ALL bot modes including
FOLLOW.

### Priority 2: Signal Fire (campfire + hay bale)

**Gate:** a lit campfire (`CampfireBlock.LIT` = true) with a
`Blocks.HAY_BLOCK` directly below it exists within
`SIGNAL_FIRE_SEARCH_RADIUS` (24 blocks) of the bot. A plain lit campfire
without a hay bale does NOT qualify — the hay bale is required for signal
fire capability, not an optional enhancement.

**Mode suppression:** if bot is in **FOLLOW mode** → skip this channel,
fall through to priority 3. In FOLLOW mode, the bot should not leave the
commander's side. All other modes (GUARD, PATROL, IDLE, active skills like
woodcut/farm/hunt, idle hobbies) allow the campfire run.

**Mechanic:** bot pathfinds to within 2 blocks of the signal fire (uses
existing `MovementService`), then emits the overhead line from the
campfire's position. The tall smoke column (vanilla hay-bale-boosted
particles) serves as the visual signal.

**Max travel budget:** 24 blocks. If the nearest signal fire is farther,
skip this channel. ~3 seconds of sprint travel.

**Overhead line (emitted at campfire position):** "Patrol nearby —
signaling from the fire!"

**Overhead message range:** `SIGNAL_FIRE_OVERHEAD_RANGE` = **64 blocks**
from the campfire position (not from the bot's original position).

### Priority 3: Direct Message (magic-comm gated)

**Gate:** any ONE of these four conditions passes:
- Bot OR commander has `Items.ENDER_EYE` in inventory
- Bot OR commander has the wizard's tome item
  (`CompanionCommunicationPolicy.hasWizardTome`)
- Bot AND commander BOTH have `Items.ENDER_PEARL` in inventory
- Bot OR commander is within `ENCHANT_TABLE_PROXIMITY` (8 blocks) of a
  `Blocks.ENCHANTING_TABLE`

Inventory checks use the existing `player.getInventory().contains(new
ItemStack(item))` pattern from `CompanionCommunicationPolicy`. Enchanting
table proximity: scan a small box around both bot and commander.

**Overhead line:** "I see a patrol — stay alert."

**Overhead message range:** `DIRECT_MSG_RANGE` = **48 blocks**.

**Travel required:** No (instant). Available in ALL bot modes.

### Priority 4: Fallback (always available)

**Gate:** none — always fires if nothing above was available.

**Overhead line:** "Something's not right..."

**Overhead message range:** `FALLBACK_RANGE` = **16 blocks**. The
commander must be near the bot to see this. This is the "you should have
brought a comm item" scenario.

**Travel required:** No (instant). Available in ALL bot modes.

### Magic-comm gate helper

The four inventory/proximity gates form a general "can the bot communicate
at range with the commander" predicate. Implement as a public helper on
`CompanionCommunicationPolicy`:

```java
public static boolean canLongRangeComm(
        ServerPlayerEntity bot, ServerPlayerEntity commander) {
    // Eye of ender (either player)
    // Wizard's tome (either player)
    // Both have ender pearl
    // Either near enchanting table
}
```

Reusable for future features that need the same communication-range gate.

### "Hold ground to fight" fallback

If no signal fire is available and no comm items are present, the bot
doesn't just stand behind a shield forever. After the fallback overhead
warning fires, the bot is in defensive posture: shield up, don't pursue.
If an illager aggros (escalation trigger from the posture section), normal
combat kicks in. If the illager attacks an owned animal, Feature A defense
kicks in. The bot holds its ground and fights what comes to it.

## Cooldown + Repeat Alerts

- **Per-bot cooldown:** `ALERT_COOLDOWN_MS` = 60,000ms (1 minute) after
  an alert fires, the same bot won't alert again even if it loses and
  re-gains LOS on the same patrol. Prevents spam at patrol-path edges.
- **Keyed on bot UUID only** (not specific illager UUIDs). Even if a NEW
  patrol appears within 60 seconds, the bot stays quiet — the commander
  already knows there are patrols in the area.
- **Reset on full decay + re-detect:** if the `PATROL_DETECTED` flag fully
  decays (10 seconds of zero visible illagers) AND 60+ seconds have passed
  since the last alert, a fresh patrol sighting fires a new alert.

## Service Architecture

**New file:** `BotPillagerAlertService.java` in
`net.wcfcarolina13.GameAI.services`.

```java
public final class BotPillagerAlertService {
    // State maps
    private static final Map<UUID, Long> PATROL_DETECTED;      // botUuid -> expireGameTick
    private static final Map<UUID, Long> LAST_ALERT_MS;        // botUuid -> lastAlertEpochMillis
    private static final Map<UUID, Long> LAST_SCAN_TICK;       // botUuid -> lastScanGameTick

    // Called from BotEventHandler.engageHostiles after the hostile list is built.
    // Returns true if pursuit of illager targets should be suppressed.
    public static boolean checkForPatrolAndSuppressPursuit(
        ServerPlayerEntity bot,
        MinecraftServer server,
        List<Entity> hostileList);

    // Called from BotEventHandler.engageHostiles before the moveToward
    // that chases the target. Returns true if the target is an illager
    // AND the bot is in patrol-defensive mode AND no illager has aggroed.
    public static boolean shouldSuppressPursuit(
        ServerPlayerEntity bot, Entity target);

    // Alert channel dispatch (called internally on first detection).
    private static void fireAlert(ServerPlayerEntity bot, MinecraftServer server);
    private static boolean tryGoatHorn(ServerPlayerEntity bot, ServerPlayerEntity commander);
    private static boolean trySignalFire(ServerPlayerEntity bot, ServerWorld world);
    private static boolean tryDirectMessage(ServerPlayerEntity bot, ServerPlayerEntity commander);
    private static void fireFallback(ServerPlayerEntity bot);

    // Cleanup
    public static void reset();
}
```

**`CompanionCommunicationPolicy` addition:**

```java
public static boolean canLongRangeComm(
    ServerPlayerEntity bot, ServerPlayerEntity commander);
```

### Integration points (2 hooks in existing code)

1. **`BotEventHandler.engageHostiles`** — after the existing
   `augmentHostilesWithDefenseTargets` hook from Feature A, add:
   ```java
   boolean patrolSuppressPursuit = BotPillagerAlertService
       .checkForPatrolAndSuppressPursuit(bot, server, hostileEntities);
   ```

2. **`BotEventHandler.engageHostiles`** — in the approach/chase block
   (around `moveToward(positionOf(closest), ...)`), wrap in:
   ```java
   if (!patrolSuppressPursuit
           || !BotPillagerAlertService.shouldSuppressPursuit(bot, closest)) {
       moveToward(positionOf(closest), preferredStopDistance, true);
   } else {
       BotActions.raiseShieldFacing(bot, closest);
   }
   ```

3. **`Frens.java`** — one line in SERVER_STOPPING cleanup:
   `BotPillagerAlertService.reset();`

No new END_SERVER_TICK registration (everything piggybacks on
engageHostiles).

## Tunable Constants

| Constant | Value | Notes |
|---|---|---|
| `ILLAGER_COUNT_THRESHOLD` | `2` | Minimum visible illagers for "patrol detected" |
| `SCAN_CADENCE_TICKS` | `20` | Only count illagers every 20 ticks (1s) |
| `PATROL_FLAG_DECAY_TICKS` | `200` (10s) | How long the flag persists after last detection |
| `ALERT_COOLDOWN_MS` | `60_000` (1 min) | Per-bot cooldown between alerts |
| `HORN_OVERHEAD_RANGE_ABOVE` | `128.0` blocks | Goat horn overhead range, both above ground |
| `HORN_OVERHEAD_RANGE_BELOW` | `48.0` blocks | Goat horn overhead range, either underground |
| `SIGNAL_FIRE_SEARCH_RADIUS` | `24` blocks | Max distance bot will travel to signal fire |
| `SIGNAL_FIRE_OVERHEAD_RANGE` | `64.0` blocks | Overhead range from campfire position |
| `DIRECT_MSG_RANGE` | `48.0` blocks | Direct message overhead range |
| `FALLBACK_RANGE` | `16.0` blocks | Fallback overhead range (always available) |
| `ENCHANT_TABLE_PROXIMITY` | `8` blocks | How close to enchanting table for comm gate |
| `PURSUIT_SUPPRESS_LEASH` | `6.0` blocks | Max chase distance in defensive mode |

## Manual Verification Checklist

1. Spawn 3 pillagers 12 blocks from bot in open field; bot has goat horn
   → verify horn sounds, overhead line, shield up, does NOT charge.
2. Same but bot has no horn, no comm items → verify fallback overhead at
   16 blocks only.
3. Same, bot has eye of ender → verify 48-block range direct message.
4. Patrol walks past without aggroing → verify bot relaxes after ~10s.
5. Patrol aggros on bot → verify pursuit suppression lifts, normal combat.
6. Patrol aggros on owned wolf → verify Feature A defense kicks in.
7. Bot in FOLLOW mode near signal fire, no horn → verify bot does NOT
   run to campfire; uses direct message or fallback.
8. Bot in GUARD mode near signal fire → verify bot runs to campfire,
   overhead line at campfire position.
9. Bot in PATROL mode near signal fire → verify same as GUARD (campfire
   run allowed).
10. Bot running woodcut skill near signal fire → verify campfire run
    allowed (skill is interrupted for the alert, then resumes or the
    patrol engagement takes over).
11. Bot underground, patrol above → verify NOT detected (LOS blocked).
12. Both bot and commander have ender pearl → verify direct message.
13. Bot near enchanting table, no comm items → verify direct message.
14. Alert fires, same patrol re-enters LOS within 60s → verify NO
    second alert.
15. Alert fires, patrol leaves, 60s+ passes, new patrol → verify fresh
    alert.
16. Single lone pillager 10 blocks away → verify NO patrol alert (below
    threshold of 2).
17. No signal fire within 24 blocks, no comm items, no horn → verify
    bot holds ground defensively and fights when aggro'd.

## Devlog: future enhancements

- **Raid-specific detection:** track the raid bar state and trigger
  more urgent alerts with different dialogue lines when a raid is
  active.
- **Outpost awareness:** detect pillager outpost structures and warn
  the commander when approaching.
- **Multi-bot coordination:** when multiple bots detect the same
  patrol, only one sends the alert (elected by proximity).
- **Patrol direction tracking:** "Patrol heading east" — watch the
  illager group's movement vector over 2-3 scans.
- **Alliances integration:** alert allied players (not just the
  commander) when alliances feature lands.

## Out of Scope / Future Work

- Raid-bar UI or countdown tracking
- Outpost proximity detection
- Multi-bot alert deduplication
- Persistent patrol memory ("I saw a patrol 5 minutes ago")
- Custom goat horn instrument selection (all horn variants sound
  the same for alert purposes)
- Campfire interaction mechanics beyond standing near it (no placing
  hay bales, no toggling campfire state)
