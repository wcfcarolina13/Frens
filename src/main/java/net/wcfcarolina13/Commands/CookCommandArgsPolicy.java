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
