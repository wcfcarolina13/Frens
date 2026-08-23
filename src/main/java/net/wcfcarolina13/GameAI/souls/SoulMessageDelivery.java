package net.wcfcarolina13.GameAI.souls;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Server-thread-only private delivery boundary for the soul-communication pipeline.
 *
 * <p>{@link #deliverReply} always schedules its work via {@link MinecraftServer#execute}: it
 * resolves the bot/player {@link ServerPlayerEntity} instances live from the player manager, asks
 * a {@link DeliveryGuard} for a final go/no-go recheck, and — only if both are online and the
 * guard approves — sends {@code Text.literal(botName + ": " + text)} privately to that one
 * player. This class never constructs a bot command source and never calls into {@code ChatUtils}
 * or a voice mapper; it is a thin, dumb pipe from validated dialogue text to one player's chat.
 */
public final class SoulMessageDelivery implements SoulConversationService.Delivery {

    // A dedicated logger (never Frens.LOGGER) -- referencing the Frens class at all triggers its
    // static initializer, which fails outside a running game and would break this class's own
    // unit-testable pure evaluate() combinator.
    private static final Logger LOGGER = LoggerFactory.getLogger("frens.souls");

    /**
     * Final go/no-go recheck performed immediately before a reply is actually sent, to catch
     * anything that changed between when generation started and when the reply is ready.
     */
    public interface DeliveryGuard {
        boolean canDeliver(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token);
    }

    private final MinecraftServer server;
    private final DeliveryGuard guard;

    public SoulMessageDelivery(MinecraftServer server, DeliveryGuard guard) {
        this.server = Objects.requireNonNull(server, "server");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    @Override
    public CompletableFuture<Boolean> deliverReply(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token,
                                                     String text) {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(token, "token");
        String safeText = text == null ? "" : text;

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        server.execute(() -> {
            long startNanos = System.nanoTime();
            boolean delivered = false;
            try {
                ServerPlayerEntity bot = server.getPlayerManager().getPlayer(turn.key().botId());
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(turn.key().playerId());
                if (bot != null && player != null && guard.canDeliver(turn, token)) {
                    player.sendMessage(Text.literal(turn.botDisplayName() + ": " + safeText), false);
                    delivered = true;
                }
            } catch (RuntimeException ex) {
                // Never let a delivery-time failure escape the server tick; report it as a
                // normal non-delivery instead.
                delivered = false;
            }
            long elapsedMillis = elapsedMillis(startNanos);
            LOGGER.info("[souls] delivery correlationId={} delivered={} elapsedMs={}",
                    token.correlationId(), delivered, elapsedMillis);
            future.complete(delivered);
        });
        return future;
    }

    @Override
    public void deliverStatus(UUID playerId, String text) {
        Objects.requireNonNull(playerId, "playerId");
        String safeText = text == null ? "" : text;
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
            if (player != null) {
                player.sendMessage(Text.literal(safeText), false);
            }
        });
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /**
     * Pure combinator for the six delivery gates this class's production guard enforces. Kept
     * free of Minecraft/Fabric types and {@link SoulStore} I/O so it can be exhaustively unit
     * tested without a running server; {@link ProductionDeliveryGuard} is the only caller.
     */
    static boolean evaluate(boolean masterEnabled, boolean profileUnchanged, boolean epochMatches,
                             boolean bothOnline, boolean ownershipValid, SoulTypes.Reachability reachability) {
        return masterEnabled && profileUnchanged && epochMatches && bothOnline && ownershipValid
                && reachability != SoulTypes.Reachability.UNREACHABLE;
    }

    /**
     * Production {@link DeliveryGuard}. Requires, all at once: the master soul-communication
     * switch is on, the bot's bound profile hasn't changed since the turn was accepted, the
     * conversation's cursor epoch still matches the turn token's epoch, both the bot and the
     * player are online, the actor is still exactly authorized for private soul communication
     * with this bot ({@link CompanionCommunicationPolicy#isPrivateSoulAuthorized}), and current
     * reachability is not {@link SoulTypes.Reachability#UNREACHABLE}
     * ({@link CompanionCommunicationPolicy#classifySoulReachability}).
     *
     * <p>{@code SoulRuntime} does not exist yet (a later task), so this guard takes only the
     * minimal, explicit dependencies it actually needs: the server (to resolve live entities),
     * the store (to re-read the conversation's current profile/epoch), and a live master-enabled
     * probe supplied by the caller.
     *
     * <p>The profile/epoch recheck is the one piece of this guard that is not purely
     * synchronous: {@link SoulStore} is deliberately I/O-off-thread everywhere else in this
     * pipeline, but this recheck runs synchronously inside {@link MinecraftServer#execute}
     * (per {@link SoulMessageDelivery#deliverReply}'s contract), so it bounds the wait to a short
     * timeout and fails closed (no delivery) rather than block the server thread indefinitely.
     * {@code soul.json} is a small local file read off a normally-idle single-writer executor, so
     * in practice this resolves in well under the timeout.
     */
    public static final class ProductionDeliveryGuard implements DeliveryGuard {

        private static final Duration STATE_LOOKUP_TIMEOUT = Duration.ofMillis(500);

        private final MinecraftServer server;
        private final SoulStore store;
        private final BooleanSupplier masterEnabled;

        public ProductionDeliveryGuard(MinecraftServer server, SoulStore store, BooleanSupplier masterEnabled) {
            this.server = Objects.requireNonNull(server, "server");
            this.store = Objects.requireNonNull(store, "store");
            this.masterEnabled = Objects.requireNonNull(masterEnabled, "masterEnabled");
        }

        @Override
        public boolean canDeliver(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token) {
            long startNanos = System.nanoTime();
            boolean result = evaluateLive(turn, token);
            long elapsedMillis = elapsedMillis(startNanos);
            LOGGER.info("[souls] delivery-recheck correlationId={} outcome={} elapsedMs={}",
                    token.correlationId(), result, elapsedMillis);
            return result;
        }

        private boolean evaluateLive(SoulTypes.AcceptedTurn turn, SoulTypes.TurnToken token) {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(turn.key().botId());
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(turn.key().playerId());
            boolean bothOnline = bot != null && player != null;

            boolean profileUnchanged = false;
            boolean epochMatches = false;
            if (bothOnline) {
                try {
                    SoulTypes.SoulState state = store.state(turn.key().botId())
                            .get(STATE_LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    profileUnchanged = turn.profileId().equals(state.profileId());
                    String cursorKey = turn.key().channel().name() + ":" + turn.key().playerId();
                    long currentEpoch = state.conversations()
                            .getOrDefault(cursorKey, new SoulTypes.ConversationCursor(0L, 0L)).epoch();
                    epochMatches = currentEpoch == token.epoch();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (ExecutionException | TimeoutException ex) {
                    return false;
                }
            }

            boolean ownershipValid = bothOnline && CompanionCommunicationPolicy.isPrivateSoulAuthorized(player, bot);
            SoulTypes.Reachability reachability = bothOnline
                    ? CompanionCommunicationPolicy.classifySoulReachability(bot, player)
                    : SoulTypes.Reachability.UNREACHABLE;

            return evaluate(masterEnabled.getAsBoolean(), profileUnchanged, epochMatches,
                    bothOnline, ownershipValid, reachability);
        }
    }
}
