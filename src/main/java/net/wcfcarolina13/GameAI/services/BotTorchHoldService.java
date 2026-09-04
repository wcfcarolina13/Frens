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
    /**
     * Last verdict line logged for a bot (verdict + gate + measured values).
     * Diagnostics are state-change-only: we only emit a line when this changes,
     * so a 5-tick evaluation loop does not flood the field log.
     */
    private static final ConcurrentHashMap<UUID, String> LAST_VERDICT = new ConcurrentHashMap<>();

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

        Verdict verdict = evalHold(bot, world, id);
        boolean shouldHold = verdict.gate == null;

        if (shouldHold) {
            int torchSlot = findTorchHotbarSlot(bot);
            boolean promoted = false;
            if (torchSlot < 0) {
                torchSlot = promoteTorchToHotbar(bot);
                promoted = torchSlot >= 0;
            }
            if (torchSlot < 0) {
                logVerdictIfChanged(bot, id, new Verdict("no-torch-in-inventory", -1.0D, verdict.light, null), "");
                return;
            }
            if (currentSlot != torchSlot) {
                if (savedSlot == null) {
                    SAVED_SELECTED_SLOT.put(id, currentSlot);
                }
                BotActions.selectHotbarSlot(bot, torchSlot);
            }
            logVerdictIfChanged(bot, id, verdict,
                    String.format(" action=%s slot=%d savedSlot=%d",
                            promoted ? "promoted+held" : "held", torchSlot, currentSlot));
        } else if (savedSlot != null) {
            BotActions.selectHotbarSlot(bot, savedSlot);
            SAVED_SELECTED_SLOT.remove(id);
            logVerdictIfChanged(bot, id, verdict, String.format(" action=yield slot=%d", savedSlot));
        } else {
            logVerdictIfChanged(bot, id, verdict, "");
        }
    }

    /**
     * Emits one INFO diagnostic line, but only when the verdict for this bot has
     * actually changed since the last evaluation. Format:
     * {@code [torch-hold] Jake verdict=reject gate=audible-hostile-8 dist=5.2 light=4}
     */
    private static void logVerdictIfChanged(ServerPlayerEntity bot, UUID id, Verdict verdict, String suffix) {
        String line = String.format("[torch-hold] %s verdict=%s gate=%s dist=%s light=%d%s%s",
                bot.getName().getString(),
                verdict.gate == null ? "hold" : "reject",
                verdict.gate == null ? "none" : verdict.gate,
                verdict.dist < 0 ? "n/a" : String.format("%.1f", verdict.dist),
                verdict.light,
                verdict.detail == null ? "" : " mob=" + verdict.detail,
                suffix);
        String prev = LAST_VERDICT.get(id);
        if (!line.equals(prev)) {
            LAST_VERDICT.put(id, line);
            LOGGER.info(line);
        }
    }

    /** Immutable evaluation result: which gate rejected (null = hold) plus the measured values. */
    private static final class Verdict {
        final String gate;
        final double dist;
        final int light;
        final String detail;

        Verdict(String gate, double dist, int light, String detail) {
            this.gate = gate;
            this.dist = dist;
            this.light = light;
            this.detail = detail;
        }
    }

    /**
     * Evaluates every hold gate in order and returns the first rejecting gate
     * (or a gate of {@code null} meaning "hold a torch now"), along with the
     * measured values that drove the decision. Gate names are stable strings and
     * carry their threshold where one exists (e.g. {@code audible-hostile-8}) so
     * a field log line is self-explanatory. This method has NO side effects and
     * changes no gate's logic — it only reports.
     */
    private static Verdict evalHold(ServerPlayerEntity bot, ServerWorld world, UUID id) {
        int light = world.getLightLevel(bot.getBlockPos());

        Mode mode = BotEventHandler.getModePublic(bot);
        if (mode != Mode.IDLE && mode != Mode.FOLLOW) return new Verdict("mode-" + mode, -1.0D, light, null);
        if (TaskService.hasActiveTask(id)) return new Verdict("active-task", -1.0D, light, null);
        if (bot.isUsingItem()) return new Verdict("using-item", -1.0D, light, null);
        if (bot.hasVehicle()) return new Verdict("mounted", -1.0D, light, null);
        if (bot.isSleeping()) return new Verdict("sleeping", -1.0D, light, null);

        if (light > LIGHT_THRESHOLD) return new Verdict("light-above-" + LIGHT_THRESHOLD, -1.0D, light, null);

        // Combat suppression — visible OR audible hostile.
        List<Entity> visible = BotThreatService.findHostilesAround(bot, VISIBLE_HOSTILE_RADIUS);
        for (Entity hostile : visible) {
            double dist = Math.sqrt(hostile.squaredDistanceTo(bot));
            if (dist <= AUDIBLE_HOSTILE_RADIUS) {
                return new Verdict("audible-hostile-" + (int) AUDIBLE_HOSTILE_RADIUS,
                        dist, light, hostile.getType().toString());
            }
            if (EntityVisibilityUtil.canSee(bot, hostile)) {
                return new Verdict("visible-hostile-" + (int) VISIBLE_HOSTILE_RADIUS,
                        dist, light, hostile.getType().toString());
            }
        }
        return new Verdict(null, -1.0D, light, null);
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
        LAST_VERDICT.clear();
    }
}
