package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.wcfcarolina13.GameAI.services.SkillResumeService;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.item.Item;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.wcfcarolina13.GameAI.services.navigation.VoxelJunctionService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages flee behavior for IDLE-mode bots that are outnumbered or critically wounded.
 *
 * <p>Bots in FOLLOW, GUARD, PATROL, or STAY modes never flee (they have duties).
 * IDLE bots evaluate the threat and sprint away from the centroid of hostile positions
 * when the situation is unwinnable.</p>
 */
public final class BotFleeService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-flee");

    /** Maximum ticks a flee can last before force-stopping (10 seconds). */
    private static final int MAX_FLEE_TICKS = 200;
    /** Squared distance threshold to consider "safe" from all hostiles. */
    private static final double SAFE_DISTANCE_SQ = 20.0 * 20.0;
    /** Cooldown ticks after a flee ends before re-evaluating (5 seconds). */
    private static final int COOLDOWN_TICKS = 100;

    /** Type of tactical shelter the bot is occupying. */
    public enum ShelterType { CLIFF, DIG_DOWN, VILLAGE_HOUSE, JOIN_ENCLOSED, WALL_OFF }

    public enum ShelterInvalidReason {
        VALID,
        NO_STANDABLE_INTERIOR,
        OPEN_SKY,
        NO_OVERHEAD_COVER,
        ENTRANCE_UNSEALED,
        WATER_INTRUSION,
        HAZARD_EXPOSED,
        HOSTILE_EXPOSED,
        NO_VILLAGE_SIGNAL,
        NO_DOORWAY
    }

    /**
     * Metadata for an active tactical shelter. {@code placedBlocks} is the wall-off ledger: the
     * cells the bot placed, the only cells its dawn teardown may mine. Empty for every other type.
     */
    public record ShelterInfo(long enteredTick,
                              ShelterType type,
                              BlockPos capPos,
                              Direction entryDir,
                              BlockPos interiorPos,
                              long lastValidatedTick,
                              ShelterInvalidReason lastValidationReason,
                              List<BlockPos> placedBlocks) {
        public ShelterInfo {
            placedBlocks = placedBlocks == null ? List.of() : List.copyOf(placedBlocks);
        }

        public ShelterInfo(long enteredTick,
                           ShelterType type,
                           BlockPos capPos,
                           Direction entryDir,
                           BlockPos interiorPos,
                           long lastValidatedTick,
                           ShelterInvalidReason lastValidationReason) {
            this(enteredTick, type, capPos, entryDir, interiorPos, lastValidatedTick, lastValidationReason, List.of());
        }
    }

    public record ShelterAssessment(boolean valid,
                                    ShelterType type,
                                    BlockPos standPos,
                                    ShelterInvalidReason reason) {}

    private record VillageHouseCandidate(BlockPos doorPos,
                                         BlockPos interiorPos,
                                         Direction interiorDir,
                                         int score) {}

    private enum InterruptedSurvivalAction {
        PROACTIVE_SHELTER,
        BREAK_FREE,
        SURFACE_RECOVERY
    }

    private static final ConcurrentHashMap<UUID, FleeState> FLEE_STATES = new ConcurrentHashMap<>();
    /** Per-bot cooldown to prevent spamming proactive shelter attempts. */
    private static final ConcurrentHashMap<UUID, Long> SHELTER_COOLDOWN = new ConcurrentHashMap<>();
    /** Tracks bots currently inside a tactical shelter with metadata. */
    private static final ConcurrentHashMap<UUID, ShelterInfo> SHELTER_ACTIVE = new ConcurrentHashMap<>();
    /** Generation counter — incremented on death/respawn to invalidate stale shelter threads. */
    private static final ConcurrentHashMap<UUID, Long> SHELTER_GENERATION = new ConcurrentHashMap<>();
    /** Mutex to prevent duplicate shelter threads for the same bot. */
    private static final ConcurrentHashMap<UUID, AtomicBoolean> SHELTER_LOCK = new ConcurrentHashMap<>();
    /** Single-flight guard for general surface recovery so hunt/idle/break-free don't dogpile the same bot. */
    private static final ConcurrentHashMap<UUID, AtomicBoolean> SURFACE_RECOVERY_LOCK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> SURFACE_RECOVERY_FAILURE_TICK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> SURFACE_RECOVERY_FAILURE_REASON = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, InterruptedSurvivalAction> CURRENT_SURVIVAL_ACTION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, InterruptedSurvivalAction> PENDING_SURVIVAL_RESUME = new ConcurrentHashMap<>();

    // ── Underground linger system state ──────────────────────────────────
    /** Tick when the bot's underground linger timer started (hunger-driven surface decision). */
    private static final ConcurrentHashMap<UUID, Long> UNDERGROUND_LINGER_START = new ConcurrentHashMap<>();
    /** Tick when the bot last exited a shelter (suppresses immediate re-trigger of surface recovery). */
    private static final ConcurrentHashMap<UUID, Long> RECENT_SHELTER_EXIT_TICK = new ConcurrentHashMap<>();
    /** Tick when the commander last left proximity (grace period before hunger checks activate). */
    private static final ConcurrentHashMap<UUID, Long> COMMANDER_LEFT_TICK = new ConcurrentHashMap<>();
    /** Whether the commander was nearby on the previous check (for edge detection). */
    private static final ConcurrentHashMap<UUID, Boolean> COMMANDER_WAS_NEARBY = new ConcurrentHashMap<>();
    /** Grace period after shelter exit before surface recovery can trigger (60 seconds). */
    private static final long SHELTER_EXIT_GRACE_TICKS = 1200L;
    /** Tick when a commander-issued task last completed (post-task grace period). */
    private static final ConcurrentHashMap<UUID, Long> TASK_COMPLETED_TICK = new ConcurrentHashMap<>();
    /** Grace period after commander task completion before surface recovery triggers (10 minutes). */
    private static final long TASK_GRACE_TICKS = 20L * 60L * 10L;

    /** Solid blocks suitable for sealing shelter entrances (mined from stone/dirt). */
    private static final List<Item> SEAL_BLOCKS = List.of(
            Items.COBBLESTONE, Items.STONE, Items.DIRT, Items.COBBLED_DEEPSLATE,
            Items.DEEPSLATE, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
            Items.SANDSTONE, Items.RED_SANDSTONE, Items.NETHERRACK,
            Items.TUFF, Items.CALCITE, Items.BASALT, Items.GRAVEL, Items.SAND,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
            Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
            Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS);

    /** Registry ids of {@link #SEAL_BLOCKS}, for {@link WallOffPolicy}'s pure counting and teardown rules. */
    private static final Set<String> SEAL_IDS = SEAL_BLOCKS.stream()
            .map(item -> Registries.ITEM.getId(item).toString())
            .collect(Collectors.toUnmodifiableSet());

    /** {@link #SEAL_BLOCKS} as ids in today's order, and back to items, for {@link SealOrderPolicy}. */
    private static final List<String> SEAL_ID_ORDER = SEAL_BLOCKS.stream()
            .map(item -> Registries.ITEM.getId(item).toString())
            .toList();
    private static final Map<String, Item> SEAL_ITEM_BY_ID = SEAL_BLOCKS.stream()
            .collect(Collectors.toUnmodifiableMap(item -> Registries.ITEM.getId(item).toString(), item -> item));

    /**
     * The seal list every shelter seal and the dig-down cap place from, ordered by
     * {@link SealOrderPolicy} for the tools the bot holds right now. The tool flags are read in one
     * server hop; on a hop timeout this falls back to today's {@link #SEAL_BLOCKS} order, never an
     * empty list (an empty list lets {@code tryPlaceBlockAt} place any block item).
     */
    private static List<Item> sealBlocksFor(ServerPlayerEntity bot) {
        if (bot == null) {
            return SEAL_BLOCKS;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        boolean[] tools = callOnServer(server, () -> new boolean[]{hasAnyPickaxe(bot), hasAnyAxe(bot)},
                WALL_OFF_HOP_TIMEOUT_MS, null);
        if (tools == null) {
            return SEAL_BLOCKS;
        }
        List<Item> ordered = new ArrayList<>(SEAL_BLOCKS.size());
        for (String id : SealOrderPolicy.order(SEAL_ID_ORDER, tools[0], tools[1])) {
            Item item = SEAL_ITEM_BY_ID.get(id);
            if (item != null) {
                ordered.add(item);
            }
        }
        return ordered.isEmpty() ? SEAL_BLOCKS : List.copyOf(ordered);
    }

    /** Seal ids that are falling blocks (sand, gravel): never placed with air under them. */
    private static final Set<String> FALLING_SEAL_IDS = SEAL_BLOCKS.stream()
            .filter(item -> item instanceof net.minecraft.item.BlockItem bi
                    && bi.getBlock() instanceof net.minecraft.block.FallingBlock)
            .map(item -> Registries.ITEM.getId(item).toString())
            .collect(Collectors.toUnmodifiableSet());
    /** Seal ids the dig-down cap may use. */
    private static final Set<String> CAP_IDS = SEAL_IDS.stream()
            .filter(id -> !FALLING_SEAL_IDS.contains(id))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * {@link #sealBlocksFor} without falling blocks, for the dig-down cap: it sits over the bot's
     * air head cell, so sand or gravel there would fall onto the bot. Never empty (falls back to
     * today's gravity-free order).
     */
    private static List<Item> capBlocksFor(ServerPlayerEntity bot) {
        List<String> ids = new ArrayList<>();
        for (Item item : sealBlocksFor(bot)) {
            ids.add(Registries.ITEM.getId(item).toString());
        }
        List<Item> out = new ArrayList<>();
        for (String id : SealOrderPolicy.withoutFalling(ids, FALLING_SEAL_IDS)) {
            Item item = SEAL_ITEM_BY_ID.get(id);
            if (item != null) {
                out.add(item);
            }
        }
        if (out.isEmpty()) {
            for (String id : SealOrderPolicy.withoutFalling(SEAL_ID_ORDER, FALLING_SEAL_IDS)) {
                out.add(SEAL_ITEM_BY_ID.get(id));
            }
        }
        return List.copyOf(out);
    }

    /** Returns true if the bot is currently inside a tactical shelter. */
    public static boolean isInShelter(UUID botId) {
        return SHELTER_ACTIVE.containsKey(botId);
    }

    /** Clears shelter state so the bot can fight or act normally. */
    public static void clearShelter(UUID botId) {
        SHELTER_ACTIVE.remove(botId);
    }

    /**
     * Scans horizontally from center for the nearest position where sky is visible.
     * Useful for underground bots finding natural exits instead of pillaring up.
     * @return nearest sky-visible BlockPos, or null if none within radius
     */
    public static BlockPos findNearestSkylight(ServerWorld world, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue; // circular scan
                BlockPos check = center.add(dx, 0, dz);
                if (!world.isSkyVisible(check.up())) continue;
                // Reject underground skylights: only accept if the scan Y is near
                // the heightmap surface (within 3 blocks). A skylight 5+ blocks below
                // the surface is just a cave ceiling hole, not a real exit.
                int surfaceY = world.getTopY(
                        net.minecraft.world.Heightmap.Type.MOTION_BLOCKING,
                        check.getX(), check.getZ());
                if (check.getY() < surfaceY - 3) continue;
                double distSq = center.getSquaredDistance(check);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = check.toImmutable();
                }
            }
        }
        return best;
    }

    /**
     * Mark a bot as sheltered on join only if the current enclosure validates as survivable.
     */
    public static boolean setShelterFromJoin(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        long tick = bot.getCommandSource().getServer() != null
                ? bot.getCommandSource().getServer().getTicks() : 0;
        ShelterAssessment assessment = assessShelterAt(bot, world, bot.getBlockPos(), ShelterType.JOIN_ENCLOSED, null, null);
        if (!assessment.valid()) {
            LOGGER.info("Bot {} join shelter rejected reason={} at={}",
                    bot.getName().getString(),
                    assessment.reason().name().toLowerCase(),
                    bot.getBlockPos().toShortString());
            return false;
        }
        SHELTER_ACTIVE.put(bot.getUuid(), new ShelterInfo(
                tick,
                ShelterType.JOIN_ENCLOSED,
                bot.getBlockPos().up(2).toImmutable(),
                null,
                assessment.standPos(),
                tick,
                ShelterInvalidReason.VALID));
        LOGGER.info("Bot {} marked as sheltered on join (interior={})",
                bot.getName().getString(), assessment.standPos().toShortString());
        return true;
    }

    // ── Shelter persistence across a reload ─────────────────────────────

    /**
     * Marks the bot sheltered and, for the saved types ({@link ShelterRestorePolicy#PERSISTED_TYPES}),
     * writes the record so a reload restores it. Called at each successful registration only, never
     * by the per-tick revalidation.
     */
    private static void registerShelter(ServerPlayerEntity bot, ShelterInfo info) {
        SHELTER_ACTIVE.put(bot.getUuid(), info);
        if (!ShelterRestorePolicy.persisted(info.type().name())) {
            return;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null || info.interiorPos() == null) {
            return;
        }
        int[][] placed = new int[info.placedBlocks().size()][];
        for (int i = 0; i < placed.length; i++) {
            placed[i] = xyz(info.placedBlocks().get(i));
        }
        ShelterPersistenceStore.save(
                bot.getName().getString().toLowerCase(),
                BotWorldStateService.currentWorldKey(server),
                new ShelterPersistenceStore.ShelterRecord(
                        bot.getEntityWorld().getRegistryKey().getValue().toString(),
                        info.type().name(),
                        info.capPos() != null ? xyz(info.capPos()) : null,
                        info.entryDir() != null ? info.entryDir().name() : null,
                        xyz(info.interiorPos()),
                        placed));
        LOGGER.info("Bot {} shelter saved type={} interior={}",
                bot.getName().getString(), info.type().name().toLowerCase(), info.interiorPos().toShortString());
    }

    /**
     * Deletes the saved shelter: break-free started, or the shelter was invalidated. Not called on
     * disconnect or reset, so a reload keeps it.
     */
    private static void forgetPersistedShelter(ServerPlayerEntity bot, String reason) {
        MinecraftServer server = bot != null ? bot.getCommandSource().getServer() : null;
        if (server == null) {
            return;
        }
        if (ShelterPersistenceStore.forget(bot.getName().getString().toLowerCase(),
                BotWorldStateService.currentWorldKey(server))) {
            LOGGER.info("Bot {} saved shelter deleted ({})", bot.getName().getString(), reason);
        }
    }

    /**
     * Server thread, from the join check: restore the bot's saved shelter when it is for this
     * dimension and the exact cell the bot stands in and still validates. The record is consumed
     * whatever the outcome (re-saved when restored). If it is already day the shelter is restored
     * anyway and {@link #validateAndTickShelter} runs its type-correct exit.
     *
     * @return true when the shelter was restored (the join check stops there)
     */
    public static boolean restoreShelterFromJoin(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }
        String name = bot.getName().getString();
        ShelterPersistenceStore.ShelterRecord record = ShelterPersistenceStore.take(
                name.toLowerCase(), BotWorldStateService.currentWorldKey(server));
        BlockPos pos = bot.getBlockPos().toImmutable();
        String dimension = world.getRegistryKey().getValue().toString();
        ShelterRestorePolicy.Verdict verdict = ShelterRestorePolicy.precheck(
                record != null,
                record != null ? record.type() : null,
                record != null ? record.dimension() : null,
                dimension,
                record != null ? record.interior() : null,
                pos.getX(), pos.getY(), pos.getZ());
        if (verdict == ShelterRestorePolicy.Verdict.NO_RECORD) {
            return false;
        }
        if (verdict != null) {
            LOGGER.info("Bot {} saved shelter not restored reason={} saved={} at={}",
                    name, verdict.name().toLowerCase(), record.type(), pos.toShortString());
            return false;
        }
        ShelterType type = ShelterType.valueOf(record.type());
        BlockPos cap = toBlockPos(record.cap());
        Direction entryDir = horizontalByName(record.entryDir());
        ShelterAssessment assessment = assessShelterAt(bot, world, pos, type, cap, entryDir);
        String reason = assessment.reason().name().toLowerCase();
        boolean wallOff = type == ShelterType.WALL_OFF;
        boolean secured = wallOff && isShelterEntranceSecured(world, assessment.standPos(), type, cap, entryDir);
        verdict = ShelterRestorePolicy.afterAssess(assessment.valid(), wallOff, reason, secured);
        if (verdict != ShelterRestorePolicy.Verdict.RESTORE) {
            LOGGER.info("Bot {} saved shelter not restored reason={} type={} at={}",
                    name, reason, type.name().toLowerCase(), pos.toShortString());
            return false;
        }
        List<BlockPos> placed = new ArrayList<>();
        if (record.placedBlocks() != null) {
            for (int[] cell : record.placedBlocks()) {
                BlockPos p = toBlockPos(cell);
                if (p != null) {
                    placed.add(p);
                }
            }
        }
        long tick = server.getTicks();
        registerShelter(bot, new ShelterInfo(tick, type, cap, entryDir, assessment.standPos(), tick,
                ShelterInvalidReason.VALID, placed));
        LOGGER.info("Bot {} shelter restored on join type={} interior={} blocks={} (day={})",
                name, type.name().toLowerCase(), assessment.standPos().toShortString(), placed.size(),
                world.isDay());
        return true;
    }

    private static int[] xyz(BlockPos pos) {
        return new int[]{pos.getX(), pos.getY(), pos.getZ()};
    }

    private static BlockPos toBlockPos(int[] xyz) {
        return xyz != null && xyz.length >= 3 ? new BlockPos(xyz[0], xyz[1], xyz[2]) : null;
    }

    private static Direction horizontalByName(String name) {
        if (name == null) {
            return null;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (dir.name().equals(name)) {
                return dir;
            }
        }
        return null;
    }

    /**
     * Validates shelter state: auto-clears when undead burn (tod ~23460+).
     * Normal exit is via {@link #checkDaylightBreakFree} (waits until tod 1000).
     * This is the safety net — if daylight-break-free hasn't fired yet but
     * undead are burning, there's no reason to stay sheltered.
     * Abnormal exits via {@link #clearShelter} from teleport, environmental
     * damage, or mode change.
     */
    public static boolean validateAndTickShelter(ServerPlayerEntity bot, MinecraftServer server) {
        ShelterInfo info = SHELTER_ACTIVE.get(bot.getUuid());
        if (info == null) return false;
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            ShelterAssessment assessment = assessShelterAt(
                    bot,
                    world,
                    info.interiorPos() != null ? info.interiorPos() : bot.getBlockPos(),
                    info.type(),
                    info.capPos(),
                    info.entryDir());
            boolean keptSealed = !assessment.valid()
                    && info.type() == ShelterType.WALL_OFF
                    && WallOffPolicy.keepOnRevalidate(
                            assessment.reason().name().toLowerCase(),
                            isShelterEntranceSecured(world, assessment.standPos(), ShelterType.WALL_OFF,
                                    info.capPos(), info.entryDir()));
            if (!assessment.valid() && !keptSealed) {
                SHELTER_ACTIVE.remove(bot.getUuid());
                forgetPersistedShelter(bot, "invalidated " + assessment.reason().name().toLowerCase());
                LOGGER.info("Bot {} shelter invalidated reason={} type={} pos={}",
                        bot.getName().getString(),
                        assessment.reason().name().toLowerCase(),
                        info.type().name().toLowerCase(),
                        bot.getBlockPos().toShortString());
                return false;
            }
            long nowTick = server != null ? server.getTicks() : 0L;
            SHELTER_ACTIVE.put(bot.getUuid(), new ShelterInfo(
                    info.enteredTick(),
                    info.type(),
                    info.capPos(),
                    info.entryDir(),
                    assessment.standPos(),
                    nowTick,
                    ShelterInvalidReason.VALID,
                    info.placedBlocks()));
            if (shouldBreakShelterForStarvation(bot, world)) {
                SHELTER_ACTIVE.remove(bot.getUuid());
                LOGGER.info("Bot {} breaking tactical shelter for urgent food recovery (food={})",
                        bot.getName().getString(),
                        bot.getHungerManager().getFoodLevel());
                Thread t = new Thread(() -> breakFreeFromShelter(bot, info),
                        "shelter-breakfree-" + bot.getName().getString());
                t.setDaemon(true);
                t.start();
                return false;
            }
            long tod = world.getTimeOfDay() % 24000L;
            // Undead burn at ~23460. Clear shelter once it's safe outside.
            // Range: 23460 (sunrise burn) through 12000 (noon) — full daytime.
            // But NOT if thundering (undead don't burn in storms).
            if (!world.isThundering() && (tod >= 23460 || tod < 12000)) {
                SHELTER_ACTIVE.remove(bot.getUuid());
                LOGGER.info("Bot {} shelter cleared — safe to emerge (tod={})",
                        bot.getName().getString(), tod);
                // Launch break-free mining — bot may be physically enclosed
                final ShelterInfo shelterInfo = info;
                Thread t = new Thread(() -> breakFreeFromShelter(bot, shelterInfo),
                        "shelter-breakfree-" + bot.getName().getString());
                t.setDaemon(true);
                t.start();
                return false;
            }
        }
        return true;
    }

    public static BlockPos getValidatedShelterStandPos(ServerPlayerEntity bot) {
        if (bot == null || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        ShelterInfo info = SHELTER_ACTIVE.get(bot.getUuid());
        if (info == null) {
            return null;
        }
        ShelterAssessment assessment = assessShelterAt(
                bot,
                world,
                info.interiorPos() != null ? info.interiorPos() : bot.getBlockPos(),
                info.type(),
                info.capPos(),
                info.entryDir());
        return assessment.valid() ? assessment.standPos() : null;
    }

    public static BlockPos findNearbyVillageHouseAnchor(ServerPlayerEntity bot, ServerWorld world, int radius) {
        return findNearbyVillageHouseAnchor(bot, world, bot != null ? bot.getBlockPos() : null, radius);
    }

    public static BlockPos findNearbyVillageHouseAnchor(ServerPlayerEntity bot, ServerWorld world, BlockPos center, int radius) {
        VillageHouseCandidate candidate = findNearbyVillageHouseCandidate(bot, world, center, radius);
        return candidate != null ? candidate.interiorPos() : null;
    }

    public static boolean hadRecentSurfaceRecoveryFailure(UUID botId, long nowTick, long maxAgeTicks) {
        if (botId == null) {
            return false;
        }
        Long failedAt = SURFACE_RECOVERY_FAILURE_TICK.get(botId);
        return failedAt != null && failedAt > 0 && (nowTick - failedAt) <= Math.max(1L, maxAgeTicks);
    }

    /** Staleness window for the remembered surface-recovery failure reason: 1200 ticks (60s). */
    static final long SURFACE_RECOVERY_REASON_FRESH_TICKS = 1200L;

    /**
     * Reason for the most recent surface-recovery failure, but only while it is still fresh
     * (within {@link #SURFACE_RECOVERY_REASON_FRESH_TICKS} of now) — otherwise the
     * {@code [guard-escape]} log would name a failure from minutes ago.
     *
     * <p>Never returns {@code null}: a null, missing, or stale {@code botId}/reason yields
     * {@code ""}.
     */
    public static String getRecentSurfaceRecoveryFailureReason(UUID botId) {
        if (botId == null) {
            return "";
        }
        Long failedAt = SURFACE_RECOVERY_FAILURE_TICK.get(botId);
        if (failedAt == null) {
            return "";
        }
        long nowTick = net.wcfcarolina13.Frens.serverInstance != null
                ? net.wcfcarolina13.Frens.serverInstance.getTicks() : 0L;
        if (!net.wcfcarolina13.PlayerUtils.TickFreshness.isFresh(failedAt, nowTick, SURFACE_RECOVERY_REASON_FRESH_TICKS)) {
            return "";
        }
        String reason = SURFACE_RECOVERY_FAILURE_REASON.get(botId);
        return reason != null ? reason : "";
    }

    public static boolean hasPendingInterruptedSurvival(UUID botId) {
        return botId != null && PENDING_SURVIVAL_RESUME.containsKey(botId);
    }

    public static void clearInterruptedSurvival(UUID botId) {
        if (botId == null) {
            return;
        }
        PENDING_SURVIVAL_RESUME.remove(botId);
        CURRENT_SURVIVAL_ACTION.remove(botId);
    }

    public static boolean isSurfaceRecoveryActive(UUID botId) {
        if (botId == null) {
            return false;
        }
        if (CURRENT_SURVIVAL_ACTION.get(botId) == InterruptedSurvivalAction.SURFACE_RECOVERY) {
            return true;
        }
        AtomicBoolean lock = SURFACE_RECOVERY_LOCK.get(botId);
        return lock != null && lock.get();
    }

    public static boolean captureInterruptedSurvival(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        InterruptedSurvivalAction action = CURRENT_SURVIVAL_ACTION.get(bot.getUuid());
        if (action == null) {
            return false;
        }
        PENDING_SURVIVAL_RESUME.put(bot.getUuid(), action);
        LOGGER.info("Bot {} queued interrupted survival resume ({})",
                bot.getName().getString(),
                action.name().toLowerCase());
        return true;
    }

    public static boolean tryResumeInterruptedSurvival(ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null || bot.isRemoved() || !bot.isAlive()) {
            return false;
        }
        UUID botId = bot.getUuid();
        InterruptedSurvivalAction action = PENDING_SURVIVAL_RESUME.get(botId);
        if (action == null) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        if (TaskService.hasActiveTask(botId)) {
            return false;
        }
        boolean resolved = switch (action) {
            case PROACTIVE_SHELTER -> isInShelter(botId);
            case BREAK_FREE -> !isInShelter(botId) && isAtSurface(bot, world);
            case SURFACE_RECOVERY -> isAtSurface(bot, world);
        };
        if (resolved) {
            PENDING_SURVIVAL_RESUME.remove(botId);
            return false;
        }

        boolean resumed = switch (action) {
            case PROACTIVE_SHELTER -> tryProactiveShelter(bot, server);
            case BREAK_FREE -> {
                Thread thread = isInShelter(botId) ? clearShelterAndBreakFree(bot) : forceBreakFree(bot);
                yield thread != null;
            }
            // Callers are tick code; ensureAtSurface blocks (sleeps, landing waits), so it runs on
            // its own thread like BREAK_FREE. A recovery already running counts as resumed.
            case SURFACE_RECOVERY -> {
                if (!isSurfaceRecoveryActive(botId)) {
                    Thread thread = new Thread(() -> ensureAtSurface(bot, world),
                            "surface-recovery-resume-" + bot.getName().getString());
                    thread.setDaemon(true);
                    thread.start();
                }
                yield true;
            }
        };
        if (resumed) {
            PENDING_SURVIVAL_RESUME.remove(botId);
            LOGGER.info("Bot {} resumed interrupted survival workflow ({})",
                    bot.getName().getString(),
                    action.name().toLowerCase());
        }
        return resumed;
    }

    private BotFleeService() {}

    private static final class FleeState {
        boolean isFleeing;
        long fleeStartTick;
        Vec3d fleeDirection;
        long fleeCooldownUntilTick;
        boolean emergencyTacticAttempted;
        /** Position when flee started — used to detect if flee is actually working. */
        Vec3d fleeStartPos;
        /** Set true when {@link #fleeFromEntity} seeds the state from a damage hook
         *  (named-hostile pacifism). Causes {@link #tickFlee} to bypass the
         *  IDLE-only mode guard so the bot keeps fleeing even if it was in
         *  FOLLOW / GUARD / PATROL etc. when the hit landed. Cleared on
         *  {@link #stopFleeing}. */
        boolean forcedByDamage;
    }

    /**
     * Evaluates whether the bot should flee and, if already fleeing, continues the flee movement.
     * Returns true if the bot is actively fleeing (caller should skip normal combat/idle).
     *
     * <p>Only IDLE-mode bots can flee. All other modes return false immediately.</p>
     */
    public static boolean tickFlee(ServerPlayerEntity bot, MinecraftServer server,
                                   List<Entity> hostiles, BotEventHandler.Mode mode) {
        if (bot == null || server == null) return false;
        // Damage-seeded flees from the named-hostile pacifism path bypass the
        // IDLE-only mode guard — otherwise a bot in FOLLOW / GUARD / PATROL would
        // never continue fleeing after the initial damage impulse.
        FleeState modeBypass = FLEE_STATES.get(bot.getUuid());
        boolean damageOverride = modeBypass != null && modeBypass.forcedByDamage && modeBypass.isFleeing;
        if (mode != BotEventHandler.Mode.IDLE && !damageOverride) return false;
        // Bot is in a tactical shelter — don't flee unless shelter is compromised
        if (isInShelter(bot.getUuid())) {
            if (BotCombatCalloutService.wasRecentlyDamagedByHostile(bot, server.getTicks(), 40)) {
                clearShelter(bot.getUuid());
                LOGGER.info("Bot {} shelter compromised (taking damage) — clearing for flee",
                        bot.getName().getString());
            } else {
                return false;
            }
        }
        if (hostiles == null || hostiles.isEmpty()) {
            // No threats — stop fleeing if we were
            FleeState state = FLEE_STATES.get(bot.getUuid());
            if (state != null && state.isFleeing) {
                stopFleeing(state, server.getTicks());
            }
            return false;
        }

        long currentTick = server.getTicks();
        FleeState state = FLEE_STATES.computeIfAbsent(bot.getUuid(), id -> new FleeState());

        // Already fleeing — continue or stop
        if (state.isFleeing) {
            return continueFlee(bot, state, hostiles, currentTick);
        }

        // Cooldown check
        if (currentTick < state.fleeCooldownUntilTick) {
            return false;
        }

        // Threat assessment
        if (shouldFlee(bot, hostiles)) {
            startFleeing(bot, state, hostiles, currentTick);
            return state.isFleeing; // false if all flee paths blocked
        }

        return false;
    }

    /**
     * Proactive shelter check for IDLE bots during a lull after heavy combat.
     * Called from the IDLE tick path when no hostiles are nearby but the bot
     * was recently in combat and is hurt. Attempts to build shelter preemptively
     * before the next wave arrives.
     *
     * <p>Trigger conditions (all must be true):
     * <ul>
     *   <li>Emergency tactics enabled</li>
     *   <li>Any missing health, OR damaged by hostile within last 10s</li>
     *   <li>Not near a base (no baseTarget set)</li>
     *   <li>Has placeable blocks</li>
     *   <li>Not on shelter cooldown (60s between attempts)</li>
     * </ul>
     *
     * @return true if the bot is building shelter (caller should skip idle behaviors)
     */
    public static boolean tryProactiveShelter(ServerPlayerEntity bot, net.minecraft.server.MinecraftServer server) {
        if (bot == null || server == null) return false;
        if (!BotHomeService.isTacticalShelterEnabled(bot)) return false;
        if (BotAutoReturnSunsetService.isNightTravelSessionActive(bot.getUuid())
                && !BotAutoReturnSunsetService.isTacticalShelterSession(bot.getUuid())) {
            return false;
        }
        // Already sheltered — don't dig another hole
        if (isInShelter(bot.getUuid())) return true;
        // Mutex: only one shelter thread per bot at a time
        AtomicBoolean lock = SHELTER_LOCK.computeIfAbsent(bot.getUuid(), k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) return false;
        if (!SkillPreferences.emergencyTactics(bot)) { lock.set(false); return false; }

        long currentTick = server.getTicks();

        // Don't build shelter during daytime — undead burn, no point entombing
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            if (world.isDay() && !world.isThundering()) {
                lock.set(false);
                return false;
            }
            // Underground bots are already safe from nighttime mobs — no need to dig shelter
            if (!world.isSkyVisible(bot.getBlockPos().up())) {
                lock.set(false);
                return false;
            }
        }

        // Skip shelter on peaceful difficulty — no hostile mobs spawn
        if (bot.getEntityWorld() instanceof ServerWorld world
                && world.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) { lock.set(false); return false; }

        // Must not be near a base (has somewhere safe to go)
        BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
        if (state != null && state.baseTarget != null) { lock.set(false); return false; }

        // Suppress shelter if commander is nearby — player is present and doesn't want the bot to vanish
        if (isCommanderNearby(bot, server)) {
            LOGGER.debug("Bot {} skipping shelter — commander nearby", bot.getName().getString());
            lock.set(false);
            return false;
        }

        // Skip cooldown when phantoms are the only threat — bot needs immediate cover
        boolean phantomBypass = false;
        if (bot.getEntityWorld() instanceof ServerWorld shelterWorld) {
            List<? extends net.minecraft.entity.mob.MobEntity> nearbyMobs =
                    shelterWorld.getEntitiesByClass(
                            net.minecraft.entity.mob.MobEntity.class,
                            bot.getBoundingBox().expand(24),
                            e -> e.isAlive() && !e.isRemoved()
                                    && e.getTarget() != null && e.getTarget().equals(bot));
            phantomBypass = !nearbyMobs.isEmpty()
                    && nearbyMobs.stream().allMatch(e -> e.getType() == EntityType.PHANTOM);
        }

        // Cooldown: don't spam shelter attempts (30 second cooldown, bypassed for phantom-only threats)
        if (!phantomBypass) {
            long lastAttempt = SHELTER_COOLDOWN.getOrDefault(bot.getUuid(), 0L);
            if (currentTick - lastAttempt < 600) { lock.set(false); return false; }
        }

        SHELTER_COOLDOWN.put(bot.getUuid(), currentTick);

        LOGGER.info("Bot {} proactively seeking shelter (hp={}/{}, nighttime)",
                bot.getName().getString(),
                String.format("%.1f", bot.getHealth()),
                String.format("%.1f", bot.getMaxHealth()));

        // Quick stabilize (1-2 bites) then shelter — don't sit eating in the open
        final long gen = getGeneration(bot.getUuid());
        Thread t = new Thread(() -> {
            boolean owner = beginSurvivalAction(bot.getUuid(), InterruptedSurvivalAction.PROACTIVE_SHELTER);
            try {
                if (isStaleShelter(bot, gen)) return;
                if (bot.getHealth() < bot.getMaxHealth() * 0.5f) {
                    int eaten = HealingService.stabilizeEat(bot, 2);
                    LOGGER.info("Bot {} stabilized with {} bites before shelter (hp={})",
                            bot.getName().getString(), eaten,
                            String.format("%.1f", bot.getHealth()));
                }
                if (isStaleShelter(bot, gen)) return;
                runEmergencyTacticChain(bot, gen);
            } finally {
                endSurvivalAction(bot.getUuid(), InterruptedSurvivalAction.PROACTIVE_SHELTER, owner);
                lock.set(false);
            }
        }, "proactive-shelter-" + bot.getName().getString());
        t.setDaemon(true);
        t.start();
        return true;
    }

    private static boolean shouldFlee(ServerPlayerEntity bot, List<Entity> hostiles) {
        int hostileCount = hostiles.size();
        float healthRatio = bot.getHealth() / bot.getMaxHealth();

        // Critical health — flee regardless
        if (healthRatio <= 0.30f) return true;

        // Phantom-only threat: unarmed bot with no shield can't fight phantoms — flee to cover
        boolean allPhantoms = hostiles.stream().allMatch(e -> e.getType() == EntityType.PHANTOM);
        boolean hasShield = bot.getOffHandStack().isOf(Items.SHIELD) || bot.getMainHandStack().isOf(Items.SHIELD);
        if (allPhantoms && !BotActions.hasRangedWeapon(bot) && !hasShield) {
            return true;
        }

        // Equipment-based flee threshold: better gear = stand your ground longer.
        // With sweep attacks the bot can handle groups, so well-armed bots stay.
        int fleeThreshold = computeFleeThreshold(bot);

        // Low health reduces the threshold — a hurt bot should flee sooner.
        // At 50% health, threshold drops by 1; at 35% it drops by 2.
        if (healthRatio <= 0.35f) {
            fleeThreshold = Math.max(2, fleeThreshold - 2);
        } else if (healthRatio <= 0.50f) {
            fleeThreshold = Math.max(2, fleeThreshold - 1);
        }

        if (hostileCount >= fleeThreshold) return true;

        return false;
    }

    /**
     * Computes the hostile count at which the bot should flee, based on equipment.
     * Well-equipped bots (weapon + armor + shield) can handle larger groups via
     * sweep attacks and shielding; unarmed/unarmored bots flee much sooner.
     */
    private static int computeFleeThreshold(ServerPlayerEntity bot) {
        boolean hasMelee = BotActions.hasMeleeWeapon(bot);
        boolean hasRanged = BotActions.hasRangedWeapon(bot);
        boolean hasWeapon = hasMelee || hasRanged;
        int armorPoints = bot.getArmor(); // 0-20; iron full ~15, diamond full ~20

        if (!hasWeapon && armorPoints < 5) {
            return 2;  // unarmed and unarmored — flee from 2+
        }
        if (!hasWeapon) {
            return 3;  // unarmored but has some armor — flee from 3+
        }
        if (armorPoints >= 10) {
            return 6;  // well-armed (weapon + iron+ armor) — sweep attacks handle groups
        }
        if (armorPoints >= 5) {
            return 5;  // armed with light armor
        }
        return 4;      // armed but barely any armor
    }

    /**
     * Named-mob pacifism hook: seed flee state with a specific attacker so the bot
     * runs instead of fighting back. Called from the server damage listener when a
     * name-tagged mob hits the bot and {@link BotHomeService#isAttackNamedMobs}
     * is off.
     *
     * <p>Returns the seeded {@link #FLEE_STATES} state for the bot so callers can
     * inspect whether a flee direction was found. If the bot is already fleeing,
     * this is a no-op — {@code tickFlee} will continue the existing flee. If the
     * bot is cornered (no traversable direction), {@code startFleeing} bails
     * without setting {@code isFleeing}, and the caller's path should fall through
     * to normal combat handling.
     *
     * <p><b>Mode caveat:</b> {@code tickFlee} currently early-returns when the bot
     * is not in {@code Mode.IDLE}. If the bot is actively engaging other hostiles
     * (COMBAT mode) when a named mob hits it, seeded flee state will not tick
     * until combat ends. Phase 3 (damage hook) revisits this; if the observed
     * behavior is wrong in-game, the fix is a {@code forcedByDamage} flag on
     * {@code FleeState} that bypasses the mode guard.
     */
    public static void fleeFromEntity(ServerPlayerEntity bot, Entity attacker, long currentTick) {
        if (bot == null || attacker == null) return;
        FleeState state = FLEE_STATES.computeIfAbsent(bot.getUuid(), id -> new FleeState());
        if (state.isFleeing) return;
        startFleeing(bot, state, List.of(attacker), currentTick);
        // Mark this flee as damage-seeded so tickFlee bypasses the IDLE-only mode guard.
        // Only set after startFleeing so we know flee actually launched (not blocked at
        // computeTraversableFleeDirection — that path leaves isFleeing == false).
        if (state.isFleeing) {
            state.forcedByDamage = true;
        }
    }

    private static void startFleeing(ServerPlayerEntity bot, FleeState state,
                                     List<Entity> hostiles, long currentTick) {
        Vec3d fleeDir = computeTraversableFleeDirection(bot, hostiles);
        if (fleeDir == null) {
            LOGGER.info("Bot {} cornered — no traversable flee direction, standing ground",
                    bot.getName().getString());
            return; // don't set isFleeing, let combat engage
        }
        state.isFleeing = true;
        state.fleeStartTick = currentTick;
        state.fleeDirection = fleeDir;
        state.fleeStartPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        LOGGER.info("Bot {} fleeing from {} hostiles (health={}/{})",
                bot.getName().getString(), hostiles.size(),
                String.format("%.1f", bot.getHealth()),
                String.format("%.1f", bot.getMaxHealth()));
        applyFleeMovement(bot, state);
    }

    private static boolean continueFlee(ServerPlayerEntity bot, FleeState state,
                                        List<Entity> hostiles, long currentTick) {
        // Check if we've reached safety (all hostiles are far enough)
        boolean allSafe = hostiles.stream().allMatch(
                e -> e.squaredDistanceTo(bot) >= SAFE_DISTANCE_SQ);
        if (allSafe) {
            stopFleeing(state, currentTick);
            return false;
        }

        long fleeDuration = currentTick - state.fleeStartTick;

        // Stuck detection: if the bot hasn't moved more than 2 blocks from where it
        // started fleeing after 1.25 seconds, it's cornered — stop fleeing and fight.
        if (fleeDuration >= 25 && state.fleeStartPos != null) {
            double distFromStartSq = bot.squaredDistanceTo(state.fleeStartPos);
            if (distFromStartSq < 4.0) { // less than 2 blocks from start
                LOGGER.info("Bot {} cornered (moved only {} blocks in {}t) — abandoning flee to fight",
                        bot.getName().getString(),
                        String.format("%.1f", Math.sqrt(distFromStartSq)),
                        fleeDuration);
                stopFleeing(state, currentTick);
                return false; // let combat take over
            }
        }

        // Timeout — flee hasn't worked. Try emergency tactics before giving up.
        if (fleeDuration >= MAX_FLEE_TICKS) {
            if (!state.emergencyTacticAttempted && SkillPreferences.emergencyTactics(bot)) {
                state.emergencyTacticAttempted = true;
                if (tryEmergencyTactic(bot, hostiles)) {
                    stopFleeing(state, currentTick);
                    return true; // emergency tactic launched on a worker thread
                }
            }
            stopFleeing(state, currentTick);
            return false;
        }

        // Recompute direction periodically (hostiles may have shifted)
        if ((currentTick - state.fleeStartTick) % 20 == 0) {
            Vec3d newDir = computeTraversableFleeDirection(bot, hostiles);
            if (newDir == null) {
                LOGGER.info("Bot {} flee path blocked mid-flee — abandoning to fight",
                        bot.getName().getString());
                stopFleeing(state, currentTick);
                return false;
            }
            state.fleeDirection = newDir;
        }

        applyFleeMovement(bot, state);
        return true;
    }

    private static void applyFleeMovement(ServerPlayerEntity bot, FleeState state) {
        Vec3d target = new Vec3d(
                bot.getX() + state.fleeDirection.x * 25,
                bot.getY(),
                bot.getZ() + state.fleeDirection.z * 25);
        BotActions.sprint(bot, true);
        FollowMovementService.moveToward(bot, target, 1.0, true, null);
    }

    /**
     * Computes a flee direction that is actually traversable (no wall in the way).
     * Probes 5 candidate directions: primary (away from hostiles), +/-45deg, +/-90deg.
     * Returns null if ALL directions are blocked — caller should stand and fight.
     */
    private static Vec3d computeTraversableFleeDirection(ServerPlayerEntity bot, List<Entity> hostiles) {
        double cx = 0, cz = 0;
        for (Entity e : hostiles) {
            cx += e.getX();
            cz += e.getZ();
        }
        cx /= hostiles.size();
        cz /= hostiles.size();

        double dx = bot.getX() - cx;
        double dz = bot.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            dx = 1; dz = 0; len = 1;
        }
        dx /= len;
        dz /= len;

        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new Vec3d(dx, 0, dz); // fallback: can't probe blocks
        }

        // 5 candidate angles: 0, +45, -45, +90, -90 degrees from primary
        double[][] rotations = {
            {dx, dz},                                               // 0 deg
            {dx * 0.707 - dz * 0.707, dx * 0.707 + dz * 0.707},   // +45 deg
            {dx * 0.707 + dz * 0.707, -dx * 0.707 + dz * 0.707},  // -45 deg
            {-dz, dx},                                              // +90 deg
            {dz, -dx},                                              // -90 deg
        };

        BlockPos feet = bot.getBlockPos();
        int bestClearance = -1;
        int bestIdx = -1;

        for (int i = 0; i < rotations.length; i++) {
            int rdx = (int) Math.round(rotations[i][0]);
            int rdz = (int) Math.round(rotations[i][1]);
            if (rdx == 0 && rdz == 0) rdx = 1; // degenerate case

            int clearance = probeOpenBlocks(world, feet, rdx, rdz, 3);
            if (clearance > bestClearance) {
                bestClearance = clearance;
                bestIdx = i;
            }
        }

        if (bestClearance <= 0) {
            return null; // all directions blocked — stand and fight
        }

        return new Vec3d(rotations[bestIdx][0], 0, rotations[bestIdx][1]);
    }

    /** Probes how many blocks are traversable (feet+head clear) in a direction. */
    private static int probeOpenBlocks(ServerWorld world, BlockPos start, int dx, int dz, int maxDist) {
        for (int i = 1; i <= maxDist; i++) {
            BlockPos pos = start.add(dx * i, 0, dz * i);
            BlockState feetState = world.getBlockState(pos);
            BlockState headState = world.getBlockState(pos.up());
            if (feetState.blocksMovement() || headState.blocksMovement()) {
                return i - 1;
            }
        }
        return maxDist;
    }

    private static void stopFleeing(FleeState state, long currentTick) {
        state.isFleeing = false;
        state.fleeDirection = null;
        state.emergencyTacticAttempted = false;
        state.forcedByDamage = false;
        state.fleeCooldownUntilTick = currentTick + COOLDOWN_TICKS;
    }

    // ── Emergency Tactics ──────────────────────────────────────────────────

    /**
     * Attempts last-ditch survival tactics when fleeing has failed.
     * Runs all tactics sequentially on a single worker thread — if one fails,
     * falls through to the next. Returns true if the worker thread was launched.
     */
    private static boolean tryEmergencyTactic(ServerPlayerEntity bot, List<Entity> hostiles) {
        // Don't entomb during daytime — undead burn, just fight them
        if (bot.getEntityWorld() instanceof ServerWorld world) {
            if (world.isDay() && !world.isThundering()) return false;
        }

        // Don't entomb at high health — bot can handle the fight
        float healthRatio = bot.getHealth() / bot.getMaxHealth();
        if (healthRatio > 0.50f) return false;

        boolean hasBlocks = hasPlaceableBlocks(bot, 6);

        LOGGER.info("Bot {} evaluating emergency tactics (hostiles={}, hp={}/{}, blocks={})",
                bot.getName().getString(), hostiles.size(),
                String.format("%.1f", bot.getHealth()),
                String.format("%.1f", bot.getMaxHealth()),
                hasBlocks);

        final long gen = getGeneration(bot.getUuid());
        Thread t = new Thread(() -> runEmergencyTacticChain(bot, gen),
                "emergency-tactics-" + bot.getName().getString());
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * Runs emergency tactics sequentially on a worker thread. Each tactic is
     * attempted and verified — if it doesn't actually change the bot's situation,
     * the next tactic in the chain is tried.
     */
    private static void runEmergencyTacticChain(ServerPlayerEntity bot, long gen) {
        if (isStaleShelter(bot, gen)) return;

        // 1. Out of the water first.
        if (bot.getEntityWorld() instanceof ServerWorld dryWorld
                && prioritizeDryLand(bot, dryWorld, "night-shelter-water", 12, gen)) {
            if (isStaleShelter(bot, gen)) return;
        }

        // 2. A village house with a door.
        if (bot.getEntityWorld() instanceof ServerWorld villageWorld) {
            if (tryVillageHouseShelter(bot, villageWorld, gen)) {
                return;
            }
        }

        // 3. Wall off a natural overhang: seal its 1-2 open faces, no hole dug. Falls through
        //    unless assessShelterAt validates the finished cell.
        if (bot.getEntityWorld() instanceof ServerWorld wallWorld && tryWallOffShelter(bot, wallWorld, gen)) {
            return;
        }
        if (isStaleShelter(bot, gen)) return;

        // 4. Dig into cliff face — most reliable: mine in, seal entrance, fully enclosed.
        if (bot.getEntityWorld() instanceof ServerWorld world3) {
            net.minecraft.util.math.Direction cliffDir = findNearbyCliffFace(world3, bot.getBlockPos(), 6);
            if (cliffDir != null) {
                LOGGER.info("Bot {} trying emergency cliff-dig {}",
                        bot.getName().getString(), cliffDir.asString());
                emergencyCliffDig(bot, cliffDir, gen);
                return;
            }
        }

        // 5. Dig straight down — always available, self-supplying blocks
        BlockPos digSpot = bot.getBlockPos();
        LOGGER.info("Bot {} trying emergency dig-down at {}",
                bot.getName().getString(), digSpot.toShortString());
        emergencyDigDown(bot, gen);
    }

    /** Marks whose {@code column-marked} skip was already logged (once per column per night). */
    private static final ConcurrentHashMap<UUID, List<DigDownPolicy.Mark>> LOGGED_MARK_SKIPS = new ConcurrentHashMap<>();

    /** True the first time a skip under {@code mark} is seen for this bot; expired marks are pruned. */
    private static boolean firstMarkedSkip(ServerPlayerEntity bot, DigDownPolicy.Mark mark) {
        boolean[] first = {false};
        LOGGED_MARK_SKIPS.compute(bot.getUuid(), (id, logged) -> {
            first[0] = DigDownPolicy.firstSkipLog(logged, mark);
            return first[0] ? DigDownPolicy.withMark(logged, mark, mark.day()) : logged;
        });
        return first[0];
    }

    /** Per-bot no-dig-down marks left by an uncapped dig, kept until the next dawn. */
    private static final ConcurrentHashMap<UUID, List<DigDownPolicy.Mark>> NO_DIG_DOWN_MARKS = new ConcurrentHashMap<>();
    /** Timeout for each bounded server-thread hop of the dig-down step. */
    private static final long DIG_DOWN_HOP_TIMEOUT_MS = 500L;

    /** The preflight hop's answer: where the column starts and why it may not be dug (null = dig). */
    private record DigDownPreflight(BlockPos origin, String dimension, long day, String skipReason,
                                    DigDownPolicy.Mark coveringMark) {}

    /** One cap attempt: where the cap went (null when none) and every candidate's refusal. */
    private record DigDownCap(BlockPos capPos, BlockPos feet, String reasons) {}

    /**
     * Emergency dig-down: mine the 3 cells under the bot, then cap the hole with a seal block.
     * Preflights the column in one server hop and stays on the surface when it cannot finish a
     * bunker. Counts real removals. A real dig that ends uncapped climbs back out (refilling the pit
     * with a pillar) and marks the column so the chain does not dig there again before dawn.
     */
    private static void emergencyDigDown(ServerPlayerEntity bot, long gen) {
        String name = bot.getName().getString();
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null || !(bot.getEntityWorld() instanceof ServerWorld world)) return;
        DigDownPreflight pre = null;
        int realRemovals = 0;
        // True once the pit is registered as a shelter or the climb-out has run: nothing left to undo.
        boolean settled = false;
        try {
            if (isStaleShelter(bot, gen)) return;
            // Same stale-latch rule as wall-off: a latched /bot stop from before turns every
            // mineBlock into a no-op, and the cap then ran with the bot still on the surface.
            boolean latched = TaskService.isAbortRequested(bot.getUuid()) && !TaskService.isServerStopping();
            if (WallOffPolicy.clearStaleLatchAtStart(latched, TaskService.hasActiveTask(bot.getUuid()))) {
                String latchReason = TaskService.getCancelReason(bot.getUuid()).orElse("");
                TaskService.clearAbortLatch(bot.getUuid());
                LOGGER.info("Bot {} dig-down: cleared a stale abort latch (reason={})", name, latchReason);
            }

            pre = callOnServer(server, () -> {
                bot.setSprinting(false);
                bot.setVelocity(0, bot.getVelocity().y, 0);
                bot.velocityDirty = true;
                return digDownPreflight(bot, world);
            }, DIG_DOWN_HOP_TIMEOUT_MS, null);
            if (pre == null) {
                LOGGER.info("Bot {} dig-down skipped reason=preflight-timeout", name);
                return;
            }
            if (pre.skipReason() != null) {
                if (pre.coveringMark() == null || firstMarkedSkip(bot, pre.coveringMark())) {
                    LOGGER.info("Bot {} dig-down skipped reason={} at {}", name, pre.skipReason(), pre.origin().toShortString());
                }
                return;
            }
            BlockPos digPos = pre.origin();
            // The bot's box is 0.6 wide: off centre it straddles a neighbour cell and never drops
            // into the 1x1 shaft it digs.
            boolean centred = centreOnDigCell(bot, server, digPos);
            LOGGER.info("Bot {} dig-down: centred={} at {} over {}", name, centred,
                    String.format(java.util.Locale.ROOT, "%.2f,%.2f", bot.getX(), bot.getZ()), digPos.toShortString());
            Thread.sleep(300);

            // Mine the column, counting cells that really opened.
            for (int depth = 0; depth < DigDownPolicy.DEPTH; depth++) {
                if (isStaleShelter(bot, gen)) {
                    settled = true;
                    abandonStaleDigDown(bot, server, world, pre, realRemovals);
                    return;
                }
                BlockPos cell = digPos.down(depth + 1).toImmutable();
                MiningTool.mineBlock(bot, cell, false).join();
                Thread.sleep(200);
                boolean opened = Boolean.TRUE.equals(callOnServer(server,
                        () -> !world.getBlockState(cell).blocksMovement(), DIG_DOWN_HOP_TIMEOUT_MS, Boolean.FALSE));
                if (opened) {
                    realRemovals++;
                }
                DigDownPolicy.Step step = DigDownPolicy.afterMine(depth, opened);
                if (step == DigDownPolicy.Step.STOP_NOTHING_DUG) {
                    LOGGER.info("Bot {} dig-down: first cell {} did not open — staying on the surface",
                            name, cell.toShortString());
                    return;
                }
                if (step == DigDownPolicy.Step.STOP_PARTIAL) {
                    LOGGER.info("Bot {} dig-down: cell {} did not open — stopping at depth {}",
                            name, cell.toShortString(), realRemovals);
                    break;
                }
            }

            // Fall into the hole and pick up the drops (the cap may need them).
            Thread.sleep(1500);
            if (isStaleShelter(bot, gen)) {
                settled = true;
                abandonStaleDigDown(bot, server, world, pre, realRemovals);
                return;
            }

            // The cap goes over the bot's head with air below it: never a falling block.
            List<Item> capBlocks = capBlocksFor(bot);
            DigDownCap cap = tryCapDigDown(bot, server, world, digPos, capBlocks);
            if (cap.capPos() == null) {
                LOGGER.info("Bot {} dig-down cap failed (feet {}): {} — retrying once",
                        name, cap.feet() != null ? cap.feet().toShortString() : "?", cap.reasons());
                Thread.sleep(300);
                if (isStaleShelter(bot, gen)) {
                    settled = true;
                    abandonStaleDigDown(bot, server, world, pre, realRemovals);
                    return;
                }
                cap = tryCapDigDown(bot, server, world, digPos, capBlocks);
                if (cap.capPos() == null) {
                    LOGGER.info("Bot {} dig-down cap failed again (feet {}): {}",
                            name, cap.feet() != null ? cap.feet().toShortString() : "?", cap.reasons());
                }
            }
            final BlockPos capPos = cap.capPos();
            if (capPos != null) {
                LOGGER.info("Bot {} dig-down cap placed at {} (feet {}, covers={})",
                        name, capPos.toShortString(), cap.feet().toShortString(),
                        UndergroundLingerPolicy.bunkerCapCovers(cap.feet().getY(), capPos.getY()));
            }
            Thread.sleep(300);
            if (isStaleShelter(bot, gen)) {
                // Capped or not, an unregistered bot must not stay in the pit.
                settled = true;
                abandonStaleDigDown(bot, server, world, pre, realRemovals);
                return;
            }

            DigDownPolicy.Outcome outcome = DigDownPolicy.afterCap(realRemovals, capPos != null);
            if (outcome == DigDownPolicy.Outcome.CLIMB_OUT) {
                LOGGER.info("Bot {} dig-down bunker uncapped after digging {} — climbing out", name, realRemovals);
                settled = true;
                markAndClimbOut(bot, server, world, pre);
                return;
            }
            if (outcome != DigDownPolicy.Outcome.SHELTER) {
                return;
            }

            // Mark shelter active only if the bunker validates as survivable (read on the server thread).
            ShelterAssessment assessment = callOnServer(server,
                    () -> assessShelterAt(bot, world, bot.getBlockPos(), ShelterType.DIG_DOWN, capPos, null),
                    DIG_DOWN_HOP_TIMEOUT_MS, null);
            String rejectReason = assessment == null ? "assess-timeout"
                    : assessment.valid() ? "valid" : assessment.reason().name().toLowerCase();
            boolean sealed = assessment != null && Boolean.TRUE.equals(callOnServer(server,
                    () -> isShelterEntranceSecured(world, assessment.standPos(), ShelterType.DIG_DOWN, capPos, null),
                    DIG_DOWN_HOP_TIMEOUT_MS, Boolean.FALSE));
            DigDownPolicy.Accept accept = DigDownPolicy.accept(assessment != null && assessment.valid(), rejectReason, sealed);
            if (accept == DigDownPolicy.Accept.CLIMB_OUT) {
                LOGGER.info("Bot {} dig-down bunker rejected reason={} — climbing out", name, rejectReason);
                settled = true;
                markAndClimbOut(bot, server, world, pre);
                return;
            }
            if (accept == DigDownPolicy.Accept.REGISTER_SEALED) {
                LOGGER.info("Bot {} dig-down: hostile adjacent but sealed, registering", name);
            }
            registerShelter(bot, new ShelterInfo(
                    server.getTicks(),
                    ShelterType.DIG_DOWN,
                    capPos,
                    null,
                    assessment.standPos(),
                    server.getTicks(),
                    ShelterInvalidReason.VALID));
            settled = true;

            // Wait for bot to settle inside the bunker before placing torch
            Thread.sleep(500);

            // Place a torch at the bottom of the dug column.
            final BlockPos torchTarget = digPos.down(realRemovals);
            server.execute(() -> {
                for (int i = 0; i < bot.getInventory().size(); i++) {
                    if (bot.getInventory().getStack(i).isOf(Items.TORCH)) {
                        if (i >= 9) {
                            ItemStack temp = bot.getInventory().getStack(0);
                            bot.getInventory().setStack(0, bot.getInventory().getStack(i));
                            bot.getInventory().setStack(i, temp);
                        }
                        bot.getInventory().setSelectedSlot((i < 9) ? i : 0);
                        boolean placed = BotActions.placeBlockAt(bot, torchTarget);
                        LOGGER.info("Bot {} torch at {} (inside bunker): {}",
                                name, torchTarget.toShortString(), placed ? "placed" : "failed");
                        break;
                    }
                }
            });
            Thread.sleep(200);

            LOGGER.info("Bot {} dug emergency bunker at {} (mined={}, capPlaced=true)",
                    name, digPos.toShortString(), realRemovals);

            // Eat inside the shelter while waiting to heal
            int shelterBites = HealingService.stabilizeEat(bot, 5);
            if (shelterBites > 0) {
                LOGGER.info("Bot {} ate {} items inside shelter (hp={})",
                        name, shelterBites, String.format("%.1f", bot.getHealth()));
            }
            // If still hurt and couldn't eat enough, wait for natural regen
            if (bot.getHealth() < bot.getMaxHealth() * 0.8f) {
                Thread.sleep(3000);
            }
        } catch (InterruptedException e) {
            if (DigDownPolicy.climbAfterFailure(realRemovals, settled) && pre != null) {
                // Climb with the flag cleared (every wait would throw again), then restore it.
                Thread.interrupted();
                LOGGER.info("Bot {} dig-down interrupted after digging {} — climbing out", name, realRemovals);
                try {
                    markAndClimbOut(bot, server, world, pre);
                } catch (InterruptedException ignored) {
                    // fall through to restoring the flag
                } catch (Exception climbError) {
                    LOGGER.warn("Bot {} dig-down climb-out after interrupt failed: {}", name, climbError.getMessage());
                }
            }
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Emergency dig-down failed for {}: {}", name, e.getMessage());
            if (DigDownPolicy.climbAfterFailure(realRemovals, settled) && pre != null) {
                LOGGER.info("Bot {} dig-down failed after digging {} — climbing out", name, realRemovals);
                try {
                    markAndClimbOut(bot, server, world, pre);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception climbError) {
                    LOGGER.warn("Bot {} dig-down climb-out after failure failed: {}", name, climbError.getMessage());
                }
            }
        }
    }

    /**
     * Bounded centring over the dig cell before mining, like the wall-off centring but to
     * {@link DigDownPolicy#CENTRE_TOLERANCE} so the 0.6-wide box fits the 1x1 shaft. The verdict is
     * read on the server thread.
     */
    private static boolean centreOnDigCell(ServerPlayerEntity bot, MinecraftServer server, BlockPos cell)
            throws InterruptedException {
        Vec3d target = Vec3d.ofBottomCenter(cell);
        long deadline = System.currentTimeMillis() + 2_000L;
        boolean moved = false;
        while (System.currentTimeMillis() < deadline) {
            if (DigDownPolicy.centred(bot.getX(), bot.getZ(), cell.getX(), cell.getZ())) {
                break;
            }
            double dx = target.x - bot.getX();
            double dz = target.z - bot.getZ();
            double horizSq = dx * dx + dz * dz;
            if (horizSq > 1.0D) {
                break;
            }
            double impulse = Math.max(0.03D, Math.min(0.08D, Math.sqrt(horizSq) * 0.25D));
            BotActions.applyMovementInput(bot, target, impulse);
            moved = true;
            Thread.sleep(90L);
        }
        if (moved) {
            BotActions.stop(bot);
            Thread.sleep(150L);
        }
        return Boolean.TRUE.equals(callOnServer(server,
                () -> DigDownPolicy.centred(bot.getX(), bot.getZ(), cell.getX(), cell.getZ()),
                DIG_DOWN_HOP_TIMEOUT_MS, Boolean.FALSE));
    }

    /** Server thread only: one read of the column under the bot, its seal blocks and its marks. */
    private static DigDownPreflight digDownPreflight(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos origin = bot.getBlockPos().toImmutable();
        String dimension = world.getRegistryKey().getValue().toString();
        long day = DigDownPolicy.gameDay(world.getTimeOfDay());
        DigDownPolicy.Mark covering = DigDownPolicy.coveringMark(NO_DIG_DOWN_MARKS.get(bot.getUuid()),
                dimension, origin.getX(), origin.getZ(), day);
        boolean marked = covering != null;
        List<DigDownPolicy.Cell> cells = new ArrayList<>(DigDownPolicy.DEPTH);
        for (int depth = 1; depth <= DigDownPolicy.DEPTH; depth++) {
            cells.add(classifyDigDownCell(bot, world, origin.down(depth)));
        }
        BlockState floor = world.getBlockState(origin.down(DigDownPolicy.DEPTH + 1));
        boolean floorSolid = floor.blocksMovement() && floor.getFluidState().isEmpty();
        // Only blocks the cap may use count: no falling blocks over the bot's head.
        int seals = 0;
        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && CAP_IDS.contains(Registries.ITEM.getId(stack.getItem()).toString())) {
                seals += stack.getCount();
            }
        }
        String topId = Registries.BLOCK.getId(world.getBlockState(origin.down()).getBlock()).toString();
        String reason = DigDownPolicy.preflight(marked, cells, floorSolid, seals, topId);
        return new DigDownPreflight(origin, dimension, day, reason, marked ? covering : null);
    }

    private static DigDownPolicy.Cell classifyDigDownCell(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return DigDownPolicy.Cell.FLUID;
        }
        if (state.isAir() || state.getCollisionShape(world, pos).isEmpty()) {
            return DigDownPolicy.Cell.AIR;
        }
        if (state.getHardness(world, pos) < 0.0F) {
            return DigDownPolicy.Cell.UNBREAKABLE;
        }
        if (ProtectedStructureBlockHelper.isNeverBreakBlock(state)
                || net.wcfcarolina13.GameAI.skills.support.MiningHazardDetector.isHarvestProtectedOre(state)
                || MappedVillageService.isInsideMappedVillage(world, pos)
                || !BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos).allowed()) {
            return DigDownPolicy.Cell.PROTECTED;
        }
        return DigDownPolicy.Cell.DIGGABLE;
    }

    /**
     * One server hop: place a seal block on the validator's cover cell (just above the head), and
     * only when that cell is at or below the original ground: a cap above an air gap, or above a
     * 1-deep hole with the head at ground level, is not a bunker.
     */
    private static DigDownCap tryCapDigDown(ServerPlayerEntity bot, MinecraftServer server, ServerWorld world,
                                            BlockPos digPos, List<Item> seals) {
        DigDownCap cap = callOnServer(server, () -> {
            BlockPos feetNow = bot.getBlockPos().toImmutable();
            BlockPos c = feetNow.up(2).toImmutable();
            if (!DigDownPolicy.capAllowed(feetNow.getX(), feetNow.getY(), feetNow.getZ(),
                    digPos.getX(), digPos.getY(), digPos.getZ())) {
                return new DigDownCap(null, feetNow, c.toShortString() + "=not-in-pit");
            }
            if (!world.getBlockState(c).isAir()) {
                return new DigDownCap(null, feetNow, c.toShortString() + "=occupied");
            }
            BotActions.PlaceResult result = BotActions.tryPlaceBlockAt(bot, c, Direction.UP, seals, false);
            if (result != null && result.success()) {
                return new DigDownCap(c, feetNow, "");
            }
            return new DigDownCap(null, feetNow, c.toShortString() + "=" + (result != null ? result.reason() : "null"));
        }, DIG_DOWN_HOP_TIMEOUT_MS, null);
        return cap != null ? cap : new DigDownCap(null, null, "timeout");
    }

    /**
     * Leaves a dig-down pit. The linger tree suppresses every surface recovery entry point at night
     * (SUPPRESS_NIGHT), and the pillar recovery tears its pillar down again, so this pillars up
     * inside the 1x1 shaft and leaves the pillar in place: the pit ends up filled. The pillar places
     * {@link ScaffoldService#SCAFFOLD_BLOCKS} only, so "pillar with the mined blocks" works for a dirt
     * or grass column, or a stone column mined with a pickaxe (cobblestone drops). Sand, gravel or
     * stone mined by hand gives no scaffold: then the dedicated surface recovery (no linger gate) runs.
     */
    private static void climbOutOfDigDownPit(ServerPlayerEntity bot,
                                             MinecraftServer server,
                                             ServerWorld world,
                                             BlockPos digPos) throws InterruptedException {
        String name = bot.getName().getString();
        Integer startY = callOnServer(server, () -> bot.getBlockY(), DIG_DOWN_HOP_TIMEOUT_MS, null);
        if (startY == null) {
            LOGGER.info("Bot {} dig-down climb-out: position read timed out — not climbing", name);
            return;
        }
        int depth = DigDownPolicy.pitDepth(startY, digPos.getY());
        if (depth <= 0) {
            LOGGER.info("Bot {} dig-down climb-out: not in the pit (feetY={} ground={}) — nothing to climb",
                    name, startY, digPos.getY());
            return;
        }
        // A /bot stop or forceAbort that came with the stale generation latches TaskService, and the
        // pillar aborts at step 0 on a latch. Clear it unless a running task owns it.
        boolean latched = TaskService.isAbortRequested(bot.getUuid()) && !TaskService.isServerStopping();
        DigDownPolicy.Latch latch = DigDownPolicy.climbLatch(latched, TaskService.hasActiveTask(bot.getUuid()));
        if (latch == DigDownPolicy.Latch.CLEAR) {
            String latchReason = TaskService.getCancelReason(bot.getUuid()).orElse("");
            TaskService.clearAbortLatch(bot.getUuid());
            LOGGER.info("Bot {} dig-down climb-out: cleared an abort latch (reason={}) so the pillar can run",
                    name, latchReason);
        } else if (latch == DigDownPolicy.Latch.BLOCKED) {
            LOGGER.info("Bot {} dig-down climb-out: abort latch owned by a running task (reason={}) — left in the pit at {}",
                    name, TaskService.getCancelReason(bot.getUuid()).orElse(""), bot.getBlockPos().toShortString());
            return;
        }
        ensureScaffoldReachable(bot, depth);
        Integer scaffold = callOnServer(server, () -> countScaffoldBlocks(bot), DIG_DOWN_HOP_TIMEOUT_MS, 0);
        int scaffoldCount = scaffold != null ? scaffold : 0;
        int steps = DigDownPolicy.climbSteps(depth, scaffoldCount);
        int placed = 0;
        String pillarReason = "no-scaffold";
        if (steps > 0) {
            ScaffoldService.PillarResult pillar = ScaffoldService.pillarUpDetailed(bot, steps);
            placed = pillar.placed().size();
            pillarReason = pillar.reason() == null || pillar.reason().isEmpty() ? "ok" : pillar.reason();
        }
        LOGGER.info("Bot {} dig-down climb-out: pillared {}/{} (scaffold={} reason={})",
                name, placed, depth, scaffoldCount, pillarReason);
        if (shouldAbortSurvival(bot)) {
            return;
        }
        // Step onto the rim (from a full refill the bot already stands at ground level).
        BlockPos rim = callOnServer(server, () -> {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos side = digPos.offset(dir);
                if (VoxelJunctionService.isStandable(world, side)) {
                    return side.toImmutable();
                }
            }
            return null;
        }, DIG_DOWN_HOP_TIMEOUT_MS, null);
        if (rim != null) {
            MovementService.MovementPlan rimPlan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT, rim, rim, null, null, null);
            MovementService.execute(bot.getCommandSource(), bot, rimPlan, Boolean.FALSE, true);
            Thread.sleep(400L);
        }
        Integer feetY = callOnServer(server, () -> bot.getBlockY(), DIG_DOWN_HOP_TIMEOUT_MS, Integer.MIN_VALUE);
        boolean out = feetY != null && DigDownPolicy.climbedOut(feetY, digPos.getY());
        if (!out && !shouldAbortSurvival(bot)) {
            LOGGER.info("Bot {} dig-down climb-out: still in the pit (feetY={}) — running surface recovery", name, feetY);
            net.wcfcarolina13.GameAI.skills.SkillExecutionResult result = SurfaceRecoveryService.runSurfaceRecovery(bot, world);
            feetY = callOnServer(server, () -> bot.getBlockY(), DIG_DOWN_HOP_TIMEOUT_MS, Integer.MIN_VALUE);
            out = feetY != null && DigDownPolicy.climbedOut(feetY, digPos.getY());
            LOGGER.info("Bot {} dig-down climb-out: surface recovery success={} message={}",
                    name, result != null && result.success(), result != null ? result.message() : "null");
        }
        LOGGER.info("Bot {} dig-down climb-out: {} at {} (rim={})",
                name, out ? "out" : "still in the pit",
                bot.getBlockPos().toShortString(), rim != null ? rim.toShortString() : "none");
    }

    /** Marks the column first (so a failed or aborted climb still blocks a second dig), then climbs out. */
    private static void markAndClimbOut(ServerPlayerEntity bot, MinecraftServer server, ServerWorld world,
                                        DigDownPreflight pre) throws InterruptedException {
        markNoDigDown(bot, pre.dimension(), pre.origin(), pre.day());
        climbOutOfDigDownPit(bot, server, world, pre.origin());
    }

    /**
     * A generation bump (respawn, mode change, combat reset) mid-dig must not leave a live bot in an
     * uncapped pit: climb out and mark the column, as the uncapped path does. Wall-off precedent:
     * {@link #abandonStaleWallOff}.
     */
    private static void abandonStaleDigDown(ServerPlayerEntity bot, MinecraftServer server, ServerWorld world,
                                            DigDownPreflight pre, int realRemovals) throws InterruptedException {
        if (realRemovals <= 0 || !bot.isAlive() || bot.isRemoved()) {
            return;
        }
        LOGGER.info("Bot {} dig-down: stale mid-dig after digging {} — climbing out", bot.getName().getString(), realRemovals);
        markAndClimbOut(bot, server, world, pre);
    }

    /** No dig-down in this column (and {@link DigDownPolicy#MARK_RADIUS} around it) until the next dawn. */
    private static void markNoDigDown(ServerPlayerEntity bot, String dimension, BlockPos digPos, long day) {
        DigDownPolicy.Mark mark = new DigDownPolicy.Mark(dimension, digPos.getX(), digPos.getZ(), day);
        NO_DIG_DOWN_MARKS.compute(bot.getUuid(), (id, marks) -> DigDownPolicy.withMark(marks, mark, day));
        LOGGER.info("Bot {} dig-down: column {} marked no-dig-down (±{}) until dawn",
                bot.getName().getString(), digPos.toShortString(), DigDownPolicy.MARK_RADIUS);
    }

    /** Check if the bot has at least N placeable blocks in inventory. */
    private static boolean hasPlaceableBlocks(ServerPlayerEntity bot, int minCount) {
        int count = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            var stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.item.BlockItem) {
                count += stack.getCount();
                if (count >= minCount) return true;
            }
        }
        return false;
    }

    // ── Wall-off night shelter ─────────────────────────────────────────────

    /** Wall-clock budget across candidates; the first candidate always runs. */
    private static final long WALL_OFF_BUDGET_MS = 12_000L;
    /** Timeout for each bounded server-thread hop of the wall-off step. */
    private static final long WALL_OFF_HOP_TIMEOUT_MS = 500L;
    /** Bounded centring after the mover: the seal cells are adjacent to the stand cell. */
    private static final long WALL_OFF_CENTRE_TIMEOUT_MS = 2_500L;

    /** The server-thread snapshot and the absolute block its offsets are relative to (the bot's feet). */
    private record WallOffScan(BlockPos origin, WallOffPolicy.Snapshot snapshot) {}

    private record WallOffTeardown(int removed, int skipped, List<BlockPos> skippedCells) {}

    /**
     * Step 3 of the tactic chain: walk to a natural overhang cell with one or two open faces, seal
     * them with seal-family blocks, and register the cell only when {@link #assessShelterAt}
     * validates it. Returns true only when {@code SHELTER_ACTIVE} was set; every other outcome
     * removes what it placed and falls through to cliff-dig / dig-down.
     */
    private static boolean tryWallOffShelter(ServerPlayerEntity bot, ServerWorld world, long gen) {
        if (bot == null || world == null || isStaleShelter(bot, gen)) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return false;
        }
        String name = bot.getName().getString();
        // TaskService.forceAbort latches even with no ticket, and nothing clears it for this
        // background thread: a /bot stop from minutes ago would abort the walk (shouldAbortSurvival)
        // and the mover itself (nudge/execute read isAbortRequested). Clear a latch that no running
        // task owns, as the sunset return and underground survival do. A /bot stop issued during
        // the step re-latches and still aborts it.
        boolean latched = TaskService.isAbortRequested(bot.getUuid()) && !TaskService.isServerStopping();
        if (WallOffPolicy.clearStaleLatchAtStart(latched, TaskService.hasActiveTask(bot.getUuid()))) {
            String latchReason = TaskService.getCancelReason(bot.getUuid()).orElse("");
            TaskService.clearAbortLatch(bot.getUuid());
            LOGGER.info("Bot {} wall-off: cleared a stale abort latch (reason={}) so the shelter walk can run",
                    name, latchReason);
        }
        long startMs = System.currentTimeMillis();
        float hpStart = bot.getHealth();
        try {
            WallOffScan scan = callOnServer(server, () -> buildWallOffScan(bot, world), WALL_OFF_HOP_TIMEOUT_MS, null);
            if (scan == null) {
                LOGGER.info("Bot {} wall-off: snapshot timeout", name);
                return false;
            }
            WallOffPolicy.Snapshot snap = scan.snapshot();
            boolean gatesOk = snap.blocks() >= WallOffPolicy.MIN_BLOCKS && snap.hotbarHasSealBlock();
            List<WallOffPolicy.Candidate> ranked = gatesOk
                    ? WallOffPolicy.rank(snap, WallOffPolicy.MAX_PLACEMENTS)
                    : List.of();
            LOGGER.info("Bot {} wall-off: scan r={} candidates={} blocks={} hotbarOk={}",
                    name, WallOffPolicy.SEARCH_RADIUS, ranked.size(), snap.blocks(), snap.hotbarHasSealBlock());
            if (!gatesOk) {
                return false;
            }
            if (ranked.isEmpty()) {
                LOGGER.info("Bot {} wall-off: no candidate", name);
                return false;
            }

            BlockPos origin = scan.origin();
            for (int i = 0; i < ranked.size(); i++) {
                if (i > 0 && System.currentTimeMillis() - startMs > WALL_OFF_BUDGET_MS) {
                    LOGGER.info("Bot {} wall-off: walk aborted reason=budget", name);
                    return false;
                }
                String abort = wallOffAbortReason(bot, gen, hpStart);
                if (abort != null) {
                    LOGGER.info("Bot {} wall-off: walk aborted reason={}", name, abort);
                    return false;
                }
                WallOffPolicy.Candidate candidate = ranked.get(i);
                BlockPos feet = toWorld(origin, candidate.feet());
                List<BlockPos> plan = new ArrayList<>();
                for (WallOffPolicy.Cell cell : WallOffPolicy.placementOrder(candidate)) {
                    plan.add(toWorld(origin, cell));
                }
                LOGGER.info("Bot {} wall-off: walking to {} placements={} faces={}",
                        name, feet.toShortString(), plan.size(), candidate.openFaces());
                if (!bot.getBlockPos().equals(feet)) {
                    moveTowardLocalShelterAnchor(bot, world, feet, "wall-off");
                }
                abort = wallOffAbortReason(bot, gen, hpStart);
                if (abort != null) {
                    LOGGER.info("Bot {} wall-off: walk aborted reason={}", name, abort);
                    return false;
                }
                WallOffPolicy.Arrival arrival = centreOnWallOffCell(bot, server, feet);
                LOGGER.info("Bot {} wall-off: arrival {} at {} target {}",
                        name, arrival.name().toLowerCase(), bot.getBlockPos().toShortString(), feet.toShortString());
                if (arrival != WallOffPolicy.Arrival.ARRIVED) {
                    continue;
                }
                abort = wallOffAbortReason(bot, gen, hpStart);
                if (abort != null) {
                    LOGGER.info("Bot {} wall-off: walk aborted reason={}", name, abort);
                    return false;
                }
                return sealAndRegisterWallOff(bot, world, server, gen, feet, plan, toDirection(candidate.primaryFace()));
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOGGER.warn("Bot {} wall-off failed: {}", name, e.getMessage());
            return false;
        }
    }

    /** Seals {@code plan} in order, verifies, validates, and registers. Tears down on every failure. */
    private static boolean sealAndRegisterWallOff(ServerPlayerEntity bot,
                                                  ServerWorld world,
                                                  MinecraftServer server,
                                                  long gen,
                                                  BlockPos feet,
                                                  List<BlockPos> plan,
                                                  Direction primaryDir) throws InterruptedException {
        String name = bot.getName().getString();
        List<Item> seals = sealBlocksFor(bot);
        List<BlockPos> ledger = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            if (isStaleShelter(bot, gen)) {
                abandonStaleWallOff(bot, server, ledger);
                return false;
            }
            BlockPos cell = plan.get(i);
            BotActions.PlaceResult result = BotActions.tryPlaceBlockAt(bot, cell, Direction.UP, seals, false);
            if (result == null || !result.success()) {
                Thread.sleep(300L);
                result = BotActions.tryPlaceBlockAt(bot, cell, Direction.UP, seals, false);
            }
            if (result != null && result.success()) {
                ledger.add(cell);
                LOGGER.info("Bot {} wall-off: placed {} ({}/{})", name, cell.toShortString(), i + 1, plan.size());
                continue;
            }
            WallOffTeardown torn = tearDownWallOffLedger(bot, server, ledger);
            LOGGER.info("Bot {} wall-off: place failed at {} reason={} — tore down {}",
                    name, cell.toShortString(), result != null ? result.reason() : "null", torn.removed());
            return false;
        }

        List<BlockPos> placed = List.copyOf(ledger);
        Boolean holds = callOnServer(server, () -> {
            for (BlockPos cell : placed) {
                if (!world.getBlockState(cell).blocksMovement()) {
                    return false;
                }
            }
            return true;
        }, WALL_OFF_HOP_TIMEOUT_MS, Boolean.FALSE);
        if (!Boolean.TRUE.equals(holds)) {
            WallOffTeardown torn = tearDownWallOffLedger(bot, server, placed);
            LOGGER.info("Bot {} wall-off: verify failed — tore down {}", name, torn.removed());
            return false;
        }
        LOGGER.info("Bot {} wall-off: sealed at {} placed={}", name, feet.toShortString(), placed.size());

        if (isStaleShelter(bot, gen)) {
            abandonStaleWallOff(bot, server, placed);
            return false;
        }
        BlockPos firstFeet = placed.get(0);
        for (BlockPos cell : placed) {
            if (cell.getY() == feet.getY()) {
                firstFeet = cell;
                break;
            }
        }
        final BlockPos capPos = firstFeet;
        // Reads world state off-thread, as the dig-down and cliff accept steps do (pre-existing precedent).
        ShelterAssessment assessment = assessShelterAt(bot, world, feet, ShelterType.WALL_OFF, capPos, primaryDir);
        final BlockPos stand = assessment.standPos() != null ? assessment.standPos() : feet;
        if (!assessment.valid()) {
            String reason = assessment.reason().name().toLowerCase();
            boolean sealedAnyway = false;
            if (!WallOffPolicy.teardownOnReject(reason)) {
                sealedAnyway = Boolean.TRUE.equals(callOnServer(server,
                        () -> isShelterEntranceSecured(world, stand, ShelterType.WALL_OFF, capPos, primaryDir),
                        WALL_OFF_HOP_TIMEOUT_MS, Boolean.FALSE));
            }
            if (!sealedAnyway) {
                WallOffTeardown torn = tearDownWallOffLedger(bot, server, placed);
                LOGGER.info("Bot {} wall-off rejected reason={} tore down {}", name, reason, torn.removed());
                return false;
            }
            LOGGER.info("Bot {} wall-off: hostile adjacent but sealed, registering", name);
        }

        long tick = server.getTicks();
        registerShelter(bot, new ShelterInfo(
                tick,
                ShelterType.WALL_OFF,
                capPos,
                primaryDir,
                stand,
                tick,
                ShelterInvalidReason.VALID,
                placed));
        LOGGER.info("Bot {} wall-off shelter active at {} blocks={}", name, stand.toShortString(), placed.size());

        int shelterBites = HealingService.stabilizeEat(bot, 5);
        if (shelterBites > 0) {
            LOGGER.info("Bot {} ate {} items inside wall-off shelter (hp={})",
                    name, shelterBites, String.format("%.1f", bot.getHealth()));
        }
        return true;
    }

    /**
     * A stale generation mid-seal (respawn, reset) must not leave a half wall behind. A dead or
     * removed bot cannot mine, so its blocks stay.
     */
    private static void abandonStaleWallOff(ServerPlayerEntity bot,
                                            MinecraftServer server,
                                            List<BlockPos> ledger) throws InterruptedException {
        if (ledger.isEmpty() || !bot.isAlive() || bot.isRemoved()) {
            return;
        }
        WallOffTeardown torn = tearDownWallOffLedger(bot, server, List.copyOf(ledger));
        LOGGER.info("Bot {} wall-off: stale mid-seal — tore down {}", bot.getName().getString(), torn.removed());
    }

    /** Null when the walk may continue, else the reason to fall through. */
    private static String wallOffAbortReason(ServerPlayerEntity bot, long gen, float hpStart) {
        if (isStaleShelter(bot, gen)) {
            return "stale";
        }
        if (shouldAbortSurvival(bot)) {
            return "abort";
        }
        if (WallOffPolicy.abortWalk(hpStart, bot.getHealth())) {
            return "hp-drop";
        }
        return null;
    }

    /**
     * Small bounded centring loop toward the stand cell's bottom centre. The final verdict is read
     * in one server hop; the loop's own position reads are off-thread like the nudge helpers'.
     */
    private static WallOffPolicy.Arrival centreOnWallOffCell(ServerPlayerEntity bot,
                                                            MinecraftServer server,
                                                            BlockPos feet) throws InterruptedException {
        Vec3d target = Vec3d.ofBottomCenter(feet);
        long deadline = System.currentTimeMillis() + WALL_OFF_CENTRE_TIMEOUT_MS;
        boolean moved = false;
        while (System.currentTimeMillis() < deadline) {
            if (WallOffPolicy.arrival(bot.getX(), bot.getY(), bot.getZ(), bot.isOnGround(),
                    feet.getX(), feet.getY(), feet.getZ()) == WallOffPolicy.Arrival.ARRIVED) {
                break;
            }
            double dx = target.x - bot.getX();
            double dz = target.z - bot.getZ();
            double horizSq = dx * dx + dz * dz;
            if (horizSq > 4.0D || Math.abs(target.y - bot.getY()) > 0.6D) {
                break; // the mover did not get close enough; centring will not fix it
            }
            double impulse = Math.max(0.04D, Math.min(0.10D, Math.sqrt(horizSq) * 0.25D));
            BotActions.applyMovementInput(bot, target, impulse);
            moved = true;
            Thread.sleep(90L);
        }
        if (moved) {
            BotActions.stop(bot);
            Thread.sleep(150L);
        }
        return callOnServer(server,
                () -> WallOffPolicy.arrival(bot.getX(), bot.getY(), bot.getZ(), bot.isOnGround(),
                        feet.getX(), feet.getY(), feet.getZ()),
                WALL_OFF_HOP_TIMEOUT_MS, WallOffPolicy.Arrival.OFF_CELL);
    }

    /**
     * Mines the ledger in reverse placement order (head before feet, so a gravel or sand head
     * block never drops into a freed feet cell), only where the cell still holds a seal-family
     * block. Never touches anything outside the ledger.
     */
    private static WallOffTeardown tearDownWallOffLedger(ServerPlayerEntity bot,
                                                         MinecraftServer server,
                                                         List<BlockPos> ledger) throws InterruptedException {
        int removed = 0;
        int skipped = 0;
        List<BlockPos> skippedCells = new ArrayList<>();
        if (ledger == null || ledger.isEmpty() || !(bot.getEntityWorld() instanceof ServerWorld world)) {
            return new WallOffTeardown(0, 0, List.of());
        }
        String name = bot.getName().getString();
        for (int i = ledger.size() - 1; i >= 0; i--) {
            BlockPos cell = ledger.get(i);
            String id = callOnServer(server, () -> {
                BlockState state = world.getBlockState(cell);
                return state.isAir() ? "" : Registries.ITEM.getId(state.getBlock().asItem()).toString();
            }, WALL_OFF_HOP_TIMEOUT_MS, null);
            if (id == null) {
                skipped++;
                skippedCells.add(cell);
                LOGGER.info("Bot {} wall-off teardown: skipped {} (read timeout)", name, cell.toShortString());
                continue;
            }
            if (id.isEmpty()) {
                removed++; // already open
                continue;
            }
            if (!WallOffPolicy.teardownAllowed(id, SEAL_IDS)) {
                skipped++;
                skippedCells.add(cell);
                LOGGER.info("Bot {} wall-off teardown: skipped {} ({} is not a seal block)",
                        name, cell.toShortString(), id);
                continue;
            }
            // enforceVillageProtection=false: ledger cells are the bot's own blocks. With the 3-arg
            // overload a wall inside a mapped village is refused at dawn and the bot is entombed.
            MiningTool.mineBlock(bot, cell, false, false).join();
            Thread.sleep(200L);
            Boolean open = callOnServer(server, () -> !world.getBlockState(cell).blocksMovement(),
                    WALL_OFF_HOP_TIMEOUT_MS, Boolean.FALSE);
            if (Boolean.TRUE.equals(open)) {
                removed++;
            } else {
                skipped++;
                skippedCells.add(cell);
                LOGGER.info("Bot {} wall-off teardown: {} still blocks after mining", name, cell.toShortString());
            }
        }
        return new WallOffTeardown(removed, skipped, List.copyOf(skippedCells));
    }

    /** Server thread only: one bounded read of the box around the bot's feet plus its seal blocks. */
    private static WallOffScan buildWallOffScan(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos origin = bot.getBlockPos().toImmutable();
        int r = WallOffPolicy.READ_RADIUS;
        int dyMin = WallOffPolicy.READ_DY_MIN;
        int dyMax = WallOffPolicy.READ_DY_MAX;
        int volume = WallOffPolicy.Snapshot.volume(r, dyMin, dyMax);
        byte[] cells = new byte[volume];
        boolean[] sky = new boolean[volume];
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dy = dyMin; dy <= dyMax; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    int idx = WallOffPolicy.Snapshot.index(r, dyMin, dx, dy, dz);
                    cells[idx] = classifyWallOffCell(world, m);
                    sky[idx] = world.isSkyVisible(m);
                }
            }
        }

        // Claims and mapped villages: ask only for clear cells a candidate could seal
        // (feet/head beside a possible stand cell). Marks are applied after the pass so a mark
        // never changes another cell's stand test.
        Set<Integer> refused = new java.util.HashSet<>();
        Set<Integer> asked = new java.util.HashSet<>();
        for (int dy = WallOffPolicy.SEARCH_DY_MIN; dy <= WallOffPolicy.SEARCH_DY_MAX; dy++) {
            for (int dz = -WallOffPolicy.SEARCH_RADIUS; dz <= WallOffPolicy.SEARCH_RADIUS; dz++) {
                for (int dx = -WallOffPolicy.SEARCH_RADIUS; dx <= WallOffPolicy.SEARCH_RADIUS; dx++) {
                    if (cells[WallOffPolicy.Snapshot.index(r, dyMin, dx, dy - 1, dz)] != WallOffPolicy.SOLID
                            || cells[WallOffPolicy.Snapshot.index(r, dyMin, dx, dy, dz)] != WallOffPolicy.CLEAR
                            || cells[WallOffPolicy.Snapshot.index(r, dyMin, dx, dy + 1, dz)] != WallOffPolicy.CLEAR
                            || cells[WallOffPolicy.Snapshot.index(r, dyMin, dx, dy + 2, dz)] != WallOffPolicy.SOLID) {
                        continue;
                    }
                    for (WallOffPolicy.Face face : WallOffPolicy.Face.values()) {
                        for (int k = 0; k <= 1; k++) {
                            int idx = WallOffPolicy.Snapshot.index(r, dyMin, dx + face.dx, dy + k, dz + face.dz);
                            if (cells[idx] != WallOffPolicy.CLEAR || !asked.add(idx)) {
                                continue;
                            }
                            BlockPos pos = origin.add(dx + face.dx, dy + k, dz + face.dz);
                            if (MappedVillageService.isInsideMappedVillage(world, pos)
                                    || !BotTerritoryAuthorizationService.authorizeBlockMutation(bot, world, pos).allowed()) {
                                refused.add(idx);
                            }
                        }
                    }
                }
            }
        }
        for (int idx : refused) {
            cells[idx] = WallOffPolicy.PROTECTED;
        }

        // Hostiles: the read box widened by the 4-block rule so an edge candidate still sees them.
        int reach = r + 4;
        List<int[]> hostiles = new ArrayList<>();
        Box box = new Box(origin.getX() - reach, origin.getY() + dyMin - 4, origin.getZ() - reach,
                origin.getX() + reach + 1, origin.getY() + dyMax + 5, origin.getZ() + reach + 1);
        for (net.minecraft.entity.mob.HostileEntity mob : world.getEntitiesByClass(
                net.minecraft.entity.mob.HostileEntity.class, box, e -> e != null && e.isAlive() && !e.isRemoved())) {
            BlockPos p = mob.getBlockPos();
            hostiles.add(new int[]{p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ()});
        }

        Map<String, Integer> counts = new HashMap<>();
        boolean hotbarSeal = false;
        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            if (!SEAL_IDS.contains(id)) {
                continue;
            }
            counts.merge(id, stack.getCount(), Integer::sum);
            if (i < 9) {
                hotbarSeal = true;
            }
        }
        boolean hotbarOk = !HotbarLockService.isLocked(bot) || hotbarSeal;
        int blocks = WallOffPolicy.countSealable(counts, SEAL_IDS);

        return new WallOffScan(origin, new WallOffPolicy.Snapshot(r, dyMin, dyMax, cells, sky,
                hostiles.toArray(new int[0][]), blocks, hotbarOk));
    }

    /** Collision-based cell code; hazards first because lava and fire have empty collision. */
    private static byte classifyWallOffCell(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE)) {
            return WallOffPolicy.HAZARD;
        }
        var fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            return fluid.isStill() ? WallOffPolicy.HAZARD : WallOffPolicy.FLUID;
        }
        if (state.isAir()) {
            return WallOffPolicy.CLEAR;
        }
        boolean noCollision = state.getCollisionShape(world, pos).isEmpty();
        if (state.isReplaceable() && noCollision) {
            return WallOffPolicy.CLEAR;
        }
        return noCollision ? WallOffPolicy.PARTIAL : WallOffPolicy.SOLID;
    }

    private static BlockPos toWorld(BlockPos origin, WallOffPolicy.Cell cell) {
        return origin.add(cell.dx(), cell.dy(), cell.dz()).toImmutable();
    }

    private static Direction toDirection(WallOffPolicy.Face face) {
        if (face == null) {
            return null;
        }
        return switch (face) {
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }

    /** Bounded server-thread call from a worker; runs inline when already on the server thread. */
    private static <T> T callOnServer(MinecraftServer server, Supplier<T> action, long timeoutMs, T fallback) {
        if (server == null || action == null) {
            return fallback;
        }
        if (server.isOnThread()) {
            return action.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(Math.max(250L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static ShelterAssessment assessShelterAt(ServerPlayerEntity bot,
                                                     ServerWorld world,
                                                     BlockPos feet,
                                                     ShelterType type,
                                                     BlockPos capPos,
                                                     Direction entryDir) {
        if (bot == null || world == null || feet == null) {
            return new ShelterAssessment(false, type, feet, ShelterInvalidReason.NO_STANDABLE_INTERIOR);
        }

        BlockPos stand = VoxelJunctionService.isStandable(world, feet) ? feet.toImmutable() : bot.getBlockPos().toImmutable();
        if (!VoxelJunctionService.isStandable(world, stand)) {
            return new ShelterAssessment(false, type, stand, ShelterInvalidReason.NO_STANDABLE_INTERIOR);
        }
        if (world.isSkyVisible(stand) || world.isSkyVisible(stand.up()) || world.isSkyVisible(stand.up(2))) {
            return new ShelterAssessment(false, type, stand, ShelterInvalidReason.OPEN_SKY);
        }
        if (!hasOverheadCover(world, stand)) {
            return new ShelterAssessment(false, type, stand, ShelterInvalidReason.NO_OVERHEAD_COVER);
        }
        if (hasImmediateShelterHazard(world, stand)) {
            return new ShelterAssessment(false, type, stand, ShelterInvalidReason.WATER_INTRUSION);
        }
        if (isHostileExposed(bot, world, stand)) {
            return new ShelterAssessment(false, type, stand, ShelterInvalidReason.HOSTILE_EXPOSED);
        }
        if (!isShelterEntranceSecured(world, stand, type, capPos, entryDir)) {
            return new ShelterAssessment(false, type, stand, ShelterInvalidReason.ENTRANCE_UNSEALED);
        }
        if (type == ShelterType.VILLAGE_HOUSE && !SurvivalRecruitmentService.isVillageSignalNearby(world, stand)) {
            return new ShelterAssessment(false, type, stand, ShelterInvalidReason.NO_VILLAGE_SIGNAL);
        }
        return new ShelterAssessment(true, type, stand, ShelterInvalidReason.VALID);
    }

    private static boolean hasOverheadCover(ServerWorld world, BlockPos stand) {
        if (world == null || stand == null) {
            return false;
        }
        BlockState above = world.getBlockState(stand.up(2));
        return !above.getCollisionShape(world, stand.up(2)).isEmpty();
    }

    private static boolean hasImmediateShelterHazard(ServerWorld world, BlockPos stand) {
        if (world == null || stand == null) {
            return true;
        }
        if (!world.getFluidState(stand).isEmpty() || !world.getFluidState(stand.up()).isEmpty()) {
            return true;
        }
        BlockState support = world.getBlockState(stand.down());
        if (support.isOf(Blocks.MAGMA_BLOCK) || support.isOf(Blocks.CAMPFIRE) || support.isOf(Blocks.SOUL_CAMPFIRE)) {
            return true;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos adjacent = stand.offset(dir);
            BlockState state = world.getBlockState(adjacent);
            if (state.isOf(Blocks.LAVA) || state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.CACTUS)) {
                return true;
            }
            if (!world.getFluidState(adjacent).isEmpty() && world.getFluidState(adjacent).isStill()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHostileExposed(ServerPlayerEntity bot, ServerWorld world, BlockPos stand) {
        if (bot == null || world == null || stand == null) {
            return false;
        }
        Box box = Box.of(Vec3d.ofCenter(stand), 6.0D, 4.0D, 6.0D);
        return !world.getEntitiesByClass(
                net.minecraft.entity.mob.HostileEntity.class,
                box,
                mob -> mob != null && mob.isAlive() && !mob.isRemoved() && mob.getBlockPos().getSquaredDistance(stand) <= 16.0D
        ).isEmpty();
    }

    private static boolean isShelterEntranceSecured(ServerWorld world,
                                                    BlockPos stand,
                                                    ShelterType type,
                                                    BlockPos capPos,
                                                    Direction entryDir) {
        if (world == null || stand == null) {
            return false;
        }
        return switch (type) {
            case CLIFF -> capPos != null
                    && world.getBlockState(capPos).blocksMovement()
                    && world.getBlockState(capPos.up()).blocksMovement();
            case DIG_DOWN, JOIN_ENCLOSED -> {
                if (capPos != null && world.getBlockState(capPos).blocksMovement()) {
                    yield true;
                }
                yield VoxelJunctionService.countOpenFaces(world, stand) <= 1;
            }
            case WALL_OFF -> {
                // Strictest rule: all eight side cells (feet and head on every face) block movement.
                for (Direction dir : Direction.Type.HORIZONTAL) {
                    BlockPos side = stand.offset(dir);
                    if (!world.getBlockState(side).blocksMovement() || !world.getBlockState(side.up()).blocksMovement()) {
                        yield false;
                    }
                }
                yield true;
            }
            case VILLAGE_HOUSE -> {
                if (capPos == null) {
                    yield false;
                }
                BlockPos base = normalizeDoorBase(world, capPos);
                if (base == null) {
                    yield false;
                }
                BlockState state = world.getBlockState(base);
                boolean open = state.contains(DoorBlock.OPEN) && Boolean.TRUE.equals(state.get(DoorBlock.OPEN));
                yield state.getBlock() instanceof DoorBlock || open || (entryDir != null && VoxelJunctionService.countOpenFaces(world, stand) <= 2);
            }
        };
    }

    private static boolean tryVillageHouseShelter(ServerPlayerEntity bot, ServerWorld world, long gen) {
        if (bot == null || world == null) {
            return false;
        }
        BlockPos searchCenter = chooseWaterAwareShelterSearchCenter(bot, world);
        ShelterAssessment current = assessShelterAt(bot, world, bot.getBlockPos(), ShelterType.VILLAGE_HOUSE, findAdjacentDoor(world, bot.getBlockPos(), 3), null);
        if (current.valid() && SurvivalRecruitmentService.isVillageSignalNearby(world, bot.getBlockPos())) {
            long tick = bot.getCommandSource().getServer() != null ? bot.getCommandSource().getServer().getTicks() : 0L;
            registerShelter(bot, new ShelterInfo(
                    tick,
                    ShelterType.VILLAGE_HOUSE,
                    findAdjacentDoor(world, current.standPos(), 3),
                    null,
                    current.standPos(),
                    tick,
                    ShelterInvalidReason.VALID));
            LOGGER.info("Bot {} already inside a validated village shelter at {}",
                    bot.getName().getString(), current.standPos().toShortString());
            return true;
        }

        VillageHouseCandidate candidate = findNearbyVillageHouseCandidate(bot, world, searchCenter, BotWaterEscapeService.isInWater(bot) ? 28 : 18);
        if (candidate == null || isStaleShelter(bot, gen)) {
            return false;
        }

        LOGGER.info("Bot {} trying village-house shelter door={} interior={}",
                bot.getName().getString(),
                candidate.doorPos().toShortString(),
                candidate.interiorPos().toShortString());

        boolean traversed = MovementService.tryTraverseOpenableToward(bot, candidate.doorPos(), candidate.interiorPos(), "village-shelter");
        if (!traversed && bot.getBlockPos().getSquaredDistance(candidate.interiorPos()) > 4.0D) {
            return false;
        }
        if (isStaleShelter(bot, gen)) {
            return false;
        }
        ShelterAssessment assessment = assessShelterAt(
                bot,
                world,
                bot.getBlockPos().getSquaredDistance(candidate.interiorPos()) <= 4.0D ? bot.getBlockPos() : candidate.interiorPos(),
                ShelterType.VILLAGE_HOUSE,
                candidate.doorPos(),
                candidate.interiorDir());
        if (!assessment.valid()) {
            LOGGER.info("Bot {} village-house shelter rejected reason={}",
                    bot.getName().getString(),
                    assessment.reason().name().toLowerCase());
            return false;
        }
        long tick = bot.getCommandSource().getServer() != null ? bot.getCommandSource().getServer().getTicks() : 0L;
        registerShelter(bot, new ShelterInfo(
                tick,
                ShelterType.VILLAGE_HOUSE,
                candidate.doorPos(),
                candidate.interiorDir(),
                assessment.standPos(),
                tick,
                ShelterInvalidReason.VALID));
        return true;
    }

    private static BlockPos chooseWaterAwareShelterSearchCenter(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return null;
        }
        if (!BotWaterEscapeService.isInWater(bot)) {
            return bot.getBlockPos();
        }
        BlockPos shore = BotWaterEscapeService.findNearestShoreStand(bot, 14);
        return shore != null ? shore : bot.getBlockPos();
    }

    private static boolean prioritizeDryLand(ServerPlayerEntity bot,
                                             ServerWorld world,
                                             String label,
                                             int radius,
                                             long gen) {
        if (bot == null || world == null || !BotWaterEscapeService.isInWater(bot)) {
            return false;
        }
        BlockPos shore = BotWaterEscapeService.findNearestShoreStand(bot, radius);
        if (shore == null) {
            return false;
        }
        if (bot.getBlockPos().getSquaredDistance(shore) <= 2.25D) {
            return true;
        }
        LOGGER.info("Bot {} water-phobic shelter reroute to dry land {}",
                bot.getName().getString(), shore.toShortString());
        boolean moved = moveTowardLocalShelterAnchor(bot, world, shore, label);
        return moved || isStaleShelter(bot, gen);
    }

    private static VillageHouseCandidate findNearbyVillageHouseCandidate(ServerPlayerEntity bot,
                                                                         ServerWorld world,
                                                                         BlockPos center,
                                                                         int radius) {
        if (bot == null || world == null || center == null) {
            return null;
        }
        VillageHouseCandidate best = null;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!world.isChunkLoaded(cursor)) {
                        continue;
                    }
                    BlockPos doorBase = normalizeDoorBase(world, cursor);
                    if (doorBase == null || !isVillageUsableDoor(world, doorBase)) {
                        continue;
                    }
                    if (!SurvivalRecruitmentService.isVillageSignalNearby(world, doorBase)) {
                        continue;
                    }
                    VillageHouseCandidate candidate = scoreVillageDoorCandidate(bot, world, doorBase);
                    if (candidate == null) {
                        continue;
                    }
                    if (best == null || candidate.score() > best.score()) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static VillageHouseCandidate scoreVillageDoorCandidate(ServerPlayerEntity bot,
                                                                   ServerWorld world,
                                                                   BlockPos doorBase) {
        BlockState state = world.getBlockState(doorBase);
        if (!(state.getBlock() instanceof DoorBlock door) || !state.contains(DoorBlock.FACING)) {
            return null;
        }
        if (door == Blocks.IRON_DOOR && !(state.contains(DoorBlock.OPEN) && Boolean.TRUE.equals(state.get(DoorBlock.OPEN)))) {
            return null;
        }
        Direction facing = state.get(DoorBlock.FACING);
        BlockPos sideA = doorBase.offset(facing);
        BlockPos sideB = doorBase.offset(facing.getOpposite());
        VillageHouseCandidate a = makeVillageCandidate(bot, world, doorBase, sideA);
        VillageHouseCandidate b = makeVillageCandidate(bot, world, doorBase, sideB);
        if (a == null) return b;
        if (b == null) return a;
        return a.score() >= b.score() ? a : b;
    }

    private static VillageHouseCandidate makeVillageCandidate(ServerPlayerEntity bot,
                                                              ServerWorld world,
                                                              BlockPos doorBase,
                                                              BlockPos interiorSeed) {
        BlockPos stand = findVillageInteriorStand(world, interiorSeed, 2);
        if (stand == null) {
            return null;
        }
        Direction interiorDir = directionFrom(doorBase, stand);
        ShelterAssessment assessment = assessShelterAt(bot, world, stand, ShelterType.VILLAGE_HOUSE, doorBase, interiorDir);
        if (!assessment.valid()) {
            return null;
        }
        int roofBonus = world.isSkyVisible(doorBase) ? 0 : 8;
        int score = roofBonus
                + 16
                - Math.min(12, (int) Math.round(Math.sqrt(bot.getBlockPos().getSquaredDistance(stand))))
                + Math.max(0, 4 - VoxelJunctionService.countOpenFaces(world, stand));
        return new VillageHouseCandidate(doorBase.toImmutable(), assessment.standPos(), interiorDir, score);
    }

    private static BlockPos findVillageInteriorStand(ServerWorld world, BlockPos seed, int radius) {
        if (world == null || seed == null) {
            return null;
        }
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = seed.add(dx, dy, dz);
                    if (!VoxelJunctionService.isStandable(world, candidate)) {
                        continue;
                    }
                    if (world.isSkyVisible(candidate) || world.isSkyVisible(candidate.up())) {
                        continue;
                    }
                    double score = 10.0D - candidate.getSquaredDistance(seed)
                            + Math.max(0, 4 - VoxelJunctionService.countOpenFaces(world, candidate)) * 2.0D;
                    if (score > bestScore) {
                        bestScore = score;
                        best = candidate.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos normalizeDoorBase(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.HALF)) {
            return state.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER
                    ? pos.down().toImmutable()
                    : pos.toImmutable();
        }
        BlockState above = world.getBlockState(pos.up());
        if (above.getBlock() instanceof DoorBlock && above.contains(DoorBlock.HALF)
                && above.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER) {
            return pos.toImmutable();
        }
        return null;
    }

    private static boolean isVillageUsableDoor(ServerWorld world, BlockPos doorBase) {
        if (world == null || doorBase == null) {
            return false;
        }
        BlockState state = world.getBlockState(doorBase);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return false;
        }
        if (!state.contains(DoorBlock.FACING)) {
            return false;
        }
        return !(state.getBlock() == Blocks.IRON_DOOR)
                || (state.contains(DoorBlock.OPEN) && Boolean.TRUE.equals(state.get(DoorBlock.OPEN)));
    }

    private static BlockPos findAdjacentDoor(ServerWorld world, BlockPos center, int radius) {
        if (world == null || center == null) {
            return null;
        }
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockPos base = normalizeDoorBase(world, cursor);
                    if (base != null && isVillageUsableDoor(world, base)) {
                        return base;
                    }
                }
            }
        }
        return null;
    }

    private static Direction directionFrom(BlockPos origin, BlockPos target) {
        if (origin == null || target == null) {
            return null;
        }
        int dx = Integer.compare(target.getX(), origin.getX());
        int dz = Integer.compare(target.getZ(), origin.getZ());
        if (Math.abs(dx) >= Math.abs(dz)) {
            if (dx > 0) return Direction.EAST;
            if (dx < 0) return Direction.WEST;
        }
        if (dz > 0) return Direction.SOUTH;
        if (dz < 0) return Direction.NORTH;
        return null;
    }

    /**
     * Finds a solid cliff/hillside face within searchRadius that the bot can dig into.
     * A valid cliff face has: 2+ solid blocks tall at feet+head level, with air in front
     * (the bot's side). Returns the direction TO dig (toward the cliff), or null.
     */
    private static net.minecraft.util.math.Direction findNearbyCliffFace(
            ServerWorld world, BlockPos center, int searchRadius) {
        net.minecraft.util.math.Direction bestDir = null;
        double bestDistSq = Double.MAX_VALUE;

        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            for (int dist = 1; dist <= searchRadius; dist++) {
                BlockPos standPos = center.offset(dir, dist - 1);
                BlockPos wallFeet = center.offset(dir, dist);
                BlockPos wallHead = wallFeet.up();

                // The stand position must be open (air at feet + head)
                if (!world.getBlockState(standPos).isAir()) continue;
                if (!world.getBlockState(standPos.up()).isAir()) continue;
                // The wall must be solid at both feet and head level
                if (!world.getBlockState(wallFeet).isSolidBlock(world, wallFeet)) continue;
                if (!world.getBlockState(wallHead).isSolidBlock(world, wallHead)) continue;
                // Layers 2-3 must also be solid (will be mined for 3-deep tunnel)
                BlockPos deepFeet = wallFeet.offset(dir);
                BlockPos deepHead = deepFeet.up();
                if (!world.getBlockState(deepFeet).isSolidBlock(world, deepFeet)) continue;
                if (!world.getBlockState(deepHead).isSolidBlock(world, deepHead)) continue;
                BlockPos deeperFeet = deepFeet.offset(dir);
                BlockPos deeperHead = deeperFeet.up();
                if (!world.getBlockState(deeperFeet).isSolidBlock(world, deeperFeet)) continue;
                if (!world.getBlockState(deeperHead).isSolidBlock(world, deeperHead)) continue;
                // Fourth layer must be solid (back wall of the 3-deep tunnel)
                BlockPos backFeet = deeperFeet.offset(dir);
                BlockPos backHead = backFeet.up();
                if (!world.getBlockState(backFeet).isSolidBlock(world, backFeet)) continue;
                if (!world.getBlockState(backHead).isSolidBlock(world, backHead)) continue;
                // Solid overhead for all three tunnel sections
                BlockPos ceiling = wallFeet.up(2);
                if (!world.getBlockState(ceiling).isSolidBlock(world, ceiling)) continue;
                BlockPos deepCeiling = deepFeet.up(2);
                if (!world.getBlockState(deepCeiling).isSolidBlock(world, deepCeiling)) continue;
                BlockPos deeperCeiling = deeperFeet.up(2);
                if (!world.getBlockState(deeperCeiling).isSolidBlock(world, deeperCeiling)) continue;

                double distSq = center.getSquaredDistance(wallFeet);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestDir = dir;
                }
                break; // found one in this direction, no need to search further
            }
        }
        return bestDir;
    }

    /**
     * Emergency cliff-dig: mine a 3-deep 1x2 tunnel into a cliff face, pathfind to
     * the back, seal the entrance, and place a torch if available.
     *
     * <p>Staged approach: mine entrance → pathfind into entrance (picks up block drops)
     * → mine deeper layers → pathfind to back (picks up remaining drops) → seal.</p>
     */
    private static void emergencyCliffDig(ServerPlayerEntity bot,
                                          net.minecraft.util.math.Direction digDir, long gen) {
        try {
            if (isStaleShelter(bot, gen)) return;
            MinecraftServer server = bot.getCommandSource().getServer();

            // Stop moving.
            server.execute(() -> {
                bot.setSprinting(false);
                bot.setVelocity(0, bot.getVelocity().y, 0);
                bot.velocityDirty = true;
            });
            Thread.sleep(300);

            BlockPos botPos = bot.getBlockPos();
            // Layer 1: entrance (1 block in digDir from bot)
            BlockPos wallFeet = botPos.offset(digDir);
            BlockPos wallHead = wallFeet.up();
            // Layer 2: middle (2 blocks in digDir from bot)
            BlockPos deepFeet = wallFeet.offset(digDir);
            BlockPos deepHead = deepFeet.up();
            // Layer 3: back (3 blocks in digDir from bot)
            BlockPos deeperFeet = deepFeet.offset(digDir);
            BlockPos deeperHead = deeperFeet.up();

            // Walk toward the cliff if not adjacent
            if (bot.squaredDistanceTo(Vec3d.ofCenter(wallFeet)) > 4.0) {
                server.execute(() ->
                        FollowMovementService.moveToward(bot, Vec3d.ofCenter(botPos), 1.0, true, null));
                Thread.sleep(1000);
            }

            if (isStaleShelter(bot, gen)) return;

            // Stage 1: mine entrance (2 blocks)
            LOGGER.debug("Cliff-dig: mining 3-deep tunnel {} → {} → {}",
                    wallFeet.toShortString(), deepFeet.toShortString(), deeperFeet.toShortString());
            MiningTool.mineBlock(bot, wallFeet, false).join();
            Thread.sleep(200);
            if (isStaleShelter(bot, gen)) return;
            MiningTool.mineBlock(bot, wallHead, false).join();
            Thread.sleep(500); // let drops settle

            if (isStaleShelter(bot, gen)) return;

            // Stage 2: pathfind into entrance — picks up block drops from layer 1
            MovementService.MovementPlan entrancePlan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    wallFeet, wallFeet,
                    null, null, null);
            MovementService.execute(bot.getCommandSource(), bot, entrancePlan, Boolean.FALSE, true);
            Thread.sleep(600); // item pickup delay (10 ticks = 500ms)

            if (isStaleShelter(bot, gen)) return;

            // Stage 3: mine middle + back layers (4 blocks) from inside entrance
            MiningTool.mineBlock(bot, deepFeet, false).join();
            Thread.sleep(200);
            if (isStaleShelter(bot, gen)) return;
            MiningTool.mineBlock(bot, deepHead, false).join();
            Thread.sleep(200);
            if (isStaleShelter(bot, gen)) return;
            MiningTool.mineBlock(bot, deeperFeet, false).join();
            Thread.sleep(200);
            if (isStaleShelter(bot, gen)) return;
            MiningTool.mineBlock(bot, deeperHead, false).join();
            Thread.sleep(500); // let drops settle

            if (isStaleShelter(bot, gen)) return;

            // Stage 4: pathfind to the back of the tunnel — picks up remaining drops
            MovementService.MovementPlan backPlan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    deeperFeet, deeperFeet,
                    null, null, null);
            MovementService.execute(bot.getCommandSource(), bot, backPlan, Boolean.FALSE, true);

            // MovementService "close enough" often stops 1 block short — nudge the rest
            if (bot.squaredDistanceTo(Vec3d.ofCenter(deeperFeet)) > 1.5) {
                MovementService.nudgeTowardUntilClose(bot, deeperFeet, 1.0, 3000, 0.20, "shelter-back");
            }
            Thread.sleep(600); // item pickup delay

            LOGGER.info("Bot {} tunnel position: at {} dest={} distSq={}",
                    bot.getName().getString(), bot.getBlockPos().toShortString(),
                    deeperFeet.toShortString(),
                    String.format("%.1f", bot.squaredDistanceTo(Vec3d.ofCenter(deeperFeet))));

            // Seal the entrance with solid blocks (allowIntersecting=true for bounding box edge cases)
            final List<Item> seals = sealBlocksFor(bot);
            boolean[] sealed = {false};
            server.execute(() -> {
                BotActions.PlaceResult fResult = BotActions.tryPlaceBlockAt(bot, wallFeet,
                        net.minecraft.util.math.Direction.UP, seals, true);
                BotActions.PlaceResult hResult = BotActions.tryPlaceBlockAt(bot, wallHead,
                        net.minecraft.util.math.Direction.UP, seals, true);
                sealed[0] = fResult.success() && hResult.success();
                if (!sealed[0]) {
                    LOGGER.info("Bot {} seal failed (feet={}, head={})",
                            bot.getName().getString(), fResult.reason(), hResult.reason());
                }
            });
            Thread.sleep(400);
            // Retry seal if first attempt failed
            if (!sealed[0]) {
                server.execute(() -> {
                    if (!bot.getEntityWorld().getBlockState(wallFeet).blocksMovement())
                        BotActions.tryPlaceBlockAt(bot, wallFeet, net.minecraft.util.math.Direction.UP, seals, true);
                    if (!bot.getEntityWorld().getBlockState(wallHead).blocksMovement())
                        BotActions.tryPlaceBlockAt(bot, wallHead, net.minecraft.util.math.Direction.UP, seals, true);
                    sealed[0] = bot.getEntityWorld().getBlockState(wallFeet).blocksMovement()
                            && bot.getEntityWorld().getBlockState(wallHead).blocksMovement();
                });
                Thread.sleep(300);
            }

            // Place a torch inside if available
            server.execute(() -> {
                for (int i = 0; i < bot.getInventory().size(); i++) {
                    if (bot.getInventory().getStack(i).isOf(Items.TORCH)) {
                        if (i >= 9) {
                            ItemStack temp = bot.getInventory().getStack(0);
                            bot.getInventory().setStack(0, bot.getInventory().getStack(i));
                            bot.getInventory().setStack(i, temp);
                        }
                        bot.getInventory().setSelectedSlot((i < 9) ? i : 0);
                        BotActions.placeBlockAt(bot, bot.getBlockPos());
                        break;
                    }
                }
            });
            Thread.sleep(200);

            // Mark shelter active only if the finished enclosure validates as survivable.
            if (isStaleShelter(bot, gen)) return;
            ShelterAssessment assessment = assessShelterAt(
                    bot,
                    (ServerWorld) bot.getEntityWorld(),
                    bot.getBlockPos(),
                    ShelterType.CLIFF,
                    wallFeet.toImmutable(),
                    digDir);
            String cliffReason = assessment.reason().name().toLowerCase();
            DigDownPolicy.Accept cliffAccept = DigDownPolicy.accept(assessment.valid(), cliffReason, sealed[0]);
            ShelterInfo cliffInfo = new ShelterInfo(
                    server.getTicks(),
                    ShelterType.CLIFF,
                    wallFeet.toImmutable(),
                    digDir,
                    assessment.standPos(),
                    server.getTicks(),
                    ShelterInvalidReason.VALID);
            if (cliffAccept == DigDownPolicy.Accept.CLIMB_OUT) {
                // Sealed in but unregistered would mean no dawn exit: open the seal and walk out.
                LOGGER.info("Bot {} cliff shelter rejected reason={} — leaving the tunnel",
                        bot.getName().getString(), cliffReason);
                breakFreeCliff(bot, (ServerWorld) bot.getEntityWorld(), cliffInfo);
                return;
            }
            if (cliffAccept == DigDownPolicy.Accept.REGISTER_SEALED) {
                LOGGER.info("Bot {} cliff: hostile adjacent but sealed, registering", bot.getName().getString());
            }
            registerShelter(bot, cliffInfo);

            LOGGER.info("Bot {} dug emergency cliff shelter {} (sealed={})",
                    bot.getName().getString(), digDir.asString(), sealed[0]);

            // Eat inside the shelter while waiting to heal
            int shelterBites = HealingService.stabilizeEat(bot, 5);
            if (shelterBites > 0) {
                LOGGER.info("Bot {} ate {} items inside cliff shelter (hp={})",
                        bot.getName().getString(), shelterBites,
                        String.format("%.1f", bot.getHealth()));
            }
            if (bot.getHealth() < bot.getMaxHealth() * 0.8f) {
                Thread.sleep(3000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Emergency cliff-dig failed for {}: {}", bot.getName().getString(), e.getMessage());
        }
    }

    /**
     * Checks if a sheltered bot should break free now that it's safe daylight.
     * Called from the IDLE tick handler. Returns true if break-free was initiated.
     */
    public static boolean checkDaylightBreakFree(ServerPlayerEntity bot, MinecraftServer server) {
        if (shouldAbortSurvival(bot)) return false;
        ShelterInfo info = SHELTER_ACTIVE.get(bot.getUuid());
        if (info == null) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;

        if (!world.isDay() || world.isThundering()) return false;
        long tod = world.getTimeOfDay() % 24000L;
        if (tod < 1000 || tod >= 12000) return false;

        SHELTER_ACTIVE.remove(bot.getUuid());
        LOGGER.info("Bot {} breaking free from tactical shelter (daylight, tod={})",
                bot.getName().getString(), tod);

        Thread t = new Thread(() -> {
            breakFreeFromShelter(bot, info);
            // After breaking free, try to resume the interrupted task (e.g., woodcut stopped at sunset).
            SkillResumeService.requestAutoResume(bot);
        }, "shelter-breakfree-" + bot.getName().getString());
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * Clears shelter state and launches break-free mining on a worker thread.
     * Use when a command (follow, skill, etc.) needs the bot to leave shelter first.
     * The returned thread can be joined to wait for completion before executing the command.
     * Guarded by SHELTER_LOCK to prevent duplicate break-free threads and allow
     * callers to check {@link #isBreakingFree(UUID)}.
     */
    public static Thread clearShelterAndBreakFree(ServerPlayerEntity bot) {
        if (shouldAbortSurvival(bot)) return null;
        ShelterInfo info = SHELTER_ACTIVE.remove(bot.getUuid());
        if (info == null) return null;
        AtomicBoolean lock = SHELTER_LOCK.computeIfAbsent(bot.getUuid(), k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            LOGGER.debug("clearShelterAndBreakFree skipped for {} — another break-free already running",
                    bot.getName().getString());
            return null;
        }
        LOGGER.info("Bot {} breaking free from {} shelter for command",
                bot.getName().getString(), info.type());
        Thread t = new Thread(() -> {
            try {
                if (shouldAbortSurvival(bot)) {
                    return;
                }
                breakFreeFromShelter(bot, info);
            } finally {
                lock.set(false);
            }
        }, "shelter-breakfree-" + bot.getName().getString());
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Returns true if a break-free thread is currently running for this bot. */
    public static boolean isBreakingFree(UUID botId) {
        AtomicBoolean lock = SHELTER_LOCK.get(botId);
        return lock != null && lock.get();
    }

    /**
     * Force break-free without shelter metadata (e.g. bot enclosed after server restart).
     * Uses generic escape: collect torch, try 4 horizontal dirs, then pillar to surface.
     * Guarded by SHELTER_LOCK to prevent duplicate concurrent break-free threads.
     */
    public static Thread forceBreakFree(ServerPlayerEntity bot) {
        if (shouldAbortSurvival(bot)) return null;
        SHELTER_ACTIVE.remove(bot.getUuid());
        AtomicBoolean lock = SHELTER_LOCK.computeIfAbsent(bot.getUuid(), k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            LOGGER.debug("forceBreakFree skipped for {} — another break-free already running",
                    bot.getName().getString());
            return null;
        }
        Thread t = new Thread(() -> {
            try {
                if (shouldAbortSurvival(bot)) {
                    return;
                }
                breakFreeFromShelter(bot, null);
            } finally {
                lock.set(false);
            }
        }, "shelter-breakfree-" + bot.getName().getString());
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Type-aware shelter breakout. Collects torch, mines exit based on shelter type,
     * then escapes to surface if still underground.
     */
    private static void breakFreeFromShelter(ServerPlayerEntity bot, ShelterInfo info) {
        // Every break-free (dawn, starvation, command, forced) starts here: the saved shelter goes.
        forgetPersistedShelter(bot, "break-free");
        if (shouldAbortSurvival(bot)) {
            return;
        }
        boolean owner = beginSurvivalAction(bot.getUuid(), InterruptedSurvivalAction.BREAK_FREE);
        try {
            BlockPos pos = bot.getBlockPos();
            ServerWorld world = (ServerWorld) bot.getEntityWorld();

            // 1. Collect torch — check current position and all adjacent
            collectNearbyTorch(bot, world, pos);
            if (shouldAbortSurvival(bot)) {
                return;
            }

            // 2. Type-specific exit
            if (info != null && info.type() == ShelterType.CLIFF && info.capPos() != null) {
                breakFreeCliff(bot, world, info);
            } else if (info != null && (info.type() == ShelterType.DIG_DOWN || info.type() == ShelterType.JOIN_ENCLOSED) && info.capPos() != null) {
                breakFreeDugDown(bot, world, info);
            } else if (info != null && info.type() == ShelterType.VILLAGE_HOUSE && info.capPos() != null) {
                breakFreeVillageHouse(bot, world, info);
            } else if (info != null && info.type() == ShelterType.WALL_OFF && info.capPos() != null) {
                breakFreeWallOff(bot, world, info);
            } else {
                breakFreeGeneric(bot, world, pos);
            }
            if (shouldAbortSurvival(bot)) {
                return;
            }

            // Record shelter exit so the underground linger system doesn't immediately
            // re-trigger surface recovery after a shelter breakfree.
            MinecraftServer srv = bot.getCommandSource().getServer();
            if (srv != null) {
                noteShelterExit(bot.getUuid(), srv.getTicks());
            }

            // 3. Surface escape — if still underground after breaking seal, pillar up
            escapeToSurface(bot, world);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Break-free failed for {}: {}", bot.getName().getString(), e.getMessage());
        } finally {
            endSurvivalAction(bot.getUuid(), InterruptedSurvivalAction.BREAK_FREE, owner);
        }
    }

    /** Collect one torch from adjacent positions. */
    private static void collectNearbyTorch(ServerPlayerEntity bot, ServerWorld world, BlockPos pos)
            throws InterruptedException {
        BlockPos[] torchCandidates = {
            pos, pos.up(),
            pos.north(), pos.south(), pos.east(), pos.west()
        };
        for (BlockPos tp : torchCandidates) {
            if (world.getBlockState(tp).isOf(Blocks.TORCH)
                    || world.getBlockState(tp).isOf(Blocks.WALL_TORCH)) {
                MiningTool.mineBlock(bot, tp, false).join();
                Thread.sleep(500);
                break;
            }
        }
    }

    /** Break free from a cliff shelter: mine the 2 seal blocks, pathfind outward. */
    private static void breakFreeCliff(ServerPlayerEntity bot, ServerWorld world, ShelterInfo info)
            throws InterruptedException {
        BlockPos sealFeet = info.capPos();
        BlockPos sealHead = sealFeet.up();
        // Mine seal blocks
        if (world.getBlockState(sealFeet).isSolidBlock(world, sealFeet)) {
            MiningTool.mineBlock(bot, sealFeet, false).join();
            Thread.sleep(200);
        }
        if (world.getBlockState(sealHead).isSolidBlock(world, sealHead)) {
            MiningTool.mineBlock(bot, sealHead, false).join();
            Thread.sleep(200);
        }
        // Walk out through the entrance (opposite of dig direction)
        Direction exitDir = info.entryDir() != null ? info.entryDir().getOpposite() : null;
        if (exitDir != null) {
            BlockPos exitTarget = sealFeet.offset(exitDir);
            MovementService.MovementPlan exitPlan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT, exitTarget, exitTarget, null, null, null);
            MovementService.execute(bot.getCommandSource(), bot, exitPlan, Boolean.FALSE, true);
            Thread.sleep(600);
        }
        LOGGER.info("Bot {} broke free from cliff shelter", bot.getName().getString());
    }

    /** Break free from a dig-down shelter: mine the cap block, then pillar up. */
    private static void breakFreeDugDown(ServerPlayerEntity bot, ServerWorld world, ShelterInfo info)
            throws InterruptedException {
        BlockPos capPos = info.capPos();
        // Mine the cap block
        if (world.getBlockState(capPos).isSolidBlock(world, capPos)) {
            MiningTool.mineBlock(bot, capPos, false).join();
            Thread.sleep(300);
        }
        LOGGER.info("Bot {} broke cap of dig-down shelter at {}", bot.getName().getString(),
                capPos.toShortString());
        // Pillar up is handled by escapeToSurface()
    }

    /**
     * Break free from a wall-off shelter: mine only the ledger cells that still hold seal-family
     * blocks, then step out through the primary face.
     */
    private static void breakFreeWallOff(ServerPlayerEntity bot, ServerWorld world, ShelterInfo info)
            throws InterruptedException {
        MinecraftServer server = bot.getCommandSource().getServer();
        String name = bot.getName().getString();
        List<BlockPos> ledger = info.placedBlocks();
        WallOffTeardown torn = tearDownWallOffLedger(bot, server, ledger);
        LOGGER.info("Bot {} wall-off teardown: removed {}/{} skipped={}",
                name, torn.removed(), ledger.size(), torn.skipped());
        Direction exitDir = info.entryDir();
        if (exitDir == null || shouldAbortSurvival(bot)) {
            return;
        }
        BlockPos stand = info.interiorPos() != null ? info.interiorPos() : bot.getBlockPos();
        // A skipped cell (glass, chest) still walls the primary face: leave by another sealed face
        // whose cells all came down, if there is one.
        if (faceHasCell(stand, exitDir, torn.skippedCells())) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                if (dir != exitDir && faceHasCell(stand, dir, ledger) && !faceHasCell(stand, dir, torn.skippedCells())) {
                    exitDir = dir;
                    break;
                }
            }
        }
        BlockPos far = stand.offset(exitDir, 2).toImmutable();
        boolean farStandable = Boolean.TRUE.equals(callOnServer(server,
                () -> VoxelJunctionService.isStandable(world, far), WALL_OFF_HOP_TIMEOUT_MS, Boolean.FALSE));
        BlockPos exitTarget = farStandable ? far : stand.offset(exitDir).toImmutable();
        MovementService.MovementPlan exitPlan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT, exitTarget, exitTarget, null, null, null);
        MovementService.execute(bot.getCommandSource(), bot, exitPlan, Boolean.FALSE, true);
        Thread.sleep(600L);
        LOGGER.info("Bot {} broke free from wall-off shelter via {}", name, exitDir.asString());
    }

    /** Whether any of {@code cells} sits in the face column of {@code stand} toward {@code dir}. */
    private static boolean faceHasCell(BlockPos stand, Direction dir, List<BlockPos> cells) {
        BlockPos side = stand.offset(dir);
        for (BlockPos cell : cells) {
            if (cell.getX() == side.getX() && cell.getZ() == side.getZ()) {
                return true;
            }
        }
        return false;
    }

    private static void breakFreeVillageHouse(ServerPlayerEntity bot, ServerWorld world, ShelterInfo info)
            throws InterruptedException {
        BlockPos doorPos = info.capPos();
        Direction interiorDir = info.entryDir();
        if (doorPos == null || interiorDir == null) {
            return;
        }
        BlockPos outside = doorPos.offset(interiorDir.getOpposite());
        MovementService.tryOpenDoorAt(bot, doorPos);
        MovementService.tryTraverseOpenableToward(bot, doorPos, outside, "village-breakfree");
        Thread.sleep(500L);
        LOGGER.info("Bot {} stepped out of village shelter at {}", bot.getName().getString(), doorPos.toShortString());
    }

    /** Generic break-free: try 4 horizontal directions, then mine up. */
    private static void breakFreeGeneric(ServerPlayerEntity bot, ServerWorld world, BlockPos pos)
            throws InterruptedException {
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos exitFeet = pos.offset(dir);
            BlockPos exitHead = exitFeet.up();
            boolean feetSolid = world.getBlockState(exitFeet).isSolidBlock(world, exitFeet);
            boolean headSolid = world.getBlockState(exitHead).isSolidBlock(world, exitHead);
            if (feetSolid || headSolid) {
                // Skip direction if either block is a protected ore the bot can't harvest
                if ((feetSolid && isUnharvestableOre(bot, world.getBlockState(exitFeet)))
                        || (headSolid && isUnharvestableOre(bot, world.getBlockState(exitHead)))) {
                    LOGGER.info("Bot {} break-free skipping {} — protected ore without adequate tool",
                            bot.getName().getString(), dir.asString());
                    continue;
                }
                if (feetSolid) {
                    MiningTool.mineBlock(bot, exitFeet, false).join();
                    Thread.sleep(200);
                }
                if (headSolid) {
                    MiningTool.mineBlock(bot, exitHead, false).join();
                    Thread.sleep(200);
                }
                BlockPos beyondFeet = exitFeet.offset(dir);
                if (world.getBlockState(beyondFeet).isAir()
                        || !world.getBlockState(beyondFeet).isSolidBlock(world, beyondFeet)) {
                    bot.getCommandSource().getServer().execute(() ->
                            FollowMovementService.moveToward(bot, Vec3d.ofCenter(exitFeet), 1.0, true, null));
                    Thread.sleep(800);
                    LOGGER.info("Bot {} broke free from shelter toward {}",
                            bot.getName().getString(), dir.asString());
                    return;
                }
            }
        }
        // Fallback: mine one block up
        BlockPos capBlock = pos.up(2);
        if (world.getBlockState(capBlock).isSolidBlock(world, capBlock)) {
            if (isUnharvestableOre(bot, world.getBlockState(capBlock))) {
                LOGGER.warn("Bot {} break-free: overhead block is protected ore — staying put",
                        bot.getName().getString());
                return;
            }
            MiningTool.mineBlock(bot, capBlock, false).join();
            Thread.sleep(200);
        }
        LOGGER.info("Bot {} generic break-free (mined upward)", bot.getName().getString());
    }

    /** Returns true if the block is a harvest-protected ore the bot's best tool cannot harvest. */
    private static boolean isUnharvestableOre(ServerPlayerEntity bot, net.minecraft.block.BlockState state) {
        if (!net.wcfcarolina13.GameAI.skills.support.MiningHazardDetector.isHarvestProtectedOre(state)) return false;
        net.minecraft.item.ItemStack best = net.wcfcarolina13.PlayerUtils.ToolSelector.selectBestToolForBlock(bot, state);
        return !net.wcfcarolina13.GameAI.skills.support.MiningHazardDetector.canToolHarvest(best, state);
    }

    /**
     * Returns true only when the bot is on an operational open-sky surface tile that is
     * actually usable for pathing rather than merely poking out of a shaft.
     */
    public static boolean isAtSurface(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        return SafePositionService.isRecoveryReadySurface(world, bot.getBlockPos());
    }

    // ── Underground linger decision helpers ─────────────────────────────

    /** Record that a bot just exited a shelter (called from breakFreeFromShelter). */
    public static void noteShelterExit(UUID botUuid, long serverTick) {
        if (botUuid != null) RECENT_SHELTER_EXIT_TICK.put(botUuid, serverTick);
    }

    /** Record that a commander-issued task just completed for this bot. */
    public static void noteTaskCompleted(UUID botId, long serverTick) {
        if (botId != null) TASK_COMPLETED_TICK.put(botId, serverTick);
    }

    /**
     * Central decision: should surface recovery be suppressed for this bot?
     * Evaluates context (player proximity, hunger, tools, time of day, chests, tasks)
     * to decide if the bot should stay underground rather than panic-escaping.
     */
    private static boolean shouldSuppressSurfaceRecovery(ServerPlayerEntity bot, ServerWorld world) {
        UUID botId = bot.getUuid();
        MinecraftServer server = bot.getCommandSource().getServer();
        long tick = server != null ? server.getTicks() : 0L;
        String name = bot.getName().getString();
        int foodLevel = bot.getHungerManager().getFoodLevel();

        // 0. Non-functional position override — if the bot is stuck in a pit (no open sky
        //    AND not near the terrain surface), bypass the entire linger tree. The bot can't
        //    do anything useful from here, so all suppression conditions are moot.
        //    EXCEPT: if the commander is nearby or the bot is near a base, this is likely an
        //    intentional underground location, not a random pit.
        SafePositionService.SurfaceCandidateAssessment posCheck =
                SafePositionService.analyzeSurfaceCandidate(world, bot.getBlockPos());
        int feetY = bot.getBlockY();
        int wideSurfaceY = SafePositionService.wideSurfaceFeetY(world, bot.getBlockX(), bot.getBlockZ());
        int scaffoldCount = countScaffoldBlocks(bot);
        boolean nearBase = BotHomeService.isNearAnyBase(bot, 32.0D);
        // isDay() is always false in fixed-time dimensions (Nether, End): no night rule there.
        boolean hasDayCycle = !world.getDimension().hasFixedTime();
        boolean thundering = world.isThundering();
        UndergroundLingerPolicy.Decision lingerPre = UndergroundLingerPolicy.decide(
                posCheck.openSky(), feetY, wideSurfaceY, hasDayCycle, world.isDay(), thundering,
                scaffoldCount, foodLevel, isInShelter(botId), nearBase);
        // Shelter and night win over the #0 bypass (which used to skip the night check #9).
        if (lingerPre == UndergroundLingerPolicy.Decision.SUPPRESS_SHELTERED) {
            LOGGER.info("Bot {} linger: suppressed — inside own shelter", name);
            return true;
        }
        if (lingerPre == UndergroundLingerPolicy.Decision.SUPPRESS_NIGHT) {
            LOGGER.info("Bot {} linger: suppressed — {}, waiting it out (food={})",
                    name, thundering ? "thunderstorm" : "nighttime", foodLevel);
            return true;
        }
        if (!posCheck.openSky() && !posCheck.nearSurface()) {
            boolean commanderHere = isCommanderNearby(bot, server);
            if (commanderHere || nearBase) {
                LOGGER.info("Bot {} linger: non-functional position but {} — staying put",
                        name, commanderHere ? "commander nearby" : "near base");
            } else {
                LOGGER.info("Bot {} linger: bypassed — stuck at non-functional position (openSky=false nearSurface=false)", name);
                return false;
            }
        }

        // 1. Teleport grace period (already checked earlier, but belt-and-suspenders)
        if (BotEventHandler.isInTeleportGracePeriod(bot)) {
            LOGGER.info("Bot {} linger: suppressed — teleport grace period", name);
            return true;
        }

        // 1b. Post-task grace — commander just finished a task, don't surface immediately
        Long taskCompletedAt = TASK_COMPLETED_TICK.get(botId);
        if (taskCompletedAt != null) {
            if ((tick - taskCompletedAt) < TASK_GRACE_TICKS) {
                LOGGER.info("Bot {} linger: suppressed — task completed {}s ago",
                        name, (tick - taskCompletedAt) / 20);
                return true;
            }
            TASK_COMPLETED_TICK.remove(botId);
        }

        // 2. Active task running — bot is mining/working, don't interrupt
        if (TaskService.hasActiveTask(botId)) {
            LOGGER.info("Bot {} linger: suppressed — active task running", name);
            return true;
        }

        // 3. Commander proximity with edge-detected grace timer.
        //    While commander is nearby (any depth): suppress and reset timers.
        //    When commander leaves: start a grace timer (= linger duration).
        //    Only after that grace timer expires do hunger checks activate.
        boolean commanderNearby = isCommanderNearby(bot, server);
        boolean wasNearby = COMMANDER_WAS_NEARBY.getOrDefault(botId, false);
        COMMANDER_WAS_NEARBY.put(botId, commanderNearby);

        if (commanderNearby) {
            // Commander is here — suppress, reset all timers
            COMMANDER_LEFT_TICK.remove(botId);
            UNDERGROUND_LINGER_START.remove(botId);
            LOGGER.info("Bot {} linger: suppressed — commander nearby and underground", name);
            return true;
        }

        // 4. Critically hungry — override grace periods (shelter-exit, commander-left)
        //    and surface immediately.  Commander-nearby (#3) is still respected so the
        //    bot stays if the player is right there to help.
        if (foodLevel <= 6) {
            LOGGER.info("Bot {} linger: allowing surface recovery — critically hungry (food={})", name, foodLevel);
            UNDERGROUND_LINGER_START.remove(botId);
            COMMANDER_LEFT_TICK.remove(botId);
            return false;
        }

        // 5. Recently exited shelter — don't immediately re-trigger surface recovery
        Long shelterExitTick = RECENT_SHELTER_EXIT_TICK.get(botId);
        if (shelterExitTick != null && (tick - shelterExitTick) < SHELTER_EXIT_GRACE_TICKS) {
            LOGGER.info("Bot {} linger: suppressed — recently exited shelter ({}s ago)",
                    name, (tick - shelterExitTick) / 20);
            return true;
        }

        // Commander not nearby — check if they just left (transition edge)
        if (wasNearby && !commanderNearby) {
            COMMANDER_LEFT_TICK.put(botId, tick);
            LOGGER.info("Bot {} linger: commander left proximity — starting grace timer", name);
        }

        // 6. If commander left recently, enforce grace period before hunger checks
        Long commanderLeftAt = COMMANDER_LEFT_TICK.get(botId);
        if (commanderLeftAt != null) {
            int lingerMinutes = net.wcfcarolina13.Frens.CONFIG.getUndergroundLingerMinutes();
            long graceTicks = lingerMinutes * 60L * 20L;
            if ((tick - commanderLeftAt) < graceTicks) {
                return true; // still in post-commander grace period
            }
            // Grace expired — clear and fall through to hunger checks
            COMMANDER_LEFT_TICK.remove(botId);
        }

        // 7b. Shallow open-sky hole by day with enough scaffold, away from any base — pillar out
        //     (mines overhead with the held tool, never inside a base), no pickaxe needed and no reason to wait just because the bot is fed (#8, #12).
        if (lingerPre == UndergroundLingerPolicy.Decision.ALLOW_RECOVERY) {
            LOGGER.info("Bot {} linger: allow — shallow open-sky hole by day, climbing out (depth={} scaffold={})",
                    name, UndergroundLingerPolicy.depthBelowSurface(feetY, wideSurfaceY), scaffoldCount);
            UNDERGROUND_LINGER_START.remove(botId);
            return false;
        }

        // 8. No tools and NOT near surface with soft blocks — can't mine out, wait for rescue
        boolean hasPickaxe = hasAnyPickaxe(bot);
        if (!hasPickaxe && !isNearSurfaceWithSoftBlocks(bot, world, wideSurfaceY)) {
            LOGGER.info("Bot {} linger: suppressed — no tools, deep underground, waiting for rescue", name);
            return true;
        }

        // 9. Nighttime on surface — stay underground unless critically starving
        if (!world.isDay() || world.isThundering()) {
            if (foodLevel > 3) {
                LOGGER.info("Bot {} linger: suppressed — nighttime, waiting for dawn (food={})", name, foodLevel);
                return true;
            }
        }

        // 10. Commander recently died — linger near death site to guard drops
        if (isCommanderRecentlyDied(bot, server, tick)) {
            LOGGER.info("Bot {} linger: suppressed — commander died recently, guarding drops", name);
            return true;
        }

        // 11. Try to get food from nearby chests before surfacing
        try {
            if (BotMutualAidService.tryImmediateChestFoodRecovery(bot, world)) {
                LOGGER.info("Bot {} linger: suppressed — found food in nearby chest", name);
                UNDERGROUND_LINGER_START.remove(botId); // reset timer since we found food
                return true;
            }
        } catch (Throwable t) {
            LOGGER.debug("Bot {} linger: chest food check failed: {}", name, t.getMessage());
        }

        // 12. Well-fed — no survival urgency, stay underground indefinitely
        if (foodLevel >= 14) {
            LOGGER.info("Bot {} linger: suppressed — well-fed (food={})", name, foodLevel);
            UNDERGROUND_LINGER_START.remove(botId); // no urgency, reset timer
            return true;
        }

        // Moderately hungry (food 7-13) — wait for linger timer to expire
        // (Critically hungry food ≤ 6 is handled earlier as check #4, overriding grace periods.)
        int lingerMinutes = net.wcfcarolina13.Frens.CONFIG.getUndergroundLingerMinutes();
        long lingerTicks = lingerMinutes * 60L * 20L; // minutes → ticks
        Long lingerStart = UNDERGROUND_LINGER_START.get(botId);
        if (lingerStart == null) {
            UNDERGROUND_LINGER_START.put(botId, tick);
            LOGGER.info("Bot {} linger: timer started — moderately hungry (food={}), will surface in {}min",
                    name, foodLevel, lingerMinutes);
            return true;
        }
        if ((tick - lingerStart) < lingerTicks) {
            return true; // still waiting
        }

        // Timer expired — allow surface recovery
        LOGGER.info("Bot {} linger: timer expired after {}min — allowing surface recovery (food={})",
                name, lingerMinutes, foodLevel);
        UNDERGROUND_LINGER_START.remove(botId);
        return false;
    }

    /**
     * Lightweight safety check for hobby/guard surface escape.
     * Enforces critical constraints without the full linger tree.
     */
    public static boolean shouldSuppressHobbyEscape(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) return false;
        MinecraftServer server = bot.getCommandSource() != null
                ? bot.getCommandSource().getServer() : null;
        String name = bot.getName().getString();

        if (isCommanderNearby(bot, server)) {
            LOGGER.info("Bot {} hobby-escape: suppressed — commander nearby", name);
            return true;
        }
        if ((!world.isDay() || world.isThundering()) && bot.getHungerManager().getFoodLevel() > 3) {
            LOGGER.info("Bot {} hobby-escape: suppressed — nighttime (food={})",
                    name, bot.getHungerManager().getFoodLevel());
            return true;
        }
        UUID botId = bot.getUuid();
        long tick = server != null ? server.getTicks() : 0L;
        Long taskDone = TASK_COMPLETED_TICK.get(botId);
        if (taskDone != null && (tick - taskDone) < TASK_GRACE_TICKS) {
            LOGGER.info("Bot {} hobby-escape: suppressed — post-task grace", name);
            return true;
        }
        // Bot is underground (caller already gated on !isAtSurface) AND inside a registered base.
        // Surfacing from here means routing UP through the user's structure — Baritone has no
        // notion of doors as exits, so the staging tile lands on the roof and the escape ladder
        // ends in pillar-mining through walls. Skip the hobby; bot stays put until they're
        // moved outside the base or the situation changes.
        if (server != null
                && BotHomeService.findBaseNearPosition(server, world, bot.getBlockPos()).isPresent()) {
            LOGGER.info("Bot {} hobby-escape: suppressed — underground inside a registered base", name);
            return true;
        }
        return false;
    }

    /** Check if the bot's commander is nearby (any surface state — used for shelter suppression). */
    private static boolean isCommanderNearby(ServerPlayerEntity bot, MinecraftServer server) {
        if (server == null) return false;
        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (ownerUuid == null) return false;
        ServerPlayerEntity commander = server.getPlayerManager().getPlayer(ownerUuid);
        if (commander == null || commander.isRemoved()) return false;
        if (commander.getEntityWorld() != bot.getEntityWorld()) return false;
        int proximityBlocks = net.wcfcarolina13.Frens.CONFIG.getUndergroundProximityBlocks();
        return bot.squaredDistanceTo(commander) <= (double) proximityBlocks * proximityBlocks;
    }

    /** Check if the bot's commander/owner is nearby and also underground. */
    private static boolean isCommanderNearbyAndUnderground(ServerPlayerEntity bot, ServerWorld world, MinecraftServer server) {
        if (server == null) return false;
        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (ownerUuid == null) return false;
        ServerPlayerEntity commander = server.getPlayerManager().getPlayer(ownerUuid);
        if (commander == null || commander.isRemoved()) return false;
        // Must be in the same world
        if (commander.getEntityWorld() != bot.getEntityWorld()) return false;
        int proximityBlocks = net.wcfcarolina13.Frens.CONFIG.getUndergroundProximityBlocks();
        double distSq = bot.squaredDistanceTo(commander);
        if (distSq > (double) proximityBlocks * proximityBlocks) return false;
        // If commander is significantly above the bot, the bot needs to escape
        // to reach them — don't suppress surface recovery.
        if (commander.getY() - bot.getY() >= 3.0) return false;
        // Commander must also be underground (no sky visible)
        return !world.isSkyVisible(commander.getBlockPos().up());
    }

    /** Check if the bot has any pickaxe in inventory. */
    private static boolean hasAnyPickaxe(ServerPlayerEntity bot) {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty()) {
                String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
                if (id.endsWith("_pickaxe")) return true;
            }
        }
        return false;
    }

    /** Check if the bot has any axe in inventory (pickaxes excluded). */
    private static boolean hasAnyAxe(ServerPlayerEntity bot) {
        var inv = bot.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack != null && !stack.isEmpty()) {
                String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
                if (id.endsWith("_axe")) return true;
            }
        }
        return false;
    }

    /**
     * Check if the bot is near the surface with only soft blocks overhead.
     * Allows tool-less breakfree when the bot is just a few blocks below ground
     * with dirt/sand/gravel above (e.g., after tactical shelter with no tools).
     */
    private static boolean isNearSurfaceWithSoftBlocks(ServerPlayerEntity bot, ServerWorld world, int surfaceY) {
        // surfaceY is the wide (median of 9 columns) surface sample: in a 1-wide open shaft the
        // bot's own column heightmap is the shaft floor, which read as depth 0 / "deep underground".
        int botY = bot.getBlockY();
        int delta = surfaceY - botY;
        if (delta < 1 || delta > 5) return false; // must be 1-5 blocks below surface

        // Check all blocks between bot head and surface are soft (mineable by hand quickly)
        for (int y = botY + 2; y <= surfaceY; y++) {
            BlockPos pos = new BlockPos(bot.getBlockX(), y, bot.getBlockZ());
            net.minecraft.block.BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (!isSoftBlock(state)) return false;
        }
        return true;
    }

    /** Blocks that can be mined quickly by hand (no tool required). */
    private static boolean isSoftBlock(net.minecraft.block.BlockState state) {
        net.minecraft.block.Block block = state.getBlock();
        return block == Blocks.DIRT || block == Blocks.GRASS_BLOCK || block == Blocks.SAND
                || block == Blocks.GRAVEL || block == Blocks.CLAY || block == Blocks.SOUL_SAND
                || block == Blocks.SOUL_SOIL || block == Blocks.SNOW_BLOCK || block == Blocks.MOSS_BLOCK
                || block == Blocks.MUD || block == Blocks.MUDDY_MANGROVE_ROOTS
                || block == Blocks.ROOTED_DIRT || block == Blocks.COARSE_DIRT
                || block == Blocks.FARMLAND || block == Blocks.DIRT_PATH
                || state.isIn(BlockTags.LEAVES);
    }

    /** Check if the commander recently died (within last 2 minutes). */
    private static boolean isCommanderRecentlyDied(ServerPlayerEntity bot, MinecraftServer server, long tick) {
        if (server == null) return false;
        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (ownerUuid == null) return false;
        ServerPlayerEntity commander = server.getPlayerManager().getPlayer(ownerUuid);
        // If commander is null or dead, they recently died/disconnected
        if (commander == null) return false; // disconnected, not dead — don't guard
        if (!commander.isAlive()) {
            // Commander is dead — check if they're in the same world and nearby
            if (commander.getEntityWorld() == bot.getEntityWorld()
                    && bot.squaredDistanceTo(commander) < 64.0 * 64.0) {
                return true; // guard their drops
            }
        }
        return false;
    }

    /**
     * Escape to surface using the dedicated system-owned surface recovery path.
     * Evaluates the underground linger decision tree before attempting escape.
     */
    public static void escapeToSurface(ServerPlayerEntity bot, ServerWorld world)
            throws InterruptedException {
        if (shouldAbortSurvival(bot) || world == null) return;
        if (isAtSurface(bot, world)) return; // already at surface

        // Evaluate the full underground linger decision tree
        if (shouldSuppressSurfaceRecovery(bot, world)) {
            return;
        }

        LOGGER.info("Bot {} underground — launching surface recovery", bot.getName().getString());

        net.wcfcarolina13.GameAI.skills.SkillExecutionResult result =
                SurfaceRecoveryService.runSurfaceRecovery(bot, world);

        if (shouldAbortSurvival(bot)) {
            return;
        }

        long attemptTick = bot.getCommandSource().getServer() != null ? bot.getCommandSource().getServer().getTicks() : 0L;
        if (result != null && result.success()) {
            SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
            SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
            LOGGER.info("Bot {} reached surface via dedicated surface recovery", bot.getName().getString());
            BotUndergroundSurvivalService.recordRecoveryAttempt(bot, true, countScaffoldBlocks(bot), world.isDay(), attemptTick);
        } else {
            SURFACE_RECOVERY_FAILURE_TICK.put(bot.getUuid(), attemptTick);
            SURFACE_RECOVERY_FAILURE_REASON.put(bot.getUuid(), result != null ? String.valueOf(result.message()) : "null result");
            LOGGER.warn("Bot {} ascent failed: {}",
                    bot.getName().getString(), result != null ? result.message() : "null result");
            BotUndergroundSurvivalService.recordRecoveryAttempt(bot, false, countScaffoldBlocks(bot), world.isDay(), attemptTick);
        }
    }

    public static boolean descendFromSurfacePillar(ServerPlayerEntity bot,
                                                   ServerWorld world,
                                                   List<BlockPos> pillarBlocks,
                                                   BlockPos stagingPos,
                                                   String label) {
        if (bot == null || world == null || pillarBlocks == null || pillarBlocks.isEmpty()) {
            return false;
        }

        LOGGER.info("{}: descending pillar blocks={} staging={}",
                label,
                pillarBlocks.size(),
                stagingPos != null ? stagingPos.toShortString() : "none");

        BotActions.stop(bot);
        List<BlockPos> ordered = new ArrayList<>(pillarBlocks);
        ordered.sort(Comparator.comparingInt(BlockPos::getY).reversed());

        BlockPos preserve = chooseStepOffSupport(ordered, stagingPos);
        LOGGER.info("{}: preserveSupport={}", label, preserve != null ? preserve.toShortString() : "none");

        if (!teardownPillarGradually(bot, world, ordered, preserve, label)) {
            // Best-effort cleanup of the preserved support block even on abort/removal.
            if (preserve != null && bot != null && !bot.isRemoved()) {
                ScaffoldService.teardownScaffolds(bot, world, List.of(preserve), Collections.emptySet());
            }
            return false;
        }

        boolean moved = false;
        if (stagingPos != null) {
            moved = moveToOperationalSurface(bot, world, stagingPos, label + "-stepoff");
            if (!moved) {
                SafePositionService.SurfaceStagingCandidate retry =
                        SafePositionService.findBestSurfaceStaging(world, bot.getBlockPos(), 6, true);
                if (retry != null && !retry.pos().equals(stagingPos)) {
                    LOGGER.info("{}: retry staging {} score={} {}",
                            label,
                            retry.pos().toShortString(),
                            retry.score(),
                            SafePositionService.summarizeSurfaceAssessment(retry.assessment()));
                    moved = moveToOperationalSurface(bot, world, retry.pos(), label + "-retry-stepoff");
                }
            }
        }
        if (preserve != null) {
            int cleaned = ScaffoldService.teardownScaffolds(bot, world, List.of(preserve), Collections.emptySet());
            LOGGER.info("{}: cleanup support {} removed={}", label, preserve.toShortString(), cleaned);
            waitForOnGround(bot, 800L);
        }

        boolean ready = isAtSurface(bot, world);
        if (!ready) {
            SafePositionService.SurfaceCandidateAssessment assessment =
                    SafePositionService.analyzeSurfaceCandidate(world, bot.getBlockPos());
            LOGGER.info("{}: post-descent surface check failed at {} {}",
                    label,
                    bot.getBlockPos().toShortString(),
                    SafePositionService.summarizeSurfaceAssessment(assessment));
        } else {
            LOGGER.info("{}: ready after descent moved={}", label, moved);
        }
        return ready;
    }

    /**
     * Ensures bot is on an operational open-sky surface tile. The recovery ladder is:
     * nearby operational staging -> collect_dirt ascent -> scaffold pillar recovery.
     *
     * @return true if the bot is at the surface after this call
     */
    /**
     * Like {@link #ensureAtSurface(ServerPlayerEntity, ServerWorld)} but skips the
     * underground linger decision tree.  Used by the hobby system where the bot
     * simply needs to reach surface to start a hobby — not a survival decision.
     */
    public static boolean ensureAtSurfaceForHobby(ServerPlayerEntity bot, ServerWorld world) {
        return ensureAtSurface(bot, world, true);
    }

    public static boolean ensureAtSurface(ServerPlayerEntity bot, ServerWorld world) {
        return ensureAtSurface(bot, world, false);
    }

    private static boolean ensureAtSurface(ServerPlayerEntity bot, ServerWorld world,
                                           boolean skipLingerCheck) {
        if (bot == null || world == null) {
            return false;
        }
        boolean owner = beginSurvivalAction(bot.getUuid(), InterruptedSurvivalAction.SURFACE_RECOVERY);
        try {

        if (BotWaterEscapeService.isInWater(bot)) {
            BlockPos shore = BotWaterEscapeService.findNearestShoreStand(bot, 12);
            if (shore != null) {
                LOGGER.info("ensureAtSurface: {} seeking dry land first at {}",
                        bot.getName().getString(), shore.toShortString());
                if (moveTowardLocalShelterAnchor(bot, world, shore, "surface-dryland")) {
                    SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
                    SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
                    if (isAtSurface(bot, world) || !BotWaterEscapeService.isInWater(bot)) {
                        net.wcfcarolina13.GameAI.souls.SoulEventObserver.onSelfRescue(bot.getUuid(), "surface-recovery");
                        return true;
                    }
                }
            }
        }

        if (logOperationalSurfaceState(bot, world, "ensureAtSurface:current")) {
            return true;
        }

        // Suppress surface recovery based on context:
        // - Hobby/guard callers (skipLingerCheck=true): lightweight safety check only
        //   (commander nearby, nighttime, post-task grace).
        // - Idle/survival callers: full linger decision tree (hunger timers, chest food, etc.).
        // - Skill callers (hasActiveTask=true): bypass entirely — they need staging/pillar
        //   paths and have their own suppression inside escapeToSurface().
        if (skipLingerCheck) {
            if (shouldSuppressHobbyEscape(bot, world)) {
                LOGGER.info("ensureAtSurface: {} suppressed by hobby-escape safety check",
                        bot.getName().getString());
                return false;
            }
        } else if (!TaskService.hasActiveTask(bot.getUuid())
                && shouldSuppressSurfaceRecovery(bot, world)) {
            LOGGER.info("ensureAtSurface: {} suppressed by linger decision tree",
                    bot.getName().getString());
            return false;
        }

        AtomicBoolean lock = SURFACE_RECOVERY_LOCK.computeIfAbsent(bot.getUuid(), ignored -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            LOGGER.info("ensureAtSurface: {} recovery already in progress, skipping duplicate request",
                    bot.getName().getString());
            return false;
        }

        try {
            SafePositionService.SurfaceStagingCandidate nearby =
                    SafePositionService.findBestSurfaceStaging(world, bot.getBlockPos(), 10, true);
            if (nearby != null) {
                LOGGER.info("ensureAtSurface: {} nearby staging {} score={} {}",
                        bot.getName().getString(),
                        nearby.pos().toShortString(),
                        nearby.score(),
                        SafePositionService.summarizeSurfaceAssessment(nearby.assessment()));
                if (moveToOperationalSurface(bot, world, nearby.pos(), "surface-staging")) {
                    LOGGER.info("ensureAtSurface: {} reached operational surface via nearby staging",
                            bot.getName().getString());
                    net.wcfcarolina13.GameAI.souls.SoulEventObserver.onSelfRescue(bot.getUuid(), "surface-recovery");
                    return true;
                }
                // Movement failed — pillar up first (fast escape from surface pits/overhangs)
                ensureScaffoldReachable(bot, 2);
                if (countScaffoldBlocks(bot) >= 2 && tryPillarOperationalSurfaceRecovery(bot, world)) {
                    SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
                    SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
                    LOGGER.info("ensureAtSurface: {} reached operational surface via pillar recovery",
                            bot.getName().getString());
                    net.wcfcarolina13.GameAI.souls.SoulEventObserver.onSelfRescue(bot.getUuid(), "surface-recovery");
                    return true;
                }
                // Pillar didn't work — try building scaffold steps toward the staging area
                if (tryStepBuildToStaging(bot, world, nearby.pos())) {
                    LOGGER.info("ensureAtSurface: {} reached operational surface via step-building",
                            bot.getName().getString());
                    SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
                    SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
                    net.wcfcarolina13.GameAI.souls.SoulEventObserver.onSelfRescue(bot.getUuid(), "surface-recovery");
                    return true;
                }
            } else {
                LOGGER.info("ensureAtSurface: {} found no nearby operational staging before ascent",
                        bot.getName().getString());
            }

            // Try pillar escape before heavy ascent — fast for shallow surface pits.
            ensureScaffoldReachable(bot, 2);
            if (countScaffoldBlocks(bot) >= 2 && tryPillarOperationalSurfaceRecovery(bot, world)) {
                SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
                SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
                LOGGER.info("ensureAtSurface: {} reached operational surface via pillar recovery",
                        bot.getName().getString());
                net.wcfcarolina13.GameAI.souls.SoulEventObserver.onSelfRescue(bot.getUuid(), "surface-recovery");
                return true;
            }

            try {
                escapeToSurface(bot, world);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (logOperationalSurfaceState(bot, world, "ensureAtSurface:post-ascent")) {
                SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
                SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
                net.wcfcarolina13.GameAI.souls.SoulEventObserver.onSelfRescue(bot.getUuid(), "surface-recovery");
                return true;
            }

            if (tryPillarOperationalSurfaceRecovery(bot, world)) {
                SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
                SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
                LOGGER.info("ensureAtSurface: {} reached operational surface via pillar recovery",
                        bot.getName().getString());
                net.wcfcarolina13.GameAI.souls.SoulEventObserver.onSelfRescue(bot.getUuid(), "surface-recovery");
                return true;
            }

            SafePositionService.SurfaceCandidateAssessment assessment =
                    SafePositionService.analyzeSurfaceCandidate(world, bot.getBlockPos());
            LOGGER.warn("ensureAtSurface: {} failed at {} {}",
                    bot.getName().getString(),
                    bot.getBlockPos().toShortString(),
                    SafePositionService.summarizeSurfaceAssessment(assessment));
            long nowTick = bot.getCommandSource().getServer() != null ? bot.getCommandSource().getServer().getTicks() : 0L;
            SURFACE_RECOVERY_FAILURE_TICK.put(bot.getUuid(), nowTick);
            SURFACE_RECOVERY_FAILURE_REASON.put(bot.getUuid(), "no_operational_surface");
            BotEmergencyRescueService.tryEmergencyRescue(bot, world, "surface-failure");
            return false;
        } finally {
            lock.set(false);
        }
        } finally {
            endSurvivalAction(bot.getUuid(), InterruptedSurvivalAction.SURFACE_RECOVERY, owner);
        }
    }


    private static boolean shouldBreakShelterForStarvation(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null || !HealingService.isStarving(bot)) {
            return false;
        }
        if (HealingService.autoEat(bot)) {
            return false;
        }
        return !BotMutualAidService.tryImmediateChestFoodRecovery(bot, world);
    }

    private static boolean beginSurvivalAction(UUID botId, InterruptedSurvivalAction action) {
        if (botId == null || action == null) {
            return false;
        }
        return CURRENT_SURVIVAL_ACTION.putIfAbsent(botId, action) == null;
    }

    private static void endSurvivalAction(UUID botId, InterruptedSurvivalAction action, boolean owner) {
        if (!owner || botId == null || action == null) {
            return;
        }
        CURRENT_SURVIVAL_ACTION.remove(botId, action);
    }

    private static boolean shouldAbortSurvival(ServerPlayerEntity bot) {
        return bot == null
                || bot.isRemoved()
                || Thread.currentThread().isInterrupted()
                || TaskService.isServerStopping()
                || SkillManager.shouldAbortSkill(bot);
    }


    /**
     * Attempt to build scaffold steps toward a nearby staging area that the bot couldn't walk to.
     * Places blocks at wall faces to create step-ups, repeating until the bot reaches the staging Y
     * or runs out of blocks/attempts. For shallow depressions (2-6 block Y gap) where movement fails
     * on steep/irregular terrain.
     */
    private static boolean tryStepBuildToStaging(ServerPlayerEntity bot, ServerWorld world, BlockPos staging) {
        if (bot == null || world == null || staging == null) return false;
        int scaffoldCount = countScaffoldBlocks(bot);
        if (scaffoldCount < 2) {
            LOGGER.info("ensureAtSurface: {} step-build skipped (only {} scaffold blocks)",
                    bot.getName().getString(), scaffoldCount);
            return false;
        }
        int yGap = staging.getY() - bot.getBlockPos().getY();
        if (yGap < 1 || yGap > 6) {
            LOGGER.info("ensureAtSurface: {} step-build skipped (Y gap {} not in range 1-6)",
                    bot.getName().getString(), yGap);
            return false;
        }
        MinecraftServer server = bot.getCommandSource() != null ? bot.getCommandSource().getServer() : null;
        if (server == null) return false;

        LOGGER.info("ensureAtSurface: {} step-build starting toward {} (Y gap={})",
                bot.getName().getString(), staging.toShortString(), yGap);
        int maxSteps = Math.min(6, scaffoldCount);
        int placed = 0;
        for (int step = 0; step < maxSteps; step++) {
            BlockPos botPos = bot.getBlockPos();
            if (botPos.getY() >= staging.getY()) {
                LOGGER.info("ensureAtSurface: {} step-build reached staging Y after {} placed blocks",
                        bot.getName().getString(), placed);
                break;
            }
            // Find the best direction to place a step (toward the staging area)
            Direction bestDir = null;
            double bestDist = Double.MAX_VALUE;
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos stepTarget = botPos.offset(dir);
                // Need a position where we can place a block at current Y (bot's feet level)
                BlockState targetState = world.getBlockState(stepTarget);
                if (!targetState.isAir() && !targetState.isReplaceable()) continue;
                // Ensure 2-high clearance above the placed block
                BlockState aboveState = world.getBlockState(stepTarget.up());
                BlockState headState = world.getBlockState(stepTarget.up(2));
                if ((!aboveState.isAir() && aboveState.blocksMovement())
                        || (!headState.isAir() && headState.blocksMovement())) continue;
                // Prefer directions that bring us closer to staging
                double dist = stepTarget.getSquaredDistance(staging);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestDir = dir;
                }
            }
            if (bestDir == null) {
                LOGGER.info("ensureAtSurface: {} step-build no suitable placement at {}",
                        bot.getName().getString(), botPos.toShortString());
                break;
            }
            BlockPos placePos = botPos.offset(bestDir);
            boolean ok = BotActions.placeBlockAt(bot, placePos, Direction.UP,
                    new java.util.ArrayList<>(ScaffoldService.SCAFFOLD_BLOCKS));
            if (!ok) {
                LOGGER.info("ensureAtSurface: {} step-build placement failed at {}",
                        bot.getName().getString(), placePos.toShortString());
                break;
            }
            placed++;
            // Jump up onto the placed block
            BlockPos jumpTarget = placePos.up();
            server.execute(() -> {
                LookController.faceBlock(bot, jumpTarget);
                BotActions.autoJumpIfNeeded(bot);
                BotActions.applyMovementInput(bot, Vec3d.ofCenter(jumpTarget), 0.22D);
            });
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (placed > 0) {
            LOGGER.info("ensureAtSurface: {} step-build placed {} blocks, now at Y={}",
                    bot.getName().getString(), placed, bot.getBlockPos().getY());
            // Try moving to staging now that we're higher
            if (moveToOperationalSurface(bot, world, staging, "step-build-staging")) {
                return true;
            }
        }
        return isAtSurface(bot, world);
    }

    private static boolean tryPillarOperationalSurfaceRecovery(ServerPlayerEntity bot, ServerWorld world) {
        // Kept despite the callers' own ensureScaffoldReachable: the post-ascent call site runs after
        // escapeToSurface, which can consume the direct scaffold those earlier pulls provided.
        ensureScaffoldReachable(bot, 2);
        int scaffoldCount = countScaffoldBlocks(bot);
        if (scaffoldCount <= 0) {
            LOGGER.info("ensureAtSurface: {} pillar recovery skipped (no scaffold blocks)",
                    bot.getName().getString());
            return false;
        }

        int pillarSteps = Math.min(6, scaffoldCount);
        LOGGER.info("ensureAtSurface: {} pillar recovery steps={}",
                bot.getName().getString(), pillarSteps);

        ScaffoldService.PillarResult pillarResult = ScaffoldService.pillarUpDetailed(bot, pillarSteps);
        List<BlockPos> placed = pillarResult.placed();
        if (placed.isEmpty()) {
            String reason = pillarResult.reason() == null || pillarResult.reason().isEmpty()
                    ? "unknown" : pillarResult.reason();
            LOGGER.info("ensureAtSurface: {} pillar recovery placed no blocks reason={} steps={}",
                    bot.getName().getString(), reason, pillarSteps);
            long nowTick = bot.getCommandSource().getServer() != null
                    ? bot.getCommandSource().getServer().getTicks() : 0L;
            SURFACE_RECOVERY_FAILURE_TICK.put(bot.getUuid(), nowTick);
            SURFACE_RECOVERY_FAILURE_REASON.put(bot.getUuid(), "pillar:" + reason);
            return false;
        }

        try {
            SafePositionService.SurfaceStagingCandidate staging =
                    SafePositionService.findBestSurfaceStaging(world, bot.getBlockPos(), 8, true);
            if (staging == null) {
                LOGGER.info("ensureAtSurface: {} found no operational staging from pillar top",
                        bot.getName().getString());
                descendFromSurfacePillar(bot, world, placed, null, "surface-pillar");
                return false;
            }
            LOGGER.info("ensureAtSurface: {} pillar staging {} score={} {}",
                    bot.getName().getString(),
                    staging.pos().toShortString(),
                    staging.score(),
                    SafePositionService.summarizeSurfaceAssessment(staging.assessment()));
            return descendFromSurfacePillar(bot, world, placed, staging.pos(), "surface-pillar");
        } finally {
            if (!bot.isOnGround()) {
                waitForOnGround(bot, 1_200L);
            }
        }
    }

    private static boolean moveTowardLocalShelterAnchor(ServerPlayerEntity bot,
                                                        ServerWorld world,
                                                        BlockPos target,
                                                        String label) {
        if (bot == null || world == null || target == null) {
            return false;
        }
        Optional<MovementService.MovementPlan> plan =
                MovementService.planLootApproach(bot, target, MovementService.MovementOptions.skillLoot());
        if (plan.isPresent()) {
            MovementService.MovementResult result = MovementService.execute(
                    bot.getCommandSource(),
                    bot,
                    plan.get(),
                    Boolean.FALSE,
                    true,
                    true,
                    false
            );
            if (result != null && (result.success() || bot.getBlockPos().getSquaredDistance(target) <= 4.0D)) {
                return true;
            }
        }
        return MovementService.nudgeTowardUntilClose(bot, target, 2.5D, 4_000L, 0.20D, label);
    }

    private static boolean moveToOperationalSurface(ServerPlayerEntity bot,
                                                    ServerWorld world,
                                                    BlockPos target,
                                                    String label) {
        if (bot == null || world == null || target == null) {
            return false;
        }
        boolean targetOperational = SafePositionService.isOperationalSurface(world, target);
        Optional<MovementService.MovementPlan> plan =
                MovementService.planLootApproach(bot, target, MovementService.MovementOptions.skillLoot());
        boolean moved = false;
        if (plan.isPresent()) {
            MovementService.MovementResult result = MovementService.execute(
                    bot.getCommandSource(),
                    bot,
                    plan.get(),
                    Boolean.FALSE,
                    true,
                    true,
                    false
            );
            double targetDistSq = bot.getBlockPos().getSquaredDistance(target);
            moved = result != null && result.success() && targetDistSq <= 2.25D;
            LOGGER.info("{}: movement success={} detail={} dist={} targetOperational={} bot={}",
                    label,
                    moved,
                    result != null ? result.detail() : "null",
                    String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(targetDistSq)),
                    targetOperational,
                    bot.getBlockPos().toShortString());
        }
        boolean ready = isAtSurface(bot, world);
        if (!moved || !ready) {
            boolean nudged = MovementService.nudgeTowardUntilClose(bot, target, 1.25D, 4_500L, 0.20D, label + "-exact");
            ready = isAtSurface(bot, world);
            moved = nudged && bot.getBlockPos().getSquaredDistance(target) <= 2.25D;
            LOGGER.info("{}: exact nudge success={} ready={} dist={} bot={}",
                    label,
                    moved,
                    ready,
                    String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(bot.getBlockPos().getSquaredDistance(target))),
                    bot.getBlockPos().toShortString());
        }
        if (!ready && targetOperational) {
            SafePositionService.SurfaceStagingCandidate nearby =
                    SafePositionService.findBestSurfaceStaging(world, target, 2, true);
            if (nearby != null && !nearby.pos().equals(target)) {
                boolean redirected = MovementService.nudgeTowardUntilClose(bot, nearby.pos(), 1.25D, 3_000L, 0.20D, label + "-redirect");
                ready = isAtSurface(bot, world);
                LOGGER.info("{}: redirect success={} ready={} target={} bot={}",
                        label,
                        redirected,
                        ready,
                        nearby.pos().toShortString(),
                        bot.getBlockPos().toShortString());
            }
        }
        return ready;
    }

    private static BlockPos chooseStepOffSupport(List<BlockPos> ordered, BlockPos stagingPos) {
        if (ordered == null || ordered.isEmpty()) {
            return null;
        }
        if (stagingPos == null) {
            return null;
        }
        BlockPos best = null;
        for (BlockPos pos : ordered) {
            if (pos != null && pos.getY() < stagingPos.getY()) {
                if (best == null || pos.getY() > best.getY()) {
                    best = pos;
                }
            }
        }
        return best;
    }

    private static boolean teardownPillarGradually(ServerPlayerEntity bot,
                                                   ServerWorld world,
                                                   List<BlockPos> ordered,
                                                   BlockPos preserve,
                                                   String label) {
        for (BlockPos pos : ordered) {
            if (pos == null) {
                continue;
            }
            if (TaskService.isAbortRequested(bot != null ? bot.getUuid() : null) || Thread.currentThread().isInterrupted()) {
                BotActions.stop(bot);
                return false;
            }
            if (preserve != null && preserve.equals(pos)) {
                continue;
            }
            int removed = ScaffoldService.teardownScaffolds(bot, world, List.of(pos), Collections.emptySet());
            LOGGER.info("{}: remove {} removed={}", label, pos.toShortString(), removed);
            waitForGroundedDescent(bot, pos.getY(), 1_500L);
        }
        waitForGroundedDescent(bot, preserve != null ? preserve.getY() + 1 : Integer.MIN_VALUE, 2_000L);
        return bot != null && !bot.isRemoved();
    }

    private static void waitForGroundedDescent(ServerPlayerEntity bot, int targetFeetY, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (bot != null && !bot.isRemoved() && (System.currentTimeMillis() - start) < timeoutMs) {
            if (TaskService.isAbortRequested(bot.getUuid()) || Thread.currentThread().isInterrupted()) {
                BotActions.stop(bot);
                return;
            }
            boolean atOrBelowTarget = targetFeetY == Integer.MIN_VALUE || bot.getBlockY() <= targetFeetY;
            if (bot.isOnGround() && atOrBelowTarget) {
                BotActions.stop(bot);
                bot.fallDistance = 0.0F;
                return;
            }
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                BotActions.stop(bot);
                return;
            }
        }
        if (bot != null) {
            BotActions.stop(bot);
            bot.fallDistance = 0.0F;
        }
    }

    private static boolean logOperationalSurfaceState(ServerPlayerEntity bot, ServerWorld world, String label) {
        SafePositionService.SurfaceCandidateAssessment assessment =
                SafePositionService.analyzeSurfaceCandidate(world, bot.getBlockPos());
        // Use strict operational check (requires openSky + nearSurface) — the lenient
        // isRecoveryReadySurface fallback was returning "ready" for bots in pits with
        // overhead cover, preventing ensureAtSurface from attempting pillar/step-build.
        // A forest floor under leaves is surface too: only leaves overhead counts like open sky,
        // otherwise recovery pillars the bot up into the canopy.
        BlockPos feet = bot.getBlockPos();
        boolean canopyOnly = !assessment.openSky()
                && SurfaceReadinessPolicy.canopyOnly(
                        world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()),
                        feet.getY());
        boolean ready = SurfaceReadinessPolicy.isOperational(
                assessment.openSky(),
                canopyOnly,
                assessment.standable(),
                assessment.nearSurface(),
                assessment.cardinalStandableNeighbors(),
                assessment.totalStandableNeighbors(),
                assessment.steepDropNeighbors(),
                assessment.blockedCardinals());
        LOGGER.info("{}: {} pos={} {}{}",
                label,
                ready ? "ready" : "not-ready",
                feet.toShortString(),
                SafePositionService.summarizeSurfaceAssessment(assessment),
                canopyOnly ? " canopyOnly=true" : "");
        return ready;
    }

    /**
     * Direct-slot scaffold is what the pillar can actually place, so before giving up for lack of
     * scaffold we pull bundled scaffold into direct slots. Safe from worker threads:
     * {@link BundleService#reachFirst} does the server-thread hop internally.
     *
     * @param needed how many scaffold blocks the caller wants available directly
     */
    private static void ensureScaffoldReachable(ServerPlayerEntity bot, int needed) {
        if (bot == null) {
            return;
        }
        java.util.function.Predicate<ItemStack> isScaffold =
                stack -> !stack.isEmpty() && ScaffoldService.SCAFFOLD_BLOCKS.contains(stack.getItem());
        int direct = net.wcfcarolina13.PlayerUtils.InventoryIterator.countDirect(bot, isScaffold);
        int bundled = net.wcfcarolina13.PlayerUtils.InventoryIterator.count(bot, isScaffold) - direct;
        int extractions = net.wcfcarolina13.PlayerUtils.BundleReachPolicy.extractionsNeeded(needed, direct, bundled);
        if (extractions <= 0) {
            return;
        }
        int pulled = 0;
        for (int i = 0; i < extractions; i++) {
            if (!BundleService.reachFirst(bot, isScaffold)) {
                break;
            }
            pulled++;
        }
        if (pulled > 0) {
            LOGGER.info("ensureAtSurface: {} pulled {} scaffold stack(s) out of bundles for pillar recovery",
                    bot.getName().getString(), pulled);
        }
    }

    static int countScaffoldBlocks(ServerPlayerEntity bot) {
        if (bot == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && ScaffoldService.SCAFFOLD_BLOCKS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void waitForOnGround(ServerPlayerEntity bot, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (bot != null && !bot.isOnGround() && (System.currentTimeMillis() - start) < timeoutMs) {
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Increment generation counter — invalidates all prior shelter threads for this bot. */
    private static long incrementGeneration(UUID botId) {
        return SHELTER_GENERATION.merge(botId, 1L, Long::sum);
    }

    /** Get current generation for a bot. */
    private static long getGeneration(UUID botId) {
        return SHELTER_GENERATION.getOrDefault(botId, 0L);
    }

    /** Returns true if the shelter thread is stale (bot died/respawned since thread started). */
    private static boolean isStaleShelter(ServerPlayerEntity bot, long startGen) {
        return !bot.isAlive() || bot.isRemoved() || getGeneration(bot.getUuid()) != startGen;
    }

    /** Clear flee state for a bot (on death, respawn, or mode change). */
    public static void reset(UUID botId) {
        FLEE_STATES.remove(botId);
        SHELTER_ACTIVE.remove(botId);
        SHELTER_COOLDOWN.remove(botId);
        AtomicBoolean lock = SHELTER_LOCK.get(botId);
        if (lock != null) lock.set(false);
        incrementGeneration(botId);
    }
}
