package net.wcfcarolina13.GameAI.services;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure rule for dropping base auto-zones whose base no longer exists (no Minecraft types).
 *
 * <p>Every registered base is mirrored as a protected zone labelled {@code base:<normalized label>}.
 * When a base is deleted (or a world started with bases that are not its own), the mirror zone
 * must go too. Only {@code base:} zones are ever candidates; user-drawn zones are never returned.
 */
public final class StaleAutoZonePolicy {

    public static final String AUTO_ZONE_LABEL_PREFIX = "base:";

    private StaleAutoZonePolicy() {}

    /** The auto-zone label for a base label: prefix + trimmed, lower-cased (ROOT) label. */
    public static String autoZoneLabel(String baseLabel) {
        String normalized = baseLabel == null ? "" : baseLabel.trim().toLowerCase(Locale.ROOT);
        return AUTO_ZONE_LABEL_PREFIX + normalized;
    }

    public static boolean isAutoZoneLabel(String zoneLabel) {
        return zoneLabel != null && zoneLabel.startsWith(AUTO_ZONE_LABEL_PREFIX);
    }

    /**
     * Zone labels to remove: existing {@code base:} zones with no matching current base.
     *
     * @param existingZoneLabels every zone label in the world (non-{@code base:} labels are ignored)
     * @param currentBaseLabels  labels of the bases registered for the world (raw, un-prefixed)
     * @return sorted set of zone labels to remove
     */
    public static Set<String> toRemove(Collection<String> existingZoneLabels, Collection<String> currentBaseLabels) {
        Set<String> out = new TreeSet<>();
        if (existingZoneLabels == null || existingZoneLabels.isEmpty()) {
            return out;
        }
        Set<String> expected = new HashSet<>();
        if (currentBaseLabels != null) {
            for (String base : currentBaseLabels) {
                if (base != null) {
                    expected.add(autoZoneLabel(base));
                }
            }
        }
        for (String zone : existingZoneLabels) {
            if (isAutoZoneLabel(zone) && !expected.contains(zone)) {
                out.add(zone);
            }
        }
        return out;
    }
}
