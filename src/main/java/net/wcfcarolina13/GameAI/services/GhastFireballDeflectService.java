package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.GameAI.BotEventHandler;

import java.util.Comparator;
import java.util.List;

/**
 * Lightweight tick service that scans for incoming ghast fireballs near registered bots
 * and deflects them by attacking the fireball (Minecraft's native redirect mechanic).
 */
public final class GhastFireballDeflectService {

    private static final double DEFLECT_RANGE_SQ = 16.0; // 4 blocks

    private GhastFireballDeflectService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null || server.getTicks() % 2 != 0) return;

        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) continue;

            List<FireballEntity> fireballs = world.getEntitiesByClass(
                FireballEntity.class,
                bot.getBoundingBox().expand(4.0),
                fb -> fb.squaredDistanceTo(bot) <= DEFLECT_RANGE_SQ
            );

            if (fireballs.isEmpty()) continue;

            FireballEntity closest = fireballs.stream()
                .min(Comparator.comparingDouble(fb -> fb.squaredDistanceTo(bot)))
                .orElse(null);
            if (closest == null) continue;

            LookController.faceEntity(bot, closest);
            bot.attack(closest);
            bot.swingHand(Hand.MAIN_HAND, true);
        }
    }
}
