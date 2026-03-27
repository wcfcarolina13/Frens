package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.GameAI.DropSweeper;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.BotEmergencyRescueService;
import net.wcfcarolina13.GameAI.services.BotMutualAidService;
import net.wcfcarolina13.GameAI.services.ChestStoreService;
import net.wcfcarolina13.GameAI.services.CraftingHelper;
import net.wcfcarolina13.GameAI.services.ToolProvisionService;
import net.wcfcarolina13.GameAI.services.HuntCatalog;
import net.wcfcarolina13.GameAI.services.HuntConfigService;
import net.wcfcarolina13.GameAI.services.HuntHistoryService;
import net.wcfcarolina13.GameAI.services.HuntSessionService;
import net.wcfcarolina13.GameAI.services.SkillResumeService;
import net.wcfcarolina13.GameAI.services.MovementService;
import net.wcfcarolina13.GameAI.services.CompanionSafeZoneService;
import net.minecraft.entity.mob.ZombieEntity;
import net.wcfcarolina13.GameAI.services.CompanionOverheadDialogueService;
import net.wcfcarolina13.GameAI.services.BotFleeService;
import net.wcfcarolina13.GameAI.services.ReturnBaseStuckService;
import net.wcfcarolina13.GameAI.services.SafePositionService;
import net.wcfcarolina13.GameAI.services.SmeltingService;
import net.wcfcarolina13.GameAI.services.BotIdleHobbiesService;
import net.wcfcarolina13.GameAI.services.construction.ScaffoldService;
import net.wcfcarolina13.GameAI.services.DebugFileLogger;
import net.wcfcarolina13.network.HuntablesNetworkManager;
import net.wcfcarolina13.GameAI.skills.Skill;
import net.wcfcarolina13.GameAI.skills.SkillContext;
import net.wcfcarolina13.GameAI.skills.SkillExecutionResult;
import net.wcfcarolina13.GameAI.skills.SkillManager;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class HuntSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-hunt");
    private static final int DEFAULT_HUNT_RADIUS = 48;
    private static final int DEFAULT_HUNT_Y_SPAN = 8;
    private static final int MAX_RELOCATION_SEGMENTS_PER_SEARCH = 3;
    private static final int FOOD_CONTAINER_RADIUS = 12;
    private static final int FOOD_CONTAINER_YSPAN = 4;
    private static final int MIN_PEACEFUL_COUNT = 3;
    private static final double ATTACK_RANGE_SQ = 9.0D;
    private static final long ATTACK_TIMEOUT_MS = 12_000L;
    private static final long SWEEP_INTERVAL_MS = 12_000L;
    private static final long HUNT_NO_PROGRESS_TIMEOUT_MS = 60_000L;
    private static final int APPROACH_FAIL_BLACKLIST_THRESHOLD = 3;
    private static final double FINAL_SWEEP_RADIUS = 14.0D;
    private static final double FINAL_SWEEP_VERTICAL = 6.0D;
    private static final float ZOMBIE_MIN_HEALTH = 16.0F; // 8 hearts
    private static final int STARVING_HUNGER = 5;
    private static final int EMERGENCY_HUNGER = 1;
    private static final float EMERGENCY_HEALTH = 2.0F;
    public static final int MIN_BACKUP_FOOD_ITEMS = 4;

    private static final Set<Item> RAW_MEAT = Set.of(
            Items.BEEF,
            Items.PORKCHOP,
            Items.CHICKEN,
            Items.MUTTON,
            Items.RABBIT,
            Items.COD,
            Items.SALMON,
            Items.TROPICAL_FISH,
            Items.PUFFERFISH
    );
    private static final String[] HOBBY_HUNT_LINES = new String[] {
            "Going for a hunt.",
            "Let me track something down.",
            "I'll bring back some food.",
            "Time to scout for wild game."
    };

    @Override
    public String name() {
        return "hunt";
    }

    @Override
    public SkillExecutionResult execute(SkillContext context) {
        ServerCommandSource source = context.botSource();
        ServerPlayerEntity bot = source.getPlayer();
        if (bot == null) {
            return SkillExecutionResult.failure("Bot not available.");
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return SkillExecutionResult.failure("I can only hunt in a loaded world.");
        }
        System.out.println("[HuntSkill] execute start bot=" + bot.getName().getString()
                + " world=" + world.getRegistryKey().getValue()
                + " thread=" + Thread.currentThread().getName());
        DebugFileLogger.log("HuntSkill.execute start bot=" + bot.getName().getString()
                + " world=" + world.getRegistryKey().getValue()
                + " thread=" + Thread.currentThread().getName());
        LOGGER.info("Hunt execute start: bot={} world={} thread={}",
                bot.getName().getString(),
                world.getRegistryKey().getValue(),
                Thread.currentThread().getName());

        ServerPlayerEntity commander = context.requestSource() != null ? context.requestSource().getPlayer() : null;
        Set<Identifier> discoveredTargets = commander != null
                ? HuntHistoryService.getHistory(commander)
                : HuntHistoryService.getWorldHistory(world);
        boolean systemOrigin = "system".equals(context.parameters().get("_origin"));

        HuntRequest request = parseRequest(context.parameters());
        LOGGER.info("Hunt request: target='{}' count={} sunset={} autoStop={}",
                request.targetName, request.targetCount, request.checkSunset, request.autoStopOnHunger);
        if (request.listOnly) {
            sendCatalogList(source, commander);
            return SkillExecutionResult.success("Hunting list sent.");
        }

        HuntCatalog.HuntTarget explicitTarget = null;
        if (request.targetName != null) {
            explicitTarget = HuntCatalog.findByName(request.targetName);
            if (explicitTarget == null) {
                LOGGER.info("Hunt target '{}' not found in catalog", request.targetName);
                return SkillExecutionResult.failure("Unknown hunt target '" + request.targetName + "'. Use 'list' to see available mobs.");
            }
            if (!systemOrigin
                    && commander != null
                    && explicitTarget.foodMob()
                    && !discoveredTargets.contains(explicitTarget.id())) {
                LOGGER.info("Hunt target '{}' not yet discovered by commander {}",
                        explicitTarget.label(),
                        commander.getName().getString());
                return SkillExecutionResult.failure("I haven't discovered " + explicitTarget.label() + " yet.");
            }
        }

        String targetLabel = explicitTarget != null ? explicitTarget.label() : "food mobs";
        String countLabel = request.targetCount == Integer.MAX_VALUE ? "until sunset" : Integer.toString(request.targetCount);
        if (request.hobby()) {
            ChatUtils.sendSystemMessage(source, "Going for a hunt.");
            CompanionOverheadDialogueService.showOverheadLine(
                    bot,
                    HOBBY_HUNT_LINES[(int) (Math.random() * HOBBY_HUNT_LINES.length)],
                    3_000,
                    32.0D,
                    "hunt-hobby-start",
                    "ambient"
            );
        }
        ChatUtils.sendSystemMessage(source, "Hunting " + targetLabel + " (" + countLabel + ").");

        // Check for resumed multi-day hunt session
        boolean isResume = SkillResumeService.consumeResumeIntent(bot.getUuid());
        HuntSessionService.HuntSession resumedSession = isResume
                ? HuntSessionService.consumeSession(bot.getUuid()) : null;
        int resumedKills = 0;
        BlockPos huntOriginPos = bot.getBlockPos();

        if (resumedSession != null) {
            resumedKills = resumedSession.killsCompleted();
            huntOriginPos = resumedSession.huntOrigin();
            LOGGER.info("Resuming hunt session: kills={}/{} origin={}",
                    resumedKills, resumedSession.killsTarget(), huntOriginPos);
            ChatUtils.sendSystemMessage(source, "Resuming yesterday's hunt (" + resumedKills + " kills so far).");

            // Travel back to hunting grounds
            if (huntOriginPos != null && bot.getBlockPos().getSquaredDistance(huntOriginPos) > 16.0D) {
                Optional<MovementService.MovementPlan> plan =
                        MovementService.planLootApproach(bot, huntOriginPos, MovementService.MovementOptions.skillLoot());
                if (plan.isPresent()) {
                    MovementService.execute(source, bot, plan.get(), Boolean.FALSE, true);
                }
            }
        }

        HuntConfigService.HuntConfig huntConfig = HuntConfigService.getConfig(bot);
        HuntConfigService.HuntZone huntZone = huntConfig.huntZone();
        int huntRadius = huntZone.radius;
        int huntYSpan = huntZone.ySpan;
        boolean depopulationEnabled = huntConfig.depopulationEnabled;
        List<String> selectedTargets = huntConfig.selectedTargets;

        // Pre-hunt inventory check: if nearly full, announce and place a chest
        ensureHuntingSupplies(bot, world, source, commander);

        // Snapshot inventory AFTER prerequisites so woodcut logs aren't counted as hunt loot
        Map<Item, Integer> preHuntInventory = snapshotInventory(bot);

        // Ensure at surface — underground scans miss surface food mobs entirely
        if (!BotFleeService.isAtSurface(bot, world)) {
            LOGGER.info("Hunt: {} underground — escaping to surface before scan",
                    bot.getName().getString());
            if (!BotFleeService.ensureAtSurface(bot, world)) {
                return SkillExecutionResult.failure("Could not reach the surface for hunting.");
            }
        }

        List<BlockPos> anchors = buildHuntAnchors(bot, world, huntRadius);
        LOGGER.info("Hunt anchors: {}", anchors.size());
        List<BlockPos> searchSegments = buildHuntSearchSegments(huntOriginPos, huntRadius);
        int nextSearchSegmentIndex = searchSegments.size() > 1 ? 1 : 0;

        HuntCandidate prefoundCandidate = findCandidate(world, bot, anchors, discoveredTargets,
                explicitTarget, request.hobby(), depopulationEnabled, selectedTargets,
                huntRadius, huntYSpan);
        if (prefoundCandidate == null) {
            SearchRelocationResult relocation = relocateAndScanForCandidate(
                    source,
                    bot,
                    world,
                    discoveredTargets,
                    explicitTarget,
                    request,
                    depopulationEnabled,
                    selectedTargets,
                    huntRadius,
                    huntYSpan,
                    searchSegments,
                    nextSearchSegmentIndex,
                    MAX_RELOCATION_SEGMENTS_PER_SEARCH
            );
            prefoundCandidate = relocation.candidate();
            nextSearchSegmentIndex = relocation.nextSegmentIndex();
            anchors = buildHuntAnchors(bot, world, huntRadius);
            if (prefoundCandidate == null) {
                anchors = buildHuntAnchors(bot, world, huntRadius);
                prefoundCandidate = performVantageScan(bot, world, anchors, discoveredTargets,
                        explicitTarget, request, depopulationEnabled, selectedTargets,
                        huntRadius, huntYSpan);
            }
        }

        long lastSweep = System.currentTimeMillis();
        int kills = resumedKills;

        // Blacklist tracking: skip targets the bot can't reach to avoid infinite retry loops
        Set<UUID> blacklistedTargets = new HashSet<>();
        int consecutiveApproachFails = 0;
        UUID lastFailedTargetId = null;
        UUID lockedTargetId = null;
        HuntCatalog.HuntTarget lockedTargetType = null;
        long huntLoopStartMs = System.currentTimeMillis();
        // Capture day/night at hunt start so a nighttime zombie hunt isn't aborted immediately
        boolean startedAtDay = world.isDay();

        while (kills < request.targetCount) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Hunt paused by another task.");
            }
            anchors = buildHuntAnchors(bot, world, huntRadius);

            if (request.checkSunset && startedAtDay && isSunset(world)) {
                // Multi-day hunt: save session for sunrise resume
                if (kills < request.targetCount && BotHomeService.isAutoReturnAtSunset(bot)) {
                    HuntSessionService.saveSession(bot, huntOriginPos, null,
                            selectedTargets, kills, request.targetCount,
                            huntZone.name(), depopulationEnabled,
                            request.targetName != null ? request.targetName : "");
                    SkillResumeService.recordExecution(bot, "hunt",
                            request.targetName != null ? request.targetName : "", source);
                    SkillResumeService.requestAutoResume(bot);
                    ChatUtils.sendSystemMessage(source,
                            "Sun's setting. Heading home. I'll resume the hunt tomorrow. ("
                                    + kills + " kill" + (kills != 1 ? "s" : "") + " so far)");
                } else {
                    ChatUtils.sendSystemMessage(source, "Sun has set. Stopping hunt.");
                }
                break;
            }

            if (isLateEveningBeforeSunset(world)) {
                BotMutualAidService.tryRegroupWithLastInteraction(bot, world);
            }

            if (maybeEatEmergencyFood(bot, world)) {
                // Give the eat animation a moment to settle.
                sleep(400L);
            }

            if (bot.getHungerManager().getFoodLevel() <= STARVING_HUNGER
                    && BotMutualAidService.trySeekFoodFromNearbyBot(bot, world)) {
                sleep(900L);
                if (maybeEatEmergencyFood(bot, world)) {
                    sleep(400L);
                }
            }

            if (request.autoStopOnHunger
                    && bot.getHungerManager().getFoodLevel() >= 16
                    && countFoodItems(bot) >= MIN_BACKUP_FOOD_ITEMS) {
                break;
            }

            // Opportunistic sweep: pick up nearby drops before finding next target
            if (System.currentTimeMillis() - lastSweep > SWEEP_INTERVAL_MS) {
                if (!world.getEntitiesByClass(ItemEntity.class,
                        Box.from(Vec3d.of(bot.getBlockPos())).expand(8, 4, 8),
                        e -> e.isAlive()).isEmpty()) {
                    DropSweeper.sweep(source, 8.0, 4.0, 4, 3000L);
                    lastSweep = System.currentTimeMillis();
                }
            }

            // No-progress timeout: if no kill in 60s (resets after each kill), give up.
            if (System.currentTimeMillis() - huntLoopStartMs > HUNT_NO_PROGRESS_TIMEOUT_MS) {
                LOGGER.info("Hunt no-progress timeout for {} after {}ms",
                        bot.getName().getString(), System.currentTimeMillis() - huntLoopStartMs);
                runDropSweep(source, bot);
                return SkillExecutionResult.failure("Can't reach any prey. Giving up.");
            }

            // Check for targeted entity (from TARGET button in UI)
            HuntCandidate candidate = findTargetedEntity(world, bot);
            boolean playerTargeted = candidate != null;
            if (candidate == null && lockedTargetId != null && lockedTargetType != null) {
                candidate = resolveLockedCandidate(world, bot, anchors, lockedTargetId, lockedTargetType,
                        request.hobby(), huntRadius, huntYSpan, blacklistedTargets);
                if (candidate == null) {
                    lockedTargetId = null;
                    lockedTargetType = null;
                }
            }
            // Consume pre-found vantage scan result on first iteration
            if (candidate == null && prefoundCandidate != null) {
                candidate = prefoundCandidate;
                prefoundCandidate = null;
            }
            if (candidate == null) {
                candidate = findCandidate(world, bot, anchors, discoveredTargets, explicitTarget, request.hobby(),
                        depopulationEnabled, selectedTargets, huntRadius, huntYSpan);
                if (candidate == null) {
                    SearchRelocationResult relocation = relocateAndScanForCandidate(
                            source,
                            bot,
                            world,
                            discoveredTargets,
                            explicitTarget,
                            request,
                            depopulationEnabled,
                            selectedTargets,
                            huntRadius,
                            huntYSpan,
                            searchSegments,
                            nextSearchSegmentIndex,
                            MAX_RELOCATION_SEGMENTS_PER_SEARCH
                    );
                    candidate = relocation.candidate();
                    nextSearchSegmentIndex = relocation.nextSegmentIndex();
                    anchors = buildHuntAnchors(bot, world, huntRadius);
                }
            }
            // Skip blacklisted targets (unreachable after repeated approach failures)
            // but never blacklist a player-targeted entity — player made a deliberate choice
            if (!playerTargeted && candidate != null && candidate.entity != null
                    && blacklistedTargets.contains(candidate.entity.getUuid())) {
                candidate = null;
            }
            if (candidate == null || candidate.entity == null) {
                LOGGER.info("Hunt candidate not found (explicitTarget={})", explicitTarget != null);
                lockedTargetId = null;
                lockedTargetType = null;
                if (explicitTarget != null) {
                    return SkillExecutionResult.failure("No " + explicitTarget.label() + " found in the hunting grounds.");
                }
                if (tryFishingFallback(context, bot, source)) {
                    return SkillExecutionResult.success("Switched to fishing.");
                }
                return SkillExecutionResult.failure("No huntable mobs found nearby.");
            }

            LOGGER.info("Hunt candidate selected: {} at {} dist={}",
                    candidate.target.label(),
                    candidate.entity.getBlockPos().toShortString(),
                    String.format(Locale.ROOT, "%.1f", Math.sqrt(candidate.entity.squaredDistanceTo(bot))));
            if (candidate.entity != null) {
                lockedTargetId = candidate.entity.getUuid();
                lockedTargetType = candidate.target;
            }

            if (candidate.target.zombie() && !canHuntZombie(bot)) {
                return SkillExecutionResult.failure("I'm not geared enough to fight zombies.");
            }
            if (candidate.target.zombie() && !ensureMeleeWeapon(bot, world, source, commander)) {
                LOGGER.info("Hunt blocked: no melee weapon available for {}", bot.getName().getString());
                return SkillExecutionResult.failure("I need a weapon to hunt.");
            }

            if (depopulationEnabled && candidate.target.peaceful()) {
                int count = countTargets(world, bot, anchors, candidate.target, request.hobby(),
                        huntRadius, huntYSpan);
                if (count < MIN_PEACEFUL_COUNT) {
                    if (explicitTarget == null) {
                        candidate = null;
                    } else {
                        runDropSweep(source, bot);
                        return SkillExecutionResult.failure(
                                "Population too low (" + count + " " + candidate.target.label()
                                        + "). Depopulation protection is active.");
                    }
                }
            }

            if (candidate == null || candidate.entity == null) {
                sleep(500L);
                continue;
            }

            if (!approachTarget(source, bot, candidate.entity)) {
                UUID cid = candidate.entity.getUuid();
                consecutiveApproachFails = cid.equals(lastFailedTargetId)
                        ? consecutiveApproachFails + 1 : 1;
                lastFailedTargetId = cid;
                if (consecutiveApproachFails >= APPROACH_FAIL_BLACKLIST_THRESHOLD) {
                    blacklistedTargets.add(cid);
                    LOGGER.info("Hunt: blacklisting unreachable target {} after {} failures",
                            candidate.entity.getName().getString(), consecutiveApproachFails);
                    consecutiveApproachFails = 0;
                    lastFailedTargetId = null;
                    if (cid.equals(lockedTargetId)) {
                        lockedTargetId = null;
                        lockedTargetType = null;
                    }
                }
                ReturnBaseStuckService.tickAndCheckStuck(bot, new Vec3d(
                        candidate.entity.getX(), candidate.entity.getY(), candidate.entity.getZ()));
                sleep(600L);
                continue;
            }
            // Successful approach — reset failure tracking
            consecutiveApproachFails = 0;
            lastFailedTargetId = null;

            if (!attackTarget(bot, candidate.entity)) {
                LOGGER.info("Hunt target escaped: {}", candidate.entity.getName().getString());
                if (candidate.entity.isRemoved() || !candidate.entity.isAlive()) {
                    lockedTargetId = null;
                    lockedTargetType = null;
                }
                sleep(400L);
                continue;
            }

            BlockPos killPos = candidate.entity.getBlockPos();
            // When depopulation is enabled, zombie kills don't count toward the hunt target
            if (!(depopulationEnabled && candidate.target.zombie())) {
                kills++;
            }
            huntLoopStartMs = System.currentTimeMillis(); // reset no-progress timeout after each kill
            // Walk toward kill location before sweeping — mob may have fled far before dying
            if (bot.getBlockPos().getSquaredDistance(killPos) > 25) {
                MovementService.planLootApproach(bot, killPos, MovementService.MovementOptions.skillLoot())
                        .ifPresent(plan -> MovementService.execute(source, bot, plan, Boolean.FALSE, true));
            }
            lockedTargetId = null;
            lockedTargetType = null;
            runDropSweep(source, bot);
            if (System.currentTimeMillis() - lastSweep > SWEEP_INTERVAL_MS) {
                runDropSweep(source, bot);
                lastSweep = System.currentTimeMillis();
            }

            if (bot.getInventory().getEmptySlot() == -1) {
                offloadInventory(bot, source);
            }

            // Post-kill depopulation recheck: if population getting thin, end hunt early
            if (depopulationEnabled && candidate.target.peaceful()) {
                int remaining = countTargets(world, bot, anchors, candidate.target, request.hobby,
                        huntRadius, huntYSpan);
                if (remaining < MIN_PEACEFUL_COUNT) {
                    ChatUtils.sendSystemMessage(source,
                            "Population getting thin. Heading back. (" + kills + " kills)");
                    break;
                }
            }
        }

        // Post-hunt cooking: synchronous multi-batch cycle through all raw food types
        if (hasRawFood(bot)) {
            SmeltingService.cookAllFoodSync(bot, source, world);
        }
        // If raw food still remains after cooking, signal idle hobbies to prefer cooking next
        boolean commandOrigin = !systemOrigin && !request.hobby;
        if (hasRawFood(bot) || commandOrigin) {
            BotIdleHobbiesService.setPreferCooking(bot.getUuid());
        }

        if (request.hobby) {
            if (bot.getHungerManager().getFoodLevel() <= 19) {
                eatCookedIfHungry(bot, world);
            }
            if (hasNearbyCampfire(world, bot.getBlockPos())) {
                runHobbyHangout(context, source);
            }
        }

        if (kills == 0) {
            runFinalDropSweep(source, bot);
            return SkillExecutionResult.failure("No kills completed.");
        }
        runFinalDropSweep(source, bot);

        // Post-hunt return: summarize loot and return to base or owner
        String lootSummary = buildLootSummary(bot, preHuntInventory);
        String killMsg = kills + " kill" + (kills == 1 ? "" : "s");
        if (!request.hobby) {
            returnFromHunt(bot, source, commander, lootSummary, killMsg);
        }

        String msg = request.targetCount == Integer.MAX_VALUE
                ? "Hunt complete."
                : "Hunt complete (" + killMsg + ").";
        return SkillExecutionResult.success(msg);
    }

    private static boolean tryFishingFallback(SkillContext context, ServerPlayerEntity bot, ServerCommandSource source) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("_origin", "hunt");
            params.put("options", List.of("until_sunset"));
            SkillContext fishingContext = new SkillContext(source, context.sharedState(), params, context.requestSource());
            SkillExecutionResult result = new FishingSkill().execute(fishingContext);
            return result != null && result.success();
        } catch (Exception e) {
            LOGGER.warn("Fishing fallback failed: {}", e.getMessage());
            return false;
        }
    }

    private static HuntRequest parseRequest(Map<String, Object> params) {
        int count = getIntParameter(params, "count", -1);
        boolean openEnded = Boolean.TRUE.equals(params.get("open_ended"));
        boolean listOnly = false;
        String target = null;
        boolean untilSunset = false;
        boolean autoStopOnHunger = false;
        boolean hobby = false;

        Object optionsObj = params != null ? params.get("options") : null;
        if (optionsObj instanceof List<?> list) {
            for (Object raw : list) {
                if (raw == null) {
                    continue;
                }
                String opt = raw.toString().toLowerCase(Locale.ROOT);
                if (opt.contains("sunset")) {
                    untilSunset = true;
                    continue;
                }
                if (opt.equals("list") || opt.equals("catalog")) {
                    listOnly = true;
                    continue;
                }
                if (opt.contains("auto")) {
                    autoStopOnHunger = true;
                    continue;
                }
                if (opt.contains("hobby")) {
                    hobby = true;
                    continue;
                }
                if (target == null) {
                    target = opt;
                }
            }
        }

        int targetCount = count == -1 ? Integer.MAX_VALUE : Math.max(0, count);
        if (hobby && count == -1) {
            targetCount = 1;
        }
        boolean checkSunset = untilSunset || (count == -1 && !hobby) || openEnded;
        if (openEnded) {
            autoStopOnHunger = true;
        }
        if (hobby) {
            autoStopOnHunger = false;
        }
        return new HuntRequest(target, targetCount, checkSunset, listOnly, autoStopOnHunger, hobby);
    }

    private static int getIntParameter(Map<String, Object> params, String key, int def) {
        if (params == null || key == null) {
            return def;
        }
        Object raw = params.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static void sendCatalogList(ServerCommandSource source, ServerPlayerEntity commander) {
        StringBuilder sb = new StringBuilder("Huntable mobs: ");
        List<HuntCatalog.HuntTarget> targets = HuntCatalog.listAll();
        for (int i = 0; i < targets.size(); i++) {
            sb.append(targets.get(i).label());
            if (i + 1 < targets.size()) {
                sb.append(", ");
            }
        }
        ChatUtils.sendSystemMessage(source, sb.toString());
        if (commander != null && commander != source.getPlayer()) {
            ChatUtils.sendSystemMessage(commander.getCommandSource(), sb.toString());
        }
    }

    private static List<BlockPos> buildHuntAnchors(ServerPlayerEntity bot, ServerWorld world, int huntRadius) {
        List<BlockPos> anchors = new ArrayList<>();
        BlockPos botPos = bot.getBlockPos();
        anchors.add(botPos);

        BotHomeService.getLastSleep(bot).ifPresent(bedPos -> {
            if (botPos.getSquaredDistance(bedPos) <= (double) huntRadius * huntRadius) {
                anchors.add(bedPos);
            }
        });

        if (world.getServer() != null) {
            List<BotHomeService.BaseEntry> bases = BotHomeService.listBases(world.getServer(), world);
            for (BotHomeService.BaseEntry base : bases) {
                BlockPos pos = base.pos();
                if (pos != null && botPos.getSquaredDistance(pos) <= (double) huntRadius * huntRadius) {
                    anchors.add(pos);
                }
            }
        }
        return anchors;
    }

    private static List<BlockPos> buildHuntSearchSegments(BlockPos center, int huntRadius) {
        List<BlockPos> segments = new ArrayList<>();
        if (center == null) {
            return segments;
        }
        segments.add(center.toImmutable());
        int step = Math.max(10, Math.min(24, (int) Math.round(huntRadius * 0.6D)));
        int diag = Math.max(7, (int) Math.round(step * 0.7D));
        segments.add(center.add(step, 0, 0).toImmutable());
        segments.add(center.add(-step, 0, 0).toImmutable());
        segments.add(center.add(0, 0, step).toImmutable());
        segments.add(center.add(0, 0, -step).toImmutable());
        segments.add(center.add(diag, 0, diag).toImmutable());
        segments.add(center.add(diag, 0, -diag).toImmutable());
        segments.add(center.add(-diag, 0, diag).toImmutable());
        segments.add(center.add(-diag, 0, -diag).toImmutable());
        return segments;
    }

    private static SearchRelocationResult relocateAndScanForCandidate(ServerCommandSource source,
                                                                      ServerPlayerEntity bot,
                                                                      ServerWorld world,
                                                                      Set<Identifier> unlocked,
                                                                      HuntCatalog.HuntTarget explicit,
                                                                      HuntRequest request,
                                                                      boolean depopulationEnabled,
                                                                      List<String> selectedTargets,
                                                                      int huntRadius,
                                                                      int huntYSpan,
                                                                      List<BlockPos> searchSegments,
                                                                      int startIndex,
                                                                      int maxSegments) {
        if (bot == null || world == null || searchSegments == null || searchSegments.size() <= 1) {
            return new SearchRelocationResult(null, startIndex);
        }

        int index = normalizeSegmentIndex(startIndex, searchSegments.size());
        int attempts = Math.max(1, Math.min(maxSegments, searchSegments.size() - 1));
        for (int i = 0; i < attempts; i++) {
            BlockPos rough = searchSegments.get(index);
            index = (index + 1) % searchSegments.size();
            if (rough == null || bot.getBlockPos().getSquaredDistance(rough) <= 36.0D) {
                continue;
            }
            if (!moveToHuntSegment(source, bot, world, rough, huntRadius)) {
                LOGGER.info("Hunt relocate: failed to move toward segment {}", rough.toShortString());
                continue;
            }

            List<BlockPos> anchors = buildHuntAnchors(bot, world, huntRadius);
            HuntCandidate candidate = findCandidate(world, bot, anchors, unlocked, explicit,
                    request.hobby(), depopulationEnabled, selectedTargets,
                    huntRadius, huntYSpan);
            if (candidate != null) {
                LOGGER.info("Hunt relocate: found {} after moving to segment {}",
                        candidate.entity.getName().getString(),
                        rough.toShortString());
                return new SearchRelocationResult(candidate, index);
            }

            LOGGER.info("Hunt relocate: no prey at segment {}, trying vantage there", rough.toShortString());
            candidate = performVantageScan(bot, world, anchors, unlocked,
                    explicit, request, depopulationEnabled, selectedTargets,
                    huntRadius, huntYSpan);
            if (candidate != null) {
                LOGGER.info("Hunt relocate: found {} after vantage at segment {}",
                        candidate.entity.getName().getString(),
                        rough.toShortString());
                return new SearchRelocationResult(candidate, index);
            }
        }

        return new SearchRelocationResult(null, index);
    }

    private static boolean moveToHuntSegment(ServerCommandSource source,
                                             ServerPlayerEntity bot,
                                             ServerWorld world,
                                             BlockPos rough,
                                             int huntRadius) {
        BlockPos target = rough;
        SafePositionService.SurfaceStagingCandidate staging =
                SafePositionService.findBestSurfaceStaging(world, rough, Math.max(6, Math.min(10, huntRadius / 4)), true);
        if (staging != null) {
            target = staging.pos();
        }
        Optional<MovementService.MovementPlan> plan =
                MovementService.planLootApproach(bot, target, MovementService.MovementOptions.skillLoot());
        if (plan.isEmpty()) {
            return false;
        }
        MovementService.MovementResult result = MovementService.execute(
                source,
                bot,
                plan.get(),
                Boolean.FALSE,
                true,
                true,
                false
        );
        boolean success = result != null && (result.success() || bot.getBlockPos().getSquaredDistance(target) <= 16.0D);
        LOGGER.info("Hunt relocate: segmentTarget={} success={} detail={}",
                target.toShortString(),
                success,
                result != null ? result.detail() : "null");
        return success;
    }

    private static int normalizeSegmentIndex(int index, int size) {
        if (size <= 0) {
            return 0;
        }
        int normalized = index % size;
        return normalized < 0 ? normalized + size : normalized;
    }

    public static boolean hasAmbientHuntCandidate(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return false;
        }
        HuntConfigService.HuntConfig config = HuntConfigService.getConfig(bot);
        HuntConfigService.HuntZone zone = config.huntZone();
        Set<Identifier> unlocked = HuntHistoryService.getWorldHistory(world);
        List<BlockPos> anchors = buildHuntAnchors(bot, world, zone.radius);
        return findCandidate(world, bot, anchors, unlocked, null, true,
                config.depopulationEnabled, config.selectedTargets,
                zone.radius, zone.ySpan) != null;
    }

    /**
     * Check for a pending entity target set by the TARGET button UI.
     * Finds the specific entity by UUID in the world, bypassing depopulation checks.
     */
    private static HuntCandidate findTargetedEntity(ServerWorld world, ServerPlayerEntity bot) {
        if (world == null || bot == null) return null;
        UUID targetUuid = HuntablesNetworkManager.consumePendingTarget(bot.getUuid());
        if (targetUuid == null) return null;

        Entity entity = world.getEntity(targetUuid);
        if (!(entity instanceof LivingEntity living) || living.isDead() || living.isRemoved()) {
            LOGGER.info("Targeted entity {} not found or dead", targetUuid);
            return null;
        }

        // Resolve HuntCatalog target for the entity type
        Identifier entityId = net.minecraft.entity.EntityType.getId(entity.getType());
        HuntCatalog.HuntTarget catalogTarget = entityId != null
                ? HuntCatalog.findByName(entityId.getPath()) : null;
        if (catalogTarget == null) {
            LOGGER.info("Targeted entity {} ({}) not in hunt catalog", targetUuid, entityId);
            return null;
        }

        LOGGER.info("Using targeted entity: {} ({})", living.getName().getString(), targetUuid);
        return new HuntCandidate(catalogTarget, living);
    }

    private static HuntCandidate resolveLockedCandidate(ServerWorld world,
                                                        ServerPlayerEntity bot,
                                                        List<BlockPos> anchors,
                                                        UUID targetUuid,
                                                        HuntCatalog.HuntTarget targetType,
                                                        boolean hobbyMode,
                                                        int huntRadius,
                                                        int huntYSpan,
                                                        Set<UUID> blacklistedTargets) {
        if (world == null || bot == null || targetUuid == null || targetType == null) {
            return null;
        }
        if (blacklistedTargets != null && blacklistedTargets.contains(targetUuid)) {
            return null;
        }
        Entity entity = world.getEntity(targetUuid);
        if (!(entity instanceof LivingEntity living) || living.isRemoved() || !living.isAlive()) {
            return null;
        }
        if (!withinAnchors(anchors, new Vec3d(living.getX(), living.getY(), living.getZ()), huntRadius)) {
            return null;
        }
        if (!isEligibleHuntTarget(world, bot, living, hobbyMode)) {
            return null;
        }
        if (living.squaredDistanceTo(bot) > Math.max(256.0D, huntRadius * (double) huntRadius * 2.25D)) {
            return null;
        }
        return new HuntCandidate(targetType, living);
    }

    private static HuntCandidate findCandidate(ServerWorld world,
                                               ServerPlayerEntity bot,
                                               List<BlockPos> anchors,
                                               Set<Identifier> unlocked,
                                               HuntCatalog.HuntTarget explicit,
                                               boolean hobbyMode,
                                               boolean depopulationEnabled,
                                               List<String> selectedTargets,
                                               int huntRadius, int huntYSpan) {
        if (explicit != null) {
            List<LivingEntity> entities = findTargets(world, bot, anchors, explicit, hobbyMode, huntRadius, huntYSpan);
            LivingEntity nearest = entities.stream()
                    .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                    .orElse(null);
            return nearest == null ? null : new HuntCandidate(explicit, nearest);
        }

        HuntCandidate best = null;
        for (HuntCatalog.HuntTarget target : HuntCatalog.listAll()) {
            // Multi-select filter: if targets specified, only hunt those
            if (selectedTargets != null && !selectedTargets.isEmpty()) {
                if (!selectedTargets.contains(target.id().toString())) {
                    continue;
                }
            }
            if (target.zombie() && !canHuntZombie(bot)) {
                continue;
            }
            if (depopulationEnabled && target.peaceful()) {
                int count = countTargets(world, bot, anchors, target, hobbyMode, huntRadius, huntYSpan);
                if (count < MIN_PEACEFUL_COUNT) {
                    continue;
                }
            }
            List<LivingEntity> entities = findTargets(world, bot, anchors, target, hobbyMode, huntRadius, huntYSpan);
            LivingEntity nearest = entities.stream()
                    .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                    .orElse(null);
            if (nearest == null) {
                continue;
            }
            double dist = nearest.squaredDistanceTo(bot);
            if (best == null || dist < best.entity.squaredDistanceTo(bot)) {
                best = new HuntCandidate(target, nearest);
            }
        }
        return best;
    }

    private static List<LivingEntity> findTargets(ServerWorld world,
                                                  ServerPlayerEntity bot,
                                                  List<BlockPos> anchors,
                                                  HuntCatalog.HuntTarget target,
                                                  boolean hobbyMode,
                                                  int huntRadius, int huntYSpan) {
        Box box = buildSearchBox(anchors, huntRadius, huntYSpan);
        List<LivingEntity> out = new ArrayList<>();
        world.getEntitiesByType(target.type(), box, Entity::isAlive).forEach(entity -> {
            if (entity instanceof LivingEntity living && withinAnchors(anchors, new Vec3d(
                    living.getX(), living.getY(), living.getZ()), huntRadius)
                    && isEligibleHuntTarget(world, bot, living, hobbyMode)
                    && !BotEmergencyRescueService.shouldAvoidRiskyDescent(bot, world, living.getBlockPos())) {
                out.add(living);
            }
        });
        return out;
    }

    private static int countTargets(ServerWorld world,
                                    ServerPlayerEntity bot,
                                    List<BlockPos> anchors,
                                    HuntCatalog.HuntTarget target,
                                    boolean hobbyMode,
                                    int huntRadius, int huntYSpan) {
        Box box = buildSearchBox(anchors, huntRadius, huntYSpan);
        return world.getEntitiesByType(target.type(), box, Entity::isAlive)
                .stream()
                .mapToInt(entity -> {
                    if (!(entity instanceof LivingEntity living)) {
                        return 0;
                    }
                    boolean within = withinAnchors(anchors, new Vec3d(entity.getX(), entity.getY(), entity.getZ()), huntRadius);
                    return within && isEligibleHuntTarget(world, bot, living, hobbyMode) ? 1 : 0;
                })
                .sum();
    }

    private static boolean isEligibleHuntTarget(ServerWorld world,
                                                ServerPlayerEntity bot,
                                                LivingEntity living,
                                                boolean hobbyMode) {
        if (living == null || living.isRemoved() || !living.isAlive()) {
            return false;
        }
        if (living.isBaby()) {
            return false;
        }
        if (isDomesticated(living)) {
            return false;
        }

        // Zone protection applies in ALL modes: peaceful mobs inside protected
        // zones are off-limits. Zombies (the only hostile on the hunt catalog)
        // can still be hunted anywhere.
        if (!(living instanceof ZombieEntity)) {
            BlockPos pos = living.getBlockPos();
            if (CompanionSafeZoneService.isProtected(world, pos, null)) {
                return false;
            }
        }

        // Additional hobby-mode heuristics (structural enclosure, near player blocks)
        if (hobbyMode) {
            BlockPos pos = living.getBlockPos();
            if (isLikelyEnclosedByPlayerBuild(world, pos)) {
                return false;
            }
            if (TreeDetector.isNearHumanBlocks(world, pos, 5)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDomesticated(LivingEntity living) {
        if (living instanceof TameableEntity tameable && tameable.isTamed()) {
            return true;
        }
        if (living instanceof AbstractHorseEntity horse && horse.isTame()) {
            return true;
        }
        return living.hasCustomName();
    }

    private static boolean isLikelyEnclosedByPlayerBuild(ServerWorld world, BlockPos origin) {
        if (world == null || origin == null) {
            return false;
        }
        int barrierBlocks = 0;
        int solidBlocks = 0;
        int cardinalBlocked = 0;

        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos p = origin.offset(dir);
            BlockState s = world.getBlockState(p);
            if (isBarrierBlock(s)) {
                cardinalBlocked++;
            }
        }
        if (cardinalBlocked >= 3) {
            return true;
        }

        for (BlockPos pos : BlockPos.iterate(origin.add(-2, -1, -2), origin.add(2, 2, 2))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state == null || state.isAir()) {
                continue;
            }
            if (isBarrierBlock(state)) {
                barrierBlocks++;
            }
            if (state.isFullCube(world, pos)) {
                solidBlocks++;
            }
        }
        return barrierBlocks >= 8 || solidBlocks >= 30;
    }

    private static boolean isBarrierBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS)) {
            return true;
        }
        if (state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof DoorBlock) {
            return true;
        }
        return state.isOf(Blocks.IRON_BARS);
    }

    private static Box buildSearchBox(List<BlockPos> anchors, int huntRadius, int huntYSpan) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : anchors) {
            minX = Math.min(minX, pos.getX() - huntRadius);
            minY = Math.min(minY, pos.getY() - huntYSpan);
            minZ = Math.min(minZ, pos.getZ() - huntRadius);
            maxX = Math.max(maxX, pos.getX() + huntRadius);
            maxY = Math.max(maxY, pos.getY() + huntYSpan);
            maxZ = Math.max(maxZ, pos.getZ() + huntRadius);
        }
        return new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    private static boolean withinAnchors(List<BlockPos> anchors, Vec3d pos, int huntRadius) {
        for (BlockPos anchor : anchors) {
            if (pos.squaredDistanceTo(Vec3d.ofCenter(anchor)) <= (double) huntRadius * huntRadius) {
                return true;
            }
        }
        return false;
    }

    private static boolean approachTarget(ServerCommandSource source, ServerPlayerEntity bot, LivingEntity target) {
        if (target == null || target.isRemoved()) {
            return false;
        }
        BlockPos targetPos = target.getBlockPos();
        Optional<MovementService.MovementPlan> planOpt = MovementService.planLootApproach(bot, targetPos, MovementService.MovementOptions.skillLoot());
        if (planOpt.isEmpty()) {
            return false;
        }
        MovementService.MovementResult result = MovementService.execute(source, bot, planOpt.get(), Boolean.FALSE, true);
        return result.success() || bot.getBlockPos().getSquaredDistance(targetPos) <= ATTACK_RANGE_SQ;
    }

    /**
     * Pillar up 4-6 blocks to scan a wider Y-span for prey, then descend via the
     * shared surface-pillar descent path before resuming the hunt.
     */
    private static HuntCandidate performVantageScan(
            ServerPlayerEntity bot, ServerWorld world,
            List<BlockPos> anchors, Set<Identifier> unlocked,
            HuntCatalog.HuntTarget explicit, HuntRequest request,
            boolean depopulationEnabled, List<String> selectedTargets,
            int huntRadius, int huntYSpan) {

        int scaffoldCount = countScaffoldBlocks(bot);
        if (scaffoldCount < 4) {
            LOGGER.info("Hunt vantage: skipped (only {} scaffold blocks)", scaffoldCount);
            return null;
        }

        int pillarSteps = Math.min(6, scaffoldCount);
        LOGGER.info("Hunt vantage: pillaring up {} blocks to scan for prey", pillarSteps);

        List<BlockPos> placed = ScaffoldService.pillarUpWithPositions(bot, pillarSteps);
        if (placed.isEmpty()) {
            LOGGER.info("Hunt vantage: pillar-up failed, no blocks placed");
            return null;
        }

        HuntCandidate found = null;
        SafePositionService.SurfaceStagingCandidate staging = null;
        boolean descendedReady = false;
        try {
            // Scan with expanded Y-span from elevated position
            int elevatedYSpan = huntYSpan + placed.size() + 8;
            List<BlockPos> elevatedAnchors = buildHuntAnchors(bot, world, huntRadius);
            found = findCandidate(world, bot, elevatedAnchors, unlocked, explicit,
                    request.hobby(), depopulationEnabled, selectedTargets,
                    huntRadius, elevatedYSpan);
            if (found != null) {
                LOGGER.info("Hunt vantage: spotted {} at {} from elevation",
                        found.entity.getName().getString(), found.entity.getBlockPos().toShortString());
            } else {
                LOGGER.info("Hunt vantage: no targets visible from elevation either");
            }

            BlockPos targetBias = found != null && found.entity != null ? found.entity.getBlockPos() : null;
            staging = SafePositionService.findBestSurfaceStaging(world, bot.getBlockPos(), 8, true, targetBias);
            if (staging != null) {
                LOGGER.info("Hunt vantage: selected staging {} score={} targetBias={} {}",
                        staging.pos().toShortString(),
                        staging.score(),
                        targetBias != null ? targetBias.toShortString() : "none",
                        SafePositionService.summarizeSurfaceAssessment(staging.assessment()));
            } else {
                LOGGER.info("Hunt vantage: no operational staging candidate from pillar top");
            }
        } finally {
            descendedReady = BotFleeService.descendFromSurfacePillar(
                    bot,
                    world,
                    placed,
                    staging != null ? staging.pos() : null,
                    "hunt-vantage");
            if (!descendedReady) {
                LOGGER.info("Hunt vantage: descent ended without operational surface readiness");
            }
        }
        if (!descendedReady) {
            return null;
        }
        return found;
    }

    private static int countScaffoldBlocks(ServerPlayerEntity bot) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && ScaffoldService.SCAFFOLD_BLOCKS.contains(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean attackTarget(ServerPlayerEntity bot, LivingEntity target) {
        long start = System.currentTimeMillis();
        while (target.isAlive() && !target.isRemoved()) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }
            double distSq = bot.squaredDistanceTo(target);
            if (distSq <= ATTACK_RANGE_SQ && bot.canSee(target)) {
                BotActions.selectBestMeleeWeapon(bot);
                bot.attack(target);
                bot.swingHand(net.minecraft.util.Hand.MAIN_HAND, true);
            } else {
                MovementService.nudgeTowardUntilClose(bot, target.getBlockPos(), ATTACK_RANGE_SQ, 1200L, 0.18, "hunt-attack");
            }
            if (System.currentTimeMillis() - start > ATTACK_TIMEOUT_MS) {
                return false;
            }
            sleep(220L);
        }
        return true;
    }

    private static boolean ensureMeleeWeapon(ServerPlayerEntity bot,
                                             ServerWorld world,
                                             ServerCommandSource source,
                                             ServerPlayerEntity commander) {
        if (BotActions.selectBestMeleeWeapon(bot)) {
            return true;
        }
        if (withdrawWeaponFromContainers(bot, world)) {
            return BotActions.selectBestMeleeWeapon(bot);
        }
        if (craftSwordIfPossible(bot, source, commander)) {
            return BotActions.selectBestMeleeWeapon(bot);
        }
        return BotActions.selectBestMeleeWeapon(bot);
    }

    private static boolean craftSwordIfPossible(ServerPlayerEntity bot,
                                                ServerCommandSource source,
                                                ServerPlayerEntity commander) {
        return ToolProvisionService.ensureSword(bot, source, commander);
    }

    private static int countInInventory(ServerPlayerEntity bot, Item item) {
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countInContainers(ServerWorld world, BlockPos origin, Item item) {
        int total = 0;
        for (ContainerSlot slot : scanContainers(world, origin)) {
            if (slot.stack.isOf(item)) {
                total += slot.stack.getCount();
            }
        }
        return total;
    }

    private static boolean withdrawWeaponFromContainers(ServerPlayerEntity bot, ServerWorld world) {
        ContainerSlot best = null;
        int bestScore = -1;
        for (ContainerSlot slot : scanContainers(world, bot.getBlockPos())) {
            int score = weaponScore(slot.stack);
            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        if (best == null || bestScore <= 0) {
            return false;
        }
        ItemStack taken = best.inv.removeStack(best.slot, 1);
        if (taken.isEmpty()) {
            return false;
        }
        boolean inserted = bot.getInventory().insertStack(taken);
        if (!inserted) {
            best.inv.setStack(best.slot, taken);
            best.inv.markDirty();
            return false;
        }
        best.inv.markDirty();
        bot.getInventory().markDirty();
        return true;
    }

    private static int weaponScore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        String key = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        boolean weapon = key.contains("sword") || key.contains("axe") || key.contains("trident")
                || key.contains("mace") || key.contains("dagger");
        if (!weapon) {
            return 0;
        }
        if (key.contains("diamond")) return 40;
        if (key.contains("iron")) return 30;
        if (key.contains("stone") || key.contains("cobble")) return 20;
        if (key.contains("wood")) return 10;
        return 5;
    }

    private static boolean canHuntZombie(ServerPlayerEntity bot) {
        if (bot.getHealth() < ZOMBIE_MIN_HEALTH) {
            return false;
        }
        if (!hasArmor(bot)) {
            return false;
        }
        return BotActions.selectBestMeleeWeapon(bot);
    }

    private static boolean hasArmor(ServerPlayerEntity bot) {
        return !bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).isEmpty()
                || !bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isEmpty()
                || !bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS).isEmpty()
                || !bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET).isEmpty();
    }

    private static boolean maybeEatEmergencyFood(ServerPlayerEntity bot,
                                                ServerWorld world) {
        boolean emergency = bot.getHealth() <= EMERGENCY_HEALTH || bot.getHungerManager().getFoodLevel() <= EMERGENCY_HUNGER;
        boolean starving = bot.getHungerManager().getFoodLevel() <= STARVING_HUNGER;

        FoodCandidate cooked = findFoodCandidate(bot, world, true);
        if (cooked != null && (starving || emergency)) {
            return consumeCandidate(bot, cooked);
        }

        if (emergency) {
            FoodCandidate raw = findFoodCandidate(bot, world, false);
            if (raw != null) {
                return consumeCandidate(bot, raw);
            }
        }
        return false;
    }

    private static FoodCandidate findFoodCandidate(ServerPlayerEntity bot, ServerWorld world, boolean cookedOnly) {
        FoodCandidate best = null;
        for (FoodCandidate candidate : collectFoodCandidates(bot, world)) {
            if (cookedOnly && (candidate.raw || candidate.rotten)) {
                continue;
            }
            if (best == null || candidate.score < best.score) {
                best = candidate;
            }
        }
        return best;
    }

    private static List<FoodCandidate> collectFoodCandidates(ServerPlayerEntity bot, ServerWorld world) {
        List<FoodCandidate> out = new ArrayList<>();
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            FoodCandidate candidate = buildFoodCandidate(stack, null, i, null);
            if (candidate != null) {
                out.add(candidate);
            }
        }
        for (ContainerSlot slot : scanContainers(world, bot.getBlockPos())) {
            FoodCandidate candidate = buildFoodCandidate(slot.stack, slot.inv, slot.slot, slot.pos);
            if (candidate != null) {
                out.add(candidate);
            }
        }
        return out;
    }

    private static FoodCandidate buildFoodCandidate(ItemStack stack, Inventory inv, int slot, BlockPos pos) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
        if (food == null) {
            return null;
        }
        boolean raw = RAW_MEAT.contains(stack.getItem());
        boolean rotten = stack.isOf(Items.ROTTEN_FLESH);
        double score = food.nutrition() + (food.saturation() * 2.0);
        return new FoodCandidate(inv, pos, slot, stack, raw, rotten, score);
    }

    private static boolean consumeCandidate(ServerPlayerEntity bot, FoodCandidate candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate.inv == null) {
            return consumeInventoryFood(bot, candidate.slot);
        }

        // Pull one item from container into the hotbar (swap if needed).
        ItemStack taken = candidate.inv.removeStack(candidate.slot, 1);
        if (taken.isEmpty()) {
            return false;
        }
        int hotbarSlot = findEmptyHotbarSlot(bot);
        if (hotbarSlot == -1) {
            hotbarSlot = 0;
        }
        ItemStack displaced = bot.getInventory().getStack(hotbarSlot);
        bot.getInventory().setStack(hotbarSlot, taken);
        if (!displaced.isEmpty()) {
            candidate.inv.setStack(candidate.slot, displaced);
        }
        candidate.inv.markDirty();
        bot.getInventory().markDirty();
        return consumeInventoryFood(bot, hotbarSlot);
    }

    private static boolean consumeInventoryFood(ServerPlayerEntity bot, int slot) {
        if (bot.isUsingItem()) {
            return false;
        }
        ItemStack stack = bot.getInventory().getStack(slot);
        if (stack.isEmpty() || stack.getComponents().get(DataComponentTypes.FOOD) == null) {
            return false;
        }
        if (slot >= 9) {
            int hotbarSlot = findEmptyHotbarSlot(bot);
            if (hotbarSlot == -1) {
                hotbarSlot = 0;
            }
            ItemStack temp = bot.getInventory().getStack(hotbarSlot);
            bot.getInventory().setStack(hotbarSlot, stack);
            bot.getInventory().setStack(slot, temp);
            slot = hotbarSlot;
        }
        BotActions.selectHotbarSlot(bot, slot);
        BotActions.useSelectedItem(bot);
        bot.getInventory().markDirty();
        return true;
    }

    private static int findEmptyHotbarSlot(ServerPlayerEntity bot) {
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static List<ContainerSlot> scanContainers(ServerWorld world, BlockPos origin) {
        List<ContainerSlot> out = new ArrayList<>();
        int r = FOOD_CONTAINER_RADIUS;
        int y = FOOD_CONTAINER_YSPAN;
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -y, -r), origin.add(r, y, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            var be = world.getBlockEntity(pos);
            if (!(be instanceof Inventory inv)) {
                continue;
            }
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                out.add(new ContainerSlot(inv, pos.toImmutable(), i, stack));
            }
        }
        return out;
    }

    private static void runDropSweep(ServerCommandSource source, ServerPlayerEntity bot) {
        try {
            if (bot.getInventory().getEmptySlot() == -1) {
                offloadInventory(bot, source);
                if (bot.getInventory().getEmptySlot() == -1) {
                    LOGGER.info("Drop sweep skipped: inventory still full after offload attempt");
                    return;
                }
            }
            DropSweeper.sweep(source.withSilent(), 12.0D, 6.0D, 16, 12_000L);
        } catch (Exception e) {
            LOGGER.warn("Drop sweep failed during hunt: {}", e.getMessage());
        }
    }

    private static void runFinalDropSweep(ServerCommandSource source, ServerPlayerEntity bot) {
        try {
            if (bot.getInventory().getEmptySlot() == -1) {
                offloadInventory(bot, source);
                if (bot.getInventory().getEmptySlot() == -1) {
                    LOGGER.info("Final drop sweep skipped: inventory still full after offload attempt");
                    return;
                }
            }
            DropSweeper.sweep(source.withSilent(), FINAL_SWEEP_RADIUS, FINAL_SWEEP_VERTICAL, 12, 12_000L);
        } catch (Exception e) {
            LOGGER.warn("Final drop sweep failed after hunt: {}", e.getMessage());
        }
    }

    private static void offloadInventory(ServerPlayerEntity bot, ServerCommandSource source) {
        try {
            // First try offloading to an existing nearby chest
            Map<Item, Integer> reserve = new HashMap<>();
            for (int i = 0; i < bot.getInventory().size(); i++) {
                ItemStack stack = bot.getInventory().getStack(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                if (stack.getComponents().get(DataComponentTypes.FOOD) != null) {
                    reserve.put(stack.getItem(), Math.max(1, stack.getCount()));
                }
            }
            CraftingHelper.offloadCheapItemsToNearbyChest(bot, source, 0, 0, reserve);

            // If still full, try crafting + placing a chest
            if (bot.getInventory().getEmptySlot() == -1) {
                ServerPlayerEntity commander = source.getPlayer() != null && source.getPlayer() != bot
                        ? source.getPlayer() : null;
                if (bot.getEntityWorld() instanceof ServerWorld world) {
                    ToolProvisionService.ensureChest(bot, source, commander, 1);
                    BlockPos chestPos = ChestStoreService.placeChestNearBot(source, bot, false);
                    if (chestPos != null) {
                        ChestStoreService.depositHuntLoot(source, bot, chestPos);
                        LOGGER.info("Hunt offload: placed chest at {} and deposited loot", chestPos);
                    }
                }
            }

            // Last resort: jettison disposable items to free space
            if (bot.getInventory().getEmptySlot() == -1) {
                jettisonDisposables(bot);
            }
        } catch (Exception e) {
            LOGGER.warn("Inventory offload failed during hunt: {}", e.getMessage());
        }
    }

    /** Drop outright-disposable items (boats, extra crafting tables) when no chest is available. */
    private static void jettisonDisposables(ServerPlayerEntity bot) {
        if (bot == null) return;
        Set<net.minecraft.item.Item> disposable = Set.of(
                net.minecraft.item.Items.OAK_BOAT, net.minecraft.item.Items.SPRUCE_BOAT,
                net.minecraft.item.Items.BIRCH_BOAT, net.minecraft.item.Items.JUNGLE_BOAT,
                net.minecraft.item.Items.ACACIA_BOAT, net.minecraft.item.Items.DARK_OAK_BOAT,
                net.minecraft.item.Items.MANGROVE_BOAT, net.minecraft.item.Items.CHERRY_BOAT,
                net.minecraft.item.Items.BAMBOO_RAFT,
                net.minecraft.item.Items.OAK_CHEST_BOAT, net.minecraft.item.Items.SPRUCE_CHEST_BOAT,
                net.minecraft.item.Items.BIRCH_CHEST_BOAT, net.minecraft.item.Items.JUNGLE_CHEST_BOAT,
                net.minecraft.item.Items.ACACIA_CHEST_BOAT, net.minecraft.item.Items.DARK_OAK_CHEST_BOAT,
                net.minecraft.item.Items.MANGROVE_CHEST_BOAT, net.minecraft.item.Items.CHERRY_CHEST_BOAT,
                net.minecraft.item.Items.BAMBOO_CHEST_RAFT,
                net.minecraft.item.Items.CRAFTING_TABLE
        );
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && disposable.contains(stack.getItem())) {
                bot.dropItem(stack, false);
                bot.getInventory().setStack(i, ItemStack.EMPTY);
                LOGGER.info("Jettisoned {} to clear inventory space", stack.getItem().getName().getString());
            }
        }
    }

    public static boolean hasRawFood(ServerPlayerEntity bot) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (RAW_MEAT.contains(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts all food items (raw and cooked) in the bot's inventory.
     */
    public static int countFoodItems(ServerPlayerEntity bot) {
        if (bot == null) return 0;
        int count = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            FoodComponent food = stack.getComponents().get(DataComponentTypes.FOOD);
            if (food != null && food.nutrition() > 0) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean isSunset(ServerWorld world) {
        long timeOfDay = world.getTimeOfDay() % 24000L;
        return timeOfDay >= 13000 && timeOfDay < 23000;
    }

    private static boolean isLateEveningBeforeSunset(ServerWorld world) {
        long timeOfDay = world.getTimeOfDay() % 24000L;
        return timeOfDay >= 11_200L && timeOfDay < 13_000L;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void eatCookedIfHungry(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return;
        }
        if (bot.getHungerManager().getFoodLevel() > 19) {
            return;
        }
        FoodCandidate cooked = findFoodCandidate(bot, world, true);
        if (cooked != null) {
            consumeCandidate(bot, cooked);
        }
    }

    private static boolean hasNearbyCampfire(ServerWorld world, BlockPos origin) {
        if (world == null || origin == null) {
            return false;
        }
        int r = 24;
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            var state = world.getBlockState(pos);
            if (state.isOf(net.minecraft.block.Blocks.CAMPFIRE)
                    || state.isOf(net.minecraft.block.Blocks.SOUL_CAMPFIRE)) {
                return true;
            }
        }
        return false;
    }

    private static void runHobbyHangout(SkillContext context, ServerCommandSource source) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("_origin", "hunt_hobby");
            params.put("duration_sec", 25);
            params.put("until_sunset", true);
            SkillContext hangoutContext = new SkillContext(source, context.sharedState(), params, context.requestSource());
            new net.wcfcarolina13.GameAI.skills.impl.HangoutSkill().execute(hangoutContext);
        } catch (Exception e) {
            LOGGER.warn("Hobby hangout failed: {}", e.getMessage());
        }
    }

    // ── Phase 2: Pre-hunt inventory check ─────────────────────────────

    private static final int MIN_FREE_SLOTS = 4;

    private static void ensureHuntingSupplies(ServerPlayerEntity bot, ServerWorld world,
                                               ServerCommandSource source, ServerPlayerEntity commander) {
        int emptySlots = countEmptySlots(bot);
        if (emptySlots >= MIN_FREE_SLOTS) {
            return;
        }

        ChatUtils.sendSystemMessage(source, "Inventory is nearly full. Setting up a chest first.");

        // Try to craft a chest if we don't have one
        boolean hasChest = ToolProvisionService.ensureChest(bot, source, commander, 1);

        if (!hasChest) {
            // No chest materials — run woodcutting prerequisite
            LOGGER.info("No chest materials available, running woodcut prerequisite");
            runWoodcutPrerequisite(bot, world, source, commander);
            hasChest = ToolProvisionService.ensureChest(bot, source, commander, 1);
        }

        if (hasChest) {
            BlockPos chestPos = ChestStoreService.placeChestNearBot(source, bot, true);
            if (chestPos != null) {
                ChestStoreService.depositHuntLoot(source, bot, chestPos);
                LOGGER.info("Pre-hunt: placed chest at {} and deposited non-essentials", chestPos);
            }
        }
    }

    private static void runWoodcutPrerequisite(ServerPlayerEntity bot, ServerWorld world,
                                                ServerCommandSource source, ServerPlayerEntity commander) {
        if (ToolProvisionService.hasPlanksOrLogsInInventory(bot)) {
            LOGGER.info("Hunt prerequisite: already has planks/logs, skipping woodcut");
            return;
        }
        try {
            ChatUtils.sendSystemMessage(source, "Need wood for a chest. Chopping some trees first.");

            // Ensure basic tools for woodcutting
            ToolProvisionService.ensureCraftingTable(bot, source, commander, 1);
            ToolProvisionService.ensureAxe(bot, source, commander);

            // Run a small woodcut (8 logs = enough for planks -> chest + extra)
            Map<String, Object> params = new HashMap<>();
            params.put("count", 8);
            params.put("_origin", "hunt_prerequisite");
            SkillContext woodcutCtx = new SkillContext(source,
                    new java.util.concurrent.ConcurrentHashMap<>(), params, source);
            new net.wcfcarolina13.GameAI.skills.impl.WoodcutSkill().execute(woodcutCtx);
        } catch (Exception e) {
            LOGGER.warn("Woodcut prerequisite failed: {}", e.getMessage());
        }
    }

    private static int countEmptySlots(ServerPlayerEntity bot) {
        int count = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack == null || stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    // ── Phase 2: Inventory snapshot + loot summary ──────────────────────

    private static Map<Item, Integer> snapshotInventory(ServerPlayerEntity bot) {
        Map<Item, Integer> snapshot = new HashMap<>();
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack != null && !stack.isEmpty()) {
                snapshot.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return snapshot;
    }

    private static String buildLootSummary(ServerPlayerEntity bot, Map<Item, Integer> preHunt) {
        Map<Item, Integer> current = snapshotInventory(bot);
        List<String> gains = new ArrayList<>();

        for (Map.Entry<Item, Integer> entry : current.entrySet()) {
            int before = preHunt.getOrDefault(entry.getKey(), 0);
            int diff = entry.getValue() - before;
            if (diff > 0) {
                String name = entry.getKey().getName().getString();
                gains.add(diff + " " + name);
            }
        }

        return gains.isEmpty() ? "" : String.join(", ", gains);
    }

    // ── Phase 2: Post-hunt return ───────────────────────────────────────

    private static void returnFromHunt(ServerPlayerEntity bot, ServerCommandSource source,
                                        ServerPlayerEntity commander, String lootSummary,
                                        String killMsg) {
        try {
            // Try returning to nearest base
            Optional<BlockPos> homeTarget = BotHomeService.resolveHomeTarget(bot);
            if (homeTarget.isPresent()) {
                BlockPos target = homeTarget.get();
                double distSq = bot.getBlockPos().getSquaredDistance(target);
                if (distSq > 16.0D) {
                    String returnMsg = "Returned from the hunt (" + killMsg + ").";
                    if (!lootSummary.isEmpty()) {
                        returnMsg += " I've got " + lootSummary + ".";
                    }
                    ChatUtils.sendSystemMessage(source, returnMsg);

                    // Deposit loot at base chests if available
                    if (bot.getEntityWorld() instanceof ServerWorld) {
                        Optional<MovementService.MovementPlan> plan =
                                MovementService.planLootApproach(bot, target, MovementService.MovementOptions.skillLoot());
                        if (plan.isPresent()) {
                            MovementService.execute(source, bot, plan.get(), Boolean.FALSE, true);
                        }
                    }
                    return;
                }
            }

            // No base — just announce loot
            if (!lootSummary.isEmpty()) {
                ChatUtils.sendSystemMessage(source, "Returned from the hunt. I've got " + lootSummary + ".");
            }
        } catch (Exception e) {
            LOGGER.warn("Return from hunt failed: {}", e.getMessage());
        }
    }

    private record HuntCandidate(HuntCatalog.HuntTarget target, LivingEntity entity) {}

    private record SearchRelocationResult(HuntCandidate candidate, int nextSegmentIndex) {}

    private record HuntRequest(String targetName,
                               int targetCount,
                               boolean checkSunset,
                               boolean listOnly,
                               boolean autoStopOnHunger,
                               boolean hobby) {}

    private record ContainerSlot(Inventory inv, BlockPos pos, int slot, ItemStack stack) {}

    private record FoodCandidate(Inventory inv,
                                 BlockPos pos,
                                 int slot,
                                 ItemStack stack,
                                 boolean raw,
                                 boolean rotten,
                                 double score) {}
}
