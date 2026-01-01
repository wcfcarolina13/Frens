package net.shasankp000.GameAI.llm;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.ChatUtils.ChatUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LLMStatusReporter {

    private LLMStatusReporter() {
    }

    public static void respond(ServerCommandSource botSource, UUID commanderId) {
        ServerPlayerEntity bot = botSource.getPlayer();
        if (bot == null) {
            return;
        }
        StringBuilder status = new StringBuilder();
        status.append(personaLead(botSource, bot));

        LLMJobTracker.getActiveJob(bot.getUuid()).ifPresentOrElse(job -> {
            status.append("I'm ").append(job.description());
            Duration elapsed = Duration.between(job.startedAt(), Instant.now());
            status.append(" (").append(formatDuration(elapsed)).append(" so far).");
        }, () -> {
            status.append("I'm idle right now.");
            LLMJobTracker.getLastResult(bot.getUuid()).ifPresent(last -> status.append(" Last job: ").append(last));
            if (LLMActionQueue.size(bot.getUuid()) > 0) {
                status.append(" I have ").append(LLMActionQueue.size(bot.getUuid())).append(" queued request(s).");
            }
        });

        if (LLMJobTracker.getActiveJob(bot.getUuid()).isPresent()) {
            List<LLMActionQueue.QueuedCommand> queued = LLMActionQueue.snapshot(bot.getUuid());
            if (!queued.isEmpty()) {
                status.append(" Up next: ").append(describeQueue(queued));
            }
        }

        ChatUtils.sendChatMessages(botSource.withSilent(), status.toString().trim());
    }

    private static String personaLead(ServerCommandSource source, ServerPlayerEntity bot) {
        String worldKey = LLMOrchestrator.computeWorldKey(source.getServer(), bot);
        MemoryStore store = LLMOrchestrator.getMemoryStore();
        MemoryStore.BotProfile profile = store.getOrCreateProfile(worldKey, bot);
        StringBuilder lead = new StringBuilder();
        lead.append("As ").append(bot.getName().getString()).append(" (").append(profile.persona()).append(")");
        if (!profile.quirks().isEmpty()) {
            lead.append(" who ").append(profile.quirks().get(0));
        }
        lead.append(", ");
        return lead.toString();
    }

    private static String describeQueue(List<LLMActionQueue.QueuedCommand> queued) {
        return queued.stream()
                .map(LLMActionQueue.QueuedCommand::message)
                .limit(2)
                .reduce((a, b) -> a + " → " + b)
                .orElse("more work ahead");
    }

    private static String formatDuration(Duration duration) {
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).getSeconds();
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
