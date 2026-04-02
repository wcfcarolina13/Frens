# Sunrise Resume Loop & Supporting Features

Enables bots to work autonomously in a daily cycle: skill until sunset → return home → sleep → wake at sunrise → fast-travel back → resume skill. Plus supporting features: "Until sunset" GUI toggle for woodcut, lodestones in the Bases menu, and smoke signal navigation beacons.

## Sub-Features

1. **Woodcut "Until Sunset" GUI toggle** — actions menu parity with fishing
2. **Generic sunrise skill resume** — any skill interrupted by sunset resumes at sunrise
3. **Lodestones in the Bases menu** — compass destinations appear alongside saved bases
4. **Smoke signal navigation beacon** — campfire + hay bale extends artifact-free travel range

These are independent and can be implemented/tested in any order, though the sunrise resume benefits from the others being in place.

---

## 1. Woodcut "Until Sunset" GUI Toggle

### Problem

Woodcut's count field starts at 4 (min 1) in the Actions menu. Unlike fishing (which starts at 0 and shows "Until sunset"), the player has no GUI way to run woodcut in open-ended sunset mode.

### Changes

**`BotPlayerInventoryScreen.java`:**

- `WOODCUT_TREE_COUNT_MIN`: 1 → 0
- `woodcutTreeCount` initial value: `WOODCUT_TREE_COUNT_DEFAULT` → `SKILL_COUNT_UNSET` (0)
- `getAdjustableSkillValueLabel()`: add woodcut case: `count > 0 ? "Trees " + count : "Until sunset"`
- Replace all `adjustWoodcutTreeCount()` calls with `adjustOptionalCount()` so `-` can reach 0. This includes the overlay click handler path (lines ~2528-2532) and any other direct callers.
- `runWoodcutSkillCommand()`: when count is 0, pass null arg
- Tooltip (line 4022): mention "Until sunset" as the default mode
- `adjustWoodcutTreeCount()`: remove entirely (replaced by `adjustOptionalCount`)

**`SkillManager.java` (line 330-333):**

The "no count = open-ended" logic is currently fishing-specific:
```java
if ("fish".equalsIgnoreCase(skillName) || "fishing".equalsIgnoreCase(skillName)) {
    return !params.containsKey("count");
}
```

Extend to include woodcut:
```java
if ("fish".equalsIgnoreCase(skillName) || "fishing".equalsIgnoreCase(skillName)
        || "woodcut".equalsIgnoreCase(skillName)) {
    return !params.containsKey("count");
}
```

This is required because `WoodcutSkill.isOpenEnded()` does NOT independently check for the absence of a count parameter. Without this change, passing null from the GUI would cause `getIntParameter(params, "count", defaultTrees)` to return 4 (the standalone default), NOT open-ended mode.

**`BotGuideScreen.java`:**

- Update woodcut guide entry to mention "Until sunset" as the default and that +/- adjusts tree target

### How it works

Count at 0 → `runSkillCommand("woodcut", null)` → command parser omits `"count"` from params → `SkillManager.isOpenEnded()` returns true (no count key for woodcut = open-ended) → `WoodcutSkill` sets `targetTrees = Integer.MAX_VALUE` → skill runs until `SUNSET_TIME_OF_DAY` (12000 ticks) then breaks gracefully with "It's getting late" message.

---

## 2. Generic Sunrise Skill Resume

### Problem

When sunset interrupts an open-ended skill (woodcut, fishing, etc.), the skill is simply aborted. The bot returns home, sleeps, and wakes idle. Only Hunt has special sunrise resume logic via `HuntSessionService`.

### Design

#### Sunset interruption — evaluating options

When `BotAutoReturnSunsetService` interrupts an open-ended skill, it evaluates the bot's situation in priority order:

1. **Has lodestone compass pointing home (same dimension)?** → Fast-travel home, sleep. Save `SunriseResumeRecord` with the bot's current position.

2. **No compass, but bot is inside a saved base's protection radius?** → Fast-travel to home/bed anchor, bypassing the artifact requirement (skip underground gate, skip artifact check). Apply 3x "no artifact" delay. Save `SunriseResumeRecord`.

3. **No compass, not at a base, tactical shelter toggle ON?** → Use tactical shelter. Save `SunriseResumeRecord` with `shelteredInPlace = true`.

4. **No compass, not at a base, tactical shelter toggle OFF?** → Abort skill, go idle. No resume queued. Bot sits until player intervenes.

Note: Case 1 is already handled by the existing sunset return system + the lodestone compass anchor we just built. Cases 2-4 extend the existing behavior.

#### SunriseResumeRecord

New `private static final Map<UUID, SunriseResumeRecord> SUNRISE_RESUME_BY_BOT` in `SkillResumeService`, separate from the existing `LAST_SKILL_BY_BOT` map (which serves death-resume and interactive yes/no decisions).

```java
record SunriseResumeRecord(
    UUID botUuid,
    String skillName,
    String rawArgs,
    BlockPos interruptionPos,
    boolean shelteredInPlace,
    long savedTick  // for 24-hour expiry
)
```

New methods on `SkillResumeService`:
- `saveSunriseResume(UUID, String skillName, String rawArgs, BlockPos interruptionPos, boolean shelteredInPlace, long serverTick)`
- `getSunriseResume(UUID)` → nullable, checks 24-hour expiry
- `clearSunriseResume(UUID)`

24-hour expiry (if server runs past one night cycle without the bot resuming, the record expires). Hunt opts out — `HuntSkill` already saves its own session with hunt-specific state (kill count, targets).

**Death interaction:** If a bot dies while a `SunriseResumeRecord` exists, `clearSunriseResume()` is called from `SkillResumeService.handleDeath()`. The death-resume system handles the "should I continue?" flow separately; a dead bot should not also attempt sunrise resume.

#### Sunrise resume — return trip

`BotAutoReturnSunsetService` sunrise check (existing `tod < SUNRISE_END_TICK` window). Extended to check `SkillResumeService.getSunriseResume(botUuid)` for any pending record. This check runs only if `HuntSessionService.hasSession()` is false (hunt has its own path).

1. **Bot sheltered in place?** → Re-run the skill command immediately. Bot is already at the worksite.

2. **Bot at home, has lodestone compass near saved interruption pos?** → Scan all lodestone compasses via `LodestoneCompassService.findLodestoneCompasses(bot)`. Find the one whose lodestone is closest to `interruptionPos` (same dimension only). If the lodestone is within 128 blocks of the saved position (constant: `SUNRISE_RETURN_COMPASS_RANGE = 128`, approximately one skill search radius), fast-travel via that compass. On arrival, re-run skill via `PostArrivalAction` with type `"skill_resume"`.

3. **Bot at home, no compass near worksite?** → Re-run skill at current location. The skill finds nearby targets (not ideal, but functional).

#### PostArrivalAction integration

`NavigationArtifactService` already has `PostArrivalAction` with a `type` field and processes them in `tickPendingTravels()`. Add a new type `"skill_resume"` that, on arrival, dispatches the saved skill command via `SkillResumeService`. Store the skill command string in `PostArrivalAction` — repurpose one of the existing fields or add the command to the record.

#### Interaction with Hunt

Hunt is excluded: the sunrise check in `BotAutoReturnSunsetService` already handles `HuntSessionService.hasSession()` separately. The generic resume check runs only if no hunt session exists.

#### Base radius fast-travel bypass

When the bot is within a saved base's protection radius, `beginDelayedTravel` is called with a flag to skip only the underground/artifact gate while preserving combat/cooldown/food gates. Add a `skipArtifactGate` boolean to the private `beginDelayedTravel` overload (line 474). When `skipArtifactGate = true`:
- Skip the underground gate block (lines 542-563)
- Force delay multiplier to 3.0 (no-artifact penalty)
- All other gates (combat, cooldown, food, mount) still apply

The existing `skipGates` boolean (used by emergency travel) overrides `skipArtifactGate` — if `skipGates=true`, everything is skipped regardless.

**New method needed:** `BotHomeService.findBaseNearPosition(MinecraftServer server, ServerWorld world, BlockPos pos)` — iterates `listBases(server, world)` and returns the first `BaseEntry` whose position is within its protection radius of `pos`. Returns `Optional<BaseEntry>`. The existing `findNearestBase(ServerPlayerEntity bot)` takes a bot entity, not a position, so a new overload is needed.

---

## 3. Lodestones in the Bases Menu

### Problem

Lodestone compass destinations are invisible in the Bases menu. Players have to use `/bot compass list` to see them.

### Design

#### Server-side (`BaseNetworkManager`)

After collecting regular bases, walls, and villages (line ~534 of BaseNetworkManager.java), scan the bot's inventory via `LodestoneCompassService.findLodestoneCompasses(bot)`. For each compass entry with a valid target:

- Create a `BaseDto` with `kind = "lodestone"`
- `label` = compass display name (anvil name, or "Lodestone Compass" default)
- `x/y/z` = from `GlobalPos.pos()`
- `detailText` = dimension name if cross-dimension (e.g. "the_nether"), empty if same dimension
- `home` = true if this compass is the designated home compass
- `radius` = 0 (lodestones don't have a protection radius)
- `ownerName` = null

The `kind = "lodestone"` value is serialized to JSON and sent to the client. The client-side `BaseManagerScreen.BaseDto` deserializes it via the same JSON structure — no special handling needed since both records share the same field layout.

#### Client-side (`BaseManagerScreen`)

- Add `isLodestone()` convenience method to `BaseManagerScreen.BaseDto`: `return "lodestone".equals(kind);`
- Lodestone entries appear in the existing base list, sorted alphabetically alongside regular bases
- Visual indicator: tagged with "Lodestone" label or distinct color to differentiate from saved bases
- Interactions:
  - **Go To** — dispatches a chat command `/bot compass travel <botname> <compassname>` which routes through the existing lodestone travel flow
  - **Set Home** — dispatches `/bot compass home <botname> <compassname>` to designate as home compass
  - **Rename / Delete / Set Here / Set Radius** — disabled/hidden for lodestone entries (compass is source of truth; rename the compass on an anvil, remove the compass to remove the entry)

#### Multiple compasses, same lodestone

If a bot holds two compasses pointing to the same lodestone, both appear as separate entries. This is correct — they may have different names. The player can see the duplication and resolve it.

---

## 4. Smoke Signal Navigation Beacon

### Problem

A bot at a base without artifacts (no compass, no map) cannot fast-travel underground at all. Even with the "base radius bypass" from Section 2, it can only fast-travel from within the base radius (default 24 blocks). A thematic, buildable signal should extend this range.

### Mechanic

A **smoke signal** is a lit campfire (`Blocks.CAMPFIRE` or `Blocks.SOUL_CAMPFIRE`, not extinguished) with a hay bale (`Blocks.HAY_BLOCK`) directly below it. In vanilla Minecraft, this combination produces a tall smoke column visible from far away.

### Detection

`hasSmokeSignal(ServerWorld world, BlockPos basePos)`: scan within ±8 horizontal, ±8 vertical of the base position (wide enough to catch campfires on rooftops or across courtyards). For each block in the scan area, check:
- `blockState.isOf(Blocks.CAMPFIRE) || blockState.isOf(Blocks.SOUL_CAMPFIRE)`
- `blockState.get(CampfireBlock.LIT)` is true
- Block at `campfirePos.down()` is `Blocks.HAY_BLOCK`

Return true if any valid smoke signal found. Cache result per base position for 1200 ticks (60 seconds) to avoid rescanning every call. Clear cache in `SERVER_STOPPING` handler (consistent with the 8 other classes that clean up there).

### Effect

When evaluating fast-travel eligibility in `NavigationArtifactService.beginDelayedTravel()`:

- **Current behavior:** bot underground + no Map+Compass = travel refused
- **With smoke signal at destination base:**
  - **Above ground:** bot can fast-travel without artifacts from up to **5× base radius** distance (default: 5 × 24 = 120 blocks)
  - **Below ground:** bot can fast-travel without artifacts from up to **2× base radius** distance (default: 2 × 24 = 48 blocks)
  - Delay: 3x (no-artifact penalty still applies — the smoke signal enables travel, doesn't speed it up)

### Where it lives

New utility method in `NavigationArtifactService` or a small helper class. Called from the underground gate section of `beginDelayedTravel()`. Before refusing travel for lack of artifacts, check:

1. Is the destination near a saved base? (`BotHomeService.findBaseNearPosition(server, world, destination)`)
2. Does that base have a smoke signal? (`hasSmokeSignal(world, basePos)`)
3. Is the bot within range? (5× radius above ground, 2× radius below ground)
4. If all yes: allow travel with 3x delay

### Integration with sunrise resume

When a bot at a smoke-signal base needs to return to its worksite after sleeping, the smoke signal extends the departure range. The bot can leave from up to 2× radius underground (useful if the base is a cave base).

---

## Files Changed Summary

| Sub-Feature | Files | Estimated Lines |
|---|---|---|
| **1. Until Sunset** | `BotPlayerInventoryScreen.java`, `BotGuideScreen.java`, `SkillManager.java` | ~35 |
| **2. Sunrise Resume** | `SkillResumeService.java`, `BotAutoReturnSunsetService.java`, `NavigationArtifactService.java`, `BotHomeService.java` | ~180 |
| **3. Lodestones in Bases** | `BaseNetworkManager.java`, `BaseManagerScreen.java` | ~60 |
| **4. Smoke Signal** | `NavigationArtifactService.java` (or new helper) | ~70 |

## Not in Scope

- File-persisted sunrise resume records (in-memory with 24-hour expiry is sufficient)
- Hunt-specific changes (Hunt keeps its own session system)
- Cross-dimension sunrise return trips (same-dimension only for compass selection)
- Automatic lodestone compass crafting or lodestone placement by bots
- Smoke signal placement by bots
