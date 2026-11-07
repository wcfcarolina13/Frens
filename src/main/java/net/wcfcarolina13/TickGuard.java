package net.wcfcarolina13;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps server-thread event handlers so a RuntimeException in one of them (a bot in an odd state, a
 * removed entity, a null field) is logged and the server keeps ticking. Fabric does not catch these:
 * one escaping END_SERVER_TICK or a damage/death callback crashes the whole server. Errors such as
 * OutOfMemoryError still propagate. A failing handler skips the rest of its own work for that tick
 * only.
 */
public final class TickGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("frens-tick-guard");
    private static final TickGuardLogPolicy LOG = new TickGuardLogPolicy();

    private TickGuard() {
    }

    public static ServerTickEvents.EndTick endTick(String name, ServerTickEvents.EndTick body) {
        return server -> {
            try {
                body.onEndTick(server);
            } catch (RuntimeException e) {
                report(name, e);
            }
        };
    }

    /** On failure the damage goes ahead as vanilla would (true). */
    public static ServerLivingEntityEvents.AllowDamage allowDamage(String name, ServerLivingEntityEvents.AllowDamage body) {
        return (entity, source, amount) -> {
            try {
                return body.allowDamage(entity, source, amount);
            } catch (RuntimeException e) {
                report(name, e);
                return true;
            }
        };
    }

    public static ServerLivingEntityEvents.AfterDamage afterDamage(String name, ServerLivingEntityEvents.AfterDamage body) {
        return (entity, source, baseDamageTaken, damageTaken, blocked) -> {
            try {
                body.afterDamage(entity, source, baseDamageTaken, damageTaken, blocked);
            } catch (RuntimeException e) {
                report(name, e);
            }
        };
    }

    public static ServerLivingEntityEvents.AfterDeath afterDeath(String name, ServerLivingEntityEvents.AfterDeath body) {
        return (entity, source) -> {
            try {
                body.afterDeath(entity, source);
            } catch (RuntimeException e) {
                report(name, e);
            }
        };
    }

    /** On failure the interaction falls through to vanilla (PASS). */
    public static UseEntityCallback useEntity(String name, UseEntityCallback body) {
        return (player, world, hand, entity, hit) -> {
            try {
                return body.interact(player, world, hand, entity, hit);
            } catch (RuntimeException e) {
                report(name, e);
                return ActionResult.PASS;
            }
        };
    }

    /** On failure the attack goes ahead as vanilla would (PASS). */
    public static AttackEntityCallback attackEntity(String name, AttackEntityCallback body) {
        return (player, world, hand, entity, hit) -> {
            try {
                return body.interact(player, world, hand, entity, hit);
            } catch (RuntimeException e) {
                report(name, e);
                return ActionResult.PASS;
            }
        };
    }

    /**
     * Runs one bot's share of a tick loop. A RuntimeException is logged under {@code name} (with the
     * bot's name) and the loop goes on to the next bot, so one bot in an odd state does not skip the
     * rest.
     */
    public static void perBot(String name, ServerPlayerEntity bot, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            report(name + " [" + botName(bot) + "]", name, e);
        }
    }

    private static String botName(ServerPlayerEntity bot) {
        try {
            return bot == null ? "null" : bot.getName().getString();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static void report(String name, RuntimeException e) {
        report(name, name, e);
    }

    /** {@code label} is what the log names; {@code throttleKey} is what the once-a-minute limit counts. */
    private static void report(String label, String throttleKey, RuntimeException e) {
        long held = LOG.onFailure(throttleKey, System.currentTimeMillis());
        if (held < 0) {
            return;
        }
        LOGGER.error("Handler '{}' threw and was skipped this time{}", label,
                held > 0 ? " (" + held + " more failures since the last report)" : "", e);
    }
}
