package net.wcfcarolina13.GameAI.services.construction.execution;

/**
 * Canonical failure taxonomy for construction execution.
 */
public enum FailureReason {
    MOVEMENT_FAILED,
    BLOCKED_BY_SOLID,
    NO_SUPPORT,
    NO_LOS,
    OUT_OF_REACH,
    NO_MATERIAL,
    PLACE_REJECTED,
    ABORTED,
    TIME_BUDGET,
    UNKNOWN;

    public static FailureReason fromPlaceReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return UNKNOWN;
        }

        String r = reason.toLowerCase();
        if (r.equals("movement") || r.contains("movement")) {
            return MOVEMENT_FAILED;
        }
        if (r.startsWith("blocked-by=")) {
            return BLOCKED_BY_SOLID;
        }
        if (r.startsWith("no-solid-support")) {
            return NO_SUPPORT;
        }
        if (r.startsWith("no-line-of-sight-to-support") || r.startsWith("no-line-of-sight")) {
            return NO_LOS;
        }
        if (r.startsWith("occupied=") || r.startsWith("occupied")) {
            return BLOCKED_BY_SOLID;
        }
        if (r.startsWith("place-rejected")) {
            return PLACE_REJECTED;
        }
        if (r.startsWith("out-of-reach")) {
            return OUT_OF_REACH;
        }
        if (r.startsWith("no-material")) {
            return NO_MATERIAL;
        }
        return UNKNOWN;
    }
}
