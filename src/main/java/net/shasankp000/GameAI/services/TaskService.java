package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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

    private static final AtomicReference<TaskTicket> ACTIVE = new AtomicReference<>();

    public static Optional<TaskTicket> beginSkill(String skillName,
                                                  ServerCommandSource source,
                                                  UUID botUuid) {
        TaskTicket ticket = new TaskTicket("skill:" + skillName, source, botUuid);
        if (!ACTIVE.compareAndSet(null, ticket)) {
            return Optional.empty();
        }
        LOGGER.info("Task '{}' started for bot {}", skillName, botUuid);
        return Optional.of(ticket);
    }

    public static boolean requestPause(UUID botUuid, String reason) {
        TaskTicket ticket = ACTIVE.get();
        if (ticket == null || !ticket.matches(botUuid)) {
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
        TaskTicket ticket = ACTIVE.get();
        return ticket != null && ticket.matches(botUuid) && ticket.isCancelRequested();
    }

    public static Optional<String> getCancelReason(UUID botUuid) {
        TaskTicket ticket = ACTIVE.get();
        if (ticket == null || !ticket.matches(botUuid)) {
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
        ACTIVE.compareAndSet(ticket, null);
        LOGGER.info("Task '{}' finished with state {}", ticket.name(), finalState);
    }

    public static void forceAbort(String reason) {
        TaskTicket ticket = ACTIVE.get();
        if (ticket == null) {
            return;
        }
        if (ticket.requestCancel(reason)) {
            ticket.setState(State.ABORTED);
            dispatchMessage(ticket, reason != null && !reason.isBlank()
                    ? reason
                    : "§cHalting current task.");
            LOGGER.warn("Task '{}' aborted: {}", ticket.name(), ticket.cancelReason());
        }
        ACTIVE.compareAndSet(ticket, null);
        BotEventHandler.setExternalOverrideActive(false);
    }

    public static Optional<State> currentState() {
        TaskTicket ticket = ACTIVE.get();
        return ticket == null ? Optional.of(State.IDLE) : Optional.of(ticket.state());
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
        forceAbort("§cCurrent task aborted due to respawn.");
    }
}

