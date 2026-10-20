package net.wcfcarolina13.GameAI.services;

import java.util.Arrays;

/**
 * Pure rules for a chest-network Collect's scheduled withdraw (the {@code withdraw:<mode>}
 * post-arrival action). No Minecraft types, so it is unit tested.
 *
 * <p>The withdraw is scheduled before the travel starts (the travel's save picks it up). When the
 * travel is refused, it stays only if the cooldown gate queued a retry to this same chest: that
 * retry is the collect trip and must keep its withdraw. Any other refusal (combat, food, artifact
 * tier, mount) queues nothing, so the withdraw is removed instead of waiting for an unrelated trip.
 */
public final class CollectSchedulePolicy {

    private static final String WITHDRAW_PREFIX = "withdraw";

    private CollectSchedulePolicy() {}

    /**
     * Whether a just-scheduled withdraw stays after its travel call.
     *
     * @param started     the travel started
     * @param retryQueued a cooldown retry to this chest is waiting
     */
    public static boolean keepAction(boolean started, boolean retryQueued) {
        return started || retryQueued;
    }

    /** True when a post-arrival action type is a chest withdraw. */
    public static boolean isWithdraw(String type) {
        return type != null && type.startsWith(WITHDRAW_PREFIX);
    }

    /**
     * True when scheduling a withdraw at {@code next} overwrites a withdraw for a different chest,
     * so the owner should hear that the earlier collect was replaced. A missing previous action, a
     * non-withdraw action, a missing target or the same chest are all false.
     *
     * @param prevType the type of the action being overwritten, or null
     * @param prev     its target as {@code {x, y, z}}, or null
     * @param next     the new withdraw's target as {@code {x, y, z}}
     */
    public static boolean replacedDifferentTarget(String prevType, int[] prev, int[] next) {
        if (!isWithdraw(prevType) || prev == null || next == null) return false;
        return !Arrays.equals(prev, next);
    }
}
