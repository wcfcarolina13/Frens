package net.wcfcarolina13.GameAI.services;

import java.util.Locale;
import java.util.Set;

/**
 * Which inventory stacks a cook filter ({@code /bot cook fish}, the fishing skill's "fish") picks.
 * Pure, so it is testable without Minecraft types: callers pass the stack's display name and its
 * item id path ({@code Registries.ITEM.getId(item).getPath()}).
 *
 * <p>A blank filter matches everything. Otherwise the filter matches as a normalized substring
 * (or with spaces removed) of the display name or the id path. "fish"/"fishes" is a group that
 * also takes the raw fish whose names do not say fish ("Raw Cod", "Raw Salmon").
 */
public final class CookFilterPolicy {
    private static final Set<String> FISH_GROUP_FILTERS = Set.of("fish", "fishes");
    private static final Set<String> FISH_ITEM_PATHS = Set.of("cod", "salmon", "tropical_fish", "pufferfish");

    private CookFilterPolicy() {}

    public static boolean matches(String filter, String displayName, String itemIdPath) {
        String filterNorm = normalize(filter);
        if (filterNorm.isEmpty()) {
            return true;
        }
        String filterSlug = filterNorm.replace(" ", "");
        if (contains(normalize(displayName), filterNorm, filterSlug)
                || contains(normalize(itemIdPath), filterNorm, filterSlug)) {
            return true;
        }
        return FISH_GROUP_FILTERS.contains(filterNorm)
                && itemIdPath != null
                && FISH_ITEM_PATHS.contains(itemIdPath.toLowerCase(Locale.ROOT));
    }

    private static boolean contains(String nameNorm, String filterNorm, String filterSlug) {
        if (nameNorm.isEmpty()) {
            return false;
        }
        return nameNorm.contains(filterNorm) || nameNorm.replace(" ", "").contains(filterSlug);
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
        return lower.replaceAll("\\s+", " ");
    }
}
