# Task Queue - Active Implementation Backlog

# Task Queue - Active Implementation Backlog

## Critical Investigations (P0)

### Investigation 1: Bot Stats Not Persisting on Respawn
**Status:** NEW - Requires Investigation  
**Assigned:** Next Session  
**Estimated Time:** 1 hour

**Context:**
User reports that `/bot spawn <alias> training` resets bot's health, level, and hunger instead of restoring saved state. Inventory IS being persisted correctly after recent fixes.

**Investigation Steps:**
1. Check where bot stats are saved (NBT file?)
2. Verify stats are being written during save
3. Check if stats are being loaded on respawn
4. Compare with inventory persistence implementation
5. Ensure stats restoration happens before spawn

**Expected Behavior:**
Bot should retain health, XP level, and hunger across respawns (not deaths), just like inventory.

**Success Criteria:**
- Bot remembers health/level/hunger when respawned with same alias
- Stats properly saved to NBT/JSON
- Stats properly loaded on respawn
- Clear log trail of save/load operations

---

### Investigation 2: Upward Stairs Complete Overhaul Needed
**Status:** CRITICAL - Current Implementation Wrong  
**Assigned:** Next Session  
**Estimated Time:** 2-3 hours

**Context:**
Current upward stairs implementation is over-complicated and fails frequently. User clarified expected behavior on 2025-11-18 04:43.

**User's Simple Requirement:**
"When making stairs, say there's a solid wall of thick blocks ahead. It should mine 3 blocks above the first block, leaving that first block. Then, it hops onto that block and mines 3 blocks above the next block. In this way, it tunnels upward, stepping up one block at a time."

**Current Problems from Logs:**
```
[22:40:56] Upward stairs: current Y=-3, target Y=7, carving from 83, -3, 0 to 83, -2, 0
[22:40:56] Mining 5 blocks in work volume for upward stairs
[22:40:56] Upward stairs: forward is air, gap depth = 1
[22:40:59] ERROR: Upward stairs failed to increase Y! Last=-3, Current=-3
[22:40:59] Bot stuck in blocks! Breaking 15 blocks to escape
[22:41:01] Staircase aborted: failed to ascend (Y didn't increase)
```

**Root Causes:**
1. Direction not locked from controller-player's facing direction
2. Complex movement logic with pathfinding for upward (doesn't work)
3. Unclear when to place blocks vs when to just mine
4. Bot position not increasing correctly after jump
5. Mining volume wrong (should be 3 blocks above floor, not 5)

**Simplified Algorithm Needed:**
```
1. Lock direction from controller-player facing (MAINTAIN throughout)
2. While currentY < targetY:
   a. Mine 3 blocks above current position (head, head+1, head+2)
   b. Leave floor block (current position)
   c. Face forward and jump to climb up 1 block
   d. Wait for physics to complete
   e. Verify Y increased by 1
   f. If Y didn't increase, abort with clear message
3. No block placement unless gap detected (missing floor block)
```

**Success Criteria:**
- Bot maintains controller-player's facing direction throughout
- Ascends exactly 1 block at a time
- Mines exactly 3 blocks above each position
- No unnecessary block placement
- Clear, simple implementation easy to debug
- Works reliably in solid terrain

**Files to Rewrite:**
- `CollectDirtSkill.java` - `runStraightStaircase()` method for upward case

---

## Completed (Recent Sessions)

### Session 2025-11-18 02:23
- ✅ **Breaking Free When Spawned in Walls** - Bot successfully breaks obstructing blocks
- ✅ **Upward Stairs Begin Direction** - Bot starts in correct direction (but still has issues)

### Session 2025-11-17 22:46
- ✅ Mining sync retry mechanism (3x 100ms delays)
- ✅ Post-task suffocation safety check
- ✅ Immediate inventory save on screen close
- ✅ Fixed pre-existing BotEventHandler compilation error

### Session 2025-11-17 Earlier
- ✅ Inventory persistence race condition
- ✅ Drop sweep block breaking issue
- ✅ Inventory full message appearing
- ✅ Torch protection enhancements
- ✅ Torch replacement during mining

---

## Active Queue

### Task 1: Protected Zones Persistence
**Status:** Queued  
**Priority:** P1  
**Estimated Time:** 2-3 hours

**Description:**
Protected zones currently only exist in memory. Need JSON persistence.

**Implementation Plan:**
```json
// config/ai-player/protected_zones.json
{
  "zones": [
    {
      "id": "uuid",
      "center": {"x": 100, "y": 64, "z": 200},
      "radius": 10,
      "creator": "player-uuid",
      "createdAt": 1234567890,
      "dimension": "minecraft:overworld",
      "label": "Main Base"
    }
  ]
}
```

**Files to Modify:**
- Create `ProtectedZoneService.java`
- Add zone persistence methods
- Hook into server startup (load zones)
- Hook into zone commands (save on create/remove)

**Success Criteria:**
- Zones persist across restarts
- Per-dimension storage
- JSON serialization works
- No performance impact

---

### Task 2: Bot Config UI Refactor
**Status:** Queued  
**Priority:** P1  
**Estimated Time:** 4-6 hours

**Description:**
Refactor config screen from scrolling list to single-bot view with dropdown.

**Design:**
```
┌─────────────────────────────────────┐
│  Bot Configuration                  │
├─────────────────────────────────────┤
│  Alias: [Jake ▼]                    │
├─────────────────────────────────────┤
│  ┌─ Spawning ──────────────────┐   │
│  │ ☑ Auto Spawn                │   │
│  │ Mode: [Training ▼]           │   │
│  └──────────────────────────────┘   │
│  ┌─ Movement ──────────────────┐   │
│  │ ☐ Teleport During Skills    │   │
│  │ ☐ Drop Sweep Teleport        │   │
│  └──────────────────────────────┘   │
│  [... scrollable ...]               │
├─────────────────────────────────────┤
│        [Save]     [Cancel]          │
└─────────────────────────────────────┘
```

**Implementation Steps:**
1. Add dropdown widget for alias selection
2. Create scrollable container for settings
3. Group settings with section headers
4. Modify save logic (single bot only)
5. Test state management
6. Verify all features still work

**Files to Modify:**
- `BotControlScreen.java` (major refactor)

**Success Criteria:**
- Dropdown works smoothly
- Settings scroll properly
- Save only affects selected bot
- Clear visual grouping
- No feature regression

---

## Future Tasks (Not Queued)

### High Priority
- Swimming & boats implementation
- Portal following
- Crafting basics
- Building primitives

### Medium Priority  
- Farming loop
- Animal husbandry
- Advanced mining features
- Sleep integration

### Low Priority
- Multi-bot features
- PVP/formations
- LLM integration phases

---

## Implementation Notes

### Debugging Tips
1. Always check logs first
2. Add timing logs for async operations
3. Verify flag states at each step
4. Test with minimal setup before complex scenarios

### Common Pitfalls
- Server-client sync delays (use retries)
- Async task scheduling (use server.execute)
- Flag not cleared in finally blocks
- Race conditions with concurrent maps

### Best Practices
- Add logging before/after critical operations
- Use try-finally for flag management
- Document assumptions in code comments
- Test edge cases (no items, stuck in blocks, etc.)

### Task 1: Protected Zones Persistence (High Priority)
**Status:** Queued  
**Estimated Complexity:** Medium  
**Dependencies:** None

**Description:**
Protected zones (areas where bots shouldn't break blocks) currently only exist in memory during the session. They need to persist across server restarts.

**Requirements:**
- Save protected zones to JSON config file when created/modified
- Load protected zones on server start
- Preserve all zone data: position, radius, creator, timestamp
- Handle multiple zones per bot or global zones
- Maintain backward compatibility with existing configs

**Files Likely Affected:**
- MiningHazardDetector.java (current in-memory storage)
- ManualConfig.java (add protected zones section)
- BotControlApplier.java or similar (load on startup)

**Success Criteria:**
- Protected zones survive server restart
- Can be viewed/managed via commands
- JSON serialization/deserialization works
- No performance degradation

---

### Task 2: Bot Config UI Refactor (High Priority)
**Status:** Queued  
**Estimated Complexity:** High  
**Dependencies:** None

**Description:**
Current bot config screen shows ALL bots in a long, off-screen list. Refactor to show one bot at a time with a dropdown selector.

**User Requirements** (from wireframe):
```
┌─────────────────────────────────────┐
│  Bot Configuration                  │
├─────────────────────────────────────┤
│  Alias: [Jake ▼]                    │
├─────────────────────────────────────┤
│  ┌─ Spawning ──────────────────┐   │
│  │ ☑ Auto Spawn                │   │
│  │ Mode: [Training ▼]           │   │
│  └──────────────────────────────┘   │
│  ┌─ Movement ──────────────────┐   │
│  │ ☐ Teleport During Skills    │   │
│  │ ☐ Drop Sweep Teleport        │   │
│  └──────────────────────────────┘   │
│  ┌─ Inventory ─────────────────┐   │
│  │ ☐ Pause When Full           │   │
│  └──────────────────────────────┘   │
│  ┌─ AI ───────────────────────┐   │
│  │ ☑ LLM Enabled               │   │
│  └──────────────────────────────┘   │
│                                     │
│  [Scrollable if many options]      │
├─────────────────────────────────────┤
│        [Save]     [Cancel]          │
└─────────────────────────────────────┘
```

**Behavior Specs:**
1. Alias dropdown at top
   - Lists all configured bot aliases
   - Selecting changes entire panel below
   - Optional: Bold/highlight active bots

2. Settings panel (scrollable)
   - Vertical list of toggles/controls
   - Group related settings with section headers
   - Sections: Spawning, Movement, Inventory, AI
   - Each toggle has clear label and tooltip

3. Save/Cancel buttons
   - Bottom of screen, always visible
   - Save applies ONLY to selected alias
   - Cancel discards changes

**Files to Modify:**
- BotControlScreen.java (major refactor)
  - Replace Row list with single-bot view
  - Add dropdown widget for alias selection
  - Add ScrollableWidget for settings list
  - Group toggles into sections
  - Modify save logic (single bot only)

**Technical Challenges:**
- Layout management (dropdown + scrollable + buttons)
- State management (which bot is selected)
- Section headers (visual grouping)
- Scroll container sizing
- Maintaining all current functionality

**Success Criteria:**
- All bot settings remain functional
- UI fits on standard screen sizes
- Settings scroll smoothly
- Save only affects selected bot
- Clear visual grouping
- No regression in existing features

---

## Completed Tasks

### P0 Fixes (Session 2025-11-17)
- ✅ Torch replacement when mining torches
- ✅ Less strict inventory full detection

### Session 2025-11-17 16:29
- ✅ Position tracking for stripmine/stairs resume
- ✅ Drop sweep teleport control
- ✅ Config UI toggle for drop sweep teleport

### Session 2025-11-17 16:23
- ✅ Enhanced torch protection (double-layer)
- ✅ Inventory full continuing message

---

## Implementation Notes

### For Task 1 (Protected Zones):
Consider using a structure like:
```json
{
  "protectedZones": [
    {
      "id": "uuid-string",
      "center": {"x": 100, "y": 64, "z": 200},
      "radius": 10,
      "createdBy": "playerUUID",
      "timestamp": 1234567890,
      "dimension": "minecraft:overworld"
    }
  ]
}
```

### For Task 2 (Config UI):
Key design decisions:
- Use CyclingButtonWidget for toggles (existing pattern)
- Consider ScrollableWidget or custom scroll container
- Section headers as static Text widgets (no interaction)
- Dropdown uses existing dropdown widget pattern
- Maintain BotControlSettings as data model (no change)

---

## Future Considerations

### Additional Tasks (Not Yet Queued):
- Verify MovementService doesn't break blocks (from testing feedback)
- Add logging for drop sweep teleport setting (debugging)
- Periodic darkness checks in tunneling (belt-and-suspenders)
- Square mode in-game testing/verification

### Technical Debt:
- Config file format migration (add new fields to old saves)
- Performance profiling for large bot counts
- UI scaling for different screen resolutions
- Localization/internationalization support
