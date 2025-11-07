package net.shasankp000.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.ChatUtils.ChatUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SkillResumeService {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("skill-resume");
    private static final Object CONSOLE_KEY = new Object();

    private static final Map<UUID, PendingSkill> LAST_SKILL_BY_BOT = new ConcurrentHashMap<>();
    private static final Map<Object, PendingSkill> PENDING_BY_RESPONDER = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> AWAITING_DECISION = new ConcurrentHashMap<>();
    private static final Set<UUID> AUTO_RESUME_PENDING = ConcurrentHashMap.newKeySet();

    private SkillResumeService() {}

    public static void recordExecution(ServerPlayerEntity bot, String skillName, String rawArgs, ServerCommandSource source) {
        PendingSkill pending = new PendingSkill(bot.getUuid(), bot.getGameProfile().name(), skillName, rawArgs, source);
        LAST_SKILL_BY_BOT.put(bot.getUuid(), pending);
        AWAITING_DECISION.remove(bot.getUuid());
        PENDING_BY_RESPONDER.values().removeIf(ps -> ps.botUuid().equals(bot.getUuid()));
    }

    public static void handleCompletion(UUID botUuid, boolean success) {
        if (success || (!isAwaiting(botUuid) && !AUTO_RESUME_PENDING.contains(botUuid))) {
            clear(botUuid);
        }
    }

    public static void clear(UUID botUuid) {
        if (botUuid == null) {
            return;
        }
        LAST_SKILL_BY_BOT.remove(botUuid);
        AWAITING_DECISION.remove(botUuid);
        AUTO_RESUME_PENDING.remove(botUuid);
        PENDING_BY_RESPONDER.values().removeIf(ps -> ps.botUuid().equals(botUuid));
    }

    public static boolean isAwaiting(UUID botUuid) {
        return botUuid != null && Boolean.TRUE.equals(AWAITING_DECISION.get(botUuid));
    }

    public static void handleDeath(ServerPlayerEntity bot) {
        PendingSkill pending = LAST_SKILL_BY_BOT.get(bot.getUuid());
        if (pending == null) {
            return;
        }
        AWAITING_DECISION.put(bot.getUuid(), Boolean.TRUE);
        Object expectedResponderKey = responderKey(pending.source());
        PENDING_BY_RESPONDER.put((Object) expectedResponderKey, pending);

        TaskService.forceAbort(bot.getUuid(), "§cSkill paused: bot died.");

        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withMaxLevel(4),
                "I died. Should I continue with the last job? (yes/no)");
        ChatUtils.sendSystemMessage(pending.source(),
                pending.alias() + " died while running '" + pending.skillName() + "'. Reply with 'yes' or 'no' in chat.");
    }

    public static void handleChat(ServerPlayerEntity sender, String message) {
        if (sender == null || message == null) {
            return;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("yes") && !normalized.equals("no")) {
            return;
        }
        Object key = responderKey(sender.getCommandSource());
        PendingSkill pending = PENDING_BY_RESPONDER.get(key);
        if (pending == null) {
            return;
        }
        if (normalized.equals("yes")) {
            resume(pending, false);
        } else {
            ChatUtils.sendSystemMessage(pending.source(), "Okay, " + pending.alias() + " will stand down.");
        }
        clear(pending.botUuid());
    }

    public static void requestAutoResume(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        if (uuid == null || !LAST_SKILL_BY_BOT.containsKey(uuid)) {
            return;
        }
        AUTO_RESUME_PENDING.add(uuid);
        AWAITING_DECISION.remove(uuid);
    }

    public static void tryAutoResume(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        if (!AUTO_RESUME_PENDING.remove(uuid)) {
            return;
        }
        PendingSkill pending = LAST_SKILL_BY_BOT.get(uuid);
        if (pending == null) {
            return;
        }
        resume(pending, true);
        clear(uuid);
    }

    private static void resume(PendingSkill pending, boolean autoTriggered) {
        ServerCommandSource source = pending.source();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        StringBuilder command = new StringBuilder("/bot skill ")
                .append(pending.skillName());
        if (pending.rawArgs() != null && !pending.rawArgs().isBlank()) {
            command.append(" ").append(pending.rawArgs());
        }
        command.append(" ").append(pending.alias());
        String commandLine = command.toString();
        server.execute(() -> {
            try {
                server.getCommandManager().getDispatcher().execute(commandLine, source);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                LOGGER.warn("Failed to resume skill '{}' for {}: {}", pending.skillName(), pending.alias(), e.getMessage());
            }
        });
        String reason = autoTriggered ? "Hostiles cleared." : null;
        String prefix = "Resuming '" + pending.skillName() + "' for " + pending.alias() + ".";
        ChatUtils.sendSystemMessage(source, reason == null ? prefix : prefix + " " + reason);
    }

    private static Object responderKey(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return player.getUuid();
        }
        return CONSOLE_KEY;
    }

    private record PendingSkill(UUID botUuid, String alias, String skillName, String rawArgs, ServerCommandSource source) {
        PendingSkill {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(skillName, "skillName");
            Objects.requireNonNull(source, "source");
        }
    }
}
