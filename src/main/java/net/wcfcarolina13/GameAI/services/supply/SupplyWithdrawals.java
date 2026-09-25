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
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestOutcome;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferOutcome;
import net.wcfcarolina13.GameAI.services.supply.SupplyServerHop.Hop;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.OwnerAwayMemo;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Refusal;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Ticket;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketBook;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TransferAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The one way a companion takes items out of a chest on its own. Every automatic withdrawal —
 * a tool, seeds, food, crafting materials, idle equipment — calls {@link #withdraw}, and this is
 * the only class that calls {@link SupplyRequestService#request} and
 * {@link SupplyRequestService#transferNow} ({@code SupplyEntryPointTest} keeps it that way). What
 * the owner does themselves — {@code /bot withdraw}, Quick Fetch — is the owner's own act and does
 * not come through here.
 *
 * <p><b>Ask, walk, take.</b> Asking and taking are separate: the owner answers a prompt in chat,
 * and the bot may have to walk to the chest. Between calls the facade keeps <i>tickets</i>: a
 * request's fingerprint, the only key the owner's grant can be spent with (a fresh request would
 * hit the bot's prompt cooldown instead). A caller that must walk calls once with
 * {@link WaitMode#NONE} before walking — {@link Kind#REFUSED} means don't walk — walks, then calls
 * again with the same chest and item to take. A bot keeps one ticket per chest and exact item, so
 * asking about anything else never costs it a grant the owner already gave. A ticket lives for
 * the prompt's lifetime plus the grant's and is swept once a second. A ticket whose prompt was
 * refused or expired, or whose grant lapsed, is dropped with {@code NOT_PERMITTED} before any
 * reach check, so {@link Kind#READY} always means permitted.
 *
 * <p><b>Refusals carry a scope</b> ({@link Result#scope()}, {@link Scope}): how far the answer
 * reaches — this item, this chest, everything the bot asks for now, the owner being away, or
 * nothing but the moment. Callers decide from it; the reason string is for the logs.
 *
 * <p><b>Quiet refusals.</b> A bot with no owner is refused ({@code NO_OWNER}) without asking the
 * ledger. After the owner is found away from a chest, the bot does not ask about that chest again
 * for {@link SupplyWithdrawalPolicy#OWNER_AWAY_MEMO_MS} unless a standing permission covers it.
 * Neither logs at INFO.
 *
 * <p>{@link #grantableEstimate} lets a caller count, before asking, how much of a chest's stock
 * the policy could ever grant (allowlist and reserve only; advisory).
 *
 * <p><b>Threading.</b> Any thread. On the server thread a call runs one non-blocking step, and
 * {@link WaitMode#UNTIL_ANSWERED} is treated as {@link WaitMode#NONE} (with one WARN per run of
 * the game). Off it, each step runs on the server thread through {@link SupplyServerHop} (about
 * 2.5 s); a step the worker gave up on before it started does nothing when it finally runs. With
 * {@link WaitMode#UNTIL_ANSWERED} a worker that got {@link Kind#WAITING} polls the ledger every
 * 500 ms — without touching the world — until the prompt is answered or expires, then takes one
 * more step. Once the server is stopping, every step refuses.
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
        /** Nothing will be taken now; {@link Result#scope()} says how far that reaches. */
        REFUSED
    }

    /**
     * @param kind   what happened
     * @param moved  items moved into the bot's inventory (0 unless {@link Kind#MOVED})
     * @param reason a short why, for the logs: a {@link Refusal} name, {@code INELIGIBLE(<verdict>)}
     *               from the pre-filter, a request status such as {@code PROMPT_COOLDOWN} or
     *               {@code DENIED(<access>)}, a transfer status such as {@code NO_ROOM} (the ticket
     *               is kept: make room and call again) or {@code INELIGIBLE_NOW}; {@code ASKED} or
     *               {@code PENDING} while waiting, {@code OUT_OF_REACH} when ready
     * @param scope  how far a refusal reaches; {@link Scope#NONE} exactly when {@code kind} is not
     *               {@link Kind#REFUSED} (a refusal given no scope reads {@link Scope#BOT}; see
     *               {@link SupplyWithdrawalPolicy#resultScope})
     */
    public record Result(Kind kind, int moved, String reason, Scope scope) {
        public Result {
            scope = SupplyWithdrawalPolicy.resultScope(kind, scope);
        }

        /** A refusal with the given reason and scope. */
        public static Result refused(String reason, Scope scope) {
            return new Result(Kind.REFUSED, 0, reason, scope);
        }

        /** One of the facade's own refusals: its name and its scope. */
        public static Result refused(Refusal refusal) {
            return refused(refusal.name(), refusal.scope());
        }
    }

    /** One step's result, and whether it is worth an INFO line (a repeat or a quiet refusal is not). */
    private record Step(Result result, boolean quiet) {
        static Step loud(Result result) {
            return new Step(result, false);
        }

        static Step quiet(Result result) {
            return new Step(result, true);
        }
    }

    /** Every bot's tickets, one per chest and exact item. Touched on the server thread; thread-safe for the sweep and stop. */
    private static final TicketBook TICKETS = new TicketBook();
    /** (bot, chest) pairs that recently found the owner away. */
    private static final OwnerAwayMemo OWNER_AWAY = new OwnerAwayMemo();
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
        Supplier<Step> oneStep = () -> step(bot, chestPos, sample, qty, need);
        Step outcome;
        if (srv == null) {
            outcome = Step.loud(Result.refused(Refusal.NOT_RUNNING));
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

    private static Step step(ServerPlayerEntity bot, BlockPos chestPos, ItemStack sample, int qty, int need) {
        SupplyRequestPolicy.Config config = SupplyRequestService.config();
        // A hop queued before the stop can still run in shutdown()'s task pump, after the bots were saved.
        if (TaskService.isServerStopping() || !SupplyRequestService.isRunning() || config == null) {
            return Step.loud(Result.refused(Refusal.NOT_RUNNING));
        }
        if (bot == null || bot.isRemoved()) {
            return Step.loud(Result.refused(Refusal.BOT_GONE));
        }
        if (chestPos == null || sample == null || sample.isEmpty() || qty <= 0 || need <= 0) {
            return Step.loud(Result.refused(Refusal.INVALID));
        }
        // Nobody may approve anything for an owner-less bot: said here, quietly, so a retry costs no request.
        UUID owner = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        if (owner == null) {
            return Step.quiet(Result.refused(Refusal.NO_OWNER));
        }
        ServerWorld world = bot.getEntityWorld();
        ItemKey item = SupplyRequestService.itemKeyOf(world, sample);
        // The same config the ledger decides with: an item it would refuse is never asked about.
        Verdict verdict = SupplyRequestPolicy.classify(item, config);
        Scope preFilter = SupplyWithdrawalPolicy.preFilterScope(verdict);
        if (preFilter != Scope.NONE) {
            return Step.quiet(Result.refused("INELIGIBLE(" + verdict + ")", preFilter));
        }
        ChestKey chestKey = SupplyRequestService.chestKeyAt(world, chestPos);
        if (chestKey == null) {
            // Nothing to match a ticket against, so nothing is dropped either; ask again later.
            return Step.loud(Result.refused(Refusal.CHEST_UNREADABLE));
        }

        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        Ticket ticket = TICKETS.find(botId, TicketKey.of(chestKey, item), now, SupplyRequestService.timings());
        boolean pending = SupplyRequestService.isPending(botId);
        boolean permitted = ticket != null && SupplyRequestService.isPermitted(ticket.fp());
        switch (SupplyWithdrawalPolicy.ticketStep(ticket != null, pending, permitted)) {
            case REDEEM:
                return redeem(bot, world, chestPos, ticket, need);
            case WAIT:
                return Step.quiet(new Result(Kind.WAITING, 0, "PENDING", Scope.NONE));
            case DROP_NOT_PERMITTED:
                // Refused, expired or lapsed: say so before any reach check, so nobody walks for nothing.
                TICKETS.drop(ticket);
                return Step.loud(Result.refused(Refusal.NOT_PERMITTED));
            case OTHER_PENDING:
                return Step.quiet(Result.refused(Refusal.OTHER_REQUEST_PENDING));
            case REQUEST:
            default:
                break;
        }

        // The owner was just found away from this chest; a standing permission needs nobody nearby.
        if (OWNER_AWAY.isAway(botId, chestKey, now) && !SupplyRequestService.hasAlways(owner, chestKey)) {
            return Step.quiet(Result.refused(Refusal.OWNER_NOT_NEARBY));
        }
        RequestOutcome asked = SupplyRequestService.request(bot, chestPos, sample, qty, need);
        RequestFingerprint fp = asked.fingerprint();
        switch (SupplyWithdrawalPolicy.onRequest(asked.status())) {
            case TAKE:
                if (fp == null) {
                    return Step.loud(refusedBy(asked));
                }
                return redeem(bot, world, chestPos, TICKETS.record(fp, now), need);
            case WAIT:
                if (fp == null) {
                    return Step.loud(refusedBy(asked));
                }
                TICKETS.record(fp, now);
                return Step.loud(new Result(Kind.WAITING, 0, "ASKED", Scope.NONE));
            case REFUSE:
            default:
                if (asked.status() == RequestStatus.OWNER_NOT_NEARBY) {
                    OWNER_AWAY.noteAway(botId, chestKey, now);
                }
                return Step.loud(refusedBy(asked));
        }
    }

    private static Result refusedBy(RequestOutcome asked) {
        return Result.refused(asked.logText(),
                SupplyWithdrawalPolicy.requestScope(asked.status(), asked.access(), asked.verdict()));
    }

    /**
     * Takes what {@code ticket} permits if the bot can reach the chest; otherwise READY. Reached
     * only for a permitted ticket (a matching one the ledger still covers, or one just covered by a
     * standing permission), so READY always means "permitted, walk there".
     */
    private static Step redeem(ServerPlayerEntity bot, ServerWorld world, BlockPos chestPos, Ticket ticket, int need) {
        if (!BlockInteractionService.canInteract(bot, chestPos)) {
            return Step.loud(new Result(Kind.READY, 0, "OUT_OF_REACH", Scope.NONE));
        }
        TransferOutcome transfer = SupplyRequestService.transferNow(bot, chestPos, ticket.fp(), need);
        TransferAction action = SupplyWithdrawalPolicy.onTransfer(transfer.status(), transfer.moved());
        if (!action.keepTicket()) {
            TICKETS.drop(ticket);
        }
        if (transfer.moved() > 0) {
            refreshRegistrySnapshot(bot, world, chestPos);
        }
        int moved = action.kind() == Kind.MOVED ? transfer.moved() : 0;
        return Step.loud(new Result(action.kind(), moved, action.reason(), action.scope()));
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
     * Runs one step on the server thread through {@link SupplyServerHop}: inline there, otherwise
     * a bounded, abandon-safe hop (a step the worker gave up on before it started does nothing; one
     * that started is waited for, since its items may already have moved).
     */
    private static Step onServer(MinecraftServer srv, Supplier<Step> body) {
        Hop<Step> hop = SupplyServerHop.hop(srv, body, HOP_TIMEOUT_MS);
        return switch (hop.status()) {
            case DONE -> hop.value();
            case NOT_RUNNING -> Step.loud(Result.refused(Refusal.NOT_RUNNING));
            case TIMED_OUT, UNFINISHED -> Step.loud(Result.refused(Refusal.SERVER_BUSY));
            case INTERRUPTED -> Step.loud(Result.refused(Refusal.ABORTED));
            case FAILED -> Step.loud(Result.refused(Refusal.ERROR));
        };
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
                    return Step.loud(Result.refused(Refusal.TIMEOUT));
                case ABORTED:
                    return Step.loud(Result.refused(Refusal.ABORTED));
                case KEEP_WAITING:
                default:
                    break;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Step.loud(Result.refused(Refusal.ABORTED));
            }
        }
    }

    // ── Lifecycle (called by SupplyRequestService) ───────────────────────────────────────────

    /**
     * Drops every ticket too old to redeem anything and every lapsed owner-away note. Called from
     * the service's once-a-second tick.
     */
    static void sweepTickets(long nowMs, Timings timings) {
        TICKETS.sweep(nowMs, timings);
        OWNER_AWAY.sweep(nowMs);
    }

    /** Forgets every ticket and owner-away note. Called at SERVER_STOPPED. */
    static void clearTickets() {
        TICKETS.clear();
        OWNER_AWAY.clear();
    }

    // ── Logging ──────────────────────────────────────────────────────────────────────────────

    /** One line per call: INFO, or DEBUG for a quiet refusal or a repeat of a known wait. */
    private static void log(Step outcome, ServerPlayerEntity bot, BlockPos chestPos, ItemStack sample, int qty,
                            int need, String purpose, WaitMode mode) {
        if (outcome.quiet() ? !LOGGER.isDebugEnabled() : !LOGGER.isInfoEnabled()) {
            return;
        }
        Result r = outcome.result();
        String line = "[supply] withdraw bot={} chest={} item={} qty={} need={} purpose={} mode={} result={} moved={}"
                + " reason={} scope={}";
        Object[] args = {bot == null ? "-" : bot.getName().getString(),
                chestPos == null ? "-" : chestPos.getX() + "," + chestPos.getY() + "," + chestPos.getZ(),
                sample == null || sample.isEmpty() ? "-" : Registries.ITEM.getId(sample.getItem()).toString(),
                qty, need, purposeText(purpose), mode, r.kind(), r.moved(), r.reason(), r.scope()};
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
