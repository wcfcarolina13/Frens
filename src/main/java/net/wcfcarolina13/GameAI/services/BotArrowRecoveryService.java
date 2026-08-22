package net.wcfcarolina13.GameAI.services;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.BotEventHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Post-combat quality-of-life: track bot-fired arrows and (once threats are clear)
 * walk over to pick up missed arrows stuck in the world.
 *
 * <p>Also emits a low-frequency "I missed!" style callout when a bot-owned arrow
 * becomes stationary (likely a miss). This is cooldown+probability gated to avoid spam.</p>
 */
public final class BotArrowRecoveryService {

    // How long after last hostile sighting we consider it safe to recover arrows.
    private static final long SAFE_DELAY_TICKS = 40L; // ~2s

    // Forget arrow history after this long without shooting.
    private static final long SHOT_MEMORY_TICKS = 20L * 60L * 5L; // 5 minutes

    // Scan for arrows no more often than this per bot.
    private static final long ARROW_SCAN_PERIOD_TICKS = 5L;

    // Search radius for bot-owned arrows.
    private static final double ARROW_SCAN_RADIUS = 40.0D;

    // If we haven't seen an arrow entity recently, assume it's been picked up/despawned.
    private static final long ARROW_STALE_TICKS = 20L; // 1s

    // How close we try to get to an arrow when recovering.
    private static final double RECOVER_STOP_DISTANCE = 0.25D;

    // Give up chasing a single arrow after this long.
    private static final long CHASE_TIMEOUT_TICKS = 200L; // 10s

    // Give up a recovery session after this long (prevents long detours).
    private static final long SESSION_TIMEOUT_TICKS = 600L; // 30s

    // "I missed!" callout spam control.
    private static final long MISS_CALLOUT_COOLDOWN_TICKS = 20L * 40L; // 40s
    private static final double MISS_CALLOUT_PROBABILITY = 0.22D;

    private static final List<String> MISS_LINES = List.of(
            "I missed!",
            "Dang, missed!",
            "Ugh. Missed.",
            "That one went wide!"
    );

    private static final class TrackedArrow {
        Vec3d lastPos;
        long lastSeenTick;
        long stationarySinceTick;
        boolean botOwned;
        boolean trident;
        boolean mandatoryRecovery;

        TrackedArrow(Vec3d lastPos, long lastSeenTick, boolean botOwned, boolean trident) {
            this.lastPos = lastPos;
            this.lastSeenTick = lastSeenTick;
            this.stationarySinceTick = 0L;
            this.botOwned = botOwned;
            this.trident = trident;
            this.mandatoryRecovery = trident && botOwned;
        }
    }

    private static final class BotState {
        long lastShotTick = -1L;
        long lastHostileSeenTick = -1L;
        long nextScanTick = 0L;

        // A stable anchor near where combat/shooting happened (helps bound recovery in FOLLOW).
        Vec3d lastCombatAnchorPos = null;

        long recoverySessionStartTick = -1L;
        UUID chasingArrowId = null;
        long chaseStartTick = -1L;

        long lastMissCalloutTick = -1L;
        boolean outstandingTridentRecovery = false;
        int expectedTridentCount = 0;

        final Map<UUID, TrackedArrow> tracked = new HashMap<>();

        void clearRecovery() {
            recoverySessionStartTick = -1L;
            chasingArrowId = null;
            chaseStartTick = -1L;
        }

        void clearAll() {
            lastShotTick = -1L;
            lastHostileSeenTick = -1L;
            nextScanTick = 0L;
            lastMissCalloutTick = -1L;
            lastCombatAnchorPos = null;
            outstandingTridentRecovery = false;
            expectedTridentCount = 0;
            tracked.clear();
            clearRecovery();
        }
    }

    private static final ConcurrentHashMap<UUID, BotState> STATE = new ConcurrentHashMap<>();

    private BotArrowRecoveryService() {
    }

    private static BotState stateFor(ServerPlayerEntity bot) {
        return STATE.computeIfAbsent(bot.getUuid(), k -> new BotState());
    }

    static boolean shouldTrackProjectile(boolean tridentProjectile,
                                         boolean botOwned,
                                         PersistentProjectileEntity.PickupPermission pickupType,
                                         boolean touchingWater) {
        if (tridentProjectile) {
            return botOwned;
        }
        return botOwned
                && pickupType == PersistentProjectileEntity.PickupPermission.ALLOWED
                && !touchingWater;
    }

    /**
     * Arrow recovery is a QoL/automation behavior and should not fight user-issued (command) skills.
     * However, it should be allowed to interrupt open-ended/ambient tasks.
     */
    private static boolean isBlockingTaskRunning(UUID botUuid) {
        if (botUuid == null) {
            return false;
        }
        return TaskService.getActiveTaskInfo(botUuid)
                .map(info -> info.state() == TaskService.State.RUNNING && !info.openEnded())
                .orElse(false);
    }

    public static void noteRangedShot(ServerPlayerEntity bot, long serverTick) {
        if (bot == null) {
            return;
        }
        BotState st = stateFor(bot);
        st.lastShotTick = serverTick;
        // Keep an anchor around the shooting location for recovery bounds.
        st.lastCombatAnchorPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        // Let arrow tracking start quickly after a shot.
        st.nextScanTick = Math.min(st.nextScanTick, serverTick);
    }

    public static void noteTridentThrown(ServerPlayerEntity bot, ItemStack stack, long serverTick) {
        if (bot == null || stack == null || stack.isEmpty()) {
            return;
        }
        BotState st = stateFor(bot);
        st.outstandingTridentRecovery = true;
        st.expectedTridentCount = Math.max(st.expectedTridentCount, countTridents(bot));
        noteRangedShot(bot, serverTick);
    }

    public static void noteHostilesSeen(ServerPlayerEntity bot, long serverTick) {
        if (bot == null) {
            return;
        }
        BotState st = stateFor(bot);
        st.lastHostileSeenTick = serverTick;
        st.lastCombatAnchorPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        // If threats are present again, stop any arrow recovery movement immediately.
        st.clearRecovery();
    }

    /**
     * Lightweight per-tick hook: updates arrow tracking and (optionally) emits a low-frequency miss callout.
     * Call this from the main bot behavior loop (even while in combat).
     */
    public static void tickArrowTracking(ServerPlayerEntity bot, MinecraftServer server, boolean hostilesPresent) {
        if (bot == null || server == null) {
            return;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            return;
        }
        if (bot.getAbilities().creativeMode) {
            return; // no need to recover in creative
        }

        long nowTick = server.getTicks();
        BotState st = stateFor(bot);
        refreshTridentRecoveryState(bot, st);

        long lastActivity = Math.max(st.lastShotTick, st.lastHostileSeenTick);
        if (lastActivity < 0L) {
            return;
        }
        if (nowTick - lastActivity > SHOT_MEMORY_TICKS) {
            st.clearAll();
            return;
        }

        if (nowTick < st.nextScanTick) {
            return;
        }
        st.nextScanTick = nowTick + ARROW_SCAN_PERIOD_TICKS;

        scanForRecoverableArrows(bot, nowTick, hostilesPresent);
    }

    /**
     * Attempt to recover bot-owned arrows once combat is clear.
     * Returns true if this service drove movement this tick.
     */
    public static boolean tryRecoverMissedArrows(ServerPlayerEntity bot,
                                                MinecraftServer server,
                                                Vec3d anchor,
                                                double maxRadiusFromAnchor) {
        if (bot == null || server == null) {
            return false;
        }
        if (!BotEventHandler.isRegisteredBot(bot)) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld)) {
            return false;
        }
        if (bot.getAbilities().creativeMode) {
            return false;
        }
        if (bot.hasVehicle()) {
            return false;
        }
        if (bot.isUsingItem()) {
            return false;
        }
        if (isBlockingTaskRunning(bot.getUuid())) {
            return false;
        }

        long nowTick = server.getTicks();
        BotState st = stateFor(bot);
        refreshTridentRecoveryState(bot, st);

        // Recover if we've been in combat/shot recently, OR if there are arrows nearby.
        long lastActivity = Math.max(st.lastShotTick, st.lastHostileSeenTick);
        if (lastActivity < 0L) {
            // No combat recorded for this bot. Do a speculative scan — if arrows are
            // nearby (e.g. from other bots' fights), bootstrap recovery state.
            scanForRecoverableArrows(bot, nowTick, false);
            if (st.tracked.isEmpty() && !st.outstandingTridentRecovery) {
                return false;
            }
            // Bootstrap: set lastHostileSeenTick past the safe-delay so recovery starts immediately.
            st.lastHostileSeenTick = nowTick - SAFE_DELAY_TICKS - 1L;
            st.lastCombatAnchorPos = new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        } else if (nowTick - lastActivity > SHOT_MEMORY_TICKS) {
            st.clearAll();
            return false;
        }

        // Wait a moment after last hostile was seen (prevents ping-pong between targets).
        if (st.lastHostileSeenTick >= 0L && nowTick - st.lastHostileSeenTick <= SAFE_DELAY_TICKS) {
            return false;
        }

        if (anchor == null) {
            anchor = st.lastCombatAnchorPos != null
                    ? st.lastCombatAnchorPos
                    : new Vec3d(bot.getX(), bot.getY(), bot.getZ());
        }
        double maxRadiusSq = maxRadiusFromAnchor > 0.0D ? maxRadiusFromAnchor * maxRadiusFromAnchor : Double.POSITIVE_INFINITY;

        // Ensure tracking is fresh.
        scanForRecoverableArrows(bot, nowTick, false);

        // If we have no tracked arrows, stop.
        if (st.tracked.isEmpty()) {
            st.clearRecovery();
            return false;
        }

        // Abort session after a while.
        if (st.recoverySessionStartTick >= 0L && nowTick - st.recoverySessionStartTick > SESSION_TIMEOUT_TICKS) {
            st.clearRecovery();
            if (!st.outstandingTridentRecovery) {
                return false;
            }
        }

        // Pick / validate current target arrow.
        UUID targetId = st.chasingArrowId;
        TrackedArrow tracked = targetId != null ? st.tracked.get(targetId) : null;

        if (targetId != null) {
            // If the arrow entity hasn't been seen recently, assume it was picked up/despawned and move on.
            if (tracked == null || tracked.lastPos == null || (nowTick - tracked.lastSeenTick) > ARROW_STALE_TICKS) {
                st.tracked.remove(targetId);
                st.chasingArrowId = null;
                st.chaseStartTick = -1L;
                targetId = null;
                tracked = null;
            } else {
                Vec3d pos = tracked.lastPos;
                if (pos.squaredDistanceTo(anchor) > maxRadiusSq) {
                    // Outside allowed area.
                    st.tracked.remove(targetId);
                    st.chasingArrowId = null;
                    st.chaseStartTick = -1L;
                    targetId = null;
                    tracked = null;
                }
            }
        }

        if (targetId == null) {
            // Start a new recovery session.
            if (st.recoverySessionStartTick < 0L) {
                st.recoverySessionStartTick = nowTick;
            }

            UUID next = pickNearestRecoverableArrow(bot, st, nowTick, anchor, maxRadiusSq);
            if (next == null) {
                st.clearRecovery();
                return false;
            }

            st.chasingArrowId = next;
            st.chaseStartTick = nowTick;
            tracked = st.tracked.get(next);
        }

        if (tracked == null || tracked.lastPos == null) {
            return false;
        }

        // If the target arrow hasn't been seen recently, don't drive movement toward stale positions.
        if ((nowTick - tracked.lastSeenTick) > ARROW_STALE_TICKS) {
            if (!tracked.mandatoryRecovery) {
                st.tracked.remove(st.chasingArrowId);
            }
            st.chasingArrowId = null;
            st.chaseStartTick = -1L;
            return false;
        }

        // Give up if chasing too long.
        if (st.chaseStartTick >= 0L && nowTick - st.chaseStartTick > CHASE_TIMEOUT_TICKS) {
            if (!tracked.mandatoryRecovery) {
                st.tracked.remove(st.chasingArrowId);
            }
            st.chasingArrowId = null;
            st.chaseStartTick = -1L;
            return false;
        }

        Vec3d arrowPos = tracked.lastPos;

        // Drive movement toward the arrow.
        double distSq = bot.squaredDistanceTo(arrowPos);
        boolean sprint = distSq > 10.0D * 10.0D;
        FollowMovementService.moveToward(bot, arrowPos, RECOVER_STOP_DISTANCE, sprint, () -> BotActions.lowerShield(bot));
        return true;
    }

    private static void scanForRecoverableArrows(ServerPlayerEntity bot, long nowTick, boolean hostilesPresent) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }

        BotState st = stateFor(bot);

        Box box = new Box(
                bot.getX() - ARROW_SCAN_RADIUS,
                bot.getY() - ARROW_SCAN_RADIUS,
                bot.getZ() - ARROW_SCAN_RADIUS,
                bot.getX() + ARROW_SCAN_RADIUS,
                bot.getY() + ARROW_SCAN_RADIUS,
                bot.getZ() + ARROW_SCAN_RADIUS
        );

        List<PersistentProjectileEntity> projectiles = world.getEntitiesByClass(
                PersistentProjectileEntity.class,
                box,
                p -> p instanceof ArrowEntity || p instanceof TridentEntity
        );

        for (PersistentProjectileEntity p : projectiles) {
            boolean tridentProjectile = p instanceof TridentEntity;
            if (!tridentProjectile && !(p instanceof ArrowEntity)) {
                continue;
            }

            Entity owner = p.getOwner();
            boolean botOwned = owner != null && owner.getUuid() != null
                    && owner.getUuid().equals(bot.getUuid());
            boolean touchingWater = p.isTouchingWater()
                    || p.isSubmergedInWater()
                    || world.getFluidState(p.getBlockPos()).isIn(FluidTags.WATER);
            UUID id = p.getUuid();
            if (!shouldTrackProjectile(tridentProjectile, botOwned, p.pickupType, touchingWater)) {
                st.tracked.remove(id);
                if (id.equals(st.chasingArrowId)) {
                    st.chasingArrowId = null;
                    st.chaseStartTick = -1L;
                }
                continue;
            }

            Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());
            TrackedArrow t = st.tracked.get(id);
            if (t == null) {
                t = new TrackedArrow(pos, nowTick, botOwned, tridentProjectile);
                st.tracked.put(id, t);
            } else {
                t.lastPos = pos;
                t.lastSeenTick = nowTick;
                t.botOwned = botOwned;
                t.trident = tridentProjectile;
                t.mandatoryRecovery = tridentProjectile && botOwned;
            }

            boolean stationary = p.getVelocity().lengthSquared() < 0.0004D;
            if (stationary) {
                if (t.stationarySinceTick == 0L) {
                    t.stationarySinceTick = nowTick;
                    // "I missed!" callout only for bot-owned arrows.
                    if (botOwned && !tridentProjectile) {
                        if (hostilesPresent) {
                            BotActions.noteRangedMiss(bot, nowTick);
                        }
                        maybeCalloutMiss(bot, nowTick, hostilesPresent);
                    }
                }
            } else {
                t.stationarySinceTick = 0L;
            }
        }

        // Prune old entries.
        long pruneBefore = nowTick - 100L;
        Iterator<Map.Entry<UUID, TrackedArrow>> it = st.tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TrackedArrow> e = it.next();
            TrackedArrow t = e.getValue();
            if (t == null) {
                it.remove();
                continue;
            }
            if (t.lastSeenTick < pruneBefore && !t.mandatoryRecovery) {
                it.remove();
            }
        }
    }

    private static void maybeCalloutMiss(ServerPlayerEntity bot, long nowTick, boolean hostilesPresent) {
        if (!hostilesPresent) {
            return; // don't shout about a miss when nothing is going on
        }
        BotState st = stateFor(bot);
        if (st.lastMissCalloutTick >= 0L && nowTick - st.lastMissCalloutTick < MISS_CALLOUT_COOLDOWN_TICKS) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() > MISS_CALLOUT_PROBABILITY) {
            return;
        }
        st.lastMissCalloutTick = nowTick;

        String line = MISS_LINES.get(ThreadLocalRandom.current().nextInt(MISS_LINES.size()));
        ChatUtils.sendChatMessages(
                bot.getCommandSource().withSilent().withPermissions(Frens.OPERATOR_PERMISSIONS),
                line,
                false
        );
    }

    private static UUID pickNearestRecoverableArrow(ServerPlayerEntity bot,
                                                   BotState st,
                                                   long nowTick,
                                                   Vec3d anchor,
                                                   double maxRadiusSq) {
        if (st.tracked.isEmpty()) {
            return null;
        }

        // Build a small candidate list: stationary arrows seen recently and within anchor bounds.
        List<UUID> candidates = new ArrayList<>();
        for (Map.Entry<UUID, TrackedArrow> e : st.tracked.entrySet()) {
            UUID id = e.getKey();
            TrackedArrow t = e.getValue();
            if (id == null || t == null) {
                continue;
            }
            if (t.stationarySinceTick == 0L) {
                continue;
            }
            Vec3d pos = t.lastPos;
            if (pos == null) {
                continue;
            }
            if ((nowTick - t.lastSeenTick) > ARROW_STALE_TICKS) {
                continue;
            }
            if (pos.squaredDistanceTo(anchor) > maxRadiusSq) {
                continue;
            }
            candidates.add(id);
        }

        return candidates.stream()
                .min(Comparator
                        .comparing((UUID id) -> {
                            TrackedArrow t = st.tracked.get(id);
                            return t == null || !t.mandatoryRecovery;
                        })
                        .thenComparingDouble(id -> {
                            TrackedArrow t = st.tracked.get(id);
                            if (t == null || t.lastPos == null) {
                                return Double.POSITIVE_INFINITY;
                            }
                            return t.lastPos.squaredDistanceTo(bot.getX(), bot.getY(), bot.getZ());
                        }))
                .orElse(null);
    }

    private static void refreshTridentRecoveryState(ServerPlayerEntity bot, BotState st) {
        if (bot == null || st == null || !st.outstandingTridentRecovery) {
            return;
        }
        if (countTridents(bot) >= st.expectedTridentCount) {
            st.outstandingTridentRecovery = false;
            st.expectedTridentCount = 0;
            st.tracked.entrySet().removeIf(entry -> {
                TrackedArrow tracked = entry.getValue();
                return tracked != null && tracked.trident;
            });
            if (st.chasingArrowId != null) {
                TrackedArrow chasing = st.tracked.get(st.chasingArrowId);
                if (chasing == null || chasing.trident) {
                    st.chasingArrowId = null;
                    st.chaseStartTick = -1L;
                }
            }
        }
    }

    private static int countTridents(ServerPlayerEntity bot) {
        if (bot == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < bot.getInventory().size(); slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isOf(Items.TRIDENT)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
