package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.BotActions;

/**
 * Safety gate for "should the bot pursue this thing?". Composes four rules
 * the user flagged after watching the bot dive into dangerous places:
 *
 * <ol>
 *   <li>Predicted fall damage (target more than 4 blocks below bot) → reject.</li>
 *   <li>Light level 0 at the target → reject (pitch-black caves spawn mobs and
 *       the bot's combat reach is impaired).</li>
 *   <li>2+ visible hostile mobs within 5 blocks of the target → reject.</li>
 *   <li>Non-aggroed mob → require a long-range weapon to engage.</li>
 * </ol>
 *
 * <p>Used at two call sites:
 * <ul>
 *   <li>{@link DropSweepService} — gate per-drop acceptance (rules 1–3).</li>
 *   <li>BotEventHandler.engageHostiles — non-aggro filter (rule 4) +
 *       optional environment guard for combat initiation.</li>
 * </ul>
 */
public final class DangerousPursuitGate {

    private static final double FALL_REJECT_BLOCKS = 4.0D;
    private static final int MULTI_MOB_REJECT_THRESHOLD = 2;
    private static final double MULTI_MOB_SCAN_RADIUS = 5.0D;

    private DangerousPursuitGate() {}

    /**
     * Drop-sweep / move-to-position pursuit gate. Returns false if any of
     * the environment rules reject the target.
     */
    public static boolean isLocationSafeForPursuit(ServerPlayerEntity bot, BlockPos targetPos, World world) {
        if (bot == null || targetPos == null || !(world instanceof ServerWorld serverWorld)) {
            return true;
        }
        // Rule 1: target sits well below bot.
        // ItemEntities/XP orbs at bot.y - 5 mean diving into a hole.
        if (bot.getY() - targetPos.getY() > FALL_REJECT_BLOCKS) {
            return false;
        }
        // Rule 2: pitch-black target. Combined sky + block light.
        // Hostile spawn threshold is light <= 0 (1.18+); we mirror that.
        if (serverWorld.getLightLevel(targetPos) <= 0) {
            return false;
        }
        // Rule 3: too many hostiles clustered around the target.
        Box box = new Box(
                targetPos.getX() - MULTI_MOB_SCAN_RADIUS, targetPos.getY() - MULTI_MOB_SCAN_RADIUS, targetPos.getZ() - MULTI_MOB_SCAN_RADIUS,
                targetPos.getX() + 1 + MULTI_MOB_SCAN_RADIUS, targetPos.getY() + 1 + MULTI_MOB_SCAN_RADIUS, targetPos.getZ() + 1 + MULTI_MOB_SCAN_RADIUS);
        int hostileCount = 0;
        for (Entity e : serverWorld.getOtherEntities(bot, box)) {
            if (e == null || !e.isAlive() || e.isRemoved()) {
                continue;
            }
            if (e instanceof HostileEntity || e instanceof Monster) {
                hostileCount++;
                if (hostileCount >= MULTI_MOB_REJECT_THRESHOLD) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Combat target acceptance gate for non-aggroed mobs. Returns true only
     * if the mob is currently targeting the bot (real threat to defend
     * against) OR the bot has a long-range weapon to safely engage from
     * distance. Aggressive close-quarters pursuit of a non-aggroed mob with
     * only a melee weapon is the failure mode this rule prevents.
     */
    public static boolean shouldEngageNonAggro(ServerPlayerEntity bot, Entity candidate) {
        if (bot == null || candidate == null) {
            return false;
        }
        if (candidate instanceof MobEntity mob) {
            LivingEntity target = mob.getTarget();
            if (target == bot) {
                return true;
            }
        } else {
            // Non-mob entity (e.g. some misc hostile we don't normally see) — let it through.
            return true;
        }
        return BotActions.hasRangedWeapon(bot);
    }

    /**
     * Convenience: is this entity-position pair safe to pursue at all?
     * Combines location safety with the non-aggro rule. Used when the bot
     * is considering walking *to* an enemy position to fight.
     */
    public static boolean isCombatPursuitSafe(ServerPlayerEntity bot, Entity candidate, World world) {
        if (!shouldEngageNonAggro(bot, candidate)) {
            return false;
        }
        if (candidate == null) {
            return false;
        }
        return isLocationSafeForPursuit(bot, BlockPos.ofFloored(new Vec3d(candidate.getX(), candidate.getY(), candidate.getZ())), world);
    }
}
