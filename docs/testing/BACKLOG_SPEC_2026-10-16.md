# Backlog spec, 2026-10-16 (after the dead-code audit, 1.1.251)

The contract for the follow-ups left by `DEAD_CODE_AUDIT_2026-10.md` and builds 1.1.243-1.1.251. Scoping reports with
file:line evidence live in the release worktree's `.superpowers/sdd/backlog/` (not tracked). Decisions marked **(Bradley)**
were asked on 2026-10-16. The rest take the scoper's recommended answer and say so; each goes in its changelog entry as a
ruling with the cost if wrong.

Standing rules: threading rules in CLAUDE.md, pure `*Policy` statics for tests (no `net.minecraft` types), no em dashes or
semicolons in player-facing text, docs and the in-game Guide updated in the same build.

## Build 1.1.252: leash release, leash threading and records, base command gates, torch thread

### 1. `/bot leash release [bot]` **(Bradley: command + button)**
- New literal under the existing leash command. On the server thread: unleash every mob the bot holds, the lead item goes
  into the bot's inventory (dropped at its feet if full), clear the in-memory leash target and the saved held-lead record
  (new `LeashTargetPolicy.Event.RELEASED`).
- `BotActionPolicy` LEASH action also accepts `release`, so owners who are not operators can use it from the UI. Add a
  button next to the existing leash control in the bot inventory screen.
- The "I can't find a fence" reply names the command.
- Guide: one line under the leash or mounts topic.

### 2. Leash and dismount work on the server thread
- `RideSyncService.forceDismountAndSecure` currently mutates entity state from the skill worker (hotbar swaps, fence
  interact, `stopRiding`, the leash maps, `recordMount`). Keep the slow parts on the worker (`ensureLead`, `ensureFence`
  may walk and craft), do one short read hop to find a fence, then one bounded server hop (about 2 s, with a "couldn't
  dismount, try again" fallback) for the secure, dismount, map clears and `recordMount`.
- Same for the rest of `LeashToFenceSkill`: lead selection, the fence interact and the held count in one hop, sleeps stay
  on the worker.

### 3. Every real lead release forgets the saved record
- `maybeOpportunisticallyTether` (`RideSyncService` ~:4165) and the TETHERED branch (~:3568): call
  `MountPersistenceService.forgetHeldByBot(..., TIED_TO_FENCE)`.
- Mount dead (~:4080) and lead snapped (~:4091): new event `LEAD_LOST`, clear and forget when the saved mount matches.
  Entity not found (~:4072) stays as is (the mount may be in an unloaded chunk). Ruling, recommended.

### 4. `/bot base` commands use the screen's rules
- Shared `BaseNetworkManager.applyBaseSet/applyBaseRemove/applyBaseRename(ServerCommandSource, ...)`, like
  `applyBaseOwner`. Console passes as admin. Players go through the same edit check as the Bases screen.
- `/bot base set` stamps the caller as owner and refuses a name already in use (today it silently moves the old base and
  strips its owner). Ruling, recommended (cost: an op who used `set` to move a base now gets "name already used").
- `remove` and `rename` act on walls and villages too, like the screen. Ruling, recommended.

### 5. TorchPlacer
- Delete `RECENTLY_PLACED_TORCHES`, its constant, `registerPlacedTorch` (one thread per torch) and both calls. Nothing
  reads them since 1.1.247.

## Build 1.1.253: shelter follow-ups

### 6. Seal block order
- Pure `SealOrderPolicy.order(sealIds, hasPickaxe, hasAxe)`: with a pickaxe, today's order. Without one: dirt, sand, then
  gravel, then planks, then the blocks that need a pickaxe, fastest first (cobblestone near the end).
- One helper used by every seal site (wall-off, cliff-dig) and by the dig-down cap, which today takes any block item,
  chests and diamond blocks included.
- A bot with no pickaxe and only cobblestone still seals with it. Ruling, recommended (shelter matters more than 2 to 4
  cobblestone).

### 7. Shelters survive a reload
- New per-bot, per-world store `config/frens/bot_shelter_state.json` (pattern: `bot_mount_state.json`): dimension, type,
  cap, entry side, interior cell, placed blocks.
- Written when a shelter registers (wall-off, dig-down, cliff, village house), deleted when break-free starts and on the
  invalidation, starvation and dawn exits. Not deleted on disconnect or reset.
- On join, before the commander-near and surface checks: restore when the world, dimension and exact cell match and the
  shelter still validates (wall-off: the 8 side cells hold). If it is already day, restore and let the normal dawn exit
  run. All shelter types, and even with the commander near. Rulings, recommended (cost: a stale record if an exit path is
  missed, contained by the exact-cell check and the per-block teardown rule).

### 8. Dig-down never leaves an open pit **(Bradley: climb out)**
- Preflight in one server hop (pure `DigDownPolicy.preflight`): at least one seal block, or the top cell drops one by hand
  (dirt or grass), all three cells mineable, not protected, not fluid, solid floor under the third. Otherwise log
  `dig-down skipped reason=...` and stay on the surface.
- Count real removals, stop if the first cell did not open.
- Cap with the seal order from 6, log the failure reason, retry once.
- If still uncapped after a real dig: climb out (surface recovery, pillaring with the mined blocks) and mark that column
  (and 2 blocks around it) as no-dig-down until dawn.

## Build 1.1.254: config cleanups

### 9. Global bot controls (Q12)
- The per-world migration already exists (`migrateLegacyToPerWorld`, since 2026-03-13). Remove the global fallbacks:
  blank world key gets detached defaults, the last fallback returns null, delete the 1-arg accessors and their readers,
  and the global `botControls` key disappears from the saved file after migration. Pure
  `LegacyBotControlMigrationPolicy` + tests. Ruling, recommended (no world-load copy).
- Fix found during scoping: Bot Controls on a client connected to a remote server writes to the global map, which never
  syncs, so remote edits are lost. The server supplies the world key, or the per-bot panel is disabled with a clear
  message when there is no integrated server.

### 10. Mount `_legacy` promotion removal (Q13)
- Delete the promotion now. Nirn's mount file has no `_legacy` entry and no build since 1.1.246 can write one. Ruling,
  recommended (cost: none on current data).

## Build 1.1.255: death retrieval mission (Q11)

### 11. `/bot retrieve <bot> [me|<deadBot>]` and a "Retrieve Drops" button **(Bradley: your death and each bot's)**
- Records: the player's last death from vanilla (`getLastDeathPos`). For bots, a new per-world record of the last death
  of every bot (dimension, position, time, cause), written whatever the mode or auto-respawn setting. The questing
  `companionDied*` fields stay for the resurrection gate. Resurrection no longer wipes the bot's own record. Ruling,
  recommended.
- Flow: owner only. Fast travel to a safe standing spot next to the death position (cross-dimension allowed, no travel
  cooldown, normal food cost), abort if the target world is unavailable. On arrival, sweep everything within 8 blocks for
  about 3 passes. **(Bradley: come back)** Fast travel back to whoever sent it and report the count. If they are offline
  or unreachable, it stays put and reports.
- Refuse lava and void deaths with a clear reason. Warn but go for a mob kill. Tell the player items last about 5 minutes
  of loaded time. Pure `DeathRetrievalPolicy` (cause rule, age, stand-point choice, action parsing) + tests.
- Guide: a topic or line, and the button.

## Not in these builds
- CLAUDE.md (main checkout, gitignored, hook-blocked from worktrees): a patch is provided for Bradley.
- Spawn `_legacy` retirement (same design as 9), an item-thrower filter for retrieval (needs an accessor mixin).
