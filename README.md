# Frens

**An NPC companion mod for Minecraft that adds personality-driven bots with survival skills, autonomous companion behaviors, and optional local-LLM "souls" for conversation.**

> This project is a heavily modified fork of [shasankp000/AI-Player](https://github.com/shasankp000/AI-player). The original project aimed to add a "second player" to the game. This fork has evolved into a companion-focused NPC system with extensive skill automation, survival mechanics, optional AI/LLM infrastructure, and active UX/roadmap work.

## Features

### 🤖 Intelligent Bot Companions
- **Multiple bots** can run concurrently, each with persistent inventory, stats, and personality
- **Optional "souls"** - local-LLM conversation, banter and memory via Ollama, off by default; core play never depends on it (see below)
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

### 💬 Souls — optional local-LLM conversation (off by default)

Core bot functionality does **not** need an LLM, a voice engine, or any download. Everything in
this section is opt-in, runs entirely on your machine, and is a documented no-op until you turn
it on. With souls off, bots use the scripted dialogue lines that ship in the JAR.

**What souls add** (per bot, `/bot soul enable <Bot>`): direct chat with a bot by name, group
scenes ("bots, …"), ambient banter between bots when things are calm, opt-in reactions to
nearby unaddressed chat, a per-bot mind (stance toward you, open questions, day memories) and,
since 1.1.201, a **memory digest**: at each Minecraft day rollover the bot summarises what you
said to it into a few short facts it can bring up later. `/bot soul memory <Bot>` shows them;
`/bot soul reset <Bot>` forgets them; `/bot soul digest off` disables the digest.

**Requirements for souls**

- [Ollama](https://ollama.com) running locally (the mod talks to `http://127.0.0.1:11434` and
  cannot install Ollama for you). Only the local Ollama provider is supported; nothing is sent to
  a remote service.
- A pulled chat model. The in-game model manager (Bot Control → LLM…) lists a few tested tags
  with download size and a recommended-RAM guide; `llama3.1:8b` is what the prompts were tuned
  on, `llama3.2:3b` is the light option. The manager shows sizes before downloading and does not
  check your machine's RAM for you.
- Master switch `soulsEnabled` (Bot Control) plus a per-bot enable. Banter, local chat and voice
  each have their own switch and default off.

**Optional voice** (`Bot Control → Soul Voice`). Three engines; all are installed on request, never
silently:

| Engine | What it is | Platforms | What the installer does |
|---|---|---|---|
| Piper | Fast CPU TTS, generic voices | macOS (Apple Silicon / Intel), Windows x64, Linux x64. ARM Windows/Linux: unsupported, the screen says so | Downloads pinned, sha256-checked release archives (~30 MB) and a voice model into `config/frens/piper/` |
| Pocket TTS | CPU TTS, more natural, 21 preset voices | Needs `uv` or Python ≥ 3.10 on `PATH`. Detection currently looks in macOS/Linux locations only; **Windows is not yet supported by the installer** | Creates a Python venv in `config/frens/pocket-tts/` and `pip install`s `pocket-tts` (~850 MB incl. torch); first use pulls a 228 MB model into your Hugging Face cache |
| Dreamsleeve | Voice-clone server on Apple Metal | macOS Apple Silicon only, configured by path; no installer | Nothing — you run the server yourself |

Every installer screen shows source, size and destination before you confirm, and detects an
existing install. **Honest caveat:** souls and the voice engines have so far been field-tested
only on one macOS Apple Silicon machine (M2 Pro, 32 GB). The Windows and Linux Piper paths are
pinned but untested; please report what you see.

**Load.** Soul generations and TTS run on your CPU/GPU alongside the game. The optional
[LoadGoverner](https://github.com/wcfcarolina13) companion mod lowers bot activity while a
generation is in flight; without it, expect frame dips on lighter machines during replies.

### 🧪 Learning Mode and the legacy AI runtime

- **Learning Mode v1** - operator-only demonstration capture for recording traces used to tune
  movement/build behavior offline. No LLM involved.
- The repository still carries an older, separate LLM/tool-calling stack (ollama4j + DJL). It is
  compile-only and **not** what souls use. Building with `./gradlew build -PaiEnabled=true`
  bundles those native runtimes (large JAR, slow build); the normal `./gradlew build` does not,
  and the default JAR never touches them at runtime.

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
5. (Optional) Install [Ollama](https://ollama.com) and pull a model only if you want souls (see the Souls section); not required for normal gameplay

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
