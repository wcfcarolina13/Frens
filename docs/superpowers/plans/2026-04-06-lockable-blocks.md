# Lockable Blocks — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let players mark individual doors, fence gates, and trapdoors as locked so bots treat them as solid walls — never opening or routing through them.

**Architecture:** New `LockableBlockService` owns the lock registry and persistence. Two network payloads for toggling lock mode. The `UseBlockCallback` in `Frens.java` intercepts right-clicks while in lock mode. Pathfinders and `MovementService.tryOpenDoorAt()` check `isLocked()` before treating openables as passable. Visual feedback via vanilla actionbar/particle/sound packets.

**Tech Stack:** Minecraft Fabric 1.21.11, Java 21, Gson for persistence. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-04-06-lockable-blocks-design.md`

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/.../GameAI/services/LockableBlockService.java` | Create | Lock registry, persistence, query API, lock mode state, particle/feedback |
| `src/.../network/LockModeTogglePayload.java` | Create | C2S payload: player toggles lock mode |
| `src/.../network/LockModeStatePayload.java` | Create | S2C payload: sync lock mode state to client |
| `src/.../Frens.java` | Modify | Register payloads, add UseBlockCallback hook, load/save on lifecycle |
| `src/.../FrensClient.java` | Modify | Register S2C receiver, track client-side lock mode state |
| `src/.../GraphicalUserInterface/BotControlScreen.java` | Modify | Add "Lock Blocks" footer button |
| `src/.../GameAI/services/MovementService.java` | Modify | Check isLocked() in tryOpenDoorAt() |
| `src/.../PathFinding/BaritoneStylePathFinder.java` | Modify | Check isLocked() for DoorBlock in isPassable() |
| `src/.../PathFinding/PathFinder.java` | Modify | Check isLocked() for DoorBlock in isPassable() |
| `src/.../GameAI/services/FollowPathService.java` | Modify | Check isLocked() in isPassableForPlan() |
| `changelog.md` | Modify | Document the feature |

All source paths relative to `src/main/java/net/wcfcarolina13/`.

---

### Task 1: Create LockableBlockService

**Files:**
- Create: `src/.../GameAI/services/LockableBlockService.java`

- [ ] **Step 1: Create the service file**

```java
package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LockableBlockService {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ZONE_ROOT_DIR = "bot_zones";
    private static final String LOCK_FILE_NAME = "locked_blocks.json";

    // Lock registry: dimension -> set of locked block positions
    private static final Map<String, Set<BlockPos>> LOCKED_BLOCKS = new ConcurrentHashMap<>();

    // Lock mode active per player (transient, not persisted)
    private static final Map<UUID, Boolean> LOCK_MODE = new ConcurrentHashMap<>();

    // Bot reaction cooldown: botUuid -> (blockPos -> lastReactionMs)
    private static final Map<UUID, Map<BlockPos, Long>> BOT_REACTION_COOLDOWN = new ConcurrentHashMap<>();
    private static final long REACTION_COOLDOWN_MS = 30_000L;

    private LockableBlockService() {}

    // --- Lock mode ---

    public static boolean isLockModeActive(UUID playerUuid) {
        return LOCK_MODE.getOrDefault(playerUuid, false);
    }

    public static void setLockMode(UUID playerUuid, boolean active) {
        if (active) {
            LOCK_MODE.put(playerUuid, true);
        } else {
            LOCK_MODE.remove(playerUuid);
        }
    }

    public static void clearLockMode(UUID playerUuid) {
        LOCK_MODE.remove(playerUuid);
    }

    // --- Lock queries ---

    public static boolean isLocked(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        String worldId = world.getRegistryKey().getValue().toString();
        Set<BlockPos> locks = LOCKED_BLOCKS.get(worldId);
        if (locks == null || locks.isEmpty()) return false;
        // Normalize double-height doors: check both halves
        if (locks.contains(pos)) return true;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.contains(DoorBlock.HALF)) {
                BlockPos other = state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                        ? pos.down() : pos.up();
                return locks.contains(other);
            }
        }
        return false;
    }

    public static boolean isLockableBlock(BlockState state) {
        if (state == null) return false;
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof TrapdoorBlock;
    }

    // --- Lock mutations ---

    public static boolean toggleLock(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        if (world == null || pos == null || player == null) return false;
        BlockState state = world.getBlockState(pos);
        if (!isLockableBlock(state)) return false;

        // Normalize door to base (lower half)
        BlockPos lockPos = pos;
        if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.HALF)
                && state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            lockPos = pos.down();
        }

        String worldId = world.getRegistryKey().getValue().toString();
        Set<BlockPos> locks = LOCKED_BLOCKS.computeIfAbsent(worldId, k -> ConcurrentHashMap.newKeySet());

        boolean wasLocked = locks.contains(lockPos);
        String blockName = state.getBlock().getName().getString();

        if (wasLocked) {
            locks.remove(lockPos);
            player.networkHandler.sendPacket(new OverlayMessageS2CPacket(
                    Text.literal("\u00A7aUnlocked " + blockName)));
            world.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(),
                    SoundCategory.BLOCKS, 0.7f, 1.2f);
        } else {
            locks.add(lockPos.toImmutable());
            player.networkHandler.sendPacket(new OverlayMessageS2CPacket(
                    Text.literal("\u00A7cLocked " + blockName)));
            world.playSound(null, pos, SoundEvents.BLOCK_CHEST_LOCKED,
                    SoundCategory.BLOCKS, 0.7f, 1.0f);
        }

        saveForWorld(world.getServer(), worldId);
        return true;
    }

    // --- Crosshair feedback ---

    public static void tickCrosshairFeedback(ServerPlayerEntity player, ServerWorld world) {
        if (player == null || world == null) return;
        if (!isLockModeActive(player.getUuid())) return;

        // Raycast from player's eye to find targeted block
        var hitResult = player.raycast(5.0, 0.0f, false);
        if (!(hitResult instanceof net.minecraft.util.hit.BlockHitResult blockHit)) return;
        if (blockHit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return;

        BlockPos targetPos = blockHit.getBlockPos();
        BlockState state = world.getBlockState(targetPos);
        if (!isLockableBlock(state)) return;

        boolean locked = isLocked(world, targetPos);
        String msg = locked
                ? "\u00A7c\u00A7lLocked \u00A77— Right-click to unlock"
                : "\u00A7a\u00A7lUnlocked \u00A77— Right-click to lock";
        player.networkHandler.sendPacket(new OverlayMessageS2CPacket(Text.literal(msg)));
    }

    // --- Particle visualization ---

    public static void tickParticles(ServerPlayerEntity player, ServerWorld world) {
        if (player == null || world == null) return;
        if (!isLockModeActive(player.getUuid())) return;

        String worldId = world.getRegistryKey().getValue().toString();
        Set<BlockPos> locks = LOCKED_BLOCKS.get(worldId);
        if (locks == null || locks.isEmpty()) return;

        double maxDistSq = 32.0 * 32.0;
        for (BlockPos pos : locks) {
            if (player.getBlockPos().getSquaredDistance(pos) > maxDistSq) continue;
            world.spawnParticles(player, ParticleTypes.SOUL_FIRE_FLAME, false,
                    pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5,
                    1, 0.0, 0.05, 0.0, 0.0);
        }
    }

    // --- Bot reaction ---

    public static void maybeShowBotReaction(ServerPlayerEntity bot, BlockPos lockedPos) {
        if (bot == null || lockedPos == null) return;
        UUID id = bot.getUuid();
        Map<BlockPos, Long> cooldowns = BOT_REACTION_COOLDOWN.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(lockedPos);
        if (last != null && (now - last) < REACTION_COOLDOWN_MS) return;
        cooldowns.put(lockedPos.toImmutable(), now);

        String[] lines = {"that door is locked", "can't go through there", "that's locked"};
        String line = lines[new Random().nextInt(lines.length)];
        CompanionOverheadDialogueService.showOverheadLine(bot, line, 2_800, 32.0, "locked-block", "locked");
    }

    // --- Persistence ---

    public static void loadForWorld(MinecraftServer server, String worldId) {
        Path file = getLockFile(server, worldId);
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            List<int[]> positions = GSON.fromJson(json, new TypeToken<List<int[]>>(){}.getType());
            if (positions == null || positions.isEmpty()) return;
            Set<BlockPos> locks = ConcurrentHashMap.newKeySet();
            for (int[] coords : positions) {
                if (coords.length >= 3) {
                    locks.add(new BlockPos(coords[0], coords[1], coords[2]));
                }
            }
            LOCKED_BLOCKS.put(worldId, locks);
            LOGGER.info("Loaded {} locked blocks for world {}", locks.size(), worldId);
        } catch (IOException e) {
            LOGGER.error("Failed to load locked blocks for world {}", worldId, e);
        }
    }

    public static void saveForWorld(MinecraftServer server, String worldId) {
        Set<BlockPos> locks = LOCKED_BLOCKS.get(worldId);
        Path dir = getLockFile(server, worldId).getParent();
        try {
            Files.createDirectories(dir);
            if (locks == null || locks.isEmpty()) {
                Files.deleteIfExists(getLockFile(server, worldId));
                return;
            }
            List<int[]> positions = new ArrayList<>();
            for (BlockPos pos : locks) {
                positions.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
            }
            Files.writeString(getLockFile(server, worldId), GSON.toJson(positions));
        } catch (IOException e) {
            LOGGER.error("Failed to save locked blocks for world {}", worldId, e);
        }
    }

    public static void saveAllWorlds(MinecraftServer server) {
        if (server == null) return;
        server.getWorlds().forEach(world -> {
            String worldId = world.getRegistryKey().getValue().toString();
            saveForWorld(server, worldId);
        });
    }

    private static Path getLockFile(MinecraftServer server, String worldId) {
        return server.getRunDirectory()
                .resolve(ZONE_ROOT_DIR)
                .resolve(worldStorageKey(worldId))
                .resolve(LOCK_FILE_NAME);
    }

    private static String worldStorageKey(String worldId) {
        if (worldId == null || worldId.isBlank()) return "unknown_world__0";
        String sanitized = worldId
                .replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (sanitized.isBlank()) sanitized = "world";
        return sanitized + "__" + Integer.toHexString(worldId.hashCode());
    }
}
```

- [ ] **Step 2: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/LockableBlockService.java
git commit -m "feat: Add LockableBlockService — lock registry, persistence, feedback"
```

---

### Task 2: Create network payloads

**Files:**
- Create: `src/.../network/LockModeTogglePayload.java`
- Create: `src/.../network/LockModeStatePayload.java`

- [ ] **Step 1: Create LockModeTogglePayload (C2S)**

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> Server: toggle lock mode on/off. */
public record LockModeTogglePayload(boolean active) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "lock_mode_toggle");
    public static final CustomPayload.Id<LockModeTogglePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, LockModeTogglePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, LockModeTogglePayload payload) {
            buf.writeBoolean(payload.active);
        }
        @Override
        public LockModeTogglePayload decode(PacketByteBuf buf) {
            return new LockModeTogglePayload(buf.readBoolean());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
```

- [ ] **Step 2: Create LockModeStatePayload (S2C)**

```java
package net.wcfcarolina13.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Server -> Client: sync lock mode active state for UI rendering. */
public record LockModeStatePayload(boolean active) implements CustomPayload {
    public static final Identifier ID_IDENTIFIER = Identifier.of("frens", "lock_mode_state");
    public static final CustomPayload.Id<LockModeStatePayload> ID = new CustomPayload.Id<>(ID_IDENTIFIER);

    public static final PacketCodec<PacketByteBuf, LockModeStatePayload> CODEC = new PacketCodec<>() {
        @Override
        public void encode(PacketByteBuf buf, LockModeStatePayload payload) {
            buf.writeBoolean(payload.active);
        }
        @Override
        public LockModeStatePayload decode(PacketByteBuf buf) {
            return new LockModeStatePayload(buf.readBoolean());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/network/LockModeTogglePayload.java \
        src/main/java/net/wcfcarolina13/network/LockModeStatePayload.java
git commit -m "feat: Add LockModeTogglePayload (C2S) and LockModeStatePayload (S2C)"
```

---

### Task 3: Register payloads and wire server-side logic in Frens.java

**Files:**
- Modify: `src/.../Frens.java`

- [ ] **Step 1: Register payloads**

Find the payload registration block (near line 467 in `Frens.java`, where other `PayloadTypeRegistry` calls are). Add:

```java
PayloadTypeRegistry.playC2S().register(net.wcfcarolina13.network.LockModeTogglePayload.ID, net.wcfcarolina13.network.LockModeTogglePayload.CODEC);
PayloadTypeRegistry.playS2C().register(net.wcfcarolina13.network.LockModeStatePayload.ID, net.wcfcarolina13.network.LockModeStatePayload.CODEC);
```

- [ ] **Step 2: Register C2S receiver**

Find where other `ServerPlayNetworking.registerGlobalReceiver` calls are made (in `onInitialize` or a `registerReceiversOnce` method). Add:

```java
net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
        net.wcfcarolina13.network.LockModeTogglePayload.ID, (payload, context) ->
                context.server().execute(() -> {
                    ServerPlayerEntity player = context.player();
                    if (player == null || !isOperator(player.getCommandSource())) return;
                    boolean active = payload.active();
                    net.wcfcarolina13.GameAI.services.LockableBlockService.setLockMode(player.getUuid(), active);
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                            new net.wcfcarolina13.network.LockModeStatePayload(active));
                }));
```

- [ ] **Step 3: Add lock mode hook to UseBlockCallback**

In the existing `UseBlockCallback.EVENT.register(...)` handler in `Frens.java`, add this block **before** the zone wand check (so lock mode takes priority for admin players). Insert after the wizard tome check:

```java
            // Lock mode: right-click to toggle lock on doors/gates/trapdoors
            if (hand == net.minecraft.util.Hand.MAIN_HAND
                    && isOperator(serverPlayer.getCommandSource())
                    && net.wcfcarolina13.GameAI.services.LockableBlockService.isLockModeActive(serverPlayer.getUuid())) {
                var lockPos = hitResult.getBlockPos();
                var lockState = serverWorld.getBlockState(lockPos);
                if (net.wcfcarolina13.GameAI.services.LockableBlockService.isLockableBlock(lockState)) {
                    net.wcfcarolina13.GameAI.services.LockableBlockService.toggleLock(serverWorld, lockPos, serverPlayer);
                    return net.minecraft.util.ActionResult.SUCCESS;
                }
            }
```

- [ ] **Step 4: Add world load and server stopping hooks**

In the `SERVER_STARTED` handler, add after the `ProtectedZoneService.loadZones` call:

```java
        net.wcfcarolina13.GameAI.services.LockableBlockService.loadForWorld(server, worldId);
```

(This goes inside the existing `server.getWorlds().forEach(world -> { ... })` loop.)

In the `SERVER_STOPPING` handler, add before the bot cleanup loop:

```java
    net.wcfcarolina13.GameAI.services.LockableBlockService.saveAllWorlds(server);
```

- [ ] **Step 5: Add server tick for crosshair feedback and particles**

Find the existing `ServerTickEvents.END_SERVER_TICK` handler in `Frens.java`. Add a block that ticks lock mode feedback for admin players. If no suitable tick handler exists, add one:

```java
// Inside a server tick handler, every 10 ticks for crosshair, every 20 for particles:
for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
    if (player instanceof net.wcfcarolina13.Entity.createFakePlayer) continue;
    if (!net.wcfcarolina13.GameAI.services.LockableBlockService.isLockModeActive(player.getUuid())) continue;
    if (!(player.getEntityWorld() instanceof ServerWorld sw)) continue;
    if (server.getTicks() % 10 == 0) {
        net.wcfcarolina13.GameAI.services.LockableBlockService.tickCrosshairFeedback(player, sw);
    }
    if (server.getTicks() % 20 == 0) {
        net.wcfcarolina13.GameAI.services.LockableBlockService.tickParticles(player, sw);
    }
}
```

- [ ] **Step 6: Clear lock mode on disconnect**

Find the disconnect/leave handler (likely in `ServerPlayConnectionEvents.DISCONNECT` or similar). Add:

```java
net.wcfcarolina13.GameAI.services.LockableBlockService.clearLockMode(player.getUuid());
```

If no disconnect handler exists, this can be done in the `SERVER_STOPPING` handler by clearing all lock modes.

- [ ] **Step 7: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/net/wcfcarolina13/Frens.java
git commit -m "feat: Wire LockableBlockService — payloads, UseBlockCallback, lifecycle hooks"
```

---

### Task 4: Client-side lock mode state and BotControlScreen button

**Files:**
- Modify: `src/.../FrensClient.java`
- Modify: `src/.../GraphicalUserInterface/BotControlScreen.java`

- [ ] **Step 1: Register S2C receiver in FrensClient**

In `FrensClient.java`, find the block of `ClientPlayNetworking.registerGlobalReceiver` calls. Add:

```java
ClientPlayNetworking.registerGlobalReceiver(net.wcfcarolina13.network.LockModeStatePayload.ID, (payload, context) -> {
    net.wcfcarolina13.GraphicalUserInterface.BotControlScreen.setLockModeActive(payload.active());
});
```

- [ ] **Step 2: Add lock mode state and button to BotControlScreen**

Add static field for tracking lock mode state on the client:

```java
private static boolean lockModeActive = false;

public static void setLockModeActive(boolean active) {
    lockModeActive = active;
}
```

Add a `lockBlocksRect` field alongside the other footer Rects:

```java
private Rect lockBlocksRect;
```

In `init()`, compute the rect. After the `permissionsActionRect` initialization, add:

```java
lockBlocksRect = new Rect(permissionsActionRect.right() + footerGap, footerY, 100, BUTTON_H);
```

In the `render()` method, after the Permissions Editor button draw, add:

```java
drawActionButton(context, lockBlocksRect,
        lockModeActive ? "Lock Mode ON" : "Lock Blocks",
        lockModeActive,
        true,
        mouseX, mouseY);
```

In the `mouseClicked()` method, add a handler for the new button:

```java
if (lockBlocksRect != null && lockBlocksRect.contains(mx, my)) {
    lockModeActive = !lockModeActive;
    ClientPlayNetworking.send(new net.wcfcarolina13.network.LockModeTogglePayload(lockModeActive));
    return true;
}
```

- [ ] **Step 3: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/wcfcarolina13/FrensClient.java \
        src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotControlScreen.java
git commit -m "feat: Add Lock Blocks button to BotControlScreen with client state sync"
```

---

### Task 5: Bot behavior integration — enforcement points

**Files:**
- Modify: `src/.../GameAI/services/MovementService.java`
- Modify: `src/.../PathFinding/BaritoneStylePathFinder.java`
- Modify: `src/.../PathFinding/PathFinder.java`
- Modify: `src/.../GameAI/services/FollowPathService.java`

- [ ] **Step 1: Check isLocked() in MovementService.tryOpenDoorAt()**

In `MovementService.tryOpenDoorAt()`, after the `isOpenableBlock(state)` check and before the iron door check, add:

```java
        // Locked blocks cannot be opened by bots
        if (world instanceof ServerWorld sw
                && LockableBlockService.isLocked(sw, openablePos)) {
            LockableBlockService.maybeShowBotReaction(player, openablePos);
            return false;
        }
```

Add import at top of `MovementService.java`:
```java
import net.wcfcarolina13.GameAI.services.LockableBlockService;
```

- [ ] **Step 2: Check isLocked() in BaritoneStylePathFinder.isPassable()**

In `BaritoneStylePathFinder.isPassable(ChunkCache, int, int, int)`, inside the `DoorBlock` check where it returns `true` for wooden doors, add a lock check:

```java
        if (state.getBlock() instanceof DoorBlock) {
            if (state.isOf(Blocks.IRON_DOOR)) {
                return state.get(DoorBlock.OPEN);
            }
            // Check if this door is locked
            if (LockableBlockService.isLocked(cache.world, cache.mutablePos.set(x, y, z))) {
                return false;
            }
            return true;
        }
```

Same pattern for the `TrapdoorBlock` check added earlier — insert lock check before returning true:

```java
        if (state.getBlock() instanceof TrapdoorBlock) {
            if (state.isOf(Blocks.IRON_TRAPDOOR)) {
                return state.getCollisionShape(cache.world, cache.mutablePos.set(x, y, z)).isEmpty();
            }
            if (LockableBlockService.isLocked(cache.world, cache.mutablePos.set(x, y, z))) {
                return false;
            }
            return true;
        }
```

Do the same in `isPassableWorld()` for both DoorBlock and TrapdoorBlock.

Add import:
```java
import net.wcfcarolina13.GameAI.services.LockableBlockService;
```

- [ ] **Step 3: Check isLocked() in PathFinder.isPassable()**

Same pattern as Step 2, but in the classic `PathFinder.isPassable()`:

```java
        if (blockState.getBlock() instanceof DoorBlock) {
            if (blockState.isOf(Blocks.IRON_DOOR)) {
                return blockState.getCollisionShape(world, pos).isEmpty();
            }
            if (LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
        }
        if (blockState.getBlock() instanceof TrapdoorBlock) {
            if (blockState.isOf(Blocks.IRON_TRAPDOOR)) {
                return blockState.getCollisionShape(world, pos).isEmpty();
            }
            if (LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
        }
```

Add import:
```java
import net.wcfcarolina13.GameAI.services.LockableBlockService;
```

- [ ] **Step 4: Check isLocked() in FollowPathService.isPassableForPlan()**

In `FollowPathService.isPassableForPlan()`, inside the DoorBlock, FenceGateBlock, and TrapdoorBlock checks, add lock checks before returning true:

For DoorBlock (after the iron door check):
```java
            if (LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
```

For FenceGateBlock:
```java
        if (state.getBlock() instanceof FenceGateBlock) {
            if (LockableBlockService.isLocked(world, pos)) {
                return false;
            }
            return true;
        }
```

For TrapdoorBlock (after the iron trapdoor check):
```java
            if (LockableBlockService.isLocked(world, pos)) {
                return false;
            }
```
(Insert before the existing `return state.getCollisionShape(world, pos).isEmpty();` for the non-iron case — actually TrapdoorBlock already returns true for wooden. Insert the lock check before that return.)

Add import:
```java
import net.wcfcarolina13.GameAI.services.LockableBlockService;
```

- [ ] **Step 5: Build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java \
        src/main/java/net/wcfcarolina13/PathFinding/BaritoneStylePathFinder.java \
        src/main/java/net/wcfcarolina13/PathFinding/PathFinder.java \
        src/main/java/net/wcfcarolina13/GameAI/services/FollowPathService.java
git commit -m "feat: Enforce locked blocks in pathfinders, MovementService, and FollowPathService"
```

---

### Task 6: Changelog and final verification

**Files:**
- Modify: `changelog.md`

- [ ] **Step 1: Add changelog entry**

Prepend to `changelog.md`:

```markdown
## 2026-04-06 — Lockable Blocks

- **Lockable block system:** Players can now mark individual doors, fence gates, and trapdoors as "locked" so bots treat them as solid walls. Toggle lock mode via the "Lock Blocks" button in Bot Controls (admin only). While in lock mode: right-click a lockable block to toggle its lock state; crosshair shows lock status; blue soul flame particles highlight all locked blocks within 32 blocks.
- **Bot enforcement:** Locked blocks are treated as impassable by both pathfinders (`BaritoneStylePathFinder`, classic `PathFinder`), the follow bounded planner (`FollowPathService`), and the door-opening system (`MovementService.tryOpenDoorAt`). Bots show a brief overhead reaction line when first encountering a locked block.
- **Persistence:** Lock state persists across server restarts in `bot_zones/[world]/locked_blocks.json`. Global to all bots owned by the locking player.
```

- [ ] **Step 2: Build final verification**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit changelog**

```bash
git add changelog.md
git commit -m "docs: Add changelog entry for lockable blocks system"
```
