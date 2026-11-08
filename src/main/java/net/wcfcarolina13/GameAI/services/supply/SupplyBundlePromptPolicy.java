package net.wcfcarolina13.GameAI.services.supply;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-logic policy: whether a craft's need plan ({@link CraftSupplyNeedPolicy.Plan}) becomes a
 * prompt, and the words of that one combined prompt and its answers. No Minecraft types: the
 * adapter passes display names and counts.
 *
 * <p><b>Partial availability (ruling made here, to confirm with Bradley).</b> The 2026-10-23
 * ruling covers a chest that has everything. When it has only some lines, the conservative
 * option is taken: ask only for what the chest has, and say in the same prompt what is still
 * missing, so the owner approves knowing the craft will not finish from this chest alone. The
 * alternative (ask nothing until one chest covers all) would leave the bot unable to gather a
 * craft across two chests. Cost if wrong: the bot takes part of a set it cannot finish yet.
 *
 * <p>A one-line ask with nothing missing and no purpose uses the existing single-item wording
 * ({@link SupplyChestRules#promptText}) unchanged, so today's single-item callers read the same.
 */
public final class SupplyBundlePromptPolicy {

    /** What to do with a plan. */
    public enum Decision {
        /** The bot already has everything. */
        NOTHING_NEEDED,
        /** The chest covers every lacking item: one ask, then the craft can run. */
        ASK_ALL,
        /** The chest covers some lines: ask for those, name the rest (the partial ruling). */
        ASK_PARTIAL,
        /** The chest has none of it: no prompt; tell the owner what is missing. */
        NOTHING_TO_ASK
    }

    /** One shown line: a count and the item's display name. */
    public record Shown(int qty, String name) {
    }

    private SupplyBundlePromptPolicy() {
    }

    public static Decision decide(CraftSupplyNeedPolicy.Plan plan) {
        if (plan == null || plan.nothingNeeded()) {
            return Decision.NOTHING_NEEDED;
        }
        if (plan.fromChest().isEmpty()) {
            return Decision.NOTHING_TO_ASK;
        }
        return plan.missing().isEmpty() ? Decision.ASK_ALL : Decision.ASK_PARTIAL;
    }

    /** "3 planks", "3 planks and 2 sticks", "3 planks, 2 sticks and 1 coal"; blank names read "items". */
    public static String join(List<Shown> lines) {
        List<String> parts = new ArrayList<>();
        if (lines != null) {
            for (Shown s : lines) {
                if (s == null || s.qty() <= 0) {
                    continue;
                }
                String name = s.name() == null || s.name().isBlank() ? "items" : s.name();
                parts.add(s.qty() + " " + name);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /**
     * The one combined prompt, e.g. {@code "Jake asks to take 3 Oak Planks and 2 Stick from the
     * chest at 10, 64, 10 to make a Wooden Pickaxe. Still missing: 1 Iron Ingot."}
     *
     * @param purpose the thing being crafted ("a Wooden Pickaxe"), or blank for none
     * @param missing what the chest does not cover; empty when the ask is complete
     */
    public static String promptText(String botName, List<Shown> asked, String purpose,
                                    int x, int y, int z, List<Shown> missing) {
        String what = join(asked);
        String stillMissing = join(missing);
        boolean noPurpose = purpose == null || purpose.isBlank();
        if (asked != null && asked.size() == 1 && noPurpose && stillMissing.isEmpty()) {
            Shown only = asked.get(0);
            return SupplyChestRules.promptText(botName, only.qty(), only.name(), x, y, z);
        }
        String who = botName == null || botName.isBlank() ? "Your companion" : botName;
        StringBuilder sb = new StringBuilder();
        sb.append(who).append(" asks to take ").append(what.isEmpty() ? "items" : what)
                .append(" from the chest at ").append(x).append(", ").append(y).append(", ").append(z);
        if (!noPurpose) {
            sb.append(" to make ").append(purpose.strip());
        }
        sb.append('.');
        if (!stillMissing.isEmpty()) {
            sb.append(" Still missing: ").append(stillMissing).append('.');
        }
        return sb.toString();
    }

    /** Hover text of the [Allow once] button: exactly what one approval lets the bot take. */
    public static String allowOnceHover(String botName, List<Shown> asked) {
        String who = botName == null || botName.isBlank() ? "your companion" : botName;
        String what = join(asked);
        return "Let " + who + " take exactly " + (what.isEmpty() ? "these items" : what) + " from this chest, once.";
    }

    /** The owner's confirmation after [Allow once] on a bundle. */
    public static String grantedOnceReply(String botName, List<Shown> asked) {
        String who = botName == null || botName.isBlank() ? "Your companion" : botName;
        String what = join(asked);
        return "Allowed: " + who + " may take " + (what.isEmpty() ? "those items" : what)
                + " from that chest, this once.";
    }

    /** The owner's confirmation after [No] on a bundle: every line is refused together. */
    public static String rejectedReply(String botName, List<Shown> asked) {
        String who = botName == null || botName.isBlank() ? "Your companion" : botName;
        String what = join(asked);
        return "Refused: " + who + " won't ask for " + (what.isEmpty() ? "those items" : what)
                + " from that chest again for a while.";
    }

    /** What the bot says when the chest has none of what it lacks ({@link Decision#NOTHING_TO_ASK}). */
    public static String nothingToAskLine(String purpose, List<Shown> missing) {
        String what = join(missing);
        String thing = purpose == null || purpose.isBlank() ? "that" : purpose.strip();
        return "I can't make " + thing + ": I still need " + (what.isEmpty() ? "more materials" : what)
                + ", and the chest has none of it to spare.";
    }
}
