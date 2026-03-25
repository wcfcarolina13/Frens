# Frens

**An NPC companion mod for Minecraft that adds personality-driven bots with survival skills, optional AI/LLM hooks, and autonomous companion behaviors.**

> This project is a heavily modified fork of [shasankp000/AI-Player](https://github.com/shasankp000/AI-player). The original project aimed to add a "second player" to the game. This fork has evolved into a companion-focused NPC system with extensive skill automation, survival mechanics, optional AI/LLM infrastructure, and active UX/roadmap work.

## Features

### 🤖 Intelligent Bot Companions
- **Multiple bots** can run concurrently, each with persistent inventory, stats, and personality
- **Optional AI / natural-language infrastructure** - the repo already includes optional LLM routing, tool-calling, provider hooks, and per-bot controls, but core play does not depend on any provider
- **Distinct personalities** - Jake is a pragmatic engineer, Bob is a sardonic ranger
- **Death recovery** - bots ask if they should continue their last job after respawning
- **Learning Mode v1** - operator-only demonstration capture for recording traces used to tune movement/build behavior offline

### ⚒️ Survival Skills
| Skill | Description |
|-------|-------------|
| `woodcut` | Fell trees, avoid player builds, replant saplings, deposit to chests |
| `mining` | Mine stone/ores with proper tool selection and hazard awareness |
| `stripmine` | Carve 1×3 tunnels with automatic torch placement |
| `ascent` / `descent` | Dig staircases up or down to target Y-levels |
| `fish` | Find water, cast, and reel in catches (idle hobby) |
| `hunt` | Hunt nearby food mobs (optional auto-hunt when starving) |
| `collect_dirt` | Gather soft blocks (dirt, gravel, sand, mud) |
| `shelter hovel` | Build emergency dirt/cobble shelters with doors and torches |

### 🧠 Smart Behaviors
- **Hazard detection** - pauses for lava, water, drops, valuable ores, mineshafts
- **Automatic eating** - announces hunger levels and eats when needed
- **Torch placement** - lights dark areas during mining (pauses if out of torches)
- **Suffocation escape** - detects and mines out when stuck in blocks
- **Door handling** - opens doors to path through, closes them behind
- **Combat** - defends itself and nearby allies from hostiles
- **Day/night cycle** - returns to base at sunset, sleeps, resumes at dawn

### 💬 Optional AI / LLM Features
Core bot functionality does **not** require an LLM provider.

This repo already contains optional AI-facing infrastructure, including:

- world-level and per-bot LLM toggles
- provider/client plumbing for optional runtime integrations
- tool-calling / routing code paths
- Learning Mode v1 demonstration capture for offline tuning and future ML/LLM workflows

These systems are still partial and actively evolving, so they should be treated as optional layers on top of the main companion gameplay loop rather than mandatory setup.

### 🗺️ High-Level Roadmap

Verified near-term work in the repo currently points at three big buckets:

- **Active polish:** fortify tower reliability, cavity/callout verification, guide clarity, keybind/help cleanup, and learning-trace playtesting/tuning
- **Current optional AI work:** improving provider UX, memory/tool routing, and tightening the safety/clarity of AI-assisted control paths
- **Future exploration:** using captured learning traces and optional LLM systems to improve control quality without making core companion gameplay depend on online AI services

Roadmap items here are intentionally high-level and based only on tracked tasks, current code, and changelog history.


### ⚙️ Building with AI/LLM support (optional)

By default this project avoids bundling heavy AI runtimes. If you want to enable AI/LLM runtime packaging (which includes native runtimes and AI libraries), set the Gradle project property `aiEnabled` to `true` when building.

Build examples:

- Build normally (no AI runtime bundled):

```bash
./gradlew build
```

- Build with AI/LLM runtime packaging enabled:

```bash
./gradlew build -PaiEnabled=true
```

Enabling AI packaging will include large native libraries (e.g., PyTorch natives) and may significantly increase build times and the resulting JAR size. Use it only if you intend to run LLM providers locally or bundle them for deployment.

To enable the flag permanently for your environment, add to `gradle.properties`:

```text
aiEnabled=true
```

If you do not bundle the AI runtime, you'll still need to provide a compatible LLM provider at runtime (install native engines or configure a remote provider). Refer to provider-specific docs for setup when enabling AI packaging.

### 📦 Persistence
- **Inventory** - saved/loaded automatically between sessions
- **Position** - bots respawn where they left off
- **Stats** - health, hunger, XP preserved
- **Protected zones** - designate areas bots won't modify

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) (0.17.3+) for Minecraft 1.21.10/1.21.11
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. (Recommended) Install [Carpet Mod](https://modrinth.com/mod/carpet) for fake-player support
4. Drop the Frens JAR into your `mods/` folder
5. (Optional) Install/configure an LLM provider only if you want to experiment with the optional AI/LLM features (not required for normal gameplay)

### Creating Downloadable GitHub Releases

GitHub Releases only get a jar asset when a release workflow runs against a tag.

- Create and push a version tag:
```bash
git tag v1.0.7
git push origin v1.0.7
```
- Or run `Actions -> Release Jar -> Run workflow` and provide `tag_name` (for example `v1.0.7`).

After the workflow completes, the jar is attached to the matching release page.

---

## Quick Start

```bash
/bot spawn Jake training    # Spawn a bot named Jake
/bot follow Jake            # Make Jake follow you
/bot regroup Jake           # Safely call Jake back to you (/bot come still works)
/bot open Jake              # Open Jake's inventory/actions screen
/bot skill woodcut 10 Jake  # Cut 10 trees
/bot skill mining 32 Jake   # Mine 32 stone blocks
/bot stop Jake              # Stop current task
```

Alternatively, a work-in-progress topics dialogue menu and a shared inventory view are available for non-command interactions; these interfaces provide a simpler way to interact with bots without typing commands and are under active development.

### Inventory UI quick-switch

When a bot inventory screen is open:

- Press `[` / `]` to switch to previous/next bot
- Click `<` / `>` in the switch chip (bot stats row or expanded overlay header)
- Clicking the alias chip also advances to the next bot

Switching uses `/bot open <alias>` under the hood, so normal ownership/permission checks still apply.

---

## Village Mapping

The Base Manager now supports **Map Village** in the `Villages` section.

- This scans the nearby settlement, computes the same convex-hull perimeter used by fortification, and saves it by name.
- Mapped villages are shared world-level no-go zones for bots.
- They block destructive/resource-gathering behavior inside the saved perimeter, including mining, woodcutting, dirt collection, several small foraging hobbies, and passive-mob hunting.
- Mapped villages show up in the Base Manager list alongside bases and walls, but they are not treated as home bases or navigation destinations.

---

## Command Reference

### Bot Management

| Command | Description |
|---------|-------------|
| `/bot spawn <alias> training` | Spawn a new bot |
| `/bot stop [alias&#124;all]` | Stop current task and movement |
| `/bot resume [alias&#124;all]` | Resume a paused skill |
| `/bot follow [alias&#124;all]` | Follow the commander |
| `/bot regroup [alias&#124;all]` | Safely regroup the bot back toward you (`/bot come` remains a legacy alias) |
| `/bot open [alias]` | Open bot inventory/actions UI (no alias = last targeted fallback) |
| `/bot heal [alias&#124;all]` | Force immediate eating |
| `/bot inventory [alias&#124;all]` | Show inventory summary |

### Skills

```bash
/bot skill <skill_name> [arguments] [alias|all]
```

**Examples:**
- `/bot skill woodcut 20 Jake` - Cut 20 trees
- `/bot skill mining 50 Jake` - Mine 50 stone blocks
- `/bot skill stripmine 12 Jake` - Dig 12-block tunnel
- `/bot skill mining ascent 10 Jake` - Climb up 10 blocks
- `/bot skill mining descent-y -32 Jake` - Descend to Y=-32
- `/bot skill fish Jake` - Fish until sunset
- `/bot skill collect_dirt 30 square Jake` - Gather 30 dirt

### Storage

| Command | Description |
|---------|-------------|
| `/bot store deposit <amount&#124;all> <item>` | Deposit to nearby chest |
| `/bot store withdraw <amount&#124;all> <item>` | Withdraw from nearby chest |

### Protected Zones

| Command | Description |
|---------|-------------|
| `/bot zone protect <radius> [label]` | Create a protected zone centered on the block you are looking at |
| `/bot zone remove <label>` | Remove a protected zone (owner/op only) |
| `/bot zone list` | List zones with center, radius, owner, access mode, and permit count |
| `/bot zone permit <label> <owner>` | Grant explicit access to another owner (owner/op only) |
| `/bot zone revoke <label> <owner>` | Revoke explicit access (owner/op only) |
| `/bot zone mode <label> <owner_only&#124;allowlist&#124;public>` | Set access policy for a zone (owner/op only) |

`<owner>` may be a player name, UUID, or a bot alias owner.

Mode behavior:
- `owner_only` - only the zone owner can modify within the zone
- `allowlist` - zone owner plus explicitly permitted owners
- `public` - all owners may modify within the zone

### Configuration

| Command | Description |
|---------|-------------|
| `/bot config teleportDuringSkills on&#124;off` | Toggle teleport shortcuts |
| `/bot config inventoryFullPause on&#124;off` | Pause when inventory full |
| `/configMan` | Open GUI for persistent settings |

---

## Targeting Bots

Most commands accept an optional target:

| Syntax | Behavior |
|--------|----------|
| `/bot <cmd>` | Uses last targeted bot |
| `/bot <cmd> Jake` | Targets bot named "Jake" |
| `/bot <cmd> all` | Targets all spawned bots |

Add `each` to have every bot chase the full amount individually:
```
/bot skill mining 50 each all   # Each bot mines 50 (not split)
```

---

## Skill Modifiers

| Modifier | Effect |
|----------|--------|
| `square` | Work within expanding square from start position |
| `until` | Work until bot holds the requested amount |
| `each` | Each bot gets the full count (multi-bot) |
| `lockDirection true` | Preserve facing direction for stairs/strips |

---

## Configuration GUI

Run `/configMan` to open the settings panel:

- **Auto Spawn** - Automatically spawn bots when world loads
- **Teleport During Skills** - Allow teleport shortcuts
- **Inventory Pause** - Pause when inventory fills
- **Defend Nearby Bots** - Auto-defend allies under attack

Settings persist in `settings.json5`.

---

## Tips

- Use `/bot look_player Jake` before stripmine to set tunnel direction
- Place a **button on a wall** and right-click it to set facing direction
- Bots automatically wade through shallow water
- After death, bots ask "I died. Should I continue?" - reply yes/no
- Use `/bot reset_direction Jake` to clear stored work direction
- When skills pause for hazards, use `/bot resume Jake` to continue

---

## Requirements

- Minecraft 1.21.10 or 1.21.11
- Fabric Loader 0.17.3+
- Fabric API
- Java 21+

**Recommended:**
- Carpet Mod (for fake-player mechanics)

---

## Credits

- **Original Author:** [shasankp000](https://github.com/shasankp000) - Created the original AI-Player mod
- **Fork Maintainer:** wcfcarolina13 - Extended with skill automation and survival behaviors

---

## License

MIT License - See [LICENSE](LICENSE) for details.
