package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotEventHandler;
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
        TaskTicket ticket = new TaskTicket("skill:" + skillName, source, botUuid);
        UUID slot = key(botUuid);
        TaskTicket existing = ACTIVE.putIfAbsent(slot, ticket);
        if (existing != null) {
            return Optional.empty();
        }
        LOGGER.info("Task '{}' started for bot {}", skillName, botUuid);
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
        TaskTicket ticket = ACTIVE.get(key(botUuid));
        return ticket != null && ticket.isCancelRequested();
    }

    public static Optional<String> getCancelReason(UUID botUuid) {
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
        TaskTicket ticket = ACTIVE.get(key(botUuid));
        if (ticket == null) {
            return;
        }
        abortTicket(ticket, reason);
        BotEventHandler.setExternalOverrideActive(false);
    }

    public static Optional<State> currentState() {
        if (ACTIVE.isEmpty()) {
            return Optional.of(State.IDLE);
        }
        return Optional.of(State.RUNNING);
    }

    private static void dispatchMessage(TaskTicket ticket, String message) {
        ServerCommandSource source = ticket.source();
        if (source == null || message == null || message.isBlank()) {
            return;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> ChatUtils.sendChatMessages(source.withSilent().withPermissions(net.shasankp000.AIPlayer.OPERATOR_PERMISSIONS), message));
    }

    public static void onBotRespawn(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        forceAbort(bot.getUuid(), "§cCurrent task aborted due to respawn.");
    }

    private static void abortTicket(TaskTicket ticket, String reason) {
        if (ticket.requestCancel(reason)) {
            ticket.setState(State.ABORTED);
            dispatchMessage(ticket, reason != null && !reason.isBlank()
                    ? reason
                    : "§cHalting current task.");
            LOGGER.warn("Task '{}' aborted: {}", ticket.name(), ticket.cancelReason());
        }
        // Best-effort: interrupt the executing thread so long-running loops/sleeps unwind promptly.
        ticket.interruptExecutingThread();
        // Don't remove ticket from ACTIVE here - let skill detect cancelRequest and finish naturally
        // Ticket will be removed when task completes in finishTask()
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
    }
}
