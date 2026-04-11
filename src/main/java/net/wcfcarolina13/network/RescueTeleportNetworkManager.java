package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotCommandStateService;
import net.wcfcarolina13.GameAI.services.BotRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Server-side receiver for the rescue-teleport request.
 *
 * <p>When a player presses the rescue key, we look for bots that are:
 * <ul>
 *   <li>Following this specific player (mode=FOLLOW, followTargetUuid=player.uuid)</li>
 *   <li>In the same world</li>
 *   <li>Within 5 blocks horizontally</li>
 *   <li>No more than 3 blocks above the player</li>
 *   <li>No more than 1 block below the player</li>
 *   <li>Visible to the player (unobstructed line-of-sight)</li>
 * </ul>
 * If multiple bots qualify we pick the closest in 3D; if none qualify we send a brief
 * actionbar hint explaining the limitation. On success we teleport the bot to the player's
 * exact position, facing the player's current yaw/pitch, and zero the bot's velocity so it
 * doesn't fall through the landing.</p>
 *
 * <p>The tight constraints exist so this cannot be used to yank a bot across the map or
 * phase it through walls — it's purely a "break out of a wedge geometry" convenience for
 * when wolf-teleport isn't triggering and the short follow distance can't push through.</p>
 */
public final class RescueTeleportNetworkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("rescue-teleport");

    private static final double MAX_HORIZONTAL_DIST = 5.0D;
    private static final double MAX_HORIZONTAL_DIST_SQ = MAX_HORIZONTAL_DIST * MAX_HORIZONTAL_DIST;
    private static final double MAX_ABOVE = 3.0D;
    private static final double MAX_BELOW = 1.0D;

    private static volatile boolean REGISTERED = false;

    private RescueTeleportNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) {
            return;
        }
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(RescueTeleportRequestPayload.ID, (payload, context) ->
                context.server().execute(() -> handleRescueRequest(context.server(), context.player())));
    }

    private static void handleRescueRequest(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null || player.isRemoved()) {
            return;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld playerWorld)) {
            return;
        }

        ServerPlayerEntity best = null;
        double bestDistSq = Double.MAX_VALUE;
        int eligibleFollowers = 0;
        int failedHorizontal = 0;
        int failedVertical = 0;
        int failedSight = 0;

        UUID playerUuid = player.getUuid();
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        for (ServerPlayerEntity bot : BotRegistry.getPlayers(server)) {
            if (bot == null || bot.isRemoved() || bot == player) {
                continue;
            }
            if (bot.getEntityWorld() != playerWorld) {
                continue;
            }
            if (!BotEventHandler.isFollowingPlayer(bot)) {
                continue;
            }
            BotCommandStateService.State state = BotCommandStateService.stateFor(bot);
            if (state == null || !playerUuid.equals(state.followTargetUuid)) {
                continue;
            }
            eligibleFollowers++;

            double dx = bot.getX() - px;
            double dy = bot.getY() - py;
            double dz = bot.getZ() - pz;
            double horizSq = dx * dx + dz * dz;
            if (horizSq > MAX_HORIZONTAL_DIST_SQ) {
                failedHorizontal++;
                continue;
            }
            if (dy > MAX_ABOVE || -dy > MAX_BELOW) {
                failedVertical++;
                continue;
            }
            if (!player.canSee(bot)) {
                failedSight++;
                continue;
            }

            double distSq = horizSq + dy * dy;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = bot;
            }
        }

        if (best == null) {
            String hint = explainFailure(eligibleFollowers, failedHorizontal, failedVertical, failedSight);
            player.sendMessage(Text.literal(hint), true);
            return;
        }

        best.teleport(playerWorld,
                px,
                py,
                pz,
                EnumSet.noneOf(PositionFlag.class),
                player.getYaw(),
                player.getPitch(),
                true);
        best.setVelocity(Vec3d.ZERO);
        best.velocityDirty = true;

        LOGGER.info("Rescue teleport: bot={} -> player {} at ({}, {}, {})",
                best.getName().getString(),
                player.getName().getString(),
                (int) px, (int) py, (int) pz);

        player.sendMessage(Text.literal("Rescued " + best.getName().getString()), true);
    }

    private static String explainFailure(int eligibleFollowers,
                                         int failedHorizontal,
                                         int failedVertical,
                                         int failedSight) {
        if (eligibleFollowers == 0) {
            return "No bot is following you";
        }
        // Report the most-restrictive failure first so the player knows what to adjust.
        if (failedHorizontal > 0 && failedHorizontal >= failedVertical && failedHorizontal >= failedSight) {
            return "Rescue: bot is too far (move within 5 blocks)";
        }
        if (failedVertical > 0 && failedVertical >= failedSight) {
            return "Rescue: bot is too high or too low";
        }
        if (failedSight > 0) {
            return "Rescue: bot is out of sight";
        }
        return "Rescue: no eligible bot nearby";
    }
}
