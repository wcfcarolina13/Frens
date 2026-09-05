package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure helper that turns a "needed vs have" material comparison into a single player-facing
 * refusal line. No Minecraft imports — unit tested directly.
 */
public final class CraftingRequirementsPolicy {

    /** A single unmet ingredient requirement. {@code item} is a registry-ish name (underscores ok). */
    public record Missing(String item, int need, int have) {
        /** How many more of this item are required. */
        public int delta() {
            return Math.max(0, need - have);
        }
    }

    private CraftingRequirementsPolicy() {
    }

    /**
     * Compares required amounts against available amounts, preserving the iteration order of
     * {@code needed}. Non-positive needs are ignored; an absent {@code have} entry counts as 0;
     * satisfied requirements are skipped.
     */
    public static List<Missing> missing(Map<String, Integer> needed, Map<String, Integer> have) {
        List<Missing> out = new ArrayList<>();
        if (needed == null || needed.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Integer> entry : needed.entrySet()) {
            String item = entry.getKey();
            if (item == null || item.isBlank()) {
                continue;
            }
            int need = entry.getValue() == null ? 0 : entry.getValue();
            if (need <= 0) {
                continue;
            }
            int owned = 0;
            if (have != null) {
                Integer h = have.get(item);
                if (h != null) {
                    owned = h;
                }
            }
            if (owned >= need) {
                continue;
            }
            out.add(new Missing(item, need, owned));
        }
        return out;
    }

    /**
     * Formats a refusal line such as
     * {@code "Can't craft bed: need 3 more white wool, 2 more oak planks"}.
     * Returns {@code ""} when nothing is missing.
     */
    public static String format(String thing, List<Missing> missing) {
        if (missing == null || missing.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Can't craft ").append(pretty(thing == null ? "that" : thing)).append(": need ");
        boolean first = true;
        for (Missing m : missing) {
            int delta = m.delta();
            if (delta <= 0) {
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(delta).append(" more ").append(plural(pretty(m.item()), delta));
        }
        if (first) {
            return "";
        }
        return sb.toString();
    }

    private static String pretty(String raw) {
        return raw == null ? "" : raw.replace('_', ' ').trim();
    }

    /**
     * Naive pluralisation: only add "s" when more than one is needed and the name lacks one.
     * A short mass-noun list is exempt so the message reads "3 more white wool", not "wools".
     */
    private static String plural(String name, int delta) {
        if (delta <= 1 || name.endsWith("s")) {
            return name;
        }
        int space = name.lastIndexOf(' ');
        String head = space < 0 ? name : name.substring(space + 1);
        for (String mass : MASS_NOUNS) {
            if (head.equalsIgnoreCase(mass)) {
                return name;
            }
        }
        return name + "s";
    }

    private static final String[] MASS_NOUNS = {
            "wool", "coal", "charcoal", "leather", "string", "wood", "glass", "iron", "gold"
    };
}
