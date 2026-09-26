package net.wcfcarolina13.GameAI.services;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure arithmetic and wording for a tool craft that is short of ingredients: its head material
 * ({@code cobblestone}, {@code planks}, {@code iron_ingot}, ...) and sticks. Sticks the bot does
 * not hold count as covered when spare planks could be turned into them (2 planks make 4 sticks),
 * which is what the craft itself does before giving up; for a wooden tool the head planks are set
 * aside first. No Minecraft imports — unit tested directly.
 */
public final class ToolCraftShortfallPolicy {

    private static final Set<String> MASS_NOUNS = Set.of(
            "cobblestone", "cobbled deepslate", "blackstone", "planks", "sticks");

    private ToolCraftShortfallPolicy() {
    }

    /**
     * The whole recipe for {@code crafts} tools, head first then sticks, each with what the bot has
     * toward it. The sticks entry's {@code have} includes sticks that spare planks could make.
     *
     * @param headItem     registry-ish name of the head material ("cobblestone", "planks", ...)
     * @param headPerCraft head items one tool needs
     * @param stickPerCraft sticks one tool needs
     * @param crafts       tools requested
     * @param headHave     head items held (all planks, for a wooden tool)
     * @param stickHave    sticks held
     * @param planksHave   planks held (the same number as {@code headHave} when {@code headIsPlanks})
     * @param headIsPlanks whether the head material is planks (wooden tool)
     */
    public static List<CraftingRequirementsPolicy.Missing> requirements(String headItem,
                                                                        int headPerCraft,
                                                                        int stickPerCraft,
                                                                        int crafts,
                                                                        int headHave,
                                                                        int stickHave,
                                                                        int planksHave,
                                                                        boolean headIsPlanks) {
        int n = Math.max(1, crafts);
        int headNeed = Math.max(0, headPerCraft) * n;
        int stickNeed = Math.max(0, stickPerCraft) * n;
        int sticks = Math.max(0, stickHave);
        int effectiveSticks = sticks;
        if (sticks < stickNeed) {
            int sparePlanks = Math.max(0, planksHave - (headIsPlanks ? headNeed : 0));
            long obtainable = sticks + 4L * (sparePlanks / 2);
            effectiveSticks = (int) Math.min(stickNeed, obtainable);
        }
        return List.of(
                new CraftingRequirementsPolicy.Missing(headItem, headNeed, Math.max(0, headHave)),
                new CraftingRequirementsPolicy.Missing("stick", stickNeed, effectiveSticks));
    }

    /** Whether any requirement is unmet. */
    public static boolean isShort(List<CraftingRequirementsPolicy.Missing> requirements) {
        if (requirements == null) {
            return false;
        }
        for (CraftingRequirementsPolicy.Missing m : requirements) {
            if (m.delta() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * A line such as {@code "I need 3 cobblestone and 2 sticks for a stone axe — I have 2 sticks."}
     * naming the whole recipe and what the bot holds toward it. Returns {@code ""} when nothing is
     * short, so the caller can fall back to its own wording.
     */
    public static String message(String toolLabel, int crafts, List<CraftingRequirementsPolicy.Missing> requirements) {
        if (!isShort(requirements)) {
            return "";
        }
        int n = Math.max(1, crafts);
        String tool = pretty(toolLabel == null || toolLabel.isBlank() ? "tool" : toolLabel);
        StringBuilder need = new StringBuilder();
        StringBuilder have = new StringBuilder();
        int needParts = 0;
        int haveParts = 0;
        for (CraftingRequirementsPolicy.Missing m : requirements) {
            if (m.need() <= 0) {
                continue;
            }
            need.append(needParts++ == 0 ? "" : " and ").append(m.need()).append(' ').append(noun(m.item(), m.need()));
            if (m.have() > 0) {
                have.append(haveParts++ == 0 ? "" : " and ").append(m.have()).append(' ').append(noun(m.item(), m.have()));
            }
        }
        String target = n == 1 ? article(tool) + " " + tool : n + " " + tool + (tool.endsWith("s") ? "" : "s");
        return "I need " + need + " for " + target + " — I have "
                + (haveParts == 0 ? "none of that" : have.toString()) + ".";
    }

    private static String noun(String item, int count) {
        String name = pretty(item);
        if (count <= 1 || MASS_NOUNS.contains(name) || name.endsWith("s")) {
            return name;
        }
        return name + "s";
    }

    private static String article(String word) {
        return !word.isEmpty() && "aeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }

    private static String pretty(String raw) {
        return raw == null ? "" : raw.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
    }
}
