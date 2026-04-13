# Cross-Session Bot Despawn Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/bot despawn` persist across sessions so bots stay gone until manually re-spawned, with a new "Auto Spawn on Load" UI toggle and `/bot despawn session` for the old session-only behavior.

**Architecture:** Add `autoSpawnOnLoad` boolean to `BotControlSettings` (default `true`). `/bot despawn` sets it to `false`; `/bot spawn` clears it back to `true`. `BotControlApplier.scheduleAutoSpawns()` skips bots where the flag is `false`. UI toggle in Spawning tab, guide entry in BotGuideScreen.

**Tech Stack:** Java 21, Fabric API, Minecraft 1.21.11. No automated tests — verify with `./gradlew build`.

**Spec:** `docs/superpowers/specs/2026-04-13-cross-session-despawn-design.md`

---

## Chunk 1: Data Model + Auto-Spawn Gate

### Task 1: Add `autoSpawnOnLoad` field to BotControlSettings

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java:1209-1224` (field declarations)

- [ ] **Step 1: Add the field declaration**

In `ManualConfig.java`, inside `BotControlSettings`, add after the `autoRegroupOnLost` field (~line 1224):

```java
private boolean autoSpawnOnLoad = true;  // shelved when false
```

- [ ] **Step 2: Add getter and setter**

Add after the existing `isAutoRegroupOnLost()` / `setAutoRegroupOnLost()` pair (find them with grep):

```java
/**
 * Whether this bot should automatically spawn when the world loads.
 * When {@code false}, the bot is "shelved" and must be manually spawned
 * with {@code /bot spawn}.  Defaults to {@code true}.
 */
public boolean isAutoSpawnOnLoad() {
    return autoSpawnOnLoad;
}

public void setAutoSpawnOnLoad(boolean autoSpawnOnLoad) {
    this.autoSpawnOnLoad = autoSpawnOnLoad;
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/FilingSystem/ManualConfig.java
git commit -m "feat: add autoSpawnOnLoad field to BotControlSettings"
```

---

### Task 2: Gate `scheduleAutoSpawns()` on `autoSpawnOnLoad`

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotControlApplier.java:130-145`

- [ ] **Step 1: Add the gate check**

In `scheduleAutoSpawns()`, inside the `for (String alias : candidates)` loop, after the line that resolves settings (~line 143):

```java
ManualConfig.BotControlSettings settings = Frens.CONFIG.getOrCreateBotControl(alias, currentWorldKey);
```

Add immediately after:

```java
if (settings != null && !settings.isAutoSpawnOnLoad()) {
    continue;
}
```

This skips shelved bots before the spawn command is dispatched.

- [ ] **Step 2: Verify build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotControlApplier.java
git commit -m "feat: skip shelved bots in scheduleAutoSpawns"
```

---

## Chunk 2: Command Changes

### Task 3: Modify `/bot despawn` to set `autoSpawnOnLoad = false`

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java:5817-5832`

- [ ] **Step 1: Add shelving logic to `executeDespawnTargets`**

Replace the current `executeDespawnTargets` method with a version that accepts a `sessionOnly` flag. Find the method at ~line 5817:

```java
static int executeDespawnTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
```

Replace the entire method body with:

```java
static int executeDespawnTargets(CommandContext<ServerCommandSource> context, String targetArg) throws CommandSyntaxException {
    return executeDespawnTargets(context, targetArg, false);
}

static int executeDespawnTargets(CommandContext<ServerCommandSource> context, String targetArg, boolean sessionOnly) throws CommandSyntaxException {
    List<ServerPlayerEntity> targets = BotTargetingService.resolve(context.getSource(), targetArg);
    boolean isAll = targetArg != null && "all".equalsIgnoreCase(targetArg.trim());
    int successes = 0;
    MinecraftServer server = context.getSource().getServer();
    String worldKey = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(server);
    for (ServerPlayerEntity bot : targets) {
        BotTargetingService.forgetIfMatches(context.getSource(), bot.getName().getString());
        if (!sessionOnly) {
            String alias = bot.getName().getString();
            ManualConfig.BotControlSettings ctrl = Frens.CONFIG.getOrCreateBotControl(alias, worldKey);
            ctrl.setAutoSpawnOnLoad(false);
        }
        BotEventHandler.unregisterBot(bot);
        successes++;
    }
    if (!sessionOnly && !targets.isEmpty()) {
        Frens.CONFIG.save();
    }
    if (!targets.isEmpty()) {
        String summary = formatBotList(targets, isAll);
        String verb = (isAll || targets.size() > 1) ? "have" : "has";
        if (sessionOnly) {
            ChatUtils.sendSystemMessage(context.getSource(),
                    summary + " " + verb + " been removed for this session. They will return on next world load.");
        } else {
            ChatUtils.sendSystemMessage(context.getSource(),
                    summary + " " + verb + " been despawned and shelved. Use /bot spawn to bring them back.");
        }
    }
    return successes;
}
```

Note: `BotWorldStateService` is used fully-qualified (`net.wcfcarolina13.GameAI.services.BotWorldStateService`) matching the codebase convention in `modCommandRegistry.java` — do NOT add an import.

- [ ] **Step 2: Verify build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java
git commit -m "feat: /bot despawn now shelves bots cross-session"
```

---

### Task 4: Add `/bot despawn session` subcommand

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/Commands/BotLifecycleCommands.java:17-23`

- [ ] **Step 1: Add `session` literal to the command tree**

Replace the `buildDespawn()` method with:

```java
static ArgumentBuilder<ServerCommandSource, ?> buildDespawn() {
    return CommandManager.literal("despawn")
            .executes(context -> modCommandRegistry.executeDespawnTargets(context, (String) null))
            .then(CommandManager.literal("session")
                    .executes(context -> modCommandRegistry.executeDespawnTargets(context, null, true))
                    .then(CommandManager.argument("target", StringArgumentType.string())
                            .executes(context -> modCommandRegistry.executeDespawnTargets(context,
                                    StringArgumentType.getString(context, "target"), true))))
            .then(CommandManager.argument("target", StringArgumentType.string())
                    .executes(context -> modCommandRegistry.executeDespawnTargets(context,
                            StringArgumentType.getString(context, "target"))));
}
```

This gives us:
- `/bot despawn` — shelves all (no target = null)
- `/bot despawn <name>` — shelves named bot
- `/bot despawn session` — session-only, all
- `/bot despawn session <name>` — session-only, named bot

- [ ] **Step 2: Verify build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Commands/BotLifecycleCommands.java
git commit -m "feat: add /bot despawn session subcommand for session-only removal"
```

---

### Task 5: Clear `autoSpawnOnLoad` on `/bot spawn`

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java:2291-2361` (the `spawnBot` method, line 2110)

The `spawnBot` method has two independent branches that set `BotControlSettings`. Both must clear the shelved flag.

- [ ] **Step 1: Add unshelve to the training branch (~line 2296)**

Find the training branch config block at ~line 2291:

```java
// Training bots auto-respawn on death by default.
{
    String worldKey = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(server);
    ManualConfig.BotControlSettings ctrl = Frens.CONFIG.getOrCreateBotControl(botName, worldKey);
    if (ctrl != null) {
        ctrl.setSpawnMode("training");
        ctrl.setAutoRespawnOnDeath(true);
    }
}
```

Add `ctrl.setAutoSpawnOnLoad(true);` after `ctrl.setAutoRespawnOnDeath(true);`:

```java
if (ctrl != null) {
    ctrl.setSpawnMode("training");
    ctrl.setAutoRespawnOnDeath(true);
    ctrl.setAutoSpawnOnLoad(true);
}
```

- [ ] **Step 2: Add unshelve to the admin/questing branch (~line 2356)**

Find the admin/questing branch config block at ~line 2352:

```java
// Admin/questing bots: set spawn mode and auto-respawn default.
{
    String worldKey = net.wcfcarolina13.GameAI.services.BotWorldStateService.currentWorldKey(server);
    ManualConfig.BotControlSettings ctrl = Frens.CONFIG.getOrCreateBotControl(botName, worldKey);
    if (ctrl != null) {
        ctrl.setSpawnMode(normalizedSpawnMode);
        if (isAdminLikeSpawnMode(normalizedSpawnMode)) {
            ctrl.setAutoRespawnOnDeath(true);
        }
    }
}
```

Add `ctrl.setAutoSpawnOnLoad(true);` after the `setAutoRespawnOnDeath` block:

```java
if (ctrl != null) {
    ctrl.setSpawnMode(normalizedSpawnMode);
    if (isAdminLikeSpawnMode(normalizedSpawnMode)) {
        ctrl.setAutoRespawnOnDeath(true);
    }
    ctrl.setAutoSpawnOnLoad(true);
}
```

No explicit `Frens.CONFIG.save()` is needed here — the spawn flow already saves config downstream via `BotPersistenceService.onBotJoin()`.

- [ ] **Step 3: Verify build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java
git commit -m "feat: /bot spawn clears shelved state (autoSpawnOnLoad=true)"
```

---

## Chunk 3: UI Toggle + Guide

### Task 6: Add "Auto Spawn on Load" toggle to BotControlScreen

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java`

This task has 4 surgical edits. The widget list is positionally indexed, so order matters.

- [ ] **Step 1: Update `SettingsSnapshot` record (~line 290)**

Replace:
```java
private record SettingsSnapshot(
        boolean autoRespawnOnDeath, String spawnMode, String gameMode,
        String failsafeSpawnMode,
        boolean teleportDuringSkills, boolean followTeleport,
        boolean pauseOnFullInventory,
        boolean teleportDuringDropSweep, boolean autoRegroupOnLost,
        boolean llmEnabled, boolean voicedDialogue) {}
```

With:
```java
private record SettingsSnapshot(
        boolean autoRespawnOnDeath, boolean autoSpawnOnLoad,
        String spawnMode, String gameMode,
        String failsafeSpawnMode,
        boolean teleportDuringSkills, boolean followTeleport,
        boolean pauseOnFullInventory,
        boolean teleportDuringDropSweep, boolean autoRegroupOnLost,
        boolean llmEnabled, boolean voicedDialogue) {}
```

- [ ] **Step 2: Add toggle to Spawning tab in `rebuildSettingsWidgets()` (~line 365-384)**

Find the Spawning group construction. After the "Auto Respawn" line:
```java
spawning.add(makeOnOff("Auto Respawn", "Respawn on death (skip resurrection ritual).", autoRespawn, TOGGLE_W));
```

Add immediately after:
```java
spawning.add(makeOnOff("Auto Spawn on Load",
        "Automatically spawn this bot when the world loads. Turn off to keep the bot shelved until manually spawned.",
        autoSpawnOnLoad, TOGGLE_W));
```

Also need to load the value from config. At ~line 352, find:

```java
boolean autoRespawn = snap != null ? snap.autoRespawnOnDeath : cfg.isAutoRespawnOnDeath();
```

Add immediately after (before the `spawnMode` line at ~353):

```java
boolean autoSpawnOnLoad = snap != null ? snap.autoSpawnOnLoad : cfg.isAutoSpawnOnLoad();
```

Here `cfg` is the same `BotControlSettings` variable used for `autoRespawn`.

- [ ] **Step 3: Update `captureCurrentWidgets()` (~line 304-334)**

The new toggle is inserted as widget index 1 (after Auto Respawn at index 0, before Spawn Mode). This shifts ALL subsequent indices by 1.

Replace:
```java
if (ws.size() < 11) return;

boolean autoRespawn = Boolean.TRUE.equals(ws.get(0).getValue());
Object spawnModeValue = ws.get(1).getValue();
Object gameModeValue = ws.get(2).getValue();
Object failsafeValue = ws.get(3).getValue();
boolean teleportSkills = Boolean.TRUE.equals(ws.get(4).getValue());
boolean followTeleport = Boolean.TRUE.equals(ws.get(5).getValue());
boolean pauseInventory = Boolean.TRUE.equals(ws.get(6).getValue());
boolean teleportSweep = Boolean.TRUE.equals(ws.get(7).getValue());
boolean autoRegroup = Boolean.TRUE.equals(ws.get(8).getValue());
boolean llmEnabled = Boolean.TRUE.equals(ws.get(9).getValue());
boolean voicedDialogue = Boolean.TRUE.equals(ws.get(10).getValue());

dirtySettings.put(selectedAlias, new SettingsSnapshot(
        autoRespawn,
        spawnModeValue instanceof String s ? s : "training",
        gameModeValue instanceof String s ? s : "survival",
        failsafeValue instanceof String s ? s : "world_spawn",
        teleportSkills,
        followTeleport,
        pauseInventory,
        teleportSweep,
        autoRegroup,
        llmEnabled,
        voicedDialogue
));
```

With:
```java
if (ws.size() < 12) return;

boolean autoRespawn = Boolean.TRUE.equals(ws.get(0).getValue());
boolean autoSpawnOnLoad = Boolean.TRUE.equals(ws.get(1).getValue());
Object spawnModeValue = ws.get(2).getValue();
Object gameModeValue = ws.get(3).getValue();
Object failsafeValue = ws.get(4).getValue();
boolean teleportSkills = Boolean.TRUE.equals(ws.get(5).getValue());
boolean followTeleport = Boolean.TRUE.equals(ws.get(6).getValue());
boolean pauseInventory = Boolean.TRUE.equals(ws.get(7).getValue());
boolean teleportSweep = Boolean.TRUE.equals(ws.get(8).getValue());
boolean autoRegroup = Boolean.TRUE.equals(ws.get(9).getValue());
boolean llmEnabled = Boolean.TRUE.equals(ws.get(10).getValue());
boolean voicedDialogue = Boolean.TRUE.equals(ws.get(11).getValue());

dirtySettings.put(selectedAlias, new SettingsSnapshot(
        autoRespawn,
        autoSpawnOnLoad,
        spawnModeValue instanceof String s ? s : "training",
        gameModeValue instanceof String s ? s : "survival",
        failsafeValue instanceof String s ? s : "world_spawn",
        teleportSkills,
        followTeleport,
        pauseInventory,
        teleportSweep,
        autoRegroup,
        llmEnabled,
        voicedDialogue
));
```

- [ ] **Step 4: Update save path (~line 459-481)**

Find the save loop. After:
```java
s.setAutoRespawnOnDeath(v.autoRespawnOnDeath);
```

Add:
```java
s.setAutoSpawnOnLoad(v.autoSpawnOnLoad);
```

- [ ] **Step 5: Verify build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java
git commit -m "feat: add 'Auto Spawn on Load' toggle to Spawning tab in BotControlScreen"
```

---

### Task 7: Add guide entry to BotGuideScreen

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java`

- [ ] **Step 1: Find the baseTopics() method and locate the `"basics_bot_controls"` entry**

Search for `"basics_bot_controls"` in `BotGuideScreen.java`. The `baseTopics()` method returns `List.of(...)` (immutable), so the new entry must be inserted inline as a new element — you cannot `.add()` to it.

- [ ] **Step 2: Add the guide entry inline after the "Bot Controls Panel" topic**

Insert a new `GuideTopic` element immediately after the `"basics_bot_controls"` entry (after its closing `)`), separated by a comma. The entry goes inside the existing `List.of(...)` call:

```java
new GuideTopic(
        "spawning_auto_spawn_on_load",
        "Spawning",
        "Auto Spawn on Load",
        "Controls whether a bot automatically returns when you re-enter the world.",
        List.of(
                "When ON (default), the bot spawns automatically on world load with full state restored.",
                "When OFF, the bot stays shelved until you manually run /bot spawn.",
                "Use /bot despawn to shelve a bot across sessions (turns this OFF).",
                "Use /bot despawn session to remove a bot for the current session only (does not change this setting).",
                "Toggle this in the Bot Controls panel under the Spawning tab."
        ),
        "bot despawn " + target,
        "",
        "shelve shelved auto spawn load world session despawn persist"
),
```

Make sure the `target` variable is available — it's defined at the top of `baseTopics()` as `String target = botTarget();`.

- [ ] **Step 3: Verify build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java
git commit -m "feat: add 'Auto Spawn on Load' guide entry to BotGuideScreen"
```

---

## Chunk 4: Changelog + Final Verification

### Task 8: Update changelog and final build

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Add changelog entry**

Add at the top of `changelog.md`:

```markdown
## Cross-Session Bot Despawn (2026-04-13)

- `/bot despawn <name>` now shelves bots across sessions — they stay gone until `/bot spawn`
- New `/bot despawn session <name>` subcommand for the old session-only behavior
- New "Auto Spawn on Load" toggle in Bot Controls > Spawning tab
- New guide entry explaining shelving and despawn modes
- `autoSpawnOnLoad` field added to BotControlSettings (defaults to true, no migration needed)
```

- [ ] **Step 2: Final build verification**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add changelog.md
git commit -m "docs: changelog entry for cross-session despawn feature"
```

- [ ] **Step 4: Report artifact path**

Report the JAR path (`build/libs/frens-*.jar`) to the user. Do NOT copy to PrismLauncher instances — user is currently playing Minecraft.
