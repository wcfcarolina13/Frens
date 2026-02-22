package net.wcfcarolina13.GameAI.services;

import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.GameAI.BotEventHandler;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Low-frequency overhead dialogue for inventory pressure and chest offload relief moments.
 */
public final class BotInventoryFullDialogueService {

    private static final long FULL_INVENTORY_COOLDOWN_MS = 20_000L;
    private static final long CHEST_RELIEF_COOLDOWN_MS = 12_000L;
    private static final double CHEST_RELIEF_CHANCE = 0.55D;
    private static final int OVERHEAD_DURATION_MS = 2_800;
    private static final double OVERHEAD_RANGE = 32.0D;

    private static final String[] FULL_INVENTORY_LINES = new String[] {
            "I'm not a pack animal, you know.",
            "Do we really need to carry all this stuff?",
            "We need to find a chest",
            "This stuff is getting heavy",
            "Full inventory, by the way"
    };

    private static final String[] CHEST_RELIEF_LINES = new String[] {
            "That's a relief",
            "Finally, a chest",
            "Dropping off some loot"
    };

    private static final Random RNG = new Random();
    private static final Map<UUID, Long> LAST_FULL_INVENTORY_LINE_MS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_CHEST_RELIEF_LINE_MS = new ConcurrentHashMap<>();

    private BotInventoryFullDialogueService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || bot.isSleeping()) {
                continue;
            }
            if (!isInventoryFull(bot)) {
                continue;
            }
            long last = LAST_FULL_INVENTORY_LINE_MS.getOrDefault(bot.getUuid(), 0L);
            if (now - last < FULL_INVENTORY_COOLDOWN_MS) {
                continue;
            }
            // Keep this occasional even when inventory remains full for long stretches.
            if (RNG.nextDouble() > 0.08D) {
                continue;
            }
            LAST_FULL_INVENTORY_LINE_MS.put(bot.getUuid(), now);
            CompanionOverheadDialogueService.showOverheadLine(
                    bot,
                    FULL_INVENTORY_LINES[RNG.nextInt(FULL_INVENTORY_LINES.length)],
                    OVERHEAD_DURATION_MS,
                    OVERHEAD_RANGE,
                    "inventory-full",
                    "full"
            );
        }
    }

    public static void tryShowChestRelief(ServerPlayerEntity bot, String reason) {
        if (bot == null || bot.isRemoved()) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID botId = bot.getUuid();
        long last = LAST_CHEST_RELIEF_LINE_MS.getOrDefault(botId, 0L);
        if (now - last < CHEST_RELIEF_COOLDOWN_MS) {
            return;
        }
        if (RNG.nextDouble() > CHEST_RELIEF_CHANCE) {
            return;
        }
        LAST_CHEST_RELIEF_LINE_MS.put(botId, now);
        CompanionOverheadDialogueService.showOverheadLine(
                bot,
                CHEST_RELIEF_LINES[RNG.nextInt(CHEST_RELIEF_LINES.length)],
                OVERHEAD_DURATION_MS,
                OVERHEAD_RANGE,
                "chest-relief",
                reason
        );
    }

    private static boolean isInventoryFull(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        int empty = 0;
        // Main inventory slots only (same practical threshold used by mining skills).
        for (int i = 0; i < 36; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack == null || stack.isEmpty()) {
                empty++;
                if (empty >= 3) {
                    return false;
                }
            }
        }
        return true;
    }
}
