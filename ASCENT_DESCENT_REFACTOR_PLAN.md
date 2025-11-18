# Ascent/Descent Refactor Plan

## Goal
Separate ascent and descent into distinct, well-working commands with their own implementations.

## New Command Structure

### Commands
1. **`/bot skill mining ascent <blocks> <alias>`**
   - Relative: climb UP by <blocks> Y-levels
   - Example: `ascent 10` climbs from Y=70 to Y=80

2. **`/bot skill mining ascent-y <target-y> <alias>`**
   - Absolute: climb UP to Y=<target-y>
   - Example: `ascent-y 100` climbs from Y=70 to Y=100

3. **`/bot skill mining descent <blocks> <alias>`**
   - Relative: dig DOWN by <blocks> Y-levels  
   - Example: `descent 10` digs from Y=70 to Y=60

4. **`/bot skill mining descent-y <target-y> <alias>`**
   - Absolute: dig DOWN to Y=<target-y>
   - Example: `descent-y 50` digs from Y=70 to Y=50

### Removed Parameters
- ❌ `depth` / `depth-z` (replaced by ascent/descent variants)
- ❌ `stairs` (baked into ascent/descent behavior)

## Implementation Details

### Command Parsing (modCommandRegistry.java)
```java
// In executeSkill method, replace depth/stairs parsing with:

Integer ascentBlocks = null;
Integer ascentTargetY = null;
Integer descentBlocks = null;
Integer descentTargetY = null;

for (int i = 0; i < tokens.length; i++) {
    String token = tokens[i];
    
    if ("ascent".equalsIgnoreCase(token) && i + 1 < tokens.length) {
        ascentBlocks = Integer.parseInt(tokens[++i]);
        continue;
    }
    if ("ascent-y".equalsIgnoreCase(token) && i + 1 < tokens.length) {
        ascentTargetY = Integer.parseInt(tokens[++i]);
        continue;
    }
    if ("descent".equalsIgnoreCase(token) && i + 1 < tokens.length) {
        descentBlocks = Integer.parseInt(tokens[++i]);
        continue;
    }
    if ("descent-y".equalsIgnoreCase(token) && i + 1 < tokens.length) {
        descentTargetY = Integer.parseInt(tokens[++i]);
        continue;
    }
    // ... rest of parsing
}

// Set parameters
if (ascentBlocks != null) {
    params.put("ascentBlocks", ascentBlocks);
} else if (ascentTargetY != null) {
    params.put("ascentTargetY", ascentTargetY);
} else if (descentBlocks != null) {
    params.put("descentBlocks", descentBlocks);
} else if (descentTargetY != null) {
    params.put("descentTargetY", descentTargetY);
}
```

### Skill Execution (CollectDirtSkill.java)

```java
// In execute() method:
Integer ascentBlocks = getOptionalIntParameter(context, "ascentBlocks");
Integer ascentTargetY = getOptionalIntParameter(context, "ascentTargetY");
Integer descentBlocks = getOptionalIntParameter(context, "descentBlocks");
Integer descentTargetY = getOptionalIntParameter(context, "descentTargetY");

boolean ascentMode = (ascentBlocks != null || ascentTargetY != null);
boolean descentMode = (descentBlocks != null || descentTargetY != null);

if (ascentMode && playerForAbortCheck != null) {
    int targetY;
    if (ascentBlocks != null) {
        targetY = playerForAbortCheck.getBlockY() + ascentBlocks;
    } else {
        targetY = ascentTargetY;
    }
    return runAscent(context, source, playerForAbortCheck, targetY);
}

if (descentMode && playerForAbortCheck != null) {
    int targetY;
    if (descentBlocks != null) {
        targetY = playerForAbortCheck.getBlockY() - descentBlocks;
    } else {
        targetY = descentTargetY;
    }
    return runDescent(context, source, playerForAbortCheck, targetY);
}
```

### Descent Implementation (RESTORE OLD WORKING CODE)

```java
private SkillExecutionResult runDescent(SkillContext context,
                                       ServerCommandSource source,
                                       ServerPlayerEntity player,
                                       int targetDepthY) {
    // Use the OLD working implementation from git commit 514709c
    // This includes:
    // - MovementService for reliable positioning
    // - Hazard detection (lava/water)
    // - WorkDirectionService for pause/resume
    // - Torch placement every 6 steps
    // - buildStraightStairVolume for headroom
    // - Support checking
}
```

### Ascent Implementation (NEW SIMPLIFIED LOGIC)

```java
private SkillExecutionResult runAscent(SkillContext context,
                                      ServerCommandSource source,
                                      ServerPlayerEntity bot,
                                      int targetY) {
    // Simplified walk-and-jump approach:
    // 1. Lock direction from controller-player facing
    // 2. Walk forward until finding block at feet level
    // 3. If block found, clear 5 blocks above it
    // 4. Jump onto block
    // 5. Repeat until targetY reached
    // 6. If open air, just keep walking forward
    // 7. Include: hazard detection, inventory checks, torch placement
}
```

### Key Requirements

**Both ascent and descent must include:**
- ✅ Torch placement (TorchPlacer)
- ✅ Hazard detection (MiningHazardDetector - lava/water)
- ✅ Inventory full handling
- ✅ Pause/resume with WorkDirectionService
- ✅ Teleport toggle support (when bot can't return to resume position)
- ✅ Direction locking from controller-player facing
- ✅ Physical mining only (no remote mining)
- ✅ Threat detection (SkillManager.shouldAbortSkill)

**Ascent-specific:**
- 5-block headroom clearance
- Walk forward behavior in open spaces
- Jump mechanics for climbing

**Descent-specific:**
- Use old working implementation
- 4-block headroom clearance
- Support block verification
- MovementService pathfinding

## Files to Modify

1. **src/main/java/net/shasankp000/Commands/modCommandRegistry.java**
   - Update executeSkill() to parse ascent/descent tokens
   - Remove depth/stairs parsing

2. **src/main/java/net/shasankp000/GameAI/skills/impl/CollectDirtSkill.java**
   - Remove runStraightStaircase()
   - Add runAscent() - new implementation
   - Add runDescent() - restored old working implementation
   - Update execute() to call runAscent/runDescent

## Testing Plan

### Phase 1: Descent (should work immediately - it's the old code)
```
/bot skill mining descent 5 Jake
/bot skill mining descent-y 50 Jake
```

### Phase 2: Ascent (new implementation)
```
/bot skill mining ascent 5 Jake
/bot skill mining ascent-y 100 Jake
```

### Phase 3: Edge Cases
- Test pause/resume
- Test hazard encounters (lava/water)
- Test inventory full
- Test torch placement
- Test in open caves vs solid terrain

## Success Criteria

- ✅ Descent works exactly as it did before (no regressions)
- ✅ Ascent cleanly climbs using walk-and-jump
- ✅ Both support pause/resume
- ✅ Both detect and handle hazards
- ✅ Both place torches
- ✅ Both use physical mining/movement only
- ✅ Build compiles successfully
