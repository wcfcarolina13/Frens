package net.wcfcarolina13.GameAI.services;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.network.ResumeDecisionPayload;

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
    /** Bots a threat paused, mapped to the wall-clock time of the pause (see {@link ResumeFreshnessPolicy}). */
    private static final Map<UUID, Long> AUTO_RESUME_PENDING = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong EXECUTION_IDS = new java.util.concurrent.atomic.AtomicLong();
    private static final Set<UUID> RESUME_INTENT = ConcurrentHashMap.newKeySet();
    /** Bots whose last pending skill was paused for a protected-zone refusal — Resume should grant a 60s zone override. */
    private static final Set<UUID> ZONE_OVERRIDE_PENDING = ConcurrentHashMap.newKeySet();
    private static final long ZONE_OVERRIDE_DURATION_MS = 60_000L;

    // ── Sunrise resume: generic skill resume after sunset interruption ────
    private static final long SUNRISE_RESUME_EXPIRY_TICKS = 24000L; // 1 in-game day
    private static final Map<UUID, SunriseResumeRecord> SUNRISE_RESUME_BY_BOT = new ConcurrentHashMap<>();

    public record SunriseResumeRecord(
            UUID botUuid,
            String skillName,
            String rawArgs,
            BlockPos interruptionPos,
            boolean shelteredInPlace,
            long savedTick
    ) {}

    private SkillResumeService() {}

    /**
     * Records a {@code /bot skill} start as the bot's resumable task.
     *
     * @return this run's id; pass it to {@link #handleCompletion} when the run finishes
     */
    public static long recordExecution(ServerPlayerEntity bot, String skillName, String rawArgs, ServerCommandSource source) {
        UUID botUuid = bot.getUuid();
        PendingSkill prior = LAST_SKILL_BY_BOT.get(botUuid);
        if (prior != null) {
            notifyDecision(prior, false);
        }

        long executionId = EXECUTION_IDS.incrementAndGet();
        PendingSkill pending = new PendingSkill(botUuid, bot.getGameProfile().name(), skillName, rawArgs, source, executionId);
        LAST_SKILL_BY_BOT.put(botUuid, pending);
        AWAITING_DECISION.remove(botUuid);
        AUTO_RESUME_PENDING.remove(botUuid);
        PENDING_BY_RESPONDER.values().removeIf(ps -> ps.botUuid().equals(botUuid));
        return executionId;
    }

    /**
     * A {@code /bot skill} run finished (any outcome). Drops the bot's resume record unless something
     * still means to resume it: a threat pause waiting for hostiles to clear, a continue? prompt, or a
     * sunset stop held for sunrise. Success does not matter: a run the threat paused keeps its record
     * whatever it returned, and a run that ended on its own (done, failed, refused) leaves nothing to
     * resume. Only the run that owns the record clears it, so a run that unwinds after a newer one
     * started (or after it re-recorded itself at sunset) changes nothing. Never touches the sunrise
     * record itself.
     */
    public static void handleCompletion(UUID botUuid, long executionId, boolean success) {
        if (botUuid == null) {
            return;
        }
        PendingSkill current = LAST_SKILL_BY_BOT.get(botUuid);
        boolean sameRun = current != null && current.executionId() == executionId;
        if (!ResumeFreshnessPolicy.shouldClearOnCompletion(sameRun, AUTO_RESUME_PENDING.containsKey(botUuid),
                isAwaiting(botUuid), isHeldForSunrise(botUuid))) {
            return;
        }
        LAST_SKILL_BY_BOT.remove(botUuid, current);
        AWAITING_DECISION.remove(botUuid);
        ZONE_OVERRIDE_PENDING.remove(botUuid);
        PENDING_BY_RESPONDER.values().removeIf(ps -> ps.botUuid().equals(botUuid));
        LOGGER.debug("Cleared resume record for {} after '{}' finished (success={})",
                current.alias(), current.skillName(), success);
    }

    private static boolean isHeldForSunrise(UUID botUuid) {
        return SUNRISE_RESUME_BY_BOT.containsKey(botUuid)
                || HuntSessionService.hasSession(botUuid)
                || FishingSessionService.hasSession(botUuid);
    }

    public static void clear(UUID botUuid) {
        if (botUuid == null) {
            return;
        }
        LAST_SKILL_BY_BOT.remove(botUuid);
        AWAITING_DECISION.remove(botUuid);
        AUTO_RESUME_PENDING.remove(botUuid);
        ZONE_OVERRIDE_PENDING.remove(botUuid);
        ProtectedZoneOverrideService.clearOverride(botUuid);
        PENDING_BY_RESPONDER.values().removeIf(ps -> ps.botUuid().equals(botUuid));
        // Any explicit intent-reset (/bot stop, /bot follow) should also drop the
        // pending sunrise-resume record. Without this, a fishing skill that aborts
        // at sunset persists "resume tomorrow" state that the next sunrise auto-fires
        // even after the player has clearly taken manual control.
        SUNRISE_RESUME_BY_BOT.remove(botUuid);
    }

    public static boolean isAwaiting(UUID botUuid) {
        return botUuid != null && Boolean.TRUE.equals(AWAITING_DECISION.get(botUuid));
    }

    public static boolean hasResumeIntent(UUID botUuid) {
        return botUuid != null && RESUME_INTENT.contains(botUuid);
    }

    public static void handleDeath(ServerPlayerEntity bot) {
        clearSunriseResume(bot.getUuid());
        PendingSkill pending = LAST_SKILL_BY_BOT.get(bot.getUuid());
        if (pending == null) {
            return;
        }
        AWAITING_DECISION.put(bot.getUuid(), Boolean.TRUE);
        Object expectedResponderKey = responderKey(pending.source());
        PENDING_BY_RESPONDER.put((Object) expectedResponderKey, pending);

        TaskService.forceAbort(bot.getUuid(), "§cSkill paused: bot died.");

        ChatUtils.sendChatMessages(bot.getCommandSource().withSilent().withPermissions(net.wcfcarolina13.Frens.OPERATOR_PERMISSIONS),
                "I died. Should I continue with the last job? (yes/no)");
        ChatUtils.sendSystemMessage(pending.source(),
                pending.alias() + " died while running '" + pending.skillName() + "'. Reply with 'yes' or 'no' in chat.");
        notifyDecision(pending, true);
    }

    /**
     * Chat hook for the post-death "continue? (yes/no)" prompt.
     *
     * @return whether this line answered a pending prompt (the skill was resumed or stood down);
     *     the caller still lets the line through to other chat handlers
     */
    public static boolean handleChat(ServerPlayerEntity sender, String message) {
        if (sender == null || message == null) {
            return false;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("yes") && !normalized.equals("no")) {
            return false;
        }
        Object key = responderKey(sender.getCommandSource());
        PendingSkill pending = PENDING_BY_RESPONDER.get(key);
        if (pending == null) {
            return false;
        }
        if (normalized.equals("yes")) {
            resume(pending, false, sender.getCommandSource());
        } else {
            ChatUtils.sendSystemMessage(pending.source(), "Okay, " + pending.alias() + " will stand down.");
            notifyDecision(pending, false);
        }
        clear(pending.botUuid());
        return true;
    }

    /**
     * Arms the auto-resume of the bot's recorded task with no check of what is running. For
     * callers that are not pausing a task (sunset hunt/fishing, the break-free after a tactical
     * shelter). A threat that pauses a task goes through {@link #pauseWithAutoResume} instead.
     */
    public static void requestAutoResume(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        if (uuid == null || !LAST_SKILL_BY_BOT.containsKey(uuid)) {
            return;
        }
        AUTO_RESUME_PENDING.put(uuid, System.currentTimeMillis());
        AWAITING_DECISION.remove(uuid);
    }

    /**
     * A threat pauses the bot's task and, only if the pause stopped it, asks for the task to be
     * auto-resumed once hostiles clear.
     * <p>
     * The resume is armed before {@code pause} runs: the skill's worker can reach
     * {@link #handleCompletion} the moment the pause lands, and an unarmed record would be cleared
     * there before a resume request made afterwards. It is armed only when the task being paused is
     * the recorded {@code /bot skill} (not a hobby, not some other task), so a pause of an unrelated
     * task cannot re-arm an old record. If the pause is refused the arm is undone, restoring any
     * earlier request; if the run already finished in that window, the record it left for this arm
     * is dropped as {@link #handleCompletion} would have.
     *
     * @param pause requests the pause (normally {@code SkillManager.requestSkillPause}); true when it stopped a task
     * @return what {@code pause} returned
     */
    public static boolean pauseWithAutoResume(ServerPlayerEntity bot, java.util.function.BooleanSupplier pause) {
        UUID uuid = bot != null ? bot.getUuid() : null;
        PendingSkill record = uuid != null ? LAST_SKILL_BY_BOT.get(uuid) : null;
        TaskService.ActiveTaskInfo active = uuid != null ? TaskService.getActiveTaskInfo(uuid).orElse(null) : null;
        boolean owns = record != null && active != null && ResumeFreshnessPolicy.pausedTaskOwnsRecord(
                active.name(), active.origin() == TaskService.Origin.AMBIENT, record.skillName());
        if (!owns) {
            return pause.getAsBoolean();
        }
        long armedAt = System.currentTimeMillis();
        Long previous = AUTO_RESUME_PENDING.put(uuid, armedAt);
        boolean paused = pause.getAsBoolean();
        if (paused) {
            AWAITING_DECISION.remove(uuid);
            return true;
        }
        AUTO_RESUME_PENDING.compute(uuid, (k, v) -> v != null && v == armedAt ? previous : v);
        if (previous == null && TaskService.getActiveTaskInfo(uuid).isEmpty()
                && ResumeFreshnessPolicy.shouldClearOnCompletion(true, false, isAwaiting(uuid), isHeldForSunrise(uuid))
                && LAST_SKILL_BY_BOT.remove(uuid, record)) {
            // The run finished between the arm and the pause and kept its record for this arm.
            ZONE_OVERRIDE_PENDING.remove(uuid);
            PENDING_BY_RESPONDER.values().removeIf(ps -> ps.botUuid().equals(uuid));
        }
        return false;
    }

    /**
     * Sunrise path (hunt/fishing sessions): resumes the pending task however long ago it stopped,
     * since a night is longer than the threat window.
     */
    public static void tryAutoResume(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        if (AUTO_RESUME_PENDING.remove(uuid) == null) {
            return;
        }
        PendingSkill pending = LAST_SKILL_BY_BOT.get(uuid);
        if (pending == null) {
            return;
        }
        resume(pending, true, pending.source());
        clear(uuid);
    }

    /**
     * Hostiles-cleared path: resumes the task a threat paused, but only within
     * {@link ResumeFreshnessPolicy#AUTO_RESUME_WINDOW_MS} of the pause. A stale request is dropped
     * (logged once) together with its task record, so a later threat pausing some other task cannot
     * re-arm it; the record stays only while the player is being asked about it. A request a sunset
     * stop holds for sunrise is left to the sunrise path, and the sunrise record and zone override
     * are never touched here.
     */
    public static void tryAutoResumeAfterThreat(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        Long pausedAtMs = uuid != null ? AUTO_RESUME_PENDING.get(uuid) : null;
        if (pausedAtMs == null) {
            return;
        }
        long now = System.currentTimeMillis();
        ResumeFreshnessPolicy.ThreatClearedAction action = ResumeFreshnessPolicy.onThreatCleared(
                pausedAtMs, now, ResumeFreshnessPolicy.AUTO_RESUME_WINDOW_MS,
                isHeldForSunrise(uuid), isAwaiting(uuid));
        switch (action) {
            case RESUME -> tryAutoResume(bot);
            case LEAVE_FOR_SUNRISE -> { }
            case DROP_REQUEST_KEEP_RECORD, DROP_REQUEST_AND_RECORD -> {
                if (!AUTO_RESUME_PENDING.remove(uuid, pausedAtMs)) {
                    return;
                }
                PendingSkill pending = LAST_SKILL_BY_BOT.get(uuid);
                boolean dropRecord = action == ResumeFreshnessPolicy.ThreatClearedAction.DROP_REQUEST_AND_RECORD
                        && pending != null && LAST_SKILL_BY_BOT.remove(uuid, pending);
                LOGGER.info("Not auto-resuming '{}' for {}: paused {} s ago, past the {} s window{}",
                        pending != null ? pending.skillName() : "?",
                        pending != null ? pending.alias() : uuid,
                        (now - pausedAtMs) / 1000L,
                        ResumeFreshnessPolicy.AUTO_RESUME_WINDOW_MS / 1000L,
                        dropRecord ? "; dropped its resume record" : "");
            }
        }
    }

    public static void saveSunriseResume(UUID botUuid, String skillName, String rawArgs,
                                          BlockPos interruptionPos, boolean shelteredInPlace, long serverTick) {
        if (botUuid == null || skillName == null) return;
        SUNRISE_RESUME_BY_BOT.put(botUuid, new SunriseResumeRecord(
                botUuid, skillName, rawArgs, interruptionPos, shelteredInPlace, serverTick));
        LOGGER.info("Saved sunrise resume for {}: skill={} pos={} sheltered={}",
                botUuid, skillName, interruptionPos != null ? interruptionPos.toShortString() : "null", shelteredInPlace);
    }

    public static SunriseResumeRecord getSunriseResume(UUID botUuid, long currentTick) {
        if (botUuid == null) return null;
        SunriseResumeRecord rec = SUNRISE_RESUME_BY_BOT.get(botUuid);
        if (rec == null) return null;
        if (currentTick - rec.savedTick() > SUNRISE_RESUME_EXPIRY_TICKS) {
            SUNRISE_RESUME_BY_BOT.remove(botUuid);
            return null;
        }
        return rec;
    }

    public static void clearSunriseResume(UUID botUuid) {
        if (botUuid != null) {
            SUNRISE_RESUME_BY_BOT.remove(botUuid);
        }
    }

    public static String getLastRawArgs(UUID botUuid) {
        if (botUuid == null) return null;
        PendingSkill pending = LAST_SKILL_BY_BOT.get(botUuid);
        return pending != null ? pending.rawArgs() : null;
    }

    /**
     * Marks the bot's pending skill as zone-override-eligible: when the user
     * presses Resume next, the resume path will grant a 60 s
     * {@link ProtectedZoneOverrideService} window so the same skill re-runs
     * without being refused by zone protection. Call this immediately
     * alongside {@link #flagManualResume} when a skill is paused specifically
     * because of a protected-zone hazard.
     */
    public static void flagZoneOverridePending(ServerPlayerEntity bot) {
        if (bot == null) return;
        UUID uuid = bot.getUuid();
        if (uuid != null) {
            ZONE_OVERRIDE_PENDING.add(uuid);
        }
    }

    public static void flagManualResume(ServerPlayerEntity bot) {
        if (bot == null) {
            return;
        }
        UUID uuid = bot.getUuid();
        if (uuid == null) {
            return;
        }
        if (!LAST_SKILL_BY_BOT.containsKey(uuid)) {
            return;
        }
        AWAITING_DECISION.put(uuid, Boolean.TRUE);
        PendingSkill pending = LAST_SKILL_BY_BOT.get(uuid);
        if (pending != null) {
            notifyDecision(pending, true);
        }
    }

    public static boolean manualResume(ServerCommandSource requester, UUID botUuid) {
        if (botUuid == null) {
            return false;
        }
        PendingSkill pending = LAST_SKILL_BY_BOT.get(botUuid);
        if (pending == null) {
            return false;
        }
        resume(pending, false, requester != null ? requester : pending.source());
        if (requester != null && requester != pending.source()) {
            ChatUtils.sendSystemMessage(requester, "Resuming '" + pending.skillName() + "' for " + pending.alias() + ".");
        }
        clear(botUuid);
        return true;
    }

    public static void clearAndNotify(UUID botUuid) {
        if (botUuid == null) {
            return;
        }
        PendingSkill pending = LAST_SKILL_BY_BOT.get(botUuid);
        if (pending != null) {
            notifyDecision(pending, false);
        }
        clear(botUuid);
    }

    public static boolean consumeResumeIntent(UUID botUuid) {
        if (botUuid == null) {
            return false;
        }
        return RESUME_INTENT.remove(botUuid);
    }

    public static DebugSnapshot debugForBot(UUID botUuid) {
        if (botUuid == null) {
            return new DebugSnapshot(false, false, false, false);
        }
        return new DebugSnapshot(
                LAST_SKILL_BY_BOT.containsKey(botUuid),
                isAwaiting(botUuid),
                AUTO_RESUME_PENDING.containsKey(botUuid),
                RESUME_INTENT.contains(botUuid)
        );
    }

    private static void resume(PendingSkill pending, boolean autoTriggered, ServerCommandSource commandSource) {
        ServerCommandSource source = commandSource != null ? commandSource : pending.source();
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        notifyDecision(pending, false);
        RESUME_INTENT.add(pending.botUuid());
        // If this pending skill was paused by a protected-zone refusal,
        // grant the bot a 60s zone-override window so the re-run gets through.
        if (ZONE_OVERRIDE_PENDING.remove(pending.botUuid())) {
            ProtectedZoneOverrideService.grantOverride(pending.botUuid(), ZONE_OVERRIDE_DURATION_MS);
            ChatUtils.sendSystemMessage(source, pending.alias()
                    + " will ignore protected-zone refusals for the next 60s.");
        }
        StringBuilder command = new StringBuilder("bot skill ")
                .append(pending.skillName());
        if (pending.rawArgs() != null && !pending.rawArgs().isBlank()) {
            command.append(" ").append(pending.rawArgs());
        }
        command.append(" ").append(pending.alias());
        String commandLine = command.toString();
        server.execute(() -> {
            try {
                ServerCommandSource execSource = source;
                if (execSource.getEntity() instanceof ServerPlayerEntity player) {
                    ServerPlayerEntity refreshed = server.getPlayerManager().getPlayer(player.getUuid());
                    if (refreshed != null) {
                        execSource = refreshed.getCommandSource();
                    }
                }
                if (execSource.getEntity() instanceof ServerPlayerEntity requester
                        && !(requester instanceof net.wcfcarolina13.Entity.createFakePlayer)
                        && !net.wcfcarolina13.Frens.hasBotCommandPermission(execSource)) {
                    // A player without /bot (a non-op owner, a LAN guest): authorise them on the
                    // resolved bot, then run under the per-call grant, synchronously, right here.
                    resumeUnderGrant(server, requester, pending, commandLine);
                    return;
                }
                server.getCommandManager().getDispatcher().execute(commandLine, execSource);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                LOGGER.warn("Failed to resume skill '{}' for {}: {}", pending.skillName(), pending.alias(), e.getMessage());
                RESUME_INTENT.remove(pending.botUuid());
            }
        });
        String reason = autoTriggered ? "Hostiles cleared." : null;
        String prefix = "Resuming '" + pending.skillName() + "' for " + pending.alias() + ".";
        ChatUtils.sendSystemMessage(source, reason == null ? prefix : prefix + " " + reason);
    }

    /**
     * Resume for a player without {@code /bot}. The bot is resolved by UUID server-side and must
     * still carry the name the stored command targets; the player must pass
     * {@link net.wcfcarolina13.network.BotAccessGate#permits} on it (owner, op or host). Server thread
     * only: the grant lives inside {@link net.wcfcarolina13.network.AuthorizedBotDispatch#run}.
     */
    private static void resumeUnderGrant(MinecraftServer server, ServerPlayerEntity requester,
                                         PendingSkill pending, String commandLine) {
        if (requester.isRemoved()) {
            RESUME_INTENT.remove(pending.botUuid());
            return;
        }
        ServerPlayerEntity bot = server.getPlayerManager().getPlayer(pending.botUuid());
        if (!(bot instanceof net.wcfcarolina13.Entity.createFakePlayer) || bot.isRemoved()
                || !bot.getGameProfile().name().equals(pending.alias())) {
            LOGGER.warn("Skill resume for {} refused: bot {} is not online under that name",
                    requester.getName().getString(), pending.alias());
            RESUME_INTENT.remove(pending.botUuid());
            return;
        }
        if (!net.wcfcarolina13.network.BotAccessGate.permits(requester, bot, "skill_resume")) {
            requester.sendMessage(net.minecraft.text.Text.literal("You can only do that for your own bot.")
                    .formatted(net.minecraft.util.Formatting.YELLOW), false);
            RESUME_INTENT.remove(pending.botUuid());
            return;
        }
        if (net.wcfcarolina13.network.AuthorizedBotDispatch.run(requester, commandLine) <= 0) {
            LOGGER.warn("Skill resume '{}' for {} was refused by the dispatcher", pending.skillName(), pending.alias());
            RESUME_INTENT.remove(pending.botUuid());
        }
    }

    private static Object responderKey(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return player.getUuid();
        }
        return CONSOLE_KEY;
    }

    private static void notifyDecision(PendingSkill pending, boolean active) {
        if (pending == null) {
            return;
        }
        ServerCommandSource source = pending.source();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, ResumeDecisionPayload.ID)) {
            return;
        }
        ServerPlayNetworking.send(player, new ResumeDecisionPayload(active, pending.alias()));
    }

    private record PendingSkill(UUID botUuid, String alias, String skillName, String rawArgs, ServerCommandSource source,
                                long executionId) {
        PendingSkill {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(skillName, "skillName");
            Objects.requireNonNull(source, "source");
        }
    }

    public record DebugSnapshot(
            boolean hasPendingSkill,
            boolean awaitingDecision,
            boolean autoResumePending,
            boolean resumeIntent
    ) {
    }
}
