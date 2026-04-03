# General Protected Zones — Design Spec

## Context

The mod has several zone protection systems (saved bases with radius, fortification hulls, mapped villages, and ProtectedZoneService with center+radius cubes), but creating and managing protected zones is command-only with no visual feedback. Admins can't see what they're protecting, can't precisely control boundaries, and non-admin players have no way to discover or visualize existing zones.

This feature replaces the ProtectedZoneService's center+radius model with an AABB (axis-aligned bounding box) model, adds an interactive wand-based selection workflow with particle visualization, integrates zone management into the Base Manager UI, and adds guide/tooltip coverage.

## Data Model — ProtectedZoneService AABB Migration

### Current → New

| Field | Current | New |
|-------|---------|-----|
| Shape definition | `BlockPos center` + `int radius` | `BlockPos minCorner` + `BlockPos maxCorner` |
| Containment check | Chebyshev distance ≤ radius | AABB bounds check (x/y/z between min and max) |

All other fields unchanged: `label`, `worldId`, `ownerUuid`, `ownerName`, `createdTime`, `accessMode`, `allowedOwnerUuids`.

### Migration

On load, if a zone entry has `centerX`/`centerY`/`centerZ` + `radius` fields (legacy format), auto-convert:
- `minCorner = (centerX - radius, centerY - radius, centerZ - radius)`
- `maxCorner = (centerX + radius, centerY + radius, centerZ + radius)`

New JSON format:
```json
{
  "label": "spawn-area",
  "minX": 100, "minY": -64, "minZ": 200,
  "maxX": 150, "maxY": 320, "maxZ": 250,
  "ownerUuid": "...",
  "ownerName": "...",
  "accessMode": "owner_only",
  "allowedOwnerUuids": []
}
```

### Contains check

```java
public boolean contains(BlockPos pos) {
    return pos.getX() >= minCorner.getX() && pos.getX() <= maxCorner.getX()
        && pos.getY() >= minCorner.getY() && pos.getY() <= maxCorner.getY()
        && pos.getZ() >= minCorner.getZ() && pos.getZ() <= maxCorner.getZ();
}
```

### Backward compatibility

The existing `/bot zone protect <radius> [label]` command continues to work. It computes the AABB from the looked-at block position ± radius, then calls the updated `createZone()` with minCorner/maxCorner.

### Additional method updates

Methods that reference `center`/`radius` must be updated:
- `removeZoneByPosition()` — currently measures distance to `zone.getCenter()`. Update to compute distance to AABB centroid: `((minX+maxX)/2, (minY+maxY)/2, (minZ+maxZ)/2)`
- `distanceFrom()` — same centroid approach
- `grantZoneAccess()`, `revokeZoneAccess()`, `setZoneAccessMode()` — all reconstruct `ProtectedZone` using the center+radius constructor. Update to use new `minCorner`/`maxCorner` constructor
- `executeZoneList()` in `modCommandRegistry.java` — currently prints `center=X,Y,Z, radius=N`. Update to print `min=X,Y,Z, max=X,Y,Z`

### JSON format — include createdTime

The existing `ZoneData` omits `createdTime` (pre-existing bug). The new format includes it:
```json
{
  "label": "spawn-area",
  "minX": 100, "minY": -64, "minZ": 200,
  "maxX": 150, "maxY": 320, "maxZ": 250,
  "ownerUuid": "...",
  "ownerName": "...",
  "accessMode": "owner_only",
  "allowedOwnerUuids": [],
  "createdTime": 1712160000000
}
```

**Files modified:**
- `GameAI/services/ProtectedZoneService.java` — ProtectedZone class, ZoneData class, contains(), createZone(), save/load, migration logic, removeZoneByPosition(), distanceFrom(), grant/revoke/setAccessMode constructors
- `Commands/modCommandRegistry.java` — executeZoneList() output format, executeZoneProtect() AABB conversion

## Zone Wand — Item & Selection

### Wand item

- Vanilla blaze rod with MC 1.21 Data Components:
  - `DataComponentTypes.CUSTOM_NAME` → `§b§lZone Wand`
  - `DataComponentTypes.LORE` → usage instructions
  - `DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE` → `true` (enchant shimmer)
  - `DataComponentTypes.CUSTOM_DATA` → `NbtCompound` with `frens_zone_wand: 1b` for programmatic identification
- Wand identification: check `stack.get(DataComponentTypes.CUSTOM_DATA)` for the `frens_zone_wand` tag
- Given via `/bot zone wand` (admin-only) or "New Zone" button in Base Manager (admin-only)
- Server validates operator status on every interaction; non-ops cannot use the wand even if they obtain one

### Wand interaction mechanism

Server-side: Register `UseBlockCallback.EVENT` in `Frens.java`. The callback checks if the player is holding a wand item (via custom data tag) and is an operator. If so, it records the clicked position and returns `ActionResult.SUCCESS` to consume the interaction. The server sends back a `ZoneCornerSetPayload` (S2C) confirming the corner position so the client can update its preview state.

### Selection workflow

**State (client-side, in FrensClient):**
- `pendingZonePos1: BlockPos` (null = not set)
- `pendingZonePos2: BlockPos` (null = not set)
- `pendingZoneDimension: RegistryKey<World>` (set when pos1 is placed)
- `pendingZoneMinY: int` (default: `world.getBottomY()`, typically -64)
- `pendingZoneMaxY: int` (default: `world.getTopYInclusive()`, typically 319)
- `pendingZonePreviewActive: boolean`

**Dimension safety:** When pos1 is set, `pendingZoneDimension` is recorded. If the player changes dimension before setting pos2, the pending selection is automatically cleared with action bar message: `§7Zone selection cancelled (dimension changed)`.

**Disconnect/cleanup:** Client state is cleared on disconnect (all `pending*` fields nulled). Server-side `ZoneVisualizerService` cleans up stale preview/viewing entries on `ServerPlayerEvents.AFTER_DISCONNECT`.

**Flow:**

1. Admin right-clicks ground with wand → **pos1 set**
   - Action bar: `§aCorner 1 set at X, Z — right-click to set Corner 2`
   - State: `pendingZonePos1 = hitPos`

2. Admin right-clicks ground again → **pos2 set, preview starts**
   - Action bar: `§aCorner 2 set at X, Z — Shift+↑/↓ adjust Y | [=] confirm | [ESC] cancel`
   - State: `pendingZonePos2 = hitPos`, `pendingZonePreviewActive = true`
   - Particles begin rendering (see Particle Visualization section)

3. **Y adjustment** — while holding wand and preview is active:
   - Shift+Up arrow: raise maxY by 1 (ceiling up)
   - Shift+Down arrow: lower minY by 1 (floor down)
   - Ctrl+Up arrow: lower maxY by 1 (ceiling down, min: minY + 1)
   - Ctrl+Down arrow: raise minY by 1 (floor up, max: maxY - 1)
   - Action bar updates with current Y range: `§7Y range: §f-64 §7to §f320`

4. **Confirm** — press confirm keybind (`=`):
   - Opens `ZoneNamePopupScreen` with text field + confirm button
   - On submit: sends `ZoneConfirmPayload(pos1, pos2, minY, maxY, name)` to server
   - Server validates admin status, creates zone via `ProtectedZoneService.createZone()`
   - Action bar: `§aZone "name" created!`
   - Client state cleared

5. **Cancel** — press ESC, switch item, or right-click wand a third time:
   - Client state cleared, particles stop
   - Action bar: `§7Zone selection cancelled`

**Files modified:**
- `FrensClient.java` — new state fields, wand interaction handler, keybind processing, Y adjustment logic
- `Commands/modCommandRegistry.java` — `/bot zone wand` command to give item

## Particle Visualization

### Color & style

Cyan/teal `DustParticleEffect` (`0x00CCCC` → RGB 0.0, 0.8, 0.8), particle size 1.0.

### Particle count limits

Particles render along all 12 edges of the AABB at 1-block intervals, with these constraints:
- **Y clamping (required):** Only render vertical edges within ±64 blocks of the viewer's Y position
- **XZ clamping (required):** Only render horizontal edges within ±128 blocks of the viewer's XZ position
- **Max particles per tick: 2,000.** If the AABB is so large that edge rendering exceeds this, increase spacing to 2 or 4 blocks between particles until under the cap

### Two rendering contexts

1. **Preview mode** (during wand selection): **Client-side rendering** via `client.particleManager.addParticle()`, matching the existing schematic preview pattern in FrensClient. No server round-trip needed — the client already has both corner positions. Renders every 3 ticks (same cadence as schematic preview).

2. **View mode** (saved zones): **Server-side rendering** via `ServerWorld.spawnParticles()`. Server tracks which players are viewing which zones and sends particles every 20 ticks (1 second). Any player can toggle viewing via Base Manager UI or hotkey.

### New service

`ZoneVisualizerService` — handles server-side particle spawning for saved zone viewing. Registers a `ServerTickEvents.END_SERVER_TICK` listener. Maintains:
- `Map<String, Set<UUID>>` for saved zone viewing (zone label → set of viewing player UUIDs)
- Cleans up entries on player disconnect via `ServerPlayerEvents.AFTER_DISCONNECT`

**Files added:**
- `GameAI/services/ZoneVisualizerService.java`

## Base Manager UI Integration

### New "Protected Zones" section

Added as a new section in `BaseManagerScreen`, below existing sections (bases, fortifications, villages).

**Section header:** `§b⬡ Protected Zones`

**Zone list item format:** `§b{name} §7({x1},{z1} → {x2},{z2}) §8Y:{minY}→{maxY}`

**Buttons per zone (admin):**
- **Show/Hide** — toggles particle visualization (sends `ZoneToggleViewPayload`)
- **Edit** — opens inline Y-level editor (two number fields: Floor Y, Ceiling Y) + Rename field
- **Delete** — click once shows "§cConfirm?", click again deletes (sends `ZoneDeletePayload`)

**Buttons per zone (non-admin):**
- **Show/Hide** — same toggle, particles only

**Admin-only button at section top:**
- **New Zone** — sends `ZoneWandRequestPayload` to server, receives wand item

**Tooltips:**
- New Zone: "Gives you a Zone Wand to select an area"
- Show: "Display zone boundary particles"
- Edit: "Change zone name or Y bounds"
- Delete: "Remove this protected zone"

### Zone detail edit view (admin)

When admin clicks Edit on a zone:
- Two text fields appear: "Floor Y" (number), "Ceiling Y" (number)
- One text field: "Name"
- "Save" button → sends `ZoneEditPayload(label, newName, newMinY, newMaxY)` to server
- "Cancel" button → returns to zone list

**XZ editing is out of scope.** To change a zone's XZ footprint, the admin deletes the zone and recreates it with the wand. This keeps the edit UI simple and avoids partial-update edge cases.

**Files modified:**

- `GraphicalUserInterface/BaseManagerScreen.java` — new section, zone list rendering, edit view, button handlers

## Zone Name Popup Screen

Small centered popup screen (200×100px) with:
- Title: `§bName this zone`
- Text field (max 32 chars, alphanumeric + spaces + hyphens)
- "Confirm" button → sends payload, closes screen
- "Cancel" button → closes screen, clears wand selection

**Files added:**
- `GraphicalUserInterface/ZoneNamePopupScreen.java`

## Network Payloads

| Payload | Direction | Fields | Purpose |
|---------|-----------|--------|---------|
| `ZoneWandRequestPayload` | C2S | (none) | Request wand item |
| `ZoneCornerSetPayload` | S2C | cornerIndex, pos | Confirm corner position to client |
| `ZoneConfirmPayload` | C2S | pos1, pos2, minY, maxY, name | Create zone from wand selection |
| `ZoneToggleViewPayload` | C2S | label, enabled | Start/stop viewing zone particles |
| `ZoneEditPayload` | C2S | label, newName, newMinY, newMaxY | Edit zone bounds/name |
| `ZoneDeletePayload` | C2S | label | Delete zone |
| `ZoneListPayload` | S2C | zonesJson | Zone list for Base Manager |
| `RequestZoneListPayload` | C2S | (none) | Request zone list from server |

All C2S payloads validate `Frens.isOperator()` on server side (except `ZoneToggleViewPayload` which is available to all players).

**Rename collision safety:** `ZoneEditPayload` handler validates that `newName` does not collide with an existing zone label. If it does, the server sends an error message and rejects the edit. On successful rename, `ZoneVisualizerService` updates its viewing state map from the old label to the new label.

**Files added:**

- `network/ZoneWandRequestPayload.java`
- `network/ZoneCornerSetPayload.java`
- `network/ZoneConfirmPayload.java`
- `network/ZoneToggleViewPayload.java`
- `network/ZoneEditPayload.java`
- `network/ZoneDeletePayload.java`
- `network/ZoneListPayload.java`
- `network/RequestZoneListPayload.java`
- `network/ZoneNetworkManager.java` — server-side receivers for all zone payloads

**Files modified:**

- `Frens.java` — register payload types, register `UseBlockCallback` for wand interaction

## Keybinds

| Key | Default | Purpose |
|-----|---------|---------|
| `KEY_ZONE_CONFIRM` | `=` (Equals) | Confirm zone selection / toggle zone particle view |

Registered in `FrensClient` via `KeyBindingHelper.registerKeyBinding()`.

Context-dependent behavior:
- If wand selection is in preview state → opens naming popup
- If a zone is being viewed → toggles particles off
- Otherwise → no action

## Commands

### New commands

- `/bot zone wand` — gives zone wand item (admin-only)
- `/bot zone set-y <label> <minY> <maxY>` — set Y bounds for existing zone (admin-only)

### Modified commands

- `/bot zone protect <radius> [label]` — unchanged behavior, but internally creates AABB (center ± radius)

## HUD Notifications

Action bar messages during wand workflow:
- After pos1: `§aCorner 1 set at X, Z — right-click to set Corner 2`
- After pos2: `§aZone preview active — Shift+↑/↓ adjust Y | [=] confirm | [ESC] cancel`
- During Y adjust: `§7Y range: §f{minY} §7to §f{maxY}`
- On confirm: `§aZone "{name}" created!`
- On cancel: `§7Zone selection cancelled`

## Bot Guide Integration

New section in `BotGuideScreen` covering:
- What protected zones do (prevent bot block-breaking)
- How to create zones (wand workflow with step-by-step)
- How to view zones (Base Manager + hotkey)
- How to edit/delete zones (admin-only)
- Y-level control explanation

**Files modified:**
- `GraphicalUserInterface/BotGuideScreen.java` — new guide section

## Verification Plan

1. **Build:** `./gradlew build -x test` compiles cleanly
2. **Migration:** Create a zone with old `/bot zone protect 10 test-zone`, restart server, verify it loads as AABB
3. **Wand workflow:** `/bot zone wand` → right-click pos1 → right-click pos2 → see cyan particles → adjust Y → press `=` → name popup → verify zone saved
4. **Protection:** Bot tries to break block inside zone → blocked. Bot breaks block outside zone → allowed
5. **Base Manager:** Open Base Manager → see Protected Zones section → Show/Hide toggle → Edit Y bounds → Delete zone
6. **Non-admin viewing:** Non-op player opens Base Manager → sees zones → can Show/Hide → cannot Edit/Delete/New Zone
7. **Guide:** Open bot guide → verify Protected Zones section exists with correct instructions
8. **Persistence:** Create zone → restart server → zone still exists with correct bounds
