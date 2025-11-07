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

Arguments are forwarded exactly as you type them (minus the alias token). Each bot runs the skill in its own task, so multiple bots can execute the same skill concurrently.  
When you target multiple bots (or `all`), the requested count is shared between them; add the keyword `each` if you want every bot to chase the full amount.
`collect_dirt` will also scoop up gravel, sand, mud, and similar soft blocks whenever pure dirt is out of reach so the bots don’t stall in tight caves.

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
| `/bot stop [alias\|all]` | Abort the active task and movement for the target bots. |
| `/bot follow [alias\|all] [player|bot]` | Make one or more bots follow you (default), another player, or even another bot. |
| `/bot defend nearby bots <y\|n> [alias\|all]` | Toggle the auto-defend behaviour so a bot (or every bot) will break formation to protect any ally that is attacked within ~12 blocks. Use `y`/`n` and the optional alias just like other commands. |
| `/bot inventory [alias\|all]` | Summarise the selected bot(s) inventory. |
| `/bot inventory count <item> [alias\|all]` | Count how many of an item the bot(s) are carrying. |

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
- Mention multiple bots in chat (e.g., “Jake and Bob, report in” or “all bots follow me”) to address them at once—each will respond without interrupting their current jobs.

Happy testing! If a command reports “No bot found”, ensure you either targeted one previously or appended the alias/`all` token to the invocation.
