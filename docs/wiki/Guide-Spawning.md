<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Spawning

[All Guide topics](Guide.md)

## Auto Spawn on Load

Controls whether a bot automatically returns when you re-enter the world.

- When ON (default), the bot spawns automatically on world load with full state restored.
- When OFF, the bot stays shelved until you manually run /bot spawn.
- Use /bot despawn to shelve a bot across sessions (turns this OFF).
- Use /bot despawn session to remove a bot for the current session only (does not change this setting).
- Toggle this in the Bot Controls panel under the Spawning tab.

**Command:** `/bot despawn <bot>`

## Auto Respawn

Controls what happens when a bot dies.

- When ON (default), the bot respawns instantly at the world spawn / bed / saved base.
- When OFF, the bot's death triggers a chat prompt: 'Respawn now? (yes/no)'. Reply yes to bring them back, no to stand down.
- If you don't answer before the session ends, the bot stays shelved. Use /bot spawn &lt;name&gt; or toggle Auto Respawn back ON to bring them back.
- Toggling Auto Respawn ON while a bot is shelved auto-spawns them, which is useful as a revive shortcut.

**Where:** Bot Controls &gt; Spawning &gt; Auto Respawn

## Bot Roster (Spawn Bots…)

Multi-select screen for (re)spawning bots and creating new ones.

- Opens automatically after you pick Admin Mode on a fresh world, or when you press the [-] go-to-look hotkey while no bots are present.
- You can also open it any time from Bot Controls → 'Spawn Bots…' button in the footer.
- Tick one or more saved bots and click 'Spawn Selected (N)'. They spawn in a ring around your look position so they don't pile up.
- Create a brand-new bot with the name input field. Names follow Minecraft username rules (letters, numbers, _-.', max 24 chars). Random skin is assigned. Customize later via the bot's inventory → skin chooser.
- The 'Manage settings → Bot Controls' link at the bottom opens the full Bot Controls panel for per-bot spawning rules.

**Where:** Bot Controls &gt; Spawn Bots…, also the [-] key when no bots exist

## Bulk Apply (All Bots)

One-click reset buttons that set a spawning flag for every bot in this world.

- Expand the global toggles panel in Bot Controls to find them at the bottom.
- Auto Respawn: All Bots [ALL ON] / [ALL OFF]: resets the per-bot Auto Respawn flag for every bot. ALL ON also auto-spawns any inactive bot that was waiting on a respawn decision.
- Auto Spawn on Load: All Bots [ALL ON] / [ALL OFF]: resets the per-bot Auto Spawn on Load flag for every bot.
- These are reset buttons, not persistent toggles. They just apply the value and exit. Per-bot settings you tweak later still take precedence.

**Where:** Bot Controls &gt; Global Settings (expanded) &gt; Bulk Apply

## No-Companions HUD Hint

The on-screen notice that appears when no bots are currently in the world.

- Shown after ~2 seconds of no bots being present, in admin mode or when survival recruitment is complete.
- Press [-] (the go-to-look key) while no bots are present to jump straight to the Bot Roster. Any time, open Guide (]) &gt; Admin &gt; Bot Controls &gt; Spawn Bots.
- Once a bot is out, [-] sends it where you look instead.
- Click the small [x] button on the hint to hide it for the rest of the session.
- It will reappear the next time bots transition from present → absent (e.g. everyone dies or you /bot despawn them).

**Where:** (HUD)
