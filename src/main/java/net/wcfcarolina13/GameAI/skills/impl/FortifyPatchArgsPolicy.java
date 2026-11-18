package net.wcfcarolina13.GameAI.skills.impl;

/**
 * Parses the text after {@code /bot fortify patch}: an optional wall name and an
 * optional trailing {@code auto} flag (repeat passes until aborted).
 * Pure: no Minecraft types, unit-tested.
 */
public final class FortifyPatchArgsPolicy {

    private FortifyPatchArgsPolicy() {}

    /** @param wallName the named wall, or null when none was given (auto-detect nearest) */
    public record PatchArgs(String wallName, boolean autoRepeat) {}

    public static PatchArgs parse(String rest) {
        String trimmed = rest == null ? "" : rest.trim();
        if (trimmed.isEmpty()) {
            return new PatchArgs(null, false);
        }
        String[] tokens = trimmed.split("\\s+");
        if (!tokens[tokens.length - 1].equalsIgnoreCase("auto")) {
            return new PatchArgs(trimmed, false);
        }
        if (tokens.length == 1) {
            return new PatchArgs(null, true);
        }
        int lastSpace = Math.max(trimmed.lastIndexOf(' '), trimmed.lastIndexOf('\t'));
        String name = trimmed.substring(0, lastSpace).trim();
        return new PatchArgs(name, true);
    }
}
