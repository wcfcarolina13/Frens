# Batch 3 Phase 1 Playtest Runbook

Date: February 13, 2026
Scope: Batch 3 non-topic audio integration (87 IDs, 261 clips)

## Purpose

This runbook is for validating:

1. Asset + registry integrity.
2. Trigger wiring and cadence behavior.
3. Manual deterministic playback for rare/complex triggers.
4. Regression safety for preserved overlap IDs.

Use this with `docs/audio/BATCH3_STATUS.md`.

## Preflight

Run these from repo root before world testing:

```bash
tools/audio/audit_batch3_phase1.sh
./gradlew compileJava
```

Expected:

- Audit passes with 87 IDs / 261 files and zero missing entries.
- Compile succeeds.

## In-Game Setup

1. Start a test world with cheats/op.
2. Ensure you have a registered bot companion available.
3. Confirm voiced playback works:

```mcfunction
/bot sound_test <bot_name>
```

4. Optional but useful while testing:

```mcfunction
/bot debug on
```

## Fast Manual Trigger Command

Syntax:

```mcfunction
/bot dialogue_test <bot_name> <trigger_key> [line_id]
```

Notes:

- `line_id` is the short ID from the manifest (example: `combat_multi_not_fair`), not `bot.line.*`.
- For `VillageProximityReactionService`, `PetProximityReactionService`, and `CompanionContextReactionService`, supplying `line_id` bypasses that trigger's local cooldown.
- `BotCombatCalloutService` debug trigger is direct and does not enforce its normal combat cooldown gates.
- Global anti-spam still applies in `BotDialoguePlayer`:
  - any voice gap: 2.5s
  - combat/status category gap: 10s (for `bot.line.combat_*` paths)

If you get "No dialogue played...", wait 2.5 to 10 seconds and retry.

## Trigger Keys and Aliases

Canonical keys are from `tools/audio/batch3_phase1_manifest.tsv`.
Aliases below are also accepted by `/bot dialogue_test`.

| Canonical trigger key | Alias |
|---|---|
| `fighting_multiple_dangerous` | `combat_multi` |
| `combat_ended` | `post_combat` |
| `combat_ended_explosion` | `post_explosion` |
| `combat_ended_multiple_dangerous` | `post_combat_multi` |
| `combat_ended_single_weak` | `post_combat_single` |
| `player_hit_bot` | `ff_received` |
| `bot_hit_player` | `ff_dealt` |
| `villager_noise_nearby` | `villager` |
| `player_opens_villager_trade` | `villager_negotiate` |
| `tamed_wolf_nearby` | `wolf_nearby` |
| `wolf_takes_damage` | `wolf_hurt` |
| `tamed_animal_nearby` | `animal_nearby` |
| `random_idle_not_combat` | `ambient` |
| `in_high_threat_location` | `high_threat` |
| `scary_sound_nearby` | `scary` |
| `in_boat_not_combat` | `boat` |
| `in_boat_deep_water` | `boat_deep` |
| `in_boat_dolphin_nearby` | `boat_dolphin` |
| `boat_breaks` | `boat_break` |
| `standing_on_edge` | `precipice` |
| `safe_vista` | `vista` |
| `falling_or_elytra` | `freefall` |
| `random_ambient` | `meta` |
| `baby_zombie_on_chicken` | `meme_chicken` |
| `creeper_hiss` | `meme_creeper` |
| `world_start_or_milestone` | `meme_steve` |
| `survive_near_death_or_totem` | `meme_technoblade` |
| `lightning_at_night` | `meme_herobrine` |
| `shelter_completion` | `shelter` |

## Cadence Expectations

Local cooldown defaults:

- Combat multi: 25s.
- Post-combat buckets: 25s (combat end callout cadence).
- Friendly fire (received/dealt): 6s.
- Villager proximity and negotiate: 90s.
- Wolf nearby: 90s.
- Wolf hurt: 8s.
- Tamed animal nearby: 90s.
- Boat, precipice, high-threat, scary, ambient: 90s.
- Vista: 180s.
- Freefall/elytra: 20s.
- Meta (`random_ambient`): 12m.
- Meme triggers: 30m.
- Shelter completion: 90s.

Global anti-spam still layers on top (2.5s and selective 10s).

## Recommended Playtest Flow

1. Run static checks (audit + compile).
2. Deterministic trigger sweep using `/bot dialogue_test` with explicit `line_id`.
3. Natural scenario validation for each trigger family.
4. Regression checks for overlap IDs.
5. Record failures and retest after fixes.

## Deterministic Sweep (Manual Commands)

Replace `<bot_name>` with your bot.

### Combat Pools

```mcfunction
/bot dialogue_test <bot_name> fighting_multiple_dangerous combat_multi_not_fair
/bot dialogue_test <bot_name> fighting_multiple_dangerous combat_multi_relax
/bot dialogue_test <bot_name> fighting_multiple_dangerous combat_multi_excessive

/bot dialogue_test <bot_name> combat_ended post_combat_still_alive
/bot dialogue_test <bot_name> combat_ended post_combat_adequate

/bot dialogue_test <bot_name> combat_ended_multiple_dangerous post_combat_multi_not_easy
/bot dialogue_test <bot_name> combat_ended_multiple_dangerous post_combat_multi_need_minute

/bot dialogue_test <bot_name> combat_ended_single_weak post_combat_single_easy
/bot dialogue_test <bot_name> combat_ended_single_weak post_combat_single_feel_bad
/bot dialogue_test <bot_name> combat_ended_single_weak post_combat_single_must_hurt
/bot dialogue_test <bot_name> combat_ended_single_weak post_combat_single_inconvenience

/bot dialogue_test <bot_name> combat_ended_explosion post_explosion_bones
/bot dialogue_test <bot_name> combat_ended_explosion post_explosion_less_boom

/bot dialogue_test <bot_name> player_hit_bot ff_received_ow_that_was_you
/bot dialogue_test <bot_name> player_hit_bot ff_received_on_your_team

/bot dialogue_test <bot_name> bot_hit_player ff_dealt_panicked
/bot dialogue_test <bot_name> bot_hit_player ff_dealt_didnt_mean
```

### Villager and Pets

```mcfunction
/bot dialogue_test <bot_name> villager_noise_nearby villager_all_you_say
/bot dialogue_test <bot_name> villager_noise_nearby villager_rude
/bot dialogue_test <bot_name> villager_noise_nearby villager_few_words
/bot dialogue_test <bot_name> villager_noise_nearby villager_foreign_tongue
/bot dialogue_test <bot_name> villager_noise_nearby villager_no_idea
/bot dialogue_test <bot_name> villager_noise_nearby villager_cant_tell_apart
/bot dialogue_test <bot_name> villager_noise_nearby villager_committed_one_line
/bot dialogue_test <bot_name> villager_noise_nearby villager_nod_understand
/bot dialogue_test <bot_name> player_opens_villager_trade villager_negotiate

/bot dialogue_test <bot_name> tamed_wolf_nearby wolf_guard_duty
/bot dialogue_test <bot_name> tamed_wolf_nearby wolf_menace
/bot dialogue_test <bot_name> wolf_takes_damage wolf_leave_alone

/bot dialogue_test <bot_name> tamed_animal_nearby animal_quality
/bot dialogue_test <bot_name> tamed_animal_nearby animal_well_behaved
```

### Context / Environment

```mcfunction
/bot dialogue_test <bot_name> random_idle_not_combat ambient_bad_feeling
/bot dialogue_test <bot_name> random_idle_not_combat ambient_blame_terrain
/bot dialogue_test <bot_name> random_idle_not_combat ambient_my_job
/bot dialogue_test <bot_name> random_idle_not_combat ambient_thinking

/bot dialogue_test <bot_name> in_high_threat_location creepy_head_swivel
/bot dialogue_test <bot_name> in_high_threat_location creepy_bad_ideas_mature
/bot dialogue_test <bot_name> in_high_threat_location creepy_complaint_reality

/bot dialogue_test <bot_name> scary_sound_nearby scary_not_acknowledging
/bot dialogue_test <bot_name> scary_sound_nearby scary_hate_sound

/bot dialogue_test <bot_name> in_boat_not_combat boat_fish_size
/bot dialogue_test <bot_name> in_boat_not_combat boat_kraken
/bot dialogue_test <bot_name> in_boat_not_combat boat_beautiful_day
/bot dialogue_test <bot_name> in_boat_not_combat boat_good_fishing
/bot dialogue_test <bot_name> in_boat_not_combat boat_know_swim
/bot dialogue_test <bot_name> in_boat_deep_water boat_deep_water
/bot dialogue_test <bot_name> in_boat_dolphin_nearby boat_dolphin_escort
/bot dialogue_test <bot_name> boat_breaks boat_shipwreck_speedrun

/bot dialogue_test <bot_name> standing_on_edge precipice_you_first
/bot dialogue_test <bot_name> standing_on_edge precipice_gonna_jump
/bot dialogue_test <bot_name> standing_on_edge precipice_big_drop
/bot dialogue_test <bot_name> standing_on_edge precipice_nope
/bot dialogue_test <bot_name> standing_on_edge precipice_not_fan_gravity
/bot dialogue_test <bot_name> standing_on_edge precipice_back_up

/bot dialogue_test <bot_name> safe_vista vista_wow
/bot dialogue_test <bot_name> safe_vista vista_would_you_look
/bot dialogue_test <bot_name> safe_vista vista_built_base_here
/bot dialogue_test <bot_name> safe_vista vista_beautiful
/bot dialogue_test <bot_name> safe_vista vista_gorgeous
/bot dialogue_test <bot_name> safe_vista vista_amazing_view
/bot dialogue_test <bot_name> safe_vista vista_worth_walk
/bot dialogue_test <bot_name> safe_vista vista_could_live_here

/bot dialogue_test <bot_name> falling_or_elytra freefall_exhilarating
/bot dialogue_test <bot_name> falling_or_elytra freefall_im_a_bird
/bot dialogue_test <bot_name> falling_or_elytra freefall_falling_style
/bot dialogue_test <bot_name> falling_or_elytra freefall_yolo
/bot dialogue_test <bot_name> falling_or_elytra freefall_aaahaha
/bot dialogue_test <bot_name> falling_or_elytra freefall_woohoo
/bot dialogue_test <bot_name> falling_or_elytra freefall_regret
/bot dialogue_test <bot_name> falling_or_elytra freefall_inventory
```

### Rare / Very Rare / Meta / Shelter

```mcfunction
/bot dialogue_test <bot_name> random_ambient meta_not_robot
/bot dialogue_test <bot_name> random_ambient meta_human_laugh
/bot dialogue_test <bot_name> random_ambient meta_stop_looking

/bot dialogue_test <bot_name> baby_zombie_on_chicken meme_chicken_jockey
/bot dialogue_test <bot_name> baby_zombie_on_chicken meme_chicken_nope
/bot dialogue_test <bot_name> baby_zombie_on_chicken meme_chicken_illegal

/bot dialogue_test <bot_name> creeper_hiss meme_creeper_aw_man
/bot dialogue_test <bot_name> creeper_hiss meme_creeper_back_up
/bot dialogue_test <bot_name> creeper_hiss meme_creeper_hate_sound

/bot dialogue_test <bot_name> world_start_or_milestone meme_i_am_steve
/bot dialogue_test <bot_name> world_start_or_milestone meme_steve_adjacent

/bot dialogue_test <bot_name> survive_near_death_or_totem meme_technoblade

/bot dialogue_test <bot_name> lightning_at_night meme_herobrine_leaving
/bot dialogue_test <bot_name> lightning_at_night meme_herobrine_saw_nothing

/bot dialogue_test <bot_name> shelter_completion shelter_roof_luxury
/bot dialogue_test <bot_name> shelter_completion shelter_not_pretty
/bot dialogue_test <bot_name> shelter_completion shelter_some_problems
```

## Natural Scenario Validation

Use these after deterministic sweep confirms mapping/audio/subtitles.

### Combat

1. Multi-hostile combat:
   - Scenario: aggro 3+ hostiles near bot.
   - Expect: one `combat_multi_*` during active fight; cadence not spammy.
2. Post-combat bucket (general):
   - Scenario: normal mixed fight ends and quiets out.
   - Expect: `post_combat_*` line after end window.
3. Post-combat bucket (single weak):
   - Scenario: one weak hostile only (zombie/skeleton/spider class).
   - Expect: `post_combat_single_*`.
4. Explosion branch:
   - Scenario: creeper/explosion present in fight.
   - Expect: `post_explosion_*`.
5. Friendly fire received:
   - Scenario: player damages bot.
   - Expect: `ff_received_*`, with 6s local cadence.
6. Friendly fire dealt:
   - Scenario: bot accidentally damages player.
   - Expect: `ff_dealt_*`, with 6s local cadence.

### Villagers, Pets, Context

1. Villager proximity:
   - Scenario: bot near village/villagers.
   - Expect: `villager_noise_nearby` pool with ~90s local cooldown.
2. Villager negotiate:
   - Scenario: player opens/interacts villager trade near bot.
   - Expect: `villager_negotiate`.
3. Wolf nearby/hurt:
   - Scenario: tamed wolf near bot; then wolf takes damage.
   - Expect: `wolf_*` pools with 90s / 8s cooldowns.
4. Tamed non-wolf animal:
   - Scenario: tamed horse/cat/etc near bot.
   - Expect: `animal_*`.
5. Boat contexts:
   - Scenario: in boat, deep water, dolphin nearby, boat exit/break context.
   - Expect: correct `boat_*` pools.
6. Precipice:
   - Scenario: bot on edge with large drop ahead.
   - Expect: `precipice_*`.
7. Vista:
   - Scenario: safe high scenic location, no rain/thunder, out of combat.
   - Expect: `vista_*` at low frequency (180s cooldown).
8. Freefall/elytra:
   - Scenario: sustained fall or elytra-like descent.
   - Expect: `freefall_*`, 20s cooldown.

### Rare and Shelter

1. High threat/scary:
   - Scenario: deep dark/nether/end or scary local conditions.
   - Expect: `creepy_*` and `scary_*` buckets.
2. Meta/meme:
   - Scenario: natural conditions are intentionally rare.
   - Validate naturally only if convenient; otherwise rely on manual command path.
3. Shelter completion:
   - Scenario: successful hovel and successful burrow.
   - Expect: one `shelter_*` line after completion.

## Regression Checks (Must Remain Unchanged)

Validate existing behavior still works:

- `bot.line.discover_mineshaft`
- `bot.line.discover_spawner`
- `bot.line.weather_rain`
- `bot.line.weather_snow`
- `bot.line.weather_sunny`
- `bot.line.weather_thunder`
- `bot.line.time_sunset_soon`
- `bot.fx.hurt_grunt`

These overlap IDs were intentionally preserved in Phase 1.

## Failure Logging Template

Copy this section when reporting issues:

```text
Date/Build:
Bot name:
Trigger key:
Line ID (if forced):
Expected:
Actual:
Repro steps:
Frequency:
Notes (subtitle, sound heard, cooldown behavior):
```

## Trigger Pool Catalog (from manifest)

| Trigger key | Line IDs |
|---|---|
| `baby_zombie_on_chicken` | `meme_chicken_illegal`, `meme_chicken_jockey`, `meme_chicken_nope` |
| `boat_breaks` | `boat_shipwreck_speedrun` |
| `bot_hit_player` | `ff_dealt_didnt_mean`, `ff_dealt_panicked` |
| `combat_ended` | `post_combat_adequate`, `post_combat_still_alive` |
| `combat_ended_explosion` | `post_explosion_bones`, `post_explosion_less_boom` |
| `combat_ended_multiple_dangerous` | `post_combat_multi_need_minute`, `post_combat_multi_not_easy` |
| `combat_ended_single_weak` | `post_combat_single_easy`, `post_combat_single_feel_bad`, `post_combat_single_inconvenience`, `post_combat_single_must_hurt` |
| `creeper_hiss` | `meme_creeper_aw_man`, `meme_creeper_back_up`, `meme_creeper_hate_sound` |
| `falling_or_elytra` | `freefall_aaahaha`, `freefall_exhilarating`, `freefall_falling_style`, `freefall_im_a_bird`, `freefall_inventory`, `freefall_regret`, `freefall_woohoo`, `freefall_yolo` |
| `fighting_multiple_dangerous` | `combat_multi_excessive`, `combat_multi_not_fair`, `combat_multi_relax` |
| `in_boat_deep_water` | `boat_deep_water` |
| `in_boat_dolphin_nearby` | `boat_dolphin_escort` |
| `in_boat_not_combat` | `boat_beautiful_day`, `boat_fish_size`, `boat_good_fishing`, `boat_know_swim`, `boat_kraken` |
| `in_high_threat_location` | `creepy_bad_ideas_mature`, `creepy_complaint_reality`, `creepy_head_swivel` |
| `lightning_at_night` | `meme_herobrine_leaving`, `meme_herobrine_saw_nothing` |
| `player_hit_bot` | `ff_received_on_your_team`, `ff_received_ow_that_was_you` |
| `player_opens_villager_trade` | `villager_negotiate` |
| `random_ambient` | `meta_human_laugh`, `meta_not_robot`, `meta_stop_looking` |
| `random_idle_not_combat` | `ambient_bad_feeling`, `ambient_blame_terrain`, `ambient_my_job`, `ambient_thinking` |
| `safe_vista` | `vista_amazing_view`, `vista_beautiful`, `vista_built_base_here`, `vista_could_live_here`, `vista_gorgeous`, `vista_worth_walk`, `vista_would_you_look`, `vista_wow` |
| `scary_sound_nearby` | `scary_hate_sound`, `scary_not_acknowledging` |
| `shelter_completion` | `shelter_not_pretty`, `shelter_roof_luxury`, `shelter_some_problems` |
| `standing_on_edge` | `precipice_back_up`, `precipice_big_drop`, `precipice_gonna_jump`, `precipice_nope`, `precipice_not_fan_gravity`, `precipice_you_first` |
| `survive_near_death_or_totem` | `meme_technoblade` |
| `tamed_animal_nearby` | `animal_quality`, `animal_well_behaved` |
| `tamed_wolf_nearby` | `wolf_guard_duty`, `wolf_menace` |
| `villager_noise_nearby` | `villager_all_you_say`, `villager_cant_tell_apart`, `villager_committed_one_line`, `villager_few_words`, `villager_foreign_tongue`, `villager_no_idea`, `villager_nod_understand`, `villager_rude` |
| `wolf_takes_damage` | `wolf_leave_alone` |
| `world_start_or_milestone` | `meme_i_am_steve`, `meme_steve_adjacent` |

## Suggested Next System Improvements

1. Add `/bot dialogue_test list` and `/bot dialogue_test list <trigger_key>` to avoid checking docs for valid keys.
2. Add `/bot dialogue_test sweep <bot> <trigger_key>` to iterate all line IDs in that pool with built-in spacing.
3. Add `/bot dialogue_debug on` overlay/log that prints trigger key, chosen line ID, and cooldown-rejected reason.
4. Add telemetry counters (`attempted`, `played`, `throttled`, `cooldown_blocked`) per trigger and per line.
5. Add a temporary operator-only "test mode" that relaxes local cooldowns while still respecting safety limits.
6. Add a small automated integration test that parses `batch3_phase1_manifest.tsv` and validates all `line_id` values are routable by at least one trigger path.
7. Produce a coverage report for mapping gaps: emitted text without audio mapping, registered events without trigger usage, and orphaned generated clips.
