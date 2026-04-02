package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class WoodcutRerouteBlacklist {
    private final Map<String, Long> blockedUntilMs = new HashMap<>();
    private final long ttlMs;

    WoodcutRerouteBlacklist(long ttlMs) {
        this.ttlMs = Math.max(1L, ttlMs);
    }

    void recordFailure(BlockPos target, BlockPos candidate, long nowMs) {
        if (candidate == null) {
            return;
        }
        blockedUntilMs.put(key(target, candidate), nowMs + ttlMs);
        purgeExpired(nowMs);
    }

    boolean isBlacklisted(BlockPos target, BlockPos candidate, long nowMs) {
        if (candidate == null) {
            return false;
        }
        purgeExpired(nowMs);
        Long blockedUntil = blockedUntilMs.get(key(target, candidate));
        return blockedUntil != null && blockedUntil > nowMs;
    }

    private void purgeExpired(long nowMs) {
        Iterator<Map.Entry<String, Long>> iterator = blockedUntilMs.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= nowMs) {
                iterator.remove();
            }
        }
    }

    private String key(BlockPos target, BlockPos candidate) {
        String targetKey = target == null ? "none" : target.toShortString();
        return targetKey + "->" + candidate.toShortString();
    }
}
