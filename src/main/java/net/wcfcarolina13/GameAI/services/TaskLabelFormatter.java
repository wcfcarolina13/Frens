package net.wcfcarolina13.GameAI.services;

import java.util.Locale;

/**
 * Shared formatter for short human-facing task/status labels.
 */
public final class TaskLabelFormatter {

    private TaskLabelFormatter() {
    }

    public static String humanizeTaskName(String taskName) {
        if (taskName == null || taskName.isBlank()) {
            return "working";
        }
        String raw = taskName.trim();
        if (raw.regionMatches(true, 0, "skill:", 0, "skill:".length())) {
            raw = raw.substring("skill:".length());
        } else if (raw.regionMatches(true, 0, "system:", 0, "system:".length())) {
            raw = raw.substring("system:".length());
        }
        raw = raw.replace('_', ' ').trim().replaceAll("\\s+", " ");
        if (raw.isEmpty()) {
            return "working";
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "farm":
                return "farming";
            case "collect dirt":
                return "collecting dirt";
            case "drop sweep":
            case "woodcut cleanup":
                return "running cleanup";
            case "woodcut":
                return "woodcutting";
            case "stripmine":
            case "strip mine":
                return "strip mining";
            case "hangout":
                return "hanging out";
            case "hunt":
                return "hunting";
            case "fish":
                return "fishing";
            case "shelter":
                return "building shelter";
            case "build":
                return "building";
            case "flowers":
                return "collecting flowers";
            case "grass seeds":
                return "collecting seeds";
            case "leaf litter":
                return "collecting leaf litter";
            case "mushrooms":
                return "foraging mushrooms";
            case "feed animals":
                return "feeding animals";
            case "wander":
                return "wandering";
            case "surface recovery":
                return "climbing to the surface";
            case "break free":
                return "breaking free";
            case "fortify":
            case "fortify village":
                return "fortifying the village";
            case "fortify patch":
                return "repairing fortifications";
            case "fortify moat":
                return "digging the moat";
            case "fortify drift":
                return "checking wall drift";
            case "fortify expand":
                return "expanding the wall";
            case "fortify status":
            case "fortify list":
                return "reviewing fortifications";
            case "fortify resume":
                return "resuming wall construction";
            default:
                if (normalized.endsWith("ing")) {
                    return normalized;
                }
                if (normalized.startsWith("collect ")) {
                    return "collecting " + normalized.substring("collect ".length());
                }
                if (normalized.startsWith("fortify ")) {
                    return "working on fortifications";
                }
                return "working on " + normalized;
        }
    }
}
