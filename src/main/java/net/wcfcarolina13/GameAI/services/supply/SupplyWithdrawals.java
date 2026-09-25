package net.wcfcarolina13.GameAI.services.supply;

import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.BlockInteractionService;
import net.wcfcarolina13.GameAI.services.BotChestRegistryService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestOutcome;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferOutcome;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TransferAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The one way a companion takes items out of a chest on its own. Every automatic withdrawal —
 * a tool, seeds, food, crafting materials, idle equipment — calls {@link #withdraw}, and this is
 * the only class that calls {@link SupplyRequestService#request} and
 * {@link SupplyRequestService#transferNow} ({@code SupplyDormancyTest} keeps it that way). What the
 * owner does themselves — {@code /bot withdraw}, Quick Fetch — is the owner's own act and does not
 * come through here.
 *
 * <p><b>Ask, walk, take.</b> Asking and taking are separate: the owner answers a prompt in chat,
 * and the bot may have to walk to the chest. Between calls the facade keeps a per-bot
 * <i>ticket</i>: the request's fingerprint, the only key the owner's grant can be spent with (a
 * fresh request would hit the bot's prompt cooldown instead). A caller that must walk calls once
 * with {@link WaitMode#NONE} before walking — {@link Kind#REFUSED} means skip this chest and don't
 * walk — walks, then calls again with the same chest and item to take. One ticket per bot; a
 * ticket lives for the prompt's lifetime plus the grant's, and is swept once a second. A ticket
 * whose prompt was refused or expired, or whose grant lapsed, is dropped with {@code NOT_PERMITTED}
 * before any reach check, so {@link Kind#READY} always means permitted.
 *
 * <p>{@link #grantableEstimate} lets a caller count, before asking, how much of a chest's stock
 * the policy could ever grant (allowlist and reserve only; advisory).
 *
 * <p><b>Threading.</b> Any thread. On the server thread a call runs one non-blocking step, and
 * {@link WaitMode#UNTIL_ANSWERED} is treated as {@link WaitMode#NONE} (with one WARN per run of
 * the game). Off it, each step runs on the server thread through a bounded hop (about 2.5 s); a
 * step the worker gave up on before it started does nothing when it finally runs. With
 * {@link WaitMode#UNTIL_ANSWERED} a worker that got {@link Kind#WAITING} polls the ledger every
 * 500 ms — without touching the world — until the prompt is answered or expires, then takes one
 * more step.
 */
public final class SupplyWithdrawals {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens-supply");

    /** How long a worker waits for its step to start on the server thread before giving up on it. */
    private static final long HOP_TIMEOUT_MS = 2_500L;
    /** How often an {@link WaitMode#UNTIL_ANSWERED} wait polls the ledger. */
    private static final long POLL_INTERVAL_MS = 500L;

    /** Whether a worker may wait for the owner's answer inside one call. */
    public enum WaitMode {
        /** Never wait: report {@link Kind#WAITING} and let a later call pick the answer up. */
        NONE,
        /** Off the server thread only: wait for the answer (or expiry), then take one more step. */
        UNTIL_ANSWERED
    }

    /** What a call achieved. */
    public enum Kind {
        /** Items were moved into the bot's inventory; {@link Result#moved()} says how many. */
        MOVED,
        /** Permitted, but the bot cannot reach the chest from where it stands: walk, then call again. */
        READY,
        /** The owner has not answered yet: call again later (the ticket is kept). */
        WAITING,
        /** Nothing will be taken now; {@link Result#reason()} says why. */
        REFUSED
    }

    /**
     * @param kind   what happened
     * @param moved  items moved into the bot's inventory (0 unless {@link Kind#MOVED})
     * @param reason a short machine-readable why: for {@link Kind#REFUSED} one of
     *               {@code NOT_RUNNING}, {@code BOT_GONE}, {@code INVALID}, {@code INELIGIBLE(<verdict>)}
     *               (the item is never asked for), {@code OTHER_REQUEST_PENDING}, a request status
     *               such as {@code PROMPT_COOLDOWN}, {@code REJECT_COOLDOWN}, {@code OWNER_NOT_NEARBY},
     *               {@code DENIED(<access>)} or {@code INELIGIBLE(<verdict>)}, a transfer status such as
     *               {@code NO_ROOM} (the ticket is kept: make room and call again), {@code NOT_PERMITTED}
     *               or {@code NO_STOCK}, {@code TIMEOUT}, {@code ABORTED}, {@code SERVER_BUSY} or
     *               {@code ERROR}
     */
    public record Result(Kind kind, int moved, String reason) {
        static Result refused(String reason) {
            return new Result(Kind.REFUSED, 0, reason);
        }
    }

    /** A request this bot asked or was covered for, kept between the ask and the take. */
    private record Ticket(RequestFingerprint fp, BlockPos chestPos, long openedAtMs, String purpose) {
    }

    /** One step's result, and whether it is worth an INFO line (a repeat or a pre-filter refusal is not). */
    private record Step(Result result, boolean quiet) {
        static Step loud(Result result) {
            return new Step(result, false);
        }

        static Step quiet(Result result) {
            return new Step(result, true);
        }
    }

    /** One ticket per bot. Touched only on the server thread; concurrent so the sweep and stop are safe too. */
    private static final ConcurrentHashMap<UUID, Ticket> TICKETS = new ConcurrentHashMap<>();
    private static final AtomicBoolean WARNED_WAIT_ON_SERVER_THREAD = new AtomicBoolean();
    private static final AtomicBoolean WARNED_ESTIMATE_OFF_THREAD = new AtomicBoolean();

    private SupplyWithdrawals() {
    }

    /**
     * Takes up to {@code qty} of {@code sample}'s exact item (id and components) from the chest at
     * {@code chestPos} into {@code bot}'s inventory, asking the owner first unless a standing
     * permission covers the chest. The policy's reserve and allowlist always hold; an item the
     * policy never allows is refused without asking.
     *
     * @param bot      the companion taking the items
     * @param chestPos either half of the chest
     * @param sample   a stack of the exact item wanted, usually read from the chest; only read
     * @param qty      how many the bot asks for
     * @param need     how many it actually needs (the grant never exceeds it)
     * @param purpose  a short label for the logs, e.g. {@code "woodcut-axe"}
     * @param mode     whether a worker may wait for the owner's answer inside this call
     * @param abort    polled while waiting; {@code true} stops the wait ({@code null}: never)
     */
    public static Result withdraw(ServerPlayerEntity bot, BlockPos chestPos, ItemStack sample, int qty, int need,
                                  String purpose, WaitMode mode, BooleanSupplier abort) {
        MinecraftServer srv = SupplyRequestService.server();
        boolean onServerThread = srv != null && srv.isOnThread();
        if (mode == WaitMode.UNTIL_ANSWERED && onServerThread
                && WARNED_WAIT_ON_SERVER_THREAD.compareAndSet(false, true)) {
            LOGGER.warn("[supply] withdraw asked to wait for an answer on the server thread (purpose={});"
                    + " not waiting there, this call and any like it run without waiting", purposeText(purpose));
        }
        WaitMode effective = SupplyWithdrawalPolicy.effectiveMode(mode, onServerThread);
        Supplier<Step> oneStep = () -> step(bot, chestPos, sample, qty, need, purpose);
        Step outcome;
        if (srv == null) {
            outcome = Step.loud(Result.refused("NOT_RUNNING"));
        } else if (onServerThread) {
            outcome = oneStep.get();
        } else {
            outcome = onServer(srv, oneStep);
            if (effective == WaitMode.UNTIL_ANSWERED && outcome.result().kind() == Kind.WAITING) {
                outcome = awaitAnswer(srv, bot, abort, oneStep);
            }
        }
        log(outcome, bot, chestPos, sample, qty, need, purpose, effective);
        return outcome.result();
    }

    /**
     * Roughly how many of {@code stack}'s exact item a companion could be granted from
     * {@code countInThisChest} of it in one chest half: 0 when the item fails the pre-filter under
     * the running ledger's own config, otherwise the count less the item's reserve, never below 0
     * ({@link SupplyWithdrawalPolicy#grantableEstimate}). For counting what automatic work may rely
     * on before asking. Advisory: no ledger state (permissions, cooldowns, pending prompts) and no
     * owner is consulted, and nothing is asked, logged or moved.
     *
     * <p>Server thread only (the item's components are read through the world's registries); on
     * any other thread it returns 0 and logs one WARN per run of the game. Also 0 when the service
     * is not running or an argument is missing.
     */
    public static int grantableEstimate(ServerWorld world, ItemStack stack, int countInThisChest) {
        MinecraftServer srv = world == null ? null : world.getServer();
        if (srv == null) {
            return 0;
        }
        if (!srv.isOnThread()) {
            if (WARNED_ESTIMATE_OFF_THREAD.compareAndSet(false, true)) {
                LOGGER.warn("[supply] grantableEstimate called off the server thread ({}); counting 0 there",
                        Thread.currentThread().getName());
            }
            return 0;
        }
        SupplyRequestPolicy.Config config = SupplyRequestService.config();
        if (config == null || stack == null || stack.isEmpty() || countInThisChest <= 0) {
            return 0;
        }
        return SupplyWithdrawalPolicy.grantableEstimate(SupplyRequestService.itemKeyOf(world, stack), config,
                countInThisChest);
    }

    // ── One step (server thread) ─────────────────────────────────────────────────────────────

    private static Step step(ServerPlayerEntity bot, BlockPos chestPos, ItemStack sample, int qty, int need,
                             String purpose) {
        SupplyRequestPolicy.Config config = SupplyRequestService.config();
        if (!SupplyRequestService.isRunning() || config == null) {
            return Step.loud(Result.refused("NOT_RUNNING"));
        }
        if (bot == null || bot.isRemoved()) {
            return Step.loud(Result.refused("BOT_GONE"));
        }
        if (chestPos == null || sample == null || sample.isEmpty() || qty <= 0 || need <= 0) {
            return Step.loud(Result.refused("INVALID"));
        }
        ServerWorld world = bot.getEntityWorld();
        ItemKey item = SupplyRequestService.itemKeyOf(world, sample);
        // The same config the ledger decides with: an item it would refuse is never asked about.
        Verdict verdict = SupplyRequestPolicy.classify(item, config);
        if (!SupplyWithdrawalPolicy.passesPreFilter(verdict)) {
            return Step.quiet(Result.refused("INELIGIBLE(" + verdict + ")"));
        }

        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        Ticket ticket = TICKETS.get(botId);
        if (ticket != null && !SupplyWithdrawalPolicy.isTicketLive(ticket.openedAtMs(), now,
                SupplyRequestService.timings())) {
            TICKETS.remove(botId, ticket);
            ticket = null;
        }
        boolean pending = SupplyRequestService.isPending(botId);
        boolean matches = ticket != null && SupplyWithdrawalPolicy.ticketMatches(ticket.fp(),
                SupplyRequestService.chestKeyAt(world, chestPos), item);
        boolean permitted = ticket != null && SupplyRequestService.isPermitted(ticket.fp());
        switch (SupplyWithdrawalPolicy.ticketStep(ticket != null, matches, pending, permitted)) {
            case WAIT:
                return Step.quiet(new Result(Kind.WAITING, 0, "PENDING"));
            case REDEEM:
                return redeem(bot, world, chestPos, ticket, need);
            case DROP_NOT_PERMITTED:
                // Refused, expired or lapsed: say so before any reach check, so nobody walks for nothing.
                TICKETS.remove(botId, ticket);
                return Step.loud(Result.refused("NOT_PERMITTED"));
            case OTHER_PENDING:
                return Step.quiet(Result.refused("OTHER_REQUEST_PENDING"));
            case DROP_AND_REQUEST:
                // Its grant, if the owner gave one, simply expires unspent.
                TICKETS.remove(botId, ticket);
                LOGGER.debug("[supply] withdraw bot={} dropped its ticket for {} at {} (purpose={})",
                        bot.getName().getString(), ticket.fp().itemId(), ticket.chestPos().toShortString(),
                        purposeText(ticket.purpose()));
                break;
            case REQUEST:
                break;
        }

        RequestOutcome asked = SupplyRequestService.request(bot, chestPos, sample, qty, need);
        RequestFingerprint fp = asked.fingerprint();
        switch (SupplyWithdrawalPolicy.onRequest(asked.status())) {
            case TAKE:
                if (fp == null) {
                    return Step.loud(Result.refused(asked.logText()));
                }
                Ticket covered = new Ticket(fp, chestPos.toImmutable(), now, purpose);
                TICKETS.put(botId, covered);
                return redeem(bot, world, chestPos, covered, need);
            case WAIT:
                if (fp == null) {
                    return Step.loud(Result.refused(asked.logText()));
                }
                TICKETS.put(botId, new Ticket(fp, chestPos.toImmutable(), now, purpose));
                return Step.loud(new Result(Kind.WAITING, 0, "ASKED"));
            case REFUSE:
            default:
                return Step.loud(Result.refused(asked.logText()));
        }
    }

    /**
     * Takes what {@code ticket} permits if the bot can reach the chest; otherwise READY. Reached
     * only for a permitted ticket (a matching one the ledger still covers, or one just covered by a
     * standing permission), so READY always means "permitted, walk there".
     */
    private static Step redeem(ServerPlayerEntity bot, ServerWorld world, BlockPos chestPos, Ticket ticket, int need) {
        if (!BlockInteractionService.canInteract(bot, chestPos)) {
            return Step.loud(new Result(Kind.READY, 0, "OUT_OF_REACH"));
        }
        TransferOutcome transfer = SupplyRequestService.transferNow(bot, chestPos, ticket.fp(), need);
        TransferAction action = SupplyWithdrawalPolicy.onTransfer(transfer.status(), transfer.moved());
        if (!action.keepTicket()) {
            TICKETS.remove(bot.getUuid(), ticket);
        }
        if (transfer.moved() > 0) {
            refreshRegistrySnapshot(bot, world, chestPos);
        }
        int moved = action.kind() == Kind.MOVED ? transfer.moved() : 0;
        return Step.loud(new Result(action.kind(), moved, action.reason()));
    }

    /**
     * After items left a chest one of this bot's owner's bots placed, refresh its registry
     * snapshot, as {@code ChestStoreService}'s transfer did after every withdrawal. Only for a
     * recorded chest: {@code updateContentsSnapshot} rewrites the registry file even when no
     * record matches.
     */
    private static void refreshRegistrySnapshot(ServerPlayerEntity bot, ServerWorld world, BlockPos chestPos) {
        boolean recorded = false;
        for (BotChestRegistryService.ChestRecord record : BotChestRegistryService.listChestsForOwner(bot, world)) {
            if (record.x == chestPos.getX() && record.y == chestPos.getY() && record.z == chestPos.getZ()) {
                recorded = true;
                break;
            }
        }
        if (!recorded) {
            return;
        }
        BlockState state = world.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return;
        }
        Inventory storage = ChestBlock.getInventory(chestBlock, state, world, chestPos, true);
        if (storage != null) {
            BotChestRegistryService.updateContentsSnapshot(bot, chestPos, world, storage);
        }
    }

    // ── Worker side ──────────────────────────────────────────────────────────────────────────

    /**
     * Runs {@code body} on the server thread and waits a bounded time for it. Whichever side
     * claims the step first owns it: the server task claims it when it starts, and a worker whose
     * wait ran out claims it to abandon it, so a late task finds it claimed and does nothing. A
     * task that already started is waited for, because its items may already have moved.
     */
    private static Step onServer(MinecraftServer srv, Supplier<Step> body) {
        CompletableFuture<Step> done = new CompletableFuture<>();
        AtomicBoolean claimed = new AtomicBoolean();
        try {
            srv.execute(() -> {
                if (!claimed.compareAndSet(false, true)) {
                    return; // abandoned by the worker before it started
                }
                if (!srv.isOnThread()) {
                    // A stopped server runs execute() inline on the caller: never touch the world off-thread.
                    done.complete(Step.loud(Result.refused("NOT_RUNNING")));
                    return;
                }
                try {
                    done.complete(body.get());
                } catch (RuntimeException e) {
                    LOGGER.warn("[supply] withdraw step failed on the server thread", e);
                } finally {
                    done.complete(Step.loud(Result.refused("ERROR"))); // no-op once completed
                }
            });
        } catch (RuntimeException rejected) {
            return Step.loud(Result.refused("NOT_RUNNING"));
        }
        try {
            return done.get(HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException e) {
            boolean interrupted = e instanceof InterruptedException;
            if (claimed.compareAndSet(false, true)) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Step.loud(Result.refused(interrupted ? "ABORTED" : "SERVER_BUSY"));
            }
            return awaitStarted(done, interrupted);
        } catch (ExecutionException e) {
            return Step.loud(Result.refused("ERROR"));
        }
    }

    /** Waits for a step that has already started on the server thread; keeps any interrupt for later. */
    private static Step awaitStarted(CompletableFuture<Step> done, boolean interrupted) {
        boolean restore = interrupted;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HOP_TIMEOUT_MS);
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0L) {
                    LOGGER.warn("[supply] withdraw step still running on the server thread after {} ms more;"
                            + " its result is lost to this caller", HOP_TIMEOUT_MS);
                    return Step.loud(Result.refused("SERVER_BUSY"));
                }
                try {
                    return done.get(left, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    restore = true;
                } catch (TimeoutException e) {
                    // loop: the deadline check reports it
                } catch (ExecutionException e) {
                    return Step.loud(Result.refused("ERROR"));
                }
            }
        } finally {
            if (restore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Polls the ledger until the bot's prompt is no longer pending, then takes one more step on the
     * server thread. Stops early on the caller's abort, a thread interrupt, the bot's removal or the
     * service stopping; stops after the prompt's lifetime plus slack. Either stop keeps the ticket.
     */
    private static Step awaitAnswer(MinecraftServer srv, ServerPlayerEntity bot, BooleanSupplier abort,
                                    Supplier<Step> oneStep) {
        Timings timings = SupplyRequestService.timings();
        UUID botId = bot.getUuid();
        long startedAt = System.currentTimeMillis();
        while (true) {
            boolean aborted = (abort != null && abort.getAsBoolean())
                    || Thread.currentThread().isInterrupted()
                    || bot.isRemoved()
                    || !SupplyRequestService.isRunning()
                    || TaskService.isServerStopping();
            boolean pending = !aborted && SupplyRequestService.isPending(botId);
            switch (SupplyWithdrawalPolicy.keepWaiting(aborted, pending, startedAt, System.currentTimeMillis(),
                    timings)) {
                case SETTLED:
                    return onServer(srv, oneStep);
                case TIMED_OUT:
                    return Step.loud(Result.refused("TIMEOUT"));
                case ABORTED:
                    return Step.loud(Result.refused("ABORTED"));
                case KEEP_WAITING:
                default:
                    break;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Step.loud(Result.refused("ABORTED"));
            }
        }
    }

    // ── Lifecycle (called by SupplyRequestService) ───────────────────────────────────────────

    /** Drops every ticket too old to redeem anything. Called from the service's once-a-second tick. */
    static void sweepTickets(long nowMs, Timings timings) {
        TICKETS.values().removeIf(t -> !SupplyWithdrawalPolicy.isTicketLive(t.openedAtMs(), nowMs, timings));
    }

    /** Forgets every ticket. Called at SERVER_STOPPED. */
    static void clearTickets() {
        TICKETS.clear();
    }

    // ── Logging ──────────────────────────────────────────────────────────────────────────────

    /** One line per call: INFO, or DEBUG for a pre-filter refusal or a repeat of a known wait. */
    private static void log(Step outcome, ServerPlayerEntity bot, BlockPos chestPos, ItemStack sample, int qty,
                            int need, String purpose, WaitMode mode) {
        if (outcome.quiet() ? !LOGGER.isDebugEnabled() : !LOGGER.isInfoEnabled()) {
            return;
        }
        Result r = outcome.result();
        String line = "[supply] withdraw bot={} chest={} item={} qty={} need={} purpose={} mode={} result={} moved={} reason={}";
        Object[] args = {bot == null ? "-" : bot.getName().getString(),
                chestPos == null ? "-" : chestPos.getX() + "," + chestPos.getY() + "," + chestPos.getZ(),
                sample == null || sample.isEmpty() ? "-" : Registries.ITEM.getId(sample.getItem()).toString(),
                qty, need, purposeText(purpose), mode, r.kind(), r.moved(), r.reason()};
        if (outcome.quiet()) {
            LOGGER.debug(line, args);
        } else {
            LOGGER.info(line, args);
        }
    }

    private static String purposeText(String purpose) {
        return purpose == null || purpose.isBlank() ? "-" : purpose;
    }
}
