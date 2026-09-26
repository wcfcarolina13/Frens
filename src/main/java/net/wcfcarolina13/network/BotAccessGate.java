package net.wcfcarolina13.network;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.wcfcarolina13.Entity.createFakePlayer;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.services.BotAccessPolicy;
import net.wcfcarolina13.GameAI.services.BotRegistry;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-thread receiver check for client packets that act on one named bot. Gathers the inputs
 * of {@link BotAccessPolicy#authorize} (configured owner, fake player in the bot registry,
 * operator, integrated-server host) and WARNs on a deny, at most once per (sender, action,
 * target) every {@link BotAccessPolicy#DENY_LOG_INTERVAL_MS}.
 */
public final class BotAccessGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-access");

    /** Last deny WARN per {@code senderUuid|action|target}; pruned on every insert. */
    private static final Map<String, Long> LAST_DENY_LOG_MS = new ConcurrentHashMap<>();

    private BotAccessGate() {}

    /**
     * The policy decision for {@code sender} acting on {@code target}, with no logging. Used where
     * the check repeats every tick (a screen handler's {@code canUse}). A null target is not a bot.
     */
    public static BotAccessPolicy.Decision decide(ServerPlayerEntity sender, ServerPlayerEntity target) {
        if (sender == null || target == null) return BotAccessPolicy.Decision.DENY_NOT_BOT;
        boolean registeredBot = target instanceof createFakePlayer && BotRegistry.isRegistered(target.getUuid());
        return BotAccessPolicy.authorize(
                sender.getUuid(),
                registeredBot ? CompanionCommunicationPolicy.resolveOwnerUuid(target) : null,
                registeredBot,
                Frens.isOperator(sender),
                isHost(sender));
    }

    /** True if {@code sender} may do {@code action} on {@code target}; otherwise WARNs (rate-limited). */
    public static boolean permits(ServerPlayerEntity sender, ServerPlayerEntity target, String action) {
        BotAccessPolicy.Decision decision = decide(sender, target);
        if (BotAccessPolicy.allowed(decision)) return true;
        warnDenied(sender, target == null ? null : target.getName().getString(), action, decision.name());
        return false;
    }

    /**
     * Like {@link #permits} for a client-supplied bot name, resolved to an online player by exact
     * name. A null, blank or unknown name is denied.
     */
    public static boolean permitsByName(ServerPlayerEntity sender, String botName, String action) {
        ServerPlayerEntity target = null;
        MinecraftServer server = Frens.serverInstance;
        if (server != null && botName != null && !botName.isBlank()) {
            target = server.getPlayerManager().getPlayer(botName);
        }
        if (target == null) {
            warnDenied(sender, botName, action, "NO_SUCH_BOT");
            return false;
        }
        return permits(sender, target, action);
    }

    /** Whether {@code p} hosts the integrated server (the single-player owner, op or not). */
    public static boolean isHost(ServerPlayerEntity p) {
        MinecraftServer server = Frens.serverInstance;
        if (p == null || server == null) return false;
        return server.isHost(new PlayerConfigEntry(p.getGameProfile()));
    }

    /**
     * Writes {@code [bot-access] denied ...} unless the same (sender, action, target) was logged in
     * the last {@link BotAccessPolicy#DENY_LOG_INTERVAL_MS}. {@code target} is a client string and
     * is sanitised; {@code reason} is a short code.
     */
    public static void warnDenied(ServerPlayerEntity sender, String target, String action, String reason) {
        String safeTarget = BotAccessPolicy.logSafe(target);
        String safeAction = BotAccessPolicy.logSafe(action);
        String senderKey = sender == null ? "null" : sender.getUuidAsString();
        String key = senderKey + "|" + safeAction + "|" + safeTarget;
        long now = System.currentTimeMillis();
        if (!BotAccessPolicy.shouldLogDeny(LAST_DENY_LOG_MS.get(key), now)) return;
        // Entries whose window has passed carry no information; drop them so the map stays bounded.
        LAST_DENY_LOG_MS.entrySet().removeIf(e -> BotAccessPolicy.shouldLogDeny(e.getValue(), now));
        LAST_DENY_LOG_MS.put(key, now);
        LOGGER.warn("[bot-access] denied action={} sender={} target={} reason={}",
                safeAction,
                sender == null ? "null" : BotAccessPolicy.logSafe(sender.getName().getString()),
                safeTarget,
                reason);
    }
}
