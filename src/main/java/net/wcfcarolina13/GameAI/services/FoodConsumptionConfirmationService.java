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
import java.util.Locale;
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

    // Per commander: last-insertion-ordered map of pending confirmations by bot alias.
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

        // If the player recently denied, don't spam.
        long deniedUntil = DENIED_UNTIL_MS_BY_BOT.getOrDefault(botId, 0L);
        if (now < deniedUntil) {
            return false;
        }

        // If already approved (one-shot within a short window), allow and consume the approval.
        String itemId = toItemId(expensiveStack);
        ApprovedConsumption approved = APPROVED_BY_BOT.get(botId);
        if (approved != null && now <= approved.expiresAtMs && approved.itemId != null && approved.itemId.equals(itemId)) {
            APPROVED_BY_BOT.remove(botId);
            return true;
        }

        requestConfirmation(bot, expensiveStack, reason);
        return false;
    }

    private static void requestConfirmation(ServerPlayerEntity bot, ItemStack stack, String reason) {
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null) {
            return;
        }

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
        String aliasKey = normalizeAlias(botName);
        if (aliasKey.isEmpty()) {
            aliasKey = "bot";
        }

        // If there's already a pending confirmation for this bot+commander, don't repeat.
        LinkedHashMap<String, FoodConfirmationState> map = PENDING.computeIfAbsent(controller.getUuid(), id -> new LinkedHashMap<>());
        FoodConfirmationState existing = map.get(aliasKey);
        String itemId = toItemId(stack);
        String display = stack.getName() != null ? stack.getName().getString() : itemId;
        if (existing != null && itemId.equals(existing.itemId)) {
            return;
        }

        map.put(aliasKey, new FoodConfirmationState(botId, botName, itemId, display, now));
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
     * Returns true if the chat message was consumed.
     */
    public static boolean tryHandleConfirmation(ServerPlayerEntity commander, String message) {
        if (commander == null || commander.isRemoved()) {
            return false;
        }
        if (message == null) {
            return false;
        }
        String raw = message.trim();
        if (raw.isEmpty()) {
            return false;
        }

        LinkedHashMap<String, FoodConfirmationState> pending = PENDING.get(commander.getUuid());
        if (pending == null || pending.isEmpty()) {
            return false;
        }

        AliasToken token = extractAliasToken(raw, pending);
        FoodConfirmationState state = token != null ? pending.get(token.normalizedAlias()) : lastValue(pending);
        if (state == null) {
            return false;
        }

        String response = stripAliasToken(raw, token);
        Boolean decision = parseDecision(response);
        if (decision == null) {
            // Only consume the message if the user was obviously replying to the prompt.
            // If they said something else ("follow", etc.), let other chat routing handle it.
            return false;
        }

        // Consume and clear.
        if (token != null) {
            pending.remove(token.normalizedAlias());
        } else {
            removeLast(pending);
        }
        if (pending.isEmpty()) {
            PENDING.remove(commander.getUuid());
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
            APPROVED_BY_BOT.put(bot.getUuid(), new ApprovedConsumption(state.itemId, System.currentTimeMillis() + APPROVAL_WINDOW_MS));
            ChatUtils.sendChatMessages(bot.getCommandSource(), "Understood. Using it.", false);
        } else {
            DENIED_UNTIL_MS_BY_BOT.put(bot.getUuid(), System.currentTimeMillis() + DENY_COOLDOWN_MS);
            ChatUtils.sendChatMessages(bot.getCommandSource(), "Alright. I'll save it.", false);
        }

        return true;
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

    private static Boolean parseDecision(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        // Keep this deliberately strict so we don't eat chat.
        if (normalized.equals("yes") || normalized.equals("y")) {
            return true;
        }
        if (normalized.equals("no") || normalized.equals("n")) {
            return false;
        }
        return null;
    }

    private static String normalizeAlias(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("[^A-Za-z0-9]", "");
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private record AliasToken(String rawToken, String normalizedAlias) {
    }

    private static AliasToken extractAliasToken(String message, Map<String, FoodConfirmationState> pending) {
        if (message == null || message.isBlank() || pending == null || pending.isEmpty()) {
            return null;
        }
        String trimmed = message.trim();
        int breakIndex = findAliasBreakIndex(trimmed);
        if (breakIndex <= 0) {
            return null;
        }
        String rawToken = trimmed.substring(0, breakIndex);
        String sanitized = rawToken.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
        if (sanitized.isEmpty()) {
            return null;
        }
        String normalized = sanitized.toLowerCase(Locale.ROOT);
        if (!pending.containsKey(normalized)) {
            return null;
        }
        return new AliasToken(rawToken, normalized);
    }

    private static int findAliasBreakIndex(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                return i;
            }
            if (c == ':' || c == ',' || c == ';' || c == '-') {
                return i + 1;
            }
        }
        return -1;
    }

    private static String stripAliasToken(String message, AliasToken token) {
        if (message == null) {
            return "";
        }
        String trimmed = message.trim();
        if (token == null) {
            return trimmed;
        }
        if (trimmed.length() <= token.rawToken.length()) {
            return "";
        }
        String remainder = trimmed.substring(token.rawToken.length()).trim();
        remainder = remainder.replaceFirst("^[,:;-]+", "").trim();
        return remainder;
    }

    private static FoodConfirmationState lastValue(LinkedHashMap<String, FoodConfirmationState> map) {
        FoodConfirmationState last = null;
        for (FoodConfirmationState v : map.values()) {
            last = v;
        }
        return last;
    }

    private static void removeLast(LinkedHashMap<String, FoodConfirmationState> map) {
        String lastKey = null;
        for (String key : map.keySet()) {
            lastKey = key;
        }
        if (lastKey != null) {
            map.remove(lastKey);
        }
    }
}
