# Lodestone Compass Fast-Travel Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable bots to fast-travel to lodestone compass destinations, including cross-dimension, with player commands and autonomous sunset/rescue integration.

**Architecture:** New `LodestoneCompassService` encapsulates all lodestone compass logic (inventory scanning, block validation, home designation). `NavigationArtifactService` calls into it for tier classification. Three `/bot compass` commands provide player control. Sunset return integrates via a new `LODESTONE_COMPASS` anchor kind.

**Tech Stack:** Minecraft 1.21.11 Fabric, Java 21, yarn mappings 1.21.11+build.3-v2

**Spec:** `docs/superpowers/specs/2026-04-02-lodestone-compass-fast-travel-design.md`

---

## Verified MC 1.21.11 API (yarn mappings)

These have been confirmed against the yarn mappings JAR:

- `DataComponentTypes.LODESTONE_TRACKER` → `ComponentType<LodestoneTrackerComponent>`
- `LodestoneTrackerComponent` is a record: `target()` → `Optional<GlobalPos>`, `tracked()` → `boolean`
- `GlobalPos` is a record: `dimension()` → `RegistryKey<World>`, `pos()` → `BlockPos`
- `DataComponentTypes.CUSTOM_NAME` → `ComponentType<Text>` (anvil-given item name)
- `Items.COMPASS` is the same item for regular AND lodestone compasses — the binding is a data component

Imports needed:
```java
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.util.math.GlobalPos;
```

---

## Chunk 1: Core Service + NavigationArtifactService Integration

### Task 1: Verify LodestoneTrackerComponent API compiles

Before writing the full service, confirm the MC API works as expected in this codebase.

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java`

- [ ] **Step 1: Create minimal LodestoneCompassService with API smoke test**

Create the service with just `findLodestoneCompasses()` to confirm the component API compiles:

```java
package net.wcfcarolina13.GameAI.services;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.GlobalPos;

import java.util.ArrayList;
import java.util.List;

public final class LodestoneCompassService {

    private LodestoneCompassService() {}

    public record LodestoneCompassEntry(int slot, String displayName, GlobalPos target) {}

    public static List<LodestoneCompassEntry> findLodestoneCompasses(ServerPlayerEntity bot) {
        List<LodestoneCompassEntry> results = new ArrayList<>();
        if (bot == null) return results;
        var inv = bot.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty() || !stack.isOf(Items.COMPASS)) continue;

            LodestoneTrackerComponent tracker = stack.get(DataComponentTypes.LODESTONE_TRACKER);
            if (tracker == null) continue;

            // target() returns Optional<GlobalPos>
            tracker.target().ifPresent(globalPos -> {
                Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
                String displayName = customName != null ? customName.getString() : "Lodestone Compass";
                results.add(new LodestoneCompassEntry(i, displayName, globalPos));
            });
        }
        return results;
    }
}
```

- [ ] **Step 2: Build to verify the component API compiles**

Run: `./gradlew build -x test`

Expected: BUILD SUCCESSFUL. If `LodestoneTrackerComponent` or `GlobalPos` has different accessors, check the decompiled source and adjust. The yarn mappings confirm `target()` returns `Optional<GlobalPos>` and `GlobalPos` has `dimension()` and `pos()`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java
git commit -m "feat: Add LodestoneCompassService with inventory scanning"
```

---

### Task 2: Add validation and convenience methods

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java`

- [ ] **Step 1: Add validateLodestone() and hasLodestoneCompass()**

Add these methods to `LodestoneCompassService`:

```java
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

```java
private static final Logger LOGGER = LoggerFactory.getLogger("lodestone-compass");

/**
 * Validate that a lodestone block exists at the compass's target position.
 * Uses getWorldChunk() to avoid forced chunk generation. Returns false if
 * the chunk is unloaded or the block is not a lodestone.
 */
public static boolean validateLodestone(MinecraftServer server, GlobalPos target) {
    if (server == null || target == null) return false;

    RegistryKey<World> dimKey = target.dimension();
    ServerWorld world = server.getWorld(dimKey);
    if (world == null) return false;

    BlockPos pos = target.pos();
    WorldChunk chunk = world.getChunkManager().getWorldChunk(
            pos.getX() >> 4, pos.getZ() >> 4);
    if (chunk == null) return false;

    return world.getBlockState(pos).isOf(Blocks.LODESTONE);
}

/** Check if bot holds at least one compass with a lodestone binding (component check only). */
public static boolean hasLodestoneCompass(ServerPlayerEntity bot) {
    return !findLodestoneCompasses(bot).isEmpty();
}
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java
git commit -m "feat: Add lodestone validation and hasLodestoneCompass convenience"
```

---

### Task 3: Add compass selection methods

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java`

- [ ] **Step 1: Add selectCompassByName()**

```java
import java.util.Locale;
import java.util.Optional;
```

```java
/** Select a compass by display name (case-insensitive). No block validation — caller validates. */
public static Optional<LodestoneCompassEntry> selectCompassByName(
        ServerPlayerEntity bot, String name) {
    if (bot == null || name == null || name.isBlank()) return Optional.empty();
    String lower = name.toLowerCase(Locale.ROOT);
    return findLodestoneCompasses(bot).stream()
            .filter(e -> e.displayName().toLowerCase(Locale.ROOT).equals(lower))
            .findFirst();
}
```

- [ ] **Step 2: Add selectAutonomousCompass()**

```java
import java.util.Comparator;
```

```java
/**
 * Select a compass for autonomous travel.
 * Priority: designated home compass > same-dimension nearest > cross-dimension first-valid.
 */
public static Optional<LodestoneCompassEntry> selectAutonomousCompass(
        ServerPlayerEntity bot, MinecraftServer server) {
    if (bot == null || server == null) return Optional.empty();

    List<LodestoneCompassEntry> compasses = findLodestoneCompasses(bot);
    if (compasses.isEmpty()) return Optional.empty();

    // 1. Try designated home compass
    String homeName = getHomeCompassName(bot);
    if (homeName != null && !homeName.isBlank()) {
        String lower = homeName.toLowerCase(Locale.ROOT);
        Optional<LodestoneCompassEntry> home = compasses.stream()
                .filter(e -> e.displayName().toLowerCase(Locale.ROOT).equals(lower))
                .findFirst();
        if (home.isPresent() && validateLodestone(server, home.get().target())) {
            return home;
        }
    }

    // 2. Same-dimension compasses sorted by distance
    RegistryKey<World> botDim = bot.getEntityWorld().getRegistryKey();
    BlockPos botPos = bot.getBlockPos();

    Optional<LodestoneCompassEntry> sameDim = compasses.stream()
            .filter(e -> e.target().dimension().equals(botDim))
            .sorted(Comparator.comparingDouble(e ->
                    botPos.getSquaredDistance(e.target().pos())))
            .filter(e -> validateLodestone(server, e.target()))
            .findFirst();
    if (sameDim.isPresent()) return sameDim;

    // 3. Cross-dimension: inventory order, first-validates-wins
    return compasses.stream()
            .filter(e -> !e.target().dimension().equals(botDim))
            .filter(e -> validateLodestone(server, e.target()))
            .findFirst();
}
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java
git commit -m "feat: Add compass selection methods (by name, autonomous)"
```

---

### Task 4: Add home compass persistence via BotHomeService

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java:989-991` (RootData)
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java`

- [ ] **Step 1: Add homeCompassNameByBot to BotHomeService.RootData**

In `BotHomeService.java`, find the `RootData` class at line 989:

```java
private static final class RootData {
    Map<String, WorldData> worlds = new HashMap<>();
}
```

Add the new field:

```java
private static final class RootData {
    Map<String, WorldData> worlds = new HashMap<>();
    Map<String, String> homeCompassNameByBot = new HashMap<>();
}
```

- [ ] **Step 2: Add BotHomeService accessor methods**

Add these two public methods to `BotHomeService` (after the existing accessor methods, around line ~780):

```java
/** Get the designated home compass name for a bot, or null if not set. */
public static String getHomeCompassName(ServerPlayerEntity bot) {
    if (bot == null) return null;
    ensureLoaded();
    String botId = botKey(bot);
    if (botId.isBlank()) return null;
    synchronized (LOCK) {
        if (DATA.homeCompassNameByBot == null) {
            DATA.homeCompassNameByBot = new HashMap<>();
        }
        return DATA.homeCompassNameByBot.get(botId);
    }
}

/** Set the designated home compass name for a bot. Pass null to clear. */
public static void setHomeCompassName(ServerPlayerEntity bot, String name) {
    if (bot == null) return;
    String botId = botKey(bot);
    if (botId.isBlank()) return;
    ensureLoaded();
    synchronized (LOCK) {
        if (DATA.homeCompassNameByBot == null) {
            DATA.homeCompassNameByBot = new HashMap<>();
        }
        if (name == null || name.isBlank()) {
            DATA.homeCompassNameByBot.remove(botId);
        } else {
            DATA.homeCompassNameByBot.put(botId, name);
        }
    }
    flush();
}
```

- [ ] **Step 3: Wire LodestoneCompassService home methods to BotHomeService**

Add these delegate methods to `LodestoneCompassService`:

```java
/** Get the designated home compass name for a bot, or null. Delegates to BotHomeService. */
public static String getHomeCompassName(ServerPlayerEntity bot) {
    return BotHomeService.getHomeCompassName(bot);
}

/** Set the designated home compass name for a bot. Delegates to BotHomeService. */
public static void setHomeCompassName(ServerPlayerEntity bot, String compassName) {
    BotHomeService.setHomeCompassName(bot, compassName);
}
```

- [ ] **Step 4: Build to verify**

Run: `./gradlew build -x test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java \
       src/main/java/net/wcfcarolina13/GameAI/services/LodestoneCompassService.java
git commit -m "feat: Add home compass persistence via BotHomeService"
```

---

### Task 5: Integrate with NavigationArtifactService tier + multiplier

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java:96-107` (getBotNavigationTier)
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java:170-189` (artifactDelayMultiplier)

- [ ] **Step 1: Add lodestone check to getBotNavigationTier()**

In `NavigationArtifactService.java` at line 96, the method currently reads:

```java
public static NavTier getBotNavigationTier(ServerPlayerEntity bot, ServerPlayerEntity player) {
    if (hasItemInInventory(bot, Items.ENDER_EYE) || hasItemInInventory(player, Items.ENDER_EYE)) {
        return NavTier.ENHANCED;
    }
    if (hasItemInInventory(bot, Items.COMPASS)
```

Insert the lodestone check after the Eye of Ender check but before the regular compass check. Replace:

```java
    if (hasItemInInventory(bot, Items.ENDER_EYE) || hasItemInInventory(player, Items.ENDER_EYE)) {
        return NavTier.ENHANCED;
    }
    if (hasItemInInventory(bot, Items.COMPASS)
```

With:

```java
    if (hasItemInInventory(bot, Items.ENDER_EYE) || hasItemInInventory(player, Items.ENDER_EYE)) {
        return NavTier.ENHANCED;
    }
    // Lodestone compass with binding → ENHANCED tier (component check only, no chunk validation —
    // the travel-commit path validates the block separately; tier classification should be cheap)
    if (LodestoneCompassService.hasLodestoneCompass(bot)) {
        return NavTier.ENHANCED;
    }
    if (hasItemInInventory(bot, Items.COMPASS)
```

- [ ] **Step 2: Add lodestone check to artifactDelayMultiplier()**

In `NavigationArtifactService.java` at line 170, the method currently has the tier-2 block starting at line 172. Add the lodestone check at the top of the tier-2 block. Replace:

```java
    public static double artifactDelayMultiplier(ServerPlayerEntity bot, ServerPlayerEntity owner) {
        // Tier 2+: Eye of Ender, Wizard's Tome, Enchanting Table, or both hold Ender Pearls.
        if (hasArtifact(bot, net.minecraft.item.Items.ENDER_EYE)
```

With:

```java
    public static double artifactDelayMultiplier(ServerPlayerEntity bot, ServerPlayerEntity owner) {
        // Lodestone compass (component check only — full block validation happens at travel-commit)
        if (LodestoneCompassService.hasLodestoneCompass(bot)) {
            return 1.0;
        }
        // Tier 2+: Eye of Ender, Wizard's Tome, Enchanting Table, or both hold Ender Pearls.
        if (hasArtifact(bot, net.minecraft.item.Items.ENDER_EYE)
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java
git commit -m "feat: Lodestone compass promotes to ENHANCED tier with 1x delay"
```

---

## Chunk 2: Commands + Sunset Integration

### Task 6: Register /bot compass commands

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java`

The file is 8044 lines. Commands are registered via Brigadier's `literal()` / `argument()` chaining.

**Pattern reference** (from existing code):
```java
// Bot name argument: StringArgumentType.getString(context, "bot_name")
// Subcommand nesting: .then(literal("compass").then(literal("travel").then(...)))
```

- [ ] **Step 1: Find the right insertion point**

Search for the last `.then(literal(` registration block in the main command tree. New `/bot compass` subcommands should be added alongside existing subcommands like `/bot config`, `/bot spawn`, etc.

Use `grep -n 'then(literal' modCommandRegistry.java | tail -20` to find the insertion point.

- [ ] **Step 2: Add the /bot compass command tree**

Add the following command registrations. Insert as a new `.then(literal("compass")` block at the end of the main `/bot` command tree (before the closing `));` of the root registration):

```java
.then(literal("compass")
    // /bot compass list <bot_name>
    .then(literal("list")
        .then(CommandManager.argument("bot_name", StringArgumentType.string())
            .executes(context -> {
                String botName = StringArgumentType.getString(context, "bot_name");
                MinecraftServer server = context.getSource().getServer();
                ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
                if (bot == null) {
                    context.getSource().sendFeedback(() -> Text.literal(
                            "\u00A7cBot '" + botName + "' not found.\u00A7r"), false);
                    return 0;
                }
                var compasses = LodestoneCompassService.findLodestoneCompasses(bot);
                if (compasses.isEmpty()) {
                    context.getSource().sendFeedback(() -> Text.literal(
                            "\u00A77" + botName + " has no lodestone compasses.\u00A7r"), false);
                    return 0;
                }
                String homeName = LodestoneCompassService.getHomeCompassName(bot);
                context.getSource().sendFeedback(() -> Text.literal(
                        "\u00A7e" + botName + "'s lodestone compasses:\u00A7r"), false);
                for (var c : compasses) {
                    String dimName = c.target().dimension().getValue().toString();
                    boolean isHome = homeName != null
                            && c.displayName().equalsIgnoreCase(homeName);
                    String homeTag = isHome ? " \u00A7a[HOME]\u00A7r" : "";
                    context.getSource().sendFeedback(() -> Text.literal(
                            "  \u00A7f" + c.displayName() + "\u00A77 \u2192 "
                            + c.target().pos().getX() + ", "
                            + c.target().pos().getY() + ", "
                            + c.target().pos().getZ()
                            + " (" + dimName + ")" + homeTag), false);
                }
                return 1;
            })))
    // /bot compass home <bot_name> <name>
    .then(literal("home")
        .then(CommandManager.argument("bot_name", StringArgumentType.string())
            .then(CommandManager.argument("name", StringArgumentType.greedyString())
                .executes(context -> {
                    String botName = StringArgumentType.getString(context, "bot_name");
                    String compassName = StringArgumentType.getString(context, "name");
                    MinecraftServer server = context.getSource().getServer();
                    ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
                    if (bot == null) {
                        context.getSource().sendFeedback(() -> Text.literal(
                                "\u00A7cBot '" + botName + "' not found.\u00A7r"), false);
                        return 0;
                    }
                    var match = LodestoneCompassService.selectCompassByName(bot, compassName);
                    if (match.isEmpty()) {
                        context.getSource().sendFeedback(() -> Text.literal(
                                "\u00A7c" + botName + " has no compass named '"
                                + compassName + "'.\u00A7r"), false);
                        return 0;
                    }
                    LodestoneCompassService.setHomeCompassName(bot, compassName);
                    var target = match.get().target();
                    String dimName = target.dimension().getValue().toString();
                    context.getSource().sendFeedback(() -> Text.literal(
                            "\u00A7a" + botName + "'s home compass set to '"
                            + compassName + "' (pointing to "
                            + target.pos().getX() + ", "
                            + target.pos().getY() + ", "
                            + target.pos().getZ()
                            + ", " + dimName + ").\u00A7r"), false);
                    return 1;
                }))))
    // /bot compass travel <bot_name> [name]
    .then(literal("travel")
        .then(CommandManager.argument("bot_name", StringArgumentType.string())
            .executes(context -> {
                // No name provided — use home or first available
                String botName = StringArgumentType.getString(context, "bot_name");
                return executeLodestoneTravel(context, botName, null);
            })
            .then(CommandManager.argument("name", StringArgumentType.greedyString())
                .executes(context -> {
                    String botName = StringArgumentType.getString(context, "bot_name");
                    String compassName = StringArgumentType.getString(context, "name");
                    return executeLodestoneTravel(context, botName, compassName);
                })))))
```

- [ ] **Step 3: Add the executeLodestoneTravel helper method**

Add this private static method to `modCommandRegistry.java`:

```java
private static int executeLodestoneTravel(
        com.mojang.brigadier.context.CommandContext<net.minecraft.server.command.ServerCommandSource> context,
        String botName, String compassName) {
    MinecraftServer server = context.getSource().getServer();
    ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botName);
    if (bot == null) {
        context.getSource().sendFeedback(() -> Text.literal(
                "\u00A7cBot '" + botName + "' not found.\u00A7r"), false);
        return 0;
    }

    // Select compass
    Optional<LodestoneCompassService.LodestoneCompassEntry> selected;
    if (compassName != null) {
        selected = LodestoneCompassService.selectCompassByName(bot, compassName);
        if (selected.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal(
                    "\u00A7c" + botName + " has no compass named '"
                    + compassName + "'.\u00A7r"), false);
            return 0;
        }
    } else {
        // Try home compass, then first available
        selected = LodestoneCompassService.selectAutonomousCompass(bot, server);
        if (selected.isEmpty()) {
            // Try any compass without validation for a better error message
            var all = LodestoneCompassService.findLodestoneCompasses(bot);
            if (all.isEmpty()) {
                context.getSource().sendFeedback(() -> Text.literal(
                        "\u00A7c" + botName + " has no lodestone compass.\u00A7r"), false);
            } else {
                context.getSource().sendFeedback(() -> Text.literal(
                        "\u00A7c" + botName + "'s compass no longer points to a valid lodestone.\u00A7r"), false);
            }
            return 0;
        }
    }

    var compass = selected.get();

    // Validate lodestone block
    if (!LodestoneCompassService.validateLodestone(server, compass.target())) {
        context.getSource().sendFeedback(() -> Text.literal(
                "\u00A7c" + botName + "'s compass '" + compass.displayName()
                + "' no longer points to a valid lodestone.\u00A7r"), false);
        return 0;
    }

    // Extract destination
    BlockPos dest = compass.target().pos();
    RegistryKey<World> dim = compass.target().dimension();
    boolean crossDim = !bot.getEntityWorld().getRegistryKey().equals(dim);
    double distance = bot.getBlockPos().getManhattanDistance(dest);
    int delayTicks = NavigationArtifactService.calculateDelayTicks(distance, crossDim, 1.0);

    // Resolve owner
    UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
    if (ownerUuid == null) {
        ownerUuid = context.getSource().getPlayer() != null
                ? context.getSource().getPlayer().getUuid() : null;
    }

    boolean started = NavigationArtifactService.beginDelayedTravel(
            server, bot, botName, dest, dim, delayTicks, ownerUuid);

    if (started) {
        String dimName = dim.getValue().toString();
        int etaSec = Math.max(1, delayTicks / 20);
        context.getSource().sendFeedback(() -> Text.literal(
                "\u00A7d" + botName + " is traveling to "
                + dest.getX() + ", " + dest.getY() + ", " + dest.getZ()
                + " (" + dimName + ") via lodestone compass '"
                + compass.displayName() + "' (ETA ~" + etaSec + "s).\u00A7r"), false);
        return 1;
    } else {
        context.getSource().sendFeedback(() -> Text.literal(
                "\u00A7c" + botName + " cannot travel right now (cooldown, combat, or insufficient food).\u00A7r"), false);
        return 0;
    }
}
```

- [ ] **Step 4: Add required imports to modCommandRegistry.java**

Add at the top of the file (check which are already imported):

```java
import net.wcfcarolina13.GameAI.services.LodestoneCompassService;
import net.wcfcarolina13.GameAI.services.NavigationArtifactService;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
```

Many of these may already be imported. Only add missing ones.

- [ ] **Step 5: Build to verify**

Run: `./gradlew build -x test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java
git commit -m "feat: Add /bot compass travel|home|list commands"
```

---

### Task 7: Add LODESTONE_COMPASS to sunset anchor resolution

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java:69-79` (AnchorKind enum)
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java:814-819` (resolveSelfSufficientFallback)

- [ ] **Step 1: Add LODESTONE_COMPASS to AnchorKind enum**

In `BotAutoReturnSunsetService.java` at line 69, the enum currently reads:

```java
    private enum AnchorKind {
        HOME,
        SPAWN,
        BASE,
        BED,
        COMMANDER,
        ALLY_BOT,
        VILLAGE_HOUSE,
        CHEST,
        TACTICAL_SHELTER
    }
```

Add `LODESTONE_COMPASS` after `BED`:

```java
    private enum AnchorKind {
        HOME,
        SPAWN,
        BASE,
        BED,
        LODESTONE_COMPASS,
        COMMANDER,
        ALLY_BOT,
        VILLAGE_HOUSE,
        CHEST,
        TACTICAL_SHELTER
    }
```

- [ ] **Step 2: Insert lodestone compass check in resolveSelfSufficientFallback()**

In `BotAutoReturnSunsetService.java`, find the BED check at line 814-816:

```java
        Optional<BlockPos> bed = BotHomeService.getLastSleep(bot);
        if (bed.isPresent() && !bed.get().equals(primaryHome)) {
            return new SunsetAnchor(bed.get().toImmutable(), AnchorKind.BED, "bed");
        }

        MinecraftServer server = world.getServer();
        if (server != null) {
            ServerPlayerEntity commander = CompanionCommunicationPolicy.resolveController(server, bot);
```

Insert the lodestone compass check between the BED return and the server null-check. Replace:

```java
        if (bed.isPresent() && !bed.get().equals(primaryHome)) {
            return new SunsetAnchor(bed.get().toImmutable(), AnchorKind.BED, "bed");
        }

        MinecraftServer server = world.getServer();
        if (server != null) {
```

With:

```java
        if (bed.isPresent() && !bed.get().equals(primaryHome)) {
            return new SunsetAnchor(bed.get().toImmutable(), AnchorKind.BED, "bed");
        }

        MinecraftServer server = world.getServer();

        // Lodestone compass: same-dimension only (SunsetAnchor has no dimension field,
        // and the sunset session walks to the anchor — cross-dimension would navigate
        // to wrong coordinates). Ranks above social fallbacks.
        if (server != null) {
            RegistryKey<World> botDim = world.getRegistryKey();
            var lodestoneCompasses = LodestoneCompassService.findLodestoneCompasses(bot);
            for (var lc : lodestoneCompasses) {
                if (lc.target().dimension().equals(botDim)
                        && LodestoneCompassService.validateLodestone(server, lc.target())) {
                    // Check designated home first
                    String homeName = LodestoneCompassService.getHomeCompassName(bot);
                    if (homeName != null && lc.displayName().equalsIgnoreCase(homeName)) {
                        return new SunsetAnchor(lc.target().pos().toImmutable(),
                                AnchorKind.LODESTONE_COMPASS,
                                "lodestone compass: " + lc.displayName());
                    }
                }
            }
            // If no home compass matched, use nearest valid same-dimension compass
            for (var lc : lodestoneCompasses) {
                if (lc.target().dimension().equals(botDim)
                        && LodestoneCompassService.validateLodestone(server, lc.target())) {
                    return new SunsetAnchor(lc.target().pos().toImmutable(),
                            AnchorKind.LODESTONE_COMPASS,
                            "lodestone compass: " + lc.displayName());
                }
            }
        }

        if (server != null) {
```

- [ ] **Step 3: Add required imports to BotAutoReturnSunsetService.java**

Add at the top of the file (only `GlobalPos` and `RegistryKey` are needed — `LodestoneCompassService` is in the same package):

```java
import net.minecraft.util.math.GlobalPos;
import net.minecraft.registry.RegistryKey;
```

Check if `RegistryKey` is already imported — it likely is since the service already uses `World.OVERWORLD`.

- [ ] **Step 4: Build to verify**

Run: `./gradlew build -x test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java
git commit -m "feat: Add LODESTONE_COMPASS anchor for sunset auto-return"
```

---

### Task 8: Final build + changelog

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Full build verification**

Run: `./gradlew build -x test`

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run type check**

Run: `./gradlew compileJava`

Expected: BUILD SUCCESSFUL with no warnings related to lodestone changes.

- [ ] **Step 3: Update changelog**

Add an entry to the top of `changelog.md`:

```markdown
## 2026-04-02 — Lodestone compass fast-travel

- **New:** Bots can fast-travel to lodestone compass destinations, including cross-dimension
- **New:** `/bot compass list <bot>` — list all lodestone compasses a bot holds
- **New:** `/bot compass home <bot> <name>` — designate a named compass as the bot's home compass
- **New:** `/bot compass travel <bot> [name]` — fast-travel to a lodestone compass destination
- Lodestone compass promotes to ENHANCED nav tier (1x delay multiplier, same as Eye of Ender)
- Autonomous sunset return uses designated home compass as fallback anchor (after HOME/BASE/BED)
- Lodestone block is validated before travel; broken lodestones notify the owner
```

- [ ] **Step 4: Commit**

```bash
git add changelog.md
git commit -m "docs: Add lodestone compass fast-travel changelog entry"
```
