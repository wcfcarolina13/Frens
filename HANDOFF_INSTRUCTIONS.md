# Handoff Instructions - December 30, 2025

## Current Work: Return-to-Base & Follow Mode Untangling

### Problems Solved This Session

The bot's return-to-base functionality was broken due to tangled logic with follow mode:

1. **Follow toggle in UI was stopping return-to-base** - Since return-to-base used `Mode.FOLLOW` internally, the Topics menu showed "Follow" as active, and clicking it would stop the return-to-base.

2. **Repeated `setReturnToBase()` calls reset stuck counters** - Each time the command was triggered, it reset the `ReturnBaseStuckService` stagnation counter, preventing escape mechanisms from triggering.

3. **UI couldn't distinguish follow vs return-to-base** - The `isBotFollowing()` check returned true for both cases.

### Architecture Understanding

**Return-to-base uses FOLLOW mode, not RETURNING_BASE mode:**
- `setReturnToBase()` in `BotEventHandler.java` sets `Mode.FOLLOW` with `state.baseTarget` + `state.followFixedGoal` populated
- This leverages FOLLOW's door/corner/waypoint planning
- The stuck detection is called from `handleFollow()` when `state.baseTarget != null`

**3-Tier Progressive Escape System (ReturnBaseStuckService.java):**
1. **200 ticks (10s)**: `tryBackupAndSidestep()` - tries all 4 cardinal directions
2. **400 ticks (20s)**: `tryPillarEscape()` - detects enclosure, mines walls/roof
3. **600 ticks (30s)**: `triggerStuckFlare()` - fires flare signal for player help

### Fixes Applied This Session

1. **Added `isReturningToBase()` and `isFollowingPlayer()` helpers** (BotEventHandler.java):
   - `isReturningToBase()` - true when FOLLOW mode + baseTarget + followFixedGoal set
   - `isFollowingPlayer()` - true when FOLLOW mode + followTargetUuid set + NO baseTarget

2. **Updated UI to distinguish follow vs return-to-base** (BotPlayerInventoryScreenHandler.java):
   - Added new stat index 13 for "returning to base"
   - `isBotFollowing()` now returns false during return-to-base
   - `isBotReturningToBase()` new getter for the separate state

3. **Topics menu now shows correct state** (BotPlayerInventoryScreen.java):
   - "Follow" toggle only shows active when following a player (not returning to base)
   - "Return Home" now shows as a toggle - active when returning, clicking cancels it
   - Clicking "Return Home" when already returning will run `/bot stop` to cancel

4. **Guard against repeated `setReturnToBase()` calls** (BotEventHandler.java):
   - If already returning to the same/nearby base (within 3 blocks), skip reset
   - Logs debug message instead of resetting counters

5. **Previous session fixes preserved**:
   - Wall detection counts EITHER lower OR upper block as blocked
   - Mining escape tries all blocked directions
   - Touch chat says "On my way back to base" during return mode

### Key Files Modified

- **BotEventHandler.java** - Added `isReturningToBase()`, `isFollowingPlayer()`, guard in `setReturnToBase()`
- **BotPlayerInventoryScreenHandler.java** - Added stat index 13, fixed `isBotFollowing()`, added `isBotReturningToBase()`
- **BotPlayerInventoryScreen.java** - Updated topics menu for proper follow/return distinction

### Current State: Ready for Testing

The build is deployed to `~/Library/Application Support/PrismLauncher/instances/1.21.10/minecraft/mods/`

**Expected behavior:**
- When bot is returning to base, Topics menu shows "Return Home" as active (highlighted)
- "Follow" should NOT show as active during return-to-base
- Clicking "Return Home" while returning will cancel the return
- Stuck counter should NOT reset when touching the bot or re-triggering return
- Escape mechanisms should trigger at 200/400/600 ticks as designed

### If Mining Still Fails

The `tryMineBlock()` method uses:
- `ToolSelector.selectBestToolForBlock()` to pick the right tool
- `bot.interactionManager.tryBreakBlock(target)` for physical breaking
- Calculated mining time based on hardness and tool speed

Potential issues to investigate:
1. `tryBreakBlock()` may not work the same way for fake players
2. Mining time calculation may be too short
3. Bot may not be facing the block correctly

### User's Broader Suggestions (Not Yet Implemented)

1. **Study villager pathfinding to beds** - How do villagers navigate around obstacles?
2. **Dynamic re-pathing** - Update path every few blocks to find easier routes around obstacles
3. **Use existing PathFinder.java** - There's already a bi-directional A* pathfinder that could be better integrated

### Build Command
```bash
cd /Users/roti/AI-Player-checkpoint
./gradlew build --no-daemon
cp "build/libs/ai-player-1.0.6-release+1.21.11.jar" ~/Library/Application\ Support/PrismLauncher/instances/1.21.10/minecraft/mods/
```

### Minecraft Version
- Minecraft 1.21.11 with Fabric Loader 0.18.3
- Instance folder named "1.21.10" but runs 1.21.11
