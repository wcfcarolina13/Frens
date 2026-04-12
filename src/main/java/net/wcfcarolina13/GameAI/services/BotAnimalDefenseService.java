package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bot defends commander-owned animals from non-commander attackers.
 *
 * <p>See docs/superpowers/specs/2026-04-11-tamed-animal-defense-design.md for the
 * full design. Briefly:</p>
 *
 * <ul>
 *   <li>Per-tick (10-tick throttle) per-bot scan that uses a hostile-forward
 *       primary path ({@code mob.getTarget()} against nearby hostiles) plus a
 *       small watch-list reverse scan for player attackers and accidental hits.</li>
 *   <li>Defended categories: commander-owned tameables (cat/wolf/parrot),
 *       commander-owned horses, mobs leashed to commander, animals on the bot's
 *       preferred home base near a hay bale, name-tagged entities, and villagers
 *       inside mapped villages. Hostile mob classes are excluded from being
 *       victims (Victim Sanity Gates).</li>
 *   <li>When an attacker is in range (~16 blocks), it gets a threat-score boost
 *       via {@link #defenseBoost(ServerPlayerEntity, Entity)} which {@code
 *       BotEventHandler.scoreThreat} adds to its normal score. The attacker is
 *       also injected into the hostile list via
 *       {@link #augmentHostilesWithDefenseTargets(ServerPlayerEntity, List)} so
 *       non-{@code HostileEntity} attackers (e.g. wolves gone wild) are visible
 *       to the combat system.</li>
 *   <li>When the attacker is out of range or is a player, an overhead warning
 *       fires via {@code CompanionOverheadDialogueService.showOverheadLine},
 *       throttled per (bot,victim,attacker) tuple.</li>
 *   <li>Iron golem special rules and the alliances forward-compat hook live
 *       here as well — see {@link #isAttackerAllied(ServerPlayerEntity,
 *       ServerPlayerEntity)} and the iron-golem helper called from
 *       {@code BotEventHandler}.</li>
 * </ul>
 */
public final class BotAnimalDefenseService {

    private static final Logger LOGGER = LoggerFactory.getLogger("animal-defense");

    // ─────────────────────────────────────────────────────────────────────────
    // Tunable constants — units strictly enforced.
    //   *_TICKS / *_TICK / *_INTERVAL_TICKS = server game-ticks (server.getTicks())
    //   *_MS                                = wall-clock milliseconds (System.currentTimeMillis())
    // ─────────────────────────────────────────────────────────────────────────

    /** How often the per-tick scan runs. 10 ticks = 0.5 seconds. */
    private static final int SCAN_INTERVAL_TICKS = 10;

    /** Radius (blocks) for the hostile-forward scan. Matches existing combat range. */
    private static final double HOSTILE_SCAN_RADIUS = 16.0D;

    /** Radius (blocks) for the watch-list reverse scan. */
    private static final double WATCH_LIST_SCAN_RADIUS = 16.0D;

    /** Hard cap on watch-list size — defends against pathological setups with many named pets. */
    private static final int WATCH_LIST_HARD_CAP = 12;

    /** Within this distance, defense engages; outside, only an overhead warning fires. */
    private static final double DEFENSE_ENGAGE_RADIUS = 16.0D;

    /** Hay bale "farm marker" radius for rule 4. */
    private static final int HAY_BALE_RADIUS = 8;

    /** Threat-boost lifetime in server ticks. 100 = 5 seconds. */
    private static final long DEFEND_EXPIRE_TICKS = 100L;

    /** Per (bot,victim,attacker) overhead warn cooldown in milliseconds. 60_000 = 1 minute. */
    private static final long OVERHEAD_WARN_COOLDOWN_MS = 60_000L;

    /** Bot will not engage in defense below this fraction of max HP (flees instead). */
    private static final float SELF_PRESERVATION_HP_FRACTION = 0.30F;

    /** Additive boost added to scoreThreat for defended attackers. */
    private static final double DEFENSE_SCORE_BOOST = 50.0D;

    // ─────────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * botUuid -> (attackerUuid -> expireGameTick). Values are server-ticks
     * (server.getTicks() + DEFEND_EXPIRE_TICKS at insert time), NOT milliseconds.
     * Cleaned lazily on read inside {@link #defenseBoost}.
     */
    private static final Map<UUID, Map<UUID, Long>> DEFEND_TARGETS = new ConcurrentHashMap<>();

    /**
     * Throttle key for overhead warnings. Three-UUID tuple uniquely identifies
     * a (bot, victim, attacker) combination.
     */
    private record WarnKey(UUID botUuid, UUID victimUuid, UUID attackerUuid) {}

    /**
     * (botUuid, victimUuid, attackerUuid) -> lastWarnEpochMillis. Values are
     * System.currentTimeMillis(), NOT game-ticks. Used to enforce
     * OVERHEAD_WARN_COOLDOWN_MS.
     */
    private static final Map<WarnKey, Long> LAST_OVERHEAD_WARN_MS = new ConcurrentHashMap<>();

    private BotAnimalDefenseService() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Per-tick entry point. Registered in {@code Frens.java} alongside the other
     * END_SERVER_TICK services. Throttled internally to {@link #SCAN_INTERVAL_TICKS}.
     */
    public static void onServerTick(MinecraftServer server) {
        // Stub: filled in chunk 3.
    }

    /**
     * Hook for {@code BotEventHandler.scoreThreat}. Returns the additive score
     * boost for a candidate attacker against a particular bot, or {@code 0.0}
     * if the candidate is not currently a defended attacker for that bot.
     * Lazy expiry sweep happens here on read.
     */
    public static double defenseBoost(ServerPlayerEntity bot, Entity candidate) {
        // Stub: filled in chunk 3.
        return 0.0D;
    }

    /**
     * Hook for the top of {@code BotEventHandler.engageHostiles}. Returns a list
     * containing all original hostiles plus any defense-target attackers from the
     * map that aren't already in the list (dedup by UUID). Returns the same
     * reference if the defense map is empty for this bot (zero allocation common
     * case). Never mutates the input list — defends against {@code Stream.toList()}
     * immutable callers (see feedback_stream_tolist_mutation.md memory).
     */
    public static List<Entity> augmentHostilesWithDefenseTargets(
            ServerPlayerEntity bot, List<Entity> hostileList) {
        // Stub: filled in chunk 3.
        return hostileList;
    }

    /**
     * Forward-compat hook for the future "alliances" system. v1 always returns
     * {@code false} — player attackers are never treated as allied, so
     * {@link #maybeOverheadWarn} always fires the overhead warning when a
     * non-commander player hits an owned animal.
     *
     * <p>When alliances lands, this method will gate behavior per-player.</p>
     */
    public static boolean isAttackerAllied(
            ServerPlayerEntity bot, ServerPlayerEntity attacker) {
        return false;
    }

    /**
     * Cleanup hook called from the {@code Frens.SERVER_STOPPING} handler.
     * Mirrors the pattern used by the 8 other services documented in CLAUDE.md.
     */
    public static void reset() {
        DEFEND_TARGETS.clear();
        LAST_OVERHEAD_WARN_MS.clear();
        LOGGER.info("BotAnimalDefenseService reset (server stopping)");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal — stubs to be filled in chunks 2-3
    // ─────────────────────────────────────────────────────────────────────────

    /** True if the candidate victim passes all sanity gates (chunk 2). */
    @SuppressWarnings("unused")
    private static boolean passesVictimSanityGates(Entity victim, ServerPlayerEntity bot) {
        return false;
    }

    /** True if the victim is a defended entity for this bot (chunk 2, rules 1-6). */
    @SuppressWarnings("unused")
    private static boolean isDefendedEntity(
            Entity target, UUID commanderUuid, ServerPlayerEntity bot) {
        return false;
    }

    /** Farm-machinery exclusion: attacker is in a vehicle (boat, minecart, mounted) (chunk 2). */
    @SuppressWarnings("unused")
    private static boolean isExcludedByFarmHeuristic(Entity attacker) {
        return false;
    }

    /** Tamed-vs-tamed skip: attacker is itself a defended entity (chunk 2). */
    @SuppressWarnings("unused")
    private static boolean isTamedVsTamedCase(
            Entity attacker, Entity victim, UUID commanderUuid, ServerPlayerEntity bot) {
        return false;
    }

    /** True if the victim was hit recently (vanilla hurtTime field, chunk 2). */
    @SuppressWarnings("unused")
    private static boolean recentlyAttacked(LivingEntity victim) {
        return false;
    }

    /** Resolves the bot's commander UUID via CompanionCommunicationPolicy. */
    @SuppressWarnings("unused")
    private static UUID resolveCommanderUuid(ServerPlayerEntity bot) {
        return CompanionCommunicationPolicy.resolveOwnerUuid(bot);
    }

    /** Resolves the live commander entity in the bot's world, or null if offline/cross-dim. */
    @SuppressWarnings("unused")
    private static ServerPlayerEntity resolveCommanderEntity(
            MinecraftServer server, UUID commanderUuid, ServerWorld botWorld) {
        if (server == null || commanderUuid == null || botWorld == null) {
            return null;
        }
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(commanderUuid);
        if (p == null || p.isRemoved() || !p.isAlive()) {
            return null;
        }
        if (p.getEntityWorld() != botWorld) {
            return null;
        }
        return p;
    }
}
