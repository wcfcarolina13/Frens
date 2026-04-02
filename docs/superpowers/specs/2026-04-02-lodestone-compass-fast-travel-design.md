# Lodestone Compass Fast-Travel

Bots can use lodestone compasses for fast-travel, the same way a player would orient by one. As long as a compass has a valid lodestone binding, the bot can fast-travel to that location — including cross-dimension (e.g. Overworld to Nether).

## Decisions

- **Navigation tier:** Lodestone compass promotes to ENHANCED (1x delay multiplier), same as Eye of Ender. Justified by the netherite ingot cost and the fact that the compass provides an exact destination.
- **Validation:** The mod validates the lodestone block still exists before committing to travel. If the lodestone is gone, the compass is treated as a regular BASIC-tier compass and the owner is notified.
- **Cross-dimension:** Full support. The server checks the target chunk in the other dimension to validate. Cross-dimension adds the standard +30s delay.
- **Multiple compasses:** Bots can hold several lodestone compasses. Player selects by anvil-given name via command; autonomous systems use the designated "home" compass with a same-dimension-nearest fallback.
- **Home designation:** Player marks one compass as "home" via `/bot compass home <bot> <name>`. Autonomous travel (sunset return, rescue) uses this compass first.
- **Approach:** New `LodestoneCompassService` encapsulates all lodestone logic. `NavigationArtifactService` calls into it for tier classification and destination resolution.

## API Note

This spec assumes the MC 1.21.11 data component API for lodestone compasses:
- `DataComponentTypes.LODESTONE_TRACKER` → `LodestoneTrackerComponent`
- `LodestoneTrackerComponent.target()` → `Optional<GlobalPos>`
- `GlobalPos` contains `RegistryKey<World>` (dimension) and `BlockPos`

These types have never been used in this codebase. **Verify the exact API shape** against the yarn mappings for 1.21.11+build.3 during implementation. If the accessor names or shapes differ, adjust `findLodestoneCompasses()` accordingly.

## Architecture

### New: `LodestoneCompassService`

`GameAI/services/LodestoneCompassService.java` (~200 lines)

Single-concern service owning all lodestone compass logic: inventory scanning, component reading, lodestone validation, home designation, and multi-compass selection.

#### Record

```java
public record LodestoneCompassEntry(int slot, String displayName, GlobalPos target)
```

- `slot`: inventory slot index
- `displayName`: custom anvil name, or `"Lodestone Compass"` if unnamed
- `target`: `GlobalPos` containing dimension `RegistryKey<World>` and `BlockPos`

#### Public methods

```java
/** Scan bot inventory for compasses with valid LodestoneTracker components. */
public static List<LodestoneCompassEntry> findLodestoneCompasses(ServerPlayerEntity bot)
```

Iterates all inventory slots. For each `Items.COMPASS` stack, reads `DataComponentTypes.LODESTONE_TRACKER`. If the `LodestoneTrackerComponent` has a present `target()` (`Optional<GlobalPos>`), creates a `LodestoneCompassEntry`. Display name comes from `DataComponentTypes.CUSTOM_NAME` if present, else defaults.

Note: In MC 1.21, `Items.COMPASS` is the same item for both regular and lodestone compasses — the lodestone binding is a data component, not a separate item ID. The existing `hasItemInInventory(bot, Items.COMPASS)` check in `NavigationArtifactService` already matches lodestone compasses and classifies them as BASIC. The new lodestone check intercepts before that, promoting validated lodestone compasses to ENHANCED.

```java
/** Validate that a lodestone block exists at the compass's target position. */
public static boolean validateLodestone(MinecraftServer server, GlobalPos target)
```

Gets `ServerWorld` for the target dimension via `server.getWorld()`. If the world is null (unloaded/invalid dimension), returns false. Uses `world.getChunkManager().getWorldChunk(chunkX, chunkZ)` to check if the chunk is loaded — returns null for unloaded chunks without forcing generation. If the chunk is loaded, checks `world.getBlockState(pos).isOf(Blocks.LODESTONE)`. If the chunk is not loaded, returns false (treat as "cannot verify" rather than force-loading expensive chunks). The caller gets a clear "invalid/unverifiable" signal and can fall back gracefully.

```java
/** Check if bot holds at least one compass with a lodestone binding (component check only, no block validation). */
public static boolean hasLodestoneCompass(ServerPlayerEntity bot)
```

Convenience method for tier/multiplier checks where full block validation is unnecessary. Returns true if `findLodestoneCompasses()` is non-empty.

```java
/** Select a compass for autonomous travel. Home designation first, then same-dimension nearest. */
public static Optional<LodestoneCompassEntry> selectAutonomousCompass(
        ServerPlayerEntity bot, MinecraftServer server)
```

1. Get all lodestone compasses via `findLodestoneCompasses(bot)`
2. If a home compass is designated (`getHomeCompassName(bot)`), find it by name match
3. If found and `validateLodestone()` passes, return it
4. Fallback: filter to same-dimension compasses, sort by distance to bot, validate nearest first
5. If no same-dimension compass validates, try cross-dimension compasses in inventory order (first-validates-wins — distance comparison across dimensions is meaningless due to coordinate scaling, e.g. Nether 8x compression)
6. Return empty if nothing validates

```java
/** Select a compass by display name (case-insensitive). */
public static Optional<LodestoneCompassEntry> selectCompassByName(
        ServerPlayerEntity bot, String name)
```

Finds first compass whose `displayName` matches (case-insensitive). No validation — caller validates after selection.

```java
/** Get the designated home compass name for a bot, or null. */
public static String getHomeCompassName(ServerPlayerEntity bot)

/** Set the designated home compass name for a bot. */
public static void setHomeCompassName(ServerPlayerEntity bot, String compassName)
```

Delegates to `BotHomeService` for persistence. Takes `ServerPlayerEntity` (not raw UUID) to match `BotHomeService` conventions — the service uses `botKey(bot)` (lowercase bot name) internally.

### Modified: `NavigationArtifactService`

**`getBotNavigationTier()`** — Before the regular compass check (line 100), insert a lodestone check. The current signature is `getBotNavigationTier(ServerPlayerEntity bot, ServerPlayerEntity player)`. Extract `MinecraftServer` from `bot.getServer()` (available on `ServerPlayerEntity`):

```java
MinecraftServer server = bot.getServer();
if (server != null && LodestoneCompassService.findLodestoneCompasses(bot).stream()
        .anyMatch(e -> LodestoneCompassService.validateLodestone(server, e.target()))) {
    return NavTier.ENHANCED;
}
```

No signature change needed — `getServer()` is available on the entity.

**`artifactDelayMultiplier()`** — Add lodestone compass to the tier-2 check block (alongside Eye of Ender, Wizard's Tome, etc.). Both `hasValidLodestoneCompass()` and the tier check should validate consistently:

```java
if (LodestoneCompassService.hasLodestoneCompass(bot)) {
    return 1.0;
}
```

Note: `hasLodestoneCompass()` checks only that a lodestone-bound compass exists (component check), not that the lodestone block is still valid. This is intentional for the multiplier — it's a speed tier classification, and the travel-commit path validates separately. This avoids a redundant chunk check on every multiplier query.

**Underground gate** — Lodestone compass gets the same bypass as tier-2 artifacts. No explicit change needed: the `artifactDelayMultiplier()` returning `1.0` already triggers the `mult <= 1.0` branch at line 542.

### Modified: `BotHomeService`

Add `homeCompassNameByBot` (`Map<String, String>`, keyed by bot name) to `RootData` (not `WorldData`). Home compass designation is dimension-independent — a bot should be able to use its designated home compass regardless of which dimension it's currently in, since the compass itself stores the target dimension.

```java
// In RootData:
Map<String, String> homeCompassNameByBot = new HashMap<>();
```

Two accessor methods following the `botKey(bot)` pattern used by all other BotHomeService accessors:

```java
public static String getHomeCompassName(ServerPlayerEntity bot)
public static void setHomeCompassName(ServerPlayerEntity bot, String name)
```

These read/write from `DATA.homeCompassNameByBot` using `botKey(bot)` as the key. No `WorldData` involvement.

Note on stale designations: If a player sets a home compass name and the bot later loses that compass (death, etc.), the name persists as a stale string. The autonomous selection silently falls through to the distance-based fallback. This is acceptable — no cleanup needed.

### Modified: `BotAutoReturnSunsetService`

Add `LODESTONE_COMPASS` to the `AnchorKind` enum.

The sunset service uses a **tiered resolution chain**, not a candidates list:
- `resolvePrimaryHomeAnchor()` → HOME or SPAWN
- `resolveLocalSurvivalOverride()` → village house, tactical shelter, or null
- `resolveSelfSufficientFallback()` → BASE → BED → COMMANDER → ALLY_BOT → VILLAGE_HOUSE → CHEST → TACTICAL_SHELTER

Insert the lodestone compass check in `resolveSelfSufficientFallback()`, after the BED check but before the COMMANDER check:

```java
// After BED check (line ~816), before commander check (line ~821):
Optional<LodestoneCompassEntry> lodestoneCompass =
        LodestoneCompassService.selectAutonomousCompass(bot, server);
if (lodestoneCompass.isPresent()) {
    GlobalPos target = lodestoneCompass.get().target();
    return new SunsetAnchor(target.pos().toImmutable(), AnchorKind.LODESTONE_COMPASS,
            "lodestone compass: " + lodestoneCompass.get().displayName());
}
```

This priority means: HOME/SPAWN (primary) > BASE > BED > **LODESTONE_COMPASS** > COMMANDER > ALLY_BOT > VILLAGE_HOUSE > CHEST > TACTICAL_SHELTER. The lodestone compass outranks social/proximity fallbacks because it provides an exact, player-configured destination.

Note: The lodestone compass anchor can point to a different dimension. When `resolveSelfSufficientFallback` returns a cross-dimension anchor, the sunset return session will need to detect this and route through `beginDelayedTravel` with cross-dimension support rather than walking. The existing fast-travel pipeline handles this — the session just needs to recognize that the anchor is unreachable by walking and trigger fast-travel instead.

### Modified: `modCommandRegistry`

Three new subcommands under `/bot`. Consolidated under `/bot compass` namespace for clarity:

#### `/bot compass travel <botname> [name]`

1. Resolve bot entity by name
2. If `[name]` provided: `LodestoneCompassService.selectCompassByName(bot, name)`; else: find designated home compass or first available
3. If no compass found: `"§c<bot> has no lodestone compass."` → return
4. `LodestoneCompassService.validateLodestone(server, compass.target())`
5. If invalid: `"§c<bot>'s compass no longer points to a valid lodestone."` → return
6. Extract `BlockPos` and `RegistryKey<World>` from `compass.target()`
7. Calculate delay: `NavigationArtifactService.calculateDelayTicks(distance, crossDim, 1.0)` (ENHANCED multiplier)
8. Call `NavigationArtifactService.beginDelayedTravel(server, bot, alias, dest, dim, delay, ownerUuid)`
9. Notify owner: `"§d<bot> is traveling to <dest> via lodestone compass (ETA ~Xs).§r"`

#### `/bot compass home <botname> <name>`

1. Resolve bot entity
2. `LodestoneCompassService.selectCompassByName(bot, name)` — verify the compass exists
3. If not found: `"§c<bot> has no compass named '<name>'."` → return
4. `LodestoneCompassService.setHomeCompassName(bot, name)`
5. `"§a<bot>'s home compass set to '<name>' (pointing to <coords>, <dimension>).§r"`

#### `/bot compass list <botname>`

1. Resolve bot entity
2. `LodestoneCompassService.findLodestoneCompasses(bot)`
3. If empty: `"§7<bot> has no lodestone compasses."` → return
4. For each entry, display: `"  <name> → <x, y, z> (<dimension>) [HOME]"` (HOME marker if it's the designated one)

## Existing Behavior: No Changes Needed

- **ChestStoreService:** `Items.COMPASS` is already in the chest-store exclusion list (line 168 of ChestStoreService.java), so lodestone compasses are protected from being offloaded to chests. No change needed.
- **BotEmergencyRescueService:** Already calls `NavigationArtifactService.beginEmergencyTravel()` and `artifactDelayMultiplier()`. Lodestone compasses automatically benefit from the `NavigationArtifactService` tier/multiplier changes. No direct changes needed — inherited.
- **PendingTravel record, tickPendingTravels(), respawnBotAtDestination():** Unchanged. Lodestone compass just provides a new way to determine destination + tier.
- **Cooldown, food, and mount logic:** Unchanged — all existing gates apply.

## Validation Flow

```
Command or autonomous trigger
  │
  ▼
findLodestoneCompasses(bot)  ──── scan inventory for Items.COMPASS + LodestoneTracker component
  │
  ▼
Select compass (by name, home designation, or fallback heuristic)
  │
  ▼
validateLodestone(server, globalPos)
  │    uses getWorldChunk() — no forced chunk generation
  │
  ├── invalid/unverifiable → notify owner, treat as BASIC tier / refuse travel
  │
  └── valid → extract destination + dimension from GlobalPos
                │
                ▼
          beginDelayedTravel(server, bot, alias, dest, dim, delay, owner)
                │
                ▼
          Standard travel pipeline (remove bot, delay, respawn at dest)
```

## Files Changed

| File | Change | ~Lines |
|---|---|---|
| **NEW:** `GameAI/services/LodestoneCompassService.java` | Core lodestone compass service | ~200 |
| `GameAI/services/NavigationArtifactService.java` | Tier check + delay multiplier lodestone additions | ~20 |
| `GameAI/services/BotHomeService.java` | `homeCompassNameByBot` in RootData + accessors | ~15 |
| `GameAI/services/BotAutoReturnSunsetService.java` | `LODESTONE_COMPASS` anchor kind + fallback insertion | ~30 |
| `Commands/modCommandRegistry.java` | Register 3 subcommands under `/bot compass` | ~80 |

## Not in Scope

- Bots placing or crafting lodestone blocks
- Bots crafting compasses or binding them to lodestones
- Lodestone "networks" (multiple lodestones as a fast-travel network)
- Compass pointing animation on the bot entity (visual-only, no gameplay impact)
- Recovery compass integration (tracks last death location — different mechanic)
