# Cross-Session Bot Despawn (Shelving)

**Date:** 2026-04-13  
**Status:** Approved  
**Problem:** `/bot despawn` and `/bot stop` only remove the bot entity for the current session. On next world load, `BotControlApplier.scheduleAutoSpawns()` finds persisted config/world-state data and re-spawns the bot automatically. There is no mechanism to keep a bot intentionally despawned across sessions.

---

## Design

### Data Model

Add a `shelved` boolean field to `BotControlSettings` in `ManualConfig.java`:

- `private boolean shelved = false;` with standard getter/setter
- Per-world scoped (keyed by `(alias, worldKey)`, same as existing settings)
- When `true`, `BotControlApplier.scheduleAutoSpawns()` skips this bot
- All existing persistence (inventory, position, settings, personality) is preserved — shelving is a flag, not a data wipe

### Command Structure

**`/bot despawn <name>`** (modified — cross-session by default):
1. Sets `shelved = true` on the bot's `BotControlSettings`
2. Saves config (`Frens.CONFIG.save()`)
3. Unregisters bot from `BotRegistry` + removes entity (existing despawn logic)
4. Chat feedback: *"{name} has been despawned and shelved. Use /bot spawn {name} to bring them back."*

**`/bot despawn session <name>`** (new subcommand — old session-only behavior):
1. Unregisters bot from `BotRegistry` + removes entity (existing logic, unchanged)
2. Does NOT touch `shelved` flag
3. Chat feedback: *"{name} has been removed for this session. They will return on next world load."*

**`/bot spawn <name>`** (modified — clears shelved state):
1. If `shelved == true`, sets it to `false` and saves config
2. Proceeds with normal spawn + full state restore
3. No-op write if already `shelved == false`

**`BotControlApplier.scheduleAutoSpawns()`** (modified — the gate):
1. After finding candidate bots with persisted spawn/world-state data, check `ctrl.isShelved()`
2. If `shelved == true`, skip this bot — do not auto-spawn
3. All other logic unchanged

### UI — Menu Settings

Both settings live in the **Spawning tab** of `BotControlScreen`.

**Setting 1: "Auto Respawn" (existing, unchanged)**
- Label: `Auto Respawn`
- Description: `"Respawn on death (skip resurrection ritual)."`
- Toggle: ON/OFF
- Controls post-death respawn behavior only

**Setting 2: "Auto Spawn on Load" (new)**
- Label: `Auto Spawn on Load`
- Description: `"Automatically spawn this bot when the world loads. Turn off to keep the bot shelved until manually spawned."`
- Toggle: ON/OFF, default ON
- Maps to the inverse of `shelved`: ON = `shelved == false`, OFF = `shelved == true`
- Position: directly below "Auto Respawn" in the Spawning tab

**In-game guide entry** (BotGuideScreen, Spawning/Persistence category):
> Controls whether this bot automatically returns when you re-enter the world. When OFF, the bot stays shelved until you run /bot spawn. Use /bot despawn to shelve a bot (also turns this off). Use /bot despawn session to remove a bot for the current session only — it will return on next world load.

### Edge Cases

| Scenario | Behavior |
|----------|----------|
| Bot spawned in different world | `shelved` is per-world — no cross-world interference |
| `/bot stop` on shelved bot | Not possible — bot isn't in the world, command can't resolve entity |
| `/bot despawn` on already-shelved bot | Bot not in world — existing "bot not found" feedback |
| `/bot spawn` when not shelved | `shelved = false` write is a no-op, spawn proceeds normally |
| Menu toggle OFF while bot is active | Sets `shelved = true` in config. Bot stays for rest of session, won't auto-spawn on next load |
| Menu toggle ON while bot is shelved | Sets `shelved = false`. Bot auto-spawns on next world load |
| Death + shelved=false + autoRespawn=false | Existing death-gating behavior. On next load, bot spawns but may need resurrection (unchanged) |
| Server crash after `/bot despawn` | Config saved immediately on despawn — `shelved = true` is durable |

### Files to Modify

| File | Change |
|------|--------|
| `FilingSystem/ManualConfig.java` | Add `shelved` field + getter/setter to `BotControlSettings` |
| `GameAI/services/BotControlApplier.java` | Gate `scheduleAutoSpawns()` on `!ctrl.isShelved()` |
| `Commands/modCommandRegistry.java` | Modify `/bot despawn` to set shelved flag; add `session` subcommand; modify `/bot spawn` to clear flag |
| `GraphicalUserInterface/BotControlScreen.java` | Add "Auto Spawn on Load" toggle to Spawning tab; wire to `SettingsSnapshot` + save path |
| `GraphicalUserInterface/BotGuideScreen.java` | Add guide entry for "Auto Spawn on Load" |

### Future Consideration

If config migration becomes painful or the persistence structure outgrows `ManualConfig`, consider **Approach B**: a separate `shelved.json` per bot in the mod's data directory. This fully decouples shelve state from config. Deferred for now in favor of the simpler boolean flag approach.
