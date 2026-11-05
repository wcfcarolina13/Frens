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
import net.wcfcarolina13.GameAI.services.supply.SupplyBundlePromptPolicy.Shown;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.LineOutcome;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.LineStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestLedger.Timings;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.BundleRequestOutcome;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestOutcome;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.RequestStatus;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestService.TransferOutcome;
import net.wcfcarolina13.GameAI.services.supply.SupplyServerHop.Hop;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.BundleWhy;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.OwnerAwayMemo;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Refusal;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Scope;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.Ticket;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketBook;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TicketStep;
import net.wcfcarolina13.GameAI.services.supply.SupplyWithdrawalPolicy.TransferAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
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
 * reach check, so {@link Kind#READY} always means permitted. A take the policy refuses at the
 * chest ({@code INELIGIBLE_NOW}) drops its ticket too, so it is not walked for again. A request
 * the owner's unspent "Allow once" for that chest and item still covers (its ticket was dropped)
 * is answered from the grant without a second prompt ({@code COVERED_BY_GRANT}), after the same
 * access and policy checks as any request.
 *
 * <p><b>Refusals carry a scope</b> ({@link Result#scope()}, {@link Scope}): how far the answer
 * reaches — this item anywhere, this chest, this item in this chest, the owner being away, the
 * owner's decision for everything the bot asks now, a busy moment, or no room in the bot. Callers
 * decide from it; the reason string is for the logs.
 *
 * <p><b>Quiet refusals.</b> A bot with no owner is refused ({@code NO_OWNER}) without asking the
 * ledger. After a request finds the owner away from the bot, the bot asks about no chest a
 * standing permission does not cover for {@link SupplyWithdrawalPolicy#OWNER_AWAY_MEMO_MS}, or
 * until the owner is back in prompt range, whichever comes first. Neither logs at INFO.
 *
 * <p><b>One combined ask</b> ({@link #withdrawBundle}, Bradley's ruling of 2026-10-23). A craft that
 * lacks several things asks one chest for all of them in one prompt
 * ({@link SupplyRequestService#requestBundle}); the owner's one answer grants or refuses the set.
 * Each line then has its own ticket and is taken through the same {@link #redeem} as a single
 * withdrawal, so a line already covered by a live grant is taken without a prompt and a line the
 * owner refused recently is left out. The result carries one kind for the set
 * ({@link SupplyWithdrawalPolicy#aggregateKinds}), a {@link BundleWhy} for the caller's next move,
 * and every line's own result.
 *
 * <p>{@link #grantableEstimate} lets a caller count, before asking, how much of a chest's stock
 * the policy could ever grant (allowlist and reserve only; advisory). {@link #grantablePreview}
 * adds what this bot's request at this chest would be refused for before the owner could answer
 * (no owner, no chest key, owner out of prompt range with no standing permission); still advisory.
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
     *               is kept: make room and call again) or {@code INELIGIBLE_NOW} (the ticket is
     *               dropped); {@code ASKED} or {@code PENDING} while waiting, {@code OUT_OF_REACH}
     *               when ready
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

    /**
     * One line of a bundled ask: a stack of the exact item wanted (only read), how many the bot
     * asks for and how many it needs.
     */
    public record BundleAsk(ItemStack sample, int qty, int need) {
    }

    /**
     * What {@link #withdrawBundle} achieved for the set.
     *
     * @param kind   one kind for the set ({@link SupplyWithdrawalPolicy#aggregateKinds})
     * @param moved  items moved into the bot's inventory over every line
     * @param reason the reason of the first line that decided {@code kind}
     * @param scope  that line's scope ({@link Scope#NONE} unless refused)
     * @param why    for a refusal, whether the owner declined, nobody answered, or something else
     * @param lines  every line's own result, in input order
     */
    public record BundleResult(Kind kind, int moved, String reason, Scope scope, BundleWhy why, List<Result> lines) {
        public BundleResult {
            scope = SupplyWithdrawalPolicy.resultScope(kind, scope);
            why = why == null ? BundleWhy.NONE : why;
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** Every line refused the same way. */
        static BundleResult refusedAll(Result refusal, BundleWhy why, int count) {
            List<Result> lines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                lines.add(refusal);
            }
            return new BundleResult(refusal.kind(), 0, refusal.reason(), refusal.scope(), why, lines);
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
    /** Bots whose request recently found their owner away. */
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
     * @param abort    checked before each server step and while waiting; true stops ({@code null}: never)
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
        Supplier<Step> oneStep = () -> abort != null && abort.getAsBoolean()
                ? Step.loud(Result.refused(Refusal.ABORTED))
                : step(bot, chestPos, sample, qty, need);
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
     * Takes every line of {@code lines} from the chest at {@code chestPos} into {@code bot}'s
     * inventory after ONE prompt to the owner (see the class comment). Lines a live grant already
     * covers are taken without asking; a line the policy refuses, or the owner refused here
     * recently, is left out and reported in its own {@link Result}. Threading, waiting and the
     * abort check are exactly as for {@link #withdraw}: with {@link WaitMode#UNTIL_ANSWERED} off
     * the server thread the worker polls the ledger until the one prompt settles, then takes every
     * granted line in one more server step.
     *
     * @param purpose      what the bot is making, for the prompt ("a wooden pickaxe") and the logs
     * @param stillMissing what this chest cannot cover, named in the same prompt; may be empty
     */
    public static BundleResult withdrawBundle(ServerPlayerEntity bot, BlockPos chestPos, List<BundleAsk> lines,
                                              String purpose, List<Shown> stillMissing, WaitMode mode,
                                              BooleanSupplier abort) {
        int count = lines == null ? 0 : lines.size();
        MinecraftServer srv = SupplyRequestService.server();
        boolean onServerThread = srv != null && srv.isOnThread();
        if (mode == WaitMode.UNTIL_ANSWERED && onServerThread
                && WARNED_WAIT_ON_SERVER_THREAD.compareAndSet(false, true)) {
            LOGGER.warn("[supply] withdraw asked to wait for an answer on the server thread (purpose={});"
                    + " not waiting there, this call and any like it run without waiting", purposeText(purpose));
        }
        WaitMode effective = SupplyWithdrawalPolicy.effectiveMode(mode, onServerThread);
        Supplier<BundleResult> oneStep = () -> abort != null && abort.getAsBoolean()
                ? BundleResult.refusedAll(Result.refused(Refusal.ABORTED), BundleWhy.OTHER, count)
                : bundleStep(bot, chestPos, lines, purpose, stillMissing);
        BundleResult outcome;
        if (srv == null || count == 0) {
            outcome = BundleResult.refusedAll(Result.refused(count == 0 ? Refusal.INVALID : Refusal.NOT_RUNNING),
                    BundleWhy.OTHER, count);
        } else {
            Function<Refusal, BundleResult> refused = r -> BundleResult.refusedAll(Result.refused(r),
                    r == Refusal.TIMEOUT ? BundleWhy.NO_ANSWER : BundleWhy.OTHER, count);
            outcome = onServer(srv, oneStep, refused);
            if (effective == WaitMode.UNTIL_ANSWERED && outcome.kind() == Kind.WAITING) {
                outcome = awaitAnswer(srv, bot, abort, () -> onServer(srv, oneStep, refused), refused);
            }
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("[supply] withdraw-bundle bot={} chest={} lines={} purpose={} mode={} result={} moved={}"
                            + " reason={} scope={} why={}",
                    bot == null ? "-" : bot.getName().getString(),
                    chestPos == null ? "-" : chestPos.getX() + "," + chestPos.getY() + "," + chestPos.getZ(),
                    bundleText(lines), purposeText(purpose), effective, outcome.kind(), outcome.moved(),
                    outcome.reason(), outcome.scope(), outcome.why());
        }
        return outcome;
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

    /**
     * {@link #grantableEstimate} for {@code bot} at the chest at {@code chestPos}, or 0 when a
     * request from this bot for this chest would be refused before the owner could answer: the
     * bot has no owner, the item fails the pre-filter, the chest has no readable key, the chest's
     * access check (the one a request makes: foreign, locked, territory, zones, blocked) denies it, or neither a
     * standing permission covers the chest nor is the owner in prompt range (which covers a bot
     * noted away; {@link SupplyWithdrawalPolicy#previewAllows}). Advisory, like the estimate:
     * nothing is asked, logged or moved, no ticket, grant or owner-away note is read or changed,
     * so an unspent grant can still make a take possible where this says 0, and the owner can
     * still say no where it does not.
     *
     * <p>Server thread only; 0 on any other thread (one WARN per run, shared with the estimate),
     * when the service is not running, or when an argument is missing.
     */
    public static int grantablePreview(ServerPlayerEntity bot, BlockPos chestPos, ItemStack sample,
                                       int countInThisChest) {
        if (bot == null || bot.isRemoved() || chestPos == null || !SupplyRequestService.isRunning()) {
            return 0;
        }
        ServerWorld world = bot.getEntityWorld();
        int estimate = grantableEstimate(world, sample, countInThisChest);
        if (estimate <= 0) {
            return 0;
        }
        SupplyRequestPolicy.Config config = SupplyRequestService.config();
        if (config == null) {
            return 0;
        }
        UUID owner = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        Verdict verdict = SupplyRequestPolicy.classify(SupplyRequestService.itemKeyOf(world, sample), config);
        ChestKey chestKey = owner == null ? null : SupplyRequestService.chestKeyAt(world, chestPos);
        // The same access read a request makes: a foreign, locked or territory-barred chest never counts.
        SupplyChestRules.Access access = chestKey == null ? null : SupplyRequestService.accessAt(bot, chestPos, owner);
        boolean always = chestKey != null && SupplyRequestService.hasAlways(owner, chestKey);
        boolean inRange = chestKey != null && !always && SupplyRequestService.isOwnerInPromptRange(bot, owner);
        return SupplyWithdrawalPolicy.previewAllows(owner != null, verdict, chestKey != null, access, always, inRange)
                ? estimate : 0;
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
            // Nothing to match a ticket against, so nothing is dropped either; the caller skips this chest.
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

        // Any live grant for this exact target must reach the request's stock/need normalization.
        // The one-item probe only bypasses the memo; the request still checks the full approved quantity.
        boolean notedAway = OWNER_AWAY.isAway(botId, now);
        switch (SupplyWithdrawalPolicy.ownerAwayStep(notedAway,
                notedAway && SupplyRequestService.isPermitted(new RequestFingerprint(owner, botId, chestKey, item, 1)),
                notedAway && SupplyRequestService.isOwnerInPromptRange(bot, owner))) {
            case REFUSE_QUIETLY:
                return Step.quiet(Result.refused(Refusal.OWNER_NOT_NEARBY));
            case FORGET_AND_ASK:
                OWNER_AWAY.forget(botId);
                break;
            case ASK:
            default:
                break;
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
                    OWNER_AWAY.noteAway(botId, now);
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
     * One server-thread step of a bundle: per line, the same ticket logic as {@link #step}
     * (redeem a covered ticket, wait on a pending one, drop a lapsed one); then ONE
     * {@link SupplyRequestService#requestBundle} for every line still without a ticket, whose
     * per-line outcomes become tickets (asked: wait; covered: take now) or refusals.
     */
    private static BundleResult bundleStep(ServerPlayerEntity bot, BlockPos chestPos, List<BundleAsk> lines,
                                           String purpose, List<Shown> stillMissing) {
        int count = lines.size();
        SupplyRequestPolicy.Config config = SupplyRequestService.config();
        if (TaskService.isServerStopping() || !SupplyRequestService.isRunning() || config == null) {
            return BundleResult.refusedAll(Result.refused(Refusal.NOT_RUNNING), BundleWhy.OTHER, count);
        }
        if (bot == null || bot.isRemoved()) {
            return BundleResult.refusedAll(Result.refused(Refusal.BOT_GONE), BundleWhy.OTHER, count);
        }
        if (chestPos == null) {
            return BundleResult.refusedAll(Result.refused(Refusal.INVALID), BundleWhy.OTHER, count);
        }
        UUID owner = CompanionCommunicationPolicy.resolveOwnerUuid(bot);
        if (owner == null) {
            return BundleResult.refusedAll(Result.refused(Refusal.NO_OWNER), BundleWhy.OTHER, count);
        }
        ServerWorld world = bot.getEntityWorld();
        ChestKey chestKey = SupplyRequestService.chestKeyAt(world, chestPos);
        if (chestKey == null) {
            return BundleResult.refusedAll(Result.refused(Refusal.CHEST_UNREADABLE), BundleWhy.OTHER, count);
        }
        UUID botId = bot.getUuid();
        long now = System.currentTimeMillis();
        boolean pending = SupplyRequestService.isPending(botId);
        Result[] results = new Result[count];
        BundleWhy[] whys = new BundleWhy[count];
        List<BundleAsk> toRequest = new ArrayList<>();
        List<Integer> toRequestAt = new ArrayList<>();
        List<ItemKey> toRequestItems = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BundleAsk ask = lines.get(i);
            whys[i] = BundleWhy.OTHER;
            if (ask == null || ask.sample() == null || ask.sample().isEmpty() || ask.qty() <= 0 || ask.need() <= 0) {
                results[i] = Result.refused(Refusal.INVALID);
                continue;
            }
            ItemKey item = SupplyRequestService.itemKeyOf(world, ask.sample());
            Verdict verdict = SupplyRequestPolicy.classify(item, config);
            Scope preFilter = SupplyWithdrawalPolicy.preFilterScope(verdict);
            if (preFilter != Scope.NONE) {
                results[i] = Result.refused("INELIGIBLE(" + verdict + ")", preFilter);
                continue;
            }
            Ticket ticket = TICKETS.find(botId, TicketKey.of(chestKey, item), now, SupplyRequestService.timings());
            boolean permitted = ticket != null && SupplyRequestService.isPermitted(ticket.fp());
            TicketStep ticketStep = SupplyWithdrawalPolicy.ticketStep(ticket != null, pending, permitted);
            switch (ticketStep) {
                case REDEEM -> results[i] = redeem(bot, world, chestPos, ticket, ask.need()).result();
                case WAIT -> results[i] = new Result(Kind.WAITING, 0, "PENDING", Scope.NONE);
                case DROP_NOT_PERMITTED -> {
                    TICKETS.drop(ticket);
                    results[i] = Result.refused(Refusal.NOT_PERMITTED);
                    whys[i] = SupplyWithdrawalPolicy.bundleWhyOfTicket(ticketStep);
                }
                case OTHER_PENDING -> results[i] = Result.refused(Refusal.OTHER_REQUEST_PENDING);
                case REQUEST -> {
                    toRequest.add(ask);
                    toRequestAt.add(i);
                    toRequestItems.add(item);
                }
                default -> results[i] = Result.refused(Refusal.INVALID);
            }
        }
        if (!toRequest.isEmpty()) {
            boolean notedAway = OWNER_AWAY.isAway(botId, now);
            boolean covered = false;
            if (notedAway) {
                covered = SupplyRequestService.hasAlways(owner, chestKey);
                for (ItemKey item : toRequestItems) {
                    covered |= SupplyRequestService.isPermitted(new RequestFingerprint(owner, botId, chestKey, item, 1));
                }
            }
            switch (SupplyWithdrawalPolicy.ownerAwayStep(notedAway, covered,
                    notedAway && SupplyRequestService.isOwnerInPromptRange(bot, owner))) {
                case REFUSE_QUIETLY -> {
                    for (int at : toRequestAt) {
                        results[at] = Result.refused(Refusal.OWNER_NOT_NEARBY);
                    }
                    toRequest.clear();
                }
                case FORGET_AND_ASK -> OWNER_AWAY.forget(botId);
                case ASK -> {
                }
            }
        }
        if (!toRequest.isEmpty()) {
            BundleRequestOutcome asked = SupplyRequestService.requestBundle(bot, chestPos, toRequest, purpose,
                    stillMissing);
            List<LineOutcome> outcomes = asked.lines();
            switch (SupplyWithdrawalPolicy.onRequest(asked.status())) {
                case TAKE, WAIT -> {
                    for (int n = 0; n < toRequestAt.size(); n++) {
                        int at = toRequestAt.get(n);
                        LineOutcome line = n < outcomes.size() ? outcomes.get(n) : null;
                        if (line == null) {
                            results[at] = Result.refused(asked.status().name(), Scope.BUSY);
                            continue;
                        }
                        switch (line.status()) {
                            case ASKED -> {
                                Ticket ticket = TICKETS.record(line.approved(), now);
                                results[at] = asked.status() == RequestStatus.OPENED
                                        ? new Result(Kind.WAITING, 0, "ASKED", Scope.NONE)
                                        : redeem(bot, world, chestPos, ticket, toRequest.get(n).need()).result();
                            }
                            case COVERED_BY_GRANT -> results[at] = redeem(bot, world, chestPos,
                                    TICKETS.record(line.approved(), now), toRequest.get(n).need()).result();
                            case REJECT_COOLDOWN -> {
                                results[at] = Result.refused(LineStatus.REJECT_COOLDOWN.name(),
                                        SupplyWithdrawalPolicy.requestScope(RequestStatus.REJECT_COOLDOWN, null, null));
                                whys[at] = BundleWhy.OWNER_DECLINED;
                            }
                            case INELIGIBLE -> results[at] = Result.refused("INELIGIBLE(" + line.verdict() + ")",
                                    SupplyWithdrawalPolicy.verdictScope(line.verdict()));
                        }
                    }
                }
                case REFUSE -> {
                    if (asked.status() == RequestStatus.OWNER_NOT_NEARBY) {
                        OWNER_AWAY.noteAway(botId, now);
                    }
                    Result refusal = Result.refused(asked.logText(),
                            SupplyWithdrawalPolicy.requestScope(asked.status(), asked.access(), null));
                    BundleWhy why = SupplyWithdrawalPolicy.bundleWhyOfRequest(asked.status());
                    for (int at : toRequestAt) {
                        results[at] = refusal;
                        whys[at] = why;
                    }
                }
            }
        }
        List<Kind> kinds = new ArrayList<>(count);
        List<Result> all = new ArrayList<>(count);
        int moved = 0;
        BundleWhy why = BundleWhy.NONE;
        for (int i = 0; i < count; i++) {
            Result r = results[i] == null ? Result.refused(Refusal.INVALID) : results[i];
            all.add(r);
            kinds.add(r.kind());
            moved += r.moved();
            if (r.kind() == Kind.REFUSED) {
                why = SupplyWithdrawalPolicy.strongerWhy(why, whys[i]);
            }
        }
        Kind kind = SupplyWithdrawalPolicy.aggregateKinds(kinds);
        Result first = all.get(0);
        for (Result r : all) {
            if (r.kind() == kind) {
                first = r;
                break;
            }
        }
        return new BundleResult(kind, moved, first.reason(), first.scope(), kind == Kind.REFUSED ? why : BundleWhy.NONE,
                all);
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
        return onServer(srv, body, r -> Step.loud(Result.refused(r)));
    }

    /** {@link #onServer(MinecraftServer, Supplier)} for any step shape; {@code refused} builds a hop's refusal. */
    private static <T> T onServer(MinecraftServer srv, Supplier<T> body, Function<Refusal, T> refused) {
        Hop<T> hop = SupplyServerHop.hop(srv, body, HOP_TIMEOUT_MS);
        return switch (hop.status()) {
            case DONE -> hop.value();
            case NOT_RUNNING -> refused.apply(Refusal.NOT_RUNNING);
            case TIMED_OUT, UNFINISHED -> refused.apply(Refusal.SERVER_BUSY);
            case INTERRUPTED -> refused.apply(Refusal.ABORTED);
            case FAILED -> refused.apply(Refusal.ERROR);
        };
    }

    /**
     * Polls the ledger until the bot's prompt is no longer pending, then takes one more step on the
     * server thread. Stops early on the caller's abort, a thread interrupt, the bot's removal or the
     * service stopping; stops after the prompt's lifetime plus slack. Either stop keeps the ticket.
     */
    private static Step awaitAnswer(MinecraftServer srv, ServerPlayerEntity bot, BooleanSupplier abort,
                                    Supplier<Step> oneStep) {
        return awaitAnswer(srv, bot, abort, () -> onServer(srv, oneStep), r -> Step.loud(Result.refused(r)));
    }

    /**
     * The wait itself, for any step shape: {@code settle} runs the one more step once the prompt is
     * no longer pending, {@code refused} builds the timeout or abort result. Worker thread only.
     */
    private static <T> T awaitAnswer(MinecraftServer srv, ServerPlayerEntity bot, BooleanSupplier abort,
                                     Supplier<T> settle, Function<Refusal, T> refused) {
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
                    return settle.get();
                case TIMED_OUT:
                    return refused.apply(Refusal.TIMEOUT);
                case ABORTED:
                    return refused.apply(Refusal.ABORTED);
                case KEEP_WAITING:
                default:
                    break;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return refused.apply(Refusal.ABORTED);
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

    /** {@code minecraft:oak_log x2,minecraft:stick x2}, for the one log line per bundle call. */
    private static String bundleText(List<BundleAsk> lines) {
        if (lines == null || lines.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (BundleAsk ask : lines) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (ask == null || ask.sample() == null || ask.sample().isEmpty()) {
                sb.append('-');
            } else {
                sb.append(Registries.ITEM.getId(ask.sample().getItem())).append('x').append(ask.qty());
            }
        }
        return sb.toString();
    }
}
