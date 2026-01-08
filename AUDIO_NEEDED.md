# Audio Files for Bot Dialogue System

✅ **ALL AUDIO FILES HAVE BEEN GENERATED AND INTEGRATED**

This document originally listed dialogue lines needing audio. All 119 unique sounds (237 files with 2 variants each) have been created and integrated into the mod.

## Pending Dialogue Requests (2026-01)

Add new voiced variants for mount/lead handling. Please record 2-3 variants per line to avoid repetition.

### Environment-Specific Dialogue (NEW)

**Amethyst Geode** (triggers near amethyst blocks underground):
- "These crystals are beautiful."
- "So sparkly down here."
- "We found a geode!"

**Bats** (triggers when bats nearby in caves/dark):
- "Ah! Bats!"
- "I hear wings flapping."
- "Bats give me the creeps."

**Dripstone** (triggers near dripstone in dark caves):
- "Careful, those are sharp."
- "Pointy stalactites everywhere."
- "I can hear dripping."

**Deepslate** (triggers below Y=0 in dark areas with deepslate):
- "It's cold down here."
- "We're really deep now."
- "This stone feels ancient."

### Adventure Banter

- Adventure banter: "Look out, creeper! Haha, just kidding."
- Adventure banter: "Relax. If I yell \"run,\" then worry."
- Adventure banter: "I've got your back."
- Adventure banter: "Stay sharp out here."

### Mount/Lead Handling
- Low mount health warning: "This horse looks hurt."
- Low mount health warning: "Mount's looking banged up."
- Low mount health warning: "My horse is hurt."
- No apples for healing: "I don't have any apples to heal it."
- No apples for healing: "I'm out of apples for the horse."
- No apples for healing: "No apples on me for this horse."
- No suitable food: "I don't have any suitable food to heal it."
- No suitable food: "I can't find any mount food right now."
- No suitable food: "I don't have feed for this horse."
- No lead available: "I don't have a lead to secure this horse."
- No lead available: "I'm missing a lead for the horse."
- No lead available: "I can't secure it without a lead."
- Can't grab lead: "I can't grab a lead to secure this horse."
- Can't grab lead: "I couldn't get a lead out."
- Can't grab lead: "My lead isn't accessible right now."
- No fence to tie: "I don't have a fence to tie this horse to yet. I'll keep it on a lead."
- No fence to tie: "No fence nearby, I'll keep it on the lead."
- No fence to tie: "I'll hold the lead until I can tie it off."
- Lost track of lead: "I lost track of the horse I was holding."
- Lost track of lead: "I lost the horse I had on the lead."
- Lost track of lead: "I can't find the horse I was holding."
- Horse gone: "The horse I was holding is gone."
- Horse gone: "The mount I was holding is gone."
- Horse gone: "I lost the horse I was leading."
- Lead snapped: "The lead snapped after a sudden drop."
- Lead snapped: "The lead broke after that fall."
- Lead snapped: "The lead snapped on that drop."
- Lead reattach fail: "I don't have a lead to reattach."
- Lead reattach fail: "I'm out of leads to reattach."
- Lead reattach fail: "No spare leads to reattach."

## Current Status

- **Total Audio Files**: 237 (.ogg format)
- **Unique Sounds**: 119
- **Location**: `src/main/resources/assets/ai-player/sounds/dialogue/`
- **Sound Events Registered**: 142

## Features Implemented

1. ✅ **Reactive Dialogue** - Sounds play automatically when bot speaks
2. ✅ **Context-Aware Chatter** - Ambient sounds based on health/hunger state
3. ✅ **Subtitle System** - Action bar subtitles for ambient chatter
4. ✅ **3D Positional Audio** - 8-block attenuation distance
5. ✅ **Config Toggle** - Per-bot voicedDialogue setting

## Sound Categories

| Category | Count | Examples |
|----------|-------|----------|
| Greeting | 4 | hey, good_to_see, welcome_back, there_you_are |
| Touch | 4 | hmm, yeah, what_need, need_something |
| Status | 7 | hungry, find_food, snack_time, need_breather, not_best, too_many_hits, late_heading_home |
| Idle | 5 | all_quiet, still_standing, taking_it_easy, here_if_needed, enjoying_calm |
| Context | 7 | fish_earlier, smells_fish, fish_cooperating, warming_earlier, breather_sometimes, campfire_wonders, listening |
| Skill | 15 | fishing_*, woodcut_*, hangout_*, sleep_*, fish_session_done, fish_sunset |
| Mode | 15 | follow_*, guard_*, patrol_*, return_*, stay_* |
| Warning | 6 | suffocating, drop_ahead, stuck, banged_up, not_full_strength, prefer_chicken |
| Combat | 6 | engaging, standing_down, defend_bots, focus_self, aggressive, evasive |
| Confirm | 3 | on_it, hold_off, ask_yesno |
| Discover | 12 | diamonds, ancient_debris, emeralds, gold, iron, coal, redstone, lapis, quartz, chest, geode, structure |
| Hazard | 3 | lava, water, no_torches |
| Hunger | 3 | dying, starving, warning |
| Eating | 4 | no_food, still_hungry, progress, done |
| Death | 1 | resume_ask |
| Move | 5 | cant_reach, blocked, walking_to_you, target_lost, back_to_idle |
| Inventory | 3 | full, dont_have, give_item |
| Fish | 1 | no_water |
| Sleep | 4 | cant_now, no_bed, no_spot, bed_blocked |
| Craft | 3 | need_table, unknown, cant_place |
| Smelt | 2 | need_furnace, nothing |
| Farm | 2 | need_seeds, need_hoe |
| Shelter | 1 | cant_build |

---

*Last updated: December 2024*
