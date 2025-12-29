# Audio Files Needed for Bot Dialogue System

This document lists all dialogue lines that need audio files created.
Each line should have 2 variants (e.g., `confirm_on_it__01.ogg` and `confirm_on_it__02.ogg`).

Audio format: `.ogg` (Ogg Vorbis)
Location: `src/main/resources/assets/ai-player/sounds/dialogue/`

---

## HIGH PRIORITY - Core Gameplay

### Acknowledgments / Confirmations

| Text | Suggested Filename |
|------|-------------------|
| "On it." | `confirm_on_it` |
| "Understood. I'll hold off for now." | `confirm_hold_off` |
| "Give me a quick yes or no so I know whether to proceed." | `confirm_ask_yesno` |

### Mining Discoveries

| Text | Suggested Filename |
|------|-------------------|
| "I found diamonds!" | `discover_diamonds` |
| "I found ancient debris!" | `discover_ancient_debris` |
| "I found emeralds!" | `discover_emeralds` |
| "I found gold!" | `discover_gold` |
| "I found iron!" | `discover_iron` |
| "I found coal!" | `discover_coal` |
| "I found redstone!" | `discover_redstone` |
| "I found lapis!" | `discover_lapis` |
| "I found quartz!" | `discover_quartz` |
| "I found a chest!" | `discover_chest` |
| "I found an amethyst geode!" | `discover_geode` |
| "I found a structure." | `discover_structure` |

### Mining Hazards

| Text | Suggested Filename |
|------|-------------------|
| "Careful, there's lava ahead." | `hazard_lava` |
| "Water detected ahead." | `hazard_water` |
| "Ran out of torches!" | `hazard_no_torches` |

### Combat

| Text | Suggested Filename |
|------|-------------------|
| "Engaging threats against allies." | `combat_engaging` |
| "Standing down unless attacked." | `combat_standing_down` |
| "I'll defend nearby bots when they are attacked." | `combat_defend_bots` |
| "I'll focus on my own fights." | `combat_focus_self` |
| "Combat stance set to aggressive." | `combat_aggressive` |
| "Combat stance set to evasive." | `combat_evasive` |

---

## MEDIUM PRIORITY - Status Updates

### Hunger Emergency

| Text | Suggested Filename |
|------|-------------------|
| "I'll die if I don't eat!" | `hunger_dying` |
| "I'm starving!" | `hunger_starving` |
| "I'm getting hungry." | `hunger_warning` |

### Eating Feedback

| Text | Suggested Filename |
|------|-------------------|
| "I don't have any safe food to eat!" | `eating_no_food` |
| "I ate some food, but I'm still hungry." | `eating_still_hungry` |
| "I ate some food so far." | `eating_progress` |
| "I ate some food. I feel better now!" | `eating_done` |

### Death & Resume

| Text | Suggested Filename |
|------|-------------------|
| "I died. Should I continue with the last job?" | `death_resume_ask` |

### Movement

| Text | Suggested Filename |
|------|-------------------|
| "I couldn't reach that spot." | `move_cant_reach` |
| "I couldn't clear a block." | `move_blocked` |
| "Walking to you." | `move_walking_to_you` |
| "Follow target lost. Returning to idle." | `move_target_lost` |
| "Back to idling." | `move_back_to_idle` |

### Inventory

| Text | Suggested Filename |
|------|-------------------|
| "I'm out of inventory space." | `inventory_full` |
| "I don't have that." | `inventory_dont_have` |
| "Here, take this." | `inventory_give_item` |

---

## LOWER PRIORITY - Skill-Specific

### Fishing

| Text | Suggested Filename |
|------|-------------------|
| "I can't find any water nearby." | `fish_no_water` |

### Sleep

| Text | Suggested Filename |
|------|-------------------|
| "I couldn't sleep right now." | `sleep_cant_now` |
| "I couldn't craft a bed." | `sleep_no_bed` |
| "I couldn't find a safe spot." | `sleep_no_spot` |
| "I couldn't get into the bed." | `sleep_bed_blocked` |

### Crafting

| Text | Suggested Filename |
|------|-------------------|
| "I need a crafting table placed nearby." | `craft_need_table` |
| "I don't know how to craft that yet." | `craft_unknown` |
| "I couldn't place a crafting table here." | `craft_cant_place` |

### Smelting

| Text | Suggested Filename |
|------|-------------------|
| "I need a furnace placed nearby." | `smelt_need_furnace` |
| "I have nothing cookable." | `smelt_nothing` |

### Farming

| Text | Suggested Filename |
|------|-------------------|
| "I need seeds before I can farm." | `farm_need_seeds` |
| "I need a hoe to till the soil." | `farm_need_hoe` |

### Shelter

| Text | Suggested Filename |
|------|-------------------|
| "I can't build a shelter here." | `shelter_cant_build` |

---

## Summary

| Priority | Category | Count |
|----------|----------|-------|
| HIGH | Acknowledgments | 3 |
| HIGH | Mining Discoveries | 12 |
| HIGH | Mining Hazards | 3 |
| HIGH | Combat | 6 |
| MEDIUM | Hunger Emergency | 3 |
| MEDIUM | Eating Feedback | 4 |
| MEDIUM | Death & Resume | 1 |
| MEDIUM | Movement | 5 |
| MEDIUM | Inventory | 3 |
| LOWER | Fishing | 1 |
| LOWER | Sleep | 4 |
| LOWER | Crafting | 3 |
| LOWER | Smelting | 2 |
| LOWER | Farming | 2 |
| LOWER | Shelter | 1 |
| **TOTAL** | | **53 lines** |

With 2 variants each: **106 audio files total**

---

## File Naming Convention

Each audio file should follow this pattern:
```
{category}_{description}__{variant}.ogg
```

Examples:
- `discover_diamonds__01.ogg`
- `discover_diamonds__02.ogg`
- `combat_aggressive__01.ogg`
- `combat_aggressive__02.ogg`

## Voice Style Notes

- Keep the same voice/tone as existing dialogue files
- Casual, friendly companion feel
- Not robotic or overly formal
- Brief and natural-sounding
