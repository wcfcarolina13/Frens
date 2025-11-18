# Critical Issues Analysis - Session 2025-11-18

## Issue Summary

Based on latest.log testing at 20:02:33:

### 1. Upward Stairs Infinite Loop ❌ CRITICAL
**Symptom:** Bot repeats same action 129 times without progressing
**Log Evidence:**
```
[20:02:33] Upward stairs: current Y=-8, target Y=2, clearing headroom at 88, -8, -4 for move to 88, -7, -3
[20:02:33] Mining 4 blocks in work volume for upward stairs
[20:02:33] Step block already solid (Cobbled Deepslate), no placement needed
[20:02:33] Manual nudge succeeded after 1 attempts (dist 1.41 blocks)
[REPEATS 129 TIMES - Y NEVER CHANGES]
```

**Root Cause:** 
Bot position NEVER changes from Y=-8. The manual nudge "succeeds" but bot doesn't actually move UP.

**Why:**
The destination `stairFoot = forward.up()` means (88, -7, -3). Bot tries to move there with manual nudge, but:
1. Bot is at (88, -8, -4)
2. Target is (88, -7, -3) - one block north and one up
3. Manual nudge moves bot horizontally to -3 but NOT up to -7
4. Loop iteration checks `while Y < targetY` - since Y=-8 still, loop continues
5. Infinite loop

**The Fatal Flaw:** My previous "fix" changed the work volume to currentFeet but left stairFoot as forward.up(), creating a mismatch. Bot mines at current position but tries to move to a DIFFERENT position.

### 2. No Damage Event Handler Firing ❌ CRITICAL
**Symptom:** No "taking IN_WALL damage" logs when bot spawned in wall
**Expected:** Should see "Bot Jake taking IN_WALL damage - attempting immediate escape"
**Actual:** Nothing

**Possible Causes:**
1. Damage event handler not registered (compilation issue?)
2. DamageTypes.IN_WALL not matching actual damage source
3. Bot not actually taking damage (user destroyed blocks first?)

### 3. Stats Reset on Spawn ⚠️ NOT ADDRESSED
**Symptom:** `/bot spawn Jake training` resets stats
**Status:** Never investigated or fixed

## The Real Solution

The upward stairs logic is fundamentally broken. Here's what needs to happen:

### Current (Broken) Flow:
```
1. Bot at (88, Y=-8, -4)
2. Mine headroom at CURRENT position (88, -8, -4) ✓
3. Try to move to forward.up() = (88, -7, -3) ✗ WRONG!
4. Manual nudge moves horizontally but not UP
5. Loop checks Y=-8 < target=2, continues
6. INFINITE LOOP
```

### Correct Flow Should Be:
```
1. Bot at (88, Y=-8, -4)
2. Mine headroom at CURRENT position (Y=-8, -7, -6, -5) ✓
3. Move FORWARD (to 88, -8, -3) - horizontal only
4. Move UP (to 88, -7, -3) - vertical step
5. Check Y=-7 < target=2, continue
6. Repeat until Y=2
```

OR:

```
1. Bot at (88, Y=-8, -4)
2. Mine at CURRENT position for headroom
3. Place block at current.up() if needed (to stand on)
4. Move UP to current.up() = (88, -7, -4) - vertical only!
5. Mine forward for horizontal clearance
6. Move forward to (88, -7, -3)
7. Check Y=-7 < target=2, continue
```

## Why My Fixes Made It Worse

**Original Bug:** Mining blocks out of reach (at forward position)
**My "Fix":** Mine at current position instead
**New Bug:** Now mines at current but moves to forward.up() which doesn't advance Y coordinate properly

The movement logic expects to move diagonally (forward AND up) but manual nudge can't do that reliably.

## Required Fixes

### Fix 1: Match Work Volume with Destination
If we mine at currentFeet, we should also move to currentFeet.up(), NOT forward.up()

### Fix 2: Separate Vertical and Horizontal Movement
Don't try diagonal moves. Do vertical first, then horizontal (or vice versa).

### Fix 3: Verify Bot Actually Moved
After movement, check if Y coordinate actually increased. If not, ABORT with clear message.

### Fix 4: Add Loop Escape Hatch
If same position repeated 3 times, ABORT with "stuck in loop" message.

## Damage Handler Investigation Needed

Check if:
1. ALLOW_DAMAGE event is actually registered
2. Print log at START of event handler to confirm it fires
3. Check damage source types when spawning in wall
