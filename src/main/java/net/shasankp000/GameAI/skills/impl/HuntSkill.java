package net.shasankp000.GameAI.skills.impl;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import net.shasankp000.GameAI.DropSweeper;
import net.shasankp000.GameAI.services.BotHomeService;
import net.shasankp000.GameAI.services.CraftingHelper;
import net.shasankp000.GameAI.services.HuntCatalog;
import net.shasankp000.GameAI.services.HuntHistoryService;
import net.shasankp000.GameAI.services.MovementService;
import net.shasankp000.GameAI.services.ReturnBaseStuckService;
import net.shasankp000.GameAI.services.SmeltingService;
import net.shasankp000.GameAI.services.DebugFileLogger;
import net.shasankp000.GameAI.skills.Skill;
import net.shasankp000.GameAI.skills.SkillContext;
import net.shasankp000.GameAI.skills.SkillExecutionResult;
import net.shasankp000.GameAI.skills.SkillManager;
import net.shasankp000.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class HuntSkill implements Skill {

    private static final Logger LOGGER = LoggerFactory.getLogger("skill-hunt");
    private static final int HUNT_RADIUS = 48;
    private static final int HUNT_Y_SPAN = 8;
    private static final int FOOD_CONTAINER_RADIUS = 12;
    private static final int FOOD_CONTAINER_YSPAN = 4;
    private static final int MIN_PEACEFUL_COUNT = 3;
    private static final double ATTACK_RANGE_SQ = 9.0D;
    private static final long ATTACK_TIMEOUT_MS = 12_000L;
    private static final long SWEEP_INTERVAL_MS = 12_000L;
    private static final double FINAL_SWEEP_RADIUS = 14.0D;
    private static final double FINAL_SWEEP_VERTICAL = 6.0D;
    private static final float ZOMBIE_MIN_HEALTH = 16.0F; // 8 hearts
    private static final int STARVING_HUNGER = 5;
    private static final int EMERGENCY_HUNGER = 1;
    private static final float EMERGENCY_HEALTH = 2.0F;

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
        Set<Identifier> unlocked = commander != null
                ? HuntHistoryService.getHistory(commander)
                : HuntHistoryService.getWorldHistory(world);
        boolean huntUnlocked = commander != null
                ? HuntHistoryService.hasFoodKill(commander)
                : HuntHistoryService.hasAnyFoodKill(world);
        if (!huntUnlocked) {
            LOGGER.info("Hunt locked: commander={} unlockedCount={}",
                    commander != null ? commander.getName().getString() : "none",
                    unlocked.size());
            return SkillExecutionResult.failure("Hunting is locked until you've killed a food mob at least once.");
        }

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
            if (explicitTarget.foodMob() && !unlocked.contains(explicitTarget.id())) {
                LOGGER.info("Hunt target '{}' locked for {}", explicitTarget.label(), commander != null ? commander.getName().getString() : "unknown");
                return SkillExecutionResult.failure("I haven't hunted " + explicitTarget.label() + " yet.");
            }
        }

        String targetLabel = explicitTarget != null ? explicitTarget.label() : "food mobs";
        String countLabel = request.targetCount == Integer.MAX_VALUE ? "until sunset" : Integer.toString(request.targetCount);
        ChatUtils.sendSystemMessage(source, "Hunting " + targetLabel + " (" + countLabel + ").");

        List<BlockPos> anchors = buildHuntAnchors(bot, world);
        LOGGER.info("Hunt anchors: {}", anchors.size());
        long lastSweep = System.currentTimeMillis();
        int kills = 0;

        while (kills < request.targetCount) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return SkillExecutionResult.failure("Hunt paused by another task.");
            }

            if (request.checkSunset && isSunset(world)) {
                ChatUtils.sendSystemMessage(source, "Sun has set. Stopping hunt.");
                break;
            }

            if (maybeEatEmergencyFood(bot, world)) {
                // Give the eat animation a moment to settle.
                sleep(400L);
            }

            if (request.autoStopOnHunger && bot.getHungerManager().getFoodLevel() >= 16) {
                break;
            }

            if (!ensureMeleeWeapon(bot, world, source, commander)) {
                LOGGER.info("Hunt blocked: no melee weapon available for {}", bot.getName().getString());
                return SkillExecutionResult.failure("I need a weapon to hunt.");
            }

            HuntCandidate candidate = findCandidate(world, bot, anchors, unlocked, explicitTarget);
            if (candidate == null || candidate.entity == null) {
                LOGGER.info("Hunt candidate not found (explicitTarget={})", explicitTarget != null);
                if (explicitTarget != null) {
                    return SkillExecutionResult.failure("No " + explicitTarget.label() + " found in the hunting grounds.");
                }
                if (tryFishingFallback(context, bot, source)) {
                    return SkillExecutionResult.success("Switched to fishing.");
                }
                return SkillExecutionResult.failure("No huntable mobs found nearby.");
            }

            if (candidate.target.zombie() && !canHuntZombie(bot)) {
                return SkillExecutionResult.failure("I'm not geared enough to fight zombies.");
            }

            if (candidate.target.peaceful()) {
                int count = countTargets(world, anchors, candidate.target);
                if (count < MIN_PEACEFUL_COUNT) {
                    if (explicitTarget != null) {
                        runDropSweep(source, bot);
                        return SkillExecutionResult.failure("Only " + count + " " + candidate.target.label() + "(s) nearby; hunting paused to avoid depopulation.");
                    }
                    candidate = null;
                }
            }

            if (candidate == null || candidate.entity == null) {
                sleep(500L);
                continue;
            }

            if (!approachTarget(source, bot, candidate.entity)) {
                ReturnBaseStuckService.tickAndCheckStuck(bot, new Vec3d(
                        candidate.entity.getX(), candidate.entity.getY(), candidate.entity.getZ()));
                sleep(600L);
                continue;
            }

            if (!attackTarget(bot, candidate.entity)) {
                LOGGER.info("Hunt target escaped: {}", candidate.entity.getName().getString());
                sleep(400L);
                continue;
            }

            kills++;
            runDropSweep(source, bot);
            if (System.currentTimeMillis() - lastSweep > SWEEP_INTERVAL_MS) {
                runDropSweep(source, bot);
                lastSweep = System.currentTimeMillis();
            }

            if (bot.getInventory().getEmptySlot() == -1) {
                offloadInventory(bot, source);
            }
        }

        if (hasRawFood(bot)) {
            SmeltingService.startBatchCook(bot, source, "", "auto");
        }

        if (kills == 0) {
            runFinalDropSweep(source, bot);
            return SkillExecutionResult.failure("No kills completed.");
        }
        runFinalDropSweep(source, bot);
        String msg = request.targetCount == Integer.MAX_VALUE
                ? "Hunt complete."
                : "Hunt complete (" + kills + " kill" + (kills == 1 ? "" : "s") + ").";
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
                }
                if (target == null) {
                    target = opt;
                }
            }
        }

        int targetCount = count == -1 ? Integer.MAX_VALUE : Math.max(0, count);
        boolean checkSunset = untilSunset || count == -1 || openEnded;
        if (openEnded) {
            autoStopOnHunger = true;
        }
        return new HuntRequest(target, targetCount, checkSunset, listOnly, autoStopOnHunger);
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

    private static List<BlockPos> buildHuntAnchors(ServerPlayerEntity bot, ServerWorld world) {
        List<BlockPos> anchors = new ArrayList<>();
        BlockPos botPos = bot.getBlockPos();
        anchors.add(botPos);

        BotHomeService.getLastSleep(bot).ifPresent(bedPos -> {
            if (botPos.getSquaredDistance(bedPos) <= (double) HUNT_RADIUS * HUNT_RADIUS) {
                anchors.add(bedPos);
            }
        });

        if (world.getServer() != null) {
            List<BotHomeService.BaseEntry> bases = BotHomeService.listBases(world.getServer(), world);
            for (BotHomeService.BaseEntry base : bases) {
                BlockPos pos = base.pos();
                if (pos != null && botPos.getSquaredDistance(pos) <= (double) HUNT_RADIUS * HUNT_RADIUS) {
                    anchors.add(pos);
                }
            }
        }
        return anchors;
    }

    private static HuntCandidate findCandidate(ServerWorld world,
                                               ServerPlayerEntity bot,
                                               List<BlockPos> anchors,
                                               Set<Identifier> unlocked,
                                               HuntCatalog.HuntTarget explicit) {
        if (explicit != null) {
            List<LivingEntity> entities = findTargets(world, anchors, explicit);
            LivingEntity nearest = entities.stream()
                    .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(bot)))
                    .orElse(null);
            return nearest == null ? null : new HuntCandidate(explicit, nearest);
        }

        HuntCandidate best = null;
        for (HuntCatalog.HuntTarget target : HuntCatalog.listAll()) {
            if (target.foodMob() && !unlocked.contains(target.id())) {
                continue;
            }
            if (target.zombie() && !canHuntZombie(bot)) {
                continue;
            }
            if (target.peaceful()) {
                int count = countTargets(world, anchors, target);
                if (count < MIN_PEACEFUL_COUNT) {
                    continue;
                }
            }
            List<LivingEntity> entities = findTargets(world, anchors, target);
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

    private static List<LivingEntity> findTargets(ServerWorld world, List<BlockPos> anchors, HuntCatalog.HuntTarget target) {
        Box box = buildSearchBox(anchors);
        List<LivingEntity> out = new ArrayList<>();
        world.getEntitiesByType(target.type(), box, Entity::isAlive).forEach(entity -> {
            if (entity instanceof LivingEntity living && withinAnchors(anchors, new Vec3d(
                    living.getX(), living.getY(), living.getZ()))) {
                out.add(living);
            }
        });
        return out;
    }

    private static int countTargets(ServerWorld world, List<BlockPos> anchors, HuntCatalog.HuntTarget target) {
        Box box = buildSearchBox(anchors);
        return world.getEntitiesByType(target.type(), box, Entity::isAlive)
                .stream()
                .mapToInt(entity -> withinAnchors(anchors, new Vec3d(
                        entity.getX(), entity.getY(), entity.getZ())) ? 1 : 0)
                .sum();
    }

    private static Box buildSearchBox(List<BlockPos> anchors) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : anchors) {
            minX = Math.min(minX, pos.getX() - HUNT_RADIUS);
            minY = Math.min(minY, pos.getY() - HUNT_Y_SPAN);
            minZ = Math.min(minZ, pos.getZ() - HUNT_RADIUS);
            maxX = Math.max(maxX, pos.getX() + HUNT_RADIUS);
            maxY = Math.max(maxY, pos.getY() + HUNT_Y_SPAN);
            maxZ = Math.max(maxZ, pos.getZ() + HUNT_RADIUS);
        }
        return new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
    }

    private static boolean withinAnchors(List<BlockPos> anchors, Vec3d pos) {
        for (BlockPos anchor : anchors) {
            if (pos.squaredDistanceTo(Vec3d.ofCenter(anchor)) <= (double) HUNT_RADIUS * HUNT_RADIUS) {
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
        MovementService.MovementResult result = MovementService.execute(source, bot, planOpt.get(), SkillPreferences.teleportDuringSkills(bot), true);
        return result.success() || bot.getBlockPos().getSquaredDistance(targetPos) <= ATTACK_RANGE_SQ;
    }

    private static boolean attackTarget(ServerPlayerEntity bot, LivingEntity target) {
        long start = System.currentTimeMillis();
        while (target.isAlive() && !target.isRemoved()) {
            if (SkillManager.shouldAbortSkill(bot)) {
                return false;
            }
            double distSq = bot.squaredDistanceTo(target);
            if (distSq <= ATTACK_RANGE_SQ && bot.canSee(target)) {
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
        if (craftSwordIfPossible(bot, source, commander, world)) {
            return BotActions.selectBestMeleeWeapon(bot);
        }
        return BotActions.selectBestMeleeWeapon(bot);
    }

    private static boolean craftSwordIfPossible(ServerPlayerEntity bot,
                                                ServerCommandSource source,
                                                ServerPlayerEntity commander,
                                                ServerWorld world) {
        if (!canCraftSword(commander != null ? commander : bot)) {
            return false;
        }
        if (hasMaterial(bot, world, Items.COBBLESTONE)
                || hasMaterial(bot, world, Items.COBBLED_DEEPSLATE)
                || hasMaterial(bot, world, Items.BLACKSTONE)) {
            return CraftingHelper.craftGeneric(source, bot, commander, "sword", 1, "stone") > 0;
        }
        if (hasMaterial(bot, world, Items.IRON_INGOT)) {
            return CraftingHelper.craftGeneric(source, bot, commander, "sword", 1, "iron") > 0;
        }
        if (hasMaterial(bot, world, Items.DIAMOND)) {
            return CraftingHelper.craftGeneric(source, bot, commander, "sword", 1, "diamond") > 0;
        }
        if (hasPlanks(bot, world)) {
            return CraftingHelper.craftGeneric(source, bot, commander, "sword", 1, "wood") > 0;
        }
        return false;
    }

    private static boolean canCraftSword(ServerPlayerEntity commander) {
        if (commander == null) {
            return false;
        }
        Set<Identifier> history = net.shasankp000.GameAI.services.CraftingHistoryService.getHistory(commander);
        return history.contains(Identifier.of("minecraft", "wooden_sword"))
                || history.contains(Identifier.of("minecraft", "stone_sword"))
                || history.contains(Identifier.of("minecraft", "iron_sword"))
                || history.contains(Identifier.of("minecraft", "diamond_sword"));
    }

    private static boolean hasMaterial(ServerPlayerEntity bot, ServerWorld world, Item item) {
        if (countInInventory(bot, item) > 0) {
            return true;
        }
        return countInContainers(world, bot.getBlockPos(), item) > 0;
    }

    private static boolean hasPlanks(ServerPlayerEntity bot, ServerWorld world) {
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (!stack.isEmpty() && (stack.isIn(ItemTags.PLANKS) || stack.isIn(ItemTags.LOGS))) {
                return true;
            }
        }
        for (ContainerSlot slot : scanContainers(world, bot.getBlockPos())) {
            if (slot.stack.isIn(ItemTags.PLANKS) || slot.stack.isIn(ItemTags.LOGS)) {
                return true;
            }
        }
        return false;
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
                return;
            }
            DropSweeper.sweep(source.withSilent(), 6.0D, 4.0D, 8, 8_000L);
        } catch (Exception e) {
            LOGGER.warn("Drop sweep failed during hunt: {}", e.getMessage());
        }
    }

    private static void runFinalDropSweep(ServerCommandSource source, ServerPlayerEntity bot) {
        try {
            if (bot.getInventory().getEmptySlot() == -1) {
                return;
            }
            DropSweeper.sweep(source.withSilent(), FINAL_SWEEP_RADIUS, FINAL_SWEEP_VERTICAL, 12, 12_000L);
        } catch (Exception e) {
            LOGGER.warn("Final drop sweep failed after hunt: {}", e.getMessage());
        }
    }

    private static void offloadInventory(ServerPlayerEntity bot, ServerCommandSource source) {
        try {
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
        } catch (Exception e) {
            LOGGER.warn("Inventory offload failed during hunt: {}", e.getMessage());
        }
    }

    private static boolean hasRawFood(ServerPlayerEntity bot) {
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

    private static boolean isSunset(ServerWorld world) {
        long timeOfDay = world.getTimeOfDay() % 24000L;
        return timeOfDay >= 13000 && timeOfDay < 23000;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record HuntCandidate(HuntCatalog.HuntTarget target, LivingEntity entity) {}

    private record HuntRequest(String targetName,
                               int targetCount,
                               boolean checkSunset,
                               boolean listOnly,
                               boolean autoStopOnHunger) {}

    private record ContainerSlot(Inventory inv, BlockPos pos, int slot, ItemStack stack) {}

    private record FoodCandidate(Inventory inv,
                                 BlockPos pos,
                                 int slot,
                                 ItemStack stack,
                                 boolean raw,
                                 boolean rotten,
                                 double score) {}
}
