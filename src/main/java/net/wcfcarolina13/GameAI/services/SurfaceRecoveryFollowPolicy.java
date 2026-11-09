package net.wcfcarolina13.GameAI.services;

/**
 * Whether surface recovery may suspend follow while it walks the bot up. Only a plain player
 * follow: SkillManager.FollowResumeState cannot restore return-to-base (baseTarget) or force-walk
 * follow (followNoTeleport), so those keep steering as before rather than coming back degraded.
 */
public final class SurfaceRecoveryFollowPolicy {

    private SurfaceRecoveryFollowPolicy() {
    }

    public static boolean suspendFollow(boolean returningToBase, boolean followNoTeleport) {
        return !returningToBase && !followNoTeleport;
    }
}
