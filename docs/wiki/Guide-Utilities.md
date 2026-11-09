<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Utilities

[All Guide topics](Guide.md)

## Storage

View and manage supply chests your companions have placed in the world.

- Open via Actions → Utilities → Storage.
- Each chest shows coordinates, status, and a snapshot of its contents.
- Go: fast-travel the bot to the chest location.
- Collect: bot fast-travels to chest, withdraws items, optionally returns (to player, home, or stays).
- Dismiss: removes the chest from the registry (does not break the block).
- Fast travel speed depends on artifacts: Map/Compass = 2x delay, Eye of Ender or higher = standard speed.
- Hover a chest row to see a tooltip of its last-known contents.
- Note: Only chests the bot places or uses in automated tasks appear here. Chests targeted via Quick Store/Fetch are not tracked.

**Command:** UI-only action (Actions → Utilities → Storage)

**Where:** Switch between bots with the dropdown in the header

## Protected Zones

Mark areas where bots cannot break blocks.

- Admins can create protected zones using the Zone Wand.
- Get the wand: /bot zone wand or 'New Zone' in the Base Manager.
- Right-click a block to set Corner 1, right-click again for Corner 2.
- Cyan particles appear showing the zone boundary.
- Press [=] to confirm and name the zone.
- Bots will not break any blocks inside protected zones.
- Any player can view zone boundaries via Base Manager &gt; Show.
- Admins can rename or delete zones in the Base Manager.
- Zones persist across server restarts.
- To re-do a selection, right-click again to restart from Corner 1.

**Command:** `/bot zone wand, /bot zone list, /bot zone protect <radius> [label]`

**Where:** Confirm zone: = (Equals key)
