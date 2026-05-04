package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Central policy for whether a bot is allowed to engage a given entity in combat.
 *
 * <p>Protects mobs bearing custom name tags from attack: if a player name-tags a mob —
 * whether hostile (a display zombie in a farm, a kept raid captain) or peaceful (a
 * prize cow, a favorite sheep) — the bot treats it as off-limits. The per-bot
 * {@link BotHomeService#isAttackNamedMobs} toggle restores normal engagement for
 * players who want the bot to keep fighting back.
 *
 * <p>Threat detection itself (see {@code EntityUtil.isHostile}) is intentionally left
 * unchanged. Named hostiles still appear in hostile scans so {@link BotFleeService}
 * can fire flee behavior when they damage the bot.
 */
public final class BotCombatPolicyService {

    private BotCombatPolicyService() {}

    /**
     * @return {@code false} if {@code target} is a living entity with a custom name
     *         and {@code bot}'s {@code attackNamedMobs} toggle is off; {@code true}
     *         otherwise. A {@code null} target is never attackable; a {@code null}
     *         bot disables the toggle check (rejects named targets).
     */
    public static boolean shouldBotAttack(Entity target, ServerPlayerEntity bot) {
        if (target == null) return false;
        if (!(target instanceof LivingEntity living)) return true;
        if (!living.hasCustomName()) return true;
        return bot != null && BotHomeService.isAttackNamedMobs(bot);
    }
}
