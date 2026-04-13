# Cross-Session Bot Despawn (Shelving)

**Date:** 2026-04-13  
**Status:** Approved  
**Problem:** `/bot despawn` and `/bot stop` only remove the bot entity for the current session. On next world load, `BotControlApplier.scheduleAutoSpawns()` finds persisted config/world-state data and re-spawns the bot automatically. There is no mechanism to keep a bot intentionally despawned across sessions.

---

## Design

### Data Model

Add an `autoSpawnOnLoad` boolean field to `BotControlSettings` in `ManualConfig.java`:

- `private boolean autoSpawnOnLoad = true;` with standard getter/setter
- Per-world scoped (keyed by `(alias, worldKey)`, same as existing settings)
- When `false`, `BotControlApplier.scheduleAutoSpawns()` skips this bot
- All existing persistence (inventory, position, settings, personality) is preserved — this is a flag, not a data wipe
- Existing configs without the field default to `true` via Gson (Java primitive default after deserialization of missing key). No migration step needed.

**Why `autoSpawnOnLoad` instead of `shelved`:** The UI toggle is "Auto Spawn on Load" (ON/OFF). Storing the field with matching polarity eliminates sign-flip bugs — `ctrl.isAutoSpawnOnLoad()` maps directly to the toggle state with no inversion.

### Command Structure

**`/bot despawn <name>`** (modified — cross-session by default):
1. Resolves the live `ServerPlayerEntity` via `BotTargetingService.resolve()`
2. Derives alias from `bot.getName().getString()` and worldKey from `BotWorldStateService.currentWorldKey(server)`
3. Sets `autoSpawnOnLoad = false` on `Frens.CONFIG.getOrCreateBotControl(alias, worldKey)`
4. Saves config (`Frens.CONFIG.save()`)
5. Unregisters bot from `BotRegistry` + removes entity (existing despawn logic)
6. Chat feedback: *"{name} has been despawned and shelved. Use /bot spawn {name} to bring them back."*

**`/bot despawn session <name>`** (new subcommand — old session-only behavior):
1. Unregisters bot from `BotRegistry` + removes entity (existing logic, unchanged)
2. Does NOT touch `autoSpawnOnLoad`
3. Chat feedback: *"{name} has been removed for this session. They will return on next world load."*

**`/bot despawn all`** — applies cross-session shelving to every resolved bot. This shelves all bots until individually re-spawned. `/bot despawn session all` removes all bots for the current session only (old behavior).

**`/bot spawn <name>`** (modified — clears shelved state):
1. If `autoSpawnOnLoad == false`, sets it to `true` and saves config
2. Proceeds with normal spawn + full state restore
3. No-op write if already `true`

**`BotControlApplier.scheduleAutoSpawns()`** (modified — the gate):
1. After finding candidate bots with persisted spawn/world-state data, check `ctrl.isAutoSpawnOnLoad()`
2. If `false`, skip this bot — do not auto-spawn
3. All other logic unchanged

### Thread Model

The despawn command mutates config on the server thread. The UI toggle mutates on the render thread. Both paths call `Frens.CONFIG.save()` which uses `synchronized (SAVE_LOCK)`. This follows the established thread model for all existing settings — no new concurrency concerns.

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
- Maps directly to `autoSpawnOnLoad` (no inversion)
- Position: directly below "Auto Respawn" in the Spawning tab

**Implementation notes for BotControlScreen:**
- Add `autoSpawnOnLoad` field to the `SettingsSnapshot` record (line ~290)
- Adding a new toggle to the Spawning group shifts all subsequent widget indices in `captureCurrentWidgets()` (line ~307). The hard-coded `ws.get(N)` indices for Behavior and LLM toggles must be incremented by 1.
- Add `s.setAutoSpawnOnLoad(v.autoSpawnOnLoad)` to the save path (line ~467)
- Network sync via `configNetworkManager.sendSaveConfigPacket()` is already called at end of save — new field included automatically via Gson serialization

**In-game guide entry** (BotGuideScreen, under Spawning category, after existing "Auto Respawn" entry):
> Controls whether this bot automatically returns when you re-enter the world. When OFF, the bot stays shelved until you run /bot spawn. Use /bot despawn to shelve a bot (also turns this off). Use /bot despawn session to remove a bot for the current session only — it will return on next world load.

### Edge Cases

| Scenario | Behavior |
|----------|----------|
| Bot spawned in different world | `autoSpawnOnLoad` is per-world — no cross-world interference |
| `/bot stop` on shelved bot | Not possible — bot isn't in the world, command can't resolve entity |
| `/bot stop` on active non-shelved bot, then close world | Bot auto-spawns on next load (unchanged behavior, `autoSpawnOnLoad` still `true`) |
| `/bot despawn` on already-shelved bot | Bot not in world — existing "bot not found" feedback |
| `/bot despawn all` | Shelves every active bot cross-session. Use `/bot despawn session all` for session-only removal. |
| `/bot spawn` when not shelved | `autoSpawnOnLoad = true` write is a no-op, spawn proceeds normally |
| Menu toggle OFF while bot is active | Sets `autoSpawnOnLoad = false` in config. Bot stays for rest of session, won't auto-spawn on next load |
| Menu toggle ON while bot is shelved | Sets `autoSpawnOnLoad = true`. Bot auto-spawns on next world load |
| Death + autoSpawnOnLoad=true + autoRespawn=false | Existing death-gating behavior. On next load, bot spawns but may need resurrection (unchanged) |
| Server crash after `/bot despawn` | Config saved immediately on despawn — flag is durable |

### Files to Modify

| File | Change |
|------|--------|
| `FilingSystem/ManualConfig.java` | Add `autoSpawnOnLoad` field + getter/setter to `BotControlSettings` |
| `GameAI/services/BotControlApplier.java` | Gate `scheduleAutoSpawns()` on `ctrl.isAutoSpawnOnLoad()` |
| `Commands/modCommandRegistry.java` | Modify `/bot despawn` to set flag + save config; add `session` subcommand; modify `/bot spawn` to clear flag |
| `GraphicalUserInterface/BotControlScreen.java` | Add "Auto Spawn on Load" toggle to Spawning tab; add to `SettingsSnapshot` record; update widget indices in `captureCurrentWidgets()`; add to save path |
| `GraphicalUserInterface/BotGuideScreen.java` | Add guide entry for "Auto Spawn on Load" under Spawning category |

### Future Consideration

If config migration becomes painful or the persistence structure outgrows `ManualConfig`, consider **Approach B**: a separate `shelved.json` per bot in the mod's data directory. This fully decouples shelve state from config. Deferred for now in favor of the simpler boolean flag approach.
