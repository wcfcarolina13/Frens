package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Assessment;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Choice;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Config;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.RequestFingerprint;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Stock;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * In-memory state for companion supply requests: the one pending prompt per bot, the grants an
 * owner's answer produced, standing "always" permissions, and the cooldowns that keep a bot from
 * nagging. Nothing here touches the world; the server adapter (supplies Phase 2) moves items only
 * after {@link #consumeGrant} says how many.
 *
 * <p><b>Lifecycle of one request.</b> {@link #open} checks the owner, the item and the reserve
 * through {@link SupplyRequestPolicy#assess}, then either reports that a standing permission
 * already covers it, refuses (cooldown, another prompt pending), or records a pending prompt with a
 * fresh id. {@link #respond} lets only the owner answer that id — a foreign click changes nothing —
 * and turns Allow once / Always into a single-use grant for exactly the pending fingerprint.
 * {@link #consumeGrant} re-assesses against the stock at transfer time and spends the grant.
 * A prompt nobody answers expires and grants nothing.
 *
 * <p><b>Time.</b> All deadlines are epoch millis from the injected clock; an instant equal to a
 * deadline counts as expired (the same boundary as {@code SpeechFloorPolicy.isOpen}). Expired
 * entries are dropped lazily by whichever call meets them, and in bulk by {@link #sweep}.
 *
 * <p><b>Threading.</b> Every public method is {@code synchronized}; the ledger may be called from
 * the server thread and from worker threads alike.
 */
public final class SupplyRequestLedger {

    /**
     * Lifetimes and cooldowns, in milliseconds. Negative values count as zero.
     *
     * @param requestLifetimeMs how long a prompt waits for an answer (default 30 s)
     * @param grantLifetimeMs   how long an answered grant may be spent (default 60 s, from the answer)
     * @param promptCooldownMs  minimum gap between one bot's prompts (default 15 s)
     * @param rejectCooldownMs  how long a "No" silences the same bot, chest and item id (default 300 s)
     */
    public record Timings(long requestLifetimeMs, long grantLifetimeMs, long promptCooldownMs,
                          long rejectCooldownMs) {
        public Timings {
            requestLifetimeMs = Math.max(0L, requestLifetimeMs);
            grantLifetimeMs = Math.max(0L, grantLifetimeMs);
            promptCooldownMs = Math.max(0L, promptCooldownMs);
            rejectCooldownMs = Math.max(0L, rejectCooldownMs);
        }

        public static Timings defaults() {
            return new Timings(30_000L, 60_000L, 15_000L, 300_000L);
        }
    }

    public enum OpenStatus {
        /** A prompt is now pending under {@link OpenResult#requestId()}. */
        OPENED,
        /** This bot already has a prompt waiting; one at a time. */
        DUPLICATE_PENDING,
        /** This bot prompted too recently. */
        PROMPT_COOLDOWN,
        /** The owner said No to this bot, chest and item id recently. */
        REJECT_COOLDOWN,
        /** A standing "always" permission covers it: no prompt, go straight to {@link #consumeGrant}. */
        COVERED_BY_ALWAYS,
        /** The policy refused it; see {@link OpenResult#verdict()}. */
        INELIGIBLE
    }

    /**
     * @param requestId   set for {@link OpenStatus#OPENED} only
     * @param fingerprint for {@link OpenStatus#OPENED} and {@link OpenStatus#COVERED_BY_ALWAYS}:
     *                    the request with its quantity cut to what the policy permits — the exact
     *                    quantity to show in the prompt and to consume later; otherwise {@code null}
     * @param verdict     the policy verdict ({@link Verdict#ELIGIBLE} unless {@link OpenStatus#INELIGIBLE})
     */
    public record OpenResult(OpenStatus status, UUID requestId, RequestFingerprint fingerprint,
                             Verdict verdict) {
    }

    public enum ResponseStatus {
        GRANTED_ONCE,
        /** Granted this request and recorded a standing permission for (owner, world, chest). */
        GRANTED_ALWAYS,
        REJECTED,
        /** The prompt timed out before the answer; nothing is granted. */
        EXPIRED,
        /** No such pending prompt — never opened, already answered, or cleared. */
        NOT_FOUND,
        /** The responder may not answer for this bot; the prompt stays pending for the owner. */
        FOREIGN_CALLER
    }

    /** @param fingerprint the answered request, or {@code null} for NOT_FOUND / FOREIGN_CALLER */
    public record Response(ResponseStatus status, RequestFingerprint fingerprint) {
    }

    public enum ConsumeStatus {
        /** Spent a single-use grant. */
        ONCE,
        /** Covered by a standing permission; nothing was spent. */
        ALWAYS,
        /** No grant matches this exact fingerprint and no permission covers it. */
        NO_GRANT,
        /** The matching grant ran out of time. */
        EXPIRED,
        /** A grant matches but for fewer items than asked; the grant is kept. */
        OVER_GRANT,
        /** A grant or permission matches but the policy now refuses (e.g. the reserve); kept. */
        INELIGIBLE
    }

    /**
     * @param quantity how many items the adapter may move now (0 unless ONCE or ALWAYS)
     * @param verdict  the transfer-time policy verdict
     */
    public record Consume(ConsumeStatus status, int quantity, Verdict verdict) {
        public boolean permitted() {
            return (status == ConsumeStatus.ONCE || status == ConsumeStatus.ALWAYS) && quantity > 0;
        }
    }

    /**
     * What {@link #revokeAllAlways} withdrew.
     *
     * @param permissions standing "always" permissions removed
     * @param grants      unspent, unexpired grants removed (answers the bots had not used yet)
     */
    public record Revoked(int permissions, int grants) {
        public static final Revoked NOTHING = new Revoked(0, 0);

        public boolean nothing() {
            return permissions == 0 && grants == 0;
        }
    }

    /** A grant's identity: the fingerprint minus the quantity. */
    private record Target(UUID owner, UUID bot, ChestKey chest, ItemKey item) {
        static Target of(RequestFingerprint fp) {
            return new Target(fp.owner(), fp.bot(), fp.chest(), fp.item());
        }
    }

    private record Pending(UUID id, RequestFingerprint fp, long expiresAtMs) {
    }

    private record Grant(RequestFingerprint fp, long expiresAtMs) {
    }

    /**
     * A standing "always" permission: common supplies from {@code chest} for any bot of
     * {@code owner}. Public so the adapter can persist {@link #alwaysSnapshot()} and seed it back
     * through {@link #restoreAlways}; this is the only structure those, {@link #respond},
     * {@link #revokeAlways}, {@link #revokeAllAlways} and {@link #clearOwner} read and write.
     */
    public record AlwaysScope(UUID owner, ChestKey chest) {
        public AlwaysScope {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(chest, "chest");
        }
    }

    private record RejectKey(UUID bot, ChestKey chest, String itemId) {
    }

    private final LongSupplier clock;
    private final Supplier<UUID> ids;
    private final Timings timings;
    private final Config config;

    private final Map<UUID, Pending> pendingById = new HashMap<>();
    private final Map<UUID, UUID> pendingIdByBot = new HashMap<>();
    /** One grant per target: a newer grant replaces an older one rather than stacking beside it. */
    private final Map<Target, Grant> grants = new HashMap<>();
    private final Set<AlwaysScope> always = new HashSet<>();
    private final Map<UUID, Long> promptCooldownUntil = new HashMap<>();
    private final Map<RejectKey, Long> rejectCooldownUntil = new HashMap<>();

    public SupplyRequestLedger(LongSupplier clock, Supplier<UUID> ids, Timings timings, Config config) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.timings = timings == null ? Timings.defaults() : timings;
        this.config = config == null ? Config.defaults() : config;
    }

    public Config config() {
        return config;
    }

    public Timings timings() {
        return timings;
    }

    /**
     * Asks to take {@code fp.qty()} of {@code fp.item()} from {@code fp.chest()}.
     *
     * <p>Checks, in order: the policy (owner, item, need, reserve) → a standing permission →
     * a recent No for this bot, chest and item id → a prompt already pending for this bot →
     * this bot's prompt cooldown. A standing permission outranks an earlier No: it can only have
     * been given after that No, since a covered request is never prompted.
     *
     * @param stock current chest contents for this item (both halves)
     * @param need  how many the bot actually needs
     */
    public synchronized OpenResult open(RequestFingerprint fp, Stock stock, int need) {
        Objects.requireNonNull(fp, "fp");
        long now = clock.getAsLong();
        Assessment assessment = SupplyRequestPolicy.assess(fp, stock, need, config);
        if (!assessment.eligible()) {
            return new OpenResult(OpenStatus.INELIGIBLE, null, null, assessment.verdict());
        }
        RequestFingerprint approved = fp.withQty(assessment.quantity());
        if (always.contains(new AlwaysScope(fp.owner(), fp.chest()))) {
            return new OpenResult(OpenStatus.COVERED_BY_ALWAYS, null, approved, Verdict.ELIGIBLE);
        }
        if (isActive(rejectCooldownUntil, new RejectKey(fp.bot(), fp.chest(), fp.itemId()), now)) {
            return new OpenResult(OpenStatus.REJECT_COOLDOWN, null, null, Verdict.ELIGIBLE);
        }
        UUID pendingId = pendingIdByBot.get(fp.bot());
        if (pendingId != null) {
            Pending pending = pendingById.get(pendingId);
            if (pending != null && now < pending.expiresAtMs()) {
                return new OpenResult(OpenStatus.DUPLICATE_PENDING, null, null, Verdict.ELIGIBLE);
            }
            if (pending != null) {
                expire(pending);
            } else {
                pendingIdByBot.remove(fp.bot());
            }
        }
        if (isActive(promptCooldownUntil, fp.bot(), now)) {
            return new OpenResult(OpenStatus.PROMPT_COOLDOWN, null, null, Verdict.ELIGIBLE);
        }
        UUID id = Objects.requireNonNull(ids.get(), "ids supplied null");
        Pending pending = new Pending(id, approved, now + timings.requestLifetimeMs());
        pendingById.put(id, pending);
        pendingIdByBot.put(fp.bot(), id);
        extendPromptCooldown(fp.bot(), now);
        return new OpenResult(OpenStatus.OPENED, id, approved, Verdict.ELIGIBLE);
    }

    /**
     * Records an answer to the prompt {@code requestId}.
     *
     * <p>A responder who may not answer ({@link SupplyRequestPolicy#mayRespond}) gets
     * {@link ResponseStatus#FOREIGN_CALLER} and the prompt stays exactly as it was, so a stranger's
     * click can neither approve nor cancel it. Any other outcome closes the prompt, so answering
     * twice finds nothing. The latest answer wins: a No also withdraws any unspent grant an earlier
     * answer left for the same owner, bot, chest and exact item.
     */
    public synchronized Response respond(UUID requestId, UUID responder, boolean responderIsOperator,
                                         Choice choice) {
        Objects.requireNonNull(choice, "choice");
        long now = clock.getAsLong();
        Pending pending = requestId == null ? null : pendingById.get(requestId);
        if (pending == null) {
            return new Response(ResponseStatus.NOT_FOUND, null);
        }
        RequestFingerprint fp = pending.fp();
        if (!SupplyRequestPolicy.mayRespond(responder, fp.owner(), responderIsOperator, config)) {
            return new Response(ResponseStatus.FOREIGN_CALLER, null);
        }
        if (now >= pending.expiresAtMs()) {
            expire(pending);
            return new Response(ResponseStatus.EXPIRED, fp);
        }
        close(pending);
        extendPromptCooldown(fp.bot(), now);
        return switch (choice) {
            case NO -> {
                // The latest answer wins: an earlier "Allow once" for this exact target is withdrawn.
                grants.remove(Target.of(fp));
                rejectCooldownUntil.put(new RejectKey(fp.bot(), fp.chest(), fp.itemId()),
                        now + timings.rejectCooldownMs());
                yield new Response(ResponseStatus.REJECTED, fp);
            }
            case ALLOW_ONCE -> {
                grants.put(Target.of(fp), new Grant(fp, now + timings.grantLifetimeMs()));
                yield new Response(ResponseStatus.GRANTED_ONCE, fp);
            }
            case ALWAYS_COMMON -> {
                grants.put(Target.of(fp), new Grant(fp, now + timings.grantLifetimeMs()));
                always.add(new AlwaysScope(fp.owner(), fp.chest()));
                yield new Response(ResponseStatus.GRANTED_ALWAYS, fp);
            }
        };
    }

    /**
     * Transfer-time check. Call on the server thread immediately before moving items, with the
     * stock as it is now and the bot's current owner in {@code fp}.
     *
     * <p>A single-use grant matches only a fingerprint equal to it on owner, bot, chest, item id
     * and component fingerprint, with {@code fp.qty()} no larger than granted; it is spent on
     * success. Otherwise a standing permission for (owner, chest) covers it. Either way the policy
     * is re-run against {@code stockNow} and {@code needNow}, so the reserve holds even when the
     * chest changed since the prompt and even under "always"; {@link Consume#quantity()} is the most
     * the adapter may move.
     */
    public synchronized Consume consumeGrant(RequestFingerprint fp, Stock stockNow, int needNow) {
        if (fp == null) {
            return new Consume(ConsumeStatus.NO_GRANT, 0, Verdict.NO_NEED);
        }
        long now = clock.getAsLong();
        Assessment assessment = SupplyRequestPolicy.assess(fp, stockNow, needNow, config);
        boolean expired = false;
        boolean overGrant = false;
        Target target = Target.of(fp);
        Grant grant = grants.get(target);
        if (grant != null) {
            if (now >= grant.expiresAtMs()) {
                grants.remove(target);
                expired = true;
            } else if (fp.qty() > grant.fp().qty()) {
                overGrant = true;
            } else if (!assessment.eligible()) {
                return new Consume(ConsumeStatus.INELIGIBLE, 0, assessment.verdict());
            } else {
                grants.remove(target);
                return new Consume(ConsumeStatus.ONCE, assessment.quantity(), Verdict.ELIGIBLE);
            }
        }
        if (fp.owner() != null && always.contains(new AlwaysScope(fp.owner(), fp.chest()))) {
            if (!assessment.eligible()) {
                return new Consume(ConsumeStatus.INELIGIBLE, 0, assessment.verdict());
            }
            return new Consume(ConsumeStatus.ALWAYS, assessment.quantity(), Verdict.ELIGIBLE);
        }
        ConsumeStatus status = overGrant ? ConsumeStatus.OVER_GRANT
                : expired ? ConsumeStatus.EXPIRED : ConsumeStatus.NO_GRANT;
        return new Consume(status, 0, assessment.verdict());
    }

    public synchronized boolean hasAlways(UUID owner, ChestKey chest) {
        return owner != null && chest != null && always.contains(new AlwaysScope(owner, chest));
    }

    /**
     * Seeds a standing permission for (owner, chest) exactly as an {@link Choice#ALWAYS_COMMON}
     * answer records it, without a grant and without touching any prompt or cooldown. For loading
     * persisted permissions at server start; a {@code null} argument is ignored.
     */
    public synchronized void restoreAlways(UUID owner, ChestKey chest) {
        if (owner == null || chest == null) {
            return;
        }
        always.add(new AlwaysScope(owner, chest));
    }

    /** An immutable copy of every standing permission, for persisting. */
    public synchronized Set<AlwaysScope> alwaysSnapshot() {
        return Set.copyOf(always);
    }

    /**
     * Withdraws the standing permission for (owner, chest) and every unspent grant that owner's
     * answers left at that chest, so nothing more leaves it without a new prompt.
     */
    public synchronized boolean revokeAlways(UUID owner, ChestKey chest) {
        if (owner == null || chest == null) {
            return false;
        }
        boolean removed = always.remove(new AlwaysScope(owner, chest));
        grants.keySet().removeIf(t -> owner.equals(t.owner()) && chest.equals(t.chest()));
        return removed;
    }

    /**
     * Withdraws every standing permission {@code owner} gave, at every chest, and every unspent
     * grant that owner's answers left anywhere, so nothing more leaves any of their chests without
     * a new prompt. Unlike {@link #clearOwner}, a prompt still waiting for the owner stays open
     * and every cooldown stays as it was: this undoes permissions, it does not forget the bots'
     * recent asking.
     *
     * @return how many standing permissions and how many live unspent grants were removed (a grant
     *         already past its deadline is dropped too, but not counted: it permitted nothing)
     */
    public synchronized Revoked revokeAllAlways(UUID owner) {
        if (owner == null) {
            return Revoked.NOTHING;
        }
        int before = always.size();
        always.removeIf(s -> owner.equals(s.owner()));
        long now = clock.getAsLong();
        int liveGrants = 0;
        Iterator<Map.Entry<Target, Grant>> it = grants.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Target, Grant> entry = it.next();
            if (owner.equals(entry.getKey().owner())) {
                if (now < entry.getValue().expiresAtMs()) {
                    liveGrants++;
                }
                it.remove();
            }
        }
        return new Revoked(before - always.size(), liveGrants);
    }

    /**
     * Whether a transfer for {@code fp} would find a permission, ignoring stock and need: a live
     * single-use grant for the same owner, bot, chest and exact item whose quantity is at least
     * {@code fp.qty()}, or a standing permission for ({@code fp.owner()}, {@code fp.chest()}).
     * The same matching {@link #consumeGrant} applies, without the policy re-check and without
     * spending or dropping anything. An instant equal to a grant's deadline is expired.
     */
    public synchronized boolean isPermitted(RequestFingerprint fp) {
        if (fp == null) {
            return false;
        }
        Grant grant = grants.get(Target.of(fp));
        if (grant != null && clock.getAsLong() < grant.expiresAtMs() && fp.qty() <= grant.fp().qty()) {
            return true;
        }
        return fp.owner() != null && always.contains(new AlwaysScope(fp.owner(), fp.chest()));
    }

    /** True while {@code bot} has an unexpired prompt waiting. */
    public synchronized boolean hasPending(UUID bot) {
        UUID id = bot == null ? null : pendingIdByBot.get(bot);
        Pending pending = id == null ? null : pendingById.get(id);
        return pending != null && clock.getAsLong() < pending.expiresAtMs();
    }

    /**
     * Forgets everything about a bot that is being removed: its prompt, grants and cooldowns. Not
     * for a death or respawn — that would let the bot re-ask something its owner just refused.
     */
    public synchronized void clearBot(UUID bot) {
        if (bot == null) {
            return;
        }
        UUID id = pendingIdByBot.remove(bot);
        if (id != null) {
            pendingById.remove(id);
        }
        grants.keySet().removeIf(t -> bot.equals(t.bot()));
        promptCooldownUntil.remove(bot);
        rejectCooldownUntil.keySet().removeIf(k -> bot.equals(k.bot()));
    }

    /** Forgets every prompt, grant and standing permission bound to {@code owner}. */
    public synchronized void clearOwner(UUID owner) {
        if (owner == null) {
            return;
        }
        Iterator<Pending> it = pendingById.values().iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            if (owner.equals(pending.fp().owner())) {
                it.remove();
                pendingIdByBot.remove(pending.fp().bot(), pending.id());
            }
        }
        grants.keySet().removeIf(t -> owner.equals(t.owner()));
        always.removeIf(s -> owner.equals(s.owner()));
    }

    /**
     * Drops every expired prompt, grant and cooldown.
     *
     * @return how many unanswered prompts expired in this sweep
     */
    public synchronized int sweep() {
        long now = clock.getAsLong();
        int expiredPrompts = 0;
        for (Pending pending : pendingById.values().toArray(new Pending[0])) {
            if (now >= pending.expiresAtMs()) {
                expire(pending);
                expiredPrompts++;
            }
        }
        grants.values().removeIf(g -> now >= g.expiresAtMs());
        promptCooldownUntil.values().removeIf(until -> now >= until);
        rejectCooldownUntil.values().removeIf(until -> now >= until);
        return expiredPrompts;
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private void close(Pending pending) {
        pendingById.remove(pending.id());
        pendingIdByBot.remove(pending.fp().bot(), pending.id());
    }

    /**
     * Closes an unanswered prompt. The bot's prompt cooldown runs from the moment it expired, so a
     * bot whose owner ignores it waits a full cooldown before asking again instead of re-prompting
     * the instant the old prompt lapses.
     */
    private void expire(Pending pending) {
        close(pending);
        extendPromptCooldown(pending.fp().bot(), pending.expiresAtMs());
    }

    private void extendPromptCooldown(UUID bot, long fromMs) {
        long until = fromMs + timings.promptCooldownMs();
        promptCooldownUntil.merge(bot, until, Math::max);
    }

    private static <K> boolean isActive(Map<K, Long> deadlines, K key, long now) {
        Long until = deadlines.get(key);
        if (until == null) {
            return false;
        }
        if (now >= until) {
            deadlines.remove(key);
            return false;
        }
        return true;
    }
}
