package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TaskService {

    private static final Logger LOGGER = LoggerFactory.getLogger("task-service");
    private static volatile boolean SERVER_STOPPING = false;

    private TaskService() {
    }

    public enum State {
        IDLE,
        RUNNING,
        PAUSED,
        ABORTED,
        COMPLETED
    }

    /**
     * Where a task was initiated from.
     * <p>
     * This is intentionally coarse-grained; it is used for automation policies (e.g., sunset gating)
     * and light UX (touch responses), not for permission checks.
     */
    public enum Origin {
        COMMAND,
        AMBIENT,
        SYSTEM
    }

    /**
     * Snapshot of the currently active task (if any) for a bot.
     */
    public record ActiveTaskInfo(String name,
                                 Origin origin,
                                 boolean openEnded,
                                 State state,
                                 boolean cancelRequested,
                                 Instant createdAt) {
    }

    public static final class TaskTicket {
        private final String name;
        private final ServerCommandSource source;
        private final UUID botUuid;
        private final Instant createdAt;
        private final AtomicReference<State> state;
        private final AtomicBoolean cancelRequested;
        private final AtomicReference<String> cancelReason;
        private final AtomicReference<Thread> executingThread;
        private final AtomicReference<Origin> origin;
        private final AtomicBoolean openEnded;

        TaskTicket(String name, ServerCommandSource source, UUID botUuid) {
            this.name = name;
            this.source = source;
            this.botUuid = botUuid;
            this.createdAt = Instant.now();
            this.state = new AtomicReference<>(State.RUNNING);
            this.cancelRequested = new AtomicBoolean(false);
            this.cancelReason = new AtomicReference<>("");
            this.executingThread = new AtomicReference<>(null);
            this.origin = new AtomicReference<>(Origin.COMMAND);
            this.openEnded = new AtomicBoolean(false);
        }

        public String name() {
            return name;
        }

        public ServerCommandSource source() {
            return source;
        }

        public UUID botUuid() {
            return botUuid;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public Origin origin() {
            Origin o = origin.get();
            return o != null ? o : Origin.COMMAND;
        }

        public void setOrigin(Origin origin) {
            if (origin != null) {
                this.origin.set(origin);
            }
        }

        public boolean isOpenEnded() {
            return openEnded.get();
        }

        public void setOpenEnded(boolean openEnded) {
            this.openEnded.set(openEnded);
        }

        public void attachExecutingThread(Thread thread) {
            if (thread == null) {
                return;
            }
            executingThread.set(thread);
        }

        public boolean isExecutingThreadAlive() {
            Thread t = executingThread.get();
            return t != null && t.isAlive();
        }

        public void interruptExecutingThread() {
            Thread t = executingThread.get();
            if (t != null) {
                try {
                    t.interrupt();
                } catch (Exception ignored) {
                }
            }
        }

        public State state() {
            return state.get();
        }

        public void setState(State newState) {
            state.set(newState);
        }

        public boolean isCancelRequested() {
            return cancelRequested.get();
        }

        public boolean requestCancel(String reason) {
            if (!cancelRequested.compareAndSet(false, true)) {
                return false;
            }
            cancelReason.set(reason == null ? "" : reason);
            return true;
        }

        public String cancelReason() {
            return cancelReason.get();
        }

        public void clear() {
            cancelRequested.set(false);
            cancelReason.set("");
            state.set(State.IDLE);
            executingThread.set(null);
            origin.set(Origin.COMMAND);
            openEnded.set(false);
        }

        public boolean matches(UUID candidate) {
            if (botUuid == null) {
                return true;
            }
            return candidate != null && botUuid.equals(candidate);
        }
    }

    private static final UUID GLOBAL_KEY = new UUID(0L, 0L);
    private static final Map<UUID, TaskTicket> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> IN_ASCENT_MODE = new ConcurrentHashMap<>();
    private static final Map<UUID, String> ABORT_LATCH = new ConcurrentHashMap<>();

    private static UUID key(UUID botUuid) {
        return botUuid != null ? botUuid : GLOBAL_KEY;
    }

    public static void setAscentMode(UUID botUuid, boolean inAscent) {
        if (botUuid != null) {
            if (inAscent) {
                IN_ASCENT_MODE.put(botUuid, true);
            } else {
                IN_ASCENT_MODE.remove(botUuid);
            }
        }
    }

    public static boolean isInAscentMode(UUID botUuid) {
        return botUuid != null && IN_ASCENT_MODE.getOrDefault(botUuid, false);
    }

    public static Optional<TaskTicket> beginSkill(String skillName,
                                                  ServerCommandSource source,
                                                  UUID botUuid) {
        if (SERVER_STOPPING) {
            LOGGER.info("Rejecting task '{}' for bot {} because server is stopping", skillName, botUuid);
            return Optional.empty();
        }
        TaskTicket ticket = new TaskTicket("skill:" + skillName, source, botUuid);
        UUID slot = key(botUuid);
        // Starting a new skill explicitly clears any prior stop/abort latch for this bot.
        ABORT_LATCH.remove(slot);
        TaskTicket existing = ACTIVE.putIfAbsent(slot, ticket);
        if (existing != null) {
            if (existing.origin() == Origin.SYSTEM) {
                LOGGER.info("Preempting system task '{}' for bot {} with command skill '{}'",
                        existing.name(), botUuid, skillName);
                abortTicket(existing, "§eSystem recovery interrupted by new command.", false);
                TaskTicket replaced = ACTIVE.putIfAbsent(slot, ticket);
                if (replaced == null) {
                    LOGGER.info("Task '{}' started for bot {}", skillName, botUuid);
                    return Optional.of(ticket);
                }
                existing = replaced;
            }
            System.out.println("[TaskService] beginSkill blocked: bot=" + botUuid
                    + " existing=" + existing.name()
                    + " state=" + existing.state()
                    + " origin=" + existing.origin()
                    + " threadAlive=" + existing.isExecutingThreadAlive());
            LOGGER.warn("Task slot busy for bot {} (existing='{}' state={} origin={} threadAlive={})",
                    botUuid,
                    existing.name(),
                    existing.state(),
                    existing.origin(),
                    existing.isExecutingThreadAlive());
            boolean staleState = existing.state() != State.RUNNING;
            boolean deadThread = !existing.isExecutingThreadAlive();
            if (staleState || deadThread) {
                if (ACTIVE.replace(slot, existing, ticket)) {
                    LOGGER.warn("Replaced stale task '{}' (state={}, threadAlive={}) with '{}'",
                            existing.name(), existing.state(), !deadThread, ticket.name());
                    return Optional.of(ticket);
                }
            }
            return Optional.empty();
        }
        LOGGER.info("Task '{}' started for bot {}", skillName, botUuid);
        return Optional.of(ticket);
    }

    public static Optional<TaskTicket> beginSystemTask(String taskName,
                                                       ServerCommandSource source,
                                                       UUID botUuid) {
        if (SERVER_STOPPING) {
            LOGGER.info("Rejecting system task '{}' for bot {} because server is stopping", taskName, botUuid);
            return Optional.empty();
        }
        TaskTicket ticket = new TaskTicket("system:" + taskName, source, botUuid);
        ticket.setOrigin(Origin.SYSTEM);
        UUID slot = key(botUuid);
        ABORT_LATCH.remove(slot);
        TaskTicket existing = ACTIVE.putIfAbsent(slot, ticket);
        if (existing != null) {
            LOGGER.warn("System task slot busy for bot {} (existing='{}' state={} origin={} threadAlive={})",
                    botUuid,
                    existing.name(),
                    existing.state(),
                    existing.origin(),
                    existing.isExecutingThreadAlive());
            boolean staleState = existing.state() != State.RUNNING;
            boolean deadThread = !existing.isExecutingThreadAlive();
            if (staleState || deadThread) {
                if (ACTIVE.replace(slot, existing, ticket)) {
                    LOGGER.warn("Replaced stale task '{}' (state={}, threadAlive={}) with '{}'",
                            existing.name(), existing.state(), !deadThread, ticket.name());
                    return Optional.of(ticket);
                }
            }
            return Optional.empty();
        }
        LOGGER.info("System task '{}' started for bot {}", taskName, botUuid);
        return Optional.of(ticket);
    }

    /**
     * Starts an ambient (idle hobby) skill task for a bot.
     * <p>
     * Ambient tasks are considered open-ended by default so automation (e.g. sunset return) can
     * safely interrupt them without stepping on commander-issued work.
     */
    public static Optional<TaskTicket> beginAmbientSkill(String skillName,
                                                         ServerCommandSource source,
                                                         UUID botUuid) {
        Optional<TaskTicket> ticketOpt = beginSkill(skillName, source, botUuid);
        ticketOpt.ifPresent(t -> {
            t.setOrigin(Origin.AMBIENT);
            t.setOpenEnded(true);
        });
        return ticketOpt;
    }

    public static Optional<String> getActiveTaskName(UUID botUuid) {
        TaskTicket ticket = ACTIVE.get(key(botUuid));
        return ticket != null ? Optional.ofNullable(ticket.name()) : Optional.empty();
    }

    public static Optional<ActiveTaskInfo> getActiveTaskInfo(UUID botUuid) {
        TaskTicket ticket = ACTIVE.get(key(botUuid));
        if (ticket == null) {
            return Optional.empty();
        }
        return Optional.of(new ActiveTaskInfo(
                ticket.name(),
                ticket.origin(),
                ticket.isOpenEnded(),
                ticket.state(),
                ticket.isCancelRequested(),
                ticket.createdAt()
        ));
    }

    public static boolean hasActiveTask(UUID botUuid) {
        return ACTIVE.containsKey(key(botUuid));
    }

    /**
     * Mark a task as open-ended (eligible to be interrupted by automation like sunset return).
     */
    public static void markOpenEnded(TaskTicket ticket, boolean openEnded) {
        if (ticket != null) {
            ticket.setOpenEnded(openEnded);
        }
    }

    /**
     * Records which thread is executing the active skill for this ticket.
     * This enables /bot stop and server shutdown to interrupt long-running/hung skills.
     */
    public static void attachExecutingThread(TaskTicket ticket, Thread thread) {
        if (ticket == null || thread == null) {
            return;
        }
        ticket.attachExecutingThread(thread);
    }

    public static boolean requestPause(UUID botUuid, String reason) {
        TaskTicket ticket = ACTIVE.get(key(botUuid));
        if (ticket == null) {
            return false;
        }
        if (!ticket.requestCancel(reason)) {
            return false;
        }
        ticket.setState(State.PAUSED);
        dispatchMessage(ticket, reason != null && !reason.isBlank()
                ? reason
                : "§cPausing current task due to nearby threat.");
        LOGGER.info("Task '{}' pause requested: {}", ticket.name(), ticket.cancelReason());
        BotEventHandler.setExternalOverrideActive(false);
        return true;
    }

    public static boolean isAbortRequested(UUID botUuid) {
        if (SERVER_STOPPING) {
            return true;
        }
        if (ABORT_LATCH.containsKey(key(botUuid))) {
            return true;
        }
        TaskTicket ticket = ACTIVE.get(key(botUuid));
        return ticket != null && ticket.isCancelRequested();
    }

    public static Optional<String> getCancelReason(UUID botUuid) {
        String latched = ABORT_LATCH.get(key(botUuid));
        if (latched != null && !latched.isBlank()) {
            return Optional.of(latched);
        }
        TaskTicket ticket = ACTIVE.get(key(botUuid));
        if (ticket == null) {
            return Optional.empty();
        }
        String reason = ticket.cancelReason();
        return reason == null || reason.isBlank() ? Optional.empty() : Optional.of(reason);
    }

    public static void complete(TaskTicket ticket, boolean success) {
        if (ticket == null) {
            return;
        }
        State finalState = success ? State.COMPLETED : State.ABORTED;
        if (ticket.isCancelRequested()) {
            finalState = State.ABORTED;
        }
        ticket.setState(finalState);
        // Only remove if the ACTIVE slot still points at this exact ticket instance.
        // This prevents stale/hung skill threads from accidentally clearing a newer task.
        ACTIVE.remove(key(ticket.botUuid()), ticket);
        LOGGER.info("Task '{}' finished with state {}", ticket.name(), finalState);
        if (SERVER_STOPPING) {
            return;
        }

        // Post-task grace: for command-origin tasks, suppress surface recovery briefly
        // so the commander has time to issue follow-up orders.
        if (ticket.origin() == Origin.COMMAND) {
            ServerCommandSource src = ticket.source();
            MinecraftServer srv = src != null ? src.getServer() : null;
            if (srv != null) {
                BotFleeService.noteTaskCompleted(ticket.botUuid(), srv.getTicks());
                ServerPlayerEntity taskBot = srv.getPlayerManager().getPlayer(ticket.botUuid());
                if (taskBot != null) {
                    BotIdleHobbiesService.snoozeFor(taskBot, 600L);
                    // Post-task return: if enabled and bot is underground, schedule fast-travel
                    // to nearest bed/base/commander after a short grace period.
                    // Skip for underground-centric skills (stripmine, mining) — the bot is
                    // intentionally underground and fast-travel will just spam an artifact
                    // refusal message.
                    if (!isUndergroundSkill(ticket.name())) {
                        schedulePostTaskReturnIfEnabled(srv, taskBot);
                    }
                }
            }
        }

        // After task completion, check if bot is stuck in blocks and needs rescue.
        // IMPORTANT: keep this conservative to avoid fighting legitimate movement around doors/bed wakeups.
        ServerCommandSource source = ticket.source();
        if (source != null) {
            MinecraftServer server = source.getServer();
            if (server != null) {
                ServerPlayerEntity bot = server.getPlayerManager().getPlayer(ticket.botUuid());
                if (bot != null && !bot.isRemoved()) {
                    boolean isSleep = false;
                    try {
                        String name = ticket.name();
                        isSleep = name != null && ("skill:sleep".equalsIgnoreCase(name) || "sleep".equalsIgnoreCase(name));
                    } catch (Throwable ignored) {
                    }

                    // For sleep, skip the post-task safety check entirely; bed wakeups can briefly produce
                    // awkward collision states and we don't want to inject velocity nudges that fight doorway traversal.
                    if (!isSleep) {
                        boolean shouldSafetyCheck = bot.isInsideWall() || BotRescueService.tookRecentObstructDamageWindow(bot);
                        if (shouldSafetyCheck) {
                            LOGGER.info("Scheduling post-task safety check for bot {} after task '{}'", 
                                       bot.getName().getString(), ticket.name());
                            // Run a few ticks later to let vanilla position updates settle.
                            long due = server.getTicks() + 5L;
                            server.send(new ServerTask((int) due, () -> {
                                if (!bot.isRemoved()) {
                                    LOGGER.info("Running post-task safety check for bot {} at position {}", 
                                               bot.getName().getString(), bot.getBlockPos().toShortString());
                                    BotEventHandler.checkAndEscapeSuffocation(bot);
                                } else {
                                    LOGGER.warn("Bot was removed before post-task safety check could run");
                                }
                            }));
                        }
                    }

                    // Quality-of-life: after a completed/aborted sleep command, return to IDLE after a timeout
                    // (but only if idle hobbies are enabled, and only if bot is still holding position).
                    if (isSleep) {
                        BotIdleResumeService.scheduleResumeIfEnabled(server, bot, 200L, "sleep");
                    }
                } else {
                    LOGGER.warn("Could not schedule post-task safety check - bot not found or removed");
                }
            } else {
                LOGGER.warn("Could not schedule post-task safety check - server is null");
            }
        } else {
            LOGGER.warn("Could not schedule post-task safety check - source is null");
        }
    }

    public static void forceAbort(String reason) {
        Collection<TaskTicket> tickets = new ArrayList<>(ACTIVE.values());
        if (tickets.isEmpty()) {
            return;
        }
        tickets.forEach(ticket -> abortTicket(ticket, reason));
        BotEventHandler.setExternalOverrideActive(false);
    }

    public static void forceAbort(UUID botUuid, String reason) {
        // Always latch the abort so survival actions (break-free, surface recovery)
        // see it even when no skill ticket is active.
        ABORT_LATCH.put(key(botUuid), reason == null ? "" : reason);
        TaskTicket ticket = ACTIVE.get(key(botUuid));
        if (ticket != null) {
            abortTicket(ticket, reason);
        }
        BotEventHandler.setExternalOverrideActive(false);
    }

    /**
     * Interrupts an ambient task immediately to make room for a command-issued skill.
     */
    public static boolean interruptAmbientTask(UUID botUuid, String reason) {
        if (botUuid == null) {
            return false;
        }
        TaskTicket ticket = ACTIVE.get(key(botUuid));
        if (ticket == null || ticket.origin() != Origin.AMBIENT) {
            return false;
        }
        abortTicket(ticket, reason);
        ticket.interruptExecutingThread();
        ACTIVE.remove(key(botUuid), ticket);
        LOGGER.info("Ambient task '{}' interrupted for bot {}", ticket.name(), botUuid);
        return true;
    }

    public static Optional<State> currentState() {
        if (ACTIVE.isEmpty()) {
            return Optional.of(State.IDLE);
        }
        return Optional.of(State.RUNNING);
    }

    private static void dispatchMessage(TaskTicket ticket, String message) {
        ServerCommandSource source = ticket.source();
        if (SERVER_STOPPING || source == null || message == null || message.isBlank()) {
            return;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS), message));
    }

    public static void onBotRespawn(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        forceAbort(bot.getUuid(), "§cCurrent task aborted due to respawn.");
    }

    private static void abortTicket(TaskTicket ticket, String reason) {
        abortTicket(ticket, reason, true);
    }

    private static void abortTicket(TaskTicket ticket, String reason, boolean dispatchMessage) {
        ABORT_LATCH.put(key(ticket.botUuid()), reason == null ? "" : reason);
        if (ticket.requestCancel(reason)) {
            ticket.setState(State.ABORTED);
            if (dispatchMessage) {
                dispatchMessage(ticket, reason != null && !reason.isBlank()
                        ? reason
                        : "§cHalting current task.");
            }
            LOGGER.warn("Task '{}' aborted: {}", ticket.name(), ticket.cancelReason());
        }
        // Best-effort: interrupt the executing thread so long-running loops/sleeps unwind promptly.
        ticket.interruptExecutingThread();
        // Force-remove the ticket from ACTIVE immediately so new skills can start.
        // The background thread should check isCancelRequested() and exit gracefully,
        // but we don't want to block new skill starts if pathfinding gets stuck.
        ACTIVE.remove(key(ticket.botUuid()), ticket);
    }

    /**
     * Hard reset of all in-memory task state. Intended for integrated-server world reloads.
     * <p>
     * Note: this does not guarantee background skill threads are fully stopped, but it does:
     * - request cancel for all known tickets
     * - interrupt their executing threads
     * - clear the ACTIVE slot map so new worlds don't inherit stale task locks
     */
    public static void resetAll(String reason) {
        Collection<TaskTicket> tickets = new ArrayList<>(ACTIVE.values());
        for (TaskTicket ticket : tickets) {
            try {
                abortTicket(ticket, reason);
            } catch (Exception ignored) {
            }
        }
        ACTIVE.clear();
        IN_ASCENT_MODE.clear();
        ABORT_LATCH.clear();
    }

    public static void markServerStopping() {
        SERVER_STOPPING = true;
    }

    public static void clearServerStopping() {
        SERVER_STOPPING = false;
    }

    public static boolean isServerStopping() {
        return SERVER_STOPPING;
    }

    // ── Post-task autonomous return ──────────────────────────────────────

    /**
     * Skills that leave the bot intentionally underground.  Post-task return
     * should not fire for these because the bot lacks the navigation artifacts
     * to fast-travel and the resulting refusal message is pure spam.
     */
    private static boolean isUndergroundSkill(String taskName) {
        if (taskName == null) return false;
        String lower = taskName.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("skill:stripmine") || lower.equals("skill:mining");
    }

    /** Grace period before post-task return triggers (5 seconds). */
    private static final long POST_TASK_RETURN_DELAY_TICKS = 100L;

    /**
     * If the "post_task_return" admin permission is enabled for this bot's commander
     * and the bot is underground, schedule a fast-travel to the nearest safe destination
     * after a short grace period.
     */
    private static void schedulePostTaskReturnIfEnabled(MinecraftServer srv, ServerPlayerEntity bot) {
        if (srv == null || bot == null || bot.isRemoved()) return;
        if (!(bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw)) return;

        // Must be underground
        if (BotFleeService.isAtSurface(bot, sw)) return;

        // Check if commander has the permission enabled
        ServerPlayerEntity commander = CompanionCommunicationPolicy.resolveController(srv, bot);
        if (commander == null) return;
        if (!SurvivalRecruitmentService.isAdminPermissionAllowed(commander, "post_task_return")) return;

        UUID botUuid = bot.getUuid();
        LOGGER.info("Post-task return: scheduling for {} in {} ticks",
                bot.getName().getString(), POST_TASK_RETURN_DELAY_TICKS);

        srv.send(new ServerTask((int) (srv.getTicks() + POST_TASK_RETURN_DELAY_TICKS), () -> {
            if (bot.isRemoved()) return;
            // Abort if the bot started a new task or already reached surface
            if (hasActiveTask(botUuid)) return;
            if (bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld world
                    && BotFleeService.isAtSurface(bot, world)) return;

            net.minecraft.util.math.BlockPos destination = resolvePostTaskDestination(bot, srv);
            if (destination == null) {
                LOGGER.info("Post-task return: no valid destination for {}", bot.getName().getString());
                return;
            }

            UUID ownerUuid = commander.getUuid();
            String alias = bot.getName().getString();
            LOGGER.info("Post-task return: {} fast-traveling to {}", alias, destination.toShortString());
            NavigationArtifactService.beginDelayedTravel(
                    srv, bot, alias, destination,
                    bot.getEntityWorld().getRegistryKey(), 40, ownerUuid);
        }));
    }

    /**
     * Find the closest destination among: commander position, last-used bed, nearest base.
     */
    private static net.minecraft.util.math.BlockPos resolvePostTaskDestination(
            ServerPlayerEntity bot, MinecraftServer srv) {
        net.minecraft.util.math.Vec3d origin = new net.minecraft.util.math.Vec3d(bot.getX(), bot.getY(), bot.getZ());
        net.minecraft.util.math.BlockPos closest = null;
        double closestSq = Double.MAX_VALUE;

        // Commander position (same dimension only)
        ServerPlayerEntity commander = CompanionCommunicationPolicy.resolveController(srv, bot);
        if (commander != null && commander.getEntityWorld() == bot.getEntityWorld()) {
            double sq = origin.squaredDistanceTo(new net.minecraft.util.math.Vec3d(
                    commander.getX(), commander.getY(), commander.getZ()));
            if (sq < closestSq) {
                closestSq = sq;
                closest = commander.getBlockPos();
            }
        }

        // Home target: preferred base → last bed / nearest base (whichever closer)
        java.util.Optional<net.minecraft.util.math.BlockPos> home = BotHomeService.resolveHomeTarget(bot);
        if (home.isPresent()) {
            double sq = origin.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(home.get()));
            if (sq < closestSq) {
                closest = home.get();
            }
        }

        return closest;
    }
}
