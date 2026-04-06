# Wire Unused Voiced Dialogue Lines

**Date:** 2026-04-06
**Scope:** Wire 23 recorded-but-unused voiced dialogue lines into active gameplay systems.

## Background

An audit of all 516 `LINE_*` constants in `BotDialogueSounds.java` against the 7 active dialogue systems found 23 voiced lines (with audio files and subtitle mappings) that are never played during normal gameplay.

- **19 mount lines** — alternate phrasings recorded during batch audio production. For each mount situation (horse hurt, no lead, lost horse, etc.) only one phrasing was wired into `RideSyncService`; the rest sit unused.
- **4 combat lines** — legacy constants superseded by more specific variants in `BotCombatCalloutService`.

## Approach: Approach A — Randomize at the text/source level

### Mount alternates (19 lines)

`RideSyncService` sends dialogue via `ChatUtils.sendChatMessages()`, which calls `BotDialoguePlayer.tryPlayDialogueDetailed()` → `DialogueTextMapper.lookup()` to resolve text to a sound event. Currently each situation sends one hardcoded string.

**Change:** Each call site picks randomly from an array of phrasings (original + alternates). Each alternate phrasing gets an `EXACT_MAP` entry in `DialogueTextMapper` so the pipeline resolves it.

#### Situation mapping

| Situation | Current text | Alternates |
|---|---|---|
| Horse hurt | "This horse looks hurt." | "Mount's looking banged up." / "My horse is hurt." |
| No apples | "I don't have any apples to heal it." | "I'm out of apples for the horse." / "No apples on me for this horse." |
| No food | "I don't have any suitable food to heal it." | "I can't find food for it." / "I don't have feed for this horse." |
| No lead (ensure) | "I don't have a lead to secure this horse." | "I'm missing a lead for the horse." |
| No lead (grab) | "I can't grab a lead to secure this horse." | "I couldn't get the lead." / "My lead isn't accessible right now." |
| No fence | "I don't have a fence to tie this horse to yet. I'll keep it on a lead." | "No fence nearby to tie it off." / "I'll hold the lead until I can tie it off." |
| Lost horse (3 call sites share a pool) | "I lost track of the horse I was holding." / "The horse I was holding is gone." | "I lost the horse I was leading." / "I can't find the horse." / "My mount is gone." / "I lost the animal I was leading." |
| Lead snapped | "The lead snapped after a sudden drop." | "The lead broke after that fall." / "The lead snapped on that drop." |
| No lead to reattach | "I don't have a lead to reattach." | "I'm out of leads to reattach." / "No spare leads to reattach." |

#### Implementation detail

A `pickRandom(String... options)` helper using `ThreadLocalRandom` in `RideSyncService`. Each hardcoded string call site changes to `pickRandom("original", "alt1", ...)`.

For the "lost horse" group (3 call sites at lines ~3518, ~3523, ~3531 plus the 2 existing texts), all share one combined pool of 6 phrasings.

### Combat orphans (4 lines)

`BotCombatCalloutService` uses `sayWithSound(bot, text, soundEvent)` directly — no `DialogueTextMapper` involvement.

| Line | Subtitle | Where to add |
|---|---|---|
| `LINE_COMBAT_KILL` | "Enemy down." | Generic kill fallback in `pickKillCallout()` — add as 4th option alongside `TARGET_DOWN` / `GOT_IT` / `ONE_LESS` |
| `LINE_COMBAT_ATTACKING` | "Engaging!" | `onEngagement()` — random alternate to existing `LINE_COMBAT_ENGAGING` |
| `LINE_COMBAT_CLEAR` | "All clear." | Combat-end in `checkCombatEnd()` — add to the post-combat pool alongside `STILL_ALIVE` / `ADEQUATE` |
| `LINE_COMBAT_PLAYER_HIT` | "Hey! Watch it!" | Friendly-fire received in `onPlayerHit()` — add alongside `FF_RECEIVED_OW_THAT_WAS_YOU` / `FF_RECEIVED_ON_YOUR_TEAM` |

### DialogueTextMapper entries

19 new `EXACT_MAP.put()` entries for mount alternate phrasings (one per unused mount line). Combat lines don't need mapper entries since `BotCombatCalloutService` passes sound events directly.

## Files touched

1. **`RideSyncService.java`** — ~9 call sites + `pickRandom` helper
2. **`BotCombatCalloutService.java`** — 4 spots add extra options to existing random pools
3. **`DialogueTextMapper.java`** — 19 new `EXACT_MAP.put()` entries

## What this does NOT cover

- Lines without subtitles/audio (106 constants) — these need recording first.
- Lines already active through existing systems.
- The weather/cooking/wake-up lines added earlier in this session (already wired).
