package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MagmaCubeEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotHomeService.BaseEntry;

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
        if (server == null) return;
        if (server.getTicks() % SCAN_INTERVAL_TICKS != 0) return;
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) continue;
            if (!(bot.getEntityWorld() instanceof ServerWorld)) continue;
            if (bot.hasVehicle()) continue; // mounted bots are passengers, not defenders
            tickOneBot(server, bot);
        }
    }

    /**
     * Per-bot tick body. Self-preservation gates first; then hostile-forward
     * scan (Step 1); then watch-list reverse scan (Step 2). Both steps may
     * mark attackers for the defense boost map.
     */
    private static void tickOneBot(MinecraftServer server, ServerPlayerEntity bot) {
        // Self-preservation: bots below the HP threshold do not engage in defense.
        // They may still emit overhead warnings (the warning is informational and
        // doesn't put the bot at additional risk), so the HP gate only suppresses
        // markAttackerForDefense, not the warning emitter.
        boolean canEngage = bot.getHealth() > bot.getMaxHealth() * SELF_PRESERVATION_HP_FRACTION;

        UUID commanderUuid = resolveCommanderUuid(bot);

        scanHostilesStep1(server, bot, commanderUuid, canEngage);
        scanWatchListStep2(server, bot, commanderUuid, canEngage);
    }

    /**
     * Step 1 — hostile-forward scan. For each nearby hostile, look at its
     * vanilla AI target. If the target is a defended entity for this bot
     * (and the attacker isn't excluded by the farm-machinery heuristic or
     * the tamed-vs-tamed skip), mark it for defense.
     */
    private static void scanHostilesStep1(
            MinecraftServer server,
            ServerPlayerEntity bot,
            UUID commanderUuid,
            boolean canEngage) {
        List<Entity> hostiles = BotThreatService.findHostilesAround(bot, HOSTILE_SCAN_RADIUS);
        if (hostiles.isEmpty()) return;
        for (Entity hostile : hostiles) {
            if (!(hostile instanceof MobEntity hostileMob)) continue;
            LivingEntity target = hostileMob.getTarget();
            if (target == null) continue;
            if (!isDefendedEntity(target, commanderUuid, bot)) continue;
            if (isExcludedByFarmHeuristic(hostile)) continue;
            if (isTamedVsTamedCase(hostile, target, commanderUuid, bot)) continue;
            // Distance gate: within DEFENSE_ENGAGE_RADIUS = engage; outside = warn.
            double distToBot = Math.sqrt(hostile.squaredDistanceTo(bot));
            if (distToBot <= DEFENSE_ENGAGE_RADIUS) {
                if (canEngage && BotCombatPolicyService.shouldBotAttack(hostile, bot)) {
                    markAttackerForDefense(server, bot, hostile);
                }
            } else {
                maybeOverheadWarn(bot, target, hostile, "out-of-range");
            }
        }
    }

    /**
     * Step 2 — watch-list reverse scan for player attackers and accidental
     * hits that Step 1 cannot catch (players don't have an AI target;
     * skeleton arrows clipping a cow won't show up as the skeleton's target).
     * Watch list is small by construction (commander's pets, leashed mobs,
     * named entities) — capped at WATCH_LIST_HARD_CAP.
     */
    private static void scanWatchListStep2(
            MinecraftServer server,
            ServerPlayerEntity bot,
            UUID commanderUuid,
            boolean canEngage) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return;
        List<LivingEntity> watchList = collectWatchList(world, bot, commanderUuid);
        if (watchList.isEmpty()) return;
        ServerPlayerEntity commander = resolveCommanderEntity(server, commanderUuid, world);
        for (LivingEntity watched : watchList) {
            if (!passesVictimSanityGates(watched, bot)) continue;
            if (!recentlyAttacked(watched)) continue;
            LivingEntity attacker = watched.getAttacker();
            if (attacker == null) continue;
            if (commander != null && attacker == commander) continue; // commander butchering
            if (attacker instanceof PlayerEntity attackerPlayer
                    && attackerPlayer instanceof ServerPlayerEntity sp
                    && !isAttackerAllied(bot, sp)) {
                maybeOverheadWarn(bot, watched, attacker, "player-attacker");
                continue;
            }
            if (isExcludedByFarmHeuristic(attacker)) continue;
            if (isTamedVsTamedCase(attacker, watched, commanderUuid, bot)) continue;
            double distToBot = Math.sqrt(attacker.squaredDistanceTo(bot));
            if (distToBot <= DEFENSE_ENGAGE_RADIUS) {
                if (canEngage && BotCombatPolicyService.shouldBotAttack(attacker, bot)) {
                    markAttackerForDefense(server, bot, attacker);
                }
            } else {
                maybeOverheadWarn(bot, watched, attacker, "out-of-range");
            }
        }
    }

    /**
     * Builds the watch list from the bot's surroundings. Combines four
     * sub-categories (commander's tameables, horses, leashed mobs, name-tagged
     * entities) into a single deduplicated list capped at WATCH_LIST_HARD_CAP.
     */
    private static List<LivingEntity> collectWatchList(
            ServerWorld world, ServerPlayerEntity bot, UUID commanderUuid) {
        // Vec3d ctor instead of bot.getPos() — getPos() does not exist on Entity
        // in 1.21.11 yarn (same trap that bit RescueTeleportNetworkManager earlier).
        Vec3d botPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        Box box = Box.of(botPos, WATCH_LIST_SCAN_RADIUS * 2, WATCH_LIST_SCAN_RADIUS * 2,
                WATCH_LIST_SCAN_RADIUS * 2);
        List<LivingEntity> result = new ArrayList<>();
        // We use a single broad query and filter, rather than four separate queries.
        // This is cheaper than four separate getEntitiesByClass calls.
        for (LivingEntity living : world.getEntitiesByClass(LivingEntity.class, box, e -> true)) {
            if (result.size() >= WATCH_LIST_HARD_CAP) break;
            if (living == bot) continue;
            if (living.squaredDistanceTo(bot)
                    > WATCH_LIST_SCAN_RADIUS * WATCH_LIST_SCAN_RADIUS) continue;
            // Quick category match: any of rules 1-3 or rule 5 (named entity).
            if (commanderUuid != null) {
                if (living instanceof TameableEntity tameable
                        && tameable.isTamed()
                        && tameable.getOwnerReference() != null
                        && commanderUuid.equals(tameable.getOwnerReference().getUuid())) {
                    result.add(living);
                    continue;
                }
                if (living instanceof AbstractHorseEntity horse
                        && horse.isTame()
                        && horse.getOwnerReference() != null
                        && commanderUuid.equals(horse.getOwnerReference().getUuid())) {
                    result.add(living);
                    continue;
                }
                if (living instanceof MobEntity mob && mob.isLeashed()) {
                    Entity holder = mob.getLeashHolder();
                    if (holder instanceof ServerPlayerEntity holderPlayer
                            && commanderUuid.equals(holderPlayer.getUuid())) {
                        result.add(living);
                        continue;
                    }
                }
            }
            // Rule 5: name-tagged entity (sanity gates run later in the caller).
            if (living.hasCustomName()) {
                result.add(living);
            }
        }
        return result;
    }

    /**
     * Records (bot, attacker) in the defense map with an expiry of
     * {@code now + DEFEND_EXPIRE_TICKS}. The boost lifetime extends on each
     * new mark, so a continuously-attacking mob stays prioritized.
     */
    private static void markAttackerForDefense(
            MinecraftServer server, ServerPlayerEntity bot, Entity attacker) {
        if (server == null || bot == null || attacker == null) return;
        long expireAt = server.getTicks() + DEFEND_EXPIRE_TICKS;
        DEFEND_TARGETS
                .computeIfAbsent(bot.getUuid(), k -> new ConcurrentHashMap<>())
                .put(attacker.getUuid(), expireAt);
    }

    /**
     * Throttled overhead warning emitter. Calls
     * CompanionOverheadDialogueService.showOverheadLine with a message
     * tailored to the warning kind.
     */
    private static void maybeOverheadWarn(
            ServerPlayerEntity bot,
            LivingEntity victim,
            Entity attacker,
            String reason) {
        if (bot == null || victim == null || attacker == null) return;
        WarnKey key = new WarnKey(bot.getUuid(), victim.getUuid(), attacker.getUuid());
        long now = System.currentTimeMillis();
        Long lastWarnedAt = LAST_OVERHEAD_WARN_MS.get(key);
        if (lastWarnedAt != null && now - lastWarnedAt < OVERHEAD_WARN_COOLDOWN_MS) {
            return;
        }
        LAST_OVERHEAD_WARN_MS.put(key, now);
        String victimName = victim.getName().getString();
        String message = "player-attacker".equals(reason)
                ? "Engaging threats against allies."
                : "Something's attacking your " + victimName + "!";
        CompanionOverheadDialogueService.showOverheadLine(
                bot, message, 2_800, 32.0D, "animal-defense", reason);
        LOGGER.info("animal-defense overhead-warn bot={} victim={} attacker={} reason={}",
                bot.getName().getString(), victimName,
                attacker.getName().getString(), reason);
    }

    /**
     * Hook for {@code BotEventHandler.scoreThreat}. Returns the additive score
     * boost for a candidate attacker against a particular bot, or {@code 0.0}
     * if the candidate is not currently a defended attacker for that bot.
     * Lazy expiry sweep happens here on read.
     */
    public static double defenseBoost(ServerPlayerEntity bot, Entity candidate) {
        if (bot == null || candidate == null) return 0.0D;
        Map<UUID, Long> botMap = DEFEND_TARGETS.get(bot.getUuid());
        if (botMap == null || botMap.isEmpty()) return 0.0D;
        Long expireAt = botMap.get(candidate.getUuid());
        if (expireAt == null) return 0.0D;
        long now = bot.getCommandSource().getServer() == null
                ? -1L
                : bot.getCommandSource().getServer().getTicks();
        if (now < 0) return 0.0D;
        if (now >= expireAt) {
            // Lazy cleanup on read.
            botMap.remove(candidate.getUuid());
            if (botMap.isEmpty()) DEFEND_TARGETS.remove(bot.getUuid());
            return 0.0D;
        }
        return DEFENSE_SCORE_BOOST;
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
        if (bot == null || hostileList == null) return hostileList;
        Map<UUID, Long> botMap = DEFEND_TARGETS.get(bot.getUuid());
        if (botMap == null || botMap.isEmpty()) return hostileList;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return hostileList;
        // Build a UUID set of entities already in the input list, so we dedup.
        java.util.Set<UUID> existing = new java.util.HashSet<>(hostileList.size());
        for (Entity e : hostileList) {
            existing.add(e.getUuid());
        }
        long now = bot.getCommandSource().getServer() == null
                ? -1L
                : bot.getCommandSource().getServer().getTicks();
        if (now < 0) return hostileList;
        // Defensive copy — never mutate the input list (some callers pass
        // Stream.toList() which is unmodifiable; see feedback_stream_tolist_mutation.md).
        List<Entity> augmented = null;
        java.util.Iterator<Map.Entry<UUID, Long>> it = botMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now >= entry.getValue()) {
                it.remove(); // lazy cleanup
                continue;
            }
            UUID attackerUuid = entry.getKey();
            if (existing.contains(attackerUuid)) continue;
            // Resolve the attacker entity in this world via O(1) UUID lookup.
            // ServerWorld.getEntityAnyDimension(UUID) is the correct 1.21.11
            // API — much cheaper than iterating world entities for one match.
            Entity attackerEntity = world.getEntityAnyDimension(attackerUuid);
            if (attackerEntity == null) continue;
            if (attackerEntity.isRemoved() || !attackerEntity.isAlive()) continue;
            // Sanity: only inject attackers that are in the bot's current world.
            // getEntityAnyDimension can return entities from other dimensions and
            // the combat system expects same-world entities.
            if (attackerEntity.getEntityWorld() != world) continue;
            if (augmented == null) {
                augmented = new ArrayList<>(hostileList);
            }
            augmented.add(attackerEntity);
            existing.add(attackerUuid);
        }
        if (botMap.isEmpty()) DEFEND_TARGETS.remove(bot.getUuid());
        return augmented != null ? augmented : hostileList;
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

    /**
     * Hard exclusions that run before any defended-category rule. A candidate
     * victim must pass all of these or it is not a defended entity, regardless
     * of name tag, ownership, or village membership. Closes the "named
     * hostile" loophole in rule 5.
     */
    private static boolean passesVictimSanityGates(Entity victim, ServerPlayerEntity bot) {
        if (victim == null || victim.isRemoved() || !victim.isAlive()) return false;
        if (bot == null || bot.getEntityWorld() != victim.getEntityWorld()) return false;
        if (victim instanceof HostileEntity) return false;
        if (victim instanceof RaiderEntity) return false;
        if (victim instanceof SlimeEntity) return false;
        if (victim instanceof MagmaCubeEntity) return false;
        if (victim instanceof EnderDragonEntity) return false;
        if (victim instanceof WitherEntity) return false;
        return true;
    }

    /**
     * Returns true if {@code target} is a defended victim for {@code bot}.
     * Runs the six defended-category rules from the spec in priority order;
     * the first rule that matches wins. {@link #passesVictimSanityGates}
     * must be true or no rule fires (closes the named-hostile loophole).
     */
    private static boolean isDefendedEntity(
            Entity target, UUID commanderUuid, ServerPlayerEntity bot) {
        if (!passesVictimSanityGates(target, bot)) return false;
        if (matchesRule1Tameable(target, commanderUuid, bot)) return true;
        if (matchesRule2Horse(target, commanderUuid)) return true;
        if (matchesRule3Leashed(target, commanderUuid, bot)) return true;
        if (matchesRule4PreferredHomeBaseFarm(target, bot)) return true;
        if (matchesRule5NameTag(target)) return true;
        if (matchesRule6NamedVillageVillager(target, bot)) return true;
        return false;
    }

    /**
     * Rule 1 — commander-owned tameable (cat, wolf, parrot).
     * Tameable.isTamed() distinct from horse.isTame() (no 'd').
     */
    private static boolean matchesRule1Tameable(
            Entity target, UUID commanderUuid, ServerPlayerEntity bot) {
        if (commanderUuid == null) return false;
        if (!(target instanceof TameableEntity tameable)) return false;
        if (!tameable.isTamed()) return false;
        // Prefer the entity-aware check when commander is online in the same world.
        ServerPlayerEntity commander = resolveCommanderEntity(
                bot.getCommandSource().getServer(),
                commanderUuid,
                (ServerWorld) bot.getEntityWorld());
        if (commander != null) {
            return tameable.isOwner(commander);
        }
        // Offline or cross-dimension: compare UUIDs via getOwnerReference.
        if (tameable.getOwnerReference() == null) return false;
        UUID ownerUuid = tameable.getOwnerReference().getUuid();
        return commanderUuid.equals(ownerUuid);
    }

    /**
     * Rule 2 — commander-owned horse family (horse, donkey, mule, llama, camel,
     * skeleton/zombie horse). AbstractHorseEntity uses {@code isTame()} (no 'd'),
     * distinct from TameableEntity.isTamed().
     */
    private static boolean matchesRule2Horse(Entity target, UUID commanderUuid) {
        if (commanderUuid == null) return false;
        if (!(target instanceof AbstractHorseEntity horse)) return false;
        if (!horse.isTame()) return false;
        if (horse.getOwnerReference() == null) return false;
        UUID ownerUuid = horse.getOwnerReference().getUuid();
        return commanderUuid.equals(ownerUuid);
    }

    /**
     * Rule 3 — leashed to commander. Requires the commander to be a live
     * LivingEntity in the same world. Leashed-to-fence-post or leashed-to-
     * another-player does not count.
     */
    private static boolean matchesRule3Leashed(
            Entity target, UUID commanderUuid, ServerPlayerEntity bot) {
        if (commanderUuid == null) return false;
        if (!(target instanceof MobEntity mob)) return false;
        if (!mob.isLeashed()) return false;
        ServerPlayerEntity commander = resolveCommanderEntity(
                bot.getCommandSource().getServer(),
                commanderUuid,
                (ServerWorld) bot.getEntityWorld());
        if (commander == null) return false;
        return mob.getLeashHolder() == commander;
    }

    /**
     * Rule 4 — base-proximity farm animal. The bot must have a preferred home
     * base set (commander-scoped implicitly via WorldData.preferredHomeBaseByBot),
     * and both the victim and a hay bale must be inside that base's radius.
     * The hay bale must be within HAY_BALE_RADIUS of the victim.
     */
    private static boolean matchesRule4PreferredHomeBaseFarm(
            Entity target, ServerPlayerEntity bot) {
        if (!(target instanceof AnimalEntity)) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        java.util.Optional<BlockPos> preferredBaseOpt =
                BotHomeService.resolvePreferredHomeBase(bot);
        if (preferredBaseOpt.isEmpty()) return false;
        BlockPos basePos = preferredBaseOpt.get();
        java.util.Optional<BaseEntry> baseEntryOpt =
                BotHomeService.findBaseNearPosition(world.getServer(), world, basePos);
        if (baseEntryOpt.isEmpty()) return false;
        BaseEntry base = baseEntryOpt.get();
        int baseRadius = base.radius() > 0
                ? base.radius()
                : BotHomeService.DEFAULT_BASE_PROTECTION_RADIUS;
        BlockPos victimPos = target.getBlockPos();
        if (!base.pos().isWithinDistance(victimPos, baseRadius)) return false;
        // Find a hay bale within HAY_BALE_RADIUS of the victim that is also
        // inside the base radius. Scan a small box around the victim.
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -HAY_BALE_RADIUS; dx <= HAY_BALE_RADIUS; dx++) {
            for (int dy = -HAY_BALE_RADIUS; dy <= HAY_BALE_RADIUS; dy++) {
                for (int dz = -HAY_BALE_RADIUS; dz <= HAY_BALE_RADIUS; dz++) {
                    cursor.set(victimPos.getX() + dx, victimPos.getY() + dy, victimPos.getZ() + dz);
                    if (!world.getBlockState(cursor).isOf(Blocks.HAY_BLOCK)) continue;
                    if (!base.pos().isWithinDistance(cursor, baseRadius)) continue;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Rule 5 — named-entity override. Any name-tagged entity that already
     * passed the Victim Sanity Gates qualifies. The sanity gates excluded
     * hostile classes, so a name-tagged zombie still won't be defended.
     */
    private static boolean matchesRule5NameTag(Entity target) {
        return target.hasCustomName();
    }

    /**
     * Rule 6 — villager inside a mapped village. Both the victim and the bot
     * must be inside the same mapped village (label equality, not reference).
     */
    private static boolean matchesRule6NamedVillageVillager(Entity target, ServerPlayerEntity bot) {
        if (!(target instanceof VillagerEntity)) return false;
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) return false;
        java.util.Optional<MappedVillageService.MappedVillage> botVillageOpt =
                MappedVillageService.getVillageAt(world, bot.getBlockPos());
        if (botVillageOpt.isEmpty()) return false;
        java.util.Optional<MappedVillageService.MappedVillage> victimVillageOpt =
                MappedVillageService.getVillageAt(world, target.getBlockPos());
        if (victimVillageOpt.isEmpty()) return false;
        return botVillageOpt.get().getName().equalsIgnoreCase(victimVillageOpt.get().getName());
    }

    /**
     * Farm-machinery heuristic: if the attacker is riding another entity (boat,
     * minecart, mounted on another mob), it's almost certainly a farm component
     * (zombie-in-boat for iron farms, minecart-trapped mobs in spawner grinders,
     * AFK mob mounts). Skip defense for these cases.
     */
    private static boolean isExcludedByFarmHeuristic(Entity attacker) {
        return attacker != null && attacker.hasVehicle();
    }

    /**
     * If the attacker is itself a defended entity (e.g., llama spitting at owned
     * wolf, owned wolf attacking owned sheep), skip defense. Prevents llama-spit
     * cascades from causing the bot to attack its own pets.
     */
    private static boolean isTamedVsTamedCase(
            Entity attacker, Entity victim, UUID commanderUuid, ServerPlayerEntity bot) {
        if (attacker == null) return false;
        return isDefendedEntity(attacker, commanderUuid, bot);
    }

    /**
     * True if the victim took damage within the last ~10 ticks. Reads the
     * vanilla {@code LivingEntity.hurtTime} public int field (NOT a getter).
     * Used by the watch-list reverse scan to gate against stale getAttacker()
     * values that vanilla preserves for ~100 ticks after the last hit.
     */
    private static boolean recentlyAttacked(LivingEntity victim) {
        return victim != null && victim.hurtTime > 0;
    }

    /** Resolves the bot's commander UUID via CompanionCommunicationPolicy. */
    private static UUID resolveCommanderUuid(ServerPlayerEntity bot) {
        return CompanionCommunicationPolicy.resolveOwnerUuid(bot);
    }

    /** Resolves the live commander entity in the bot's world, or null if offline/cross-dim. */
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
