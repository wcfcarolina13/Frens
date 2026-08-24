package net.wcfcarolina13.GameAI.souls;

import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.ChatUtils.BotMoodManager;
import net.wcfcarolina13.Entity.EntityDetails;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.State;
import net.wcfcarolina13.GameAI.services.BotAutoReturnSunsetService;
import net.wcfcarolina13.GameAI.services.BotCombatCalloutService;
import net.wcfcarolina13.GameAI.services.BotFleeService;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.BotIdleHobbiesService;
import net.wcfcarolina13.GameAI.services.BotQuestService;
import net.wcfcarolina13.GameAI.services.HuntSessionService;
import net.wcfcarolina13.GameAI.services.MountPersistenceService;
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;
import net.wcfcarolina13.GameAI.services.TaskService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Projects live, authoritative Frens/Minecraft state into the immutable
 * {@link SoulTypes.GroundingSnapshot} the soul-communication pipeline is grounded on.
 *
 * <p>{@link #capture(MinecraftServer, ServerPlayerEntity, ServerPlayerEntity, SoulTypes.Reachability)}
 * is server-thread-only: it reads live entity/world state and must never be called off-thread.
 * The remaining static helpers on this class are pure functions of primitives — no Minecraft/Fabric
 * classes are touched at class-init or referenced by their signatures — so they can be exercised by
 * plain unit tests without a running Minecraft server.
 */
public final class SoulSnapshotBuilder {

    /** Grounding never exposes more than this many resource-stack summaries to a soul prompt. */
    static final int MAX_RESOURCE_SUMMARY = 6;

    private SoulSnapshotBuilder() {
    }

    // ─────── Server-thread capture (touches live entities/world) ───────

    /**
     * Reads live bot/player/world state on the server thread and projects it into an immutable
     * {@link SoulTypes.GroundingSnapshot}.
     *
     * <p>{@code reachability} must already be classified (see
     * {@code CompanionCommunicationPolicy.classifySoulReachability}) before calling this method.
     * A turn that resolved to {@link SoulTypes.Reachability#UNREACHABLE} must never reach capture —
     * calling with that value throws {@link IllegalArgumentException} rather than silently
     * fabricating a snapshot.
     *
     * @param server the server, required for recruitment-state lookups
     * @param bot the bot whose state is being captured
     * @param player the local player, or {@code null} when there is no shared presence (REMOTE)
     * @param reachability the pre-classified reachability for this turn
     */
    public static SoulTypes.GroundingSnapshot capture(MinecraftServer server, ServerPlayerEntity bot,
            ServerPlayerEntity player, SoulTypes.Reachability reachability) {
        Objects.requireNonNull(reachability, "reachability");
        if (reachability == SoulTypes.Reachability.UNREACHABLE) {
            throw new IllegalArgumentException(
                    "An UNREACHABLE turn must never reach snapshot capture.");
        }
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(bot, "bot");

        SoulTypes.BotSnapshot botSnapshot = captureBot(server, bot);
        SoulTypes.PlayerSnapshot playerSnapshot =
                (reachability == SoulTypes.Reachability.LOCAL && player != null)
                        ? capturePlayer(bot, player)
                        : null;
        SoulTypes.SituationSnapshot situationSnapshot = captureSituation(server, bot);

        return assemble(botSnapshot, playerSnapshot, situationSnapshot, reachability, Instant.now());
    }

    private static SoulTypes.BotSnapshot captureBot(MinecraftServer server, ServerPlayerEntity bot) {
        String botAlias = bot.getName().getString();
        ServerWorld world = (ServerWorld) bot.getEntityWorld();
        BlockPos pos = bot.getBlockPos();

        String dimension = world.getRegistryKey().getValue().toString();
        String biome = world.getBiome(pos).getKey().map(k -> k.getValue().getPath()).orElse("");
        boolean skyVisible = world.isSkyVisible(pos.up(2));
        long timeOfDay = Math.floorMod(world.getTimeOfDay(), 24_000L);
        String weather = world.isThundering() ? "thundering" : (world.isRaining() ? "raining" : "clear");

        int occupiedSlots = 0;
        Map<String, Integer> resourceCounts = new LinkedHashMap<>();
        int inventorySlots = bot.getInventory().size();
        for (int slot = 0; slot < inventorySlots; slot++) {
            ItemStack stack = bot.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            occupiedSlots++;
            resourceCounts.merge(stack.getName().getString(), stack.getCount(), Integer::sum);
        }

        Optional<TaskService.ActiveTaskInfo> taskInfo = TaskService.getActiveTaskInfo(bot.getUuid());
        String activeTask = taskInfo.map(TaskService.ActiveTaskInfo::name).orElse("");
        String taskState = taskInfo.map(info -> info.state().name()).orElse("");
        String behaviorMode = BotEventHandler.isFollowingPlayer(bot) ? "following" : "idle";

        String homeName = BotHomeService.getPreferredHomeBaseLabel(bot).orElse("");

        String ownerName = "";
        ManualConfig.BotOwnership ownership = Frens.CONFIG != null ? Frens.CONFIG.getOwner(botAlias) : null;
        if (ownership != null && ownership.ownerName() != null) {
            ownerName = ownership.ownerName();
        }

        boolean recruited = false;
        int companionQuestStage = 0;
        boolean permanentCompanion = false;
        try {
            if (Frens.CONFIG != null) {
                ManualConfig.SurvivalRecruitmentState state = SurvivalRecruitmentService.getState(server);
                if (state != null && botAlias.equalsIgnoreCase(state.getBotAlias())) {
                    recruited = state.isRecruited();
                    companionQuestStage = state.getCompanionQuestStage();
                    permanentCompanion = state.isPermanentCompanion();
                    if (ownerName.isEmpty() && state.getRecruitedByName() != null) {
                        ownerName = state.getRecruitedByName();
                    }
                }
            }
        } catch (Throwable ignored) {
            // Recruitment state is best-effort context, never load-bearing for capture.
        }

        Optional<SoulTypes.QuestSnapshot> activeQuest = BotQuestService.getActiveQuestSnapshot(bot.getUuid())
                .map(q -> new SoulTypes.QuestSnapshot(q.id(), q.intent(), q.actionIndex(), q.actionCount(), q.expiresTick()));

        return new SoulTypes.BotSnapshot(bot.getUuid(), botAlias, dimension, biome,
                roundToEight(pos.getX()), roundToEight(pos.getY()), roundToEight(pos.getZ()), skyVisible,
                timePhase(timeOfDay), weather, bot.getHealth(), bot.getMaxHealth(),
                bot.getHungerManager().getFoodLevel(), bot.getArmor(), heldItemName(bot),
                occupiedSlots, inventorySlots, topResourceSummary(resourceCounts),
                BotMoodManager.getMoodDescription(bot), behaviorMode, activeTask, taskState,
                homeName, ownerName, recruited, companionQuestStage, permanentCompanion, activeQuest);
    }

    private static SoulTypes.PlayerSnapshot capturePlayer(ServerPlayerEntity bot, ServerPlayerEntity player) {
        double dx = player.getX() - bot.getX();
        double dz = player.getZ() - bot.getZ();
        int distanceBlocks = (int) Math.round(Math.sqrt(player.squaredDistanceTo(bot)));

        return new SoulTypes.PlayerSnapshot(player.getUuid(), player.getName().getString(), distanceBlocks,
                cardinalDirection(dx, dz), player.getHealth(), player.getMaxHealth(),
                player.getHungerManager().getFoodLevel(), heldItemName(player), player.isSleeping());
    }

    private static String heldItemName(ServerPlayerEntity player) {
        ItemStack held = player.getMainHandStack();
        return held.isEmpty() ? "bare hands" : held.getName().getString();
    }

    /**
     * Reads live danger/entity/combat/survival/companion/mount/base/hunt/hobby state on the
     * server thread and projects it into plain {@link SituationInputs}, then hands off to the
     * pure {@link #buildSituation(SituationInputs)} seam for filtering/sorting/day-floor math.
     *
     * <p>Every source group is wrapped defensively: a throwing or absent source falls back to
     * that group's neutral default rather than failing the whole capture, mirroring
     * {@link #captureBot(MinecraftServer, ServerPlayerEntity)}'s recruitment-read style.
     */
    private static SoulTypes.SituationSnapshot captureSituation(MinecraftServer server, ServerPlayerEntity bot) {
        long nowEpochMs = System.currentTimeMillis();

        double dangerDistance = -1.0D;
        List<RawEntity> entities = List.of();
        boolean enclosed = false;
        boolean hasHeadroom = false;
        boolean hasEscapeRoute = false;
        try {
            State state = BotEventHandler.createInitialState(bot);
            dangerDistance = state.getDistanceToDangerZone();
            enclosed = state.isEnclosed();
            hasHeadroom = state.hasHeadroom();
            hasEscapeRoute = state.hasEscapeRoute();
            List<EntityDetails> nearby = state.getNearbyEntities();
            if (nearby != null) {
                List<RawEntity> collected = new ArrayList<>(nearby.size());
                for (EntityDetails e : nearby) {
                    collected.add(new RawEntity(e.getName(), e.isHostile(),
                            e.getX() - bot.getX(), e.getY() - bot.getY(), e.getZ() - bot.getZ(),
                            e.getDirectionToBot()));
                }
                entities = collected;
            }
        } catch (Throwable ignored) {
            // Danger/entity/enclosure state is best-effort context, never load-bearing for capture.
        }

        String behaviorMode = "";
        try {
            BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
            behaviorMode = mode != null ? mode.name() : "";
        } catch (Throwable ignored) {
        }

        boolean inCombat = false;
        boolean postCombatLinger = false;
        int recentKillCount = 0;
        try {
            inCombat = BotCombatCalloutService.isInCombat(bot.getUuid());
            postCombatLinger = BotCombatCalloutService.isInPostCombatLingerWindow(bot);
            recentKillCount = BotCombatCalloutService.getRecentKillPositions(bot.getUuid()).size();
        } catch (Throwable ignored) {
        }

        boolean inShelter = false;
        boolean surfaceRecoveryActive = false;
        boolean breakingFree = false;
        try {
            inShelter = BotFleeService.isInShelter(bot.getUuid());
            surfaceRecoveryActive = BotFleeService.isSurfaceRecoveryActive(bot.getUuid());
            breakingFree = BotFleeService.isBreakingFree(bot.getUuid());
        } catch (Throwable ignored) {
        }

        boolean nightTravelActive = false;
        try {
            nightTravelActive = BotAutoReturnSunsetService.isNightTravelSessionActive(bot.getUuid());
        } catch (Throwable ignored) {
        }

        long recruitedAtEpochMs = 0L;
        int deathCount = -1;
        try {
            if (Frens.CONFIG != null) {
                String botAlias = bot.getName().getString();
                ManualConfig.SurvivalRecruitmentState recruitmentState = SurvivalRecruitmentService.getState(server);
                if (recruitmentState != null && botAlias.equalsIgnoreCase(recruitmentState.getBotAlias())) {
                    recruitedAtEpochMs = recruitmentState.getRecruitedAtEpochMs();
                    deathCount = recruitmentState.getCompanionDeathCount();
                }
            }
        } catch (Throwable ignored) {
            // Recruitment state is best-effort context, never load-bearing for capture.
        }

        Optional<SoulTypes.MountSummary> mount = Optional.empty();
        try {
            MountPersistenceService.MountState mountState = MountPersistenceService.getRecordedState(bot);
            if (mountState != null) {
                // MountState has no persisted maxHealth; mirror health so a consumer computing a
                // health fraction sees "healthy" rather than dividing by zero.
                mount = Optional.of(new SoulTypes.MountSummary(mountState.mountType(),
                        mountState.health(), mountState.health(), mountState.saddled()));
            }
        } catch (Throwable ignored) {
        }

        int knownBaseCount = 0;
        Optional<String> lastSleepLabel = Optional.empty();
        try {
            ServerWorld world = (ServerWorld) bot.getEntityWorld();
            List<BotHomeService.BaseEntry> bases = BotHomeService.listBases(server, world);
            knownBaseCount = bases.size();
            Optional<BlockPos> sleepPos = BotHomeService.getLastSleep(bot);
            if (sleepPos.isPresent()) {
                BlockPos pos = sleepPos.get();
                double bestDistanceSq = 32.0D * 32.0D;
                String bestLabel = null;
                for (BotHomeService.BaseEntry base : bases) {
                    double distanceSq = pos.getSquaredDistance(base.pos());
                    if (distanceSq <= bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        bestLabel = base.label();
                    }
                }
                lastSleepLabel = Optional.ofNullable(bestLabel);
            }
        } catch (Throwable ignored) {
        }

        Optional<SoulTypes.HuntSummary> hunt = Optional.empty();
        try {
            HuntSessionService.HuntSession session = HuntSessionService.getSession(bot.getUuid());
            if (session != null) {
                List<String> targetIds = session.targetIds();
                String target;
                if (targetIds != null && !targetIds.isEmpty()) {
                    target = targetIds.get(0);
                } else {
                    target = session.zoneName() != null ? session.zoneName() : "";
                }
                hunt = Optional.of(new SoulTypes.HuntSummary(target, session.killsCompleted(), session.killsTarget()));
            }
        } catch (Throwable ignored) {
        }

        Optional<String> lastHobby = Optional.empty();
        try {
            lastHobby = Optional.ofNullable(BotIdleHobbiesService.getLastHobbyName(bot.getUuid()));
        } catch (Throwable ignored) {
        }

        SituationInputs inputs = new SituationInputs(dangerDistance, entities, enclosed, hasHeadroom, hasEscapeRoute,
                behaviorMode, inCombat, postCombatLinger, recentKillCount,
                inShelter, surfaceRecoveryActive, breakingFree, nightTravelActive,
                recruitedAtEpochMs, deathCount, nowEpochMs,
                mount, knownBaseCount, lastSleepLabel, hunt, lastHobby);
        return buildSituation(inputs);
    }

    // ─────── Pure projection seam (no Minecraft classes touched) ───────

    static SoulTypes.GroundingSnapshot assemble(SoulTypes.BotSnapshot bot, SoulTypes.PlayerSnapshot player,
            SoulTypes.SituationSnapshot situation, SoulTypes.Reachability reachability, Instant capturedAt) {
        Optional<SoulTypes.PlayerSnapshot> playerOpt = reachability == SoulTypes.Reachability.REMOTE
                ? Optional.empty()
                : Optional.ofNullable(player);
        return new SoulTypes.GroundingSnapshot(reachability, bot, playerOpt, situation, capturedAt);
    }

    static SoulTypes.GroundingSnapshot assemble(SoulTypes.BotSnapshot bot, SoulTypes.PlayerSnapshot player,
            SoulTypes.Reachability reachability, Instant capturedAt) {
        return assemble(bot, player, SoulTypes.SituationSnapshot.empty(), reachability, capturedAt);
    }

    /** Plain values pulled from live entity/EntityDetails state for one nearby entity. No Minecraft types. */
    record RawEntity(String name, boolean hostile, double dx, double dy, double dz, String direction) {
        RawEntity {
            name = name == null ? "" : name;
            direction = direction == null ? "" : direction;
        }
    }

    /**
     * Plain-value carrier for {@link #buildSituation(SituationInputs)}. No Minecraft/Fabric
     * classes — every field is a primitive, String, or an already-pure {@code SoulTypes} record
     * — so the pure seam can be exercised by plain unit tests without a running Minecraft server.
     */
    record SituationInputs(
            double dangerDistance,
            List<RawEntity> entities,
            boolean enclosed, boolean hasHeadroom, boolean hasEscapeRoute,
            String behaviorMode,
            boolean inCombat, boolean postCombatLinger, int recentKillCount,
            boolean inShelter, boolean surfaceRecoveryActive, boolean breakingFree,
            boolean nightTravelActive,
            long recruitedAtEpochMs, int deathCount, long nowEpochMs,
            Optional<SoulTypes.MountSummary> mount,
            int knownBaseCount,
            Optional<String> lastSleepLabel,
            Optional<SoulTypes.HuntSummary> hunt,
            Optional<String> lastHobby) {
        SituationInputs {
            entities = entities == null ? List.of() : List.copyOf(entities);
            behaviorMode = behaviorMode == null ? "" : behaviorMode;
            mount = mount == null ? Optional.empty() : mount;
            lastSleepLabel = lastSleepLabel == null ? Optional.empty() : lastSleepLabel;
            hunt = hunt == null ? Optional.empty() : hunt;
            lastHobby = lastHobby == null ? Optional.empty() : lastHobby;
        }
    }

    /**
     * Pure transform: filters to hostiles, computes rounded 3D distance, sorts nearest-first and
     * caps at 5; floors {@code companionDays} from epoch millis; passes every other group through
     * unchanged. Contains all filtering/sorting/capping/day-floor/default logic for the situation
     * snapshot — {@link #captureSituation(MinecraftServer, ServerPlayerEntity)} only reads and forwards.
     */
    static SoulTypes.SituationSnapshot buildSituation(SituationInputs inputs) {
        int dangerDistance = inputs.dangerDistance() <= 0.0D
                ? -1
                : (int) Math.round(inputs.dangerDistance());

        List<SoulTypes.HostileSighting> hostiles = inputs.entities().stream()
                .filter(RawEntity::hostile)
                .map(e -> new SoulTypes.HostileSighting(e.name(), e.direction(),
                        (int) Math.round(Math.sqrt(e.dx() * e.dx() + e.dy() * e.dy() + e.dz() * e.dz()))))
                .sorted(Comparator.comparingInt(SoulTypes.HostileSighting::distanceBlocks))
                .limit(5)
                .collect(Collectors.toList());

        int companionDays = inputs.recruitedAtEpochMs() <= 0L
                ? -1
                : (int) Math.floorDiv(inputs.nowEpochMs() - inputs.recruitedAtEpochMs(), 86_400_000L);

        return new SoulTypes.SituationSnapshot(dangerDistance, hostiles,
                inputs.enclosed(), inputs.hasHeadroom(), inputs.hasEscapeRoute(),
                inputs.behaviorMode(), inputs.inCombat(), inputs.postCombatLinger(), inputs.recentKillCount(),
                inputs.inShelter(), inputs.surfaceRecoveryActive(), inputs.breakingFree(),
                inputs.nightTravelActive(), companionDays, inputs.deathCount(),
                inputs.mount(), inputs.knownBaseCount(), inputs.lastSleepLabel(), inputs.hunt(), inputs.lastHobby());
    }

    // ─────── Pure helpers (unit-testable without a Minecraft server) ───────

    /** Rounds a coordinate to the nearest 8-block increment. */
    static int roundToEight(int value) {
        return (int) Math.round(value / 8.0D) * 8;
    }

    /** Resolves the compass direction of {@code (dx, dz)} to its nearest of 8 points. North is -Z, east is +X. */
    static String cardinalDirection(double dx, double dz) {
        if (dx == 0.0D && dz == 0.0D) {
            return "here";
        }
        double degrees = Math.toDegrees(Math.atan2(dx, -dz));
        degrees = (degrees + 360.0D) % 360.0D;
        String[] points = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        int index = (int) Math.round(degrees / 45.0D) % points.length;
        return points[index];
    }

    /** Buckets a vanilla time-of-day tick value into a coarse phase label. */
    static String timePhase(long timeOfDay) {
        long tod = Math.floorMod(timeOfDay, 24_000L);
        if (tod < 12_000L) {
            return "day";
        }
        if (tod < 13_000L) {
            return "dusk";
        }
        if (tod < 23_000L) {
            return "night";
        }
        return "dawn";
    }

    /** Formats and caps a resource-count map to at most {@link #MAX_RESOURCE_SUMMARY} entries, highest count first. */
    static List<String> topResourceSummary(Map<String, Integer> resourceCounts) {
        return resourceCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(MAX_RESOURCE_SUMMARY)
                .map(entry -> entry.getValue() + "x " + entry.getKey())
                .collect(Collectors.toList());
    }
}
