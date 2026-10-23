package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Verifies registry entries without letting an older read discard a refreshed registration. */
final class BeehiveVerificationPolicy {
    private BeehiveVerificationPolicy() {}

    private record Checked<T>(T record, long revision) {}

    static <T> boolean removeMissing(Object lock, List<T> records, ToLongFunction<T> revision,
                                     Predicate<T> candidate, Predicate<T> knownMissing) {
        List<Checked<T>> snapshot = new ArrayList<>();
        synchronized (lock) {
            for (T record : records) {
                if (record != null && candidate.test(record)) {
                    snapshot.add(new Checked<>(record, revision.applyAsLong(record)));
                }
            }
        }
        List<Checked<T>> missing = new ArrayList<>();
        // A worker's block read can wait for the server, which also uses this registry.
        for (Checked<T> check : snapshot) {
            if (knownMissing.test(check.record())) {
                missing.add(check);
            }
        }
        synchronized (lock) {
            boolean changed = false;
            for (Checked<T> check : missing) {
                if (revision.applyAsLong(check.record()) == check.revision()) {
                    changed |= records.remove(check.record());
                }
            }
            return changed;
        }
    }
}
