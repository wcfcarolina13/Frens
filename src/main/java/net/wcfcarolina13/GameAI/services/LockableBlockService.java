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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
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

        var hitResult = player.raycast(5.0, 0.0f, false);
        if (!(hitResult instanceof net.minecraft.util.hit.BlockHitResult blockHit)) return;
        if (blockHit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return;

        BlockPos targetPos = blockHit.getBlockPos();
        BlockState state = world.getBlockState(targetPos);
        if (!isLockableBlock(state)) return;

        boolean locked = isLocked(world, targetPos);
        String msg = locked
                ? "\u00A7c\u00A7lLocked\u00A77: Right-click to unlock"
                : "\u00A7a\u00A7lUnlocked\u00A77: Right-click to lock";
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
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME,
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
        // Replace, never merge: the map is static and outlives a world when the integrated
        // server switches saves inside one JVM.
        LOCKED_BLOCKS.remove(worldId);
        Path file = getLockFile(server, worldId);
        Path legacyFile = WorldZoneStore.legacyDir(server, worldId).resolve(LOCK_FILE_NAME);
        WorldStoreKeyPolicy.Adoption adoption = WorldZoneStore.decide(server, worldId, file, legacyFile);
        Path fileToRead;
        if (adoption == WorldStoreKeyPolicy.Adoption.USE_OWN) {
            fileToRead = file;
        } else if (adoption == WorldStoreKeyPolicy.Adoption.ADOPT_LEGACY) {
            fileToRead = legacyFile;
            LOGGER.info("Adopting legacy locked blocks for world {} from {} into this save ({})",
                    worldId, legacyFile, WorldZoneStore.fingerprint(server));
        } else {
            fileToRead = null;
            if (Files.exists(legacyFile)) {
                LOGGER.info("Locked blocks for world {} start empty in this save ({}): {}",
                        worldId, WorldZoneStore.fingerprint(server), adoption);
            }
        }
        try {
            Set<BlockPos> locks = ConcurrentHashMap.newKeySet();
            if (fileToRead != null) {
                String json = Files.readString(fileToRead);
                List<int[]> positions = GSON.fromJson(json, new TypeToken<List<int[]>>(){}.getType());
                if (positions != null) {
                    for (int[] coords : positions) {
                        if (coords != null && coords.length >= 3) {
                            locks.add(new BlockPos(coords[0], coords[1], coords[2]));
                        }
                    }
                }
            }
            if (!locks.isEmpty()) {
                LOCKED_BLOCKS.put(worldId, locks);
                LOGGER.info("Loaded {} locked blocks for world {}", locks.size(), worldId);
            }
            if (WorldStoreKeyPolicy.mustCreateOwnStore(adoption)) {
                // Adopted locks land in this save's store; a start-empty decision writes [] so it sticks.
                writeLocks(server, worldId, locks);
            }
            if (adoption == WorldStoreKeyPolicy.Adoption.ADOPT_LEGACY) {
                WorldZoneStore.writeClaim(WorldZoneStore.legacyDir(server, worldId), WorldZoneStore.fingerprint(server));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load locked blocks for world {}", worldId, e);
        }
    }

    public static void saveForWorld(MinecraftServer server, String worldId) {
        Set<BlockPos> locks = LOCKED_BLOCKS.get(worldId);
        try {
            // Empty is written as [] (never deleted) so this save keeps its own store and can't
            // later adopt another save's legacy locks.
            writeLocks(server, worldId, locks == null ? Set.of() : locks);
        } catch (IOException e) {
            LOGGER.error("Failed to save locked blocks for world {}", worldId, e);
        }
    }

    private static void writeLocks(MinecraftServer server, String worldId, Set<BlockPos> locks) throws IOException {
        Path file = getLockFile(server, worldId);
        Files.createDirectories(file.getParent());
        List<int[]> positions = new ArrayList<>();
        for (BlockPos pos : locks) {
            positions.add(new int[]{pos.getX(), pos.getY(), pos.getZ()});
        }
        Files.writeString(file, GSON.toJson(positions));
    }

    public static void saveAllWorlds(MinecraftServer server) {
        if (server == null) return;
        server.getWorlds().forEach(world -> {
            String worldId = world.getRegistryKey().getValue().toString();
            saveForWorld(server, worldId);
        });
    }

    /** This save's lock file for one dimension (see {@link WorldZoneStore}). */
    private static Path getLockFile(MinecraftServer server, String worldId) {
        return WorldZoneStore.ownDir(server, worldId).resolve(LOCK_FILE_NAME);
    }

    /** Drop every in-memory lock. SERVER_STOPPING, after {@link #saveAllWorlds}: the next world must not inherit them. */
    public static void clearAll() {
        LOCKED_BLOCKS.clear();
        BOT_REACTION_COOLDOWN.clear();
    }
}
