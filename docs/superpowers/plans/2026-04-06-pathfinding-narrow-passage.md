# Pathfinding: Narrow Passage & Gate Traversal — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix bot pathfinding through 1-wide doorways (with and without doors), fence gates, and narrow structural openings.

**Architecture:** Two independent changes — (1) add FenceGateBlock and TrapdoorBlock passability to both pathfinders so they route through these blocks instead of around, (2) add narrow-passage alignment to the follow movement layer so the bot centers on 1-wide gaps instead of clipping wall corners.

**Tech Stack:** Minecraft Fabric 1.21.11, Java 21. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-04-06-pathfinding-narrow-passage-design.md`

**Checkpoint tag:** `pre-pathfind-rework` at commit `bada8a1`

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/.../PathFinding/BaritoneStylePathFinder.java` | Modify | Add FenceGateBlock + TrapdoorBlock to `isPassable()` and `isPassableWorld()` |
| `src/.../PathFinding/PathFinder.java` | Modify | Add FenceGateBlock + TrapdoorBlock to `isPassable()` |
| `src/.../GameAI/services/FollowMovementService.java` | Modify | Add narrow-passage alignment detection + hook into movement methods |

All paths relative to `src/main/java/net/wcfcarolina13/`.

---

### Task 1: Add FenceGateBlock passability to BaritoneStylePathFinder

**Files:**
- Modify: `src/.../PathFinding/BaritoneStylePathFinder.java:1-12` (imports)
- Modify: `src/.../PathFinding/BaritoneStylePathFinder.java:446-470` (`isPassable` chunk cache version)
- Modify: `src/.../PathFinding/BaritoneStylePathFinder.java:596-608` (`isPassableWorld` version)

- [ ] **Step 1: Add imports**

Add to the import block at the top of `BaritoneStylePathFinder.java`:

```java
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
```

- [ ] **Step 2: Update `isPassable(ChunkCache, int, int, int)`**

After the existing DoorBlock check (line 454-462), before the fluid check (line 464), add:

```java
        if (state.getBlock() instanceof FenceGateBlock) {
            return true; // all fence gates are player-openable
        }

        if (state.getBlock() instanceof TrapdoorBlock) {
            // Open trapdoors are passable. Closed wooden trapdoors = bot can open.
            // Iron trapdoors = only passable if already open.
            if (state.isOf(Blocks.IRON_TRAPDOOR)) {
                return state.getCollisionShape(cache.world, cache.mutablePos.set(x, y, z)).isEmpty();
            }
            return true;
        }
```

- [ ] **Step 3: Update `isPassableWorld(ServerWorld, BlockPos)`**

After the existing DoorBlock check (line 599-604), before the fluid check (line 605), add:

```java
        if (state.getBlock() instanceof FenceGateBlock) {
            return true;
        }
        if (state.getBlock() instanceof TrapdoorBlock) {
            if (state.isOf(Blocks.IRON_TRAPDOOR)) {
                return state.getCollisionShape(world, pos).isEmpty();
            }
            return true;
        }
```

- [ ] **Step 4: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java
git commit -m "feat: Add FenceGateBlock and TrapdoorBlock passability to BaritoneStylePathFinder"
```

---

### Task 2: Add FenceGateBlock passability to classic PathFinder

**Files:**
- Modify: `src/.../PathFinding/PathFinder.java:1-15` (imports)
- Modify: `src/.../PathFinding/PathFinder.java:533-553` (`isPassable`)

- [ ] **Step 1: Add imports**

Add to the import block at the top of `PathFinder.java`:

```java
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
```

- [ ] **Step 2: Update `isPassable(ServerWorld, BlockPos)`**

After the existing DoorBlock check (line 539-546), before the fluid check (line 548), add:

```java
        if (blockState.getBlock() instanceof FenceGateBlock) {
            return true;
        }
        if (blockState.getBlock() instanceof TrapdoorBlock) {
            if (blockState.isOf(Blocks.IRON_TRAPDOOR)) {
                return blockState.getCollisionShape(world, pos).isEmpty();
            }
            return true;
        }
```

- [ ] **Step 3: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java
git commit -m "feat: Add FenceGateBlock and TrapdoorBlock passability to classic PathFinder"
```

---

### Task 3: Add narrow-passage alignment to FollowMovementService

**Files:**
- Modify: `src/.../GameAI/services/FollowMovementService.java` (add detection method + hook into `followWaypointStep` and `simplePursuitStep`)

- [ ] **Step 1: Add logger and imports**

Add import at the top of `FollowMovementService.java`:

```java
import net.minecraft.block.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

Add logger constant inside the class, near the other constants at the top:

```java
    private static final Logger LOGGER = LoggerFactory.getLogger("frens");
```

- [ ] **Step 2: Add `findNarrowPassageAlignmentTarget` method**

Add this method near the other static helpers (after `approximateToward`, around line 719):

```java
    /**
     * Scans the line between the bot and its waypoint for a "chokepoint" — a 1-wide gap
     * in a wall. Returns the gap center as a movement target, or null if no chokepoint found.
     *
     * Only activates when the bot is stagnant (near-zero velocity), within 3 blocks of a gap.
     * Cardinal-only scan for now; diagonal gaps may need handling later.
     */
    static BlockPos findNarrowPassageAlignmentTarget(ServerWorld world, BlockPos botPos, BlockPos waypoint) {
        if (world == null || botPos == null || waypoint == null) {
            return null;
        }
        // Only scan up to 3 blocks between bot and waypoint
        int dx = waypoint.getX() - botPos.getX();
        int dz = waypoint.getZ() - botPos.getZ();
        int dist = Math.max(Math.abs(dx), Math.abs(dz));
        if (dist < 1 || dist > 4) {
            return null;
        }

        // Step along the line from bot toward waypoint, checking each position
        int stepX = Integer.signum(dx);
        int stepZ = Integer.signum(dz);
        // Use the primary axis for scanning
        int steps = Math.max(Math.abs(dx), Math.abs(dz));

        for (int i = 1; i <= Math.min(steps, 3); i++) {
            int probeX, probeZ;
            if (Math.abs(dx) >= Math.abs(dz)) {
                probeX = botPos.getX() + stepX * i;
                probeZ = botPos.getZ() + (dz != 0 ? (int) Math.round((double) dz * i / Math.abs(dx)) : 0);
            } else {
                probeZ = botPos.getZ() + stepZ * i;
                probeX = botPos.getX() + (dx != 0 ? (int) Math.round((double) dx * i / Math.abs(dz)) : 0);
            }
            BlockPos probe = new BlockPos(probeX, botPos.getY(), probeZ);
            if (!hasTwoHighClearance(world, probe)) {
                continue;
            }
            // Check if this is a chokepoint: passable here, but walls on both perpendicular sides
            if (isChokepoint(world, probe)) {
                return probe;
            }
        }
        return null;
    }

    /**
     * Returns true if the given position is a 1-wide gap: has 2-high clearance but both
     * perpendicular wall pairs are solid.
     *
     * Checks both axes (X-axis walls = east/west neighbors, Z-axis walls = north/south neighbors).
     * A chokepoint on the X-axis means walls to the north and south; on the Z-axis, walls to the
     * east and west.
     */
    private static boolean isChokepoint(ServerWorld world, BlockPos pos) {
        // X-axis chokepoint: walls on Z-perpendicular sides (north + south)
        boolean wallNorth = !hasTwoHighClearance(world, pos.north());
        boolean wallSouth = !hasTwoHighClearance(world, pos.south());
        if (wallNorth && wallSouth) {
            return true;
        }
        // Z-axis chokepoint: walls on X-perpendicular sides (east + west)
        boolean wallEast = !hasTwoHighClearance(world, pos.east());
        boolean wallWest = !hasTwoHighClearance(world, pos.west());
        return wallEast && wallWest;
    }
```

- [ ] **Step 3: Hook into `followWaypointStep`**

In `followWaypointStep()`, after the `LookController.faceBlock(bot, waypoint)` call (line 152) and before the `tryLocalObstacleNudge` call (line 153), insert:

```java
        // Narrow passage alignment: when stagnant near a 1-wide gap, steer toward the gap center.
        if (bot.getVelocity().horizontalLengthSquared() < 0.0025D
                && bot.getEntityWorld() instanceof ServerWorld sw) {
            BlockPos gap = findNarrowPassageAlignmentTarget(sw, bot.getBlockPos(), waypoint);
            if (gap != null) {
                FollowDebugService.maybeLogDecision(LOGGER, bot,
                        "narrow-passage-align: gap=" + gap.toShortString() + " waypoint=" + waypoint.toShortString());
                Vec3d gapCenter = Vec3d.ofCenter(gap);
                LookController.faceBlock(bot, gap);
                BotActions.sprint(bot, false);
                BotActions.autoJumpIfNeeded(bot);
                BotActions.applyMovementInput(bot, gapCenter, 0.18D);
                return;
            }
        }
```

- [ ] **Step 4: Hook into `simplePursuitStep`**

In `simplePursuitStep()`, after the `LookController.faceBlock(bot, BlockPos.ofFloored(targetPos))` call (line 309) and before the `tryLocalObstacleNudge` call (line 313), insert:

```java
        // Narrow passage alignment: when stagnant near a 1-wide gap, steer toward the gap center.
        if (bot.getVelocity().horizontalLengthSquared() < 0.0025D
                && bot.getEntityWorld() instanceof ServerWorld sw) {
            BlockPos targetBlock = BlockPos.ofFloored(targetPos);
            BlockPos gap = findNarrowPassageAlignmentTarget(sw, bot.getBlockPos(), targetBlock);
            if (gap != null) {
                FollowDebugService.maybeLogDecision(LOGGER, bot,
                        "narrow-passage-align: gap=" + gap.toShortString() + " target=" + targetBlock.toShortString());
                Vec3d gapCenter = Vec3d.ofCenter(gap);
                LookController.faceBlock(bot, gap);
                BotActions.sprint(bot, false);
                BotActions.autoJumpIfNeeded(bot);
                BotActions.applyMovementInput(bot, gapCenter, 0.18D);
                return;
            }
        }
```

- [ ] **Step 5: Add FollowDebugService import**

Ensure this import exists at the top of `FollowMovementService.java`:

```java
import net.wcfcarolina13.GameAI.services.FollowDebugService;
```

- [ ] **Step 6: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/FollowMovementService.java
git commit -m "feat: Add narrow-passage alignment to FollowMovementService

Detects 1-wide chokepoints between bot and waypoint/target,
redirects movement to gap center to avoid clipping wall corners."
```

---

### Task 4: Update changelog and final verification

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Add changelog entry**

Prepend to `changelog.md`:

```markdown
## 2026-04-06 — Pathfinding: narrow passage & gate traversal

- **Pathfinder passability: fence gates + trapdoors:** `BaritoneStylePathFinder` and classic `PathFinder` now treat `FenceGateBlock` as passable (bot can open on approach) and `TrapdoorBlock` as passable (wooden = openable, iron = collision check). Previously fence gates were treated as impassable walls, causing the pathfinder to route around or fail entirely.
- **Narrow passage alignment:** When the bot is stagnant near a 1-wide gap (doorway, archway, narrow opening), `FollowMovementService` now detects the chokepoint and steers the bot toward the gap center instead of approaching diagonally and clipping wall corners. Applies to both waypoint following and direct pursuit. Cardinal-direction gaps only for now; diagonal gaps flagged for future work.
```

- [ ] **Step 2: Build final verification**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit changelog**

```bash
git add changelog.md
git commit -m "docs: Add changelog entry for narrow passage pathfinding"
```
