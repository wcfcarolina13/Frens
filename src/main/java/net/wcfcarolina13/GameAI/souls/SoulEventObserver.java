package net.wcfcarolina13.GameAI.souls;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.wcfcarolina13.GameAI.services.BotQuestService;
import net.wcfcarolina13.GameAI.services.BotRegistry;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import net.wcfcarolina13.GameAI.services.TaskService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Journals witnessed gameplay transitions (combat, sleep/wake, dimension travel, quest progress,
 * task lifecycle) as bounded, factual {@link SoulTypes.SoulEvent} records fed to
 * {@link SoulRuntime#recordEvent(UUID, SoulTypes.SoulEvent)}.
 *
 * <p>Two halves, deliberately separated:
 * <ul>
 *   <li>The <b>static production surface</b> ({@link #initializeProduction()},
 *       {@link #onServerTick}, {@code onBot*}/{@code onTask*}) touches live Fabric/Minecraft
 *       state, converts it into primitives, and delegates to one production instance.</li>
 *   <li>The <b>instance seam</b> ({@link #observe}, {@link #noteBotDamage},
 *       {@link #noteOwnerDamage}, {@link #tickCombat}) is data-only — UUIDs, strings, primitives,
 *       {@link Instant} — so {@code SoulEventObserverTest} can exercise the actual event policy
 *       (combat start/end debouncing, sleep/wake edges, dimension/quest transitions) without ever
 *       touching a Minecraft class, matching every other soul-communication seam in this
 *       package.</li>
 * </ul>
 *
 * <p>Session state kept per bot is intentionally small: last-seen dimension, sleeping flag, quest
 * signature, an in-progress combat deadline, and an active-task {@link TaskSignature} (task name
 * + start instant, used only to dedupe a repeat {@link #onTaskStarted} for the exact same task).
 * A bot's first observation only seeds this state — it never emits a synthetic transition event,
 * since there is nothing to compare against yet.
 *
 * <p>Events are immutable and carry only string facts (never entity references); the sole
 * exception is {@link SoulTypes.SoulEvent#participants()}, a list of {@link UUID}s — the same
 * domain-identifier contract every other soul-communication record already uses. Session state
 * follows the same rule: {@link TaskSignature} stores a name and an {@link Instant}, never the
 * {@code TaskTicket} itself (which pins a {@code ServerCommandSource}, an entity/world-linked
 * object) — every static {@code onTask*} method converts the ticket into primitives before
 * calling into the instance seam.
 *
 * <p>Mixed-thread contract: task hooks ({@link #onTaskStarted}/{@link #onTaskPaused}/
 * {@link #onTaskFinished}) arrive from whatever thread the skill or command dispatch runs on
 * (often a skill worker thread), while tick/damage/death/respawn hooks always arrive on the
 * server thread. {@link #onHobbySession} and {@link #onSelfRescue} are UUID-only for the same
 * reason as the task hooks — {@code BotIdleHobbiesService} and {@code BotFleeService.ensureAtSurface}
 * both run their callers on worker threads, so those two hooks never read live entity/world state
 * (dimension, biome, tick) and record empty/zero placeholders instead. All per-bot session maps
 * are {@link ConcurrentHashMap}s, and {@code TaskService}'s single-active-slot-per-bot invariant
 * means at most one task hook fires for a given bot at a time — so no additional synchronization
 * is needed here.
 */
public final class SoulEventObserver {

    /** How often {@link #onServerTick} samples registered bots for session-transition events. */
    private static final long TICK_INTERVAL = 20L;

    /** Default quiet period after the last hit before combat is considered over (10s). */
    private static final long DEFAULT_COMBAT_QUIET_TICKS = 200L;

    private static final AtomicReference<SoulEventObserver> PRODUCTION = new AtomicReference<>();

    private final EventSink sink;
    private final long combatQuietTicks;

    private final Map<UUID, Boolean> seeded = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastDimension = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> lastSleeping = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastQuestSignature = new ConcurrentHashMap<>();
    private final Map<UUID, Long> combatDeadlineTicks = new ConcurrentHashMap<>();
    private final Map<UUID, TaskSignature> activeTaskByBot = new ConcurrentHashMap<>();

    /**
     * Lightweight, data-only stand-in for "which task is active" — a name plus its start instant,
     * never the {@code TaskTicket} itself. Two tickets for the same task name started at different
     * instants compare unequal, so a re-begun task is still treated as a new transition.
     */
    private record TaskSignature(String name, Instant startedAt) {
    }

    /** Delivery seam so the observer never depends on {@link SoulRuntime} directly for tests. */
    public interface EventSink {
        boolean accepts(UUID botId);

        void append(UUID botId, SoulTypes.SoulEvent event);
    }

    SoulEventObserver(EventSink sink, long combatQuietTicks) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.combatQuietTicks = combatQuietTicks;
    }

    // === Static production wiring ===

    /**
     * Installs the production instance, whose {@link EventSink} requires both the master soul
     * switch to be on (via {@link SoulRuntime#isMasterEnabled()}) and an active soul profile (via
     * {@link SoulRuntime#hasActiveProfile(UUID)}), and delegates accepted events to
     * {@link SoulRuntime#recordEvent(UUID, SoulTypes.SoulEvent)}. Without the master-switch check,
     * a bot whose profile was bound and activated before the operator flipped the system off would
     * keep having its events journaled to disk after the switch-off.
     */
    public static void initializeProduction() {
        EventSink sink = new EventSink() {
            @Override
            public boolean accepts(UUID botId) {
                return botId != null && SoulRuntime.current()
                        .map(runtime -> acceptsEvent(runtime.isMasterEnabled(), runtime.hasActiveProfile(botId)))
                        .orElse(false);
            }

            @Override
            public void append(UUID botId, SoulTypes.SoulEvent event) {
                SoulRuntime.current().ifPresent(runtime -> runtime.recordEvent(botId, event));
            }
        };
        PRODUCTION.set(new SoulEventObserver(sink, DEFAULT_COMBAT_QUIET_TICKS));
    }

    /**
     * Pure predicate backing the production {@link EventSink#accepts(UUID)} check above -- split
     * out purely so {@code SoulEventObserverTest} can exercise the master-switch gating logic
     * without a {@link SoulRuntime} or any Minecraft type.
     */
    static boolean acceptsEvent(boolean masterEnabled, boolean hasActiveProfile) {
        return masterEnabled && hasActiveProfile;
    }

    /** Samples every registered, soul-enabled bot every {@link #TICK_INTERVAL} ticks. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        long tick = server.getTicks();
        if (tick % TICK_INTERVAL != 0L) {
            return;
        }
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null) {
            return;
        }
        for (UUID botId : BotRegistry.ids()) {
            if (!SoulRuntime.current().map(runtime -> runtime.hasActiveProfile(botId)).orElse(false)) {
                continue;
            }
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved()) {
                continue;
            }
            observer.observe(buildObservation(bot, tick));
        }
    }

    /** Forwards a registered bot taking damage (self-witnessed). */
    public static void onBotDamage(ServerPlayerEntity bot, DamageSource source, float amount) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || bot == null) {
            return;
        }
        // Gate BEFORE any live-world read (dimension/biome/source classification): a
        // souls-disabled install must pay nothing beyond this one check on every hit any bot
        // takes.
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        if (runtime == null || !runtime.isMasterEnabled()) {
            return;
        }
        observer.noteBotDamage(bot.getUuid(), dimensionOf(bot), biomeOf(bot), amount,
                classifySource(source), worldTickOf(bot));
    }

    /**
     * Forwards a real player taking damage to any registered bot that has them as owner/commander
     * and is currently local enough to witness it (see
     * {@link CompanionCommunicationPolicy#isWithinVisibleRange}).
     */
    public static void onPlayerDamage(ServerPlayerEntity player, DamageSource source, float amount) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || player == null) {
            return;
        }
        // Gate BEFORE any live-world read or BotRegistry/controller-resolution work: this hook
        // fires on every real player taking damage regardless of whether any bot even has souls
        // enabled, so a souls-disabled install must pay nothing beyond this one check.
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        if (runtime == null || !runtime.isMasterEnabled()) {
            return;
        }
        MinecraftServer server = player.getEntityWorld().getServer();
        if (server == null) {
            return;
        }
        String dimension = dimensionOf(player);
        String biome = biomeOf(player);
        String src = classifySource(source);
        long tick = server.getTicks();
        UUID ownerId = player.getUuid();
        for (ServerPlayerEntity bot : BotRegistry.getPlayers(server)) {
            ServerPlayerEntity controller = CompanionCommunicationPolicy.resolveController(server, bot);
            if (controller == null || !controller.getUuid().equals(ownerId)) {
                continue;
            }
            boolean witnessed = CompanionCommunicationPolicy.isWithinVisibleRange(
                    bot, player, CompanionCommunicationPolicy.VISIBLE_RANGE_BLOCKS);
            observer.noteOwnerDamage(bot.getUuid(), ownerId, witnessed, dimension, biome, amount, src, tick);
        }
    }

    /** Records the bot's death, then invalidates its soul profile before any respawn can fire. */
    public static void onBotDeath(ServerPlayerEntity bot, DamageSource source) {
        if (bot == null) {
            return;
        }
        SoulEventObserver observer = PRODUCTION.get();
        if (observer != null) {
            observer.noteDeath(bot.getUuid(), dimensionOf(bot), biomeOf(bot),
                    classifySource(source), worldTickOf(bot));
        }
        // Deliberately after noteDeath: cancelBot deactivates the profile that noteDeath's
        // accepts() check just relied on to actually record the event.
        SoulRuntime.current().ifPresent(runtime -> runtime.cancelBot(bot.getUuid()));
    }

    /** Records a bot's respawn and clears its stale session baseline. */
    public static void onBotRespawn(ServerPlayerEntity bot) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || bot == null) {
            return;
        }
        observer.noteRespawn(bot.getUuid(), dimensionOf(bot), biomeOf(bot), worldTickOf(bot));
    }

    /** Called once per successful ticket insertion in {@code TaskService.beginSkill}/{@code beginSystemTask}. */
    public static void onTaskStarted(TaskService.TaskTicket ticket) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || ticket == null) {
            return;
        }
        observer.noteTaskStarted(ticket.botUuid(), ticket.name(), ticket.createdAt(), category(ticket.name()),
                worldTickOf(ticket));
    }

    /** Called from {@code TaskService.requestPause} after the ticket transitions to {@code PAUSED}. */
    public static void onTaskPaused(TaskService.TaskTicket ticket) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || ticket == null) {
            return;
        }
        observer.noteTaskPaused(ticket.botUuid(), ticket.name(), category(ticket.name()),
                sanitizeReasonCategory(ticket.cancelReason()), worldTickOf(ticket));
    }

    /** Called from {@code TaskService.complete} before the active slot is cleared. */
    public static void onTaskFinished(TaskService.TaskTicket ticket, TaskService.State finalState) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || ticket == null) {
            return;
        }
        SoulTypes.EventType outcome = taskOutcome(finalState, ticket.isCancelRequested());
        observer.noteTaskFinished(ticket.botUuid(), ticket.name(), category(ticket.name()), outcome,
                sanitizeReasonCategory(ticket.cancelReason()), worldTickOf(ticket));
    }

    /**
     * Forwards a bot-witnessed kill (self-witnessed; the mob it just finished off).
     *
     * <p>Gated before any live-world read, including the mob-type derivation itself: the caller
     * passes the raw {@code killed} entity so the display-name/registry-path lookup only happens
     * once souls are actually enabled. Also suppressed while a {@code HuntSessionService} session
     * is active for this bot — {@link #onHuntProgress} already reports hunt kills at milestones,
     * so double-journaling every individual kill here would flood the event window.
     */
    public static void onMobKilled(ServerPlayerEntity bot, Entity killed) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || bot == null || killed == null) {
            return;
        }
        // Gate BEFORE any live-world read (dimension/biome) or the mob-type lookup, matching every
        // other production hook in this file.
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        if (runtime == null || !runtime.isMasterEnabled()) {
            return;
        }
        if (net.wcfcarolina13.GameAI.services.HuntSessionService.getSession(bot.getUuid()) != null) {
            return;
        }
        String mobType = EntityType.getId(killed.getType()).getPath();
        observer.noteMobKilled(bot.getUuid(), dimensionOf(bot), biomeOf(bot), mobType, worldTickOf(bot));
    }

    /**
     * Forwards a bot pulling itself out of a hazard (powder snow, drowning, fire, surface recovery,
     * etc.). UUID-only, matching {@link #onHobbySession}: callers such as
     * {@code BotFleeService.ensureAtSurface} run on worker threads, so this hook must never read
     * live entity/world state (dimension, biome, tick) off-thread.
     */
    public static void onSelfRescue(UUID botId, String kind) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || botId == null) {
            return;
        }
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        if (runtime == null || !runtime.isMasterEnabled()) {
            return;
        }
        observer.noteSelfRescue(botId, "", "", kind, 0L);
    }

    /** Forwards a completed idle-hobby session (fishing, wandering, etc.). UUID-only: no entity is guaranteed live by call time, so dimension/biome/tick are omitted. */
    public static void onHobbySession(UUID botId, String hobbyName) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || botId == null) {
            return;
        }
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        if (runtime == null || !runtime.isMasterEnabled()) {
            return;
        }
        observer.noteHobbySession(botId, hobbyName);
    }

    /** Forwards a hunt tally update (kills so far vs. goal). UUID-only, same rationale as {@link #onHobbySession}. */
    public static void onHuntProgress(UUID botId, String target, int kills, int goal) {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer == null || botId == null) {
            return;
        }
        SoulRuntime runtime = SoulRuntime.current().orElse(null);
        if (runtime == null || !runtime.isMasterEnabled()) {
            return;
        }
        observer.noteHuntProgress(botId, target, kills, goal);
    }

    /** Clears all in-memory session state; called on {@code SERVER_STOPPED} (world reload). */
    public static void resetSession() {
        SoulEventObserver observer = PRODUCTION.get();
        if (observer != null) {
            observer.resetInternal();
        }
    }

    // === Data-only instance seam (never touches Minecraft state) ===

    /** One tick's worth of session-relevant facts about a bot, ready for {@link #observe}. */
    record Observation(UUID botId, String dimension, String biome, boolean sleeping,
                        String questSignature, long worldTick, Instant occurredAt) {
    }

    /**
     * Detects dimension, sleep/wake, combat-end, and quest-stage transitions against the last
     * observation for this bot. The first observation of a bot only seeds its baseline.
     */
    void observe(Observation observation) {
        if (observation == null) {
            return;
        }
        UUID botId = observation.botId();
        if (botId == null || !sink.accepts(botId)) {
            return;
        }

        boolean firstSeen = seeded.putIfAbsent(botId, Boolean.TRUE) == null;
        String previousDimension = lastDimension.put(botId, observation.dimension());
        Boolean previousSleeping = lastSleeping.put(botId, observation.sleeping());
        String previousQuest = lastQuestSignature.put(botId, observation.questSignature());

        tickCombat(botId, observation.dimension(), observation.biome(), observation.worldTick());

        if (firstSeen) {
            return;
        }

        if (!Objects.equals(previousDimension, observation.dimension())) {
            Map<String, String> facts = factMap("from", nullToEmpty(previousDimension),
                    "to", observation.dimension());
            emit(botId, SoulTypes.EventType.DIMENSION_CHANGED, observation.dimension(), observation.biome(),
                    facts, SoulTypes.Witness.SELF, observation.worldTick(), SoulTypes.Salience.NORMAL, List.of());
        }

        if (previousSleeping != null && previousSleeping.booleanValue() != observation.sleeping()) {
            SoulTypes.EventType type = observation.sleeping() ? SoulTypes.EventType.SLEEP : SoulTypes.EventType.WAKE;
            emit(botId, type, observation.dimension(), observation.biome(), Map.of(), SoulTypes.Witness.SELF,
                    observation.worldTick(), SoulTypes.Salience.LOW, List.of());
        }

        if (!Objects.equals(previousQuest, observation.questSignature())) {
            Map<String, String> facts = factMap("from", nullToEmpty(previousQuest),
                    "to", observation.questSignature());
            emit(botId, SoulTypes.EventType.QUEST_STAGE_CHANGED, observation.dimension(), observation.biome(),
                    facts, SoulTypes.Witness.SELF, observation.worldTick(), SoulTypes.Salience.NORMAL, List.of());
        }
    }

    /** Records the bot's own damage and starts (or refreshes) its combat window. */
    void noteBotDamage(UUID botId, String dimension, String biome, float amount, String source, long worldTick) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        Map<String, String> facts = factMap("amount", formatAmount(amount), "source", nullToEmpty(source));
        emit(botId, SoulTypes.EventType.BOT_DAMAGE, dimension, biome, facts, SoulTypes.Witness.SELF,
                worldTick, SoulTypes.Salience.NORMAL, List.of());

        boolean wasActive = combatDeadlineTicks.containsKey(botId);
        combatDeadlineTicks.put(botId, worldTick + combatQuietTicks);
        if (!wasActive) {
            emit(botId, SoulTypes.EventType.COMBAT_STARTED, dimension, biome, Map.of(), SoulTypes.Witness.SELF,
                    worldTick, SoulTypes.Salience.LOW, List.of());
        }
    }

    /**
     * Records the bot's owner taking damage, but only when {@code witnessed} is {@code true} —
     * i.e. the bot was local enough to actually see it happen.
     */
    void noteOwnerDamage(UUID botId, UUID ownerId, boolean witnessed, String dimension, String biome,
                          float amount, String source, long worldTick) {
        if (botId == null || !witnessed || !sink.accepts(botId)) {
            return;
        }
        Map<String, String> facts = factMap("amount", formatAmount(amount), "source", nullToEmpty(source));
        List<UUID> participants = ownerId != null ? List.of(ownerId) : List.of();
        emit(botId, SoulTypes.EventType.OWNER_DAMAGE, dimension, biome, facts, SoulTypes.Witness.LOCAL,
                worldTick, SoulTypes.Salience.NORMAL, participants);
    }

    /** Ends the bot's combat window once {@code worldTick} reaches its quiet-period deadline. */
    void tickCombat(UUID botId, String dimension, String biome, long worldTick) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        Long deadline = combatDeadlineTicks.get(botId);
        if (deadline == null || worldTick < deadline) {
            return;
        }
        combatDeadlineTicks.remove(botId);
        emit(botId, SoulTypes.EventType.COMBAT_ENDED, dimension, biome, Map.of(), SoulTypes.Witness.SELF,
                worldTick, SoulTypes.Salience.LOW, List.of());
    }

    /** Records a mob the bot just killed. */
    void noteMobKilled(UUID botId, String dimension, String biome, String mobType, long worldTick) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        Map<String, String> facts = factMap("mob", nullToEmpty(mobType));
        emit(botId, SoulTypes.EventType.MOB_KILLED, dimension, biome, facts, SoulTypes.Witness.SELF,
                worldTick, SoulTypes.Salience.NORMAL, List.of());
    }

    /** Records the bot rescuing itself from a hazard (e.g. powder snow, drowning). */
    void noteSelfRescue(UUID botId, String dimension, String biome, String kind, long worldTick) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        Map<String, String> facts = factMap("kind", nullToEmpty(kind));
        emit(botId, SoulTypes.EventType.SELF_RESCUE, dimension, biome, facts, SoulTypes.Witness.SELF,
                worldTick, SoulTypes.Salience.HIGH, List.of());
    }

    /**
     * Records a completed idle-hobby session. UUID-only source data (no live entity guaranteed at
     * call time), so dimension/biome/worldTick are omitted the same way {@link #noteTaskStarted}
     * omits dimension/biome for ticket-sourced events.
     */
    void noteHobbySession(UUID botId, String hobbyName) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        Map<String, String> facts = factMap("hobby", nullToEmpty(hobbyName));
        emit(botId, SoulTypes.EventType.HOBBY_SESSION, "", "", facts, SoulTypes.Witness.SELF, 0L,
                SoulTypes.Salience.LOW, List.of());
    }

    /** Records a hunt tally update (kills so far vs. goal). UUID-only, same rationale as {@link #noteHobbySession}. */
    void noteHuntProgress(UUID botId, String target, int kills, int goal) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        Map<String, String> facts = factMap("target", nullToEmpty(target), "kills", String.valueOf(kills),
                "goal", String.valueOf(goal));
        emit(botId, SoulTypes.EventType.HUNT_PROGRESS, "", "", facts, SoulTypes.Witness.SELF, 0L,
                SoulTypes.Salience.NORMAL, List.of());
    }

    /** Maps a finished ticket's terminal state to the coarse outcome event type. */
    static SoulTypes.EventType taskOutcome(TaskService.State finalState, boolean cancelRequested) {
        if (finalState == TaskService.State.COMPLETED) {
            return SoulTypes.EventType.TASK_COMPLETED;
        }
        return cancelRequested ? SoulTypes.EventType.TASK_CANCELLED : SoulTypes.EventType.TASK_FAILED;
    }

    // === Instance helpers backing the static production surface ===

    private void noteDeath(UUID botId, String dimension, String biome, String source, long worldTick) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        Map<String, String> facts = factMap("source", nullToEmpty(source));
        emit(botId, SoulTypes.EventType.DEATH, dimension, biome, facts, SoulTypes.Witness.SELF,
                worldTick, SoulTypes.Salience.HIGH, List.of());
        clearSession(botId);
    }

    private void noteRespawn(UUID botId, String dimension, String biome, long worldTick) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        emit(botId, SoulTypes.EventType.RESPAWN, dimension, biome, Map.of(), SoulTypes.Witness.SELF,
                worldTick, SoulTypes.Salience.NORMAL, List.of());
        // Respawn resets position/state out from under us -- reseed rather than carry a stale
        // baseline into the next observe() (which would otherwise misfire a false transition).
        clearSession(botId);
    }

    private void noteTaskStarted(UUID botId, String taskName, Instant startedAt, String category,
                                  long worldTick) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        TaskSignature signature = new TaskSignature(nullToEmpty(taskName), startedAt);
        if (signature.equals(activeTaskByBot.put(botId, signature))) {
            // Same task transition already notified -- never double-emit TASK_STARTED for it.
            return;
        }
        Map<String, String> facts = factMap("task", nullToEmpty(taskName), "category", nullToEmpty(category));
        emit(botId, SoulTypes.EventType.TASK_STARTED, "", "", facts, SoulTypes.Witness.SELF, worldTick,
                SoulTypes.Salience.LOW, List.of());
    }

    private void noteTaskPaused(UUID botId, String taskName, String category, String reasonCategory,
                                 long worldTick) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        Map<String, String> facts = factMap("task", nullToEmpty(taskName), "category", nullToEmpty(category),
                "reason", nullToEmpty(reasonCategory));
        emit(botId, SoulTypes.EventType.TASK_PAUSED, "", "", facts, SoulTypes.Witness.SELF, worldTick,
                SoulTypes.Salience.LOW, List.of());
    }

    private void noteTaskFinished(UUID botId, String taskName, String category, SoulTypes.EventType outcome,
                                   String reasonCategory, long worldTick) {
        if (botId == null || !sink.accepts(botId)) {
            return;
        }
        Map<String, String> facts = factMap("task", nullToEmpty(taskName), "category", nullToEmpty(category),
                "state", outcome.name(), "reason", nullToEmpty(reasonCategory));
        emit(botId, outcome, "", "", facts, SoulTypes.Witness.SELF, worldTick, SoulTypes.Salience.NORMAL,
                List.of());
        activeTaskByBot.remove(botId);
    }

    private void resetInternal() {
        seeded.clear();
        lastDimension.clear();
        lastSleeping.clear();
        lastQuestSignature.clear();
        combatDeadlineTicks.clear();
        activeTaskByBot.clear();
    }

    private void clearSession(UUID botId) {
        seeded.remove(botId);
        lastDimension.remove(botId);
        lastSleeping.remove(botId);
        lastQuestSignature.remove(botId);
        combatDeadlineTicks.remove(botId);
        activeTaskByBot.remove(botId);
    }

    private void emit(UUID botId, SoulTypes.EventType type, String dimension, String biome,
                       Map<String, String> facts, SoulTypes.Witness witness, long worldTick,
                       SoulTypes.Salience salience, List<UUID> participants) {
        SoulTypes.SoulEvent event = new SoulTypes.SoulEvent(UUID.randomUUID(), type, botId, participants,
                dimension, biome, facts, witness, worldTick, Instant.now(), salience);
        sink.append(botId, event);
    }

    private static Map<String, String> factMap(String... kv) {
        Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            facts.put(kv[i], kv[i + 1]);
        }
        return Map.copyOf(facts);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String formatAmount(float amount) {
        return String.valueOf(amount);
    }

    // === Pure static helpers (Minecraft-touching, production-only) ===

    private static String category(String taskName) {
        if (taskName == null) {
            return "";
        }
        int idx = taskName.indexOf(':');
        return idx >= 0 ? taskName.substring(0, idx) : taskName;
    }

    /**
     * Buckets a raw, human-facing cancel reason (which may contain formatting codes and full
     * sentences) into a small, coarse category safe to store as an event fact. Never stores the
     * raw reason text itself.
     */
    private static String sanitizeReasonCategory(String rawReason) {
        if (rawReason == null || rawReason.isBlank()) {
            return "";
        }
        String stripped = rawReason.replaceAll("§.", "").toLowerCase(Locale.ROOT);
        if (stripped.contains("respawn")) {
            return "respawn";
        }
        if (stripped.contains("stopping")) {
            return "server_stopping";
        }
        if (stripped.contains("preempt") || stripped.contains("interrupted by new command")) {
            return "preempted";
        }
        if (stripped.contains("threat")) {
            return "threat";
        }
        if (stripped.contains("danger")) {
            return "danger";
        }
        if (stripped.contains("halt") || stripped.contains("stop")) {
            return "manual_stop";
        }
        return "other";
    }

    private static Observation buildObservation(ServerPlayerEntity bot, long worldTick) {
        return new Observation(bot.getUuid(), dimensionOf(bot), biomeOf(bot), bot.isSleeping(),
                questSignatureOf(bot.getUuid()), worldTick, Instant.now());
    }

    private static String questSignatureOf(UUID botId) {
        return BotQuestService.getActiveQuestSnapshot(botId)
                .map(quest -> quest.id() + ":" + quest.actionIndex())
                .orElse("");
    }

    private static String dimensionOf(ServerPlayerEntity player) {
        return player.getEntityWorld().getRegistryKey().getValue().toString();
    }

    private static String biomeOf(ServerPlayerEntity player) {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return "";
        }
        return world.getBiome(player.getBlockPos()).getKey()
                .map(key -> key.getValue().getPath())
                .orElse("");
    }

    private static String classifySource(DamageSource source) {
        if (source == null) {
            return "";
        }
        Entity attacker = source.getAttacker();
        if (attacker instanceof ServerPlayerEntity) {
            return "player";
        }
        if (attacker != null) {
            return EntityType.getId(attacker.getType()).getPath();
        }
        return source.getTypeRegistryEntry().getKey()
                .map(key -> key.getValue().getPath())
                .orElse("environment");
    }

    private static long worldTickOf(ServerPlayerEntity player) {
        MinecraftServer server = player.getEntityWorld().getServer();
        return server != null ? server.getTicks() : 0L;
    }

    private static long worldTickOf(TaskService.TaskTicket ticket) {
        ServerCommandSource source = ticket.source();
        MinecraftServer server = source != null ? source.getServer() : null;
        return server != null ? server.getTicks() : 0L;
    }
}
