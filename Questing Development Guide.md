Below is a single, clean Markdown file you can hand directly to an AI agent.
It is intentionally constrained, implementation-oriented, and free of speculative fluff.

⸻


# Bot Quest System — Minimal, Seed-Agnostic Design

## Purpose
Define a lightweight quest / sidequest system for a bot companion that:
- Works with any world seed
- Avoids fixed lore, locations, or landmarks
- Scales into long-term narrative depth without schema rewrites
- Supports short, disposable play sessions (2–5 minutes)

The world is replaceable.  
The bot is persistent.

---

## Core Design Rules (Non-Negotiable)

1. **No fixed locations**
   - Never reference coordinates, named biomes, or specific structures.
2. **No world lore**
   - Lore belongs to the bot, not the environment.
3. **Quests are small**
   - 1–3 actions maximum.
4. **Rewards are non-power**
   - Cosmetic, dialogue, unlocks, or memory tags only.
5. **Seed-agnostic**
   - All world interaction is expressed via predicates.

---

## Minimal Quest Schema (Foundation)

This schema is intentionally small.  
Do not add branching, dialogue trees, or hard dependencies.

```json
{
  "quest_id": "string",
  "category": "exploration | combat | curiosity | bonding",
  "intent": "Why the bot wants this",
  "constraints": {
    "environment_predicates": [],
    "entity_predicates": [],
    "state_predicates": []
  },
  "actions": [
    {
      "type": "move | observe | fight | collect | wait",
      "parameters": {}
    }
  ],
  "completion_conditions": [],
  "bot_reflection": {
    "on_success": "string",
    "on_failure": "string"
  }
}


⸻

Predicate System (Seed-Agnostic Core)

Predicates describe conditions, not places.

Environment Predicates
	•	open_area
	•	enclosed_space
	•	high_elevation
	•	low_light
	•	mob_dense
	•	player_unvisited_chunk

Entity Predicates
	•	any_hostile_mob
	•	multiple_mobs
	•	non_hostile_entity
	•	structure_like_block_cluster

State Predicates
	•	time_night
	•	player_low_health
	•	bot_recently_damaged
	•	distance_from_spawn > X

Predicates must be cheap to compute and resolvable in any seed.

⸻

Quest Structure Model

Each quest follows the same loop:
	1.	Intent
	•	Bot motivation (emotional or functional)
	2.	Constraint
	•	Abstract world requirements (predicates)
	3.	Action
	•	Small, bounded player + bot task
	4.	Reflection
	•	Bot response; source of personality and continuity

Narrative emerges from repetition + reflection, not scripted storylines.

⸻

Starter Quest Examples (Ultra-Minimal)

Quest: “Stay With Me”
	•	Category: Bonding
	•	Intent: “Don’t run ahead. Just stay close.”
	•	Constraints: none
	•	Actions:
	•	Player remains within X distance for 30 seconds
	•	Reflection:
	•	Success: “Thanks. That helps more than you think.”
	•	Failure: “You always rush. It’s fine.”

⸻

Quest: “Somewhere New”
	•	Category: Exploration
	•	Intent: “Let’s go somewhere you haven’t been.”
	•	Constraints:
	•	player_unvisited_chunk
	•	Actions:
	•	Move until predicate resolves
	•	Reflection:
	•	Success: “Good. New ground.”
	•	Failure: “We’ll try again later.”

⸻

Memory & Progression (Not in Schema)

Memory is external to quests.

Later systems may add:
	•	Memory tags (e.g., player_patient, player_risky)
	•	Dialogue tone shifts
	•	Quest availability gating

Example post-quest hook (future):

"memory_effects": {
  "tags_added": ["player_patient"],
  "tags_removed": ["player_risky"]
}

This does not require a schema rewrite.

⸻

Scaling Strategy

This system supports, without structural changes:
	•	Personalized narrative arcs
	•	AI-generated quest flavor
	•	Multiplayer (per-player bot state)
	•	Monetization (cosmetics, voice packs, personality skins)
	•	Cross-world or cross-game portability

Quests are templates.
Continuity lives in the bot.

⸻

Design Guardrail

If a quest requires:
	•	a specific biome
	•	a named structure
	•	a canonical event

It must be rewritten.

⸻

Guiding Principle

The quest is a stimulus.
The bot’s reaction is the story.

End of specification.

---

If you want next, I can:
- Convert this into **agent instructions** (system + developer prompts)
- Produce a **JSON quest pack** ready for ingestion
- Define the **predicate resolver logic** in pseudocode
- Or design the **bot memory/tag model** in the same minimalist style