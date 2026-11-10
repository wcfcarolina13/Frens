<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Utilities

[All Guide topics](Guide.md)

## Storage

View and manage supply chests your companions have placed in the world.

- Open via Actions → Utilities → Storage.
- Each chest shows coordinates, status, and a snapshot of its contents.
- Go: fast-travel the bot to the chest location.
- Collect: bot fast-travels to chest, takes up to about 256 items, then stays, returns to you, or returns to a base named home (or where it started if there is none).
- Dismiss: removes the chest from the registry (does not break the block).
- Go and Collect use fast travel, so the bot needs a gate (see Fast-Travel Gates). A lodestone compass or an Eye of Ender gives full speed. A Map and Compass that show the chest is slower.
- Hover a chest row to see a tooltip of its last-known contents.
- Note: Only chests this bot placed appear here (during a job or for /bot store deposit), in its current dimension. Chests it places while fishing, building a shelter or smelting are not listed. Chests it only used, and Quick Store/Fetch chests, are not tracked.

**Command:** UI-only action (Actions → Utilities → Storage)

**Where:** Switch between bots with the dropdown in the header

## Protected Zones

Mark areas where bots cannot break blocks.

- Admins can create protected zones using the Zone Wand.
- Get the wand: /bot zone wand or 'New Zone' in the Base Manager.
- Right-click a block to set Corner 1, right-click again for Corner 2.
- Cyan particles appear showing the zone boundary.
- Press [=] to confirm and name the zone.
- Stripmine, Collect Dirt, Ascent and Descent stop at every zone, your own included. In your own zones, or zones you are permitted in, press Resume to let the job continue for 60 seconds. Ascent and Descent still stay out of other players' bases. Other players' bots won't break blocks in your zones.
- Any player can view zone boundaries via Base Manager &gt; Show.
- Admins can rename or delete zones in the Base Manager.
- Zones persist across server restarts.
- To re-do a selection, right-click again to restart from Corner 1.

**Command:** `/bot zone wand, /bot zone list, /bot zone protect <radius> [label]`

**Where:** Confirm zone: = (Equals key)
