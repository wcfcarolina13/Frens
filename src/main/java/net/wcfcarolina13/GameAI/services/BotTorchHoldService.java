package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.BotEventHandler.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atmospheric service that holds a torch in the bot's active hand when the bot
 * is idle or following the commander through a dim area, then yields the slot
 * back the moment any other system needs the hand (skill, combat, eating, etc.).
 *
 * <p><b>Hold conditions (all must hold):</b>
 * <ul>
 *   <li>Mode is {@link Mode#IDLE} or {@link Mode#FOLLOW}.</li>
 *   <li>No active {@link TaskService} ticket (skill not running).</li>
 *   <li>Bot is not using an item ({@code !isUsingItem()}), not riding, not sleeping.</li>
 *   <li>No hostile within 16 blocks AND line of sight (visible threat).</li>
 *   <li>No hostile within 8 blocks regardless of LOS (audible threat — proxy
 *       for footsteps and mob ambient sounds).</li>
 *   <li>Block-light + sky-light at the bot's position is ≤ 7 (the vanilla mob-spawn
 *       threshold; "this is the kind of dim where torches matter").</li>
 *   <li>The bot has at least one torch reachable from the inventory.</li>
 * </ul>
 *
 * <p><b>Inventory promotion:</b> if a torch stack exists in main inventory but
 * not in hotbar, the service swaps it into the first empty hotbar slot. If no
 * empty slot exists, it picks a hotbar slot whose item is non-tool / non-food /
 * non-weapon to displace. Once promoted the torch stays in hotbar — we don't
 * shuffle it back, so layout is stable.
 *
 * <p><b>Cooperation with other services:</b> all other systems that need a
 * specific tool call {@link BotActions#selectHotbarSlot} (or one of the
 * {@code ensure*}/{@code selectBest*} primitives), which atomically changes
 * the selected slot. Our foreign-swap detection notices this on the next tick
 * and yields. When the foreign user finishes and conditions are quiet again,
 * our service picks back up.
 */
public final class BotTorchHoldService {
    private static final Logger LOGGER = LoggerFactory.getLogger("torch-hold");

    private static final int LIGHT_THRESHOLD = 7;
    private static final double VISIBLE_HOSTILE_RADIUS = 16.0D;
    private static final double AUDIBLE_HOSTILE_RADIUS = 8.0D;
    private static final long EVAL_INTERVAL_TICKS = 5L;

    /** Slot the bot had selected before we put the torch in hand. -1 = no override. */
    private static final ConcurrentHashMap<UUID, Integer> SAVED_SELECTED_SLOT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_EVAL_TICK = new ConcurrentHashMap<>();

    private BotTorchHoldService() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayerEntity bot : BotRegistry.getPlayers(server)) {
            try {
                tickBot(bot);
            } catch (Exception e) {
                LOGGER.debug("torch-hold tick failed for {}: {}",
                        bot.getName().getString(), e.getMessage());
            }
        }
    }

    private static void tickBot(ServerPlayerEntity bot) {
        if (bot == null || bot.isRemoved()) return;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;
        UUID id = bot.getUuid();

        long nowTick = world.getTime();
        Long lastEval = LAST_EVAL_TICK.get(id);
        if (lastEval != null && nowTick - lastEval < EVAL_INTERVAL_TICKS) return;
        LAST_EVAL_TICK.put(id, nowTick);

        // Foreign-swap detection: if we recorded a saved slot, the torch slot
        // we put up better still be selected. If something else swapped, yield.
        Integer savedSlot = SAVED_SELECTED_SLOT.get(id);
        int currentSlot = bot.getInventory().getSelectedSlot();
        if (savedSlot != null) {
            int torchSlot = findTorchHotbarSlot(bot);
            if (torchSlot < 0 || currentSlot != torchSlot) {
                // Either the torch is gone from the slot we put it in, or another
                // service swapped to a different slot — drop our state.
                SAVED_SELECTED_SLOT.remove(id);
                savedSlot = null;
            }
        }

        boolean shouldHold = shouldHoldTorch(bot, world, id);

        if (shouldHold) {
            int torchSlot = findTorchHotbarSlot(bot);
            if (torchSlot < 0) {
                torchSlot = promoteTorchToHotbar(bot);
            }
            if (torchSlot < 0) return;  // no torch available anywhere
            if (currentSlot != torchSlot) {
                if (savedSlot == null) {
                    SAVED_SELECTED_SLOT.put(id, currentSlot);
                }
                BotActions.selectHotbarSlot(bot, torchSlot);
            }
        } else if (savedSlot != null) {
            BotActions.selectHotbarSlot(bot, savedSlot);
            SAVED_SELECTED_SLOT.remove(id);
        }
    }

    private static boolean shouldHoldTorch(ServerPlayerEntity bot, ServerWorld world, UUID id) {
        Mode mode = BotEventHandler.getModePublic(bot);
        if (mode != Mode.IDLE && mode != Mode.FOLLOW) return false;
        if (TaskService.hasActiveTask(id)) return false;
        if (bot.isUsingItem() || bot.hasVehicle() || bot.isSleeping()) return false;

        // Light gate: only bother in dim places.
        if (world.getLightLevel(bot.getBlockPos()) > LIGHT_THRESHOLD) return false;

        // Combat suppression — visible OR audible hostile.
        List<Entity> visible = BotThreatService.findHostilesAround(bot, VISIBLE_HOSTILE_RADIUS);
        for (Entity hostile : visible) {
            double distSq = hostile.squaredDistanceTo(bot);
            if (distSq <= AUDIBLE_HOSTILE_RADIUS * AUDIBLE_HOSTILE_RADIUS) {
                // Audible — close enough to "hear footsteps" through walls.
                return false;
            }
            if (EntityVisibilityUtil.canSee(bot, hostile)) {
                return false;
            }
        }
        return true;
    }

    /** Returns the hotbar slot (0–8) holding a torch, or -1 if none. */
    private static int findTorchHotbarSlot(ServerPlayerEntity bot) {
        PlayerInventory inv = bot.getInventory();
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            if (isTorch(inv.getStack(i))) return i;
        }
        return -1;
    }

    /**
     * Tries to move a torch stack from main inventory into a hotbar slot.
     * Prefers an empty hotbar slot; falls back to displacing a non-tool, non-food,
     * non-weapon slot. Returns the new hotbar slot, or -1 if no torch exists in
     * inventory at all or no acceptable hotbar slot is available.
     */
    private static int promoteTorchToHotbar(ServerPlayerEntity bot) {
        PlayerInventory inv = bot.getInventory();
        int torchInvSlot = -1;
        for (int i = PlayerInventory.getHotbarSize(); i < inv.size(); i++) {
            if (isTorch(inv.getStack(i))) {
                torchInvSlot = i;
                break;
            }
        }
        if (torchInvSlot < 0) return -1;

        int targetHotbar = -1;
        // First pass: empty hotbar slot.
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            if (inv.getStack(i).isEmpty()) {
                targetHotbar = i;
                break;
            }
        }
        // Second pass: non-tool, non-food, non-weapon slot. Don't disturb the
        // currently-selected slot (avoids round-trip churn with whatever the bot
        // had in hand a moment ago).
        if (targetHotbar < 0) {
            int currentSlot = inv.getSelectedSlot();
            for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
                if (i == currentSlot) continue;
                ItemStack stack = inv.getStack(i);
                if (isToolOrFoodOrWeapon(stack)) continue;
                targetHotbar = i;
                break;
            }
        }
        if (targetHotbar < 0) return -1;

        ItemStack displaced = inv.getStack(targetHotbar);
        ItemStack torch = inv.getStack(torchInvSlot);
        inv.setStack(targetHotbar, torch);
        inv.setStack(torchInvSlot, displaced);
        return targetHotbar;
    }

    private static boolean isTorch(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item == Items.TORCH
                || item == Items.SOUL_TORCH
                || item == Items.REDSTONE_TORCH;
    }

    private static boolean isToolOrFoodOrWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String key = stack.getItem().getTranslationKey().toLowerCase();
        return key.contains("axe")
                || key.contains("sword")
                || key.contains("shovel")
                || key.contains("hoe")
                || key.contains("bow")
                || key.contains("crossbow")
                || key.contains("trident")
                || key.contains("shield")
                || key.contains("food")
                || key.contains("apple")
                || key.contains("bread")
                || key.contains("stew")
                || key.contains("soup")
                || key.contains("cookie")
                || key.contains("pie")
                || key.contains("bottle")
                || key.contains("bucket")
                || stack.getItem().getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD);
    }

    public static void reset() {
        SAVED_SELECTED_SLOT.clear();
        LAST_EVAL_TICK.clear();
    }
}
