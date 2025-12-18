package net.shasankp000.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.shasankp000.EntityUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Stage-2 refactor: helpers for threat detection/aggregation across nearby bots.
 */
public final class BotThreatService {

    private static final double ALLY_DEFENSE_RADIUS = 12.0D;

    private BotThreatService() {}

    public static List<Entity> findHostilesAround(ServerPlayerEntity player, double radius) {
        if (player == null) {
            return Collections.emptyList();
        }
        return player.getEntityWorld().getOtherEntities(player, player.getBoundingBox().expand(radius), EntityUtil::isHostile);
    }

    public static List<Entity> augmentHostiles(ServerPlayerEntity bot,
                                              List<Entity> baseHostiles,
                                              boolean assistAllies,
                                              MinecraftServer server,
                                              Iterable<UUID> allyIds) {
        List<Entity> base = baseHostiles == null ? Collections.emptyList() : baseHostiles;
        if (!assistAllies) {
            return base;
        }
        List<Entity> allyThreats = gatherAllyThreats(bot, server, allyIds);
        if (allyThreats.isEmpty()) {
            return base;
        }
        Set<Integer> seen = new HashSet<>();
        List<Entity> combined = new ArrayList<>();
        for (Entity entity : base) {
            if (entity != null && entity.isAlive() && seen.add(entity.getId())) {
                combined.add(entity);
            }
        }
        for (Entity entity : allyThreats) {
            if (entity != null && entity.isAlive() && seen.add(entity.getId())) {
                combined.add(entity);
            }
        }
        return combined;
    }

    private static List<Entity> gatherAllyThreats(ServerPlayerEntity bot, MinecraftServer server, Iterable<UUID> allyIds) {
        if (server == null || bot == null || allyIds == null) {
            return Collections.emptyList();
        }
        ServerWorld botWorld = bot.getCommandSource().getWorld();
        if (botWorld == null) {
            return Collections.emptyList();
        }
        List<Entity> threats = new ArrayList<>();
        double radiusSq = ALLY_DEFENSE_RADIUS * ALLY_DEFENSE_RADIUS;
        for (UUID allyId : allyIds) {
            if (allyId == null || allyId.equals(bot.getUuid())) {
                continue;
            }
            ServerPlayerEntity ally = server.getPlayerManager().getPlayer(allyId);
            if (ally == null || ally.isRemoved()) {
                continue;
            }
            if (ally.getCommandSource().getWorld() != botWorld) {
                continue;
            }
            if (ally.squaredDistanceTo(bot) > radiusSq) {
                continue;
            }
            threats.addAll(findHostilesAround(ally, 8.0D));
        }
        return threats;
    }
}

