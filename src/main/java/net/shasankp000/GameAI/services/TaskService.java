package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
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

    public static final class TaskTicket {
        private final String name;
        private final ServerCommandSource source;
        private final UUID botUuid;
        private final Instant createdAt;
        private final AtomicReference<State> state;
        private final AtomicBoolean cancelRequested;
        private final AtomicReference<String> cancelReason;

        TaskTicket(String name, ServerCommandSource source, UUID botUuid) {
            this.name = name;
            this.source = source;
            this.botUuid = botUuid;
            this.createdAt = Instant.now();
            this.state = new AtomicReference<>(State.RUNNING);
            this.cancelRequested = new AtomicBoolean(false);
            this.cancelReason = new AtomicReference<>("");
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
        ACTIVE.remove(key(ticket.botUuid()));
        LOGGER.info("Task '{}' finished with state {}", ticket.name(), finalState);
        
        // After task completion, check if bot is stuck in blocks and needs rescue
        // This is important for mining tasks that may leave bot positioned inside blocks
        ServerCommandSource source = ticket.source();
        if (source != null) {
            MinecraftServer server = source.getServer();
            if (server != null) {
                ServerPlayerEntity bot = server.getPlayerManager().getPlayer(ticket.botUuid());
                if (bot != null && !bot.isRemoved()) {
                    LOGGER.info("Scheduling post-task safety check for bot {} after task '{}'", 
                               bot.getName().getString(), ticket.name());
                    // Schedule check for next tick to ensure task cleanup is complete
                    server.execute(() -> {
                        if (!bot.isRemoved()) {
                            LOGGER.info("Running post-task safety check for bot {} at position {}", 
                                       bot.getName().getString(), bot.getBlockPos().toShortString());
                            BotEventHandler.checkAndEscapeSuffocation(bot);
                        } else {
                            LOGGER.warn("Bot was removed before post-task safety check could run");
                        }
                    });
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
        server.execute(() -> ChatUtils.sendChatMessages(source.withSilent().withMaxLevel(4), message));
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
        // Don't remove ticket from ACTIVE here - let skill detect cancelRequest and finish naturally
        // Ticket will be removed when task completes in finishTask()
    }
}
