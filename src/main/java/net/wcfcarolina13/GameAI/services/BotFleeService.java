package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final ConcurrentHashMap<UUID, FleeState> FLEE_STATES = new ConcurrentHashMap<>();

    private BotFleeService() {}

    private static final class FleeState {
        boolean isFleeing;
        long fleeStartTick;
        Vec3d fleeDirection;
        long fleeCooldownUntilTick;
        boolean emergencyTacticAttempted;
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
            return true;
        }

        return false;
    }

    private static boolean shouldFlee(ServerPlayerEntity bot, List<Entity> hostiles) {
        int hostileCount = hostiles.size();
        float healthRatio = bot.getHealth() / bot.getMaxHealth();

        // Critical health — flee regardless
        if (healthRatio <= 0.30f) return true;

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
        Vec3d fleeDir = computeFleeDirection(bot, hostiles);
        state.isFleeing = true;
        state.fleeStartTick = currentTick;
        state.fleeDirection = fleeDir;
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

        // Timeout — flee hasn't worked. Try emergency tactics before giving up.
        if (currentTick - state.fleeStartTick >= MAX_FLEE_TICKS) {
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
            state.fleeDirection = computeFleeDirection(bot, hostiles);
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

    private static Vec3d computeFleeDirection(ServerPlayerEntity bot, List<Entity> hostiles) {
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
            // Surrounded — pick an arbitrary direction
            dx = 1;
            dz = 0;
            len = 1;
        }
        return new Vec3d(dx / len, 0, dz / len);
    }

    private static void stopFleeing(FleeState state, long currentTick) {
        state.isFleeing = false;
        state.fleeDirection = null;
        state.emergencyTacticAttempted = false;
        state.fleeCooldownUntilTick = currentTick + COOLDOWN_TICKS;
    }

    // ── Emergency Tactics ──────────────────────────────────────────────────

    /**
     * Attempts a last-ditch survival tactic when fleeing has failed.
     * Priority: pillar up (if no phantoms) > dig down under tree.
     * Runs on a daemon worker thread since both tactics are multi-tick blocking ops.
     * Returns true if a tactic was launched.
     */
    private static boolean tryEmergencyTactic(ServerPlayerEntity bot, List<Entity> hostiles) {
        boolean phantomsPresent = hostiles.stream()
                .anyMatch(e -> e.getType() == EntityType.PHANTOM);
        boolean hasBlocks = hasPlaceableBlocks(bot, 6);

        // Prefer pillaring if no phantoms — fast, reliable, gets out of melee range.
        if (!phantomsPresent && hasBlocks) {
            LOGGER.info("Bot {} attempting emergency pillar-up ({} hostiles, hp={}/{})",
                    bot.getName().getString(), hostiles.size(),
                    String.format("%.1f", bot.getHealth()),
                    String.format("%.1f", bot.getMaxHealth()));
            Thread t = new Thread(() -> emergencyPillarUp(bot), "emergency-pillar-" + bot.getName().getString());
            t.setDaemon(true);
            t.start();
            return true;
        }

        // Wall off in a nearby overhang, shallow cave, or cliff recess.
        if (hasBlocks && bot.getEntityWorld() instanceof ServerWorld world2) {
            BlockPos shelter = findNearbyOverhangShelter(world2, bot.getBlockPos(), 10);
            if (shelter != null) {
                LOGGER.info("Bot {} attempting emergency wall-off at shelter {} ({} hostiles, hp={}/{})",
                        bot.getName().getString(), shelter.toShortString(), hostiles.size(),
                        String.format("%.1f", bot.getHealth()),
                        String.format("%.1f", bot.getMaxHealth()));
                Thread t = new Thread(() -> emergencyWallOff(bot, shelter),
                        "emergency-walloff-" + bot.getName().getString());
                t.setDaemon(true);
                t.start();
                return true;
            }
        }

        // Last resort: dig straight down and cap the hole.
        // The cap block provides overhead protection (even from phantoms).
        {
            BlockPos digSpot = bot.getBlockPos();
            LOGGER.info("Bot {} attempting emergency dig-down at {} ({} hostiles, hp={}/{})",
                    bot.getName().getString(), digSpot.toShortString(), hostiles.size(),
                    String.format("%.1f", bot.getHealth()),
                    String.format("%.1f", bot.getMaxHealth()));
            Thread t = new Thread(() -> emergencyDigDown(bot, digSpot),
                    "emergency-dig-" + bot.getName().getString());
            t.setDaemon(true);
            t.start();
            return true;
        }
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
    private static void emergencyDigDown(ServerPlayerEntity bot, BlockPos digPos) {
        try {
            // Stop moving before digging.
            bot.getCommandSource().getServer().execute(() -> {
                bot.setSprinting(false);
                bot.setVelocity(0, bot.getVelocity().y, 0);
                bot.velocityDirty = true;
            });
            Thread.sleep(300);

            // Mine 3 blocks straight down.
            for (int depth = 0; depth < 3; depth++) {
                BlockPos below = digPos.down(depth + 1);
                if (bot.getEntityWorld().getBlockState(below).isAir()) continue;
                MiningTool.mineBlock(bot, below, false).join();
                Thread.sleep(200);
            }

            // Wait briefly to fall into the hole.
            Thread.sleep(500);

            // Cap the top with a block.
            BlockPos capPos = digPos;
            // The bot is now ~3 blocks lower, so cap at the original ground level.
            bot.getCommandSource().getServer().execute(() -> BotActions.placeBlockAt(bot, capPos));

            LOGGER.info("Bot {} dug emergency bunker at {}", bot.getName().getString(),
                    digPos.toShortString());

            // Wait inside for a while to heal.
            Thread.sleep(5000);
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
                if (stack.isOf(net.minecraft.item.Items.TORCH)) {
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

    /** Clear flee state for a bot (on death, respawn, or mode change). */
    public static void reset(UUID botId) {
        FLEE_STATES.remove(botId);
    }
}
