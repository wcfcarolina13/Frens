package net.wcfcarolina13.Commands;

import java.util.Locale;

/**
 * {@code /bot cook <item> <fuel> [bot]}: the command tree has no {@code <item> <bot>} branch, so
 * {@code /bot cook beef Bob} read "Bob" as the fuel and answered "I don't have that fuel item."
 * (sandbox 2026-11-09). A second word that is no fuel word and no item, but names a bot, is the
 * bot. Pure, so JUnit can test it.
 */
public final class CookCommandArgsPolicy {

    private CookCommandArgsPolicy() {
    }

    /** The words {@code SmeltingService.ensureFuel} reads as "pick a fuel" or "no fuel". */
    public static boolean isFuelKeyword(String word) {
        if (word == null) {
            return false;
        }
        return switch (word.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "auto", "use", "false", "off", "none" -> true;
            default -> false;
        };
    }

    /** What the single word of {@code /bot cook <word>} is. */
    public enum FirstWordRole { ITEM, FUEL, BOT }

    /**
     * The single word of {@code /bot cook <word>}: a fuel keyword is the fuel, a word that is no
     * item id but names a bot is the bot, and anything else is the item filter as before (filters
     * like "fish" match by name, so an unknown word stays an item). An item id wins over a bot of
     * the same name, as in {@link #fuelSlotNamesBot}.
     */
    public static FirstWordRole firstWordRole(String word, boolean namesItem, boolean namesBot) {
        if (word == null || word.isBlank()) {
            return FirstWordRole.ITEM;
        }
        if (isFuelKeyword(word)) {
            return FirstWordRole.FUEL;
        }
        return !namesItem && namesBot ? FirstWordRole.BOT : FirstWordRole.ITEM;
    }

    /**
     * True when the word in the fuel slot should be read as the bot instead: it is not a fuel
     * keyword, not an item id, and it names a bot. An item id wins over a bot of the same name,
     * so {@code /bot cook beef coal} still means coal.
     */
    public static boolean fuelSlotNamesBot(String word, boolean namesItem, boolean namesBot) {
        if (word == null || word.isBlank()) {
            return false;
        }
        return !isFuelKeyword(word) && !namesItem && namesBot;
    }
}
