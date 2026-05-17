package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last-ditch rescue layer for bots that are effectively unrecoverable by local survival logic.
 */
public final class BotEmergencyRescueService {

    private static final Logger LOGGER = LoggerFactory.getLogger("bot-emergency-rescue");

    private static final long DIRE_STICKY_TICKS = 120L;
    private static final long RESCUE_RETRY_BACKOFF_TICKS = 20L * 20L;
    private static final long ARRIVAL_TIMEOUT_TICKS = 20L * 45L;
    private static final long SURFACE_FAILURE_WINDOW_TICKS = 20L * 30L;
    private static final long LOCAL_RESCUE_NO_PROGRESS_TICKS = 20L * 12L;
    private static final long DELAYED_RESCUE_COOLDOWN_TICKS = 20L * 120L;
    private static final long TELEPORT_RESCUE_COOLDOWN_TICKS = 20L * 300L;
    private static final long ESCORT_RESCUE_COOLDOWN_TICKS = 20L * 180L;
    private static final long ESCORT_STATUS_INTERVAL_TICKS = 20L * 10L;
    private static final int STARVING_THRESHOLD = 5;
    private static final int RISKY_DESCENT_Y_GAP = 8;
    private static final int DIRE_DEPTH_GAP = 12;
    private static final int MIN_SCAFFOLD_FOR_ESCAPE = 4;
    private static final int HELPER_MIN_FOOD_LEVEL = 15;
    private static final int HELPER_MIN_EDIBLE_SERVINGS = 4;
    private static final double HELPER_HOSTILE_RADIUS = 8.0D;
    private static final double HELPER_HOSTILE_RADIUS_SQ = HELPER_HOSTILE_RADIUS * HELPER_HOSTILE_RADIUS;
    private static final double HELPER_BASE_RANGE_SQ = 32.0D * 32.0D;
    private static final double HELPER_BED_RANGE_SQ = 24.0D * 24.0D;
    private static final double HELPER_CHEST_RANGE_SQ = 16.0D * 16.0D;
    private static final double HELPER_SAFE_RADIUS = 4.0D;
    private static final double CHEST_FOOD_SCAN_RADIUS = 12.0D;
    private static final double ALLY_FOOD_RADIUS = 10.0D;
    public record DireStraitsAssessment(boolean dire,
                                        boolean trapped,
                                        boolean starving,
                                        boolean hasNoFoodPath,
                                        boolean noHardBlockTool,
                                        boolean noScaffoldBlocks,
                                        boolean recentRecoveryFailure,
                                        String reason) {
    }

    private record RescueAnchor(BlockPos pos, RegistryKey<World> worldKey, String kind, int quality) {
    }

    private record RescueCandidate(ServerPlayerEntity helper,
                                   RescueAnchor anchor,
                                   RescueMode mode,
                                   double distanceSq,
                                   int score) {
    }

    private record CandidateProbe(RescueCandidate candidate, String rejectionReason) {
    }

    private record HelperSelectionResult(RescueCandidate candidate, List<String> rejectionReasons) {
    }

    private enum RescueMode {
        SPELL_DELAYED,
        SPELL_INSTANT,
        ESCORT_FAST_TRAVEL
    }

    private record PendingArrival(String botAlias,
                                  UUID botUuid,
                                  UUID ownerUuid,
                                  String helperAlias,
                                  UUID helperUuid,
                                  long expiresTick,
                                  String trigger,
                                  RescueMode mode,
                                  String anchorKind) {
    }

    private record EscortHudNotice(UUID ownerUuid,
                                   UUID rescuedUuid,
                                   UUID helperUuid,
                                   String rescuedAlias,
                                   String helperAlias,
                                   long arrivalTick,
                                   long nextNotifyTick,
                                   String anchorKind) {
    }

    private static final Map<UUID, Long> DIRE_UNTIL_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_DIRE_REASON = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_RESCUE_ATTEMPT_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RESCUE_COOLDOWN_UNTIL_TICK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TELEPORT_RESCUE_COOLDOWN_UNTIL_TICK = new ConcurrentHashMap<>();
    private static final Map<String, PendingArrival> PENDING_ARRIVALS = new ConcurrentHashMap<>();
    private static final Map<UUID, EscortHudNotice> PENDING_ESCORT_HUD = new ConcurrentHashMap<>();

    private BotEmergencyRescueService() {
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || TaskService.isServerStopping()) {
            return;
        }
        long nowTick = server.getTicks();
        processEscortHud(server, nowTick);
        processPendingArrivals(server, nowTick);
        if (!SurvivalRecruitmentService.isAutonomousRescuesEnabled(server)) {
            return;
        }
        for (ServerPlayerEntity bot : BotEventHandler.getRegisteredBots(server)) {
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                continue;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                continue;
            }
            if (!HealingService.isStarving(bot)) {
                continue;
            }
            if (NavigationArtifactService.isTraveling(bot.getUuid())) {
                continue;
            }
            if (nowTick < NEXT_RESCUE_ATTEMPT_TICK.getOrDefault(bot.getUuid(), 0L)) {
                continue;
            }
            tryEmergencyRescue(bot, world, "tick-safety-net");
        }
    }

    public static boolean tryEmergencyRescue(ServerPlayerEntity bot, ServerWorld world, String trigger) {
        if (bot == null || world == null || bot.isRemoved() || !bot.isAlive() || bot.getCommandSource() == null) {
            return false;
        }
        MinecraftServer server = bot.getCommandSource().getServer();
        if (server == null
                || !SurvivalRecruitmentService.isAutonomousRescuesEnabled(server)
                || NavigationArtifactService.isTraveling(bot.getUuid())) {
            return false;
        }

        long nowTick = server.getTicks();
        long nextAllowed = NEXT_RESCUE_ATTEMPT_TICK.getOrDefault(bot.getUuid(), 0L);
        if (nowTick < nextAllowed || isAnyRescueCooldownActive(bot.getUuid(), nowTick)) {
            return false;
        }

        DireStraitsAssessment assessment = assessDireStraits(bot, world, nowTick);
        if (!assessment.dire()) {
            return false;
        }

        HelperSelectionResult selection = findBestHelperCandidate(bot, assessment, server, nowTick);
        RescueCandidate candidate = selection.candidate();
        if (candidate == null) {
            LAST_DIRE_REASON.put(bot.getUuid(), assessment.reason());
            NEXT_RESCUE_ATTEMPT_TICK.put(bot.getUuid(), nowTick + RESCUE_RETRY_BACKOFF_TICKS);
            LOGGER.info("Emergency rescue unavailable for {} trigger={} reason={} status=no_valid_helper",
                    bot.getName().getString(),
                    trigger,
                    assessment.reason());
            if (!selection.rejectionReasons().isEmpty()) {
                LOGGER.info("Emergency rescue helper diagnostics for {}: {}",
                        bot.getName().getString(),
                        String.join("; ", selection.rejectionReasons()));
            }
            return false;
        }

        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        TaskService.forceAbort(bot.getUuid(), "§cEmergency rescue spell invoked.");
        BotAutoReturnSunsetService.clearSession(bot.getUuid());
        BotEventHandler.stopFollowing(bot, false);
        BotFleeService.clearShelter(bot.getUuid());
        BotFleeService.clearInterruptedSurvival(bot.getUuid());

        boolean cast = switch (candidate.mode()) {
            case SPELL_INSTANT -> castInstantRescue(server, bot, candidate, ownerUuid, trigger);
            case SPELL_DELAYED -> castDelayedRescue(server, bot, candidate, ownerUuid, trigger);
            case ESCORT_FAST_TRAVEL -> castEscortRescue(server, bot, candidate, ownerUuid, trigger);
        };
        NEXT_RESCUE_ATTEMPT_TICK.put(bot.getUuid(), nowTick + RESCUE_RETRY_BACKOFF_TICKS);
        if (cast) {
            DIRE_UNTIL_TICK.remove(bot.getUuid());
            LAST_DIRE_REASON.remove(bot.getUuid());
        }
        return cast;
    }

    public static DireStraitsAssessment assessDireStraits(ServerPlayerEntity bot, ServerWorld world, long nowTick) {
        if (bot == null || world == null) {
            return new DireStraitsAssessment(false, false, false, false, false, false, false, "invalid");
        }

        boolean starving = HealingService.isStarving(bot)
                || (bot.getHungerManager().getFoodLevel() <= 3 && bot.getHealth() <= 4.0F);
        boolean operationalSurface = SafePositionService.isOperationalSurface(world, bot.getBlockPos());
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING, bot.getBlockX(), bot.getBlockZ()) - 1;
        boolean deepBelowColumnSurface = (topY - bot.getBlockY()) >= DIRE_DEPTH_GAP;
        SafePositionService.SurfaceCandidateAssessment surfaceAssessment =
                SafePositionService.analyzeSurfaceCandidate(world, bot.getBlockPos());
        boolean openPitTrap = surfaceAssessment.openSky()
                && (surfaceAssessment.steepDropNeighbors() >= 2
                || surfaceAssessment.blockedCardinals() >= 3
                || !surfaceAssessment.nearSurface());
        boolean trapped = !operationalSurface || deepBelowColumnSurface || openPitTrap;
        boolean recentRecoveryFailure = BotFleeService.hadRecentSurfaceRecoveryFailure(
                bot.getUuid(), nowTick, SURFACE_FAILURE_WINDOW_TICKS);
        boolean noHardBlockTool = !hasUsablePickaxe(bot);
        boolean noScaffoldBlocks = countScaffoldBlocks(bot) < MIN_SCAFFOLD_FOR_ESCAPE;
        boolean hasFoodPath = hasAnyEdibleFood(bot)
                || hasNearbyStorageFood(bot, world)
                || hasNearbyAllyFood(bot, world.getServer());

        BlockPos validatedShelter = BotFleeService.getValidatedShelterStandPos(bot);
        if (validatedShelter != null && (hasAnyEdibleFood(bot) || hasNearbyStorageFood(bot, world))) {
            return new DireStraitsAssessment(false, trapped, starving, false, noHardBlockTool,
                    noScaffoldBlocks, recentRecoveryFailure, "sheltered_with_food");
        }

        boolean dire = starving
                && trapped
                && !hasFoodPath
                && (recentRecoveryFailure || deepBelowColumnSurface || noHardBlockTool || noScaffoldBlocks);

        Long stickyUntil = DIRE_UNTIL_TICK.get(bot.getUuid());
        if (!dire && stickyUntil != null && stickyUntil > nowTick && starving && trapped && !hasFoodPath) {
            dire = true;
        }
        if (dire) {
            DIRE_UNTIL_TICK.put(bot.getUuid(), nowTick + DIRE_STICKY_TICKS);
        } else {
            DIRE_UNTIL_TICK.remove(bot.getUuid());
        }

        String reason;
        if (!starving) {
            reason = "not_starving";
        } else if (!trapped) {
            reason = "not_trapped";
        } else if (hasFoodPath) {
            reason = "food_path_available";
        } else if (recentRecoveryFailure) {
            reason = "vertical_deadlock";
        } else if (noHardBlockTool) {
            reason = "no_hard_block_tool";
        } else if (noScaffoldBlocks) {
            reason = "no_scaffold_blocks";
        } else if (deepBelowColumnSurface) {
            reason = "deep_vertical_gap";
        } else {
            reason = "no_food_path";
        }
        LAST_DIRE_REASON.put(bot.getUuid(), reason);
        return new DireStraitsAssessment(dire, trapped, starving, !hasFoodPath, noHardBlockTool,
                noScaffoldBlocks, recentRecoveryFailure, reason);
    }

    public static boolean shouldAvoidRiskyDescent(ServerPlayerEntity bot, ServerWorld world, BlockPos target) {
        if (bot == null || world == null || target == null) {
            return false;
        }
        if (target.getY() >= bot.getBlockY() - RISKY_DESCENT_Y_GAP) {
            return false;
        }
        boolean unsafe = bot.getHungerManager().getFoodLevel() <= 10
                || !hasUsablePickaxe(bot)
                || countScaffoldBlocks(bot) < MIN_SCAFFOLD_FOR_ESCAPE
                || !hasAnyEdibleFood(bot);
        if (!unsafe) {
            return false;
        }
        SafePositionService.SurfaceCandidateAssessment targetAssessment =
                SafePositionService.analyzeSurfaceCandidate(world, target);
        if (targetAssessment.openSky() && targetAssessment.nearSurface() && targetAssessment.steepDropNeighbors() <= 1) {
            return false;
        }
        return hasNearbySurfaceAnchor(bot, world);
    }

    private static boolean castDelayedRescue(MinecraftServer server,
                                             ServerPlayerEntity rescued,
                                             RescueCandidate candidate,
                                             UUID ownerUuid,
                                             String trigger) {
        ServerPlayerEntity helper = candidate.helper();
        RescueAnchor anchor = candidate.anchor();
        if (helper == null || anchor == null || anchor.pos() == null || anchor.worldKey() == null) {
            return false;
        }
        if (!hasItemCount(rescued, Items.ENDER_PEARL, 2) || !hasItemCount(helper, Items.ENDER_PEARL, 2)) {
            return false;
        }

        double distance = Vec3d.ofCenter(rescued.getBlockPos()).squaredDistanceTo(Vec3d.ofCenter(anchor.pos()));
        boolean crossDim = rescued.getEntityWorld().getRegistryKey() != anchor.worldKey();
        double multiplier = NavigationArtifactService.artifactDelayMultiplier(rescued, helper) * 2.0D;
        int delayTicks = NavigationArtifactService.calculateDelayTicks(Math.sqrt(distance), crossDim, multiplier);

        consumeItemCount(rescued, Items.ENDER_PEARL, 2);
        consumeItemCount(helper, Items.ENDER_PEARL, 2);
        NavigationArtifactService.beginEmergencyTravel(
                server,
                rescued,
                rescued.getName().getString(),
                anchor.pos(),
                anchor.worldKey(),
                delayTicks,
                ownerUuid
        );
        applyRescueCooldown(rescued.getUuid(), server.getTicks() + delayTicks + DELAYED_RESCUE_COOLDOWN_TICKS);
        applyRescueCooldown(helper.getUuid(), server.getTicks() + delayTicks + DELAYED_RESCUE_COOLDOWN_TICKS);
        scheduleArrivalStabilization(server, rescued.getName().getString(), rescued.getUuid(), ownerUuid,
                helper.getName().getString(), helper.getUuid(), trigger, RescueMode.SPELL_DELAYED, anchor.kind());
        sendOwnerHud(server, ownerUuid,
                "Rescue underway: " + rescued.getName().getString() + " -> " + helper.getName().getString()
                        + " via spell rescue, ETA " + Math.max(1, delayTicks / 20) + "s", true);
        notifyOwner(server, ownerUuid,
                "§eEmergency rescue underway: " + rescued.getName().getString() + " is fast-traveling to "
                        + helper.getName().getString() + " (" + anchor.kind() + "), ETA ~"
                        + Math.max(1, delayTicks / 20) + "s.§r");
        LOGGER.info("Emergency rescue delayed: rescued={} helper={} anchor={} trigger={}",
                rescued.getName().getString(),
                helper.getName().getString(),
                anchor.kind(),
                trigger);
        return true;
    }

    private static boolean castInstantRescue(MinecraftServer server,
                                             ServerPlayerEntity rescued,
                                             RescueCandidate candidate,
                                             UUID ownerUuid,
                                             String trigger) {
        ServerPlayerEntity helper = candidate.helper();
        RescueAnchor anchor = candidate.anchor();
        if (helper == null || anchor == null || anchor.pos() == null || anchor.worldKey() == null) {
            return false;
        }
        ServerWorld targetWorld = server.getWorld(anchor.worldKey());
        if (targetWorld == null) {
            return false;
        }
        boolean helperHasWizardTome = CompanionCommunicationPolicy.hasWizardTome(helper);
        boolean itemPaid = hasItemCount(rescued, Items.ENDER_PEARL, 2)
                && hasItemCount(rescued, Items.CHORUS_FRUIT, 2)
                && hasItemCount(helper, Items.ENDER_PEARL, 2)
                && hasItemCount(helper, Items.CHORUS_FRUIT, 2);
        if (!helperHasWizardTome && !itemPaid) {
            return false;
        }

        Vec3d center = Vec3d.ofBottomCenter(anchor.pos());
        // Bring the bot's mount along — without this, rescue strands the horse.
        // Check mount placement BEFORE consuming reagents so a refused rescue
        // doesn't burn the spell cost. If no safe spot for the mount at the
        // rescue anchor, refuse the rescue: teleporting the bot alone would
        // leave its mount in whatever danger prompted the rescue.
        if (!TravelMountHandler.coTeleportSavedMount(rescued, targetWorld, anchor.pos())) {
            sendOwnerHud(server, ownerUuid,
                    "Rescue aborted: no safe spot for " + rescued.getName().getString() + "'s mount at the destination. Reagents not consumed — try a more open anchor.", true);
            return false;
        }

        // Mount safely placed (or no mount to bring). Now consume reagents and proceed.
        if (itemPaid && !helperHasWizardTome) {
            consumeItemCount(rescued, Items.ENDER_PEARL, 2);
            consumeItemCount(rescued, Items.CHORUS_FRUIT, 2);
            consumeItemCount(helper, Items.ENDER_PEARL, 2);
            consumeItemCount(helper, Items.CHORUS_FRUIT, 2);
        }
        rescued.teleport(targetWorld, center.x, center.y, center.z,
                java.util.EnumSet.noneOf(net.minecraft.network.packet.s2c.play.PositionFlag.class),
                rescued.getYaw(),
                rescued.getPitch(),
                true);
        rescued.setVelocity(Vec3d.ZERO);
        long cooldownUntil = server.getTicks() + TELEPORT_RESCUE_COOLDOWN_TICKS;
        applyRescueCooldown(rescued.getUuid(), cooldownUntil);
        applyRescueCooldown(helper.getUuid(), cooldownUntil);
        TELEPORT_RESCUE_COOLDOWN_UNTIL_TICK.put(rescued.getUuid(), cooldownUntil);
        TELEPORT_RESCUE_COOLDOWN_UNTIL_TICK.put(helper.getUuid(), cooldownUntil);
        scheduleArrivalStabilization(server, rescued.getName().getString(), rescued.getUuid(), ownerUuid,
                helper.getName().getString(), helper.getUuid(), trigger, RescueMode.SPELL_INSTANT, anchor.kind());
        sendOwnerHud(server, ownerUuid,
                "Instant rescue: " + rescued.getName().getString() + " -> " + helper.getName().getString()
                        + (helperHasWizardTome ? " via Wizard's Tome" : " via recall"), true);
        notifyOwner(server, ownerUuid,
                "§dEmergency recall complete: " + rescued.getName().getString() + " was recalled to "
                        + helper.getName().getString() + " (" + anchor.kind() + ").§r");
        LOGGER.info("Emergency rescue instant: rescued={} helper={} anchor={} trigger={}",
                rescued.getName().getString(),
                helper.getName().getString(),
                anchor.kind(),
                trigger);
        return true;
    }

    private static boolean castEscortRescue(MinecraftServer server,
                                            ServerPlayerEntity rescued,
                                            RescueCandidate candidate,
                                            UUID ownerUuid,
                                            String trigger) {
        ServerPlayerEntity helper = candidate.helper();
        RescueAnchor anchor = candidate.anchor();
        if (helper == null || anchor == null || anchor.pos() == null || anchor.worldKey() == null) {
            return false;
        }
        if (!(helper.getEntityWorld() instanceof ServerWorld helperWorld)) {
            return false;
        }
        BlockPos helperArrival = SafePositionService.findSafeNear(helperWorld, anchor.pos(), 4);
        if (!isArrivalSafe(helperWorld, helperArrival) || helperArrival.equals(anchor.pos())) {
            helperArrival = SafePositionService.findSafeNear(helperWorld, helper.getBlockPos(), 4);
        }
        if (!isArrivalSafe(helperWorld, helperArrival)) {
            return false;
        }

        double distance = Math.sqrt(rescued.squaredDistanceTo(Vec3d.ofCenter(anchor.pos())));
        int delayTicks = NavigationArtifactService.calculateDelayTicks(distance, false, 4.0D);
        boolean started = NavigationArtifactService.beginCoordinatedEmergencyTravel(
                server,
                rescued,
                rescued.getName().getString(),
                anchor.pos(),
                anchor.worldKey(),
                helper,
                helper.getName().getString(),
                helperArrival,
                anchor.worldKey(),
                delayTicks,
                ownerUuid
        );
        if (!started) {
            return false;
        }

        long cooldownUntil = server.getTicks() + delayTicks + ESCORT_RESCUE_COOLDOWN_TICKS;
        applyRescueCooldown(rescued.getUuid(), cooldownUntil);
        applyRescueCooldown(helper.getUuid(), cooldownUntil);
        scheduleArrivalStabilization(server, rescued.getName().getString(), rescued.getUuid(), ownerUuid,
                helper.getName().getString(), helper.getUuid(), trigger, RescueMode.ESCORT_FAST_TRAVEL, anchor.kind());
        long etaSeconds = Math.max(1, delayTicks / 20);
        sendOwnerHud(server, ownerUuid,
                "Escort rescue underway: " + helper.getName().getString() + " is extracting "
                        + rescued.getName().getString() + ", ETA " + etaSeconds + "s", true);
        notifyOwner(server, ownerUuid,
                "§eEscort rescue underway: " + helper.getName().getString() + " is extracting "
                        + rescued.getName().getString() + " to " + anchor.kind() + " (ETA ~" + etaSeconds + "s).§r");
        PENDING_ESCORT_HUD.put(rescued.getUuid(),
                new EscortHudNotice(ownerUuid, rescued.getUuid(), helper.getUuid(),
                        rescued.getName().getString(), helper.getName().getString(),
                        server.getTicks() + delayTicks, server.getTicks() + ESCORT_STATUS_INTERVAL_TICKS, anchor.kind()));
        LOGGER.info("Emergency rescue escort: rescued={} helper={} anchor={} trigger={}",
                rescued.getName().getString(),
                helper.getName().getString(),
                anchor.kind(),
                trigger);
        return true;
    }

    private static void scheduleArrivalStabilization(MinecraftServer server,
                                                     String botAlias,
                                                     UUID botUuid,
                                                     UUID ownerUuid,
                                                     String helperAlias,
                                                     UUID helperUuid,
                                                     String trigger,
                                                     RescueMode mode,
                                                     String anchorKind) {
        if (server == null || botAlias == null || botAlias.isBlank()) {
            return;
        }
        long nowTick = server.getTicks();
        PENDING_ARRIVALS.put(botAlias.toLowerCase(Locale.ROOT),
                new PendingArrival(botAlias, botUuid, ownerUuid, helperAlias, helperUuid,
                        nowTick + ARRIVAL_TIMEOUT_TICKS, trigger, mode, anchorKind));
    }

    private static void processEscortHud(MinecraftServer server, long nowTick) {
        PENDING_ESCORT_HUD.entrySet().removeIf(entry -> {
            EscortHudNotice notice = entry.getValue();
            if (notice == null) {
                return true;
            }
            if (nowTick >= notice.arrivalTick()) {
                return true;
            }
            if (nowTick < notice.nextNotifyTick()) {
                return false;
            }
            boolean rescuedTraveling = NavigationArtifactService.isTraveling(notice.rescuedUuid());
            boolean helperTraveling = NavigationArtifactService.isTraveling(notice.helperUuid());
            if (!rescuedTraveling && !helperTraveling) {
                return true;
            }
            long remainingSeconds = Math.max(1L, (notice.arrivalTick() - nowTick) / 20L);
            sendOwnerHud(server, notice.ownerUuid(),
                    "Escort rescue: " + notice.helperAlias() + " -> " + notice.rescuedAlias()
                            + " ETA " + remainingSeconds + "s", true);
            entry.setValue(new EscortHudNotice(notice.ownerUuid(), notice.rescuedUuid(), notice.helperUuid(),
                    notice.rescuedAlias(), notice.helperAlias(), notice.arrivalTick(),
                    nowTick + ESCORT_STATUS_INTERVAL_TICKS, notice.anchorKind()));
            return false;
        });
    }

    private static void processPendingArrivals(MinecraftServer server, long nowTick) {
        PENDING_ARRIVALS.entrySet().removeIf(entry -> {
            PendingArrival pending = entry.getValue();
            if (pending == null) {
                return true;
            }
            if (pending.expiresTick() < nowTick) {
                return true;
            }
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(pending.botAlias());
            if (bot == null || bot.isRemoved() || !bot.isAlive()) {
                return false;
            }
            if (NavigationArtifactService.isTraveling(bot.getUuid())) {
                return false;
            }
            stabilizeArrival(server, bot, pending);
            return true;
        });
    }

    private static void stabilizeArrival(MinecraftServer server, ServerPlayerEntity bot, PendingArrival pending) {
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        PENDING_ESCORT_HUD.remove(pending.botUuid());
        BotAutoReturnSunsetService.clearSession(bot.getUuid());
        BotEventHandler.stopFollowing(bot, false);
        BotFleeService.clearInterruptedSurvival(bot.getUuid());

        boolean stabilized = HealingService.autoEat(bot)
                || BotMutualAidService.tryImmediateChestFoodRecovery(bot, world)
                || BotMutualAidService.trySeekFoodFromNearbyBot(bot, world);
        if (!stabilized && HealingService.isStarving(bot)) {
            BotAutoHuntService.requestDecisionNow(bot);
            BotFleeService.tryProactiveShelter(bot, server);
        }
        BotIdleHobbiesService.snoozeFor(bot, 100L);
        String helperName = pending.helperAlias() == null || pending.helperAlias().isBlank()
                ? "helper"
                : pending.helperAlias();
        notifyOwner(server, pending.ownerUuid(),
                "§aEmergency rescue complete: " + pending.botAlias() + " arrived near "
                        + helperName + " (" + pending.anchorKind() + ").§r");
        LOGGER.info("Emergency rescue arrival stabilized bot={} trigger={} mode={} starving={}",
                bot.getName().getString(),
                pending.trigger(),
                pending.mode(),
                HealingService.isStarving(bot));
    }

    private static HelperSelectionResult findBestHelperCandidate(ServerPlayerEntity rescued,
                                                                 DireStraitsAssessment assessment,
                                                                 MinecraftServer server,
                                                                 long nowTick) {
        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(rescued);
        if (ownerUuid == null || server == null) {
            return new HelperSelectionResult(null, List.of());
        }

        List<String> rejectionReasons = new ArrayList<>();
        RescueCandidate escortCandidate = null;
        RescueCandidate spellCandidate = null;

        for (ServerPlayerEntity other : BotEventHandler.getRegisteredBots(server)) {
            if (other == null
                    || other == rescued
                    || other.isRemoved()
                    || !other.isAlive()
                    || !ownerUuid.equals(BotTerritoryAuthorizationService.resolveBotOwnerUuid(other))) {
                continue;
            }

            String sharedRejection = helperSharedRejectionReason(other, nowTick);
            if (sharedRejection != null) {
                rejectionReasons.add(other.getName().getString() + "=" + sharedRejection);
                continue;
            }

            CandidateProbe escortProbe = buildEscortCandidate(rescued, other, assessment);
            CandidateProbe spellProbe = buildSpellCandidate(rescued, other, assessment);

            if (escortProbe.candidate() != null
                    && (escortCandidate == null || escortProbe.candidate().score() > escortCandidate.score())) {
                escortCandidate = escortProbe.candidate();
            }
            if (spellProbe.candidate() != null
                    && (spellCandidate == null || spellProbe.candidate().score() > spellCandidate.score())) {
                spellCandidate = spellProbe.candidate();
            }
            if (escortProbe.candidate() == null && spellProbe.candidate() == null) {
                rejectionReasons.add(formatHelperRejection(other, escortProbe.rejectionReason(), spellProbe.rejectionReason()));
            }
        }

        RescueCandidate selected = escortCandidate != null ? escortCandidate : spellCandidate;
        return new HelperSelectionResult(selected, rejectionReasons);
    }

    private static CandidateProbe buildEscortCandidate(ServerPlayerEntity rescued,
                                                       ServerPlayerEntity helper,
                                                       DireStraitsAssessment rescuedAssessment) {
        if (helper.getEntityWorld().getRegistryKey() != rescued.getEntityWorld().getRegistryKey()) {
            return new CandidateProbe(null, "different_dimension");
        }
        if (!(helper.getEntityWorld() instanceof ServerWorld helperWorld)) {
            return new CandidateProbe(null, "helper_world_unavailable");
        }
        if (helper.getHungerManager().getFoodLevel() < HELPER_MIN_FOOD_LEVEL) {
            return new CandidateProbe(null, "helper_too_hungry");
        }
        if (helper.getHealth() < helper.getMaxHealth() * 0.8F) {
            return new CandidateProbe(null, "helper_injured");
        }
        if (countEdibleItems(helper) < HELPER_MIN_EDIBLE_SERVINGS) {
            return new CandidateProbe(null, "insufficient_edible_food");
        }
        if (!ToolProvisionService.hasServiceableMeleeWeapon(helper)) {
            return new CandidateProbe(null, "no_serviceable_melee_weapon");
        }
        if (helper.hasVehicle()) {
            return new CandidateProbe(null, "helper_mounted");
        }
        if (!hasMapAndCompass(helper)) {
            return new CandidateProbe(null, "missing_map_compass");
        }
        RescueAnchor anchor = resolveEscortAnchor(helper, helperWorld);
        if (anchor == null || anchor.pos() == null) {
            return new CandidateProbe(null, "no_escort_anchor");
        }
        double distanceSq = rescued.squaredDistanceTo(Vec3d.ofCenter(anchor.pos()));
        int score = anchor.quality() * 1500;
        score += 1000;
        score -= (int) Math.min(400, Math.round(Math.sqrt(distanceSq)));
        if (!healingMarginLow(helper)) {
            score += 100;
        }
        if (rescuedAssessment.recentRecoveryFailure()) {
            score += 60;
        }
        return new CandidateProbe(
                new RescueCandidate(helper, anchor, RescueMode.ESCORT_FAST_TRAVEL, distanceSq, score),
                null
        );
    }

    private static CandidateProbe buildSpellCandidate(ServerPlayerEntity rescued,
                                                      ServerPlayerEntity helper,
                                                      DireStraitsAssessment rescuedAssessment) {
        if (!(helper.getEntityWorld() instanceof ServerWorld helperWorld)) {
            return new CandidateProbe(null, "helper_world_unavailable");
        }
        RescueMode mode = null;
        if (CompanionCommunicationPolicy.hasWizardTome(helper)) {
            mode = RescueMode.SPELL_INSTANT;
        } else if (hasItemCount(helper, Items.ENDER_PEARL, 2)
                && hasItemCount(helper, Items.CHORUS_FRUIT, 2)
                && hasItemCount(rescued, Items.ENDER_PEARL, 2)
                && hasItemCount(rescued, Items.CHORUS_FRUIT, 2)) {
            mode = RescueMode.SPELL_INSTANT;
        } else if (hasItemCount(helper, Items.ENDER_PEARL, 2)
                && hasItemCount(rescued, Items.ENDER_PEARL, 2)) {
            mode = RescueMode.SPELL_DELAYED;
        } else {
            return new CandidateProbe(null, "missing_spell_pair");
        }

        RescueAnchor anchor = resolveSpellAnchor(helper, helperWorld);
        if (anchor == null || anchor.pos() == null) {
            return new CandidateProbe(null, "no_valid_arrival_anchor");
        }

        double distanceSq = helper.getEntityWorld().getRegistryKey() == rescued.getEntityWorld().getRegistryKey()
                ? rescued.squaredDistanceTo(Vec3d.ofCenter(anchor.pos()))
                : 1.0E12;
        int score = anchor.quality() * 1000;
        if (helperWorld.getRegistryKey() == rescued.getEntityWorld().getRegistryKey()) {
            score += 600;
        }
        if (mode == RescueMode.SPELL_INSTANT) {
            score += 250;
        }
        if (!healingMarginLow(helper)) {
            score += 120;
        }
        score -= (int) Math.min(500, Math.round(Math.sqrt(distanceSq)));
        if (rescuedAssessment.recentRecoveryFailure()) {
            score += 80;
        }
        return new CandidateProbe(
                new RescueCandidate(helper, anchor, mode, distanceSq, score),
                null
        );
    }

    private static String helperSharedRejectionReason(ServerPlayerEntity helper, long nowTick) {
        if (helper == null || helper.getCommandSource() == null) {
            return "invalid_helper";
        }
        if (NavigationArtifactService.isTraveling(helper.getUuid())) {
            return "helper_traveling";
        }
        if (HealingService.isStarving(helper)) {
            return "helper_starving";
        }
        if (BotCombatCalloutService.isInCombat(helper.getUuid())) {
            return "helper_in_combat";
        }
        if (BotFleeService.isBreakingFree(helper.getUuid())) {
            return "helper_breaking_free";
        }
        long rescueUntil = RESCUE_COOLDOWN_UNTIL_TICK.getOrDefault(helper.getUuid(), 0L);
        if (rescueUntil > nowTick) {
            return "rescue_cooldown";
        }
        long teleportUntil = TELEPORT_RESCUE_COOLDOWN_UNTIL_TICK.getOrDefault(helper.getUuid(), 0L);
        if (teleportUntil > nowTick) {
            return "teleport_rescue_cooldown";
        }
        if (BotFleeService.hadRecentSurfaceRecoveryFailure(helper.getUuid(), nowTick, LOCAL_RESCUE_NO_PROGRESS_TICKS)) {
            return "helper_surface_recovery_unstable";
        }

        if (!(helper.getEntityWorld() instanceof ServerWorld helperWorld)) {
            return "helper_world_unavailable";
        }
        DireStraitsAssessment helperAssessment = assessDireStraits(helper, helperWorld, nowTick);
        if (helperAssessment.dire()) {
            return "helper_dire";
        }
        return null;
    }

    private static String formatHelperRejection(ServerPlayerEntity helper, String escortReason, String spellReason) {
        String helperName = helper != null ? helper.getName().getString() : "unknown";
        if (escortReason == null && spellReason == null) {
            return helperName + "=unknown_rejection";
        }
        if (Objects.equals(escortReason, spellReason)) {
            return helperName + "=" + escortReason;
        }
        if (escortReason == null) {
            return helperName + "=spell:" + spellReason;
        }
        if (spellReason == null) {
            return helperName + "=escort:" + escortReason;
        }
        return helperName + "=escort:" + escortReason + ", spell:" + spellReason;
    }

    private static RescueAnchor resolveSpellAnchor(ServerPlayerEntity helper, ServerWorld world) {
        BlockPos validatedShelter = BotFleeService.getValidatedShelterStandPos(helper);
        if (isArrivalSafe(world, validatedShelter)) {
            return new RescueAnchor(validatedShelter.toImmutable(), world.getRegistryKey(), "shelter", 4);
        }

        BlockPos base = BotHomeService.findNearestBase(helper)
                .filter(pos -> helper.getBlockPos().getSquaredDistance(pos) <= HELPER_BASE_RANGE_SQ)
                .map(pos -> SafePositionService.findSafeNear(world, pos, 5))
                .filter(pos -> isArrivalSafe(world, pos))
                .orElse(null);
        if (base != null) {
            return new RescueAnchor(base.toImmutable(), world.getRegistryKey(), "base", 3);
        }

        BlockPos bed = BotHomeService.getLastSleep(helper)
                .filter(pos -> helper.getBlockPos().getSquaredDistance(pos) <= HELPER_BED_RANGE_SQ)
                .map(pos -> SafePositionService.findSafeNear(world, pos, 4))
                .filter(pos -> isArrivalSafe(world, pos))
                .orElse(null);
        if (bed != null) {
            return new RescueAnchor(bed.toImmutable(), world.getRegistryKey(), "bed", 3);
        }

        BlockPos chest = BotChestRegistryService.listChestsForOwner(helper, world).stream()
                .map(BotChestRegistryService.ChestRecord::toBlockPos)
                .filter(pos -> pos != null
                        && helper.getBlockPos().getSquaredDistance(pos) <= HELPER_CHEST_RANGE_SQ
                        && isStorageBlock(world, pos))
                .map(pos -> SafePositionService.findSafeNear(world, pos, 3))
                .filter(pos -> isArrivalSafe(world, pos))
                .findFirst()
                .orElse(null);
        if (chest != null) {
            return new RescueAnchor(chest.toImmutable(), world.getRegistryKey(), "chest", 2);
        }

        BlockPos nearHelper = SafePositionService.findSafeNear(world, helper.getBlockPos(), (int) HELPER_SAFE_RADIUS);
        if (isArrivalSafe(world, nearHelper)) {
            return new RescueAnchor(nearHelper.toImmutable(), world.getRegistryKey(), "helper_near", 1);
        }
        return null;
    }

    private static RescueAnchor resolveEscortAnchor(ServerPlayerEntity helper, ServerWorld world) {
        BlockPos homeAnchor = BotHomeService.resolveHomeTarget(helper)
                .filter(pos -> helper.getBlockPos().getSquaredDistance(pos) <= HELPER_BASE_RANGE_SQ)
                .map(pos -> SafePositionService.findSafeNear(world, pos, 5))
                .filter(pos -> isArrivalSafe(world, pos))
                .orElse(null);
        if (homeAnchor != null) {
            return new RescueAnchor(homeAnchor.toImmutable(), world.getRegistryKey(), "base", 5);
        }
        return null;
    }

    private static boolean hasNearbySurfaceAnchor(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        if (BotFleeService.findNearbyVillageHouseAnchor(bot, world, 20) != null) {
            return true;
        }
        if (BotHomeService.findNearestBase(bot)
                .filter(pos -> bot.getBlockPos().getSquaredDistance(pos) <= 48.0D * 48.0D)
                .isPresent()) {
            return true;
        }
        if (BotHomeService.getLastSleep(bot)
                .filter(pos -> bot.getBlockPos().getSquaredDistance(pos) <= 48.0D * 48.0D)
                .isPresent()) {
            return true;
        }
        return BotChestRegistryService.listChestsForOwner(bot, world).stream()
                .map(BotChestRegistryService.ChestRecord::toBlockPos)
                .anyMatch(pos -> pos != null && bot.getBlockPos().getSquaredDistance(pos) <= 48.0D * 48.0D);
    }

    private static boolean isArrivalSafe(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        if (!SafePositionService.isOperationalSurface(world, pos) && BotFleeService.findNearbyVillageHouseAnchor(null, world, pos, 2) == null) {
            BlockPos safe = SafePositionService.findSafeNear(world, pos, 2);
            if (safe == null || !SafePositionService.isOperationalSurface(world, safe)) {
                return false;
            }
            pos = safe;
        }
        if (world.getFluidState(pos).isStill()) {
            return false;
        }
        Box threatBox = Box.from(Vec3d.ofCenter(pos)).expand(HELPER_HOSTILE_RADIUS, 4.0D, HELPER_HOSTILE_RADIUS);
        boolean hostileNearby = !world.getOtherEntities(null, threatBox, entity -> entity != null
                && net.wcfcarolina13.EntityUtil.isHostile(entity)).isEmpty();
        return !hostileNearby;
    }

    private static boolean isStorageBlock(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null || !world.isChunkLoaded(pos)) {
            return false;
        }
        return world.getBlockState(pos).isOf(Blocks.CHEST)
                || world.getBlockState(pos).isOf(Blocks.TRAPPED_CHEST)
                || world.getBlockState(pos).isOf(Blocks.BARREL);
    }

    private static boolean hasNearbyStorageFood(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        BlockPos origin = bot.getBlockPos();
        int radius = (int) Math.round(CHEST_FOOD_SCAN_RADIUS);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (origin.getSquaredDistance(pos) > CHEST_FOOD_SCAN_RADIUS * CHEST_FOOD_SCAN_RADIUS) {
                        continue;
                    }
                    if (!isStorageBlock(world, pos)) {
                        continue;
                    }
                    var be = world.getBlockEntity(pos);
                    if (!(be instanceof net.minecraft.inventory.Inventory inv)) {
                        continue;
                    }
                    for (int slot = 0; slot < inv.size(); slot++) {
                        ItemStack stack = inv.getStack(slot);
                        if (isEdibleStack(stack)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasNearbyAllyFood(ServerPlayerEntity bot, MinecraftServer server) {
        if (bot == null || server == null) {
            return false;
        }
        UUID ownerUuid = BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot);
        if (ownerUuid == null) {
            return false;
        }
        double maxSq = ALLY_FOOD_RADIUS * ALLY_FOOD_RADIUS;
        for (ServerPlayerEntity other : BotEventHandler.getRegisteredBots(server)) {
            if (other == null || other == bot || other.isRemoved() || !other.isAlive()) {
                continue;
            }
            if (!ownerUuid.equals(BotTerritoryAuthorizationService.resolveBotOwnerUuid(other))) {
                continue;
            }
            if (other.getEntityWorld() != bot.getEntityWorld()) {
                continue;
            }
            if (bot.squaredDistanceTo(other) > maxSq) {
                continue;
            }
            if (countEdibleItems(other) >= 2 && other.getHungerManager().getFoodLevel() > STARVING_THRESHOLD + 2) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyEdibleFood(ServerPlayerEntity bot) {
        return bot != null && countEdibleItems(bot) > 0;
    }

    private static int countEdibleItems(ServerPlayerEntity bot) {
        if (bot == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (isEdibleStack(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean isEdibleStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        FoodComponent food = stack.get(DataComponentTypes.FOOD);
        return food != null;
    }

    private static boolean hasMapAndCompass(ServerPlayerEntity bot) {
        return hasItemCount(bot, Items.COMPASS, 1)
                && (hasItemCount(bot, Items.MAP, 1) || hasItemCount(bot, Items.FILLED_MAP, 1));
    }

    private static boolean hasItemCount(ServerPlayerEntity bot, net.minecraft.item.Item item, int needed) {
        if (bot == null || item == null || needed <= 0) {
            return false;
        }
        int count = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                count += stack.getCount();
                if (count >= needed) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean consumeItemCount(ServerPlayerEntity bot, net.minecraft.item.Item item, int needed) {
        if (!hasItemCount(bot, item, needed)) {
            return false;
        }
        int remaining = needed;
        for (int i = 0; i < bot.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.decrement(take);
            if (stack.isEmpty()) {
                bot.getInventory().setStack(i, ItemStack.EMPTY);
            }
            remaining -= take;
        }
        return remaining <= 0;
    }

    private static boolean hasUsablePickaxe(ServerPlayerEntity bot) {
        if (bot == null) {
            return false;
        }
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            String key = stack.getItem().getTranslationKey();
            if (key != null && key.toLowerCase(Locale.ROOT).contains("pickaxe")) {
                return true;
            }
        }
        return false;
    }

    private static int countScaffoldBlocks(ServerPlayerEntity bot) {
        if (bot == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && ScaffoldService.SCAFFOLD_BLOCKS.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean healingMarginLow(ServerPlayerEntity helper) {
        return helper == null || helper.getHungerManager().getFoodLevel() <= 10 || helper.getHealth() < helper.getMaxHealth() * 0.5F;
    }

    private static void applyRescueCooldown(UUID botUuid, long untilTick) {
        if (botUuid == null) {
            return;
        }
        RESCUE_COOLDOWN_UNTIL_TICK.put(botUuid, untilTick);
    }

    private static boolean isAnyRescueCooldownActive(UUID botUuid, long nowTick) {
        if (botUuid == null) {
            return false;
        }
        long rescueUntil = RESCUE_COOLDOWN_UNTIL_TICK.getOrDefault(botUuid, 0L);
        long teleportUntil = TELEPORT_RESCUE_COOLDOWN_UNTIL_TICK.getOrDefault(botUuid, 0L);
        return rescueUntil > nowTick || teleportUntil > nowTick;
    }

    private static void notifyOwner(MinecraftServer server, UUID ownerUuid, String message) {
        if (server == null || ownerUuid == null || message == null || message.isBlank()) {
            return;
        }
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
        if (owner != null && !owner.isRemoved()) {
            owner.sendMessage(Text.literal(message), false);
        }
    }

    private static void sendOwnerHud(MinecraftServer server, UUID ownerUuid, String message, boolean actionBar) {
        if (server == null || ownerUuid == null || message == null || message.isBlank()) {
            return;
        }
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(ownerUuid);
        if (owner != null && !owner.isRemoved()) {
            owner.sendMessage(Text.literal(message), actionBar);
        }
    }
}
