# Lockable Blocks — Design Spec

**Date:** 2026-04-06
**Status:** Draft

## Problem

Bots open fence gates, doors, and trapdoors indiscriminately — including animal pen gates, storage room doors, and other openables that the player wants left alone. There's no way to tell bots "never touch this specific door."

## Solution

A per-block lock system that lets the player mark individual doors, fence gates, and trapdoors as off-limits to all their bots. Locked blocks are treated as solid walls by every bot system — pathfinders route around them, door-escape ignores them, `tryOpenDoorAt` refuses to interact.

## Data Model

### LockableBlockService

New service: `GameAI/services/LockableBlockService.java`

**State:**
- `Map<RegistryKey<World>, Set<BlockPos>>` — locked block positions per world dimension
- `Map<UUID, Boolean>` — lock mode active per player (transient, not persisted)

**Public API:**
- `isLocked(ServerWorld, BlockPos)` — returns true if the block (or its door pair) is locked. Normalizes double-height doors so locking either half locks both.
- `toggleLock(ServerWorld, BlockPos, ServerPlayerEntity)` — adds/removes from set, persists, sends feedback
- `isLockModeActive(UUID)` / `setLockMode(UUID, boolean)` — toggle lock-editing mode per player
- `loadForWorld(ServerWorld)` / `saveForWorld(ServerWorld)` — JSON I/O

**Block validation:** Only `DoorBlock`, `FenceGateBlock`, and `TrapdoorBlock` instances can be locked. Other blocks are silently ignored.

### Persistence

- **Path:** `bot_zones/[world-key]/locked_blocks.json`
- **Format:** JSON array of `{"x":N, "y":N, "z":N}` objects
- **Lifecycle:** Loaded on world load, saved on mutation + server stopping
- **Scope:** Global to all bots owned by the player who locked the block. (Currently single-owner; multi-owner scoping deferred.)

## UI & Interaction

### Entering Lock Mode

Button in `BotControlScreen` (admin tab): "Lock Blocks". Sends `LockModeTogglePayload` to server. Button shows highlighted/toggled state when active. Lock mode persists until toggled off or player disconnects.

### Interaction While in Lock Mode

When a player in lock mode right-clicks a lockable block:

1. Server cancels the normal block interaction (door doesn't open/close)
2. Calls `toggleLock(world, pos, player)`
3. Sends actionbar feedback via vanilla `SystemMessageS2CPacket`: "Locked [Oak Door]" / "Unlocked [Oak Door]" with block display name
4. Plays sound: `block.chest.locked` for locking, UI click for unlocking

### Crosshair Feedback

Each server tick while lock mode is active, check the block under the player's crosshair (server-side raycast). If it's a lockable block, send actionbar text:
- Locked: "Locked — Right-click to unlock"
- Unlocked: "Unlocked — Right-click to lock"
- Not lockable: no text

Throttle: only send when the targeted block changes or every 10 ticks, whichever is less frequent.

### Particle Visualization

While in lock mode, every 20 ticks (1 second) the server sends particle packets for all locked blocks within 32 blocks of the player.

- **Particle:** `ParticleTypes.SOUL_FIRE_FLAME` — blue wisp at block center, slight upward drift
- **When NOT in lock mode:** No particles. Locked blocks are invisible during normal gameplay.

Uses vanilla `ParticleS2CPacket` — no custom payload needed.

## Network Payloads

Only two custom payloads needed:

| Payload | Direction | Purpose |
|---|---|---|
| `LockModeTogglePayload` | Client -> Server | Player toggles lock mode on/off |
| `LockModeStatePayload` | Server -> Client | Syncs lock mode active state for button rendering |

Actionbar text, sounds, and particles all use vanilla server packets — no custom payloads.

## Bot Behavior Integration

### Enforcement Points

1. **MovementService.tryOpenDoorAt()** — check `isLocked()` before opening. If locked, return false.
2. **FollowPathService.isPassableForPlan()** — locked openables return false (bounded planner routes around).
3. **BaritoneStylePathFinder.isPassable()** — locked doors treated as impassable. World context available via `cache.world`.
4. **PathFinder.isPassable()** — same check for classic pathfinder.

### Bot Reaction

First time a bot encounters a locked block during a follow/skill session, show overhead dialogue via `CompanionOverheadDialogueService`: "that door is locked" / "can't go through there".

- Cooldown: 30 seconds per bot per locked block position to avoid spam
- Only triggers when the bot would have otherwise tried to open the block

## What This Does NOT Cover

- Per-bot lock scoping (all owner's bots share the same lock set)
- Lock mode for non-openable blocks (chests, levers, buttons)
- Visual lock indicator during normal gameplay (locks are invisible outside lock mode)
- Multi-owner permissions on locked blocks
