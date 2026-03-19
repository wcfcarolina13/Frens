package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.item.Item;
import net.minecraft.util.math.Direction;

import java.util.List;
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
    public enum ShelterType { CLIFF, DIG_DOWN }

    /** Metadata for an active tactical shelter. */
    public record ShelterInfo(long enteredTick, ShelterType type, BlockPos capPos, Direction entryDir) {}

    private static final ConcurrentHashMap<UUID, FleeState> FLEE_STATES = new ConcurrentHashMap<>();
    /** Per-bot cooldown to prevent spamming proactive shelter attempts. */
    private static final ConcurrentHashMap<UUID, Long> SHELTER_COOLDOWN = new ConcurrentHashMap<>();
    /** Tracks bots currently inside a tactical shelter with metadata. */
    private static final ConcurrentHashMap<UUID, ShelterInfo> SHELTER_ACTIVE = new ConcurrentHashMap<>();
    /** Generation counter — incremented on death/respawn to invalidate stale shelter threads. */
    private static final ConcurrentHashMap<UUID, Long> SHELTER_GENERATION = new ConcurrentHashMap<>();
    /** Mutex to prevent duplicate shelter threads for the same bot. */
    private static final ConcurrentHashMap<UUID, AtomicBoolean> SHELTER_LOCK = new ConcurrentHashMap<>();

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

            // Mark shelter active — only if cap was placed (actually enclosed)
            if (isStaleShelter(bot, gen)) return;
            if (capPlacedAt[0] != null) {
                SHELTER_ACTIVE.put(bot.getUuid(), new ShelterInfo(
                        server.getTicks(), ShelterType.DIG_DOWN, capPlacedAt[0], null));
            } else {
                LOGGER.info("Bot {} dig-down bunker uncapped — not marking as shelter",
                        bot.getName().getString());
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

            // Mark shelter active — only if thread is still valid
            if (isStaleShelter(bot, gen)) return;
            SHELTER_ACTIVE.put(bot.getUuid(), new ShelterInfo(
                    server.getTicks(), ShelterType.CLIFF, wallFeet.toImmutable(), digDir));

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
        ShelterInfo info = SHELTER_ACTIVE.get(bot.getUuid());
        if (info == null) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;

        if (!world.isDay() || world.isThundering()) return false;
        long tod = world.getTimeOfDay() % 24000L;
        if (tod < 1000 || tod >= 12000) return false;

        SHELTER_ACTIVE.remove(bot.getUuid());
        LOGGER.info("Bot {} breaking free from tactical shelter (daylight, tod={})",
                bot.getName().getString(), tod);

        Thread t = new Thread(() -> breakFreeFromShelter(bot, info),
                "shelter-breakfree-" + bot.getName().getString());
        t.setDaemon(true);
        t.start();
        return true;
    }

    /**
     * Clears shelter state and launches break-free mining on a worker thread.
     * Use when a command (follow, skill, etc.) needs the bot to leave shelter first.
     * The returned thread can be joined to wait for completion before executing the command.
     */
    public static Thread clearShelterAndBreakFree(ServerPlayerEntity bot) {
        ShelterInfo info = SHELTER_ACTIVE.remove(bot.getUuid());
        if (info == null) return null;
        LOGGER.info("Bot {} breaking free from {} shelter for command",
                bot.getName().getString(), info.type());
        Thread t = new Thread(() -> breakFreeFromShelter(bot, info),
                "shelter-breakfree-" + bot.getName().getString());
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Force break-free without shelter metadata (e.g. bot enclosed after server restart).
     * Uses generic escape: collect torch, try 4 horizontal dirs, then pillar to surface.
     * Guarded by SHELTER_LOCK to prevent duplicate concurrent break-free threads.
     */
    public static Thread forceBreakFree(ServerPlayerEntity bot) {
        SHELTER_ACTIVE.remove(bot.getUuid());
        AtomicBoolean lock = SHELTER_LOCK.computeIfAbsent(bot.getUuid(), k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            LOGGER.debug("forceBreakFree skipped for {} — another break-free already running",
                    bot.getName().getString());
            return null;
        }
        Thread t = new Thread(() -> {
            try {
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
        try {
            BlockPos pos = bot.getBlockPos();
            ServerWorld world = (ServerWorld) bot.getEntityWorld();

            // 1. Collect torch — check current position and all adjacent
            collectNearbyTorch(bot, world, pos);

            // 2. Type-specific exit
            if (info != null && info.type() == ShelterType.CLIFF && info.capPos() != null) {
                breakFreeCliff(bot, world, info);
            } else if (info != null && info.type() == ShelterType.DIG_DOWN && info.capPos() != null) {
                breakFreeDugDown(bot, world, info);
            } else {
                breakFreeGeneric(bot, world, pos);
            }

            // 3. Surface escape — if still underground after breaking seal, pillar up
            escapeToSurface(bot, world);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.warn("Break-free failed for {}: {}", bot.getName().getString(), e.getMessage());
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
     * Escape to surface by pillaring up until sky is visible.
     * Mines the block above, jumps, places block below. Max 30 blocks.
     */
    private static void escapeToSurface(ServerPlayerEntity bot, ServerWorld world)
            throws InterruptedException {
        if (world.isSkyVisible(bot.getBlockPos().up())) return; // already at surface

        LOGGER.info("Bot {} underground after break-free — escaping to surface", bot.getName().getString());
        MinecraftServer server = bot.getCommandSource().getServer();

        for (int i = 0; i < 30; i++) {
            if (world.isSkyVisible(bot.getBlockPos().up())) {
                LOGGER.info("Bot {} reached surface after {} pillar steps", bot.getName().getString(), i);
                return;
            }
            BlockPos above = bot.getBlockPos().up(2);
            // Mine block above head if solid
            if (world.getBlockState(above).isSolidBlock(world, above)) {
                MiningTool.mineBlock(bot, above, false).join();
                Thread.sleep(200);
            }
            // Also clear the block above that if solid (2-high clearance for jump)
            BlockPos above2 = above.up();
            if (world.getBlockState(above2).isSolidBlock(world, above2)) {
                MiningTool.mineBlock(bot, above2, false).join();
                Thread.sleep(200);
            }
            // Jump + place block below to pillar up
            BlockPos feet = bot.getBlockPos();
            server.execute(() -> {
                bot.jump();
                bot.velocityDirty = true;
            });
            Thread.sleep(400); // wait for peak of jump
            final BlockPos placeAt = feet;
            server.execute(() -> BotActions.placeBlockAt(bot, placeAt));
            Thread.sleep(300);
        }
        LOGGER.warn("Bot {} failed to reach surface after 30 pillar steps", bot.getName().getString());
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
