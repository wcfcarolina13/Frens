# Navigation & Spell Mechanics Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add navigation artifacts (compass/map/eye of ender tiers), paired spells (Remote Guidance, Chorus Recall), navigation modes (walk/teleport-delay), confirmation UX, and guide entries to the Frens companion mod.

**Architecture:** Extend existing spell system with a new `NavigationArtifactService` for tier logic and travel scheduling, new network payloads for nav-tier sync and auto-return notifications, and new UI screens for confirmation/direction selection. Token system reworked to remove passive ender pearl access in favor of paired consumption spells.

**Tech Stack:** Java 21, Minecraft Fabric 1.21.11, Fabric API networking, client-side Screen/HUD rendering

**Spec:** `docs/superpowers/specs/2026-03-13-navigation-spell-mechanics-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|---|---|
| `GameAI/services/NavigationArtifactService.java` | Navigation tier enum + checks, PendingTravel record + tick-based arrival scheduling, delayed teleport departure/cleanup, item consumption helpers |
| `network/BotNavTierPayload.java` | S2C payload: syncs bot navigation tier (NONE/BASIC/ENHANCED) to client |
| `network/NavigationRequestPayload.java` | S2C payload: server asks client to show auto-return notification |
| `network/NavigationResponsePayload.java` | C2S payload: client accepts/dismisses auto-return |
| `network/SpellGuidancePayload.java` | C2S payload: client sends Remote Guidance or Chorus Recall spell cast request with destination/direction |
| `network/SpellNavigationNetworkManager.java` | Registers payloads + server-side receivers for navigation responses and spell casts |
| `GraphicalUserInterface/NavigationConfirmScreen.java` | Confirmation screen with destination/direction selection for Remote Guidance and Chorus Recall |
| `GraphicalUserInterface/NavigationHudOverlay.java` | Non-obstructive HUD overlay for auto-return sunset notifications |

### Modified Files
| File | Changes |
|---|---|
| `GameAI/BotEventHandler.java:220` | Add `TRAVELING` to Mode enum; extend `botHasNavigationTool()` to check Eye of Ender |
| `GameAI/services/BotHomeService.java` | Add `navModeByBot` map to WorldData, getter/setter for nav mode |
| `Commands/BotCompanionCommands.java` | Add `guidance` and `recall` literal subcommands |
| `Commands/modCommandRegistry.java` | Add `executeCompanionGuidance/Recall` handlers; remove ender pearl from `canUseCompanionCome/Summon/Home`; add bot inventory validation for paired spells |
| `GraphicalUserInterface/CompanionSpellsScreen.java` | Add Remote Guidance + Chorus Recall buttons; extend AccessState with pairedPearl, pairedChorus, botNavTier; update Home button gating |
| `GraphicalUserInterface/BotGuideScreen.java` | Add 5 new GuideTopic entries |
| `FrensClient.java` | Register HUD overlay, client network receivers, botNavTier cache field |
| `Frens.java` | Register new payloads in PayloadTypeRegistry, call SpellNavigationNetworkManager.registerReceiversOnce() |
| `GameAI/services/BotAutoReturnSunsetService.java` | Integrate navigation artifact tier check; send NavigationRequestPayload instead of direct setReturnToBase when bot has nav artifacts |

All paths relative to `src/main/java/net/wcfcarolina13/`.

---

## Chunk 1: Core Service + Network Layer

### Task 1: NavigationArtifactService — Tier System

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java`

- [ ] **Step 1: Create NavigationArtifactService with tier enum and checks**

```java
package net.wcfcarolina13.GameAI.services;

import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

public final class NavigationArtifactService {

    private NavigationArtifactService() {}

    public enum NavTier { NONE, BASIC, ENHANCED }

    /**
     * Determine navigation tier from bot + player inventories.
     * ENHANCED: either holds Eye of Ender.
     * BASIC: bot holds Compass, Recovery Compass, Map, or Filled Map.
     */
    public static NavTier getBotNavigationTier(ServerPlayerEntity bot, ServerPlayerEntity player) {
        if (hasItemInInventory(bot, Items.ENDER_EYE) || hasItemInInventory(player, Items.ENDER_EYE)) {
            return NavTier.ENHANCED;
        }
        if (hasItemInInventory(bot, Items.COMPASS)
                || hasItemInInventory(bot, Items.RECOVERY_COMPASS)
                || hasItemInInventory(bot, Items.FILLED_MAP)
                || hasItemInInventory(bot, Items.MAP)) {
            return NavTier.BASIC;
        }
        return NavTier.NONE;
    }

    /** Check if both player and bot each hold at least one ender pearl. */
    public static boolean bothHaveEnderPearl(ServerPlayerEntity player, ServerPlayerEntity bot) {
        return hasItemInInventory(player, Items.ENDER_PEARL)
                && hasItemInInventory(bot, Items.ENDER_PEARL);
    }

    /** Check if both hold an ender pearl AND a chorus fruit. */
    public static boolean bothHaveChorusRecallItems(ServerPlayerEntity player, ServerPlayerEntity bot) {
        return hasItemInInventory(player, Items.ENDER_PEARL)
                && hasItemInInventory(player, Items.CHORUS_FRUIT)
                && hasItemInInventory(bot, Items.ENDER_PEARL)
                && hasItemInInventory(bot, Items.CHORUS_FRUIT);
    }

    /** Consume one item of a given type from the player's inventory. Returns true if consumed. */
    public static boolean consumeItem(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(item)) {
                stack.decrement(1);
                if (stack.isEmpty()) inv.setStack(i, net.minecraft.item.ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    private static boolean hasItemInInventory(ServerPlayerEntity player, net.minecraft.item.Item item) {
        if (player == null) return false;
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            var stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty() && stack.isOf(item)) return true;
        }
        return false;
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java
git commit -m "feat: add NavigationArtifactService with tier system and item checks"
```

---

### Task 2: Add TRAVELING Mode + Extend botHasNavigationTool

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java:220` (Mode enum)
- Modify: `src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java:1864` (botHasNavigationTool)

- [ ] **Step 1: Add TRAVELING to Mode enum**

At line 220, change:
```java
public enum Mode {
    IDLE,
    FOLLOW,
    GUARD,
    PATROL,
    STAY,
    RETURNING_BASE
}
```
To:
```java
public enum Mode {
    IDLE,
    FOLLOW,
    GUARD,
    PATROL,
    STAY,
    RETURNING_BASE,
    TRAVELING
}
```

- [ ] **Step 2: Extend botHasNavigationTool to check Eye of Ender**

At line 1864, change:
```java
private static boolean botHasNavigationTool(ServerPlayerEntity bot) {
    if (bot == null) return false;
    for (int slot = 0; slot < bot.getInventory().size(); slot++) {
        net.minecraft.item.ItemStack stack = bot.getInventory().getStack(slot);
        if (stack.isOf(net.minecraft.item.Items.COMPASS)
                || stack.isOf(net.minecraft.item.Items.RECOVERY_COMPASS)
                || stack.isOf(net.minecraft.item.Items.FILLED_MAP)
                || stack.isOf(net.minecraft.item.Items.MAP)) {
            return true;
        }
    }
    return false;
}
```
To:
```java
private static boolean botHasNavigationTool(ServerPlayerEntity bot) {
    if (bot == null) return false;
    for (int slot = 0; slot < bot.getInventory().size(); slot++) {
        net.minecraft.item.ItemStack stack = bot.getInventory().getStack(slot);
        if (stack.isOf(net.minecraft.item.Items.COMPASS)
                || stack.isOf(net.minecraft.item.Items.RECOVERY_COMPASS)
                || stack.isOf(net.minecraft.item.Items.FILLED_MAP)
                || stack.isOf(net.minecraft.item.Items.MAP)
                || stack.isOf(net.minecraft.item.Items.ENDER_EYE)) {
            return true;
        }
    }
    return false;
}
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/BotEventHandler.java
git commit -m "feat: add TRAVELING mode and Eye of Ender to botHasNavigationTool"
```

---

### Task 3: NavMode Persistence in BotHomeService

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java`

- [ ] **Step 1: Add navModeByBot map to WorldData inner class**

Find the `WorldData` inner class (around line 820) and add:
```java
Map<String, String> navModeByBot = new HashMap<>();
```

- [ ] **Step 2: Add getter/setter methods**

Add these public static methods to BotHomeService. Follow the existing pattern used by `isAutoReturnAtSunset()` / `setAutoReturnAtSunset()` which use `worldData(server, world)`:

```java
/** Get navigation mode for a bot. Returns "TELEPORT_DELAY" (default) or "WALK". */
public static String getNavMode(ServerPlayerEntity bot) {
    if (bot == null || !(bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld world)) {
        return "TELEPORT_DELAY";
    }
    MinecraftServer server = world.getServer();
    if (server == null) return "TELEPORT_DELAY";
    String key = botKey(bot);
    synchronized (LOCK) {
        ensureLoaded();
        WorldData wd = worldData(server, world);
        if (wd == null) return "TELEPORT_DELAY";
        return wd.navModeByBot.getOrDefault(key, "TELEPORT_DELAY");
    }
}

/** Set navigation mode for a bot ("WALK" or "TELEPORT_DELAY"). */
public static void setNavMode(ServerPlayerEntity bot, String mode) {
    if (bot == null || !(bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld world)) {
        return;
    }
    MinecraftServer server = world.getServer();
    if (server == null) return;
    String key = botKey(bot);
    String normalized = "WALK".equalsIgnoreCase(mode) ? "WALK" : "TELEPORT_DELAY";
    synchronized (LOCK) {
        ensureLoaded();
        WorldData wd = worldData(server, world);
        if (wd == null) return;
        wd.navModeByBot.put(key, normalized);
        flush();
    }
}
```

Note: `botKey(bot)`, `worldData(server, world)`, `ensureLoaded()`, `flush()` are existing private helpers in BotHomeService. Verify exact signatures via grep before editing.

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotHomeService.java
git commit -m "feat: add navMode persistence to BotHomeService"
```

---

### Task 4: Network Payloads

**Files:**
- Create: `src/main/java/net/wcfcarolina13/network/BotNavTierPayload.java`
- Create: `src/main/java/net/wcfcarolina13/network/NavigationRequestPayload.java`
- Create: `src/main/java/net/wcfcarolina13/network/NavigationResponsePayload.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java` (PayloadTypeRegistry registration)

- [ ] **Step 1: Create BotNavTierPayload (S2C)**

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: bot's navigation tier for the spells screen. */
public record BotNavTierPayload(String botAlias, int tier) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "bot_nav_tier");
    public static final Id<BotNavTierPayload> ID = new Id<>(ID_IDENTIFIER);

    private static final PacketCodec<PacketByteBuf, Integer> INT_CODEC = new PacketCodec<>() {
        @Override public void encode(PacketByteBuf buf, Integer value) { buf.writeInt(value != null ? value : 0); }
        @Override public Integer decode(PacketByteBuf buf) { return buf.readInt(); }
    };

    public static final PacketCodec<PacketByteBuf, BotNavTierPayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(32767), BotNavTierPayload::botAlias,
                    INT_CODEC, BotNavTierPayload::tier,
                    BotNavTierPayload::new
            );

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
```

- [ ] **Step 2: Create NavigationRequestPayload (S2C)**

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: bot wants to auto-return home. Shows HUD overlay for acceptance. */
public record NavigationRequestPayload(String botAlias, String destination, int estimatedSeconds) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "nav_request");
    public static final Id<NavigationRequestPayload> ID = new Id<>(ID_IDENTIFIER);

    private static final PacketCodec<PacketByteBuf, Integer> INT_CODEC = new PacketCodec<>() {
        @Override public void encode(PacketByteBuf buf, Integer value) { buf.writeInt(value != null ? value : 0); }
        @Override public Integer decode(PacketByteBuf buf) { return buf.readInt(); }
    };

    public static final PacketCodec<PacketByteBuf, NavigationRequestPayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(32767), NavigationRequestPayload::botAlias,
                    new StringCodec(32767), NavigationRequestPayload::destination,
                    INT_CODEC, NavigationRequestPayload::estimatedSeconds,
                    NavigationRequestPayload::new
            );

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
```

- [ ] **Step 3: Create NavigationResponsePayload (C2S)**

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: player accepted or dismissed auto-return navigation. */
public record NavigationResponsePayload(String botAlias, boolean accepted) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "nav_response");
    public static final Id<NavigationResponsePayload> ID = new Id<>(ID_IDENTIFIER);

    private static final PacketCodec<PacketByteBuf, Boolean> BOOL_CODEC = new PacketCodec<>() {
        @Override public void encode(PacketByteBuf buf, Boolean value) { buf.writeBoolean(value != null && value); }
        @Override public Boolean decode(PacketByteBuf buf) { return buf.readBoolean(); }
    };

    public static final PacketCodec<PacketByteBuf, NavigationResponsePayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(32767), NavigationResponsePayload::botAlias,
                    BOOL_CODEC, NavigationResponsePayload::accepted,
                    NavigationResponsePayload::new
            );

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
```

- [ ] **Step 4: Register payloads in Frens.java**

Find the payload registration block (~line 445-506 in `Frens.java`). Add after existing registrations:

```java
// Navigation spell payloads
PayloadTypeRegistry.playS2C().register(BotNavTierPayload.ID, BotNavTierPayload.CODEC);
PayloadTypeRegistry.playS2C().register(NavigationRequestPayload.ID, NavigationRequestPayload.CODEC);
PayloadTypeRegistry.playC2S().register(NavigationResponsePayload.ID, NavigationResponsePayload.CODEC);
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/network/BotNavTierPayload.java \
        src/main/java/net/wcfcarolina13/network/NavigationRequestPayload.java \
        src/main/java/net/wcfcarolina13/network/NavigationResponsePayload.java \
        src/main/java/net/wcfcarolina13/Frens.java
git commit -m "feat: add network payloads for nav tier sync and auto-return notifications"
```

---

### Task 5: SpellNavigationNetworkManager

**Files:**
- Create: `src/main/java/net/wcfcarolina13/network/SpellNavigationNetworkManager.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java` (~line 510, manager registration)

- [ ] **Step 1: Create SpellNavigationNetworkManager**

```java
package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.NavigationArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles navigation-related network communication:
 * - BotNavTierPayload (S2C, sent on spells screen open)
 * - NavigationResponsePayload (C2S, player accepts/dismisses auto-return)
 */
public final class SpellNavigationNetworkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpellNavigationNetworkManager.class);
    private static boolean REGISTERED = false;

    private SpellNavigationNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) return;
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(NavigationResponsePayload.ID, (payload, context) ->
                context.server().execute(() -> handleNavigationResponse(context.server(), context.player(), payload))
        );
    }

    private static void handleNavigationResponse(MinecraftServer server, ServerPlayerEntity player,
                                                  NavigationResponsePayload payload) {
        if (server == null || player == null || player.isRemoved()) return;
        if (!payload.accepted()) {
            LOGGER.debug("Player {} dismissed auto-return for bot {}", player.getName().getString(), payload.botAlias());
            return;
        }

        // Find the bot via player manager (same pattern as modCommandRegistry companion commands)
        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(payload.botAlias());
        if (bot == null || bot.isRemoved()) {
            LOGGER.warn("Auto-return accepted but bot '{}' not found", payload.botAlias());
            return;
        }

        // resolveHomeTarget returns Optional<BlockPos>
        BotHomeService.resolveHomeTarget(bot).ifPresent(homePos ->
                BotEventHandler.setReturnToBase(bot, net.minecraft.util.math.Vec3d.ofCenter(homePos)));
    }

    /** Send nav tier to client when spells screen is opened. */
    public static void sendNavTierToClient(ServerPlayerEntity player, ServerPlayerEntity bot, String botAlias) {
        if (player == null || bot == null) return;
        NavigationArtifactService.NavTier tier = NavigationArtifactService.getBotNavigationTier(bot, player);
        ServerPlayNetworking.send(player, new BotNavTierPayload(
                botAlias != null ? botAlias : "", tier.ordinal()));
    }
}
```

- [ ] **Step 2: Register in Frens.java**

Add after the existing manager registrations (~line 518):
```java
net.wcfcarolina13.network.SpellNavigationNetworkManager.registerReceiversOnce();
```

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/network/SpellNavigationNetworkManager.java \
        src/main/java/net/wcfcarolina13/Frens.java
git commit -m "feat: add SpellNavigationNetworkManager for nav response handling"
```

---

## Chunk 2: Token Rework + Companion Commands

### Task 6: Remove Ender Pearl from Passive Token Checks

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java`

- [ ] **Step 1: Identify ender pearl checks in canUseCompanionCome/Summon/Home**

Use grep to find all references to `hasEnderPearlToken` within the companion permission methods. The ender pearl check appears in the bot-side portion of `canUseCompanionCome` (~line 4769), `canUseCompanionSummon` (~line 4786), and `canUseCompanionHome` (~line 4857).

Remove the `|| hasEnderPearlToken(bot)` from each of these three methods. Do NOT remove the method `hasEnderPearlToken()` itself — it will be reused for paired spell validation.

- [ ] **Step 2: Also remove ender pearl from player-side checks if present**

Search for `hasEnderPearlToken(commander)` in the same three methods. If found (unlikely based on exploration — ender pearl was bot-side only), remove those too.

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java
git commit -m "feat: remove ender pearl passive token from companion come/summon/home"
```

---

### Task 7: Add SpellGuidancePayload + Server Handlers

The NavigationConfirmScreen communicates via network payloads (NOT chat commands) to avoid command argument parsing issues. The server handles spell logic in SpellNavigationNetworkManager.

**Files:**
- Create: `src/main/java/net/wcfcarolina13/network/SpellGuidancePayload.java`
- Modify: `src/main/java/net/wcfcarolina13/network/SpellNavigationNetworkManager.java`
- Modify: `src/main/java/net/wcfcarolina13/Frens.java` (register C2S payload)

- [ ] **Step 1: Create SpellGuidancePayload (C2S)**

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: player casts Remote Guidance or Chorus Recall. */
public record SpellGuidancePayload(String botAlias, String spellType, String destination) implements CustomPayload {
    // spellType: "guidance" or "recall"
    // destination: "player" | base label (for guidance) | "bot_to_player" | "player_to_bot" (for recall)
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "spell_guidance");
    public static final Id<SpellGuidancePayload> ID = new Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, SpellGuidancePayload> CODEC =
            PacketCodec.tuple(
                    new StringCodec(32767), SpellGuidancePayload::botAlias,
                    new StringCodec(32767), SpellGuidancePayload::spellType,
                    new StringCodec(32767), SpellGuidancePayload::destination,
                    SpellGuidancePayload::new
            );

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
```

- [ ] **Step 2: Register payload in Frens.java**

Add: `PayloadTypeRegistry.playC2S().register(SpellGuidancePayload.ID, SpellGuidancePayload.CODEC);`

- [ ] **Step 3: Add spell handler to SpellNavigationNetworkManager**

Add a second receiver in `registerReceiversOnce()`:
```java
ServerPlayNetworking.registerGlobalReceiver(SpellGuidancePayload.ID, (payload, context) ->
        context.server().execute(() -> handleSpellCast(context.server(), context.player(), payload))
);
```

Add handler method:
```java
private static void handleSpellCast(MinecraftServer server, ServerPlayerEntity commander,
                                     SpellGuidancePayload payload) {
    if (server == null || commander == null || commander.isRemoved()) return;

    ServerPlayerEntity bot = server.getPlayerManager().getPlayer(payload.botAlias());
    if (bot == null || bot.isRemoved()) {
        commander.sendMessage(Text.literal("Companion not found."), false);
        return;
    }

    if ("guidance".equals(payload.spellType())) {
        handleRemoteGuidance(server, commander, bot, payload.destination());
    } else if ("recall".equals(payload.spellType())) {
        handleChorusRecall(server, commander, bot, payload.destination());
    }
}

private static void handleRemoteGuidance(MinecraftServer server, ServerPlayerEntity commander,
                                          ServerPlayerEntity bot, String destination) {
    // 1. Validate both hold ender pearls
    if (!NavigationArtifactService.bothHaveEnderPearl(commander, bot)) {
        commander.sendMessage(Text.literal("Both you and your companion must hold an Ender Pearl."), false);
        return;
    }
    // 2. Consume pearls from both
    NavigationArtifactService.consumeItem(commander, Items.ENDER_PEARL);
    NavigationArtifactService.consumeItem(bot, Items.ENDER_PEARL);
    // 3. Play acceptance sound at commander
    commander.getWorld().playSound(null, commander.getX(), commander.getY(), commander.getZ(),
            SoundEvents.ENTITY_ENDER_EYE_LAUNCH, SoundCategory.PLAYERS, 0.7f, 1.2f);
    // 4. Resolve destination (player pos or base)
    BlockPos goal;
    if ("player".equals(destination)) {
        goal = commander.getBlockPos();
    } else {
        // Look up base by label via BotHomeService
        Optional<BlockPos> basePos = BotHomeService.getBasePosition(bot, destination);
        if (basePos.isEmpty()) {
            commander.sendMessage(Text.literal("Base '" + destination + "' not found."), false);
            return;
        }
        goal = basePos.get();
    }
    // 5. Check nav mode and tier — determine travel method
    NavTier tier = NavigationArtifactService.getBotNavigationTier(bot, commander);
    String navMode = BotHomeService.getNavMode(bot);
    if (tier == NavTier.ENHANCED || "WALK".equals(navMode)) {
        // Instant teleport (enhanced) or walk mode
        if (tier == NavTier.ENHANCED) {
            // Instant teleport
            Vec3d center = Vec3d.ofCenter(goal);
            bot.teleport((ServerWorld) commander.getWorld(), center.x, center.y, center.z,
                    Set.of(), commander.getYaw(), commander.getPitch(), true);
        } else {
            BotEventHandler.setComeModeWalk(bot, commander, goal, 3.2D, true);
        }
    } else {
        // Teleport with delay
        double dist = Math.sqrt(bot.squaredDistanceTo(Vec3d.ofCenter(goal)));
        boolean crossDim = !bot.getWorld().getRegistryKey().equals(commander.getWorld().getRegistryKey());
        int delayTicks = NavigationArtifactService.calculateDelayTicks(dist, crossDim);
        NavigationArtifactService.beginDelayedTravel(server, bot, payload.botAlias(),
                goal, commander.getWorld().getRegistryKey(), delayTicks, commander.getUuid());
    }
    commander.sendMessage(Text.literal("Remote Guidance cast. Your companion is on the way."), false);
}

private static void handleChorusRecall(MinecraftServer server, ServerPlayerEntity commander,
                                        ServerPlayerEntity bot, String direction) {
    // 1. Validate both hold pearl + chorus
    if (!NavigationArtifactService.bothHaveChorusRecallItems(commander, bot)) {
        commander.sendMessage(Text.literal(
                "Both you and your companion must hold an Ender Pearl and Chorus Fruit."), false);
        return;
    }
    // 2. Consume all 4 items
    NavigationArtifactService.consumeItem(commander, Items.ENDER_PEARL);
    NavigationArtifactService.consumeItem(commander, Items.CHORUS_FRUIT);
    NavigationArtifactService.consumeItem(bot, Items.ENDER_PEARL);
    NavigationArtifactService.consumeItem(bot, Items.CHORUS_FRUIT);
    // 3. Play cast sound
    commander.getWorld().playSound(null, commander.getX(), commander.getY(), commander.getZ(),
            SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1.4f);
    // 4. Teleport based on direction
    if ("bot_to_player".equals(direction)) {
        Vec3d target = new Vec3d(commander.getX(), commander.getY(), commander.getZ());
        bot.teleport((ServerWorld) commander.getWorld(), target.x, target.y, target.z,
                Set.of(), commander.getYaw(), commander.getPitch(), true);
    } else {
        Vec3d target = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        commander.teleport((ServerWorld) bot.getWorld(), target.x, target.y, target.z,
                Set.of(), bot.getYaw(), bot.getPitch(), true);
    }
    // 5. Play arrival sound
    commander.getWorld().playSound(null, commander.getX(), commander.getY(), commander.getZ(),
            SoundEvents.ENTITY_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
    commander.sendMessage(Text.literal("Chorus Recall complete."), false);
}
```

Note: `BotHomeService.getBasePosition(bot, label)` may not exist with this exact signature. Verify via grep — look for how bases are retrieved by label. If it returns a different type, adapt accordingly.

- [ ] **Step 4: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/wcfcarolina13/network/SpellGuidancePayload.java \
        src/main/java/net/wcfcarolina13/network/SpellNavigationNetworkManager.java \
        src/main/java/net/wcfcarolina13/Frens.java
git commit -m "feat: add Remote Guidance and Chorus Recall spell handlers via network payload"
```

---

### Task 8: Delayed Teleport Travel System

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java`

- [ ] **Step 1: Add PendingTravel record and tick-based scheduling**

Add to NavigationArtifactService:

```java
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** In-transit bot travel state. */
public record PendingTravel(UUID botUuid, String botAlias, BlockPos destination,
                            RegistryKey<World> dimension, long departureTick, long arrivalTick,
                            UUID ownerUuid) {}

private static final Map<UUID, PendingTravel> PENDING_TRAVELS = new ConcurrentHashMap<>();

/** Calculate travel delay in ticks. 1 real second per chunk, min 5s, max 5min. Cross-dim +30s. */
public static int calculateDelayTicks(double distance, boolean crossDimension) {
    int chunks = Math.max(1, (int) Math.ceil(distance / 16.0));
    int seconds = chunks; // 1 second per chunk
    if (crossDimension) seconds += 30;
    seconds = Math.max(5, Math.min(300, seconds)); // clamp 5s-5min
    return seconds * 20; // convert to ticks
}

/** Begin delayed teleport. Removes bot from world, schedules arrival. */
public static void beginDelayedTravel(MinecraftServer server, ServerPlayerEntity bot,
                                       String botAlias, BlockPos destination,
                                       RegistryKey<World> dimension, int delayTicks,
                                       UUID ownerUuid) {
    long now = server.getOverworld().getTime();
    PendingTravel travel = new PendingTravel(
            bot.getUuid(), botAlias, destination, dimension, now, now + delayTicks, ownerUuid);
    PENDING_TRAVELS.put(bot.getUuid(), travel);

    // Cleanup bot state before removal (on server thread)
    // TaskService, FollowMovementService, BotCommandStateService cleanup
    // Set mode to TRAVELING
    BotCommandStateService.stateFor(bot.getUuid()).mode = BotEventHandler.Mode.TRAVELING;

    // Remove bot from world
    bot.discard();
}

/** Called every server tick. Checks for arriving bots and respawns them. */
public static void tickPendingTravels(MinecraftServer server) {
    if (PENDING_TRAVELS.isEmpty()) return;
    long now = server.getOverworld().getTime();
    var iterator = PENDING_TRAVELS.entrySet().iterator();
    while (iterator.hasNext()) {
        var entry = iterator.next();
        PendingTravel travel = entry.getValue();
        if (now >= travel.arrivalTick()) {
            iterator.remove();
            respawnBotAtDestination(server, travel);
        }
    }
}

private static void respawnBotAtDestination(MinecraftServer server, PendingTravel travel) {
    // Respawn via createFakePlayer.createFake() at destination
    // Restore inventory/stats via BotPersistenceService
    // Play arrival sound
}
```

Note: `respawnBotAtDestination` implementation depends on how `createFakePlayer.createFake()` works. The exploration showed it takes (username, server, pos, yaw, pitch, dimensionId, gamemode, flying). The bot's alias and persistence data must be used to reconstruct it.

- [ ] **Step 2: Hook tickPendingTravels into server tick**

Add a call to `NavigationArtifactService.tickPendingTravels(server)` in the main server tick handler. Find where `BotAutoReturnSunsetService.onServerTick()` is called (in `BotEventHandler` or `Frens`), and add the travel tick nearby.

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/NavigationArtifactService.java
git commit -m "feat: add delayed teleport travel system with PendingTravel scheduling"
```

---

## Chunk 3: Client UI — Spells Screen + Confirmation

### Task 9: Update CompanionSpellsScreen

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/CompanionSpellsScreen.java`

- [ ] **Step 1: Extend AccessState with new fields**

Change the `AccessState` inner class:
```java
private static final class AccessState {
    final boolean full;
    final boolean eye;
    final boolean horn;
    final boolean playerHasPearl;
    final boolean playerHasChorus;
    final int botNavTier; // 0=NONE, 1=BASIC, 2=ENHANCED

    private AccessState(boolean full, boolean eye, boolean horn,
                        boolean playerHasPearl, boolean playerHasChorus, int botNavTier) {
        this.full = full;
        this.eye = eye;
        this.horn = horn;
        this.playerHasPearl = playerHasPearl;
        this.playerHasChorus = playerHasChorus;
        this.botNavTier = botNavTier;
    }
}
```

- [ ] **Step 2: Add button fields and create buttons in init()**

Add fields:
```java
private ButtonWidget guidanceBtn;
private ButtonWidget recallBtn;
```

In `init()`, after the homeBtn and before openInvBtn, add:
```java
guidanceBtn = this.addDrawableChild(ButtonWidget.builder(
        Text.literal("Remote Guidance"), (btn) -> openGuidanceConfirm())
        .dimensions(cx - w / 2, top + 3 * (h + gap), w, h)
        .build());

recallBtn = this.addDrawableChild(ButtonWidget.builder(
        Text.literal("Chorus Recall"), (btn) -> openRecallConfirm())
        .dimensions(cx - w / 2, top + 4 * (h + gap), w, h)
        .build());
```

Shift openInvBtn down to `top + 5 * (h + gap)` and Back button to `top + 6 * (h + gap) + 10`.

- [ ] **Step 3: Update getAccessState() to include new fields**

```java
private AccessState getAccessState() {
    MinecraftClient client = this.client;
    if (client == null || client.player == null) {
        return new AccessState(false, false, false, false, false, 0);
    }
    boolean full = isNearEnchantingTable(client, 4) || hasSpellbookToken(client);
    boolean eye = !full && hasEyeOfEnderToken(client);
    boolean horn = !full && hasGoatHornToken(client);
    boolean playerHasPearl = hasEnderPearlInInventory(client);
    boolean playerHasChorus = playerHasPearl && hasChorusFruitInInventory(client);
    int botNavTier = FrensClient.getCachedBotNavTier();
    return new AccessState(full, eye, horn, playerHasPearl, playerHasChorus, botNavTier);
}
```

Add these helper methods (same pattern as existing `hasGoatHornToken`):

```java
private boolean hasEnderPearlInInventory(MinecraftClient client) {
    if (client == null || client.player == null) return false;
    var inv = client.player.getInventory();
    for (int i = 0; i < inv.size(); i++) {
        var stack = inv.getStack(i);
        if (stack != null && !stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) return true;
    }
    return false;
}

private boolean hasChorusFruitInInventory(MinecraftClient client) {
    if (client == null || client.player == null) return false;
    var inv = client.player.getInventory();
    for (int i = 0; i < inv.size(); i++) {
        var stack = inv.getStack(i);
        if (stack != null && !stack.isEmpty() && stack.isOf(Items.CHORUS_FRUIT)) return true;
    }
    return false;
}
```

- [ ] **Step 4: Update refreshEnabledState()**

```java
private void refreshEnabledState() {
    AccessState state = getAccessState();
    boolean eyeReady = state.eye && !FrensClient.isEyeSpellOnCooldown();
    if (comeBtn != null) comeBtn.active = state.full || state.horn;
    if (summonBtn != null) summonBtn.active = state.full || eyeReady;
    if (homeBtn != null) homeBtn.active = state.full || state.botNavTier >= 1;
    if (guidanceBtn != null) guidanceBtn.active = state.full || state.playerHasPearl;
    if (recallBtn != null) recallBtn.active = state.full || state.playerHasChorus;
    if (openInvBtn != null) openInvBtn.active = state.full;
}
```

- [ ] **Step 5: Add openGuidanceConfirm() and openRecallConfirm() methods**

```java
private void openGuidanceConfirm() {
    if (this.client != null) {
        this.client.setScreen(new NavigationConfirmScreen(this, botAlias, "guidance"));
    }
}

private void openRecallConfirm() {
    if (this.client != null) {
        this.client.setScreen(new NavigationConfirmScreen(this, botAlias, "recall"));
    }
}
```

- [ ] **Step 6: Update render() hint text**

Update the hint text in `render()` to mention Remote Guidance and Chorus Recall access when applicable.

- [ ] **Step 7: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/CompanionSpellsScreen.java
git commit -m "feat: add Remote Guidance and Chorus Recall buttons to spells screen"
```

---

### Task 10: NavigationConfirmScreen

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/NavigationConfirmScreen.java`

- [ ] **Step 1: Create NavigationConfirmScreen**

Follow the `RecruitmentDialogueScreen` pattern. The screen has two modes based on `spellType`:
- `"guidance"`: Shows destination selector (radio: "Guide to me" / "Guide to base: [base list]") + confirm/cancel
- `"recall"`: Shows direction selector (radio: "Teleport bot to me" / "Teleport me to bot") + confirm/cancel

```java
package net.wcfcarolina13.GraphicalUserInterface;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class NavigationConfirmScreen extends Screen {
    private final Screen parent;
    private final String botAlias;
    private final String spellType; // "guidance" or "recall"
    private int selectedOption = 0; // 0 = first radio, 1 = second radio

    public NavigationConfirmScreen(Screen parent, String botAlias, String spellType) {
        super(Text.literal(spellType.equals("guidance") ? "Remote Guidance" : "Chorus Recall"));
        this.parent = parent;
        this.botAlias = botAlias;
        this.spellType = spellType;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 160;
        int h = 20;

        // Radio option buttons (toggle selection)
        String opt1 = spellType.equals("guidance") ? "Guide to me" : "Teleport bot to me";
        String opt2 = spellType.equals("guidance") ? "Guide to base" : "Teleport me to bot";

        this.addDrawableChild(ButtonWidget.builder(Text.literal(opt1), btn -> selectedOption = 0)
                .dimensions(cx - w / 2, 60, w, h).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal(opt2), btn -> selectedOption = 1)
                .dimensions(cx - w / 2, 60 + h + 4, w, h).build());

        // Confirm / Cancel
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Confirm"), btn -> confirm())
                .dimensions(cx - w / 2, this.height - 60, 76, h).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), btn -> close())
                .dimensions(cx - w / 2 + 84, this.height - 60, 76, h).build());
    }

    private void confirm() {
        MinecraftClient client = this.client;
        if (client == null) return;

        String destination;
        if (spellType.equals("guidance")) {
            destination = selectedOption == 0 ? "player" : "base"; // TODO: base name from dropdown
        } else {
            destination = selectedOption == 0 ? "bot_to_player" : "player_to_bot";
        }

        // Send spell cast via network payload (NOT chat command — avoids argument parsing issues)
        net.fabricmc.fabric.api.networking.v1.ClientPlayNetworking.send(
                new net.wcfcarolina13.network.SpellGuidancePayload(botAlias, spellType, destination));

        // Play acceptance sound client-side
        if (client.player != null) {
            client.player.playSound(net.minecraft.sound.SoundEvents.ENTITY_ENDER_EYE_LAUNCH, 0.7f, 1.2f);
        }
        close();
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, cx, 20, 0xFFFFFF);

        // Selected indicator
        String indicator = "Selected: " + (selectedOption == 0 ? "Option 1" : "Option 2");
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(indicator), cx, 110, 0xFFB0B0B0);

        // Warning text
        String warning = spellType.equals("guidance")
                ? "Both ender pearls will be consumed."
                : "Ender pearl and chorus fruit will be consumed from both.";
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(warning), cx, this.height - 80, 0xFFFF8888);
    }
}
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/NavigationConfirmScreen.java
git commit -m "feat: add NavigationConfirmScreen with destination/direction selection"
```

---

### Task 11: NavigationHudOverlay

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/NavigationHudOverlay.java`
- Modify: `src/main/java/net/wcfcarolina13/FrensClient.java`

- [ ] **Step 1: Create NavigationHudOverlay**

```java
package net.wcfcarolina13.GraphicalUserInterface;

import net.fabricmc.fabric.api.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.wcfcarolina13.network.NavigationResponsePayload;

/**
 * Non-obstructive HUD overlay for auto-return sunset notifications.
 * Renders above the hotbar with Accept/Dismiss clickable areas.
 */
public final class NavigationHudOverlay {
    private static String pendingBotAlias = null;
    private static String pendingDestination = null;
    private static int pendingSeconds = 0;
    private static long showUntilMs = 0L;

    private NavigationHudOverlay() {}

    /** Called when server sends NavigationRequestPayload. */
    public static void show(String botAlias, String destination, int estimatedSeconds) {
        pendingBotAlias = botAlias;
        pendingDestination = destination;
        pendingSeconds = estimatedSeconds;
        showUntilMs = System.currentTimeMillis() + 60_000L; // auto-dismiss after 60s
    }

    public static boolean isVisible() {
        return pendingBotAlias != null && System.currentTimeMillis() < showUntilMs;
    }

    public static void dismiss() {
        if (pendingBotAlias != null) {
            ClientPlayNetworking.send(new NavigationResponsePayload(pendingBotAlias, false));
        }
        clear();
    }

    public static void accept() {
        if (pendingBotAlias != null) {
            ClientPlayNetworking.send(new NavigationResponsePayload(pendingBotAlias, true));
        }
        clear();
    }

    private static void clear() {
        pendingBotAlias = null;
        pendingDestination = null;
        pendingSeconds = 0;
        showUntilMs = 0L;
    }

    /** Called from HudRenderCallback. */
    public static void render(DrawContext context) {
        if (!isVisible()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;

        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();

        // Render above hotbar
        int y = screenH - 68;
        int cx = screenW / 2;

        String msg = pendingBotAlias + " wants to return home. (~" + pendingSeconds + "s)";
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(msg), cx, y, 0xFFFFCC00);

        // Accept / Dismiss hints
        context.drawCenteredTextWithShadow(client.textRenderer,
                Text.literal("[Y] Accept   [N] Dismiss"), cx, y + 12, 0xFFB0B0B0);
    }

    /** Handle keyboard input for Y/N accept/dismiss. Call from FrensClient key handler. */
    public static boolean handleKeyPress(int keyCode) {
        if (!isVisible()) return false;
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) {
            accept();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_N) {
            dismiss();
            return true;
        }
        return false;
    }
}
```

- [ ] **Step 2: Register HUD overlay and network receiver in FrensClient**

In `FrensClient.java`, add to the HUD registration block (~line 782-796):
```java
HudRenderCallback.EVENT.register((context, tickDelta) -> NavigationHudOverlay.render(context));
```

Add client network receiver for `NavigationRequestPayload` (~line 584-778):
```java
ClientPlayNetworking.registerGlobalReceiver(NavigationRequestPayload.ID, (payload, context) -> {
    NavigationHudOverlay.show(payload.botAlias(), payload.destination(), payload.estimatedSeconds());
    // Play prompt sound
    MinecraftClient client = MinecraftClient.getInstance();
    if (client != null && client.player != null) {
        client.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.0f);
    }
});
```

Add client receiver for `BotNavTierPayload`:
```java
ClientPlayNetworking.registerGlobalReceiver(BotNavTierPayload.ID, (payload, context) -> {
    FrensClient.setCachedBotNavTier(payload.tier());
});
```

Add the cache field and getter/setter to FrensClient:
```java
private static int cachedBotNavTier = 0;
public static int getCachedBotNavTier() { return cachedBotNavTier; }
public static void setCachedBotNavTier(int tier) { cachedBotNavTier = tier; }
```

Add NavigationHudOverlay key handling to the existing `ClientTickEvents.END_CLIENT_TICK` callback in FrensClient. Find where `handleResumeKey()` or other key handlers are called (grep for `ClientTickEvents.END_CLIENT_TICK`), and add:
```java
if (NavigationHudOverlay.handleKeyPress(/* see below */)) { /* consumed */ }
```

Note: The key handling uses GLFW key codes. The overlay checks Y/N directly via `GLFW.GLFW_KEY_Y` and `GLFW.GLFW_KEY_N`. Hook into the tick event and check `InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_Y)` / `GLFW_KEY_N` when the overlay is visible. Alternatively, integrate via `Screen.keyPressed()` if a screen is open, but since this is a HUD overlay (no screen), use the tick-based input check pattern.

- [ ] **Step 3: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/NavigationHudOverlay.java \
        src/main/java/net/wcfcarolina13/FrensClient.java
git commit -m "feat: add NavigationHudOverlay and client-side network receivers"
```

---

## Chunk 4: Integration + Guide + Polish

### Task 12: Auto-Return Sunset Integration

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java`

- [ ] **Step 1: Integrate navigation artifact check into sunset trigger**

Find where `BotEventHandler.setReturnToBase(bot, ...)` is called in BotAutoReturnSunsetService (~line 171). Replace or wrap that call with navigation-aware logic.

Use grep to find the exact call site and the variables available in scope (bot alias, home position, server reference). The existing code likely has `bot` (ServerPlayerEntity), `homePos` (BlockPos), and access to `server` (MinecraftServer).

**Before the existing `setReturnToBase` call, insert:**

```java
// Look up the bot's commander/owner — use the bot's recruitment owner or commander UUID
// Pattern: check BotEventHandler or BotCommandStateService for owner tracking
// If no owner tracking exists, use server.getPlayerManager() to find online players who own this bot

// If owner is online, send non-obstructive notification instead of direct auto-return
ServerPlayerEntity owner = /* resolve owner — grep for how other services find the bot's owner */;
if (owner != null && !owner.isRemoved()) {
    String alias = bot.getName().getString();
    Optional<BlockPos> homeOpt = BotHomeService.resolveHomeTarget(bot);
    if (homeOpt.isPresent()) {
        BlockPos home = homeOpt.get();
        double dist = Math.sqrt(bot.squaredDistanceTo(Vec3d.ofCenter(home)));
        boolean crossDim = false; // Auto-return is Overworld-only per existing code (line 74)
        int seconds = NavigationArtifactService.calculateDelayTicks(dist, crossDim) / 20;
        ServerPlayNetworking.send(owner, new NavigationRequestPayload(alias, "home", seconds));
        return; // Wait for player response via NavigationResponsePayload
    }
}

// Fallback: owner offline or no home — proceed with direct return (existing behavior)
```

**Key:** The exact owner-resolution method depends on how the mod tracks bot ownership. Grep for `commander`, `owner`, `recruiter` in BotEventHandler and BotCommandStateService to find the pattern. If no explicit owner tracking exists, fall back to the existing direct `setReturnToBase` call (no notification).

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/BotAutoReturnSunsetService.java
git commit -m "feat: integrate nav artifact check and player notification into auto-return"
```

---

### Task 13: Send Nav Tier on Spells Screen Open

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java`

- [ ] **Step 1: Find where spells screen is triggered server-side**

Spells screen is opened client-side (the screen opens locally). But we need the server to send the nav tier when the player opens it. Two options:

**Option A (simpler):** Send nav tier as part of the companion command dispatch. When any companion command is issued, the server already resolves the bot — piggyback a nav tier payload send.

**Option B (on-demand):** Create a new C2S request payload that the client sends when opening the spells screen, and the server responds with the nav tier.

Follow Option A: In the companion command resolution path (the shared bot-resolution code used by all `executeCompanion*` methods), add:
```java
SpellNavigationNetworkManager.sendNavTierToClient(commander, bot, alias);
```

This ensures the nav tier is refreshed every time the player interacts with the companion system.

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java
git commit -m "feat: send nav tier to client on companion command execution"
```

---

### Task 14: Guide Entries

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java`

- [ ] **Step 1: Add 5 new GuideTopic entries to baseTopics()**

Add before the closing `);` of the `List.of(...)` in `baseTopics()` method (~line 1031):

```java
,
new GuideTopic(
        "items_nav_artifacts",
        "Items",
        "Navigation Artifacts",
        "Give your companion a Compass, Map, or Eye of Ender to unlock autonomous navigation.",
        List.of(
                "Compass / Map (basic): bot can navigate to its nearest or preferred base, same dimension only.",
                "Eye of Ender (enhanced): navigate to any named base, cross-dimension, instant teleport. Either you or the bot can hold it.",
                "Use /bot open <alias> to give items to your companion via its inventory screen.",
                "Navigation artifacts are reusable — they are not consumed."
        ),
        "Give item to bot via /bot open <alias>",
        "No keybind",
        "compass map eye ender navigation artifact tier"
),
new GuideTopic(
        "spells_remote_guidance",
        "Spells",
        "Remote Guidance",
        "Uses paired ender pearls to guide your companion across any distance.",
        List.of(
                "Both you and your companion must hold at least one Ender Pearl.",
                "Choose destination: guide to your location or to a known base.",
                "Both pearls are consumed when travel begins.",
                "Travel uses your configured navigation mode (walk or teleport with delay).",
                "Open the Spells menu to cast this spell."
        ),
        "bot companion guidance " + target,
        "Spells menu: Remote Guidance button",
        "spell guidance ender pearl paired navigation remote"
),
new GuideTopic(
        "spells_chorus_recall",
        "Spells",
        "Chorus Recall",
        "Consumes paired ender pearls and chorus fruit for an instant teleport.",
        List.of(
                "Both you and your companion must hold one Ender Pearl AND one Chorus Fruit.",
                "Choose direction: teleport bot to you, or you to bot.",
                "Works across dimensions. Always instant — no delay.",
                "All four items are consumed (one pearl + one chorus from each)."
        ),
        "bot companion recall " + target,
        "Spells menu: Chorus Recall button",
        "spell recall chorus fruit ender pearl teleport instant paired"
),
new GuideTopic(
        "settings_nav_modes",
        "Settings",
        "Navigation Modes",
        "Choose how your companion travels long distances: walk or delayed teleport.",
        List.of(
                "Walk mode: bot pathfinds in 32-block segments. Realistic but slow, can get stuck.",
                "Teleport-delay mode (default): bot disappears for ~1 second per chunk of distance, then reappears at destination.",
                "Cross-dimension adds 30 seconds. Minimum 5s, maximum 5 minutes.",
                "Eye of Ender holders bypass delay entirely — always instant.",
                "Configure via /bot config <alias> nav_mode walk or teleport."
        ),
        "bot config " + target + " nav_mode walk|teleport",
        "Bot Controls panel",
        "navigation mode walk teleport delay config setting"
),
new GuideTopic(
        "items_spell_ingredients",
        "Items",
        "Spell Ingredients",
        "Quick reference for all spell-related items and what they unlock.",
        List.of(
                "Wizard's Tome: full access to all companion spells anywhere.",
                "Enchanting Table (nearby): full access to all spells.",
                "Goat Horn: regroup only (player holds).",
                "Eye of Ender: summon only with 60s cooldown (player holds); enhanced navigation (either holds).",
                "Ender Pearl (paired): Remote Guidance spell — both must hold, consumed.",
                "Ender Pearl + Chorus Fruit (paired): Chorus Recall — instant teleport, consumed."
        ),
        "Reference only",
        "No keybind",
        "spell ingredient wizard tome eye ender pearl chorus goat horn enchanting"
)
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java
git commit -m "feat: add 5 guide topics for navigation artifacts, spells, and ingredients"
```

---

### Task 15: Nav Mode Config Command

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/Commands/BotHomeCommands.java` (this is where existing per-bot config commands like auto_return_sunset live)

- [ ] **Step 1: Add nav_mode subcommand**

Find `BotHomeCommands.java` and add a `nav_mode` literal to the command tree, following the pattern of existing toggles like `auto_return_sunset`. The command should accept `walk` or `teleport` as arguments:

```java
.then(CommandManager.literal("nav_mode")
        .then(CommandManager.literal("walk")
                .executes(ctx -> {
                    // resolve bot, call BotHomeService.setNavMode(bot, "WALK")
                    // feedback: "Navigation mode set to walk."
                    return 1;
                }))
        .then(CommandManager.literal("teleport")
                .executes(ctx -> {
                    // resolve bot, call BotHomeService.setNavMode(bot, "TELEPORT_DELAY")
                    // feedback: "Navigation mode set to teleport with delay."
                    return 1;
                })))
```

Note: Verify the exact command tree structure in BotHomeCommands by reading the file first. The pattern may be `/bot config <alias> nav_mode walk` or `/bot home nav_mode walk` depending on how the existing home commands are structured.

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Commands/BotHomeCommands.java
git commit -m "feat: add /bot config nav_mode walk|teleport command"
```

---

### Task 16: Sound Helper Constants

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java`

- [ ] **Step 1: Add spell sound helper constants**

Add static final fields (these are convenience references to vanilla sounds, NOT new registered events):

```java
// -- Spell sound helpers (vanilla SoundEvents, not registered) --
public static final net.minecraft.sound.SoundEvent SPELL_GUIDANCE_PROMPT = net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
public static final net.minecraft.sound.SoundEvent SPELL_GUIDANCE_ACCEPT = net.minecraft.sound.SoundEvents.ENTITY_ENDER_EYE_LAUNCH;
public static final net.minecraft.sound.SoundEvent SPELL_GUIDANCE_CONSUME = net.minecraft.sound.SoundEvents.ENTITY_ENDER_PEARL_THROW;
public static final net.minecraft.sound.SoundEvent SPELL_CHORUS_RECALL = net.minecraft.sound.SoundEvents.ENTITY_CHORUS_FRUIT_TELEPORT;
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew build -x test`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/ChatUtils/BotDialogueSounds.java
git commit -m "feat: add spell sound helper constants to BotDialogueSounds"
```

---

### Task 17: Final Build + Changelog

- [ ] **Step 1: Full build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Update changelog.md**

Add entry for all changes in this feature set.

- [ ] **Step 3: Final commit**

```bash
git add changelog.md
git commit -m "docs: update changelog for navigation and spell mechanics"
```

---

## Verification

After all tasks complete, verify in-game:

1. Give bot compass -> Home button activates in Spells Menu -> send home -> verify arrival
2. Give bot/player Eye of Ender -> any-base selection works -> cross-dimension -> instant
3. Both hold pearls -> Remote Guidance button active -> cast -> verify navigation + pearl consumption
4. Both hold pearl+chorus -> Chorus Recall button active -> cast -> bidirectional teleport + item consumption
5. Toggle walk vs teleport-delay via `/bot config <alias> nav_mode walk` -> verify both modes
6. Confirmation screen appears for Remote Guidance/Recall with destination/direction selection
7. HUD overlay appears for auto-return sunset notification -> Y/N to accept/dismiss
8. All 3 Remote Guidance sounds play (chime on prompt, ender eye on confirm, pearl throw on arrive)
9. Chorus Recall sounds play (enderman teleport on cast, chorus fruit on arrival)
10. Guide topics searchable: "Remote Guidance", "Chorus Recall", "Navigation Artifacts", etc.
11. Old pearl passive behavior removed: holding a pearl alone no longer enables come/summon
12. Missing items -> descriptive rejection message
