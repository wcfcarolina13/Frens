# AI-Player

**An advanced AI companion mod for Minecraft that adds intelligent, personality-driven bots with survival skills, natural language understanding, and autonomous behaviors.**

> This is a heavily modified fork of [shasankp000/AI-Player](https://github.com/shasankp000/AI-player). The original project aimed to add a "second player" to the game. This fork has evolved into a comprehensive AI companion system with extensive skill automation, LLM-powered conversations, and survival mechanics.

## Features

### 🤖 Intelligent Bot Companions
- **Multiple bots** can run concurrently, each with persistent inventory, stats, and personality
- **Natural language chat** - talk to bots in plain English ("Jake, go mine some cobblestone")
- **Distinct personalities** - Jake is a pragmatic engineer, Bob is a sardonic ranger
- **Death recovery** - bots ask if they should continue their last job after respawning

### ⚒️ Survival Skills
| Skill | Description |
|-------|-------------|
| \`woodcut\` | Fell trees, avoid player builds, replant saplings, deposit to chests |
| \`mining\` | Mine stone/ores with proper tool selection and hazard awareness |
| \`stripmine\` | Carve 1×3 tunnels with automatic torch placement |
| \`ascent\` / \`descent\` | Dig staircases up or down to target Y-levels |
| \`fish\` | Find water, cast, and reel in catches (idle hobby) |
| \`collect_dirt\` | Gather soft blocks (dirt, gravel, sand, mud) |
| \`shelter hovel\` | Build emergency dirt/cobble shelters with doors and torches |

### 🧠 Smart Behaviors
- **Hazard detection** - pauses for lava, water, drops, valuable ores, mineshafts
- **Automatic eating** - announces hunger levels and eats when needed
- **Torch placement** - lights dark areas during mining (pauses if out of torches)
- **Suffocation escape** - detects and mines out when stuck in blocks
- **Door handling** - opens doors to path through, closes them behind
- **Combat** - defends itself and nearby allies from hostiles
- **Day/night cycle** - returns to base at sunset, sleeps, resumes at dawn

### 💬 LLM Integration
- **Ollama support** - connect to local LLM for rich conversations
- **Intent recognition** - translates natural requests to skill commands
- **Confirmation prompts** - asks before risky operations
- **Task queuing** - chain multiple requests; bot runs them in order
- **Status queries** - "Jake, status?" returns personality-flavored updates

### �� Persistence
- **Inventory** - saved/loaded automatically between sessions
- **Position** - bots respawn where they left off
- **Stats** - health, hunger, XP preserved
- **Protected zones** - designate areas bots won't modify

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) (0.17.3+) for Minecraft 1.21.10/1.21.11
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. (Recommended) Install [Carpet Mod](https://modrinth.com/mod/carpet) for fake-player support
4. Drop the AI-Player JAR into your \`mods/\` folder
5. (Optional) Install [Ollama](https://ollama.ai/) for LLM features

---

## Quick Start

\`\`\`
/bot spawn Jake training    # Spawn a bot named Jake
/bot follow Jake            # Make Jake follow you
/bot skill woodcut 10 Jake  # Cut 10 trees
/bot skill mining 32 Jake   # Mine 32 stone blocks
/bot stop Jake              # Stop current task
\`\`\`

For LLM chat, enable it first:
\`\`\`
/bot config llm world on
/bot config llm bot Jake on
\`\`\`
Then just chat naturally: "Jake, mine a tunnel" or "all bots follow me"

---

## Command Reference

### Bot Management
| Command | Description |
|---------|-------------|
| \`/bot spawn <alias> training\` | Spawn a new bot |
| \`/bot stop [alias\|all]\` | Stop current task and movement |
| \`/bot resume [alias\|all]\` | Resume a paused skill |
| \`/bot follow [alias\|all]\` | Follow the commander |
| \`/bot heal [alias\|all]\` | Force immediate eating |
| \`/bot inventory [alias\|all]\` | Show inventory summary |

### Skills
\`\`\`
/bot skill <skill_name> [arguments] [alias|all]
\`\`\`

**Examples:**
- \`/bot skill woodcut 20 Jake\` - Cut 20 trees
- \`/bot skill mining 50 Jake\` - Mine 50 stone blocks
- \`/bot skill stripmine 12 Jake\` - Dig 12-block tunnel
- \`/bot skill mining ascent 10 Jake\` - Climb up 10 blocks
- \`/bot skill mining descent-y -32 Jake\` - Descend to Y=-32
- \`/bot skill fish Jake\` - Fish until sunset
- \`/bot skill collect_dirt 30 square Jake\` - Gather 30 dirt

### Storage
| Command | Description |
|---------|-------------|
| \`/bot store deposit <amount\|all> <item>\` | Deposit to nearby chest |
| \`/bot store withdraw <amount\|all> <item>\` | Withdraw from nearby chest |

### Configuration
| Command | Description |
|---------|-------------|
| \`/bot config teleportDuringSkills on\|off\` | Toggle teleport shortcuts |
| \`/bot config inventoryFullPause on\|off\` | Pause when inventory full |
| \`/bot config llm world on\|off\` | Enable LLM for world |
| \`/bot config llm bot <alias> on\|off\` | Enable LLM for specific bot |
| \`/configMan\` | Open GUI for persistent settings |

---

## Targeting Bots

Most commands accept an optional target:

| Syntax | Behavior |
|--------|----------|
| \`/bot <cmd>\` | Uses last targeted bot |
| \`/bot <cmd> Jake\` | Targets bot named "Jake" |
| \`/bot <cmd> all\` | Targets all spawned bots |

Add \`each\` to have every bot chase the full amount individually:
\`\`\`
/bot skill mining 50 each all   # Each bot mines 50 (not split)
\`\`\`

---

## Skill Modifiers

| Modifier | Effect |
|----------|--------|
| \`square\` | Work within expanding square from start position |
| \`until\` | Work until bot holds the requested amount |
| \`each\` | Each bot gets the full count (multi-bot) |
| \`lockDirection true\` | Preserve facing direction for stairs/strips |

---

## Configuration GUI

Run \`/configMan\` to open the settings panel:

- **Auto Spawn** - Automatically spawn bots when world loads
- **Teleport During Skills** - Allow teleport shortcuts
- **Inventory Pause** - Pause when inventory fills
- **LLM Toggle** - Enable/disable natural language per bot
- **Defend Nearby Bots** - Auto-defend allies under attack

Settings persist in \`settings.json5\`.

---

## Tips

- Use \`/bot look_player Jake\` before stripmine to set tunnel direction
- Place a **button on a wall** and right-click it to set facing direction
- Bots automatically wade through shallow water
- After death, bots ask "I died. Should I continue?" - reply yes/no
- Use \`/bot reset_direction Jake\` to clear stored work direction
- When skills pause for hazards, use \`/bot resume Jake\` to continue

---

## Requirements

- Minecraft 1.21.10 or 1.21.11
- Fabric Loader 0.17.3+
- Fabric API
- Java 21+

**Recommended:**
- Carpet Mod (for fake-player mechanics)
- Ollama (for LLM features)

---

## Credits

- **Original Author:** [shasankp000](https://github.com/shasankp000) - Created the original AI-Player mod
- **Fork Maintainer:** wcfcarolina13 - Extended with skills, LLM integration, and survival behaviors

---

## License

MIT License - See [LICENSE](LICENSE) for details.
