package net.wcfcarolina13.GameAI.services;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.ChatUtils.ChatUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles chat-based confirmations before a bot consumes rare/expensive consumables.
 *
 * <p>Designed to prevent bots from wasting Golden Apples / potions just because they are the
 * "cheapest" available food (or the only food) in a shared inventory.
 */
public final class FoodConsumptionConfirmationService {

    private static final long PROMPT_COOLDOWN_MS = 15_000L;
    private static final long DENY_COOLDOWN_MS = 30_000L;

    private static final ConcurrentHashMap<UUID, Long> LAST_PROMPT_MS_BY_BOT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> DENIED_UNTIL_MS_BY_BOT = new ConcurrentHashMap<>();

    // Per commander (controller player UUID): pending confirmations by normalized bot alias.
    // Each map is guarded by synchronizing on itself; entries expire after
    // FoodConfirmReplyPolicy.PENDING_TTL_MS.
    private static final ConcurrentHashMap<UUID, LinkedHashMap<String, FoodConfirmationState>> PENDING = new ConcurrentHashMap<>();

    // One-shot approvals: allow the next consumption attempt for this bot+item within a short window.
    private static final long APPROVAL_WINDOW_MS = 12_000L;
    private static final ConcurrentHashMap<UUID, ApprovedConsumption> APPROVED_BY_BOT = new ConcurrentHashMap<>();

    private record FoodConfirmationState(UUID botUuid, String botName, String itemId, String displayName, long createdAtMs) {
    }

    private record ApprovedConsumption(String itemId, long expiresAtMs) {
    }

    private FoodConsumptionConfirmationService() {
    }

    public static boolean isRareOrExpensiveConsumable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        // Food: Golden Apples are explicitly expensive.
        if (stack.isOf(Items.GOLDEN_APPLE) || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) {
            return true;
        }

        // Potions: treat all potion containers as expensive consumables.
        if (stack.isOf(Items.POTION) || stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION)) {
            return true;
        }

        return false;
    }

    public static boolean isConsumable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
        if (food != null) {
            return true;
        }
        return stack.isOf(Items.POTION) || stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION);
    }

    /**
     * If the stack is expensive and not already approved, send a confirmation prompt and return false.
     * Otherwise return true.
     */
    public static boolean allowConsumptionOrRequest(ServerPlayerEntity bot, ItemStack expensiveStack, String reason) {
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return false;
        }
        if (expensiveStack == null || expensiveStack.isEmpty()) {
            return false;
        }
        if (!isRareOrExpensiveConsumable(expensiveStack)) {
            return true;
        }

        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();

        // An approval (chat "yes", or a hand-feed the player already confirmed) is checked before the
        // deny cooldown: a hand-fed [Yes] after an earlier chat "no" is the newer, explicit answer.
        String itemId = toItemId(expensiveStack);
        ApprovedConsumption approved = APPROVED_BY_BOT.get(botId);
        if (approved != null && now <= approved.expiresAtMs && approved.itemId != null && approved.itemId.equals(itemId)) {
            APPROVED_BY_BOT.remove(botId);
            return true;
        }

        // If the player recently denied, don't spam.
        long deniedUntil = DENIED_UNTIL_MS_BY_BOT.getOrDefault(botId, 0L);
        if (now < deniedUntil) {
            return false;
        }

        requestConfirmation(bot, expensiveStack, reason);
        return false;
    }

    /**
     * Records that the player already agreed to this bot consuming {@code stack} (a hand-feed confirmed
     * with [Yes], or auto-accepted by config), so the next use of that item is not asked about again.
     * One-shot, same window as a chat "yes". Clears a chat "no" cooldown for the bot.
     */
    public static void preApprove(ServerPlayerEntity bot, ItemStack stack) {
        if (bot == null || stack == null || stack.isEmpty() || !isRareOrExpensiveConsumable(stack)) {
            return;
        }
        UUID botId = bot.getUuid();
        DENIED_UNTIL_MS_BY_BOT.remove(botId);
        APPROVED_BY_BOT.put(botId, new ApprovedConsumption(toItemId(stack), System.currentTimeMillis() + APPROVAL_WINDOW_MS));
    }

    private static void requestConfirmation(ServerPlayerEntity bot, ItemStack stack, String reason) {
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return;
        }

        // Stored under the controller's player UUID; tryHandleConfirmation looks up the chat
        // sender's player UUID, so the controller's own reply finds it (same key space as the
        // skill-resume prompt's responderKey). An unowned, unrecruited bot has no controller and
        // is never prompted.
        ServerPlayerEntity controller = CompanionCommunicationPolicy.resolveController(server, bot);
        if (controller == null || controller.isRemoved()) {
            return;
        }

        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();

        long last = LAST_PROMPT_MS_BY_BOT.getOrDefault(botId, 0L);
        if (now - last < PROMPT_COOLDOWN_MS) {
            return;
        }

        String botName = bot.getName().getString();
        String aliasKey = FoodConfirmReplyPolicy.normalizeAlias(botName);
        if (aliasKey.isEmpty()) {
            aliasKey = "bot";
        }

        String itemId = toItemId(stack);
        String display = stack.getName() != null ? stack.getName().getString() : itemId;
        LinkedHashMap<String, FoodConfirmationState> map = PENDING.computeIfAbsent(controller.getUuid(), id -> new LinkedHashMap<>());
        synchronized (map) {
            pruneExpired(map, now);
            // A live, unanswered prompt for the same bot+item is not repeated; once it expires
            // (FoodConfirmReplyPolicy.PENDING_TTL_MS) the bot may ask again.
            FoodConfirmationState existing = map.get(aliasKey);
            if (existing != null && itemId.equals(existing.itemId)) {
                return;
            }
            map.put(aliasKey, new FoodConfirmationState(botId, botName, itemId, display, now));
        }
        LAST_PROMPT_MS_BY_BOT.put(botId, now);

        String prompt = "I can eat " + display + ", but it's rare. Use it anyway? (yes/no)";
        if (reason != null && !reason.isBlank()) {
            prompt = prompt + " (" + reason + ")";
        }
        String aliasHint = aliasKey;
        prompt = prompt + " Reply 'yes' or 'no'. (If you have multiple prompts pending: '" + aliasHint + " yes' / '" + aliasHint + " no'.)";

        ChatUtils.sendChatMessages(bot.getCommandSource(), prompt, false);
    }

    /**
     * Attempts to consume a yes/no message as a response to a pending expensive-consumption prompt.
     * Called from the chat handler after the respawn and skill-resume prompts. A bare answer goes to
     * the most recently asked prompt for this player; {@code "<bot> yes"} picks one.
     * Returns true if the chat message was consumed.
     */
    public static boolean tryHandleConfirmation(ServerPlayerEntity commander, String message) {
        if (commander == null || commander.isRemoved() || message == null || message.isBlank()) {
            return false;
        }

        UUID commanderId = commander.getUuid();
        LinkedHashMap<String, FoodConfirmationState> pending = PENDING.get(commanderId);
        if (pending == null) {
            return false;
        }

        FoodConfirmationState state;
        boolean decision;
        synchronized (pending) {
            pruneExpired(pending, System.currentTimeMillis());
            if (pending.isEmpty()) {
                // The empty map stays registered (one per player): removing it here could race a
                // prompt being added to this same map instance.
                return false;
            }
            FoodConfirmReplyPolicy.Reply reply = FoodConfirmReplyPolicy.parse(message, pending.keySet());
            if (reply == null) {
                // Not an answer ("follow", etc.): let other chat routing handle it.
                return false;
            }
            String key = reply.alias();
            if (key == null) {
                Map<String, Long> createdAt = new LinkedHashMap<>();
                pending.forEach((alias, s) -> createdAt.put(alias, s.createdAtMs));
                key = FoodConfirmReplyPolicy.mostRecent(createdAt);
            }
            state = key == null ? null : pending.remove(key);
            if (state == null) {
                return false;
            }
            decision = reply.approve();
        }

        MinecraftServer server = commander.getCommandSource().getServer();
        if (server == null) {
            return true;
        }

        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(state.botUuid);
        if (bot == null || bot.isRemoved() || !bot.isAlive()) {
            return true;
        }

        if (decision) {
            DENIED_UNTIL_MS_BY_BOT.remove(bot.getUuid());
            APPROVED_BY_BOT.put(bot.getUuid(), new ApprovedConsumption(state.itemId, System.currentTimeMillis() + APPROVAL_WINDOW_MS));
            ChatUtils.sendChatMessages(bot.getCommandSource(), "Understood. Using it.", false);
        } else {
            DENIED_UNTIL_MS_BY_BOT.put(bot.getUuid(), System.currentTimeMillis() + DENY_COOLDOWN_MS);
            ChatUtils.sendChatMessages(bot.getCommandSource(), "Alright. I'll save it.", false);
        }

        return true;
    }

    private static void pruneExpired(Map<String, FoodConfirmationState> map, long now) {
        map.values().removeIf(s -> FoodConfirmReplyPolicy.isExpired(s.createdAtMs, now));
    }

    private static String toItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "minecraft:air";
        }
        Item item = stack.getItem();
        if (item == null) {
            return "minecraft:air";
        }
        try {
            return String.valueOf(Registries.ITEM.getId(item));
        } catch (Throwable ignored) {
            return item.getTranslationKey();
        }
    }
}
