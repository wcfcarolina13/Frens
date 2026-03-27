package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.wcfcarolina13.GameAI.services.SkillResumeService;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    public enum ShelterType { CLIFF, DIG_DOWN, VILLAGE_HOUSE, JOIN_ENCLOSED }

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

    /** Metadata for an active tactical shelter. */
    public record ShelterInfo(long enteredTick,
                              ShelterType type,
                              BlockPos capPos,
                              Direction entryDir,
                              BlockPos interiorPos,
                              long lastValidatedTick,
                              ShelterInvalidReason lastValidationReason) {}

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
    /** Grace period after shelter exit before surface recovery can trigger (60 seconds). */
    private static final long SHELTER_EXIT_GRACE_TICKS = 1200L;

    /** Solid blocks suitable for sealing shelter entrances (mined from stone/dirt). */
    private static final List<Item> SEAL_BLOCKS = List.of(
            Items.COBBLESTONE, Items.STONE, Items.DIRT, Items.COBBLED_DEEPSLATE,
            Items.DEEPSLATE, Items.GRANITE, Items.DIORITE, Items.ANDESITE,
            Items.SANDSTONE, Items.RED_SANDSTONE, Items.NETHERRACK,
            Items.TUFF, Items.CALCITE, Items.BASALT, Items.GRAVEL, Items.SAND,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
            Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
            Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS);

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
            if (!assessment.valid()) {
                SHELTER_ACTIVE.remove(bot.getUuid());
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
                    ShelterInvalidReason.VALID));
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

    public static String getRecentSurfaceRecoveryFailureReason(UUID botId) {
        return botId == null ? null : SURFACE_RECOVERY_FAILURE_REASON.get(botId);
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
            case SURFACE_RECOVERY -> ensureAtSurface(bot, world);
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
        if (mode != BotEventHandler.Mode.IDLE) return false;
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
        }

        // Skip shelter on peaceful difficulty — no hostile mobs spawn
        if (bot.getEntityWorld() instanceof ServerWorld world
                && world.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) { lock.set(false); return false; }

        // Must not be near a base (has somewhere safe to go)
        BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
        if (state != null && state.baseTarget != null) { lock.set(false); return false; }

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
                runEmergencyTacticChain(bot, gen, false, true);
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

        boolean phantomsPresent = hostiles.stream()
                .anyMatch(e -> e.getType() == EntityType.PHANTOM);
        boolean hasBlocks = hasPlaceableBlocks(bot, 6);

        LOGGER.info("Bot {} evaluating emergency tactics (hostiles={}, hp={}/{}, phantoms={}, blocks={})",
                bot.getName().getString(), hostiles.size(),
                String.format("%.1f", bot.getHealth()),
                String.format("%.1f", bot.getMaxHealth()),
                phantomsPresent, hasBlocks);

        final long gen = getGeneration(bot.getUuid());
        Thread t = new Thread(() -> runEmergencyTacticChain(bot, gen, phantomsPresent, hasBlocks),
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
    private static void runEmergencyTacticChain(ServerPlayerEntity bot, long gen,
                                                 boolean phantomsPresent, boolean hasBlocks) {
        // 1. Pillar up — DISABLED: ScaffoldService placement consistently fails
        //    during emergency context (bot moving/being hit). Needs investigation.

        if (isStaleShelter(bot, gen)) return;

        if (bot.getEntityWorld() instanceof ServerWorld dryWorld
                && prioritizeDryLand(bot, dryWorld, "night-shelter-water", 12, gen)) {
            if (isStaleShelter(bot, gen)) return;
        }

        if (bot.getEntityWorld() instanceof ServerWorld villageWorld) {
            if (tryVillageHouseShelter(bot, villageWorld, gen)) {
                return;
            }
        }

        // 2. Dig into cliff face — most reliable: mine in, seal entrance, fully enclosed.
        if (bot.getEntityWorld() instanceof ServerWorld world3) {
            net.minecraft.util.math.Direction cliffDir = findNearbyCliffFace(world3, bot.getBlockPos(), 6);
            if (cliffDir != null) {
                LOGGER.info("Bot {} trying emergency cliff-dig {}",
                        bot.getName().getString(), cliffDir.asString());
                emergencyCliffDig(bot, cliffDir, gen);
                return;
            }
        }

        // 3. Dig straight down — always available, self-supplying blocks
        BlockPos digSpot = bot.getBlockPos();
        LOGGER.info("Bot {} trying emergency dig-down at {}",
                bot.getName().getString(), digSpot.toShortString());
        emergencyDigDown(bot, digSpot, gen);

        // 4. Wall-off — DISABLED: detection finds false positives (bot's own placed blocks,
        //    partial shelters) and reports success without actually protecting the bot.
        //    Needs stricter shelter detection before re-enabling.
    }

    /**
     * Emergency pillar up 10 blocks using ScaffoldService, then wait on top
     * to eat and heal while hostiles lose interest below.
     */
    private static void emergencyPillarUp(ServerPlayerEntity bot) {
        try {
            // Must fully stop movement before pillaring — the scaffold placement
            // needs the bot to be stationary and centered on a block.
            bot.getCommandSource().getServer().execute(() -> {
                bot.setSprinting(false);
                bot.setVelocity(0, bot.getVelocity().y, 0);
                bot.velocityDirty = true;
            });
            Thread.sleep(500); // longer pause to fully stabilize position

            ScaffoldService.pillarUp(bot, 10, true);

            // On top — eat food and wait for threats to thin.
            Thread.sleep(2000);
            // Try to eat if we have food (the HealingService/HungerService will handle this
            // once the bot returns to the normal tick loop and is no longer in combat).
            LOGGER.info("Bot {} reached pillar top, waiting for safety",
                    bot.getName().getString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Emergency pillar failed for {}: {}", bot.getName().getString(), e.getMessage());
        }
    }

    /**
     * Emergency dig-down: mine 3 blocks straight down at current position,
     * then cap the hole with a block for overhead protection.
     */
    private static void emergencyDigDown(ServerPlayerEntity bot, BlockPos digPos, long gen) {
        try {
            if (isStaleShelter(bot, gen)) return;
            // Stop moving before digging.
            bot.getCommandSource().getServer().execute(() -> {
                bot.setSprinting(false);
                bot.setVelocity(0, bot.getVelocity().y, 0);
                bot.velocityDirty = true;
            });
            Thread.sleep(300);

            // Mine 3 blocks straight down.
            int blocksMined = 0;
            for (int depth = 0; depth < 3; depth++) {
                if (isStaleShelter(bot, gen)) return;
                BlockPos below = digPos.down(depth + 1);
                if (bot.getEntityWorld().getBlockState(below).isAir()) continue;
                MiningTool.mineBlock(bot, below, false).join();
                blocksMined++;
                Thread.sleep(200);
            }

            if (blocksMined == 0) {
                LOGGER.info("Bot {} dig-down: nothing to mine at {}", bot.getName().getString(),
                        digPos.toShortString());
                return;
            }

            if (isStaleShelter(bot, gen)) return;

            // Wait to fall into the hole and auto-collect mined block drops.
            // Block drops appear at the mined block's position and take a moment to
            // settle and be picked up. Longer wait = more reliable collection.
            Thread.sleep(1500);

            if (isStaleShelter(bot, gen)) return;

            // Cap the hole at the original ground level (digPos).
            // The bot has fallen into the hole, so digPos is now above.
            // Try digPos first, then lower positions closer to the bot.
            BlockPos[] capPlacedAt = {null};
            MinecraftServer server = bot.getCommandSource().getServer();
            BlockPos botCurrentPos = bot.getBlockPos();
            server.execute(() -> {
                // Try: original ground level, then each level down toward bot
                BlockPos[] candidates = {
                    digPos,              // original ground (ideal cap)
                    digPos.down(),       // one below ground
                    botCurrentPos.up(2)  // just above bot's head
                };
                for (BlockPos cap : candidates) {
                    if (bot.getEntityWorld().getBlockState(cap).isAir()) {
                        if (BotActions.placeBlockAt(bot, cap)) {
                            capPlacedAt[0] = cap.toImmutable();
                            LOGGER.debug("Cap placed at {}", cap.toShortString());
                            break;
                        }
                    }
                }
            });
            Thread.sleep(300);

            // Mark shelter active only if the bunker validates as survivable.
            if (isStaleShelter(bot, gen)) return;
            if (capPlacedAt[0] != null) {
                ShelterAssessment assessment = assessShelterAt(
                        bot,
                        (ServerWorld) bot.getEntityWorld(),
                        bot.getBlockPos(),
                        ShelterType.DIG_DOWN,
                        capPlacedAt[0],
                        null);
                if (assessment.valid()) {
                    SHELTER_ACTIVE.put(bot.getUuid(), new ShelterInfo(
                            server.getTicks(),
                            ShelterType.DIG_DOWN,
                            capPlacedAt[0],
                            null,
                            assessment.standPos(),
                            server.getTicks(),
                            ShelterInvalidReason.VALID));
                } else {
                    LOGGER.info("Bot {} dig-down bunker rejected reason={}",
                            bot.getName().getString(),
                            assessment.reason().name().toLowerCase());
                }
            } else {
                LOGGER.info("Bot {} dig-down bunker uncapped — not marking as shelter",
                        bot.getName().getString());
            }

            // Wait for bot to settle inside the bunker before placing torch
            Thread.sleep(500);

            // Place a torch inside the bunker (at deepest mined level, not bot.getBlockPos())
            final BlockPos torchTarget = digPos.down(3); // bottom of 3-deep hole
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
                                bot.getName().getString(), torchTarget.toShortString(),
                                placed ? "placed" : "failed");
                        break;
                    }
                }
            });
            Thread.sleep(200);

            LOGGER.info("Bot {} dug emergency bunker at {} (mined={}, capPlaced={})",
                    bot.getName().getString(), digPos.toShortString(), blocksMined, capPlacedAt[0] != null);

            // Eat inside the shelter while waiting to heal
            int shelterBites = HealingService.stabilizeEat(bot, 5);
            if (shelterBites > 0) {
                LOGGER.info("Bot {} ate {} items inside shelter (hp={})",
                        bot.getName().getString(), shelterBites,
                        String.format("%.1f", bot.getHealth()));
            }
            // If still hurt and couldn't eat enough, wait for natural regen
            if (bot.getHealth() < bot.getMaxHealth() * 0.8f) {
                Thread.sleep(3000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Emergency dig-down failed for {}: {}", bot.getName().getString(), e.getMessage());
        }
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

    /** Find the nearest log block (tree trunk) within searchRadius. */
    private static BlockPos findNearbyTree(ServerWorld world, BlockPos center, int searchRadius) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    BlockPos check = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(check);
                    if (state.isIn(BlockTags.LOGS)) {
                        double distSq = center.getSquaredDistance(check);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = check.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Finds a nearby overhang, shallow cave, or cliff recess that the bot can wall off.
     * A shelter candidate is a position with:
     * - Solid block overhead (not sky-visible)
     * - At least 2 solid walls on the sides (back + one side minimum)
     * - At most 2 open faces to wall off (needs few blocks to seal)
     * - Walkable floor
     */
    private static BlockPos findNearbyOverhangShelter(ServerWorld world, BlockPos center, int searchRadius) {
        BlockPos best = null;
        int bestOpenFaces = Integer.MAX_VALUE; // fewer open faces = better shelter
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    BlockPos feet = center.add(dx, dy, dz);
                    // Must have solid floor, open feet/head space, and overhead cover
                    if (!world.getBlockState(feet.down()).isSolidBlock(world, feet.down())) continue;
                    if (!world.getBlockState(feet).isAir()) continue;
                    if (!world.getBlockState(feet.up()).isAir()) continue;
                    if (world.isSkyVisible(feet.up(2))) continue; // needs overhead cover

                    // Count open cardinal faces at feet level
                    int openFaces = 0;
                    for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                        BlockPos neighbor = feet.offset(dir);
                        if (world.getBlockState(neighbor).isAir()
                                || world.getBlockState(neighbor).isReplaceable()) {
                            openFaces++;
                        }
                    }
                    // Good shelter: 1-2 open faces (easy to wall off). 0 = fully enclosed already.
                    // 3-4 open faces = too exposed, would need too many blocks.
                    if (openFaces < 1 || openFaces > 2) continue;

                    double distSq = center.getSquaredDistance(feet);
                    // Prefer: fewer open faces first, then closer
                    if (openFaces < bestOpenFaces
                            || (openFaces == bestOpenFaces && distSq < bestDistSq)) {
                        bestOpenFaces = openFaces;
                        bestDistSq = distSq;
                        best = feet.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    /**
     * Emergency wall-off: walk to a shelter position (overhang/cave), then place blocks
     * to seal the open faces. Optionally place a torch inside if the bot has one.
     */
    private static void emergencyWallOff(ServerPlayerEntity bot, BlockPos shelter) {
        try {
            // Walk to the shelter.
            bot.getCommandSource().getServer().execute(() ->
                    FollowMovementService.moveToward(bot, Vec3d.ofCenter(shelter), 1.0, true, null));
            Thread.sleep(2000);

            if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;

            // Wall off open faces at feet level AND head level.
            for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                BlockPos wallFeet = shelter.offset(dir);
                BlockPos wallHead = wallFeet.up();
                if (world.getBlockState(wallFeet).isAir() || world.getBlockState(wallFeet).isReplaceable()) {
                    bot.getCommandSource().getServer().execute(() ->
                            BotActions.placeBlockAt(bot, wallFeet));
                    Thread.sleep(300);
                }
                if (world.getBlockState(wallHead).isAir() || world.getBlockState(wallHead).isReplaceable()) {
                    bot.getCommandSource().getServer().execute(() ->
                            BotActions.placeBlockAt(bot, wallHead));
                    Thread.sleep(300);
                }
            }

            // Place a torch inside if the bot has one.
            for (int i = 0; i < bot.getInventory().size(); i++) {
                var stack = bot.getInventory().getStack(i);
                if (stack.isOf(Items.TORCH)) {
                    BlockPos torchPos = shelter;
                    bot.getCommandSource().getServer().execute(() ->
                            BotActions.placeBlockAt(bot, torchPos));
                    break;
                }
            }

            LOGGER.info("Bot {} walled off shelter at {}",
                    bot.getName().getString(), shelter.toShortString());

            // Wait inside to heal.
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Emergency wall-off failed for {}: {}", bot.getName().getString(), e.getMessage());
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
            SHELTER_ACTIVE.put(bot.getUuid(), new ShelterInfo(
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
        SHELTER_ACTIVE.put(bot.getUuid(), new ShelterInfo(
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
            boolean[] sealed = {false};
            server.execute(() -> {
                BotActions.PlaceResult fResult = BotActions.tryPlaceBlockAt(bot, wallFeet,
                        net.minecraft.util.math.Direction.UP, SEAL_BLOCKS, true);
                BotActions.PlaceResult hResult = BotActions.tryPlaceBlockAt(bot, wallHead,
                        net.minecraft.util.math.Direction.UP, SEAL_BLOCKS, true);
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
                        BotActions.tryPlaceBlockAt(bot, wallFeet, net.minecraft.util.math.Direction.UP, SEAL_BLOCKS, true);
                    if (!bot.getEntityWorld().getBlockState(wallHead).blocksMovement())
                        BotActions.tryPlaceBlockAt(bot, wallHead, net.minecraft.util.math.Direction.UP, SEAL_BLOCKS, true);
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
            if (assessment.valid()) {
                SHELTER_ACTIVE.put(bot.getUuid(), new ShelterInfo(
                        server.getTicks(),
                        ShelterType.CLIFF,
                        wallFeet.toImmutable(),
                        digDir,
                        assessment.standPos(),
                        server.getTicks(),
                        ShelterInvalidReason.VALID));
            } else {
                LOGGER.info("Bot {} cliff shelter rejected reason={}",
                        bot.getName().getString(),
                        assessment.reason().name().toLowerCase());
                return;
            }

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
            MiningTool.mineBlock(bot, capBlock, false).join();
            Thread.sleep(200);
        }
        LOGGER.info("Bot {} generic break-free (mined upward)", bot.getName().getString());
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

        // 1. Teleport grace period (already checked earlier, but belt-and-suspenders)
        if (BotEventHandler.isInTeleportGracePeriod(bot)) {
            LOGGER.info("Bot {} linger: suppressed — teleport grace period", name);
            return true;
        }

        // 2. Active task running — bot is mining/working, don't interrupt
        if (TaskService.hasActiveTask(botId)) {
            LOGGER.info("Bot {} linger: suppressed — active task running", name);
            return true;
        }

        // 3. Recently exited shelter — don't immediately re-trigger surface recovery
        Long shelterExitTick = RECENT_SHELTER_EXIT_TICK.get(botId);
        if (shelterExitTick != null && (tick - shelterExitTick) < SHELTER_EXIT_GRACE_TICKS) {
            LOGGER.info("Bot {} linger: suppressed — recently exited shelter ({}s ago)",
                    name, (tick - shelterExitTick) / 20);
            return true;
        }

        // 4. Commander nearby and also underground — player brought the bot here
        if (isCommanderNearbyAndUnderground(bot, world, server)) {
            LOGGER.info("Bot {} linger: suppressed — commander nearby and underground", name);
            return true;
        }

        // 5. No tools and NOT near surface with soft blocks — can't mine out, wait for rescue
        boolean hasPickaxe = hasAnyPickaxe(bot);
        if (!hasPickaxe && !isNearSurfaceWithSoftBlocks(bot, world)) {
            LOGGER.info("Bot {} linger: suppressed — no tools, deep underground, waiting for rescue", name);
            return true;
        }

        // 6. Nighttime on surface — stay underground unless critically starving
        if (!world.isDay() || world.isThundering()) {
            if (foodLevel > 3) {
                LOGGER.info("Bot {} linger: suppressed — nighttime, waiting for dawn (food={})", name, foodLevel);
                return true;
            }
        }

        // 7. Commander recently died — linger near death site to guard drops
        if (isCommanderRecentlyDied(bot, server, tick)) {
            LOGGER.info("Bot {} linger: suppressed — commander died recently, guarding drops", name);
            return true;
        }

        // 8. Try to get food from nearby chests before surfacing
        try {
            if (BotMutualAidService.tryImmediateChestFoodRecovery(bot, world)) {
                LOGGER.info("Bot {} linger: suppressed — found food in nearby chest", name);
                UNDERGROUND_LINGER_START.remove(botId); // reset timer since we found food
                return true;
            }
        } catch (Throwable t) {
            LOGGER.debug("Bot {} linger: chest food check failed: {}", name, t.getMessage());
        }

        // 9. Well-fed — no survival urgency, stay underground indefinitely
        if (foodLevel >= 14) {
            LOGGER.info("Bot {} linger: suppressed — well-fed (food={})", name, foodLevel);
            UNDERGROUND_LINGER_START.remove(botId); // no urgency, reset timer
            return true;
        }

        // 10. Critically hungry — allow surface recovery immediately
        if (foodLevel <= 6) {
            LOGGER.info("Bot {} linger: allowing surface recovery — critically hungry (food={})", name, foodLevel);
            UNDERGROUND_LINGER_START.remove(botId);
            return false;
        }

        // 11. Moderately hungry (food 7-13) — wait for linger timer to expire
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

    /**
     * Check if the bot is near the surface with only soft blocks overhead.
     * Allows tool-less breakfree when the bot is just a few blocks below ground
     * with dirt/sand/gravel above (e.g., after tactical shelter with no tools).
     */
    private static boolean isNearSurfaceWithSoftBlocks(ServerPlayerEntity bot, ServerWorld world) {
        int botY = bot.getBlockY();
        int surfaceY = SafePositionService.getWalkableGroundY(world, bot.getBlockX(), bot.getBlockZ());
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

        if (result != null && result.success()) {
            SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
            SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
            LOGGER.info("Bot {} reached surface via dedicated surface recovery", bot.getName().getString());
        } else {
            long nowTick = bot.getCommandSource().getServer() != null ? bot.getCommandSource().getServer().getTicks() : 0L;
            SURFACE_RECOVERY_FAILURE_TICK.put(bot.getUuid(), nowTick);
            SURFACE_RECOVERY_FAILURE_REASON.put(bot.getUuid(), result != null ? String.valueOf(result.message()) : "null result");
            LOGGER.warn("Bot {} ascent failed: {}",
                    bot.getName().getString(), result != null ? result.message() : "null result");
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
    public static boolean ensureAtSurface(ServerPlayerEntity bot, ServerWorld world) {
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
                        return true;
                    }
                }
            }
        }

        if (logOperationalSurfaceState(bot, world, "ensureAtSurface:current")) {
            return true;
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
                    return true;
                }
                // Movement failed — try building scaffold steps toward the staging area
                if (tryStepBuildToStaging(bot, world, nearby.pos())) {
                    LOGGER.info("ensureAtSurface: {} reached operational surface via step-building",
                            bot.getName().getString());
                    SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
                    SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
                    return true;
                }
            } else {
                LOGGER.info("ensureAtSurface: {} found no nearby operational staging before ascent",
                        bot.getName().getString());
            }

            // If the bot is starving or effectively unequipped but has scaffold blocks, prefer a quick pillar escape.
            if (shouldPrioritizeEmergencyPillar(bot) && tryPillarOperationalSurfaceRecovery(bot, world)) {
                LOGGER.info("ensureAtSurface: {} reached operational surface via nearby staging",
                        bot.getName().getString());
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
                return true;
            }

            if (tryPillarOperationalSurfaceRecovery(bot, world)) {
                SURFACE_RECOVERY_FAILURE_TICK.remove(bot.getUuid());
                SURFACE_RECOVERY_FAILURE_REASON.remove(bot.getUuid());
                LOGGER.info("ensureAtSurface: {} reached operational surface via pillar recovery",
                        bot.getName().getString());
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

    private static boolean shouldPrioritizeEmergencyPillar(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        boolean starving = bot.getHungerManager().getFoodLevel() <= 6;
        boolean noTool = !hasMiningOrCombatTool(bot);
        return countScaffoldBlocks(bot) >= 3 && (starving || noTool);
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

    private static boolean hasMiningOrCombatTool(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey();
            if (key == null) {
                continue;
            }
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("pickaxe")
                    || lower.contains("shovel")
                    || lower.contains("axe")
                    || lower.contains("sword")
                    || lower.contains("trident")
                    || lower.contains("mace")) {
                return true;
            }
        }
        return false;
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
        int scaffoldCount = countScaffoldBlocks(bot);
        if (scaffoldCount <= 0) {
            LOGGER.info("ensureAtSurface: {} pillar recovery skipped (no scaffold blocks)",
                    bot.getName().getString());
            return false;
        }

        int pillarSteps = Math.min(6, scaffoldCount);
        LOGGER.info("ensureAtSurface: {} pillar recovery steps={}",
                bot.getName().getString(), pillarSteps);

        List<BlockPos> placed = ScaffoldService.pillarUpWithPositions(bot, pillarSteps);
        if (placed.isEmpty()) {
            LOGGER.info("ensureAtSurface: {} pillar recovery placed no blocks",
                    bot.getName().getString());
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
            moved = result != null && (result.success() || bot.getBlockPos().getSquaredDistance(target) <= 4.0D);
            LOGGER.info("{}: movement success={} detail={}",
                    label,
                    moved,
                    result != null ? result.detail() : "null");
        }
        if (!moved) {
            moved = MovementService.nudgeTowardUntilClose(bot, target, 2.5D, 4_000L, 0.20D, label);
            LOGGER.info("{}: nudge success={}", label, moved);
        }
        return moved && isAtSurface(bot, world);
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
        boolean ready = SafePositionService.isRecoveryReadySurface(world, bot.getBlockPos());
        LOGGER.info("{}: {} pos={} {}",
                label,
                ready ? "ready" : "not-ready",
                bot.getBlockPos().toShortString(),
                SafePositionService.summarizeSurfaceAssessment(assessment));
        return ready;
    }

    private static int countScaffoldBlocks(ServerPlayerEntity bot) {
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
