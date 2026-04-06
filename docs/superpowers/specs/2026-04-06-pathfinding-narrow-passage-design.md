# Pathfinding: Narrow Passage & Gate Traversal

**Date:** 2026-04-06  
**Status:** Approved  
**Checkpoint:** `pre-pathfind-rework` tag at `bada8a1`

## Problem

Bots fail to navigate through 1-wide doorways (with or without door blocks), fence gates, and narrow structural openings. Three root causes:

1. **BaritoneStylePathFinder** has no FenceGateBlock or TrapdoorBlock handling — treats them as impassable walls
2. **Movement layer** approaches 1-wide gaps diagonally, clips wall corners with the 0.6-wide entity hitbox, and stalls
3. **Door recovery system** detects doors but the bot can't physically reach them due to (2), causing 30-40s oscillation loops before wolf-teleport rescue

## Design

### Change 1: Pathfinder Passability

**Files:** `BaritoneStylePathFinder.java`, `PathFinder.java`

Add FenceGateBlock and TrapdoorBlock handling to `isPassable()` methods in both pathfinders:

- **FenceGateBlock:** Always treat as passable (bot can open on approach). All fence gates are player-openable.
- **TrapdoorBlock:** Open trapdoors = passable (empty collision). Closed wooden trapdoors = passable (bot can open). Closed iron trapdoors = check collision shape (blocked if closed).
- Mirror changes in `isPassableWorld()` (BaritoneStylePathFinder, used during path tagging).

No changes to `isSolidBlock()` / `hasSupportedFloor()` — too risky to broaden floor detection.

### Change 2: Narrow Passage Alignment

**File:** `FollowMovementService.java`

New static method: `findNarrowPassageAlignmentTarget(ServerWorld, BlockPos botPos, BlockPos waypoint)`

**Detection:** When the bot is within ~3 blocks of its waypoint and stagnant (velocity near zero), scan the direct line to the waypoint for a "chokepoint" — a position where the block has 2-high clearance but both perpendicular neighbors are solid walls.

**Alignment:** Return the gap center (Vec3d at x+0.5, y, z+0.5) as a temporary movement target. The bot faces the gap center and walks straight through instead of angling. Once through (bot's block position is at or past the gap), resume normal waypoint pursuit.

**Callers:**
- `followWaypointStep()` — before applying movement input, check for chokepoint and redirect
- `simplePursuitStep()` — same check for direct follow (no waypoints)

**Scope:**
- Cardinal direction gaps only (N/S/E/W). *Note: diagonal 1-wide gaps may need handling later — flagged as a possible issue.*
- Only fires when bot is stagnant (velocity < threshold) — doesn't interfere with smooth movement
- Max scan distance: 3 blocks from bot to gap
- Does NOT open doors (existing `tryOpenDoorAt` handles that separately)
- Does NOT replace the door-plan system (deferred to future Option 3 cleanup)

### Change 3: Logging

Add follow-decision-style log line when narrow passage alignment activates:
```
Follow decision: bot=Jake botPos=... msg=narrow-passage-align: gap=X,Y,Z waypoint=X,Y,Z
```

## What We're NOT Changing

- Door-plan/door-recovery/door-corner system in BotEventHandler (future Option 3)
- Wolf teleport thresholds
- FollowPathService snapshot passability (already handles fence gates + trapdoors)
- `isSolidBlock()` floor detection

## Future: Option 3 (Door System Simplification)

Once the pathfinder routes correctly through doors/gates and the movement layer reliably threads narrow gaps, the complex door-plan/door-recovery/door-corner oscillation-guard system in BotEventHandler can be simplified or removed. The pathfinder + movement layer would handle door traversal end-to-end. Deferred — needs its own analysis pass.
