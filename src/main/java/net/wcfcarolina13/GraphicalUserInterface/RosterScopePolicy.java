package net.wcfcarolina13.GraphicalUserInterface;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits the saved companion list into this save's companions and companions from other saves.
 * Saved companions live in one global config, so without this the roster shows every bot the
 * player ever made in any world. Pure so it is unit tested.
 *
 * <p>A companion belongs to this world when it has a spawn point or a saved position under the
 * current world key. A companion with no per-world record anywhere (never persisted, or only a
 * pre per-world legacy spawn) stays in this world, because nothing says it came from elsewhere.
 * When the current world key is unknown (a multiplayer client has no integrated server) every
 * companion stays in this world, which is the roster's behaviour before the split.
 */
public final class RosterScopePolicy {

    private RosterScopePolicy() {
    }

    /** This world's companions and other worlds' companions, each in the input order. */
    public record Scope(List<String> thisWorld, List<String> otherWorlds) {
        public Scope {
            thisWorld = List.copyOf(thisWorld);
            otherWorlds = List.copyOf(otherWorlds);
        }
    }

    /**
     * @param aliases            every known companion alias, in display order
     * @param inThisWorldLower   aliases with a record under the current world key, normalized with
     *                           {@link RosterPresencePolicy#normalize}
     * @param withAnyWorldLower  aliases with a record under any world key, normalized the same way
     * @param currentKeyKnown    false when the current world key cannot be computed
     */
    public static Scope scope(List<String> aliases, Set<String> inThisWorldLower, Set<String> withAnyWorldLower,
                              boolean currentKeyKnown) {
        List<String> thisWorld = new ArrayList<>();
        List<String> otherWorlds = new ArrayList<>();
        if (aliases == null) {
            return new Scope(thisWorld, otherWorlds);
        }
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            if (!currentKeyKnown || belongsHere(alias, inThisWorldLower, withAnyWorldLower)) {
                thisWorld.add(alias);
            } else {
                otherWorlds.add(alias);
            }
        }
        return new Scope(thisWorld, otherWorlds);
    }

    private static boolean belongsHere(String alias, Set<String> inThisWorldLower, Set<String> withAnyWorldLower) {
        String key = RosterPresencePolicy.normalize(alias);
        if (inThisWorldLower != null && inThisWorldLower.contains(key)) {
            return true;
        }
        return withAnyWorldLower == null || !withAnyWorldLower.contains(key);
    }

    /**
     * Whether a per-world store key counts as the current world: the save fingerprint, or the
     * pre-fingerprint level-name key that the world-state store still migrates on load.
     * {@code "_legacy"} spawn entries and blank keys never count as any world.
     */
    public static boolean isCurrentWorldKey(String storeKey, String currentFingerprint, String currentLevelName) {
        if (storeKey == null || storeKey.isBlank() || isUnscopedKey(storeKey)) {
            return false;
        }
        if (currentFingerprint != null && storeKey.equals(currentFingerprint)) {
            return true;
        }
        return currentLevelName != null && !currentLevelName.isBlank() && storeKey.equals(currentLevelName);
    }

    /** True for keys that do not name a world ({@code "_legacy"} spawn entries, blank keys). */
    public static boolean isUnscopedKey(String storeKey) {
        return storeKey == null || storeKey.isBlank() || "_legacy".equals(storeKey);
    }
}
