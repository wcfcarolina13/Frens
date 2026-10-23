package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.Entity.AutoFaceEntity;

/**
 * Nether-only ritual to resurrect a companion after death.
 *
 * <p>Trigger: sneak-right-click a Respawn Anchor with a Ghast Tear in the Nether.
 *
 * <p>Questing on: brings back the recruited companion (unchanged). Questing off (finding 30,
 * Bradley 2026-10-19): brings back a dead bot the player owns (or any, for an operator or the
 * host) whose Auto Respawn is off, picked by {@link ResurrectionEligibilityPolicy}.
 */
public final class CompanionResurrectionService {

    private CompanionResurrectionService() {
    }

    // Additional (inventory) costs beyond the held Ghast Tear.
    private static final int GOLD_INGOTS_REQUIRED = 4;
    private static final int BLAZE_POWDER_REQUIRED = 1;
    private static final RitualAttemptPolicy ATTEMPTS = new RitualAttemptPolicy();

    public static ActionResult tryHandleUseBlock(ServerPlayerEntity player, ServerWorld world, Hand hand, BlockPos pos) {
        if (player == null || world == null || hand == null || pos == null) {
            return ActionResult.PASS;
        }
        if (player.isRemoved()) {
            return ActionResult.PASS;
        }

        MinecraftServer server = player.getCommandSource().getServer();
        if (server == null) {
            return ActionResult.PASS;
        }

        // Questing off: the owned-bot ritual (finding 30). Questing on: the recruited companion.
        if (!SurvivalRecruitmentService.isEnabled(server)) {
            return tryOwnedBotRitual(player, world, hand, pos, server);
        }

        ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
        if (st == null || !st.isRecruited()) {
            return ActionResult.PASS;
        }

        // Don't interfere with normal Respawn Anchor use unless the player is explicitly attempting the ritual.
        if (!player.isSneaking()) {
            return ActionResult.PASS;
        }

        ItemStack held = player.getStackInHand(hand);
        if (held == null || !held.isOf(Items.GHAST_TEAR)) {
            return ActionResult.PASS;
        }

        // Ritual must be performed in the Nether.
        if (!world.getRegistryKey().equals(World.NETHER)) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "Nothing happens.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "(This ritual only works in the Nether.)");
            return ActionResult.SUCCESS;
        }

        String alias = st.getBotAlias();
        if (!st.isCompanionDead()) {
            // Preserve vanilla Respawn Anchor behavior unless resurrection is actually needed.
            return ActionResult.PASS;
        }

        // Ensure they're trying it on a respawn anchor.
        BlockState state = world.getBlockState(pos);
        if (state == null || !state.isOf(net.minecraft.block.Blocks.RESPAWN_ANCHOR)) {
            return ActionResult.PASS;
        }

        // Require at least 1 charge to bind the ritual.
        int charges = 0;
        try {
            charges = state.get(RespawnAnchorBlock.CHARGES);
        } catch (Throwable ignored) {
            charges = 0;
        }
        if (charges <= 0) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "The anchor is inert.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "(Charge it with Glowstone first.)");
            return ActionResult.SUCCESS;
        }

        if (!questRitualAuthorized(player, st)) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "You can't perform this ritual.");
            return ActionResult.SUCCESS;
        }

        long deathEpoch = st.getCompanionDiedAtEpochMs();
        return completeRitual(player, world, server, pos, hand, alias,
                () -> SurvivalRecruitmentService.isEnabled(server)
                        && SurvivalRecruitmentService.getState(server) == st
                        && st.isRecruited() && st.isCompanionDead()
                        && alias.equals(st.getBotAlias())
                        && st.getCompanionDiedAtEpochMs() == deathEpoch
                        && questRitualAuthorized(player, st), () -> {
            st.setCompanionDead(false);
            st.setCompanionDiedAtEpochMs(0L);
            st.setCompanionDiedDimension(null);
            st.setCompanionDiedPos(0L);
            if (Frens.CONFIG != null) {
                Frens.CONFIG.save();
            }
        });
    }

    /**
     * Questing off: the same ritual for a dead, shelved bot the player may act on. Silent PASS
     * (vanilla anchor use) until the player sneaks with a Ghast Tear on a Respawn Anchor and some
     * bot qualifies, so Admin worlds without a dead bot never see ritual messages.
     */
    private static ActionResult tryOwnedBotRitual(ServerPlayerEntity player, ServerWorld world, Hand hand,
                                                  BlockPos pos, MinecraftServer server) {
        if (!player.isSneaking()) {
            return ActionResult.PASS;
        }
        ItemStack held = player.getStackInHand(hand);
        if (held == null || !held.isOf(Items.GHAST_TEAR)) {
            return ActionResult.PASS;
        }
        BlockState state = world.getBlockState(pos);
        if (state == null || !state.isOf(net.minecraft.block.Blocks.RESPAWN_ANCHOR)) {
            return ActionResult.PASS;
        }
        if (Frens.CONFIG == null) {
            return ActionResult.PASS;
        }

        String worldKey = BotWorldStateService.currentWorldKey(server);
        String alias = ResurrectionEligibilityPolicy.chooseBot(player.getUuid(), Frens.isOperator(player),
                net.wcfcarolina13.network.BotAccessGate.isHost(player), gatherCandidates(server, worldKey));
        if (alias == null) {
            return ActionResult.PASS;
        }

        if (!world.getRegistryKey().equals(World.NETHER)) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "Nothing happens.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "(This ritual only works in the Nether.)");
            return ActionResult.SUCCESS;
        }

        int charges = 0;
        try {
            charges = state.get(RespawnAnchorBlock.CHARGES);
        } catch (Throwable ignored) {
            charges = 0;
        }
        if (charges <= 0) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "The anchor is inert.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "(Charge it with Glowstone first.)");
            return ActionResult.SUCCESS;
        }

        BotDeathRecordService.DeathRecord death = BotDeathRecordService.getBotRecord(server, alias);
        return completeRitual(player, world, server, pos, hand, alias,
                () -> !SurvivalRecruitmentService.isEnabled(server)
                        && death != null && BotDeathRecordService.getBotRecord(server, alias) == death
                        && ownedRitualAuthorized(player, alias, worldKey), () -> {
            // Un-shelve: drop the session yes/no prompt and let the bot load with the world again.
            BotRespawnPromptService.clear(alias);
            Frens.CONFIG.getOrCreateBotControl(alias, worldKey).setAutoSpawnOnLoad(true);
            Frens.CONFIG.save();
        });
    }

    /** Every known bot of this world as a policy candidate. Server thread. */
    private static java.util.List<ResurrectionEligibilityPolicy.Candidate> gatherCandidates(MinecraftServer server,
                                                                                          String worldKey) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.Set<String> aliases = new java.util.LinkedHashSet<>(Frens.CONFIG.getAllBotAliases());
        aliases.addAll(Frens.CONFIG.getBotOwnership().keySet());
        java.util.List<ResurrectionEligibilityPolicy.Candidate> out = new java.util.ArrayList<>();
        for (String alias : aliases) {
            if (alias == null || alias.isBlank() || "default".equalsIgnoreCase(alias)
                    || !seen.add(alias.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            BotDeathRecordService.DeathRecord death = BotDeathRecordService.getBotRecord(server, alias);
            ManualConfig.BotOwnership owner = Frens.CONFIG.getOwner(alias);
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(alias);
            ManualConfig.BotControlSettings ctrl = Frens.CONFIG.getEffectiveBotControl(alias, worldKey);
            // No settings → Auto Respawn on, as the death hook in Frens reads it.
            boolean autoRespawn = ctrl == null || ctrl.isAutoRespawnOnDeath();
            out.add(new ResurrectionEligibilityPolicy.Candidate(
                    alias,
                    ResurrectionEligibilityPolicy.parseOwner(owner != null ? owner.ownerUuid() : null),
                    death != null ? death.epochMs() : null,
                    online != null && !online.isRemoved() && online.isAlive(),
                    autoRespawn));
        }
        return out;
    }

    private static boolean questRitualAuthorized(ServerPlayerEntity player, ManualConfig.SurvivalRecruitmentState state) {
        return Frens.isOperator(player) || net.wcfcarolina13.network.BotAccessGate.isHost(player)
                || player.getUuidAsString().equals(state.getRecruitedByUuid());
    }

    private static boolean ownedRitualAuthorized(ServerPlayerEntity player, String alias, String worldKey) {
        ManualConfig.BotControlSettings ctrl = Frens.CONFIG.getEffectiveBotControl(alias, worldKey);
        ManualConfig.BotOwnership owner = Frens.CONFIG.getOwner(alias);
        return ctrl != null && !ctrl.isAutoRespawnOnDeath()
                && ResurrectionEligibilityPolicy.authorized(player.getUuid(),
                        ResurrectionEligibilityPolicy.parseOwner(owner == null ? null : owner.ownerUuid()),
                        Frens.isOperator(player), net.wcfcarolina13.network.BotAccessGate.isHost(player));
    }

    /** Offerings and alive state change only after connection succeeds, in the same server callback. */
    private static ActionResult completeRitual(ServerPlayerEntity player, ServerWorld world, MinecraftServer server,
                                               BlockPos pos, Hand hand, String alias,
                                               java.util.function.BooleanSupplier eligible, Runnable markAlive) {
        ServerPlayerEntity present = server.getPlayerManager().getPlayer(alias);
        if (present != null && !present.isRemoved() && present.isAlive() && !(present instanceof createFakePlayer)) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "A real player named '" + alias + "' is online.");
            return ActionResult.SUCCESS;
        }
        if (!offeringPresent(player, hand)) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "The ritual demands a Ghast Tear, "
                    + GOLD_INGOTS_REQUIRED + " gold ingots and " + BLAZE_POWDER_REQUIRED + " blaze powder.");
            return ActionResult.SUCCESS;
        }
        RitualAttemptPolicy.Attempt attempt = ATTEMPTS.claim(server, alias);
        if (attempt == null) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "A ritual for " + alias + " is already underway.");
            return ActionResult.SUCCESS;
        }
        ManualConfig config = Frens.CONFIG;
        String worldKey = BotWorldStateService.currentWorldKey(server);
        String owner = ownerIdentity(config, alias);
        BlockPos anchorPos = pos.toImmutable();
        ServerPlayerEntity existing = present != null && !present.isRemoved() && present.isAlive() ? present : null;
        boolean[] anchorConsumed = {false};
        boolean[] itemsConsumed = {false};
        java.util.function.BooleanSupplier current = () -> server.isOnThread()
                && !TaskService.isServerStopping() && !server.isStopped()
                && (Frens.serverInstance == null || Frens.serverInstance == server)
                && Frens.CONFIG == config && config != null
                && server.getWorld(world.getRegistryKey()) == world
                && worldKey.equals(BotWorldStateService.currentWorldKey(server))
                && server.getPlayerManager().getPlayer(player.getUuid()) == player
                && !player.isRemoved() && player.isAlive() && player.getEntityWorld() == world
                && player.getBlockPos().getSquaredDistance(anchorPos) <= 64
                && java.util.Objects.equals(owner, ownerIdentity(config, alias))
                && ATTEMPTS.isCurrent(attempt) && eligible.getAsBoolean();
        java.util.function.Consumer<Runnable> connectionAttempt = connect -> ATTEMPTS.execute(attempt,
                () -> current.getAsBoolean() && offeringPresent(player, hand) && chargedAnchor(world, anchorPos)
                        && sameTarget(server.getPlayerManager().getPlayer(alias), existing),
                connect, () -> {
                    // The new target is now online; do not re-run the pre-connection offline check.
                    ServerPlayerEntity bot = server.getPlayerManager().getPlayer(alias);
                    if (!current.getAsBoolean() || !(bot instanceof createFakePlayer)
                            || bot.isRemoved() || !bot.isAlive() || bot.getEntityWorld() != world
                            || bot.getBlockPos().getSquaredDistance(anchorPos) > 64
                            || !offeringPresent(player, hand) || !chargedAnchor(world, anchorPos)) {
                        throw new IllegalStateException("The companion connected but ritual conditions changed before payment");
                    }
                    BlockState anchor = world.getBlockState(anchorPos);
                    if (!world.setBlockState(anchorPos,
                            anchor.with(RespawnAnchorBlock.CHARGES, anchor.get(RespawnAnchorBlock.CHARGES) - 1), 3)) {
                        throw new IllegalStateException("The companion connected but the anchor refused its charge update");
                    }
                    anchorConsumed[0] = true;
                    if (!player.getAbilities().creativeMode) {
                        player.getStackInHand(hand).decrement(1);
                        consumeFromInventory(player, Items.GOLD_INGOT, GOLD_INGOTS_REQUIRED);
                        consumeFromInventory(player, Items.BLAZE_POWDER, BLAZE_POWDER_REQUIRED);
                        itemsConsumed[0] = true;
                    }
                    markAlive.run();
                    BotEventHandler.registerBot(bot);
                    AutoFaceEntity.startAutoFace(bot);
                    ChatUtils.sendSystemMessage(player.getCommandSource(), "\u00A7a" + alias + " returns.\u00A7r");
                });
        try {
            Vec3d spawn = new Vec3d(player.getX() + 1.0, player.getY(), player.getZ() + 1.0);
            ManualConfig.BotControlSettings ctrl = config.getEffectiveBotControl(alias, worldKey);
            GameMode mode = ctrl != null && "creative".equalsIgnoreCase(ctrl.getGameMode())
                    ? GameMode.CREATIVE : GameMode.SURVIVAL;
            ChatUtils.sendSystemMessage(player.getCommandSource(), "Preparing the ritual for " + alias + "...");
            java.util.concurrent.CompletableFuture<Void> result;
            if (existing != null) {
                connectionAttempt.accept(() -> {
                    if (!existing.teleport(world, spawn.x, spawn.y, spawn.z, java.util.Set.of(),
                            player.getYaw(), player.getPitch(), true)) {
                        throw new IllegalStateException("The companion could not reach the ritual site");
                    }
                    existing.setVelocity(Vec3d.ZERO);
                    existing.interactionManager.changeGameMode(mode);
                });
                result = java.util.concurrent.CompletableFuture.completedFuture(null);
            } else {
                result = createFakePlayer.createFake(alias, server, spawn, player.getYaw(), player.getPitch(),
                        world.getRegistryKey(), mode, false, connectionAttempt);
            }
            result.whenComplete((ignored, error) -> {
                ATTEMPTS.release(attempt);
                if (error != null) reportRitualFailure(player, server, config, world, alias, error,
                        anchorConsumed[0], itemsConsumed[0]);
            });
        } catch (Throwable error) {
            ATTEMPTS.release(attempt);
            reportRitualFailure(player, server, config, world, alias, error, anchorConsumed[0], itemsConsumed[0]);
        }
        return ActionResult.SUCCESS;
    }

    private static boolean sameTarget(ServerPlayerEntity current, ServerPlayerEntity expected) {
        return expected == null ? current == null || current.isRemoved() || !current.isAlive()
                : current == expected && !current.isRemoved() && current.isAlive();
    }

    private static String ownerIdentity(ManualConfig config, String alias) {
        ManualConfig.BotOwnership owner = config == null ? null : config.getOwner(alias);
        return owner == null ? null : owner.ownerUuid();
    }

    private static boolean chargedAnchor(ServerWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) return false;
        BlockState state = world.getBlockState(pos);
        return state.isOf(net.minecraft.block.Blocks.RESPAWN_ANCHOR) && state.get(RespawnAnchorBlock.CHARGES) > 0;
    }

    private static boolean offeringPresent(ServerPlayerEntity player, Hand hand) {
        return player.getStackInHand(hand).isOf(Items.GHAST_TEAR)
                && (player.getAbilities().creativeMode
                || hasItemCount(player, Items.GOLD_INGOT, GOLD_INGOTS_REQUIRED)
                && hasItemCount(player, Items.BLAZE_POWDER, BLAZE_POWDER_REQUIRED));
    }

    private static void reportRitualFailure(ServerPlayerEntity player, MinecraftServer server, ManualConfig config,
                                           ServerWorld world, String alias, Throwable error,
                                           boolean anchorConsumed, boolean itemsConsumed) {
        Frens.LOGGER.warn("Ritual for {} did not complete (anchorConsumed={}, itemsConsumed={})",
                alias, anchorConsumed, itemsConsumed, error);
        if (server.isOnThread() && !TaskService.isServerStopping() && !server.isStopped()
                && Frens.CONFIG == config && server.getWorld(world.getRegistryKey()) == world
                && server.getPlayerManager().getPlayer(player.getUuid()) == player && !player.isRemoved()) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "The ritual did not complete. "
                    + (anchorConsumed ? "The anchor charge was used. " : "The anchor charge was kept. ")
                    + (itemsConsumed ? "The offering was used. " : "The offering was kept. ")
                    + "The companion may already be connected. Check the server log before retrying.");
        }
    }

    private static boolean hasItemCount(ServerPlayerEntity player, Item item, int needed) {
        if (needed <= 0) {
            return true;
        }
        if (player == null || item == null) {
            return false;
        }
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack st = player.getInventory().getStack(i);
            if (st != null && st.isOf(item)) {
                count += st.getCount();
                if (count >= needed) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void consumeFromInventory(ServerPlayerEntity player, Item item, int count) {
        if (count <= 0 || player == null || item == null) {
            return;
        }
        int remaining = count;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack st = player.getInventory().getStack(i);
            if (st == null || !st.isOf(item)) {
                continue;
            }
            int take = Math.min(remaining, st.getCount());
            st.decrement(take);
            remaining -= take;
        }
    }
}
