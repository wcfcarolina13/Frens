package net.wcfcarolina13.GameAI.souls;

import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.ChatUtils.BotMoodManager;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.BotQuestService;
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;
import net.wcfcarolina13.GameAI.services.TaskService;

import java.time.Instant;
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

        return assemble(botSnapshot, playerSnapshot, reachability, Instant.now());
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

    // ─────── Pure projection seam (no Minecraft classes touched) ───────

    static SoulTypes.GroundingSnapshot assemble(SoulTypes.BotSnapshot bot, SoulTypes.PlayerSnapshot player,
            SoulTypes.Reachability reachability, Instant capturedAt) {
        Optional<SoulTypes.PlayerSnapshot> playerOpt = reachability == SoulTypes.Reachability.REMOTE
                ? Optional.empty()
                : Optional.ofNullable(player);
        return new SoulTypes.GroundingSnapshot(reachability, bot, playerOpt, capturedAt);
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
