package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Position-reporting helpers for bots standing on partial-collision blocks.
 *
 * <p>Vanilla {@link net.minecraft.entity.Entity#getBlockPos()} floors the entity Y,
 * which produces an off-by-one between "cell where the bot's feet are" and "cell
 * the bot's body is in" when the floor is a partial-height block:
 *
 * <pre>
 *   Floor type at Y=N        bot.getY()    floor(Y)    expected feet cell
 *   ----------------------   ----------    --------    ------------------
 *   Full block (dirt)        N + 1.0+      N + 1       N + 1   ✓ matches
 *   Soul sand / mud          N + 0.875     N           N + 1   ✗ off by 1
 *   Slab bottom              N + 0.5       N           N + 1   ✗ off by 1
 *   Snow layer 8             N + 0.5       N           N + 1   ✗ off by 1
 *   Carpet / pressure plate  N + 0.0625    N           N + 1   ✗ off by 1
 * </pre>
 *
 * <p>For arrival/progress checks that compare {@code bot.getBlockPos()} against a
 * pathfinder waypoint at {@code floorY + 1}, this 1-block Y delta makes the bot
 * appear "not arrived" even when it is physically standing exactly at the
 * waypoint. The most user-visible symptom is the bot oscillating or appearing
 * stuck on carpets, pressure plates, soul sand, and similar surfaces.
 *
 * <p>{@link #effectiveFootCell(ServerPlayerEntity)} promotes the reported cell
 * upward by one when the bot is overlapping with a partial-height block (top
 * collision &lt; 1.0). Pathfinder waypoints, follow targets, and stuck-detection
 * sites should compare against this instead of raw {@code getBlockPos()}.
 *
 * <p>Callers that genuinely need the literal floored cell (e.g.
 * {@code canAcceptMovementImpulse}'s same-cell short-circuit) must keep using
 * {@code getBlockPos()} directly — this helper is for arrival/progress only.
 */
public final class BotPositionUtil {

    private BotPositionUtil() {}

    /**
     * Returns the cell the bot's feet are effectively occupying for arrival/progress
     * purposes. When the bot is overlapping with a partial-height block (the bot is
     * standing on top of it but {@code getBlockPos()} reports the block's own cell),
     * the returned cell is one above. Otherwise returns {@code bot.getBlockPos()}.
     */
    public static BlockPos effectiveFootCell(ServerPlayerEntity bot) {
        if (bot == null) return BlockPos.ORIGIN;
        BlockPos raw = bot.getBlockPos();
        World world = bot.getEntityWorld();
        if (world == null) return raw;
        return effectiveFootCell(world, raw);
    }

    /**
     * Static form for callers that have already resolved the raw foot cell or want
     * to apply the same promotion to a different (world, pos) pair.
     */
    public static BlockPos effectiveFootCell(World world, BlockPos raw) {
        if (world == null || raw == null) return raw;
        BlockState here = world.getBlockState(raw);
        if (here == null || here.isAir()) return raw;
        var shape = here.getCollisionShape(world, raw);
        if (shape.isEmpty()) return raw; // water, lava, vine, ladder, climbable
        double topY = shape.getMax(Direction.Axis.Y);
        // Strict: anything with a top below 1.0 means the bot is standing ON it,
        // and its true feet cell is the one above. Full-collision blocks (closed
        // doors, fences, walls) keep returning the literal cell — those are
        // genuine "stuck inside a block" cases the caller wants to detect.
        if (topY < 1.0D - 1e-6) return raw.up();
        return raw;
    }

    /**
     * Convenience: equality between the bot's effective foot cell and a target.
     * Mirrors {@code bot.getBlockPos().equals(target)} but partial-block-aware.
     */
    public static boolean isAt(ServerPlayerEntity bot, BlockPos target) {
        if (bot == null || target == null) return false;
        return effectiveFootCell(bot).equals(target);
    }
}
