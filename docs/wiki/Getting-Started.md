# Getting started

This page takes you from a fresh install to a fren working for you. No AI model is needed for any of it.

## 1. Install

You need:

- Minecraft Java **1.21.11**
- [Fabric Loader](https://fabricmc.net/) 0.17.3 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 21 or newer (the Minecraft launcher's bundled Java is fine)

Put the Frens jar and Fabric API in your `mods/` folder.

[Carpet](https://modrinth.com/mod/carpet) is optional. It's recommended alongside Frens, but frens work without it.

## 2. Pick a world mode

The first time you open a world with Frens, a **Choose Frens World Mode** screen asks how you want to get companions:

- **Admin Mode (Manual Spawn / Commands)**: you create frens yourself, by name. It's the most stable choice, so pick this one if you're unsure.
- **Questing Mode (Village Recruitment)**: you recruit frens in a village and unlock more as the friendship grows. It's still in development and buggier than Admin. See the [Companion questing guide](../../COMPANION_QUESTING_GUIDE.md).

Only an operator or the host can choose. If you need the screen again later, run `/bot worldmode reset` ([World Mode Reset](Guide-Admin.md#world-mode-reset)).

## 3. Spawn a fren

In Admin Mode, the **Bot Roster** opens after you choose. Type a name and spawn. You can also use a command:

```
/bot spawn Jake admin
```

Each fren needs a unique name that follows Minecraft username rules. You can reopen the roster at any time from Bot Controls. Once you have saved frens, pressing `-` while none are out also opens it ([Bot Roster](Guide-Spawning.md#bot-roster-spawn-bots)).

Frens are saved with the world. When you come back, they return with their inventory, unless you turned off [Auto Spawn on Load](Guide-Spawning.md#auto-spawn-on-load).

## 4. The keys worth learning first

| Key | What it does |
|---|---|
| `]` | Open the Guide: every feature, searchable |
| `` ` `` | Toggle follow for the fren you're looking at |
| `\` | Stop the fren you're looking at (hold it for the hotkey menu) |
| `-` | Send the fren where you're looking |
| `[` | Open the Spells tab |

You can rebind all of these in Minecraft's Controls screen. The full list is on the [cheatsheet](Guide-Shortcuts.md).

## 5. Give it a job

Open the fren's inventory and action screen with `/bot open Jake`. Most jobs are buttons in the Actions menu. The same jobs are also available as commands:

```
/bot follow Jake               follow you
/bot skill woodcut Jake        cut trees until sunset
/bot skill woodcut 10 Jake     cut 10 trees
/bot skill fish Jake           fish until sunset
/bot stop Jake                 stop whatever it is doing
```

Give the fren tools and food by putting them in its inventory, the same way you'd use a chest. With no axe, it punches a tree and crafts one.

At sunset, a fren that is idle (not following you) or on an until-sunset job heads home and sleeps. To have one return to a far-off job each morning, see [Work loops](Work-Loops.md).

## 6. Optional: let frens talk back

Frens speak scripted lines out of the box. If you want them to chat in their own words, the Guide's **AI setup** button walks you through installing [Ollama](https://ollama.com) and a model. Everything runs on your own machine. Voices are a separate, optional add-on. See [Set up AI companions](Guide-Basics.md#set-up-ai-companions).

## Next

- [In-game Guide](Guide.md): every card from the Guide screen.
- [FAQ](FAQ.md): common questions and fixes.
