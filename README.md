# AI-Player Command Guide

This project adds controllable “fake players” that can run scripted skills side-by-side with real players.  
The notes below cover the commands that are most frequently used while testing and orchestrating bots.

---

## Targeting Bots

Most `/bot …` subcommands now share a common targeting model:

| Syntax | Behaviour |
| --- | --- |
| `/bot <subcommand> …` | Applies to the last bot you targeted. If you have not targeted a bot yet, the command asks you to specify one. |
| `/bot <subcommand> … <alias>` | Applies to the bot whose alias matches `<alias>` (e.g. `Jake`). |
| `/bot <subcommand> … all` | Applies to every currently spawned fake player. |

- Aliases are case-insensitive.  
- The alias (or the keyword `all`) can be placed at the *end* or the *beginning* of the argument list.  
- Once you successfully target a bot, that selection is remembered for future commands until you target a different bot or all bots despawn.

---

## Skill Commands

```
/bot skill <skill_name> [arguments …] [alias|all]
```

Examples:

- `/bot skill collect_dirt 20 square Jake` – Jake gathers 20 dirt blocks in “square” mode.  
- `/bot skill collect_dirt Jake 20 square` – Same as above (alias first, arguments after).  
- `/bot skill collect_dirt 25 square all` – Every active bot attempts the same skill.  
- `/bot skill collect_dirt until sunrise` – Uses the last targeted bot; no alias supplied.
- `/bot skill collect_dirt 50 square each all` – Every active bot aims for the full 50 blocks individually.
- `/bot skill mining 32 Jake` – Jake mines 32 stone/deepslate/andesite-style blocks with a pickaxe, great for underground resupplies.
- `/bot skill stripmine 12 Jake` – Jake carves a straight 1×3 tunnel forward for 12 blocks.

Arguments are forwarded exactly as you type them (minus the alias token). Each bot runs the skill in its own task, so multiple bots can execute the same skill concurrently.  

- `/bot skill mining ascent <blocks> [lockDirection true] <alias>` – Climb up the specified number of blocks; add `lockDirection true` to reuse prior facing across resumed or repeated commands.
- `/bot skill mining ascent-y <Y-level> [lockDirection true] <alias>` – Climb until reaching target Y.
- `/bot skill mining descent <blocks> [lockDirection true] <alias>` – Dig staircase downward for given blocks.
- `/bot skill mining descent-y <Y-level> [lockDirection true] <alias>` – Dig downward staircase until at/below Y.

---

## Persistent Bot Controls (`/configMan`)

- Run `/configMan` and open the **Bot Controls** tab to toggle session-level behaviors.  
- Per-bot toggles include:
  - **Auto Spawn + Mode:** Automatically run `/bot spawn <alias> training|play` when a world loads.
  - **Teleport During Skills:** Mirrors `bot config teleportDuringSkills …`.
  - **Inventory Pause:** Mirrors `bot config inventoryFullPause …`.
  - **LLM Bot Toggle:** Same as `bot llm bot <alias> on/off`.
- Each row now sits inside a scrollable panel that displays both the bot alias and its owner so you can see which profile you’re editing. The `default` row is a fallback for bots without entries. Hover any toggle to see a quick explainer (for example, “Pause Inv” reminds you that jobs must be resumed with `/bot resume <alias>`).
- A global **LLM World Toggle** mirrors `bot llm world on/off` so you can keep worlds LLM-enabled without reissuing chat commands.
- Ownership can be reassigned with `/bot config owner <alias> <player>`; the config UI simply reflects those assignments. When you spawn a brand-new bot, ownership defaults to the player who issued `/bot spawn`.
- Bot spawn locations are preserved between sessions. When auto-spawn is enabled the bot reappears at its last saved position (dimension, rotation, etc.), not world spawn.

All settings are saved to `settings.json5`, synced to the server, and re-applied every session—no more manual command spam when you start a new world.
When you target multiple bots (or `all`), the requested count is shared between them; add the keyword `each` if you want every bot to chase the full amount.
`collect_dirt` will also scoop up gravel, sand, mud, and similar soft blocks whenever pure dirt is out of reach so the bots don’t stall in tight caves.
With the LLM layer toggled on, you can also address bots in plain chat (“`Jake mine a tunnel`”, “`all bots follow me`”) and they’ll respond in character before asking for confirmation on risky requests. Requests like “collect 5 cobblestone” or “grab some dirt” automatically translate to the closest supported skill (mining, stripmine, collect_dirt, etc.) so the bot can launch the real job without you typing `/bot skill …`. After you confirm, the bot announces the job in chat (“On it — mining 5 cobblestone…”) and reports when it’s done (or why it paused) so you always know what it’s working on.

You can also chain natural-language jobs: If you issue a second command while a bot is busy, it queues that request and runs it automatically once the current job ends. Ask “Jake status?” at any time to get a personality-flavoured update that includes the active job, queued tasks, and the last completed assignment.

### Skill Arguments & Modifiers

| Argument | Meaning |
| --- | --- |
| `square` | Stay within a square that starts at the bot’s current position. The current radius grows as the skill expands its search. Mutually exclusive with `spiral` once that mode returns. |
| `until` | Keep working until the bot already holds the requested amount (useful for “top off” jobs). Combine with `exact` to force the exact count instead. |
| `each` | When you target multiple bots, have each bot satisfy the full count instead of splitting it. |
| `depth <y>` | New: carve an offset staircase down to the requested Y level (teleport stays off). Example: `/bot skill mining depth -50 Jake`. The bot mines whatever blocks are in the way, stepping aside before every drop so it never digs directly beneath itself, and stops once its feet are at or below Y = -50. |
| `stairs` | Combine with `depth` to dig a straight staircase: the bot carves a 1×1 corridor with 4 blocks of headroom, stepping forward and down one block per segment so you can drop in real stair blocks later. |
| `spiral` | Temporarily disabled while the straight staircase mode is being polished. |

Depth jobs implicitly enable the “digging down” mode so the bot removes whatever blocks block the stairwell until the target Y level is reached—it ignores ore filters and keeps carving offset steps until the goal depth is met. Add `stairs` if you want the bot to dig a straight, stripmine-style descent with 4 blocks of headroom per tread; the `spiral` option is gated for now until we rework it to match the new staircase spec. This is a stepping stone toward more advanced “mine to depth, then branch” tasks.

### Stripmine

`/bot skill stripmine <length>` cuts a 1-block-wide corridor with a 3-block-tall ceiling directly ahead of the bot. The miner:

- Clears the 1×3 cross-section in front of it, picking the correct tool for each block.
- Walks forward step-by-step, ensuring the tunnel stays open without digging straight down.
- Pauses immediately if it spots lava, water, big drops, mineshaft pieces, chests, or valuable ores (diamond/emerald/gold/etc., including the deepslate palette), then prints a warning telling you to run `/bot resume <alias>` after you deal with the hazard.

If you skip `<length>`, it defaults to 8 blocks.

---

## Inventory Persistence

Manual inventory snapshots live in `run/saves/<world>/playerdata/ai-player/inventories/`. Inventories now auto-load when a bot spawns and auto-save periodically while it is online; the manual commands below remain useful for explicit backups and restores.

```
/bot inventory save [alias|all]
/bot inventory load [alias|all]
```

Use **save** before you leave a world (or swap loadouts), and **load** immediately after spawning the bot again.  
Snapshots are keyed by both alias and the bot’s UUID, so Jake and Bob keep their inventories separate.

> Auto-save and auto-load on spawn/despawn are planned next; for now the manual commands let you maintain equipment across sessions.

---

## Other Handy Commands

| Command | Purpose |
| --- | --- |
| `/bot spawn <alias> training` | Spawn a fake player at your position. |
| `/bot stop [alias\|all]` | Abort the active task and movement for the target bots. Works even when bot is actively mid-job. |
| `/bot resume [alias\|all]` | Restart the last skill that paused due to danger (water/lava/rare ores/full inventory). |
| `/bot heal [alias\|all]` | Force bot to eat food immediately until fully satiated. Prioritizes least valuable food and skips items with negative effects. |
| `/bot reset_direction [alias\|all]` | Reset stored work direction. Next mining job will use bot's current facing direction. |
| `/bot follow [alias\|all] [player|bot]` | Make one or more bots follow you (default), another player, or even another bot. |
| `/bot defend nearby bots <y\|n> [alias\|all]` | Toggle the auto-defend behaviour so a bot (or every bot) will break formation to protect any ally that is attacked within ~12 blocks. Use `y`/`n` and the optional alias just like other commands. |
| `/bot inventory [alias\|all]` | Summarise the selected bot(s) inventory. |
| `/bot inventory count <item> [alias\|all]` | Count how many of an item the bot(s) are carrying. |
| `/bot config teleportDuringSkills on\|off [alias]` | Enable or disable teleport shortcuts while skills are running. Turning it off makes the bot tunnel and stair-step like a human player. |
| `/bot config inventoryFullPause on\|off [alias]` | When enabled, the bot pauses mining as soon as its inventory is full; use `/bot resume` to restart once you clear space. |
| `/bot look_player [alias\|all]` | Make the selected bot(s) turn to face you. Helpful before starting stairs/stripmine runs so the tunnel goes the way you expect. Add `stop` (e.g., `/bot look_player stop Jake`) to release their attention. |
| `/bot config llm world on\|off` | Enable or disable the LLM/personality layer for the current world (off by default so legacy behaviour remains). |
| `/bot config llm bot <alias> on\|off` | Override the LLM toggle for a specific bot if you only want some companions to chat in free-form language. |

Most of these subcommands now accept the optional alias/`all` token in the same fashion as the skill command.

---

## Tips

- Re-run `/bot skill … all` after spawning a new bot to keep the “last targeted” selection up to date.  
- Keep aliases short and distinct from skill parameters to make parsing unambiguous.  
- When experimenting with brand-new skills, start with a single bot to verify parameters before issuing the command to `all`.  
- If a bot dies mid-task it pauses, asks “I died. Should I continue the last job?”, and waits for `yes` or `no` in chat before resuming or cancelling.
- Chatting with the bots no longer interrupts whatever task they’re already performing—the language model runs asynchronously, and the bot only pauses when it actually needs to execute a requested action (and, for resource-intensive jobs, only after you confirm in chat).
- Jake and Bob answer in distinct voices (Jake = pragmatic engineer, Bob = sardonic ranger) so it’s easier to tell which bot is replying during conversations.
- Bots automatically wade across shallow rivers now; if they step into water mid-task they’ll keep their heads above the surface and resume the job once danger passes.
- When a mining job pauses for water, lava, precipices, mineshafts, chests, rare ores, or a full inventory it now tells you to run `/bot resume <alias>` once the hazard is cleared; this overrides the SkillResumeService flag so you can restart the same skill without retyping all parameters. If you resume anyway, the bot remembers that hazard location so it doesn’t immediately alert on the same block again.
- Mention multiple bots in chat (e.g., “Jake and Bob, report in” or “all bots follow me”) to address them at once—each will respond without interrupting their current jobs.
- Bots automatically eat when hunger drops below 75% (announcing "I'm hungry"), become more urgent at 25% ("I'm starving"), and critical at final hunger bar ("I'll die if I don't eat!"). Use `/bot heal <alias>` to force immediate eating.
- Bots place torches automatically during mining when light levels drop below 7. Torches are placed on perpendicular walls to avoid breaking during mining. If out of torches, bot announces "ran out of torches!" and pauses.
- Mining jobs now maintain work direction across pause/resume cycles. Use `/bot reset_direction <alias>` to change the direction for the next job.
Happy testing! If a command reports “No bot found”, ensure you either targeted one previously or appended the alias/`all` token to the invocation.
