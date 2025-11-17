# Testing Feedback Analysis - Session 2025-11-17

## Issues Reported

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
