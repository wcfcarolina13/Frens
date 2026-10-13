package net.wcfcarolina13.GraphicalUserInterface;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which saved companions the roster should offer to restore: the known aliases minus the
 * ones already in the world (case-insensitive, order preserved).
 */
public final class RosterPresencePolicy {

    private RosterPresencePolicy() {
    }

    /**
     * @param known            known bot aliases, in display order
     * @param presentLowercase names of connected players, trimmed and lower-cased (ROOT locale)
     */
    public static List<String> restorable(List<String> known, Set<String> presentLowercase) {
        List<String> out = new ArrayList<>();
        if (known == null) {
            return out;
        }
        for (String alias : known) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            if (presentLowercase != null && presentLowercase.contains(normalize(alias))) {
                continue;
            }
            out.add(alias);
        }
        return out;
    }

    /** True when at least one known alias is among the present names. */
    public static boolean anyPresent(Collection<String> known, Set<String> presentLowercase) {
        if (known == null || presentLowercase == null || presentLowercase.isEmpty()) {
            return false;
        }
        for (String alias : known) {
            if (alias != null && !alias.isBlank() && presentLowercase.contains(normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
