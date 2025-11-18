# Development Session Handoff - Upward Stair Mining

## Current Status (as of 2025-11-18T04:50:32)

### What's Working
- ✅ Bot now correctly breaks obstructing blocks if it spawns inside them
- ✅ Inventory persistence (with minor caveat about timing)
- ✅ Drop sweep only collects items, doesn't break blocks
- ✅ Inventory full messages working correctly

### Critical Issues to Fix

#### 1. **UPWARD STAIR MINING IS BROKEN** (Priority: CRITICAL)
The bot is over-complicated and bailing when it shouldn't. The user's latest feedback:

**Expected Behavior:**
- When mining stairs upward against a solid wall of blocks:
  1. Mine 3 blocks above the first block (leaving that first block intact as a step)
  2. Jump onto that first block
  3. Mine 3 blocks above the next block
  4. Repeat - creating a staircase by stepping up one block at a time

**Current Problems:**
- Bot is bailing at inappropriate moments
- Bot is skipping blocks (going up 2 blocks instead of 1)
- Bot no longer adheres to controller-player's facing direction when starting task
- Bot is sometimes mining downward instead of upward

**User's Suggestion:**
- "I can't help but feel that we've over-complicated this"
- Consider adding pauses at each stage of ascent to allow threads to conclude
- Keep it simple - just reverse the logic of digging down

#### 2. **Bot Stats Reset on Respawn** (Priority: HIGH)
- When using `/bot spawn <alias> training`, the bot's stats (health, level, hunger) are reset
- Stats should be preserved just like inventory is now preserved

#### 3. **Depth/Stairs Direction** (Priority: MEDIUM)
- Bot should start depth/stairs tasks in the direction the controller-player is facing
- Should remember that direction and position like stripmine does

## Key Files to Review

1. **latest.log** - Contains all recent test results and error messages
2. **src/main/java/net/minecraft/client/tutorial/BotActionManager.java** - Main action orchestration
3. **Stair mining related skills** - Likely in skills package
4. **Bot spawn/respawn logic** - For stats preservation issue

## Technical Context

### Inventory Persistence Issue
- Partially fixed - works if human player waits a few seconds before re-entering
- Quick exit/re-entry still causes item duplication (e.g., two boats)

### Block Placement Fallback
- User asked: "does it already have a fallback system for which blocks to choose for placement in terms of their value and what to do when it has no blocks to use?"
- This needs to be verified

## User's Simplified Algorithm for Upward Stairs

```
Given: A solid wall of blocks ahead

Step 1: Mine 3 blocks above current position (blocks above head)
        - Leaves the first block intact as a step
Step 2: Jump onto that first block
Step 3: Mine 3 blocks above the new position
Step 4: Repeat until target depth reached

Key principle: Step up ONE block at a time, not two
```

## Testing Instructions

The user has been testing with these scenarios:
1. `/bot spawn Jake training` - checking stat preservation
2. Spawning bot inside walls - checking suffocation escape
3. `depth` and `depth-z` commands - checking upward stair mining
4. Facing direction alignment - checking if bot follows player's facing direction

## Next Steps

1. **Read latest.log** to understand the specific failure patterns
2. **Simplify the upward stair mining logic** - it's currently over-complicated
3. **Fix stats preservation** on bot respawn with alias
4. **Ensure facing direction** is captured and used for depth/stairs tasks
5. **Test incrementally** - don't make multiple changes at once

## Important Notes

- User has been iterating on suffocation fixes "a dozen times" - be careful not to regress
- User explicitly said "be careful that you're not going in circles"
- Keep changes minimal and surgical
- Update `gemini_report_3.md` with all changes made
- DO NOT update `changelog.md` (per GEMINI.md rules)

## User's Patience Level

The user is showing signs of frustration with:
- Repeated regressions
- Over-complication of simple tasks
- Going in circles on the same issues

**Action:** Focus on getting upward stairs working correctly with the simplest possible implementation before moving to other issues.
