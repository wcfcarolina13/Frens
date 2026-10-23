package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped respawn prompts.  When a bot dies while
 * {@code autoRespawnOnDeath == false}, the bot's controller receives a
 * "Respawn now? (yes/no)" chat prompt.  Pending prompts live in memory only —
 * if the session ends without an answer, the bot stays shelved
 * ({@code autoSpawnOnLoad = false}) and the user must {@code /bot spawn} or
 * toggle auto-respawn back ON to bring them back.
 *
 * <p>Complements the existing {@link SkillResumeService} "continue the last
 * job?" prompt, which handles skill resumption after respawn.</p>
 */
public final class BotRespawnPromptService {

    private static final Logger LOGGER = LoggerFactory.getLogger("respawn-prompt");

    /** Alias (lower-case) → pending prompt. */
    private static final Map<String, Pending> BY_ALIAS = new ConcurrentHashMap<>();
    /** Owner UUID → alias (lower-case) for fast chat routing. */
    private static final Map<UUID, String> BY_OWNER = new ConcurrentHashMap<>();

    public record Pending(String alias, UUID ownerUuid, String spawnMode) {}

    private BotRespawnPromptService() {}

    /**
     * Called from the {@code AFTER_DEATH} handler when {@code autoRespawnOnDeath}
     * is {@code false}.  Stores a pending prompt keyed by the bot's alias and
     * (if resolvable) sends a chat message to the controller.  The bot's
     * {@code autoSpawnOnLoad} flag is also flipped to {@code false} so that
     * if the user doesn't answer, the bot stays shelved across sessions.
     */
    public static void promptForRespawn(MinecraftServer server, ServerPlayerEntity bot) {
        if (server == null || bot == null) return;
        String alias = bot.getName().getString();
        String aliasKey = alias.toLowerCase(Locale.ROOT);
        String worldKey = BotWorldStateService.currentWorldKey(server);

        // Shelve until user answers or toggles autoRespawn on.
        ManualConfig.BotControlSettings ctrl = Frens.CONFIG != null
                ? Frens.CONFIG.getOrCreateBotControl(alias, worldKey) : null;
        String mode = "admin";
        if (ctrl != null) {
            ctrl.setAutoSpawnOnLoad(false);
            String configured = ctrl.getSpawnMode();
            if (configured != null && !configured.isBlank()) {
                mode = configured;
            }
        }
        if (Frens.CONFIG != null) {
            Frens.CONFIG.save();
        }

        ServerPlayerEntity owner = CompanionCommunicationPolicy.resolveController(server, bot);

        // Replace any stale prompt for the same bot.
        Pending existing = BY_ALIAS.remove(aliasKey);
        if (existing != null && existing.ownerUuid() != null) {
            BY_OWNER.remove(existing.ownerUuid(), aliasKey);
        }

        if (owner != null) {
            UUID ownerUuid = owner.getUuid();
            BY_ALIAS.put(aliasKey, new Pending(alias, ownerUuid, mode));
            BY_OWNER.put(ownerUuid, aliasKey);
            ChatUtils.sendSystemMessage(owner.getCommandSource(),
                    alias + " died. Respawn now? (yes/no)");
        } else {
            BY_ALIAS.put(aliasKey, new Pending(alias, null, mode));
            LOGGER.info("Respawn prompt stored for {} but no controller online to notify.", alias);
        }
    }

    /**
     * Chat hook.  Returns {@code true} if the message was consumed as a
     * respawn-prompt reply.
     */
    public static boolean handleChat(ServerPlayerEntity sender, String message) {
        if (sender == null || message == null) return false;
        String aliasKey = BY_OWNER.get(sender.getUuid());
        if (aliasKey == null) return false;
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("yes") && !normalized.equals("no")) return false;

        Pending pending = BY_ALIAS.get(aliasKey);
        if (pending == null) {
            BY_OWNER.remove(sender.getUuid(), aliasKey);
            return false;
        }
        MinecraftServer server = sender.getCommandSource().getServer();
        if (!isReplyAuthorized(server, sender, pending)) {
            refuseReply(sender, pending, aliasKey);
            return true;
        }

        if (normalized.equals("yes")) {
            runReplySpawn(server, sender, pending, aliasKey);
        } else if (BY_ALIAS.remove(aliasKey, pending)) {
            BY_OWNER.remove(sender.getUuid(), aliasKey);
            ChatUtils.sendSystemMessage(sender.getCommandSource(),
                    pending.alias() + " will stand down. Use /bot spawn " + pending.alias()
                            + " to bring them back, or toggle Auto Respawn in Bot Controls.");
        } else {
            ChatUtils.sendSystemMessage(sender.getCommandSource(),
                    "That respawn request changed. Please use Bot Controls.");
        }
        return true;
    }

    private static boolean isReplyAuthorized(MinecraftServer server, ServerPlayerEntity sender, Pending pending) {
        return server != null && !sender.isRemoved()
                && RespawnReplyPolicy.mayAnswer(sender.getUuid(), pending.ownerUuid(),
                        CompanionCommunicationPolicy.resolveControllerUuidForAlias(server, pending.alias()),
                        net.wcfcarolina13.network.BotAccessGate.isServerAdmin(sender));
    }

    private static void refuseReply(ServerPlayerEntity sender, Pending pending, String aliasKey) {
        BY_OWNER.remove(sender.getUuid(), aliasKey);
        ChatUtils.sendSystemMessage(sender.getCommandSource(),
                "You no longer have permission to answer for " + pending.alias() + ". The respawn request was kept.");
    }

    private static void runReplySpawn(MinecraftServer server, ServerPlayerEntity sender,
                                      Pending pending, String aliasKey) {
        server.execute(() -> {
            if (BY_ALIAS.get(aliasKey) != pending) return;
            if (!isReplyAuthorized(server, sender, pending)) {
                refuseReply(sender, pending, aliasKey);
                return;
            }
            ChatUtils.sendSystemMessage(sender.getCommandSource(), "Respawning " + pending.alias() + ".");
            if (executeSpawn(server, pending.alias(), pending.spawnMode()) <= 0) {
                // The spawn command may clear the prompt before it later refuses the spawn.
                Pending newer = BY_ALIAS.putIfAbsent(aliasKey, pending);
                if (newer == null || newer == pending) {
                    BY_OWNER.putIfAbsent(sender.getUuid(), aliasKey);
                }
                ChatUtils.sendSystemMessage(sender.getCommandSource(),
                        "Couldn't respawn " + pending.alias() + ". The respawn request is still pending.");
            } else if (BY_ALIAS.remove(aliasKey, pending)) {
                BY_OWNER.remove(sender.getUuid(), aliasKey);
            }
        });
    }

    /** Clear a pending prompt by alias (no chat feedback). */
    public static void clear(String alias) {
        if (alias == null) return;
        String aliasKey = alias.toLowerCase(Locale.ROOT);
        Pending p = BY_ALIAS.remove(aliasKey);
        if (p != null && p.ownerUuid() != null) {
            BY_OWNER.remove(p.ownerUuid(), aliasKey);
        }
    }

    /** Clear all prompts (used on SERVER_STOPPING). */
    public static void clearAll() {
        BY_ALIAS.clear();
        BY_OWNER.clear();
    }

    /**
     * Called from the Bot Controls save path when {@code autoRespawnOnDeath}
     * transitions from {@code false} to {@code true}.  If the bot isn't
     * currently active, spawn it using the stored pending-prompt spawn mode
     * (or the configured spawn mode as fallback).
     */
    public static void onAutoRespawnEnabled(MinecraftServer server, String alias) {
        if (server == null || alias == null || alias.isBlank()) return;
        if (server.getPlayerManager().getPlayer(alias) != null) {
            // Already active — just clear any stale prompt.
            clear(alias);
            return;
        }

        String mode;
        Pending pending = BY_ALIAS.get(alias.toLowerCase(Locale.ROOT));
        if (pending != null) {
            mode = pending.spawnMode();
        } else {
            String worldKey = BotWorldStateService.currentWorldKey(server);
            ManualConfig.BotControlSettings ctrl = Frens.CONFIG != null
                    ? Frens.CONFIG.getEffectiveBotControl(alias, worldKey) : null;
            mode = ctrl != null && ctrl.getSpawnMode() != null ? ctrl.getSpawnMode() : "admin";
        }
        clear(alias);
        runSpawn(server, alias, mode);
    }

    private static void runSpawn(MinecraftServer server, String alias, String mode) {
        server.execute(() -> executeSpawn(server, alias, mode));
    }

    private static int executeSpawn(MinecraftServer server, String alias, String mode) {
        try {
            ServerCommandSource src = server.getCommandSource()
                    .withSilent()
                    .withPermissions(Frens.OPERATOR_PERMISSIONS);
            return server.getCommandManager().getDispatcher()
                    .execute("bot spawn " + alias + " " + mode, src);
        } catch (Throwable t) {
            LOGGER.warn("Respawn-prompt spawn failed for {}: {}", alias, t.getMessage());
            return 0;
        }
    }

}
