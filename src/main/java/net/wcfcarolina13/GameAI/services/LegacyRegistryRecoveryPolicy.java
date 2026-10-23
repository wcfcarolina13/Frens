package net.wcfcarolina13.GameAI.services;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Confirmed recovery keeps an independent copy in the selected save and retains the source. */
public final class LegacyRegistryRecoveryPolicy {
    public enum Result { IMPORTED, TARGET_EXISTS, NO_LEGACY, WRONG_CONFIRMATION, INVALID_TARGET, INVALID_SOURCE, SAVE_FAILED }
    public record Copy<T>(Result result, T imported) {}

    /**
     * @param siblings    other fingerprinted buckets of this level name and dimension, each as
     *                    {@code key records=N} (see {@link WorldStoreKeyPolicy#isSiblingKey})
     * @param adoptedHere this save already adopted the legacy bucket at load
     */
    public record Preview(String legacyKey, String targetKey, int records, boolean targetExists,
                          List<String> entries, List<String> siblings, boolean adoptedHere) {}

    /** Deep copy of one bucket for {@code targetKey} in {@code dimension}, rekeying embedded world fields. */
    @FunctionalInterface
    public interface Copier<T> {
        T copy(T source, String targetKey, String dimension);
    }

    public enum Outcome {
        /** Copied into this save's bucket and claimed. */
        ADOPTED,
        /** This save already has its own bucket for the dimension. */
        USE_OWN,
        /** Brand-new world: declined now, and the decline is recorded so it sticks. */
        DECLINED_NEW_WORLD,
        /** Declined on an earlier load (brand-new then). */
        ALREADY_DECLINED,
        /** Another save adopted this legacy bucket. */
        CLAIMED_ELSEWHERE,
        /** The copy was refused (see {@link Step#result()}). */
        FAILED
    }

    public record Step<T>(String legacyKey, String targetKey, Outcome outcome, Result result,
                          T imported, String previousClaim) {}

    /**
     * @param refused the fingerprint is unusable ({@code #unknown} or malformed): nothing was looked at
     */
    public record Adoption<T>(boolean refused, List<Step<T>> steps) {
        public boolean changed() {
            return steps.stream().anyMatch(s -> s.outcome() == Outcome.ADOPTED || s.outcome() == Outcome.DECLINED_NEW_WORLD);
        }
    }

    private LegacyRegistryRecoveryPolicy() {}

    public static <T> Copy<T> copyLegacy(Map<String, T> worlds, String legacyKey, String targetKey,
                                         String fingerprint, String confirmation, Function<T, T> copier) {
        if (invalidTarget(legacyKey, targetKey, fingerprint)) return new Copy<>(Result.INVALID_TARGET, null);
        if (!fingerprint.equals(confirmation)) return new Copy<>(Result.WRONG_CONFIRMATION, null);
        if (worlds == null || worlds.get(legacyKey) == null) return new Copy<>(Result.NO_LEGACY, null);
        if (worlds.containsKey(targetKey)) return new Copy<>(Result.TARGET_EXISTS, null);
        T legacy = worlds.get(legacyKey);
        T copied = copier.apply(legacy);
        if (copied == null || copied == legacy) return new Copy<>(Result.SAVE_FAILED, null);
        worlds.put(targetKey, copied);
        return new Copy<>(Result.IMPORTED, copied);
    }

    /**
     * Explicit import from another fingerprinted bucket of this level name and dimension (a moved
     * instance). Same rules as {@link #copyLegacy}; a source that is not a sibling of the target
     * is {@link Result#INVALID_SOURCE}.
     */
    public static <T> Copy<T> copyFromSibling(Map<String, T> worlds, String sourceKey, String levelName,
                                              String dimension, String targetKey, String fingerprint,
                                              String confirmation, Function<T, T> copier) {
        if (invalidTarget(sourceKey, targetKey, fingerprint)) return new Copy<>(Result.INVALID_TARGET, null);
        if (!WorldStoreKeyPolicy.isSiblingKey(sourceKey, levelName, dimension, targetKey)) {
            return new Copy<>(Result.INVALID_SOURCE, null);
        }
        return copyLegacy(worlds, sourceKey, targetKey, fingerprint, confirmation, copier);
    }

    public static <T> boolean rollback(Map<String, T> worlds, String targetKey, T imported) {
        return imported != null && worlds.remove(targetKey, imported);
    }

    /** Whether a fingerprint may own copied data: {@code level#hash}, hash present and not {@code unknown}. */
    public static boolean usableFingerprint(String fingerprint) {
        return fingerprint != null && fingerprint.contains("#") && !fingerprint.endsWith("#unknown")
                && !fingerprint.endsWith("#");
    }

    /**
     * Load-time adoption of every legacy bucket of this level name (each dimension), so an existing
     * save keeps its data after the switch to fingerprinted keys. Uses {@link WorldStoreKeyPolicy#decide}:
     * a save that already has its own bucket keeps it, a brand-new world declines and records the
     * decline in {@code declined}, a legacy bucket claimed by another save is left alone. An adoption
     * is a {@link #copyLegacy} confirmed by the fingerprint itself (deep copy, legacy kept, an existing
     * destination never overwritten) and records the claim in {@code claims}. No empty bucket is
     * ever written. The caller saves when {@link Adoption#changed()} and calls {@link #undo} if that fails.
     */
    public static <T> Adoption<T> adoptLegacy(Map<String, T> worlds, Map<String, String> claims, Set<String> declined,
                                              String rawLevelName, char separator, String fingerprint,
                                              long overworldGameTime, Copier<T> copier) {
        if (!usableFingerprint(fingerprint)) {
            return new Adoption<>(true, List.of());
        }
        List<Step<T>> steps = new ArrayList<>();
        if (worlds == null || claims == null || declined == null) {
            return new Adoption<>(false, steps);
        }
        List<String> legacyKeys = worlds.keySet().stream()
                .filter(k -> worlds.get(k) != null && WorldStoreKeyPolicy.legacyKeyDimension(k, rawLevelName, separator) != null)
                .sorted().toList();
        for (String legacyKey : legacyKeys) {
            String dimension = WorldStoreKeyPolicy.legacyKeyDimension(legacyKey, rawLevelName, separator);
            String targetKey = WorldStoreKeyPolicy.fingerprintKey(fingerprint, dimension);
            String claimant = claims.get(legacyKey);
            if (worlds.containsKey(targetKey)) {
                steps.add(new Step<>(legacyKey, targetKey, Outcome.USE_OWN, null, null, claimant));
                continue;
            }
            if (declined.contains(targetKey)) {
                steps.add(new Step<>(legacyKey, targetKey, Outcome.ALREADY_DECLINED, null, null, claimant));
                continue;
            }
            WorldStoreKeyPolicy.Adoption decision = WorldStoreKeyPolicy.decide(
                    false, true, overworldGameTime, claimant, fingerprint);
            switch (decision) {
                case START_EMPTY_NEW_WORLD -> {
                    declined.add(targetKey);
                    steps.add(new Step<>(legacyKey, targetKey, Outcome.DECLINED_NEW_WORLD, null, null, claimant));
                }
                case ADOPT_LEGACY -> {
                    Copy<T> copy = copyLegacy(worlds, legacyKey, targetKey, fingerprint, fingerprint,
                            source -> copier.copy(source, targetKey, dimension));
                    if (copy.result() == Result.IMPORTED) {
                        claims.put(legacyKey, fingerprint);
                        steps.add(new Step<>(legacyKey, targetKey, Outcome.ADOPTED, Result.IMPORTED, copy.imported(), claimant));
                    } else {
                        steps.add(new Step<>(legacyKey, targetKey, Outcome.FAILED, copy.result(), null, claimant));
                    }
                }
                default -> steps.add(new Step<>(legacyKey, targetKey, Outcome.CLAIMED_ELSEWHERE, null, null, claimant));
            }
        }
        return new Adoption<>(false, steps);
    }

    /** Reverses an {@link #adoptLegacy} pass whose save failed: copies, claims and declines it added. */
    public static <T> void undo(Adoption<T> adoption, Map<String, T> worlds, Map<String, String> claims,
                                Set<String> declined) {
        if (adoption == null) return;
        for (Step<T> step : adoption.steps()) {
            if (step.outcome() == Outcome.ADOPTED) {
                if (worlds != null) rollback(worlds, step.targetKey(), step.imported());
                if (claims != null) {
                    if (step.previousClaim() == null) claims.remove(step.legacyKey());
                    else claims.put(step.legacyKey(), step.previousClaim());
                }
            } else if (step.outcome() == Outcome.DECLINED_NEW_WORLD && declined != null) {
                declined.remove(step.targetKey());
            }
        }
    }

    /** One INFO line per decision that did something or needs explaining. */
    public static <T> void log(Logger logger, String label, String fingerprint, Adoption<T> adoption) {
        if (logger == null || adoption == null) return;
        if (adoption.refused()) {
            logger.warn("Legacy {} not adopted: the save fingerprint '{}' is not usable. Legacy data left untouched.",
                    label, fingerprint);
            return;
        }
        for (Step<T> step : adoption.steps()) {
            switch (step.outcome()) {
                case ADOPTED -> logger.info("Adopted legacy {} '{}' as '{}'. Legacy original kept.",
                        label, step.legacyKey(), step.targetKey());
                case DECLINED_NEW_WORLD -> logger.info("Brand-new save '{}' did not adopt legacy {} '{}'. It stays for its own save.",
                        step.targetKey(), label, step.legacyKey());
                case CLAIMED_ELSEWHERE -> logger.info("Legacy {} '{}' was adopted by '{}', not by '{}'.",
                        label, step.legacyKey(), step.previousClaim(), step.targetKey());
                case FAILED -> logger.warn("Legacy {} '{}' was not adopted as '{}': {}.",
                        label, step.legacyKey(), step.targetKey(), step.result());
                default -> { }
            }
        }
    }

    private static boolean invalidTarget(String sourceKey, String targetKey, String fingerprint) {
        return !usableFingerprint(fingerprint) || targetKey == null || targetKey.equals(sourceKey)
                || !targetKey.startsWith(fingerprint + ":");
    }
}
