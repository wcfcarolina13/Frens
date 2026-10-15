package net.wcfcarolina13.GameAI.skills.support;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One-time player-facing explanation for why woodcut leaves nearby trees alone and walks off to
 * find others. Pure: no Minecraft types. The caller owns the "already said" set; a reason is said
 * at most once per set, so a set shared across bots means only the first bot says it.
 */
public final class WoodcutProtectionNudgePolicy {

    public static final String BASE_RADIUS_LINE =
            "The trees around here are inside your base, so I'm leaving them alone and heading out to find others.";
    public static final String ADMIN_ZONE_LINE =
            "The trees around here are in a protected area, so I'm heading out to find others.";
    public static final String SPAWN_BASE_LINE =
            "The trees around spawn are protected, so I'm heading out to find others.";
    public static final String VILLAGE_LINE =
            "The trees around here belong to the village, so I'm heading out to find others.";
    public static final String BEEHIVE_LINE =
            "Those trees have beehives, so I'm leaving them alone and heading out to find others.";

    /** Tie-break order when two reasons have the same count. */
    private static final List<String> PRIORITY =
            List.of("base-radius", "admin-zone", WoodcutBaseAccessPolicy.SPAWN_BASE_REASON,
                    "beehive", "mapped-village", "fort-buffer");

    private WoodcutProtectionNudgePolicy() {
    }

    /**
     * The protection reason with the highest count among the reasons this policy has a line for.
     * Proximity reasons ("human", "none") and unknown reasons are ignored. Ties go to
     * {@link #PRIORITY} order.
     */
    public static Optional<String> dominantReason(Map<String, Integer> reasonCounts) {
        if (reasonCounts == null || reasonCounts.isEmpty()) {
            return Optional.empty();
        }
        String best = null;
        int bestCount = 0;
        for (String reason : PRIORITY) {
            Integer count = reasonCounts.get(reason);
            if (count != null && count > bestCount) {
                best = reason;
                bestCount = count;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Player-facing line for a protection reason, or null when there is none. */
    public static String lineFor(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "base-radius" -> BASE_RADIUS_LINE;
            case "admin-zone" -> ADMIN_ZONE_LINE;
            case WoodcutBaseAccessPolicy.SPAWN_BASE_REASON -> SPAWN_BASE_LINE;
            case "mapped-village", "fort-buffer" -> VILLAGE_LINE;
            case "beehive" -> BEEHIVE_LINE;
            default -> null;
        };
    }

    /**
     * Key recorded in the "already said" set. Reasons that share a line share a key, so the
     * village line is said once whether it came from a mapped village or a fort buffer.
     */
    public static String saidKeyFor(String reason) {
        if (reason == null) {
            return null;
        }
        return switch (reason) {
            case "mapped-village", "fort-buffer" -> "village";
            default -> reason;
        };
    }

    /**
     * Decides whether to say the nudge. Returns the line when the dominant reason's key
     * ({@link #saidKeyFor}) has not been said yet, and records that key in {@code alreadySaid}. {@code alreadySaid.add} is the claim, so a
     * concurrent set makes the once-only rule hold across bots on different worker threads.
     */
    public static Optional<String> claimNudge(Map<String, Integer> reasonCounts, Set<String> alreadySaid) {
        Optional<String> reason = dominantReason(reasonCounts);
        if (reason.isEmpty() || alreadySaid == null) {
            return Optional.empty();
        }
        String line = lineFor(reason.get());
        if (line == null || !alreadySaid.add(saidKeyFor(reason.get()))) {
            return Optional.empty();
        }
        return Optional.of(line);
    }
}
