# Audio Files for Bot Dialogue System

⚠️ **AUDIO IS MOSTLY COMPLETE — SOME NEW LINES STILL PENDING**

This document tracks dialogue audio for the AI Player mod.

## Batch 3 Phase 1 Status (January 22, 2026 source)

- Source of truth: `../gemini_projects/ai-player-dialogue/january_2026_batch3/AUDIO_INDEX.md`
- Phase 1 scope integrated: **87 non-topic IDs** and **261 `.ogg` clips** (3 variants each)
- Phase 2A integrated: **105 `topic_*` IDs** and **315 `.ogg` clips**
- Overlap refresh integrated: remaining overlap filepaths mapped to source canonical names
- Batch 3 source coverage: **618 / 618** clips mapped from `output_ogg`
- Deferred by design: side-quest dialogue audio (will be generated/mapped with side-quest overhaul)
- Overlap IDs intentionally preserved as-is (no trigger/mapping replacement in Phase 1):
  - `bot.line.discover_mineshaft`, `bot.line.discover_spawner`
  - `bot.line.weather_rain`, `bot.line.weather_snow`, `bot.line.weather_sunny`, `bot.line.weather_thunder`
  - `bot.line.time_sunset_soon`
  - `bot.fx.hurt_grunt`

Additional implementation details and trigger matrix are tracked in `docs/audio/BATCH3_STATUS.md`.

## New Audio Needed (January 2026 additions)

These were added after the previous “complete” pass. **Code support is in place** (sound events registered, chat→sound mapping added, subtitles added). The **Status** column reflects whether the corresponding `.ogg` assets are present under `src/main/resources/assets/ai-player/sounds/dialogue/` and referenced by `sounds.json`.

| Sound event id | sounds.json entry (suggested file) | Chat text | Status |
|---|---|---|---|
| `bot.line.combat_missed_1` | `ai-player:dialogue/combat_missed_i_missed__01` | "I missed!" | ✅ Integrated |
| `bot.line.combat_missed_2` | `ai-player:dialogue/combat_missed_dang_missed__01` | "Dang, missed!" | ✅ Integrated |
| `bot.line.combat_missed_3` | `ai-player:dialogue/combat_missed_ugh_missed__01` | "Ugh. Missed." | ✅ Integrated |
| `bot.line.combat_missed_4` | `ai-player:dialogue/combat_missed_went_wide__01` | "That one went wide!" | ✅ Integrated |
| `bot.line.craft_recipe_unlocked_1` | `ai-player:dialogue/craft_recipe_unlocked_1__01` | "New recipe unlocked." | ✅ Integrated |
| `bot.line.craft_recipe_unlocked_2` | `ai-player:dialogue/craft_recipe_unlocked_2__01` | "Nice. New recipe." | ✅ Integrated |
| `bot.line.craft_recipe_unlocked_3` | `ai-player:dialogue/craft_recipe_unlocked_3__01` | "That's a new one." | ✅ Integrated |
| `bot.line.craft_recipe_unlocked_4` | `ai-player:dialogue/craft_recipe_unlocked_4__01` | "Good. More options now." | ✅ Integrated |
| `bot.line.craft_recipe_unlocked_5` | `ai-player:dialogue/craft_recipe_unlocked_5__01` | "Another recipe for the book." | ✅ Integrated |
| `bot.line.craft_recipe_unlocked_6` | `ai-player:dialogue/craft_recipe_unlocked_6__01` | "That might come in handy." | ✅ Integrated |

### Foliage obstruction / "stuck in branches" (new)

These are triggered as **overhead (in-world) companion dialogue** when the bot gets blocked by natural leaves/foliage. (Not currently chat-driven.)

| Sound event id | sounds.json entry (suggested file) | Text | Status |
|---|---|---|---|
| `bot.line.foliage_stuck_1` | `ai-player:dialogue/foliage_stuck_these_branches_are_thick__01` | "These branches are thick!" | ✅ Integrated |
| `bot.line.foliage_stuck_2` | `ai-player:dialogue/foliage_stuck_hold_on_stuck_in_branches__01` | "Hold on — stuck in some branches." | ✅ Integrated |
| `bot.line.foliage_stuck_3` | `ai-player:dialogue/foliage_stuck_cant_get_through_these_leaves__01` | "Can't get through these leaves." | ✅ Integrated |
| `bot.line.foliage_stuck_4` | `ai-player:dialogue/foliage_stuck_foliage_got_me__01` | "Just a sec… foliage's got me." | ✅ Integrated |
| `bot.line.foliage_stuck_5` | `ai-player:dialogue/foliage_stuck_ugh_leaves_in_the_way__01` | "Ugh. Leaves in the way." | ✅ Integrated |

### Sweet Berry Bush reactions (new)

Triggered as **overhead (in-world) companion dialogue**:

- **Sting**: when the bot walks through/into a Sweet Berry Bush.
- **Edible** (rare): when the bot is adjacent to Sweet Berry Bushes.

| Sound event id | sounds.json entry (suggested file) | Text | Status |
|---|---|---|---|
| `bot.line.berry_bush_sting_1` | `ai-player:dialogue/berry_bush_ouch__01` | "Ouch!" | ✅ Integrated |
| `bot.line.berry_bush_sting_2` | `ai-player:dialogue/berry_bush_these_are_thorny__01` | "These are thorny!" | ✅ Integrated |
| `bot.line.berry_bush_sting_3` | `ai-player:dialogue/berry_bush_yowch__01` | "Yowch!" | ✅ Integrated |
| `bot.line.berry_bush_edible_1` | `ai-player:dialogue/berry_bush_these_are_edible_i_think__01` | "These are edible...I think." | ✅ Integrated |

### Mining POI callouts (new)

Mining-specific discoveries emitted by `MiningHazardDetector` now have dedicated voiced lines.

| Sound event id | sounds.json entry (suggested file) | Chat text | Status |
|---|---|---|---|
| `bot.line.discover_mineshaft` | `ai-player:dialogue/discover_mineshaft__01` (+ `__02`) | "I found a mineshaft!" | ✅ Integrated |
| `bot.line.discover_spawner` | `ai-player:dialogue/discover_spawner__01` (+ `__02`) | "I found a mob spawner!" | ✅ Integrated |

### Weather chatter + time-of-day reminders (new)

Overworld ambient callouts triggered when the bot is above ground (sky visible). These should have **lots of variants** so they don't get repetitive.

| Sound event id | sounds.json entry (suggested file) | Trigger | Status |
|---|---|---|---|
| `bot.line.weather_rain` | `ai-player:dialogue/weather_rain_01__01` (+ `_02__01`…`_06__01`) | Weather changed to rain | ✅ Integrated |
| `bot.line.weather_snow` | `ai-player:dialogue/weather_snow_01__01` (+ `_02__01`…`_06__01`) | Weather changed to snow (cold biome) | ✅ Integrated |
| `bot.line.weather_thunder` | `ai-player:dialogue/weather_thunder_01__01` (+ `_02__01`…`_06__01`) | Weather changed to thunderstorm | ✅ Integrated |
| `bot.line.weather_sunny` | `ai-player:dialogue/weather_sunny_01__01` (+ `_02__01`…`_06__01`) | Weather cleared (sometimes) | ✅ Integrated |
| `bot.line.time_sunset_soon` | `ai-player:dialogue/time_sunset_soon_01__01` (+ `_02__01`…`_06__01`) | After noon, if not near base or recent bed | ✅ Integrated |

### Nonverbal hurt grunts (new)

Short nonverbal reactions when the bot is hit. Should be **wordless** (grunt/breath/effort sounds). Rate-limited in code (~1s).

| Sound event id | sounds.json entry (suggested file) | Trigger | Status |
|---|---|---|---|
| `bot.fx.hurt_grunt` | `ai-player:dialogue/hurt_grunt_01__01` (+ `_02__01`…`_08__01`) | On damage taken | ✅ Integrated |

### The Nether & The End ambience (new)

Idle/environment chatter that only plays while bots are in the Nether or The End.

| Sound event id | sounds.json entry (suggested file) | Chat text | Status |
|---|---|---|---|
| `bot.line.nether_enter_1` | `ai-player:dialogue/nether_enter_stay_close__01` | "Nether... stay close." | ✅ Integrated |
| `bot.line.nether_enter_2` | `ai-player:dialogue/nether_enter_watch_your_step__01` | "We're in the Nether. Watch your step." | ✅ Integrated |
| `bot.line.nether_hot` | `ai-player:dialogue/nether_hot_real_hot__01` | "It's hot. Real hot." | ✅ Integrated |
| `bot.line.nether_lava` | `ai-player:dialogue/nether_lava_everywhere__01` | "Lava everywhere. Careful." | ✅ Integrated |
| `bot.line.nether_ghasts` | `ai-player:dialogue/nether_ghasts_keep_an_eye_out__01` | "Keep an eye out for ghasts." | ✅ Integrated |
| `bot.line.nether_piglins` | `ai-player:dialogue/nether_piglins_watching__01` | "Piglins are watching us." | ✅ Integrated |
| `bot.line.nether_soul_sand` | `ai-player:dialogue/nether_soul_sand_hate_it__01` | "This soul sand... I hate it." | ✅ Integrated |
| `bot.line.nether_fortress` | `ai-player:dialogue/nether_fortress_spotted__01` | "That looks like a fortress." | ✅ Integrated |
| `bot.line.nether_bastion` | `ai-player:dialogue/nether_blackstone_bastion_nearby__01` | "Lots of blackstone... could be a bastion nearby." | ✅ Integrated |
| `bot.line.nether_biome_crimson_forest` | `ai-player:dialogue/nether_biome_crimson_forest__01` | "Crimson forest... red as far as I can see." | ✅ Integrated |
| `bot.line.nether_biome_warped_forest` | `ai-player:dialogue/nether_biome_warped_forest__01` | "Warped forest... keep your eyes open." | ✅ Integrated |
| `bot.line.nether_biome_soul_sand_valley` | `ai-player:dialogue/nether_biome_soul_sand_valley__01` | "Soul Sand Valley... I don't like the sound of it." | ✅ Integrated |
| `bot.line.nether_biome_basalt_deltas` | `ai-player:dialogue/nether_biome_basalt_deltas__01` | "Basalt deltas. Lots of sharp stone." | ✅ Integrated |
| `bot.line.nether_biome_wastes` | `ai-player:dialogue/nether_biome_wastes__01` | "Nether wastes... heat and ash." | ✅ Integrated |
| `bot.line.end_enter_1` | `ai-player:dialogue/end_enter_this_is_the_end__01` | "This is The End..." | ✅ Integrated |
| `bot.line.end_enter_2` | `ai-player:dialogue/end_enter_dont_look_down__01` | "Don't look down. Just... don't." | ✅ Integrated |
| `bot.line.end_eerie` | `ai-player:dialogue/end_eerie_this_place_feels_wrong__01` | "This place feels wrong." | ✅ Integrated |
| `bot.line.end_void` | `ai-player:dialogue/end_void_one_step_and_its_over__01` | "One step and it's over." | ✅ Integrated |
| `bot.line.end_islands` | `ai-player:dialogue/end_islands_floating_in_void__01` | "Islands floating in the void..." | ✅ Integrated |
| `bot.line.end_chorus` | `ai-player:dialogue/end_chorus_fruit_nearby__01` | "Chorus fruit nearby." | ✅ Integrated |
| `bot.line.end_endermen` | `ai-player:dialogue/end_endermen_everywhere__01` | "Endermen everywhere..." | ✅ Integrated |
| `bot.line.end_keep_eyes_up` | `ai-player:dialogue/end_keep_your_eyes_up__01` | "Keep your eyes up. Stay sharp." | ✅ Integrated |
| `bot.line.end_city` | `ai-player:dialogue/end_city_nearby__01` | "End City nearby." | ✅ Integrated |
| `bot.line.end_gateway` | `ai-player:dialogue/end_gateway_spotted__01` | "End gateway spotted." | ✅ Integrated |
| `bot.line.end_biome_main_island` | `ai-player:dialogue/end_biome_main_island__01` | "Main island. Stay focused." | ✅ Integrated |
| `bot.line.end_biome_highlands` | `ai-player:dialogue/end_biome_highlands__01` | "End highlands ahead." | ✅ Integrated |
| `bot.line.end_biome_midlands` | `ai-player:dialogue/end_biome_midlands__01` | "End midlands... still feels like the void." | ✅ Integrated |
| `bot.line.end_biome_barrens` | `ai-player:dialogue/end_biome_barrens__01` | "End barrens... nothing out here." | ✅ Integrated |
| `bot.line.end_biome_small_islands` | `ai-player:dialogue/end_biome_small_islands__01` | "Small islands... careful crossing." | ✅ Integrated |

### Creature-specific kill callouts (new)

These add extra variety for kill confirmations and make them feel more contextual. Two variants each.

| Sound event id | sounds.json entry (suggested file) | Chat text | Status |
|---|---|---|---|
| `bot.line.combat_kill_creeper_1` | `ai-player:dialogue/combat_kill_creeper_down__01` | "Creeper down." | ✅ Integrated |
| `bot.line.combat_kill_creeper_2` | `ai-player:dialogue/combat_kill_creeper_no_boom__01` | "No boom today." | ✅ Integrated |
| `bot.line.combat_kill_skeleton_1` | `ai-player:dialogue/combat_kill_skeleton_down__01` | "Skeleton down." | ✅ Integrated |
| `bot.line.combat_kill_skeleton_2` | `ai-player:dialogue/combat_kill_skeleton_bones_scattered__01` | "Bones scattered." | ✅ Integrated |
| `bot.line.combat_kill_zombie_1` | `ai-player:dialogue/combat_kill_zombie_down__01` | "Zombie down." | ✅ Integrated |
| `bot.line.combat_kill_zombie_2` | `ai-player:dialogue/combat_kill_zombie_back_in_ground__01` | "Back in the ground." | ✅ Integrated |
| `bot.line.combat_kill_spider_1` | `ai-player:dialogue/combat_kill_spider_down__01` | "Spider down." | ✅ Integrated |
| `bot.line.combat_kill_spider_2` | `ai-player:dialogue/combat_kill_spider_webs_wont_save_you__01` | "Webs won't save you." | ✅ Integrated |
| `bot.line.combat_kill_enderman_1` | `ai-player:dialogue/combat_kill_enderman_down__01` | "Enderman down." | ✅ Integrated |
| `bot.line.combat_kill_enderman_2` | `ai-player:dialogue/combat_kill_enderman_didnt_blink__01` | "Glad I didn't blink." | ✅ Integrated |
| `bot.line.combat_kill_witch_1` | `ai-player:dialogue/combat_kill_witch_down__01` | "Witch down." | ✅ Integrated |
| `bot.line.combat_kill_witch_2` | `ai-player:dialogue/combat_kill_witch_potion_solved__01` | "Potion problem solved." | ✅ Integrated |
| `bot.line.combat_kill_slime_1` | `ai-player:dialogue/combat_kill_slime_down__01` | "Slime down." | ✅ Integrated |
| `bot.line.combat_kill_slime_2` | `ai-player:dialogue/combat_kill_slime_back_to_goo__01` | "Back to goo." | ✅ Integrated |
| `bot.line.combat_kill_pillager_1` | `ai-player:dialogue/combat_kill_pillager_down__01` | "Pillager down." | ✅ Integrated |
| `bot.line.combat_kill_pillager_2` | `ai-player:dialogue/combat_kill_pillager_one_less_raider__01` | "One less raider." | ✅ Integrated |
| `bot.line.combat_kill_vindicator_1` | `ai-player:dialogue/combat_kill_vindicator_down__01` | "Vindicator down." | ✅ Integrated |
| `bot.line.combat_kill_vindicator_2` | `ai-player:dialogue/combat_kill_vindicator_axe_is_out__01` | "Axe is out." | ✅ Integrated |
| `bot.line.combat_kill_evoker_1` | `ai-player:dialogue/combat_kill_evoker_down__01` | "Evoker down." | ✅ Integrated |
| `bot.line.combat_kill_evoker_2` | `ai-player:dialogue/combat_kill_evoker_no_more_tricks__01` | "No more tricks." | ✅ Integrated |
| `bot.line.combat_kill_magma_cube_1` | `ai-player:dialogue/combat_kill_magma_cube_down__01` | "Magma cube down." | ✅ Integrated |
| `bot.line.combat_kill_magma_cube_2` | `ai-player:dialogue/combat_kill_magma_cube_back_to_magma__01` | "Back to magma." | ✅ Integrated |
| `bot.line.combat_kill_ravager_1` | `ai-player:dialogue/combat_kill_ravager_down__01` | "Ravager down." | ✅ Integrated |
| `bot.line.combat_kill_ravager_2` | `ai-player:dialogue/combat_kill_ravager_beast_down__01` | "Beast is down." | ✅ Integrated |
| `bot.line.combat_kill_vex_1` | `ai-player:dialogue/combat_kill_vex_down__01` | "Vex down." | ✅ Integrated |
| `bot.line.combat_kill_vex_2` | `ai-player:dialogue/combat_kill_vex_no_more_flying_blades__01` | "No more flying blades." | ✅ Integrated |

**Target location for new files:** `src/main/resources/assets/ai-player/sounds/dialogue/`

## Status: Mostly complete (January 2026)

The January 2026 audio batch has been fully integrated:
- **195 new audio files** converted from WAV to OGG
- **65 new dialogue lines** across combat, environment, banter, and mount categories
- **Total dialogue files**: 494 (.ogg format)
- **Sound events registered**: ~260

## January 2026 Audio (INTEGRATED ✅)

### Combat Callouts ✅

| Type | Lines | Status |
|------|-------|--------|
| Threat Detection | "Heads up!", "Enemy spotted!", "Hostile incoming!" | ✅ Integrated |
| Engagement | "Engaging!", "Taking it out!", "On it!" | ✅ Integrated |
| Damage Taken | "Ow!", "Taking damage!", "Getting hit here!" | ✅ Integrated |
| Kill Confirmation | "Got it!", "Target down!", "One less to worry about." | ✅ Integrated |
| Combat Clear | "All clear.", "Area secure.", "That's the last of them." | ✅ Integrated |
| Player Hit Reaction | "Hey! What was that for?!", "Ow! Watch it!", "Did you just hit me?!" | ✅ Integrated |

### Environment-Specific Dialogue ✅

| Environment | Lines | Status |
|-------------|-------|--------|
| Amethyst Geode | "These crystals are beautiful.", "So sparkly down here.", "We found a geode!" | ✅ Integrated |
| Bats | "Ah! Bats!", "I hear wings flapping.", "Bats give me the creeps." | ✅ Integrated |
| Dripstone | "Careful, those are sharp.", "Pointy stalactites everywhere.", "I can hear dripping." | ✅ Integrated |
| Deepslate | "It's cold down here.", "We're really deep now.", "This stone feels ancient." | ✅ Integrated |

### Adventure Banter ✅

- "Look out, creeper! Haha, just kidding." ✅
- "Relax. If I yell run, then worry." ✅
- "I've got your back." ✅
- "Stay sharp out here." ✅

### Mount/Lead Handling ✅

| Category | Lines | Status |
|----------|-------|--------|
| Horse Hurt | 3 variants | ✅ Integrated |
| No Apples | 3 variants | ✅ Integrated |
| No Suitable Food | 3 variants | ✅ Integrated |
| No Lead | 3 variants | ✅ Integrated |
| Can't Grab Lead | 3 variants | ✅ Integrated |
| No Fence | 3 variants | ✅ Integrated |
| Lost Track | 3 variants | ✅ Integrated |
| Horse Gone | 3 variants | ✅ Integrated |
| Lead Snapped | 3 variants | ✅ Integrated |
| Lead Reattach Fail | 3 variants | ✅ Integrated |

### Touch Variant ✅

- "Hmm?" (3 variants) ✅

## Sound Categories Summary

| Category | Count | Examples |
|----------|-------|----------|
| Greeting | 4 | hey, good_to_see, welcome_back, there_you_are |
| Touch | 5 | hmm, yeah, what_need, need_something |
| Status | 7 | hungry, find_food, snack_time, need_breather, not_best, too_many_hits |
| Idle | 5 | all_quiet, still_standing, taking_it_easy, here_if_needed, enjoying_calm |
| Context | 7 | fish_earlier, smells_fish, fish_cooperating, warming_earlier, campfire_wonders |
| Skill | 15 | fishing_*, woodcut_*, hangout_*, sleep_* |
| Mode | 15 | follow_*, guard_*, patrol_*, return_*, stay_* |
| Warning | 6 | suffocating, drop_ahead, stuck, banged_up, not_full_strength |
| **Combat** | **29** | threat_*, engage_*, damage_*, kill_*, clear_*, player_hit_* |
| **Banter** | **4** | creeper_kidding, if_yell_run, got_your_back, stay_sharp |
| **Mount** | **30** | horse_hurt, no_apples, no_lead, lost_horse, lead_snapped |
| **Environment** | **12** | amethyst_*, bat_*, dripstone_*, deepslate_* |
| Confirm | 3 | on_it, hold_off, ask_yesno |
| Discover | 12 | diamonds, ancient_debris, emeralds, gold, iron, coal |
| Hazard | 3 | lava, water, no_torches |
| Hunger | 3 | dying, starving, warning |
| Eating | 4 | no_food, still_hungry, progress, done |
| Death | 1 | resume_ask |
| Move | 5 | cant_reach, blocked, walking_to_you, target_lost |
| Inventory | 3 | full, dont_have, give_item |
| Craft | 3 | need_table, unknown, cant_place |
| Lost/Found | 12 | over_here, hello, help, finally, thank_goodness |
| Dark | 5 | cant_see, where_are_you, need_light, too_dark, torch_please |
| Wildlife | 7 | heard_bird, saw_cow, pig_nearby, sheep_around, heard_wolf |

## Features Implemented

1. ✅ **Reactive Dialogue** - Sounds play automatically when bot speaks
2. ✅ **Context-Aware Chatter** - Ambient sounds based on health/hunger state
3. ✅ **Subtitle System** - Action bar subtitles for ambient chatter
4. ✅ **3D Positional Audio** - 8-block attenuation distance
5. ✅ **Config Toggle** - Per-bot voicedDialogue setting
6. ✅ **Combat Callout System** - Real-time callouts during fights
7. ✅ **Environment Detection** - Special dialogue for unique biomes/blocks

## File Locations

- **Audio Files**: `src/main/resources/assets/ai-player/sounds/dialogue/`
- **Sound Registry**: `sounds.json`
- **Sound Events**: `BotDialogueSounds.java`
- **Text Mappings**: `DialogueTextMapper.java`
- **Subtitles**: `BotDialoguePlayer.java`

---

## Phantom / Village audio status (January 2026)

We currently do **not** ship any dedicated phantom/village/villager/golem voice clips.

- **Phantoms:** implemented using existing combat threat voice (`bot.line.combat_threat_detected`) plus overhead text (“Phantom!”).
- **Village proximity:** implemented using overhead text (“Village nearby.”) plus an existing generic discovery voice (`bot.line.discover_structure`) as a placeholder.

If we want bespoke audio, we should add new sound events (e.g. `bot.line.phantom_spotted`, `bot.line.village_entered`) and record corresponding `.ogg` files.

## Asset audit (sounds.json vs sounds/dialogue)

Automated check results:

- **Missing referenced `.ogg` clips:** 42
- **Unused `.ogg` clips present but not referenced:** 1

### Missing referenced clips (currently referenced by `sounds.json` but not present as `.ogg`)

- `dialogue/discover_mineshaft__01`
- `dialogue/discover_mineshaft__02`
- `dialogue/discover_spawner__01`
- `dialogue/discover_spawner__02`
- `dialogue/hurt_grunt__01` … `dialogue/hurt_grunt__08`
- `dialogue/time_sunset_soon__01` … `dialogue/time_sunset_soon__06`
- `dialogue/weather_rain__01` … `dialogue/weather_rain__06`
- `dialogue/weather_snow__01` … `dialogue/weather_snow__06`
- `dialogue/weather_sunny__01` … `dialogue/weather_sunny__06`
- `dialogue/weather_thunder__01` … `dialogue/weather_thunder__06`

### Unused dialogue clips (present as `.ogg` but not referenced by `sounds.json`)

- `dialogue/test_line__01`

*Last updated: January 12, 2026*
