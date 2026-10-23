package net.wcfcarolina13.GameAI.services;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** The recorded owner remains authoritative even while unavailable. */
public final class CompanionControllerPolicy {
    private CompanionControllerPolicy() {
    }

    public static <T> T resolve(UUID recordedOwner, Function<UUID, T> ownerLookup,
                                Supplier<T> recruiterFallback) {
        return recordedOwner != null ? ownerLookup.apply(recordedOwner) : recruiterFallback.get();
    }
}
