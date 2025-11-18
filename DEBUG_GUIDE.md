# Debugging Guide for Critical Issues

## Issue 1: Suffocation After Task Abort

### What to Look For in Logs

When a mining task aborts, you should see this sequence:

```
[timestamp] [Server thread/INFO]: Task 'skill:mining' finished with state ABORTED
[timestamp] [Server thread/INFO]: Scheduling post-task safety check for bot Jake after task 'skill:mining'
[timestamp] [Server thread/INFO]: Running post-task safety check for bot Jake at position [x, y, z]
[timestamp] [Server thread/INFO]: Suffocation check for bot Jake at [x, y, z]: head=Stone (blocked=true), feet=Air (blocked=false)
[timestamp] [Server thread/WARN]: Bot Jake is stuck in blocks! Head blocked: true, Feet blocked: false - attempting escape
[timestamp] [Server thread/INFO]: Bot Jake attempting Strategy 1: break with appropriate tools
[timestamp] [Server thread/INFO]: Bot Jake escaped using Strategy 1
[timestamp] [Server thread/INFO]: Bot Jake successfully escaped from burial
```

### Possible Failure Scenarios

**Scenario A: Safety Check Never Runs**
```
Task 'skill:mining' finished with state ABORTED
[NO SCHEDULING MESSAGE]
```
**Diagnosis:** server.execute() is not working or ticket has null source
**Fix:** Check TaskService.complete() - add more null checks

**Scenario B: Safety Check Runs But Bot Not Detected as Stuck**
```
Scheduling post-task safety check for bot Jake
Running post-task safety check for bot Jake at position [x, y, z]
Suffocation check for bot Jake at [x, y, z]: head=Air (blocked=false), feet=Air (blocked=false)
Bot Jake is clear - no suffocation detected
```
**Diagnosis:** Bot position has air blocks (not actually stuck) OR bot is crawling (head at different Y)
**Fix:** Check if bot.isCrawling() - may need to check different positions for crawling bots

**Scenario C: Escape Strategies All Fail**
```
Bot Jake is stuck in blocks! Head blocked: true, Feet blocked: true
Bot Jake attempting Strategy 1: break with appropriate tools
Bot Jake Strategy 1 failed, attempting Strategy 2: break adjacent blocks
Bot Jake Strategy 2 failed, attempting Strategy 3: force-break
Bot Jake could NOT escape suffocation after all 3 strategies - MANUAL INTERVENTION NEEDED
```
**Diagnosis:** Bot has no tools, blocks are unbreakable, or breaking logic not working
**Fix:** Check ensureRescueTool(), breakSuffocatingBlock(), etc.

### Testing Instructions

1. Run a mining task that will abort:
   ```
   /bot skill mining depth 50 stairs Jake
   ```
   
2. Let it run until it aborts (hit hazard, run out of torches, etc.)

3. Check logs immediately after abort for the sequence above

4. If bot takes damage, note the timing:
   - How many ticks between abort and damage?
   - Does safety check run before or after damage?
   - What is bot's actual position?

---

## Issue 2: Upward Stairs Aborting

### What to Look For in Logs

When building upward stairs, you should see:

```
[timestamp] [pool-5-thread-1/INFO]: Upward stairs: current Y=64, target Y=100, carving from [x, y, z] to [x, y+1, z]
[timestamp] [pool-5-thread-1/INFO]: Mining 5 blocks in work volume for upward stairs
[timestamp] [pool-5-thread-1/INFO]: Upward stairs: checking step block at [x, y, z] - isAir=true
[timestamp] [pool-5-thread-1/INFO]: Step block is air, attempting to place building block
[timestamp] [pool-5-thread-1/INFO]: Successfully placed step block at [x, y, z]
```

### Common Abort Scenarios

**Scenario A: Mining Block Fails (Sync Issue)**
```
Mining 5 blocks in work volume for upward stairs
Block [x, y, z] still present after mining and 3 retries - block type: Stone
Failed to mine block at [x, y, z] - aborting staircase
Staircase aborted: unable to clear the stairwell
```
**Diagnosis:** Sync retry mechanism not working, or block truly can't be mined
**Check:** Is the block actually minable? Does bot have right tool?

**Scenario B: Out of Building Blocks**
```
Upward stairs: checking step block at [x, y, z] - isAir=true
Step block is air, attempting to place building block
Failed to place step block - out of building materials
Staircase paused: need cobblestone or dirt for steps
```
**Diagnosis:** Bot ran out of cobble/dirt/stone
**Solution:** This is working as intended - give bot more blocks

**Scenario C: Step Block Already Solid**
```
Upward stairs: checking step block at [x, y, z] - isAir=false
Step block already solid (Deepslate), no placement needed
```
**Diagnosis:** Block is already there (from previous mining?) - this is OK

### Understanding the Implementation

**Current Behavior:**
- Bot at Y=64, target Y=100
- Each iteration:
  1. Dig forward (horizontal move)
  2. Dig forward.up() (diagonal up move)
  3. Clear 5 blocks high for headroom (STRAIGHT_STAIR_HEADROOM=4)
  4. Place a block at forward position (to step on)
  5. Move to forward.up() (now at Y=65)

**Result:** Creates a 1-block-per-step diagonal staircase going up

**Key Question for User:**
Is this the intended design? Or should it only place blocks when height gap >1?

### Alternative Implementation (If User Wants)

**Option A: Only place when needed**
```java
if (goingUp) {
    int heightDiff = stairFoot.getY() - currentFeet.getY();
    if (heightDiff > 1) {
        // Place intermediate block(s)
    } else {
        // Can jump naturally
    }
}
```

**Option B: True diagonal stairs (no blocks needed)**
- Just dig forward and up each iteration
- Let bot jump naturally (vanilla can jump 1 block)
- No block placement needed at all

### Testing Instructions

1. Give bot plenty of cobblestone (e.g., 64 blocks)

2. Run upward stairs command:
   ```
   /bot skill mining depth 20 stairs Jake
   ```
   (Assuming bot is at Y=60, this goes to Y=80)

3. Watch logs for:
   - How many blocks in work volume each iteration
   - Whether step placement succeeds
   - Which blocks fail to mine (if any)
   - Y coordinate progression

4. If it aborts, note:
   - Exact Y when it aborted
   - Which block failed to mine
   - Whether it ran out of building blocks

---

## General Debugging Tips

### Enable Debug Logging
Logs now include:
- Task completion and scheduling
- Suffocation detection details
- Mining retry attempts
- Upward stairs block placement

### Common Log Patterns

**Success:**
```
Task finished → Scheduling safety check → Running safety check → Bot is clear
```

**Failure (needs investigation):**
```
Task finished → [missing scheduling] → Bot takes damage
```

**Mining success with retries:**
```
Block not air yet, retry 1/3
Block not air yet, retry 2/3
Block cleared after 2 retries
```

### What to Report

When reporting issues, include:
1. The exact command used
2. Bot's starting position (Y coordinate especially)
3. Log snippet showing the failure
4. What you expected vs what happened
5. Bot's inventory contents (for upward stairs)

---

## Quick Reference: Log Levels

- **INFO** - Normal operation (task start/finish, movement, placement)
- **WARN** - Potential issues (retries, couldn't place block)
- **ERROR** - Actual failures (mining failed, can't escape)
- **DEBUG** - Detailed traces (retry attempts, null checks)
