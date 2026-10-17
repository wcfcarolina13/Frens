package net.wcfcarolina13.GameAI.services;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure rules for which item entities a {@code /bot retrieve} sweep may pick up: the ones that
 * dropped when the bot or player died. The death hook tags them by entity UUID; the sweep only
 * targets tagged items. No Minecraft types, so it is unit tested.
 *
 * <p>A null or empty filter means "everything" (old death records, deaths with nothing dropped).
 * Vanilla collision pickup of other items the bot walks over still happens (ruling), and a tagged
 * stack that merges into another may lose its tag (accepted).
 */
public final class DeathDropFilterPolicy {

    /** At most this many drops are tagged per death (a full inventory plus armor is 41 stacks). */
    public static final int MAX_TAGGED = 64;
    /** Tagging box around the death position: drops spawn at eye height with a small throw. */
    public static final double TAG_RADIUS = 2.0D;
    public static final double TAG_DY_MIN = -1.0D;
    public static final double TAG_DY_MAX = 3.0D;
    /** The death hook runs in the same tick the drops spawn, before they tick. */
    public static final int MAX_AGE_TICKS = 1;

    private DeathDropFilterPolicy() {}

    /** True when an item entity this old and this far from the death position is one of its drops. */
    public static boolean isDeathDrop(int ageTicks, double dx, double dy, double dz) {
        return ageTicks >= 0 && ageTicks <= MAX_AGE_TICKS
                && Math.abs(dx) <= TAG_RADIUS && Math.abs(dz) <= TAG_RADIUS
                && dy >= TAG_DY_MIN && dy <= TAG_DY_MAX;
    }

    /** True when the sweep may pick up this item: no filter, or the item is tagged. */
    public static boolean allowed(Set<UUID> filter, UUID itemUuid) {
        if (filter == null || filter.isEmpty()) return true;
        return itemUuid != null && filter.contains(itemUuid);
    }

    /** The tagged UUIDs as a set, skipping malformed ones; null when there are none. */
    public static Set<UUID> toFilter(List<String> dropUuids) {
        if (dropUuids == null || dropUuids.isEmpty()) return null;
        Set<UUID> out = new HashSet<>();
        for (String s : dropUuids) {
            if (s == null) continue;
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * True when a death at {@code stand + (dx, dy, dz)} is one the stand point could have been chosen
     * for (the stand-point search box around the death, mirrored). A record that fails this is a later
     * death somewhere else, so its drops are not this trip's.
     */
    public static boolean deathNearStand(int dx, int dy, int dz) {
        int r = DeathRetrievalPolicy.STAND_RADIUS;
        return Math.abs(dx) <= r && Math.abs(dz) <= r
                && dy >= -DeathRetrievalPolicy.STAND_DY_MAX && dy <= -DeathRetrievalPolicy.STAND_DY_MIN;
    }
}
