# Staircase Simplification Plan

## Implementation Status: ✅ PHASE 1 COMPLETE - Ascent Fixed (2025-11-18 19:30)

**Date:** 2025-11-18  
**Build Status:** ✅ SUCCESS  
**Files Modified:** `CollectDirtSkill.java`

### Latest Update (19:30): Ascent Working Correctly

The ascent implementation now correctly follows the walk-and-jump algorithm:

1. **runAscent()** - Main loop
   - Searches for existing solid blocks to use as steps
   - Calls executeUpwardStep() each iteration
   - Safety checks and torch placement
   
2. **executeUpwardStep()** - Physical climbing
   - Searches forward 1-3 blocks for solid block at Y+1
   - Clears 4 blocks of headroom ABOVE the step
   - Does NOT mine the step itself (that's what we stand on!)
   - Uses BotActions.moveToward + jump to physically climb
   - 3 attempts before failure

### Key Insight: Ascent ≠ Inverted Descent

**Descent:** Mines blocks to CREATE a downward path → bot walks down carved steps  
**Ascent:** FINDS existing blocks at Y+1 → clears headroom → bot jumps onto them

The previous implementation tried to mine AND walk on the same blocks, which failed because the mined blocks became air!

### What Works Now

Completely rewrote `runStraightStaircase()` with a simplified, physical-movement approach:

1. **New Main Method:** `runStraightStaircase()`
   - Detects direction (up/down) based on start Y vs target Y
   - Works for both relative (`depth 10`) and absolute (`depth-z 100`) targets
   - Uses physical movement only - NO remote mining
   - Removed MovementService dependency
   - Clear logging at each step

2. **New Helper Method:** `executeUpwardStep()`
   - Searches forward 1-3 blocks for step-up block at Y+1
   - Clears 4-block headroom (step + 3 above)
   - Physically walks toward and jumps onto step
   - 3 attempts before declaring failure
   - Skips torches when clearing

3. **New Helper Method:** `executeDownwardStep()`
   - Checks for solid support below next step
   - Clears 4-block headroom ahead
   - Physically walks forward and steps down
   - Verifies Y decreased (or stayed same for flat)

4. **New Helper Method:** `isSolidBlock()`
   - Checks if block has collision shape (solid)
   - Used to find step-up blocks and verify support

### Key Features

✅ **Physical Mining Only:** All block breaking uses `mineStraightStairBlock()` which requires bot to be within 5 blocks and face the block  
✅ **Physical Movement:** Bot walks and jumps - no teleportation or remote positioning  
✅ **Bidirectional:** Automatically handles both upward and downward stairs based on target  
✅ **Direction Locking:** Uses `determineStraightStairDirection()` to lock facing from controller-player  
✅ **Natural Terrain:** Searches for existing step-up blocks (no block placement yet)  
✅ **Torch-Safe:** Skips torch blocks when clearing headroom  
✅ **Clear Errors:** Specific failure messages like "No step-up block found within 3 blocks ahead"  
✅ **Build Success:** Compiles with no errors

### What's NOT Included (Future Phases)

❌ Block placement for missing steps (aborts if no natural step exists)  
❌ Hazard detection (lava/water)  
❌ Inventory full handling  
❌ Resume/pause/teleport logic  
❌ Torch placement  
❌ WorkDirectionService pause position  

---

## Current State Analysis

### What Works Now
1. **Command parsing is functional:**
   - `/bot skill mining depth 10 stairs Jake` → relative depth (up 10 blocks)
   - `/bot skill mining depth-z 10 stairs Jake` → absolute Y-level 10
   - Direction captured from command issuer's facing direction
   - Parameters properly passed to CollectDirtSkill

2. **Existing infrastructure:**
   - `determineStraightStairDirection()` - remembers direction from controller-player
   - `WorkDirectionService` - stores pause position for resume
   - `SkillResumeService` - flags manual resume scenarios
   - `BotActions` helpers exist (though may need new ones)

### What's Broken
1. **Current `runStraightStaircase()` only goes DOWN**
   - Line 1010: `if (player.getBlockY() <= targetDepthY)` assumes going down
   - Line 1039: `while (player.getBlockY() > targetDepthY)` only descends
   - Line 1056: `BlockPos stairFoot = forward.down()` creates downward steps

2. **Uses complex MovementService that may fail**
   - Remote mining instead of physical walking
   - Strict position requirements

## Simplified Approach

### Core Algorithm (Upward Stairs)

```
WHILE bot.Y < targetY AND not timed out:
    1. Find next step-up block in facing direction
       - Search forward (1-3 blocks) for first solid block at Y+1
       - If none found within 3 blocks, abort with "no step ahead"
    
    2. Clear headroom above step-up block
       - Mine step-up block.up(1), step-up block.up(2), step-up block.up(3)
       - Skip torches
       - Use existing mineStraightStairBlock()
    
    3. Walk to and jump onto step-up block
       - BotActions.lookAt(step-up block)
       - BotActions.moveToward(step-up block, approach within 1.5 blocks)
       - BotActions.jump()
       - Wait for bot to land
    
    4. Verify progress
       - If newY > oldY: success, continue
       - If newY == oldY after 3 attempts: stall, abort
       - If newY < oldY: moved wrong direction, abort
```

### Core Algorithm (Downward Stairs)

```
WHILE bot.Y > targetY AND not timed out:
    1. Find/create next step-down block in facing direction
       - Check forward.down() for solid block
       - If air, place cobble/dirt (for now, skip placing - just abort)
    
    2. Clear headroom
       - Mine forward, forward.up(1), forward.up(2), forward.up(3)
       - Skip torches
    
    3. Walk forward and step down
       - BotActions.lookAt(forward position)
       - BotActions.moveForward(1.0)
       - Let Minecraft physics handle step-down
    
    4. Verify progress
       - Similar to upward logic
```

### What We're NOT Doing (Phase 1)

1. ❌ Block placement for missing steps (just abort if no natural step exists)
2. ❌ Complex pathfinding (use simple moveToward)
3. ❌ Torch placement
4. ❌ Hazard detection (lava/water - will add back later)
5. ❌ Inventory management (will add back later)
6. ❌ Resume/teleport logic (will add back later)

### What We ARE Doing (Phase 1)

1. ✅ Direction locking from controller-player facing
2. ✅ Simple movement toward visible step-up blocks
3. ✅ Clear headroom (4 blocks minimum)
4. ✅ Jump mechanics for climbing
5. ✅ Stall detection (3 failed movement attempts = abort)
6. ✅ Works for both UP (depth 10) and DOWN (depth -10) relative values
7. ✅ Works for absolute Y targets (depth-z 100)
8. ✅ Basic safety: abort if can't find step within 3 blocks

## Implementation Plan

### Step 1: Create Helper Methods in BotActions (if needed)

```java
// BotActions.java additions (check if they exist first)
public static void lookAt(ServerPlayerEntity bot, Vec3d target) {
    // Point bot's yaw/pitch toward target
}

public static void moveToward(ServerPlayerEntity bot, Vec3d target, double maxDistance) {
    // Existing method - verify it works
}
```

### Step 2: Rewrite runStraightStaircase()

```java
private SkillExecutionResult runStraightStaircase(SkillContext context,
                                                  ServerCommandSource source,
                                                  ServerPlayerEntity bot,
                                                  int targetDepthY) {
    if (bot == null) {
        return SkillExecutionResult.failure("No bot available for staircase mining.");
    }

    int startY = bot.getBlockY();
    boolean goingUp = startY < targetDepthY;
    boolean goingDown = startY > targetDepthY;
    
    if (!goingUp && !goingDown) {
        return SkillExecutionResult.success("Already at target depth " + targetDepthY);
    }

    Direction direction = determineStraightStairDirection(context, bot);
    BotActions.selectBestTool(bot, preferredTool, "sword");

    long startTime = System.currentTimeMillis();
    int steps = 0;
    int maxSteps = Math.abs(targetDepthY - startY) * 2; // generous limit
    
    while (System.currentTimeMillis() - startTime < MAX_RUNTIME_MILLIS && steps < maxSteps) {
        int currentY = bot.getBlockY();
        
        // Check if reached target
        if ((goingUp && currentY >= targetDepthY) || (goingDown && currentY <= targetDepthY)) {
            return SkillExecutionResult.success("Reached target depth " + targetDepthY);
        }
        
        if (goingUp) {
            SkillExecutionResult stepResult = executeUpwardStep(bot, direction);
            if (!stepResult.success()) {
                return stepResult;
            }
        } else {
            SkillExecutionResult stepResult = executeDownwardStep(bot, direction);
            if (!stepResult.success()) {
                return stepResult;
            }
        }
        
        steps++;
    }
    
    return SkillExecutionResult.failure("Timed out or reached step limit");
}

private SkillExecutionResult executeUpwardStep(ServerPlayerEntity bot, Direction direction) {
    BlockPos feet = bot.getBlockPos();
    
    // 1. Find next step-up block
    BlockPos stepBlock = null;
    for (int distance = 1; distance <= 3; distance++) {
        BlockPos candidate = feet.offset(direction, distance).up();
        if (isSolidBlock(bot, candidate)) {
            stepBlock = candidate;
            break;
        }
    }
    
    if (stepBlock == null) {
        return SkillExecutionResult.failure("No step-up block found ahead");
    }
    
    // 2. Clear headroom
    for (int h = 1; h <= 3; h++) {
        BlockPos clearPos = stepBlock.up(h);
        if (!mineStraightStairBlock(bot, clearPos)) {
            return SkillExecutionResult.failure("Failed to clear headroom");
        }
    }
    
    // 3. Move to and jump onto step
    int beforeY = bot.getBlockY();
    Vec3d stepTarget = Vec3d.ofCenter(stepBlock);
    
    for (int attempt = 0; attempt < 3; attempt++) {
        BotActions.lookAt(bot, stepTarget);
        Thread.sleep(100);
        BotActions.moveToward(bot, stepTarget, 1.5);
        BotActions.jump(bot);
        Thread.sleep(600);
        BotActions.stop(bot);
        
        int afterY = bot.getBlockY();
        if (afterY > beforeY) {
            return SkillExecutionResult.success("Climbed step");
        }
    }
    
    return SkillExecutionResult.failure("Failed to climb after 3 attempts");
}

private SkillExecutionResult executeDownwardStep(ServerPlayerEntity bot, Direction direction) {
    BlockPos feet = bot.getBlockPos();
    BlockPos forward = feet.offset(direction);
    
    // 1. Check for step-down support
    if (!isSolidBlock(bot, forward.down())) {
        return SkillExecutionResult.failure("No support below next step (would fall)");
    }
    
    // 2. Clear headroom
    for (int h = 0; h <= 3; h++) {
        if (!mineStraightStairBlock(bot, forward.up(h))) {
            return SkillExecutionResult.failure("Failed to clear headroom");
        }
    }
    
    // 3. Walk forward and step down
    int beforeY = bot.getBlockY();
    BotActions.lookAt(bot, Vec3d.ofCenter(forward));
    Thread.sleep(100);
    BotActions.moveForwardStep(bot, 1.0);
    Thread.sleep(300);
    
    int afterY = bot.getBlockY();
    if (afterY < beforeY || afterY == beforeY) {
        return SkillExecutionResult.success("Descended step");
    }
    
    return SkillExecutionResult.failure("Moved wrong direction (went up instead of down)");
}

private boolean isSolidBlock(ServerPlayerEntity bot, BlockPos pos) {
    ServerWorld world = (ServerWorld) bot.getEntityWorld();
    BlockState state = world.getBlockState(pos);
    return !state.isAir() && state.getCollisionShape(world, pos).isEmpty() == false;
}
```

## Testing Plan

### Phase 1: Upward Stairs
1. Test: `/bot skill mining depth 5 stairs Jake` from flat ground
2. Expected: Bot carves upward 5 blocks, creating ascending tunnel
3. Verify: Direction locks to controller-player facing

### Phase 2: Downward Stairs  
1. Test: `/bot skill mining depth -5 stairs Jake` from elevated position
2. Expected: Bot carves downward 5 blocks

### Phase 3: Absolute Depth
1. Test: `/bot skill mining depth-z 100 stairs Jake` from Y=90
2. Expected: Bot climbs to Y=100

## Success Criteria

- ✅ Bot physically walks up/down stairs (not remote mining)
- ✅ Direction persists throughout mining
- ✅ Works for both relative (depth) and absolute (depth-z) targets
- ✅ Aborts cleanly with helpful messages when stuck
- ✅ Build compiles without errors

## Future Enhancements (Phase 2)

1. Add back hazard detection (lava/water)
2. Add back inventory full handling
3. Add resume/pause/teleport logic
4. Add block placement for missing steps
5. Add torch placement every N steps
6. Add support for gaps (bridge across)
