package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FireBlock;
import net.minecraft.entity.passive.PufferfishEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Shared hazard classification + per-tick runtime escape for bots standing on or near
 * dangerous blocks and entities.
 *
 * <p>Three categories are handled:</p>
 *
 * <ul>
 *   <li><b>Static BLOCK hazards</b> — fire, soul fire, lava, magma block, (lit)
 *       campfires, cactus, sweet berry bush, wither rose, powder snow, pointed
 *       dripstone. Classified by {@link #isDeadlyBlock(BlockState)} and consumed by
 *       pathfinders, movement-prep checks, and
 *       {@link ProtectedStructureBlockHelper#isNeverBreakBlock(BlockState)} so the
 *       bot routes around them AND never mines through them during escape.</li>
 *
 *   <li><b>Runtime feet-on-hazard</b> — if a bot ends up with its feet blockpos on
 *       a hazard (teleport, unloaded chunk loaded-in, griefed terrain), a per-tick
 *       scan nudges it off. Mirrors {@link BotCampfireAvoidanceService}'s escape
 *       nudge pattern.</li>
 *
 *   <li><b>Pufferfish proximity</b> — pufferfish sting through fences and walls
 *       when alive in water, so any live {@link PufferfishEntity} within
 *       {@link #PUFFERFISH_FLEE_RADIUS} blocks triggers a velocity kick directly
 *       away from the fish. No line-of-sight gate, matching the "sting through
 *       walls" behaviour.</li>
 * </ul>
 *
 * <p><b>Campfires</b> are intentionally also handled by the older
 * {@link BotCampfireAvoidanceService} — that service has extra "exposed campfire"
 * pathfinding heuristics. This class returns {@code true} from
 * {@link #isDeadlyBlock} for campfires so pathfinders reject them, but skips
 * campfires in the runtime-escape scan to avoid double-nudging.</p>
 */
public final class BotHazardService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-hazard");

    /** Radius (blocks) at which a pufferfish starts triggering a flee nudge. */
    private static final double PUFFERFISH_FLEE_RADIUS = 4.0D;
    private static final double PUFFERFISH_FLEE_RADIUS_SQ = PUFFERFISH_FLEE_RADIUS * PUFFERFISH_FLEE_RADIUS;

    /** Ring search radius for finding an escape tile when standing on a hazard. */
    private static final int MIN_ESCAPE_RADIUS = 2;
    private static final int MAX_ESCAPE_RADIUS = 4;

    private BotHazardService() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Classification — shared with pathfinders, movement prep, and rescue
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if this block hurts the bot on contact or while standing in/on
     * it. Consumed by pathfinders (reject cells), movement prep
     * (skip dangerous next-step), and the per-tick escape scan.
     *
     * <p>Includes blocks that are only dangerous from above (pointed dripstone)
     * because the bot's planning should treat those cells as no-go regardless —
     * standing on a dripstone tip impales on landing and walking into the tip
     * damages on contact.</p>
     *
     * <p>Lava is checked via {@link FluidTags#LAVA} (covers vanilla lava AND any
     * mod-registered fluid that opts into the lava tag). Fire is checked via
     * {@code instanceof FireBlock} (covers vanilla fire + soul fire AND any mod
     * fire-block that extends the vanilla class). Other entries stay explicit
     * because vanilla offers no clean tag/class abstraction for them — mod
     * variants of magma / cactus / sweet berry / etc. will need to be added if
     * they show up, and {@link [[feedback-mod-block-compat-pattern]]} is the
     * playbook.</p>
     */
    public static boolean isDeadlyBlock(BlockState state) {
        if (state == null || state.isAir()) return false;
        // Fluid-tag check — auto-includes any mod whose lava fluid opts into FluidTags.LAVA.
        if (state.getFluidState().isIn(FluidTags.LAVA)) return true;
        // Fire class check — covers vanilla FIRE + SOUL_FIRE (SoulFireBlock extends FireBlock)
        // and any mod fire that extends FireBlock.
        if (state.getBlock() instanceof FireBlock) return true;
        return state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CAMPFIRE)
                || state.isOf(Blocks.SOUL_CAMPFIRE)
                || state.isOf(Blocks.CACTUS)
                || state.isOf(Blocks.SWEET_BERRY_BUSH)
                || state.isOf(Blocks.WITHER_ROSE)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.POINTED_DRIPSTONE)
                // Cobweb is not damaging on its own, but it slows entities to ~15% speed and
                // has an empty collision shape (so pathfinders happily path into them without
                // this explicit reject). In caves, mineshafts, and dungeons this is a death
                // sentence: the bot becomes a sitting target for skeleton arrows and zombies.
                // Also a trap near raids and pillager patrols.
                || state.isOf(Blocks.COBWEB);
    }

    /**
     * Subset of {@link #isDeadlyBlock} where mining the block out as a "rescue"
     * is contraindicated: lava (a source block flows back from neighbours into
     * the dug cell — bot stays in lava) and fire variants (the burning ground
     * beneath, e.g. netherrack or any infiniburn block, re-ignites immediately).
     *
     * <p>Used by {@link BotRescueService#rescueFromBurial} to short-circuit its
     * three mining branches and redirect to displacement-based escape. Other
     * deadly blocks (magma, cactus, dripstone, sweet berry, wither rose, powder
     * snow, cobweb) are solid/minable and mining them out reveals safe ground
     * — those keep the original mine-to-escape behaviour.</p>
     */
    public static boolean isMiningContraindicatedHazard(BlockState state) {
        if (state == null || state.isAir()) return false;
        if (state.getFluidState().isIn(FluidTags.LAVA)) return true;
        if (state.getBlock() instanceof FireBlock) return true;
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-tick runtime escape + pufferfish flee
    // ─────────────────────────────────────────────────────────────────────────

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;
            if (tryEscapeHazardBlockAtFeet(bot, world)) {
                continue;
            }
            tryFleePufferfish(bot, world);
        }
    }

    /**
     * If the bot is standing in/on a deadly block, nudge it toward a nearby safe
     * tile. Campfires are skipped (handled by {@link BotCampfireAvoidanceService}).
     */
    private static boolean tryEscapeHazardBlockAtFeet(ServerPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        if (!world.isChunkLoaded(feet)) return false;

        BlockState feetState = world.getBlockState(feet);
        BlockState belowState = world.getBlockState(feet.down());

        // Skip campfires — the dedicated service owns the escape path for those.
        boolean feetIsCampfire = feetState.isOf(Blocks.CAMPFIRE) || feetState.isOf(Blocks.SOUL_CAMPFIRE);
        boolean belowIsCampfire = belowState.isOf(Blocks.CAMPFIRE) || belowState.isOf(Blocks.SOUL_CAMPFIRE);

        BlockPos hazardPos = null;
        String label = null;
        if (isDeadlyBlock(feetState) && !feetIsCampfire) {
            hazardPos = feet;
            label = feetState.getBlock().getName().getString();
        } else if (belowState.isOf(Blocks.MAGMA_BLOCK) && !belowIsCampfire) {
            // Magma block burns when you stand ON it (not in it).
            hazardPos = feet.down();
            label = "magma block";
        }

        if (hazardPos == null) return false;

        // Emergency: stop current movement inputs and nudge away.
        BotActions.stop(bot);
        BlockPos escape = findEscapeSpot(world, feet, hazardPos);
        Vec3d dir;
        if (escape != null) {
            Vec3d target = Vec3d.ofCenter(escape);
            Vec3d current = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
            dir = target.subtract(current);
            if (dir.lengthSquared() > 1.0E-6D) {
                dir = dir.normalize();
            }
        } else {
            dir = new Vec3d(0.4D, 0.0D, 0.0D);
        }
        bot.setVelocity(dir.multiply(0.45D).add(0.0D, 0.15D, 0.0D));
        bot.velocityDirty = true;

        LOGGER.info("Bot {} escaping hazard '{}' at {} -> {}",
                bot.getName().getString(), label, hazardPos.toShortString(),
                escape == null ? "<kick>" : escape.toShortString());
        return true;
    }

    /**
     * Flees any alive pufferfish within {@link #PUFFERFISH_FLEE_RADIUS} blocks.
     * No line-of-sight gate because pufferfish sting through fences and walls.
     */
    private static boolean tryFleePufferfish(ServerPlayerEntity bot, ServerWorld world) {
        Box box = new Box(
                bot.getX() - PUFFERFISH_FLEE_RADIUS,
                bot.getY() - PUFFERFISH_FLEE_RADIUS,
                bot.getZ() - PUFFERFISH_FLEE_RADIUS,
                bot.getX() + PUFFERFISH_FLEE_RADIUS,
                bot.getY() + PUFFERFISH_FLEE_RADIUS,
                bot.getZ() + PUFFERFISH_FLEE_RADIUS);
        List<PufferfishEntity> fish = world.getEntitiesByClass(
                PufferfishEntity.class,
                box,
                e -> e != null && e.isAlive() && !e.isRemoved()
                        && e.squaredDistanceTo(bot) <= PUFFERFISH_FLEE_RADIUS_SQ);
        if (fish.isEmpty()) return false;

        // Average fish position — nudge horizontally away from the centroid.
        double ax = 0.0D, ay = 0.0D, az = 0.0D;
        for (PufferfishEntity f : fish) {
            ax += f.getX();
            ay += f.getY();
            az += f.getZ();
        }
        int n = fish.size();
        ax /= n;
        ay /= n;
        az /= n;

        Vec3d away = new Vec3d(bot.getX() - ax, 0.0D, bot.getZ() - az);
        if (away.lengthSquared() < 1.0E-4D) {
            // Degenerate case (bot exactly on fish) — kick arbitrary horizontal direction.
            away = new Vec3d(0.4D, 0.0D, 0.0D);
        } else {
            away = away.normalize();
        }
        bot.setVelocity(away.multiply(0.35D).add(0.0D, 0.1D, 0.0D));
        bot.velocityDirty = true;

        LOGGER.info("Bot {} fleeing {} pufferfish (avg {},{},{})",
                bot.getName().getString(), n, (int) ax, (int) ay, (int) az);
        return true;
    }

    private static BlockPos findEscapeSpot(ServerWorld world, BlockPos botFeet, BlockPos hazardPos) {
        for (int r = MIN_ESCAPE_RADIUS; r <= MAX_ESCAPE_RADIUS; r++) {
            BlockPos best = null;
            double bestDist = Double.POSITIVE_INFINITY;
            for (BlockPos pos : BlockPos.iterate(botFeet.add(-r, -1, -r), botFeet.add(r, 1, r))) {
                if (!world.isChunkLoaded(pos)) continue;
                if (pos.equals(hazardPos)) continue;
                if (Math.abs(pos.getY() - botFeet.getY()) > 1) continue;
                if (isDeadlyBlock(world.getBlockState(pos))) continue;
                if (isDeadlyBlock(world.getBlockState(pos.down()))) continue;
                if (!isStandable(world, pos)) continue;
                double d = pos.getSquaredDistance(botFeet);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.toImmutable();
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static boolean isStandable(ServerWorld world, BlockPos feet) {
        BlockPos head = feet.up();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        if (!world.getFluidState(feet).isEmpty() || !world.getFluidState(head).isEmpty()) {
            return false;
        }
        if (!feetState.getCollisionShape(world, feet).isEmpty()
                && !WalkablePartialBlocks.isStandable(feetState, world, feet)) {
            return false;
        }
        if (!headState.getCollisionShape(world, head).isEmpty()
                && !WalkablePartialBlocks.isStandable(headState, world, head)) {
            return false;
        }
        BlockPos below = feet.down();
        BlockState belowState = world.getBlockState(below);
        return !belowState.getCollisionShape(world, below).isEmpty();
    }
}
