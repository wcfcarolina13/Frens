package net.wcfcarolina13.GameAI.services;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side record of outstanding sunset return prompts. The server records an offer when it
 * sends {@code NavigationRequestPayload} to one player about one bot; the matching
 * {@code NavigationResponsePayload} (accept or dismiss) must consume it. Offers are single-use and
 * expire after {@link #TTL_MS}, so a client cannot answer a prompt it was never sent, answer one
 * twice, or answer one meant for someone else.
 *
 * <p>Free of Minecraft imports; the clock is a parameter so expiry and replay are unit-testable.
 * {@link #SHARED} is the server's instance.
 */
public final class NavigationOfferPolicy {

    /** How long a prompt stays answerable. */
    public static final long TTL_MS = 120_000L;

    /** The instance the server records and consumes offers in. */
    public static final NavigationOfferPolicy SHARED = new NavigationOfferPolicy();

    /** Outcome of {@link #consume}. */
    public enum Result { CONSUMED, EXPIRED, NONE }

    private record Key(UUID botUuid, UUID recipientUuid) {}

    private final Map<Key, Long> expiresAtMs = new HashMap<>();

    /**
     * Records that {@code recipientUuid} was asked about {@code botUuid} at {@code nowMs}. A repeat
     * offer for the same pair restarts its TTL. Expired offers are pruned here.
     */
    public synchronized void offer(UUID botUuid, UUID recipientUuid, long nowMs) {
        if (botUuid == null || recipientUuid == null) return;
        expiresAtMs.values().removeIf(expiry -> nowMs >= expiry);
        expiresAtMs.put(new Key(botUuid, recipientUuid), nowMs + TTL_MS);
    }

    /**
     * Takes the offer for this exact (bot, recipient) pair: {@link Result#CONSUMED} once while it
     * is live, {@link Result#EXPIRED} if its TTL has passed (and removes it), otherwise
     * {@link Result#NONE}.
     */
    public synchronized Result consume(UUID botUuid, UUID recipientUuid, long nowMs) {
        if (botUuid == null || recipientUuid == null) return Result.NONE;
        Long expiry = expiresAtMs.remove(new Key(botUuid, recipientUuid));
        if (expiry == null) return Result.NONE;
        return nowMs < expiry ? Result.CONSUMED : Result.EXPIRED;
    }

    /** Number of offers held, live or not yet pruned. */
    public synchronized int size() {
        return expiresAtMs.size();
    }
}
