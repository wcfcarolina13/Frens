# Dialogue Coverage Report

Generated: `2026-02-13 19:23:43Z`

## Inputs

- Repo root: `/Users/roti/AI-Player-checkpoint`
- Source ogg dir: `/Users/roti/gemini_projects/ai-player-dialogue/january_2026_batch3/output_ogg`
- sounds.json: `src/main/resources/assets/ai-player/sounds.json`
- BotDialogueSounds: `src/main/java/net/shasankp000/ChatUtils/BotDialogueSounds.java`
- DialogueTextMapper: `src/main/java/net/shasankp000/ChatUtils/DialogueTextMapper.java`
- Phase1 manifest: `tools/audio/batch3_phase1_manifest.tsv`
- Topic manifest: `tools/audio/batch3_topic_manifest.tsv`
- Log file: `/Users/roti/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/logs/latest.log`

## Summary

- Source clips (`output_ogg`): **618**
- Source clips mapped by sounds.json: **618**
- Source clips unmapped: **0**
- Declared line/fx constants: **522**
- Declared line/fx constants unreferenced outside declarations: **0**
- Declared events missing in sounds.json: **0**
- sounds.json events missing in BotDialogueSounds: **0**
- Batch3 manifest rows checked: **192**
- Batch3 rows missing sounds.json event: **0**
- Batch3 rows direct-routed via trigger services: **192**
- Batch3 rows exact text-mapped in DialogueTextMapper: **105**
- Batch3 rows with no direct route and no exact map (unreachable): **0**
- Batch3 rows with intentional text-alias mapping: **1**
- Batch3 rows exact-mapped to wrong constant: **0**
- Batch3 target files missing in repo: **0**
- Log lines with `No sound mapping for message`: **2** total / **2** unique

## Unmapped Source Clips

- None

## Unreferenced Line/Fx Constants

- None

## Constants Referenced Only In Subtitles

- `LINE_COMBAT_ATTACKING` -> `bot.line.combat_attacking`
- `LINE_COMBAT_CLEAR` -> `bot.line.combat_clear`
- `LINE_COMBAT_KILL` -> `bot.line.combat_kill`
- `LINE_COMBAT_PLAYER_HIT` -> `bot.line.combat_player_hit`
- `LINE_MOUNT_BANGED_UP` -> `bot.line.mount_banged_up`
- `LINE_MOUNT_CANT_FIND_FOOD` -> `bot.line.mount_cant_find_food`
- `LINE_MOUNT_CANT_FIND_HORSE` -> `bot.line.mount_cant_find_horse`
- `LINE_MOUNT_COULDNT_GET_LEAD` -> `bot.line.mount_couldnt_get_lead`
- `LINE_MOUNT_HOLD_LEAD_UNTIL_TIE` -> `bot.line.mount_hold_lead_until_tie`
- `LINE_MOUNT_LEAD_BROKE_FALL` -> `bot.line.mount_lead_broke_fall`
- `LINE_MOUNT_LEAD_NOT_ACCESSIBLE` -> `bot.line.mount_lead_not_accessible`
- `LINE_MOUNT_LEAD_SNAPPED_ON_DROP` -> `bot.line.mount_lead_snapped_on_drop`
- `LINE_MOUNT_LOST_HORSE_LEAD` -> `bot.line.mount_lost_horse_lead`
- `LINE_MOUNT_LOST_LEADING` -> `bot.line.mount_lost_leading`
- `LINE_MOUNT_MISSING_LEAD` -> `bot.line.mount_missing_lead`
- `LINE_MOUNT_MOUNT_GONE` -> `bot.line.mount_mount_gone`
- `LINE_MOUNT_MY_HORSE_HURT` -> `bot.line.mount_my_horse_hurt`
- `LINE_MOUNT_NO_APPLES_ON_ME` -> `bot.line.mount_no_apples_on_me`
- `LINE_MOUNT_NO_FEED` -> `bot.line.mount_no_feed`
- `LINE_MOUNT_NO_FENCE_NEARBY` -> `bot.line.mount_no_fence_nearby`
- `LINE_MOUNT_NO_SPARE_LEADS` -> `bot.line.mount_no_spare_leads`
- `LINE_MOUNT_OUT_OF_APPLES` -> `bot.line.mount_out_of_apples`
- `LINE_MOUNT_OUT_OF_LEADS` -> `bot.line.mount_out_of_leads`

## Registration Mismatches

### Declared But Missing In sounds.json

- None

### sounds.json But Missing In BotDialogueSounds

- None

## Batch3 Mapping Integrity

### Missing sounds.json Events

- None

### Direct-Routed Rows

- `ambient_bad_feeling`
- `ambient_blame_terrain`
- `ambient_my_job`
- `ambient_thinking`
- `animal_quality`
- `animal_well_behaved`
- `boat_beautiful_day`
- `boat_deep_water`
- `boat_dolphin_escort`
- `boat_fish_size`
- `boat_good_fishing`
- `boat_know_swim`
- `boat_kraken`
- `boat_shipwreck_speedrun`
- `combat_multi_excessive`
- `combat_multi_not_fair`
- `combat_multi_relax`
- `creepy_bad_ideas_mature`
- `creepy_complaint_reality`
- `creepy_head_swivel`
- `ff_dealt_didnt_mean`
- `ff_dealt_panicked`
- `ff_received_on_your_team`
- `ff_received_ow_that_was_you`
- `freefall_aaahaha`
- `freefall_exhilarating`
- `freefall_falling_style`
- `freefall_im_a_bird`
- `freefall_inventory`
- `freefall_regret`
- `freefall_woohoo`
- `freefall_yolo`
- `meme_chicken_illegal`
- `meme_chicken_jockey`
- `meme_chicken_nope`
- `meme_creeper_aw_man`
- `meme_creeper_back_up`
- `meme_creeper_hate_sound`
- `meme_herobrine_leaving`
- `meme_herobrine_saw_nothing`
- `meme_i_am_steve`
- `meme_steve_adjacent`
- `meme_technoblade`
- `meta_human_laugh`
- `meta_not_robot`
- `meta_stop_looking`
- `post_combat_adequate`
- `post_combat_multi_need_minute`
- `post_combat_multi_not_easy`
- `post_combat_single_easy`
- `post_combat_single_feel_bad`
- `post_combat_single_inconvenience`
- `post_combat_single_must_hurt`
- `post_combat_still_alive`
- `post_explosion_bones`
- `post_explosion_less_boom`
- `precipice_back_up`
- `precipice_big_drop`
- `precipice_gonna_jump`
- `precipice_nope`
- `precipice_not_fan_gravity`
- `precipice_you_first`
- `scary_hate_sound`
- `scary_not_acknowledging`
- `shelter_not_pretty`
- `shelter_roof_luxury`
- `shelter_some_problems`
- `topic_ancient_city_ask_1`
- `topic_ancient_city_ask_2`
- `topic_ancient_city_first`
- `topic_ancient_city_memory`
- `topic_bastion_ask_1`
- `topic_bastion_ask_2`
- `topic_bastion_first`
- `topic_bastion_memory`
- `topic_camel_ask_1`
- `topic_camel_ask_2`
- `topic_camel_first_1`
- `topic_camel_first_2`
- `topic_camel_memory`
- `topic_deep_dark_ask_1`
- `topic_deep_dark_ask_2`
- `topic_deep_dark_ask_3`
- `topic_deep_dark_first`
- `topic_deep_dark_memory`
- `topic_desert_ask_1`
- `topic_desert_ask_2`
- `topic_desert_first`
- `topic_desert_memory`
- `topic_donkey_ask_1`
- `topic_donkey_first`
- `topic_donkey_memory`
- `topic_elytra_ask_1`
- `topic_elytra_ask_2`
- `topic_elytra_first`
- `topic_elytra_memory`
- `topic_enchanting_ask_1`
- `topic_enchanting_ask_2`
- `topic_enchanting_ask_3`
- `topic_enchanting_first`
- `topic_enchanting_memory`
- `topic_end_ask_1`
- `topic_end_ask_2`
- `topic_end_first`
- `topic_end_memory`
- `topic_horse_ask_1`
- `topic_horse_ask_2`
- `topic_horse_first_1`
- `topic_horse_first_2`
- `topic_horse_memory`
- `topic_jungle_ask_1`
- `topic_jungle_ask_2`
- `topic_jungle_first`
- `topic_jungle_memory`
- `topic_library_ask_1`
- `topic_library_ask_2`
- `topic_library_ask_3`
- `topic_library_first`
- `topic_library_memory`
- `topic_llama_ask_1`
- `... (+72 more)`

### EXACT_MAP Rows

- `topic_ancient_city_ask_1`
- `topic_ancient_city_ask_2`
- `topic_ancient_city_first`
- `topic_ancient_city_memory`
- `topic_bastion_ask_1`
- `topic_bastion_ask_2`
- `topic_bastion_first`
- `topic_bastion_memory`
- `topic_camel_ask_1`
- `topic_camel_ask_2`
- `topic_camel_first_1`
- `topic_camel_first_2`
- `topic_camel_memory`
- `topic_deep_dark_ask_1`
- `topic_deep_dark_ask_2`
- `topic_deep_dark_ask_3`
- `topic_deep_dark_first`
- `topic_deep_dark_memory`
- `topic_desert_ask_1`
- `topic_desert_ask_2`
- `topic_desert_first`
- `topic_desert_memory`
- `topic_donkey_ask_1`
- `topic_donkey_first`
- `topic_donkey_memory`
- `topic_elytra_ask_1`
- `topic_elytra_ask_2`
- `topic_elytra_first`
- `topic_elytra_memory`
- `topic_enchanting_ask_1`
- `topic_enchanting_ask_2`
- `topic_enchanting_ask_3`
- `topic_enchanting_first`
- `topic_enchanting_memory`
- `topic_end_ask_1`
- `topic_end_ask_2`
- `topic_end_first`
- `topic_end_memory`
- `topic_horse_ask_1`
- `topic_horse_ask_2`
- `topic_horse_first_1`
- `topic_horse_first_2`
- `topic_horse_memory`
- `topic_jungle_ask_1`
- `topic_jungle_ask_2`
- `topic_jungle_first`
- `topic_jungle_memory`
- `topic_library_ask_1`
- `topic_library_ask_2`
- `topic_library_ask_3`
- `topic_library_first`
- `topic_library_memory`
- `topic_llama_ask_1`
- `topic_llama_ask_2`
- `topic_llama_ask_3`
- `topic_llama_first`
- `topic_llama_memory`
- `topic_minecart_ask_1`
- `topic_minecart_ask_2`
- `topic_minecart_first`
- `topic_minecart_memory`
- `topic_mushroom_ask_1`
- `topic_mushroom_ask_2`
- `topic_mushroom_first`
- `topic_mushroom_memory`
- `topic_nether_ask_1`
- `topic_nether_ask_2`
- `topic_nether_first`
- `topic_nether_fortress_ask_1`
- `topic_nether_fortress_ask_2`
- `topic_nether_fortress_first`
- `topic_nether_fortress_memory`
- `topic_nether_memory`
- `topic_ruined_portal_ask_1`
- `topic_ruined_portal_ask_2`
- `topic_ruined_portal_first`
- `topic_ruined_portal_memory`
- `topic_snow_ask_1`
- `topic_snow_ask_2`
- `topic_snow_first`
- `topic_snow_memory`
- `topic_strider_ask_1`
- `topic_strider_ask_2`
- `topic_strider_first_1`
- `topic_strider_first_2`
- `topic_strider_memory`
- `topic_stronghold_ask_1`
- `topic_stronghold_ask_2`
- `topic_stronghold_first`
- `topic_stronghold_memory`
- `topic_trader_ask_1`
- `topic_trader_ask_2`
- `topic_trader_ask_3`
- `topic_trader_first_1`
- `topic_trader_first_2`
- `topic_trader_memory_1`
- `topic_trader_memory_2`
- `topic_trial_chambers_ask_1`
- `topic_trial_chambers_ask_2`
- `topic_trial_chambers_first`
- `topic_trial_chambers_memory`
- `topic_village_ask_1`
- `topic_village_ask_2`
- `topic_village_first`
- `topic_village_memory`

### Unreachable Rows (No Direct Route + No EXACT_MAP)

- None

### EXACT_MAP Wrong Constant

- None

### EXACT_MAP Intentional Text-Alias

- `precipice_big_drop -> expected LINE_PRECIPICE_BIG_DROP, got LINE_WARNING_DROP_AHEAD`

### Missing Batch3 Target Files

- None

## Log-Observed Unmapped Messages

- `(1–3 steps, quick.)`
- `Side quest: Don't run ahead. Just stay close.`

## Log-Observed Played Sound Events (Top 40)

- `ai-player:bot.line.wolf_guard_duty`: 2
- `ai-player:bot.line.idle_taking_it_easy`: 2
- `ai-player:bot.line.wolf_menace`: 2
- `ai-player:bot.line.ambient_my_job`: 2
- `ai-player:bot.line.touch_hmm`: 1
- `ai-player:bot.line.ambient_blame_terrain`: 1
- `ai-player:bot.line.meme_steve_adjacent`: 1
- `ai-player:bot.line.idle_here_if_needed`: 1
- `ai-player:bot.line.foliage_stuck_5`: 1
- `ai-player:bot.line.meta_stop_looking`: 1

