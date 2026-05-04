package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot diagnostic logging for the bot anvil/enchant inventory bug
 * (items appear to combine/enchant in-screen but revert when the bot
 * inventory is re-opened). Captures slot state at every read/write
 * boundary so a single repro pinpoints where the divergence occurs.
 */
public final class BotAnvilEnchantDiagnostics {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-anvil-enchant-diag");

    private BotAnvilEnchantDiagnostics() {}

    public static void logTakeOutput(String tag,
                                     ServerPlayerEntity bot,
                                     PlayerEntity viewer,
                                     ItemStack outputStack,
                                     int levelCost,
                                     ItemStack input0,
                                     ItemStack input1) {
        LOGGER.info("[{}] bot={} viewer={} cost={} botXp={} viewerXp={} output={} input0={} input1={} cursor={} botInvSummary={}",
                tag,
                bot.getName().getString(),
                viewer != null ? viewer.getName().getString() : "<null>",
                levelCost,
                bot.experienceLevel,
                viewer != null ? viewer.experienceLevel : -1,
                describe(outputStack),
                describe(input0),
                describe(input1),
                describe(viewer != null ? viewer.currentScreenHandler.getCursorStack() : ItemStack.EMPTY),
                summarizeInventory(bot.getInventory()));
    }

    public static void logEnchantClick(String tag,
                                       ServerPlayerEntity bot,
                                       int buttonId,
                                       int cost,
                                       ItemStack slot0,
                                       ItemStack slot1) {
        LOGGER.info("[{}] bot={} button={} cost={} botXp={} slot0={} slot1={} botInvSummary={}",
                tag,
                bot.getName().getString(),
                buttonId,
                cost,
                bot.experienceLevel,
                describe(slot0),
                describe(slot1),
                summarizeInventory(bot.getInventory()));
    }

    public static void logScreenClose(String tag,
                                      ServerPlayerEntity bot,
                                      PlayerEntity viewer,
                                      ItemStack cursor,
                                      ItemStack slot0,
                                      ItemStack slot1) {
        LOGGER.info("[{}] bot={} viewer={} cursor={} slot0={} slot1={} botInvSummary={}",
                tag,
                bot.getName().getString(),
                viewer != null ? viewer.getName().getString() : "<null>",
                describe(cursor),
                describe(slot0),
                describe(slot1),
                summarizeInventory(bot.getInventory()));
    }

    public static void logInventoryOpen(String tag,
                                        ServerPlayerEntity bot,
                                        PlayerEntity viewer) {
        LOGGER.info("[{}] bot={} viewer={} botInvSummary={}",
                tag,
                bot.getName().getString(),
                viewer != null ? viewer.getName().getString() : "<null>",
                summarizeInventory(bot.getInventory()));
    }

    public static void logSlotClick(String tag,
                                    ServerPlayerEntity bot,
                                    PlayerEntity viewer,
                                    int slotIndex,
                                    int button,
                                    net.minecraft.screen.slot.SlotActionType actionType,
                                    String slotDesc,
                                    int levelCost) {
        LOGGER.info("[{}] bot={} viewer={} slot={} button={} action={} levelCost={} {}",
                tag,
                bot.getName().getString(),
                viewer != null ? viewer.getName().getString() : "<null>",
                slotIndex,
                button,
                actionType,
                levelCost,
                slotDesc);
    }

    public static void logCanTakeOutput(String tag,
                                        ServerPlayerEntity bot,
                                        PlayerEntity viewer,
                                        int cost,
                                        boolean present,
                                        boolean enoughXp,
                                        boolean validCost) {
        LOGGER.info("[{}] bot={} viewer={} cost={} present={} botXp={} enoughXp={} validCost={}",
                tag,
                bot.getName().getString(),
                viewer != null ? viewer.getName().getString() : "<null>",
                cost,
                present,
                bot.experienceLevel,
                enoughXp,
                validCost);
    }

    public static void logInsert(String tag,
                                 ServerPlayerEntity bot,
                                 ItemStack stack,
                                 boolean inserted,
                                 ItemStack remainder) {
        LOGGER.info("[{}] bot={} attempted={} inserted={} remainder={} botInvSummary={}",
                tag,
                bot.getName().getString(),
                describe(stack),
                inserted,
                describe(remainder),
                summarizeInventory(bot.getInventory()));
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "<empty>";
        String enchSummary;
        try {
            var enchants = stack.getEnchantments();
            enchSummary = enchants == null || enchants.isEmpty() ? "none" : enchants.toString();
        } catch (Throwable t) {
            enchSummary = "<err:" + t.getClass().getSimpleName() + ">";
        }
        return stack.getCount() + "x " + stack.getItem() + " ench=" + enchSummary
                + " hash=" + System.identityHashCode(stack);
    }

    private static String summarizeInventory(PlayerInventory inv) {
        if (inv == null) return "<null>";
        StringBuilder sb = new StringBuilder("[");
        int nonEmpty = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (nonEmpty++ > 0) sb.append(",");
            sb.append(i).append(":").append(describe(s));
            if (nonEmpty >= 8) {
                sb.append(",...");
                break;
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
