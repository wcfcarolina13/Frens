# Testing Feedback Analysis - Session 2025-11-17

## Latest Testing Session (17:48) - Critical Issues Found

### Issue 1: Block Explosions During Drop Sweep (CONFIRMED - Suffocation System)
**Evidence from code:**
```java
// BotEventHandler.java line 1682
boolean cleared = BotActions.digOut(bot, true);

// BotActions.java line 339-363
public static boolean digOut(ServerPlayerEntity bot, boolean forceBreak) {
    // Breaks 3 vertical: origin, up, up(2)
    // Breaks 8 horizontal: all 4 directions + their up positions
    // Total: UP TO 11 BLOCKS broken aggressively
}
```

**Root Cause:**
- User was correct: block explosions are from suffocation aversion
- `digOut()` breaks up to 11 blocks when bot detects wall collision
- With `forceBreak=true`, breaks any block with hardness <= 5.0f (stone, dirt, etc.)
- Triggers during normal movement when bot bumps into blocks
- This is what causes "wildly breaking blocks" during drop_sweep

**User's Recommendation (CORRECT):**
1. Break ONLY the block causing suffocation (typically head position)
2. Check for IN_WALL damage type specifically
3. Don't break blocks just from collision - only from damage
4. If no proper tool, pop ONLY the suffocating block

**Proposed Fix:**
Replace aggressive `digOut()` with targeted `handleSuffocationDamage()`:
```java
private static boolean handleSuffocationDamage(ServerPlayerEntity bot) {
    // Only break blocks if actively taking IN_WALL damage
    if (bot.getRecentDamageSource() == null || 
        !bot.getRecentDamageSource().isOf(DamageTypes.IN_WALL)) {
        return false;
    }
    
    // Break head block first (most common suffocation point)
    BlockPos head = bot.getBlockPos().up();
    if (breakSuffocatingBlock(world, head, bot)) {
        return true;
    }
    
    // If still suffocating, break feet block
    BlockPos feet = bot.getBlockPos();
    return breakSuffocatingBlock(world, feet, bot);
}

private static boolean breakSuffocatingBlock(ServerWorld world, BlockPos pos, ServerPlayerEntity bot) {
    BlockState state = world.getBlockState(pos);
    if (state.isAir()) return false;
    
    // Select best tool for this block
    String keyword = preferredToolKeyword(state);
    if (keyword != null) {
        BotActions.selectBestTool(bot, keyword, "sword");
    }
    
    // Break only this one block
    return breakBlock(world, pos, bot, true); // forceBreak if no tool
}
```

---

### Issue 2: Inventory Full Message Spam (CONFIRMED)
**Evidence from logs:**
```
[11:44:41] Inventory's full! Continuing…
[11:44:41] Inventory's full! Continuing…
[11:44:42] Inventory's full! Continuing…
[11:44:42] Inventory's full! Continuing…
[11:44:43] Inventory's full! Continuing…
```

**Root Cause:**
- `handleInventoryFull()` is called EVERY time a block is mined
- When inventory is full, message appears on every single mine operation
- No cooldown or state tracking
- Results in 5+ messages in 2 seconds

**Proposed Fix:**
Add cooldown tracking:
```java
private static final Map<UUID, Long> LAST_INVENTORY_FULL_MESSAGE = new HashMap<>();
private static final long INVENTORY_MESSAGE_COOLDOWN_MS = 5000; // 5 seconds

private boolean handleInventoryFull(ServerPlayerEntity player, ServerCommandSource source) {
    boolean shouldPause = SkillPreferences.pauseOnFullInventory(player);
    if (!isInventoryFull(player)) {
        LAST_INVENTORY_FULL_MESSAGE.remove(player.getUuid());
        return false;
    }
    
    // Check cooldown
    Long lastMessage = LAST_INVENTORY_FULL_MESSAGE.get(player.getUuid());
    long now = System.currentTimeMillis();
    if (lastMessage != null && (now - lastMessage) < INVENTORY_MESSAGE_COOLDOWN_MS) {
        return shouldPause; // Skip message, but still honor pause setting
    }
    
    LAST_INVENTORY_FULL_MESSAGE.put(player.getUuid(), now);
    
    if (shouldPause) {
        // Pause message
    } else {
        ChatUtils.sendChatMessages(..., "Inventory's full! Continuing…");
        return false;
    }
}
```

---

### Issue 3: Torch Still Breaking (ONGOING)
**Current Implementation:**
- Detect torch BEFORE mining (wasTorch flag)
- After mining completes, call `TorchPlacer.replaceTorchAt()`
- Places torch at exact same location

**Problems:**
1. Timing: Bot mines torch, moves forward, then replacement happens (bot already moved)
2. Placement: Torch placed at current position, bot immediately mines it again
3. No pause between torch break and continuation

**User's Suggestions:**
1. **Micro-pause**: Pause briefly when torch breaks to let replacement catch up
2. **Offset placement**: Place torch 1 block behind and adjacent (not directly adjacent)

**Proposed Fix (Combined Approach):**
```java
// After mining a torch:
if (wasTorch) {
    // Calculate 1-2 blocks behind bot's movement direction
    Direction facing = player.getHorizontalFacing();
    BlockPos behindPos = detectedPos.offset(facing.getOpposite());
    
    // Place torch behind, not at current position
    TorchPlacer.replaceTorchAt(player, behindPos);
    
    // Brief pause to ensure placement completes
    Thread.sleep(100);
}
```

Alternative: Skip torches entirely in protected block check (already done in some skills)

---

## Original Issues (Session 17:13)

### 1. Bot Breaking Torches Without Replacing Them
[Previous analysis remains valid - now with additional offset placement strategy]

### 2. Climbing vs Teleporting  
[Analysis complete - no climbing code exists, movement is normal step-up]

### 3. Drop Sweep Breaking Blocks
**NOW SOLVED**: It's the suffocation system, not drop_sweep itself

### 4. Inventory Full Message Not Appearing
**NOW SOLVED**: Message appears but SPAMS (needs cooldown)

### 5. Drop Sweep Teleport Setting
[Config persistence - user needs to save UI]

---

## Updated Priority List

### P0 (Critical - Breaks Gameplay):
1. **Fix suffocation system** - Only break suffocating block, not 11 blocks
2. **Fix inventory message spam** - Add 5-second cooldown

### P1 (Important - Improves Experience):
3. **Improve torch placement** - Place behind bot, add micro-pause
4. **Verify config persistence** - Add logging for drop sweep teleport

### P2 (Nice to Have):
5. **Periodic darkness checks** - Belt-and-suspenders for torch coverage

---

## Files to Modify

### For Suffocation Fix:
- `BotEventHandler.java` - Replace `digOut()` call with targeted damage response
- `BotActions.java` - Consider adding `handleSuffocationDamage()` method

### For Inventory Spam Fix:
- `CollectDirtSkill.java` - Add cooldown map and check in `handleInventoryFull()`
- May need to add to other mining skills if they have same pattern

### For Torch Placement Improvement:
- `DirtShovelSkill.java` - Calculate behind position, add pause
- `TorchPlacer.java` - Possibly add `replaceTorchBehind()` variant

---

## Testing Checklist

After fixes:
1. Run drop_sweep - verify NO unexpected block breaking
2. Fill inventory during mining - verify message appears ONCE per 5 seconds
3. Mine with torches - verify torch placement doesn't result in immediate re-mining
4. Test suffocation in tight spaces - verify only head/feet blocks break
5. Spawn bot in wall - verify gentle extraction, not explosion

### 1. Bot Breaking Torches Without Replacing Them
**Evidence from logs:**
- Line 3688: "Found 2 drops, closest is Torch."
- Line 3704: "Drop sweep collected Torch"  
- Line 3714: "Placed torch at class_2338{x=89, y=2, z=23}"

**Root Cause:**
Bot broke a torch block, it dropped as an item, then bot placed a new torch later. The problem is that torch placement happens:
- After clearing work volume in stairs/stripmine
- Based on step count (every 6 steps)
- Based on darkness check in generic mining

If bot breaks a torch that's NOT in its placement pattern, the area stays dark.

**Proposed Solutions:**
1. **Option A (Safer)**: Check if mined block was a torch → immediately place replacement at same location
2. **Option B**: Check darkness after every block break in tunneling tasks
3. **Combined**: Both - replace broken torches immediately AND check darkness periodically

**Recommendation**: Use Option A (immediate replacement when breaking torch)

---

### 2. Climbing/Teleporting Behavior
**Evidence from logs:**
- Position changes: y=14 → y=15 (1 block up)
- "Bot moved to position - x: 86 y: 15 z: -1"

**Analysis:**
- No climbing code found in codebase
- Y+1 movement is normal vanilla step-up or jump
- Config shows `teleportDuringSkills: false`
- `teleportDuringDropSweep` field may not be in saved config (new feature)

**Findings:**
1. No spider-climbing ability exists in the code
2. Movement is standard pathfinding (jump/step-up)
3. Config file may need regeneration to include new fields

**Action Needed:**
- Verify config persistence (user should save config UI after enabling drop sweep TP toggle)
- No climbing toggle needed (feature doesn't exist)

---

### 3. Drop Sweep "Wildly Breaking Blocks"
**Investigation:**
- DropSweepSkill code reviewed: NO block breaking logic
- Only calls MovementService and DropSweeper
- Both only NAVIGATE, don't break blocks

**Possible Causes:**
1. MovementService pathfinding might break blocks for path clearing (not found in code)
2. User might be seeing block breaking from PREVIOUS mining task
3. "all" parameter might be misinterpreted

**Code Check Needed:**
- Verify MovementService doesn't break blocks
- Check if GoTo/pathfinding breaks blocks
- Verify "all" parameter behavior

---

### 4. Inventory Full Message Not Appearing
**Code Analysis:**
```java
private boolean handleInventoryFull(ServerPlayerEntity player, ServerCommandSource source) {
    boolean shouldPause = SkillPreferences.pauseOnFullInventory(player);
    if (!isInventoryFull(player)) {
        return false;  // Message only shows if FULL
    }
    if (shouldPause) {
        // Pause message
    } else {
        ChatUtils.sendChatMessages(..., "Inventory's full! Continuing…");
        return false;
    }
}
```

**Problem:**
`isInventoryFull()` is VERY strict:
- Checks every slot is non-empty
- Checks every stack is at max count (64 for cobblestone, etc.)
- Mining rarely reaches this state (partial stacks common)

**Proposed Fix:**
Make inventory check less strict OR check more frequently during mining.

**Options:**
1. Check if < 3 empty slots (more practical)
2. Check if can't pick up more of target item
3. Check percentage full (e.g., > 90%)

---

### 5. Drop Sweep Teleport Setting
**Status:**
- Code implemented correctly
- Config UI has toggle
- BotControlApplier applies setting

**Issue:**
Config file from log (line 639) doesn't show `teleportDuringDropSweep` field.

**Likely Cause:**
- Old config file format
- User needs to open config UI and SAVE to persist new fields
- Or config didn't serialize properly

**Action:**
- Add logging to confirm setting is being loaded
- Verify config save includes new field

---

## Recommended Fixes Priority

### P0 (Critical - Do Now):
1. **Torch Replacement**: Add immediate torch replacement when mining a torch block
2. **Inventory Message**: Fix inventory full detection to be less strict

### P1 (Important):
3. **Verify Drop Sweep**: Confirm no block breaking in movement code
4. **Config Persistence**: Add logging for drop sweep teleport setting

### P2 (Nice to Have):
5. **Darkness Checks**: Add periodic darkness checks in tunneling (belt-and-suspenders)

---

## Implementation Plan

### Fix 1: Torch Replacement
**Files to modify:**
- `DirtShovelSkill.java` - Add post-mine torch check
- `StripMineSkill.java` - Add post-mine torch check  
- `CollectDirtSkill.java` - Add post-mine torch check in stairs

**Logic:**
```java
// After successfully mining a block:
Block minedBlock = state.getBlock();
if (isTorch(minedBlock)) {
    // Place torch at same location immediately
    TorchPlacer.PlacementResult result = TorchPlacer.placeTorchAt(player, minedBlockPos);
}
```

### Fix 2: Inventory Full Detection
**File:** `CollectDirtSkill.java` - `isInventoryFull()`

**Change:**
```java
private boolean isInventoryFull(ServerPlayerEntity player) {
    if (player == null) return false;
    PlayerInventory inv = player.getInventory();
    
    // Less strict: check if fewer than 3 empty slots
    int emptyCount = 0;
    for (int i = 0; i < inv.main.size(); i++) {
        if (inv.main.get(i).isEmpty()) emptyCount++;
    }
    return emptyCount < 3;
}
```

### Fix 3: Verify Movement Code
**Files to check:**
- `MovementService.java`
- `GoTo.java`
- Log where blocks are broken during drop_sweep

### Fix 4: Config Logging
**File:** `BotControlApplier.java`

**Add:**
```java
LOGGER.info("Applying drop sweep teleport: {} for bot {}", 
    settings.isTeleportDuringDropSweep(), bot.getName().getString());
```

---

## Testing Notes

### What to Test:
1. Mine in dark area - break torch - verify immediate replacement
2. Fill inventory to ~90% - verify message appears  
3. Run drop_sweep - verify NO blocks broken
4. Save config UI - verify teleportDuringDropSweep persists
5. Toggle drop sweep TP - verify it affects behavior

### Expected Behaviors:
- Torches always replaced immediately when broken
- Inventory message appears before completely full
- Drop sweep only moves, never breaks blocks
- Config persists between restarts
