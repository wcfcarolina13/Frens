# Task Queue - Prioritized Implementation Backlog

## Active Queue

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
