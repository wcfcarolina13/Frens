package net.wcfcarolina13.network;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.NavigationArtifactService;
import net.wcfcarolina13.GameAI.services.TravelMountHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Handles navigation-related network communication:
 * - NavigationResponsePayload (C2S, player accepts/dismisses auto-return)
 * - SpellGuidancePayload (C2S, player casts Remote Guidance or Chorus Recall)
 * - BotNavTierPayload (S2C, sent on spells screen open via helper method)
 */
public final class SpellNavigationNetworkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpellNavigationNetworkManager.class);
    private static boolean REGISTERED = false;

    private SpellNavigationNetworkManager() {}

    public static void registerReceiversOnce() {
        if (REGISTERED) return;
        REGISTERED = true;

        ServerPlayNetworking.registerGlobalReceiver(NavigationResponsePayload.ID, (payload, context) ->
                context.server().execute(() -> handleNavigationResponse(context.server(), context.player(), payload))
        );

        ServerPlayNetworking.registerGlobalReceiver(SpellGuidancePayload.ID, (payload, context) ->
                context.server().execute(() -> handleSpellCast(context.server(), context.player(), payload))
        );
    }

    // ── NavigationResponse (auto-return accept/dismiss) ──────────────────

    private static void handleNavigationResponse(MinecraftServer server, ServerPlayerEntity player,
                                                  NavigationResponsePayload payload) {
        if (server == null || player == null || player.isRemoved()) return;
        if (!payload.accepted()) {
            LOGGER.debug("Player {} dismissed auto-return for bot {}", player.getName().getString(), payload.botAlias());
            return;
        }

        // Find the bot via player manager
        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(payload.botAlias());
        if (bot == null || bot.isRemoved()) {
            LOGGER.warn("Auto-return accepted but bot '{}' not found", payload.botAlias());
            return;
        }

        // resolveHomeTarget returns Optional<BlockPos>
        BotHomeService.resolveHomeTarget(bot).ifPresent(homePos ->
                BotEventHandler.setReturnToBase(bot, Vec3d.ofCenter(homePos)));
    }

    // ── SpellGuidance (Remote Guidance / Chorus Recall) ──────────────────

    private static void handleSpellCast(MinecraftServer server, ServerPlayerEntity commander,
                                         SpellGuidancePayload payload) {
        if (server == null || commander == null || commander.isRemoved()) return;

        String botAlias = payload.botAlias();
        String spellType = payload.spellType();
        String destination = payload.destination();

        if (botAlias == null || botAlias.isBlank() || spellType == null || destination == null) {
            LOGGER.warn("Invalid spell guidance payload from {}", commander.getName().getString());
            return;
        }

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botAlias);
        if (bot == null || bot.isRemoved()) {
            sendFeedback(commander, "Companion '" + botAlias + "' is not online.");
            return;
        }

        switch (spellType) {
            case "guidance" -> handleRemoteGuidance(server, commander, bot, destination);
            case "recall" -> handleChorusRecall(server, commander, bot, destination);
            default -> LOGGER.warn("Unknown spell type '{}' from {}", spellType, commander.getName().getString());
        }
    }

    /**
     * Remote Guidance: consume ender pearls from both commander and bot, then
     * navigate the bot toward the destination (player location or a named base).
     */
    private static void handleRemoteGuidance(MinecraftServer server, ServerPlayerEntity commander,
                                              ServerPlayerEntity bot, String destination) {
        // Validate both hold ender pearls
        if (!NavigationArtifactService.bothHaveEnderPearl(commander, bot)) {
            sendFeedback(commander, "Both you and your companion must hold an Ender Pearl.");
            return;
        }

        // Resolve destination position
        BlockPos goal;
        if ("player".equalsIgnoreCase(destination)) {
            goal = commander.getBlockPos();
        } else {
            // Look up base by label in the commander's current world
            ServerWorld world = (ServerWorld) commander.getEntityWorld();
            Optional<BlockPos> baseOpt = BotHomeService.getBaseByLabel(server, world, destination);
            if (baseOpt.isEmpty()) {
                sendFeedback(commander, "Base '" + destination + "' not found.");
                return;
            }
            goal = baseOpt.get();
        }

        // Consume pearls from both
        NavigationArtifactService.consumeItem(commander, Items.ENDER_PEARL);
        NavigationArtifactService.consumeItem(bot, Items.ENDER_PEARL);

        // Play acceptance sound at commander
        ServerWorld commanderWorld = (ServerWorld) commander.getEntityWorld();
        commanderWorld.playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                SoundEvents.ENTITY_ENDER_EYE_LAUNCH, SoundCategory.PLAYERS, 0.7f, 1.2f);

        // Fast travel: bot disappears and reappears at destination after a delay (~1s/chunk).
        ServerWorld targetWorld = (ServerWorld) commander.getEntityWorld();
        RegistryKey<World> targetDim = targetWorld.getRegistryKey();
        double distance = new Vec3d(bot.getX(), bot.getY(), bot.getZ()).distanceTo(Vec3d.ofCenter(goal));
        boolean crossDim = !((ServerWorld) bot.getEntityWorld()).getRegistryKey().equals(targetDim);
        int delayTicks = NavigationArtifactService.calculateDelayTicks(distance, crossDim);

        NavigationArtifactService.beginDelayedTravel(
                server, bot, bot.getName().getString(), goal, targetDim, delayTicks, commander.getUuid());

        int sec = Math.max(1, delayTicks / 20);
        sendFeedback(commander, "Remote Guidance cast. " + bot.getName().getString()
                + " is fast-traveling to destination (ETA ~" + sec + "s).");
        LOGGER.info("Remote Guidance: {} sent bot '{}' via fast travel to {} (delay {}t)",
                commander.getName().getString(), bot.getName().getString(),
                goal.toShortString(), delayTicks);
    }

    /**
     * Chorus Recall: consume ender pearl + chorus fruit from both commander and bot,
     * then teleport based on direction ("bot_to_player" or "player_to_bot").
     */
    private static void handleChorusRecall(MinecraftServer server, ServerPlayerEntity commander,
                                            ServerPlayerEntity bot, String direction) {
        // Validate both hold pearl + chorus
        if (!NavigationArtifactService.bothHaveChorusRecallItems(commander, bot)) {
            sendFeedback(commander, "Both you and your companion must hold an Ender Pearl and a Chorus Fruit.");
            return;
        }

        // ── Mount evaluation (bot_to_player only) ────────────────────────
        // Must run BEFORE consuming items so refusals don't waste reagents.
        TravelMountHandler.MountTravelResult mountResult = null;
        if ("bot_to_player".equalsIgnoreCase(direction)) {
            ServerWorld targetWorld = (ServerWorld) commander.getEntityWorld();
            BlockPos targetPos = commander.getBlockPos();
            mountResult = TravelMountHandler.evaluateTravel(
                    bot, targetPos, targetWorld.getRegistryKey(), targetWorld);

            switch (mountResult.decision()) {
                case REFUSE_FULL_INVENTORY, REFUSE_NO_ROOM_AT_DEST, REFUSE_CROSS_DIM_ANIMAL -> {
                    commander.sendMessage(Text.literal("\u00A7c" + mountResult.message()), false);
                    return;
                }
                case TETHERED_CROSS_DIM -> {
                    commander.sendMessage(Text.literal("\u00A7e" + mountResult.message()), false);
                }
                default -> {}
            }
        }

        // Consume all 4 items (pearl + chorus from both)
        NavigationArtifactService.consumeItem(commander, Items.ENDER_PEARL);
        NavigationArtifactService.consumeItem(commander, Items.CHORUS_FRUIT);
        NavigationArtifactService.consumeItem(bot, Items.ENDER_PEARL);
        NavigationArtifactService.consumeItem(bot, Items.CHORUS_FRUIT);

        // Play cast sound at commander
        ServerWorld commanderWorld = (ServerWorld) commander.getEntityWorld();
        commanderWorld.playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.6f, 1.4f);

        // Teleport based on direction
        if ("bot_to_player".equalsIgnoreCase(direction)) {
            // Teleport bot to commander
            teleportEntity(bot, commanderWorld, commander.getBlockPos(), commander.getYaw(), commander.getPitch());
            LOGGER.info("Chorus Recall: teleported bot '{}' to player {}", bot.getName().getString(),
                    commander.getName().getString());

            // Co-teleport animal mount if applicable
            if (mountResult != null
                    && mountResult.decision() == TravelMountHandler.MountTravelDecision.PROCEED_WITH_ANIMAL
                    && mountResult.mountEntity() != null) {
                TravelMountHandler.teleportAnimalWithBot(bot, mountResult.mountEntity(),
                        commanderWorld, commander.getBlockPos());
            }
        } else if ("player_to_bot".equalsIgnoreCase(direction)) {
            // Teleport commander to bot
            ServerWorld botWorld = (ServerWorld) bot.getEntityWorld();
            teleportEntity(commander, botWorld, bot.getBlockPos(), bot.getYaw(), bot.getPitch());
            LOGGER.info("Chorus Recall: teleported player {} to bot '{}'", commander.getName().getString(),
                    bot.getName().getString());
        } else {
            LOGGER.warn("Unknown Chorus Recall direction '{}' from {}", direction, commander.getName().getString());
            sendFeedback(commander, "Invalid recall direction.");
            return;
        }

        // Play arrival sound at the destination
        ServerWorld arrivalWorld = "player_to_bot".equalsIgnoreCase(direction)
                ? (ServerWorld) bot.getEntityWorld()
                : commanderWorld;
        double arrivalX = "player_to_bot".equalsIgnoreCase(direction) ? bot.getX() : commander.getX();
        double arrivalY = "player_to_bot".equalsIgnoreCase(direction) ? bot.getY() : commander.getY();
        double arrivalZ = "player_to_bot".equalsIgnoreCase(direction) ? bot.getZ() : commander.getZ();
        arrivalWorld.playSound(null, arrivalX, arrivalY, arrivalZ,
                SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);

        sendFeedback(commander, "Chorus Recall complete.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Teleport a player entity to a target position in the given world.
     * Uses the same pattern as the existing summon/teleport logic in modCommandRegistry.
     */
    private static void teleportEntity(ServerPlayerEntity entity, ServerWorld world, BlockPos target,
                                        float yaw, float pitch) {
        Vec3d center = new Vec3d(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D);
        entity.teleport(world, center.x, center.y, center.z,
                EnumSet.noneOf(net.minecraft.network.packet.s2c.play.PositionFlag.class),
                yaw, pitch, true);
        entity.setVelocity(Vec3d.ZERO);
    }

    private static void sendFeedback(ServerPlayerEntity player, String message) {
        if (player == null || message == null) return;
        ChatUtils.sendSystemMessage(player.getCommandSource(), message);
    }

    /** Send nav tier to client when spells screen is opened. */
    public static void sendNavTierToClient(ServerPlayerEntity player, ServerPlayerEntity bot, String botAlias) {
        if (player == null || bot == null) return;
        NavigationArtifactService.NavTier tier = NavigationArtifactService.getBotNavigationTier(bot, player);
        ServerPlayNetworking.send(player, new BotNavTierPayload(
                botAlias != null ? botAlias : "", tier.ordinal()));
    }
}
