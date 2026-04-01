# BridgeScaffoldService Design Spec

## Context

The bot can pillar vertically to reach elevated targets but has no way to reach targets laterally. In dense, sprawling trees (cherry blossoms, large oaks, jungle), many branch logs are 4-7 blocks away horizontally from the trunk column — too far to reach from the pillar, but easily reachable by extending a horizontal scaffold bridge outward. WoodcutCleanupSkill currently fails these with `pillar-too-high` or `overhead-unreachable`.

This service builds temporary 1-block-wide horizontal bridges outward from a perch, mines reachable targets at each step, then retracts back. It lives alongside `ScaffoldService` (vertical pillaring) as a complementary horizontal primitive.

## Location

`src/main/java/net/wcfcarolina13/GameAI/services/construction/BridgeScaffoldService.java`

## API

```java
public class BridgeScaffoldService {

    public record BridgeResult(
        List<BlockPos> placedBlocks,   // blocks placed by the service (not adopted existing)
        int adoptedBlocks,             // existing solid blocks walked over without placing
        int targetsMined,              // targets mined during the bridge
        boolean reachedMaxLength,      // hit the max length limit
        boolean retracted,             // successfully returned to perch position
        String failureReason           // null on success
    ) {}

    /**
     * Extend a bridge from the bot's current position in the given cardinal
     * direction, mining reachable targets at each step, then retract back
     * to the starting position.
     *
     * @param bot             the bot entity
     * @param direction       cardinal direction only (NORTH/SOUTH/EAST/WEST)
     * @param maxLength       maximum bridge blocks to extend
     * @param ninja           use ninja bridging (fast sneak toggle) vs safe full-sneak
     * @param isTarget        predicate identifying blocks to mine (e.g. BlockTags.LOGS)
     * @param associatedBase  passed to protection checks (nullable)
     * @param preferredBlocks scaffold material priority list
     * @return BridgeResult with placement/mining stats and retraction status
     */
    public static BridgeResult bridgeAndRetract(
        ServerPlayerEntity bot,
        Direction direction,
        int maxLength,
        boolean ninja,
        Predicate<BlockState> isTarget,
        BlockPos associatedBase,
        List<Item> preferredBlocks
    );
}
```

## Safety Invariants

### Sneak Lock (Critical)

`BotActions.stop()` calls `bot.setSneaking(false)` unless `SneakLockService.isLocked()`. Mining sweeps, event handlers, or any subsystem calling `stop()` during a bridge would unsneak the bot on an edge, causing a fatal fall. The service MUST:
- Acquire `SneakLockService.acquire(bot.getUuid())` before extending
- Release in a `finally` block after retraction completes (or fails)

### Fall Detection (Critical)

After every movement step, verify `bot.getBlockPos().getY() >= bridgeY`. If the bot has fallen below bridge level:
- Abort extension immediately
- Skip retraction (bot is not on the bridge)
- Return `placedBlocks` in the result so the caller or cleanup service can tear them down
- Set `retracted = false` and `failureReason = "fell-off-bridge"`

### Pre-flight Material Check

Before starting, count available scaffold items matching `preferredBlocks` in the bot's inventory. Clamp effective `maxLength` to `min(maxLength, availableMaterials)`. Don't waste time bridging 2 blocks if the bot only has 2 blocks but `maxLength` is 8.

### Target-at-Floor Priority Rule

If a block at `nextFoot` matches `isTarget` (e.g., a log that should be harvested), mine it as a target FIRST, then re-evaluate the position. Only adopt non-target solid blocks as floor. This prevents the bot from walking over harvestable logs.

## Bridge Lifecycle

### Phase 1: Extend

For each step (up to effective `maxLength`):

1. **Compute next position:** `currentPos.offset(direction)`
2. **Proactive clearing:** Mine leaves/snow/soft blocks at next foot, head, and jump-clearance positions (3 vertical cells). Use `MiningTool.mineBlock` with short timeout. If the block at `nextFoot` matches `isTarget`, mine it as a target instead of clearing — then re-check if floor exists.
3. **Check for existing solid block:** If the next position's floor (`nextPos.down()` has collision) is already solid AND the block at `nextPos` is passable AND the floor block does NOT match `isTarget`:
   - **Adopt it** — walk onto it, no placement needed. Increment `adoptedBlocks`.
   - Skip placement. Do NOT track for teardown.
4. **Place scaffold block:** If no existing floor:
   - Bot faces downward toward `nextFloor` position (for LOS to support face)
   - `BotActions.tryPlaceBlockAt(nextFloor, direction, preferredBlocks, false)` — the `direction` hint favors the support block BEHIND the target (the block bot is standing on), which is the correct face to click for forward extension
   - If placement fails, try alternate faces via `resolvePlacementSupports`
   - If still fails, return with `failureReason = "placement-failed"`
   - Track placed position for retraction
5. **Step onto new block:**
   - Safe mode: full sneak, `applyMovementInput` toward next position center
   - Ninja mode: unsneak while >0.3 blocks from edge, re-sneak within 0.3 of edge
   - Verify bot actually moved (position check)
   - **Fall check:** Verify `bot.getBlockPos().getY() >= bridgeY`. If fallen, abort.
6. **Mine reachable targets:** Sweep ~4.5-block radius for blocks matching `isTarget`. Clear obstructing leaves via raycast. Mine each found target. Repeat sweep until no more reachable targets (max 10 passes).
7. **Abort check:** `TaskService.isServerStopping() || isAbortRequested` between steps.

### Phase 2: Retract

Walk back toward the starting perch, breaking placed scaffold blocks behind:

1. **Face toward perch** (opposite of bridge direction)
2. **For each step back toward perch:**
   a. Walk forward one block (toward perch), staying sneaked
   b. `BotActions.stop(bot)` — stabilize position (sneak lock prevents unsneak)
   c. Verify `BlockInteractionService.canInteract(bot, scaffoldBehind)` — confirm reach
   d. Face backward (toward bridge tip)
   e. Break the scaffold block behind (the one just vacated) — only if it was placed by us (in the tracked list), NOT if it was an adopted existing block
   f. Sleep briefly for block-break settle (100ms)
3. **Verify return:** Bot should be back at original perch position. If not, attempt `nudgeTowardUntilClose`.

### Abort Handling

If abort is requested mid-bridge:
- Stop extending immediately
- Still retract (best-effort) — leaving floating scaffold is worse than spending 2-3 extra seconds retracting
- If retraction also aborted (server stopping), leave blocks — WoodcutCleanupSkill or scaffold cleanup phase will handle them

## Ninja Bridging Detail

The speed advantage comes from unsneaking during the safe middle portion of each block:

```
Block traversal (1 block):
  [0.0 - 0.3] Sneak ON  — leaving previous block edge
  [0.3 - 0.7] Sneak OFF — fast walk across middle
  [0.7 - 1.0] Sneak ON  — approaching next edge, place block
```

Position tracking uses `bot.getPos()` relative to block boundaries. Java's `%` preserves sign (e.g., `-123.7 % 1.0 = -0.7`), so normalize to `[0, 1)`:

```java
double raw = bot.getPos().getComponentAlongAxis(direction.getAxis()) % 1.0;
double progress = ((raw % 1.0) + 1.0) % 1.0;  // always [0, 1)
// For negative-axis directions (WEST, NORTH), invert: progress = 1.0 - progress
if (direction.getDirection() == Direction.AxisDirection.NEGATIVE) {
    progress = 1.0 - progress;
}
```

Safe mode simply keeps sneak ON for the entire traversal.

## Proactive Obstacle Clearing

Before each bridge step, clear soft blocks at the destination:

```java
BlockPos nextFoot = currentPos.offset(direction);
BlockPos nextHead = nextFoot.up();
BlockPos nextJump = nextFoot.up(2);

for (BlockPos pos : new BlockPos[]{nextFoot, nextHead, nextJump}) {
    BlockState state = world.getBlockState(pos);
    if (!state.isAir() && isSoftObstacle(state)) {
        MiningTool.mineBlock(bot, pos).get(3000, TimeUnit.MILLISECONDS);
    }
}
```

Where `isSoftObstacle` = leaves, snow, replaceable, vines, tall grass (same set as `clearSoftStorageBlock` in ChestStoreService).

Existing solid blocks at `nextFoot` that aren't soft obstacles are treated as adoptable floor — the bot walks onto them.

## Target Mining at Each Step

Same pattern as WoodcutSkill's elevated sweep:

```java
for (int pass = 0; pass < 10; pass++) {
    BlockPos found = null;
    for (BlockPos check : BlockPos.iterate(botPos.add(-4, -2, -4), botPos.add(4, 4, 4))) {
        BlockState state = world.getBlockState(check);
        if (!isTarget.test(state)) continue;
        if (!isWithinReach(bot, check)) continue;
        if (associatedBase != null) {
            // protection check
        }
        found = check.toImmutable();
        break;
    }
    if (found == null) break;
    // clear path, equip tool, mine
}
```

The `isTarget` predicate is caller-supplied. For woodcut: `state.isIn(BlockTags.LOGS)`. For mining: ore-specific predicates.

## Existing Code Reused

| Primitive | Source | Purpose |
|-----------|--------|---------|
| `BotActions.tryPlaceBlockAt()` | BotActions:877 | Place scaffold on specific face |
| `BotActions.moveBackward()` | BotActions:184 | Walk backward |
| `BotActions.applyMovementInput()` | BotActions:211 | Fine-grained movement control |
| `bot.setSneaking(true/false)` | BotActions:310 | Sneak toggle |
| `LookController.faceBlock()` | LookController:11 | Aim at block for placement/mining |
| `MiningTool.mineBlock()` | MiningTool:56 | Async block breaking with timeout |
| `BlockInteractionService.canInteract()` | BlockInteractionService:32 | Reach/LOS validation |
| `ScaffoldService` scaffold block types | ScaffoldService:40 | Default block priority list |

## Threading

- Service runs on worker thread (same as skills)
- Block placement via `BotActions.tryPlaceBlockAt` handles server-thread scheduling internally
- Block breaking via `MiningTool.mineBlock` returns CompletableFuture, use `.get(timeout)`
- Movement via `applyMovementInput` modifies velocity asynchronously — the velocity mutation happens on the server thread, so the bot has NOT moved when the call returns. The 100-250ms step delays compensate for this. Always verify position after sleep before acting on the new location.
- `sleepQuiet` between steps for timing (100-250ms per step)

## Constants

| Name | Value | Purpose |
|------|-------|---------|
| `MAX_BRIDGE_LENGTH` | 8 | Hard cap even if caller requests more |
| `SAFE_STEP_DELAY_MS` | 200 | Delay between steps in safe mode |
| `NINJA_STEP_DELAY_MS` | 100 | Delay between steps in ninja mode |
| `NINJA_SNEAK_THRESHOLD` | 0.3 | Sub-block distance from edge to re-sneak |
| `TARGET_SWEEP_RADIUS` | 4 | Blocks to scan for targets at each step |
| `TARGET_SWEEP_PASSES` | 10 | Max mining passes per bridge position |
| `MINING_TIMEOUT_MS` | 3000 | Timeout for obstacle clearing per block |
| `RETRACT_BREAK_DELAY_MS` | 100 | Delay after breaking scaffold during retraction |

## Verification

1. `./gradlew build -x test` — compile check
2. Unit test concept: build a bridge in a flat world, verify block count and positions
3. In-game test (woodcut integration):
   - Find a cherry blossom tree with sprawling branches
   - Run `/bot skill woodcut 1`
   - Observe: during pillar descent, bot bridges outward to reach distant branch logs
   - Observe: bridge retracts cleanly after mining
   - Observe: ninja mode visibly faster than safe mode
4. In-game test (standalone):
   - Position bot at a cliff edge
   - Call bridge service toward the gap
   - Verify bridge extends, bot walks it, bridge retracts
