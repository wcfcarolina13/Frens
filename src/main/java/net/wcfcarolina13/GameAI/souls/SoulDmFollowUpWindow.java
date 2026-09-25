package net.wcfcarolina13.GameAI.souls;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * DM follow-up window (addressee rule R1): after a soul bot's DIRECT reply is actually delivered
 * to a player, that player's next unaddressed chat lines within {@link #WINDOW_MS} are treated as
 * a continuation of the DM ("what about iron?" right after Jake answered) instead of ambient chat.
 *
 * <p>Pure state, no Minecraft types: one entry per player holding the routing id of the player's
 * newest submitted DM and, when open, the window's bot and deadline. Every transition is a single
 * {@link ConcurrentHashMap#compute}-family call, so the worker-thread delivery hook and the
 * server-thread chat callback never interleave half an update.
 *
 * <p>Slow-reply guard: a reply opens the window only when its routing id is still the player's
 * newest submitted DM. With 4-16 s generations, a late reply from one bot must not steal the
 * window from a newer DM to another bot. {@link #close} forgets the pending id too, so a reply
 * still in flight when the player addresses someone else cannot reopen the window afterwards.
 *
 * <p>Expiry is exclusive: the window is open while {@code now < openedAt + WINDOW_MS} and closed
 * from the deadline itself onward.
 */
public final class SoulDmFollowUpWindow {

    public static final long WINDOW_MS = 30_000L;

    /** An open window as seen by one read: the bot it belongs to and how long ago it opened. */
    public record Open(UUID botId, long ageMs) {
        public Open {
            Objects.requireNonNull(botId, "botId");
        }
    }

    /** {@code botId == null} means no window is open (a DM may still be pending). */
    private record State(UUID newestRoutingId, UUID botId, long openedAtMs, long expiresAtMs) {
        State withoutWindow() {
            return new State(newestRoutingId, null, 0L, 0L);
        }
    }

    private final LongSupplier clock;
    private final long windowMs;
    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    public SoulDmFollowUpWindow(LongSupplier clock) {
        this(clock, WINDOW_MS);
    }

    SoulDmFollowUpWindow(LongSupplier clock, long windowMs) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (windowMs <= 0L) {
            throw new IllegalArgumentException("windowMs must be positive");
        }
        this.windowMs = windowMs;
    }

    /**
     * Records {@code routingId} as the player's newest DM (called at submit time). An already
     * open window stays open; only which reply may refresh it changes.
     */
    public void noteSubmitted(UUID playerId, UUID routingId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(routingId, "routingId");
        states.compute(playerId, (id, state) -> state == null
                ? new State(routingId, null, 0L, 0L)
                : new State(routingId, state.botId(), state.openedAtMs(), state.expiresAtMs()));
    }

    /**
     * A DIRECT reply from {@code botId} was delivered to the player. Opens (or refreshes) the
     * window for that bot only when {@code routingId} is still the player's newest submitted DM.
     *
     * @return whether the window was opened or refreshed
     */
    public boolean noteDelivered(UUID playerId, UUID botId, UUID routingId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(routingId, "routingId");
        long now = clock.getAsLong();
        boolean[] opened = {false};
        states.computeIfPresent(playerId, (id, state) -> {
            if (!routingId.equals(state.newestRoutingId())) {
                return state;
            }
            opened[0] = true;
            return new State(routingId, botId, now, now + windowMs);
        });
        return opened[0];
    }

    /** The player's open window, or empty when none is open or it has reached its deadline. */
    public Optional<Open> current(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        State state = states.get(playerId);
        if (state == null || state.botId() == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (now >= state.expiresAtMs()) {
            // Drop the expired window but keep the pending id: a reply still generating may
            // legitimately reopen it when it lands.
            states.computeIfPresent(playerId, (id, cur) -> cur == state ? cur.withoutWindow() : cur);
            return Optional.empty();
        }
        return Optional.of(new Open(state.botId(), Math.max(0L, now - state.openedAtMs())));
    }

    /**
     * Closes the window and forgets the pending DM id — explicit address, a line for another
     * human, or disconnect. A reply still in flight can no longer open a window.
     */
    public void close(UUID playerId) {
        if (playerId != null) {
            states.remove(playerId);
        }
    }

    /** Drops every player's state. */
    public void clear() {
        states.clear();
    }
}
