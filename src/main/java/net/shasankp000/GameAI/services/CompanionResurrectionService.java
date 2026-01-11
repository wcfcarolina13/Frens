package net.shasankp000.GameAI.services;

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
import net.shasankp000.AIPlayer;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.Entity.createFakePlayer;
import net.shasankp000.FilingSystem.ManualConfig;
import net.shasankp000.GameAI.BotEventHandler;
import net.shasankp000.Entity.AutoFaceEntity;

/**
 * Nether-only ritual to resurrect the recruited companion after death.
 *
 * <p>Trigger: sneak-right-click a Respawn Anchor with a Ghast Tear in the Nether.
 */
public final class CompanionResurrectionService {

    private CompanionResurrectionService() {
    }

    // Additional (inventory) costs beyond the held Ghast Tear.
    private static final int GOLD_INGOTS_REQUIRED = 4;
    private static final int BLAZE_POWDER_REQUIRED = 1;

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

        // Only applies to survival recruitment worlds.
        if (!SurvivalRecruitmentService.isEnabled(server)) {
            return ActionResult.PASS;
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

        // Authorization: same spirit as recruitment reset (operators always; integrated server convenience; dedicated -> recruiter).
        boolean authorized = false;
        if (AIPlayer.isOperator(player)) {
            authorized = true;
        } else if (!server.isDedicated()) {
            authorized = true;
        } else {
            String recruiter = st.getRecruitedByUuid();
            if (recruiter != null && !recruiter.isBlank() && recruiter.equals(player.getUuidAsString())) {
                authorized = true;
            }
        }

        if (!authorized) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "You can't perform this ritual.");
            return ActionResult.SUCCESS;
        }

        // Refuse if a real player is online with that name.
        ServerPlayerEntity existing = server.getPlayerManager().getPlayer(alias);
        if (existing != null && !existing.isRemoved() && existing.isAlive() && !(existing instanceof createFakePlayer)) {
            ChatUtils.sendSystemMessage(player.getCommandSource(), "A real player named '" + alias + "' is online.");
            ChatUtils.sendSystemMessage(player.getCommandSource(), "The ritual can't find its target.");
            return ActionResult.SUCCESS;
        }

        // Check required items.
        if (!player.getAbilities().creativeMode) {
            if (!hasItemCount(player, Items.GOLD_INGOT, GOLD_INGOTS_REQUIRED) || !hasItemCount(player, Items.BLAZE_POWDER, BLAZE_POWDER_REQUIRED)) {
                ChatUtils.sendSystemMessage(player.getCommandSource(), "The ritual demands an offering.");
                ChatUtils.sendSystemMessage(player.getCommandSource(), "Required: " + GOLD_INGOTS_REQUIRED + " gold ingots + " + BLAZE_POWDER_REQUIRED + " blaze powder.");
                return ActionResult.SUCCESS;
            }
        }

        // Consume items + anchor charge, then resurrect.
        if (!player.getAbilities().creativeMode) {
            held.decrement(1);
            consumeFromInventory(player, Items.GOLD_INGOT, GOLD_INGOTS_REQUIRED);
            consumeFromInventory(player, Items.BLAZE_POWDER, BLAZE_POWDER_REQUIRED);
        }

        // Decrement anchor charges by 1.
        try {
            world.setBlockState(pos, state.with(RespawnAnchorBlock.CHARGES, Math.max(0, charges - 1)), 3);
        } catch (Throwable ignored) {
            // If state update fails, continue anyway.
        }

        // Clear "dead" state before spawning so auto-presence won't race.
        st.setCompanionDead(false);
        st.setCompanionDiedAtEpochMs(0L);
        st.setCompanionDiedDimension(null);
        st.setCompanionDiedPos(0L);
        if (AIPlayer.CONFIG != null) {
            AIPlayer.CONFIG.save();
        }

        // Spawn (or rebind) the companion near the player in the Nether.
        Vec3d spawn = new Vec3d(player.getX() + 1.0, player.getY(), player.getZ() + 1.0);
        GameMode desiredMode = GameMode.SURVIVAL;
        if (AIPlayer.CONFIG != null) {
            ManualConfig.BotControlSettings ctrl = AIPlayer.CONFIG.getEffectiveBotControl(alias);
            if (ctrl != null && "creative".equalsIgnoreCase(ctrl.getGameMode())) {
                desiredMode = GameMode.CREATIVE;
            }
        }

        ChatUtils.sendSystemMessage(player.getCommandSource(), "You offer the tear to the anchor...");

        if (existing != null && !existing.isRemoved() && existing.isAlive() && (existing instanceof createFakePlayer)) {
            // If the fake player is somehow still online, just pull them to the ritual site.
            existing.teleport(world, spawn.x, spawn.y, spawn.z, java.util.Set.of(), player.getYaw(), player.getPitch(), true);
            existing.setVelocity(Vec3d.ZERO);
            try {
                existing.interactionManager.changeGameMode(desiredMode);
            } catch (Throwable ignored) {
            }
        } else {
            createFakePlayer.createFake(alias, server, spawn, player.getYaw(), player.getPitch(), world.getRegistryKey(), desiredMode, false);
        }

        // Ensure the bot is registered/active for the rest of the mod systems.
        server.execute(() -> {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(alias);
            if (bot != null && !bot.isRemoved() && bot instanceof createFakePlayer) {
                try {
                    BotEventHandler.registerBot(bot);
                } catch (Throwable ignored) {
                }
                try {
                    AutoFaceEntity.startAutoFace(bot);
                } catch (Throwable ignored) {
                }
            }
        });

        ChatUtils.sendSystemMessage(player.getCommandSource(), "\u00A7a" + alias + " returns.\u00A7r");
        return ActionResult.SUCCESS;
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
