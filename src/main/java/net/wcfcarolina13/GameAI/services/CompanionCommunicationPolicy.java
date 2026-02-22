package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.FilingSystem.ManualConfig;

import java.util.UUID;

/**
 * Central policy for routing and gating bot-to-commander communications.
 */
public final class CompanionCommunicationPolicy {

    // "Visible" range is approximate; we keep it conservative and cheap.
    public static final double VISIBLE_RANGE_BLOCKS = 32.0D;

    private static final Identifier WIZARD_TOME_ID = Identifier.of(Frens.MOD_ID, "wizard_tome");

    private CompanionCommunicationPolicy() {
    }

    /**
     * Resolve the controlling/owning player for a bot.
     *
     * <p>Primary source: {@link ManualConfig.BotOwnership} (per-alias owner).
     * Fallback: survival recruitment state's recruitedByUuid when it matches the recruited companion alias.
     */
    public static ServerPlayerEntity resolveController(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null) {
            return null;
        }

        UUID ownerUuid = resolveOwnerUuid(bot);
        if (ownerUuid != null) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(ownerUuid);
            if (p != null && !p.isRemoved()) {
                return p;
            }
        }

        // Fallback: in survival recruitment mode, use the recruiter for the recruited companion.
        try {
            if (Frens.CONFIG != null) {
                ManualConfig.SurvivalRecruitmentState st = SurvivalRecruitmentService.getState(server);
                if (st != null) {
                    String recruitedAlias = st.getBotAlias();
                    if (recruitedAlias != null && recruitedAlias.equalsIgnoreCase(bot.getName().getString())) {
                        String recruiterUuid = st.getRecruitedByUuid();
                        if (recruiterUuid != null && !recruiterUuid.isBlank()) {
                            return server.getPlayerManager().getPlayer(UUID.fromString(recruiterUuid));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    /**
     * True if the bot is allowed to deliver chat (DM) messages to the controller.
     *
     * <p>Rules (as requested):
     * <ul>
     *   <li>Allowed if within visible range.</li>
     *   <li>Otherwise allowed if:
     *     <ul>
     *       <li>both are holding Eye of Ender OR Goat Horn, OR</li>
     *       <li>either has a Wizard's Tome, OR</li>
     *       <li>either is near an enchanting table.</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    public static boolean canBotChatToController(ServerPlayerEntity bot, ServerPlayerEntity controller) {
        if (bot == null || controller == null || bot.isRemoved() || controller.isRemoved()) {
            return false;
        }

        if (isWithinVisibleRange(bot, controller, VISIBLE_RANGE_BLOCKS)) {
            return true;
        }

        boolean bothHolding = isHoldingCommItem(bot) && isHoldingCommItem(controller);
        if (bothHolding) {
            return true;
        }

        if (hasWizardTome(bot) || hasWizardTome(controller)) {
            return true;
        }

        if (isNearEnchantingTable(bot, 4) || isNearEnchantingTable(controller, 4)) {
            return true;
        }

        return false;
    }

    public static boolean isWithinVisibleRange(ServerPlayerEntity bot, ServerPlayerEntity controller, double rangeBlocks) {
        if (bot == null || controller == null) {
            return false;
        }
        if (bot.getEntityWorld() != controller.getEntityWorld()) {
            return false;
        }
        double r = Math.max(0.0D, rangeBlocks);
        return controller.squaredDistanceTo(bot) <= r * r;
    }

    private static UUID resolveOwnerUuid(ServerPlayerEntity bot) {
        if (bot == null) {
            return null;
        }
        try {
            if (Frens.CONFIG == null) {
                return null;
            }
            String alias = bot.getName().getString();
            ManualConfig.BotOwnership o = Frens.CONFIG.getOwner(alias);
            if (o == null) {
                return null;
            }
            String uuid = o.ownerUuid();
            if (uuid == null || uuid.isBlank()) {
                return null;
            }
            return UUID.fromString(uuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isHoldingCommItem(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        return isCommItem(player.getMainHandStack()) || isCommItem(player.getOffHandStack());
    }

    private static boolean isCommItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item == Items.ENDER_EYE || item == Items.GOAT_HORN;
    }

    private static boolean hasWizardTome(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        try {
            Item tome = Registries.ITEM.get(WIZARD_TOME_ID);
            if (tome == null || tome == Items.AIR) {
                return false;
            }
            return player.getInventory().contains(new ItemStack(tome));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isNearEnchantingTable(ServerPlayerEntity player, int radius) {
        if (player == null) {
            return false;
        }
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        int r = Math.max(0, radius);
        BlockPos base = player.getBlockPos();
        BlockPos min = base.add(-r, -r, -r);
        BlockPos max = base.add(r, r, r);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState st = world.getBlockState(pos);
            if (st != null && st.isOf(Blocks.ENCHANTING_TABLE)) {
                return true;
            }
        }
        return false;
    }
}
