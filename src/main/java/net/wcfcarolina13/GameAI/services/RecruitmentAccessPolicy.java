package net.wcfcarolina13.GameAI.services;

import java.util.UUID;

/** A recruitment reply cannot select another companion or take over an owned one. */
public final class RecruitmentAccessPolicy {
    public enum Decision { ALLOW, DENY_ALIAS, DENY_OWNER }

    private RecruitmentAccessPolicy() { }

    public static Decision authorize(String expectedAlias, String requestedAlias, UUID requester,
                                     String recordedOwner) {
        if (expectedAlias == null || expectedAlias.isBlank()
                || (requestedAlias != null && !requestedAlias.isBlank()
                && !expectedAlias.trim().equalsIgnoreCase(requestedAlias.trim()))) {
            return Decision.DENY_ALIAS;
        }
        if (requester == null || (recordedOwner != null && !recordedOwner.isBlank()
                && !stillOwnedBy(requester, recordedOwner))) {
            return Decision.DENY_OWNER;
        }
        return Decision.ALLOW;
    }

    /** Delayed setup may act only while the recorded recruitment ownership still holds. */
    public static boolean stillOwnedBy(UUID recruiter, String recordedOwner) {
        if (recruiter == null || recordedOwner == null || recordedOwner.isBlank()) return false;
        try {
            return recruiter.equals(UUID.fromString(recordedOwner.trim()));
        } catch (IllegalArgumentException invalidOwner) {
            return false;
        }
    }
}
