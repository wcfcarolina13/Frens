package net.wcfcarolina13;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
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

    private static void report(String name, RuntimeException e) {
        long held = LOG.onFailure(name, System.currentTimeMillis());
        if (held < 0) {
            return;
        }
        LOGGER.error("Handler '{}' threw and was skipped this time{}", name,
                held > 0 ? " (" + held + " more failures since the last report)" : "", e);
    }
}
