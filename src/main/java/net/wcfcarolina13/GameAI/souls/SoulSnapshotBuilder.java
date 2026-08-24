package net.wcfcarolina13.GameAI.souls;

import net.minecraft.block.BlockState;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.LightType;
import net.minecraft.world.poi.PointOfInterestTypes;
import net.wcfcarolina13.ChatUtils.BotMoodManager;
import net.wcfcarolina13.DangerZoneDetector.CliffDetector;
import net.wcfcarolina13.DangerZoneDetector.LavaDetector;
import net.wcfcarolina13.Entity.AutoFaceEntity;
import net.wcfcarolina13.Entity.EntityDetails;
import net.wcfcarolina13.FilingSystem.ManualConfig;
import net.wcfcarolina13.Frens;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.BotAutoReturnSunsetService;
import net.wcfcarolina13.GameAI.services.BotCombatCalloutService;
import net.wcfcarolina13.GameAI.services.BotFleeService;
import net.wcfcarolina13.GameAI.services.BotHomeService;
import net.wcfcarolina13.GameAI.services.BotIdleHobbiesService;
import net.wcfcarolina13.GameAI.services.BotQuestService;
import net.wcfcarolina13.GameAI.services.BotStuckService;
import net.wcfcarolina13.GameAI.services.HuntSessionService;
import net.wcfcarolina13.GameAI.services.MountPersistenceService;
import net.wcfcarolina13.GameAI.services.SurvivalRecruitmentService;
import net.wcfcarolina13.GameAI.services.TaskService;
import net.wcfcarolina13.PlayerUtils.BlockDistanceLimitedSearch;

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

    /** At most this many armor-stand lines reach a soul prompt. */
    static final int MAX_ARMOR_STANDS = 3;

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
        SoulTypes.SituationSnapshot situationSnapshot =
                captureSituation(server, bot, botSnapshot.ownerName(), reachability, player);

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

        // Main slots only: worn armor and the offhand are reported separately as wornGear, so the
        // carried digest never double-counts an equipped piece.
        int occupiedSlots = 0;
        List<SoulTypes.ItemFacts> carried = new ArrayList<>();
        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        List<ItemStack> mainStacks = bot.getInventory().getMainStacks();
        int inventorySlots = mainStacks.size();
        for (ItemStack stack : mainStacks) {
            if (stack.isEmpty()) {
                continue;
            }
            occupiedSlots++;
            carried.add(extractItemFacts(stack, 0));
            itemCounts.merge(Registries.ITEM.getId(stack.getItem()).getPath(),
                    stack.getCount(), Integer::sum);
        }
        SoulItemDescriber.InventoryDigest inventoryDigest = SoulItemDescriber.digest(carried);

        List<String> wornGear = new ArrayList<>();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack piece = bot.getEquippedStack(slot);
            if (!piece.isEmpty()) {
                wornGear.add(SoulItemDescriber.describe(extractItemFacts(piece, 0)));
            }
        }
        ItemStack offhand = bot.getEquippedStack(EquipmentSlot.OFFHAND);
        if (!offhand.isEmpty()) {
            wornGear.add(SoulItemDescriber.describe(extractItemFacts(offhand, 0)) + " (offhand)");
        }

        Optional<TaskService.ActiveTaskInfo> taskInfo = TaskService.getActiveTaskInfo(bot.getUuid());
        String activeTask = taskInfo.map(TaskService.ActiveTaskInfo::name).orElse("");
        String taskState = taskInfo.map(info -> info.state().name()).orElse("");
        BotEventHandler.Mode currentMode = BotEventHandler.getCurrentMode(bot);
        String behaviorMode = currentMode != null ? currentMode.name() : "";

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
                occupiedSlots, inventorySlots, inventoryDigest.bulk(),
                wornGear, inventoryDigest.notable(), itemCounts,
                BotMoodManager.getMoodDescription(bot), behaviorMode, activeTask, taskState,
                homeName, ownerName, recruited, companionQuestStage, permanentCompanion, activeQuest);
    }

    private static SoulTypes.PlayerSnapshot capturePlayer(ServerPlayerEntity bot, ServerPlayerEntity player) {
        double dx = player.getX() - bot.getX();
        double dz = player.getZ() - bot.getZ();
        int distanceBlocks = (int) Math.round(Math.sqrt(player.squaredDistanceTo(bot)));

        return new SoulTypes.PlayerSnapshot(player.getUuid(), player.getName().getString(), distanceBlocks,
                cardinalDirection(dx, dz), player.getHealth(), player.getMaxHealth(),
                player.getHungerManager().getFoodLevel(), heldItemName(player), player.isSleeping(),
                lookTargetName(player), captureActivity(player));
    }

    /** Instantaneous player states + the most recent tracked action (Option C awareness). */
    private static String captureActivity(ServerPlayerEntity player) {
        try {
            List<String> states = new ArrayList<>();
            if (player.isSneaking()) {
                states.add("sneaking");
            }
            if (player.isSprinting()) {
                states.add("sprinting");
            }
            if (player.isSwimming()) {
                states.add("swimming");
            }
            if (player.isGliding()) {
                states.add("gliding on elytra");
            }
            if (player.isUsingItem() && !player.getActiveItem().isEmpty()) {
                states.add("using " + player.getActiveItem().getName().getString());
            }
            String recent = SoulPlayerActivity.recentAction(
                    player.getUuid(), System.currentTimeMillis()).orElse("");
            return SoulPlayerActivity.describe(states, recent);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Display name of the block the player's crosshair rests on (20-block reach), "" when none.
     * Grounds deictic questions ("what are these?", "this trapdoor") that a bot-centric snapshot
     * can never answer -- field-tested failure class, 2026-08-24.
     */
    private static String lookTargetName(ServerPlayerEntity player) {
        try {
            HitResult hit = player.raycast(20.0D, 1.0F, false);
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                return player.getEntityWorld().getBlockState(blockHit.getBlockPos())
                        .getBlock().getName().getString();
            }
        } catch (Throwable ignored) {
            // Look target is best-effort context, never load-bearing for capture.
        }
        return "";
    }

    private static String heldItemName(ServerPlayerEntity player) {
        ItemStack held = player.getMainHandStack();
        return held.isEmpty() ? "bare hands" : SoulItemDescriber.describe(extractItemFacts(held, 0));
    }

    // 12, not 8: field test 2026-08-24 — leading the bot around a base room in FOLLOW mode put
    // real workstations just past the old radius and Jake denied their existence.
    /** Horizontal radius of the functional-block ("facilities") scan around the bot. */
    private static final int FACILITY_SCAN_RADIUS = 12;
    /** Vertical half-height of the facilities scan box. */
    private static final int FACILITY_SCAN_HEIGHT = 3;

    /**
     * Scans a box around the bot for functional blocks. Detection is structural, with no
     * per-block allowlist: a block counts as functional when its state has a block entity
     * (storage and workstations -- chests, furnaces, barrels, beds, lecterns, and their modded
     * equivalents) or when it maps to a vanilla point-of-interest type (job sites, nether portal,
     * lodestone, beehives). Utility phrases are attached later by {@link SoulBlockKnowledge},
     * which is free to not know a detected block.
     */
    private static List<SoulTypes.RawFacility> scanFacilities(ServerWorld world, ServerPlayerEntity bot) {
        BlockPos center = bot.getBlockPos();
        List<SoulTypes.RawFacility> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(
                center.add(-FACILITY_SCAN_RADIUS, -FACILITY_SCAN_HEIGHT, -FACILITY_SCAN_RADIUS),
                center.add(FACILITY_SCAN_RADIUS, FACILITY_SCAN_HEIGHT, FACILITY_SCAN_RADIUS))) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            boolean pottedPlant = state.getBlock() instanceof FlowerPotBlock
                    && !"flower_pot".equals(Registries.BLOCK.getId(state.getBlock()).getPath());
            if (!pottedPlant && !state.hasBlockEntity()
                    && PointOfInterestTypes.getTypeForState(state).isEmpty()) {
                continue;
            }
            // Honest perception: only count a facility the bot could actually see -- an
            // unobstructed sight line from its eyes. Hidden rooms and buried chests must not
            // leak into the prompt (field ruling 2026-08-24).
            if (!hasLineOfSight(world, bot, pos)) {
                continue;
            }
            // Lit state matters conversationally (an unlit furnace is not "a fire") and the
            // 8B model otherwise invents it -- field ruling 2026-08-24.
            String displayName = state.getBlock().getName().getString();
            if (state.contains(Properties.LIT)) {
                displayName += state.get(Properties.LIT) ? " (lit)" : " (unlit)";
            }
            found.add(new SoulTypes.RawFacility(
                    Registries.BLOCK.getId(state.getBlock()).getPath(),
                    displayName,
                    pos.getX(), pos.getY(), pos.getZ()));
        }
        return found;
    }

    /**
     * True when a sight line from the bot's eyes reaches {@code pos}. Non-opaque obstructions
     * (glass, panes -- display cases) do not block sight: field test 2026-08-24 showed a geared
     * armor stand behind glass LOS-filtered while its bare neighbor answered "nothing". The ray
     * restarts just past up to {@link #MAX_TRANSPARENT_SKIPS} transparent hits.
     */
    private static boolean hasLineOfSight(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        Vec3d eye = bot.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        if (eye.squaredDistanceTo(target) <= 4.0D) {
            return true; // adjacent blocks: the ray can clip its own start/target ambiguously
        }
        Vec3d from = eye;
        for (int skips = 0; skips <= MAX_TRANSPARENT_SKIPS; skips++) {
            BlockHitResult hit = world.raycast(new RaycastContext(from, target,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, bot));
            if (hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos)) {
                return true;
            }
            BlockState obstruction = world.getBlockState(hit.getBlockPos());
            if (obstruction.isOpaque()) {
                return false;
            }
            // Transparent obstruction: continue the ray from just past the hit point.
            Vec3d direction = target.subtract(from).normalize();
            from = hit.getPos().add(direction.multiply(0.1D));
            if (from.squaredDistanceTo(target) <= 1.0D) {
                return true;
            }
        }
        return false;
    }

    /** Sight passes through at most this many transparent blocks (glass walls, panes). */
    private static final int MAX_TRANSPARENT_SKIPS = 4;

    /**
     * Armor stands within the facility radius that display at least one item, described through
     * the same item describer as inventory/gear so enchantments and custom names surface.
     * Armor stands are excluded from the animal/entity scan as decoration; their contents are
     * situation-relevant anyway (field-tested gap, 2026-08-24).
     */
    private static List<String> scanArmorStands(ServerWorld world, ServerPlayerEntity bot) {
        BlockPos center = bot.getBlockPos();
        List<String> lines = new ArrayList<>();
        Box box = new Box(center).expand(FACILITY_SCAN_RADIUS, FACILITY_SCAN_HEIGHT, FACILITY_SCAN_RADIUS);
        List<ArmorStandEntity> allStands = world.getEntitiesByClass(ArmorStandEntity.class, box, e -> true);
        // Entity sight uses the proven eye-to-eye check (EntityVisibilityUtil, already used by
        // the death-event observer) instead of a feet-block raycast: the block raycast graded a
        // stand invisible from angles where its body was plainly in view.
        List<ArmorStandEntity> visibleStands = allStands.stream()
                .filter(stand -> net.wcfcarolina13.GameAI.services.EntityVisibilityUtil.canSee(bot, stand))
                .toList();
        for (ArmorStandEntity stand : visibleStands) {
            List<String> displayed = new ArrayList<>();
            int occupiedSlots = 0;
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET,
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
                ItemStack stack = stand.getEquippedStack(slot);
                if (!stack.isEmpty()) {
                    occupiedSlots++;
                    displayed.add(SoulItemDescriber.describe(extractItemFacts(stack, 0)));
                }
            }
            // Per-stand ground truth: position + slot occupancy + the exact described line.
            // Splits the 2026-08-24 "Nothing" failure into empty-equipment vs model-ignored:
            // stand the player calls "geared" logging slots=0 means the gear is not real
            // equipment (e.g. a decor mod rendering display entities over a bare stand).
            org.slf4j.LoggerFactory.getLogger("frens-souls").info(
                    "[souls] armorstand pos={} slots={} line={}",
                    stand.getBlockPos().toShortString(), occupiedSlots,
                    displayed.isEmpty() ? "-" : String.join(", ", displayed));
            if (!displayed.isEmpty()) {
                lines.add("Armor stand displaying: " + String.join(", ", displayed));
            }
        }
        if (!allStands.isEmpty()) {
            // Include look-alike entity counts: modern decor mods and datapacks dress rooms
            // with item/block DisplayEntities or item frames that read as "armor stands with
            // gear" to a player while the real ArmorStandEntity nearby is bare. Verified in
            // 1.21.11 source: ArmorStandEntity extends LivingEntity and its equip() writes the
            // same EntityEquipment that getEquippedStack reads, so vanilla-geared stands DO
            // report their gear -- a geared-looking stand logging slots=0 means the gear
            // belongs to one of these other entity kinds.
            int displays = world.getEntitiesByClass(
                    net.minecraft.entity.decoration.DisplayEntity.class, box, e -> true).size();
            int frames = world.getEntitiesByClass(
                    net.minecraft.entity.decoration.ItemFrameEntity.class, box, e -> true).size();
            org.slf4j.LoggerFactory.getLogger("frens-souls").info(
                    "[souls] armorstands inBox={} visible={} described={} displayEntities={} itemFrames={}",
                    allStands.size(), visibleStands.size(), lines.size(), displays, frames);
        }
        return lines;
    }

    /**
     * Projects one live stack into plain {@link SoulTypes.ItemFacts}. Component reads are generic
     * (custom name, enchantments, bundle/shulker contents, durability) so any item that carries
     * them -- vanilla or modded -- is picked up without per-item casework. Container contents are
     * extracted one level deep ({@code depth} guards against pathological nesting).
     */
    private static SoulTypes.ItemFacts extractItemFacts(ItemStack stack, int depth) {
        String name = stack.getName().getString();
        String typeName = stack.getItem().getName().getString();

        List<String> enchantments = new ArrayList<>();
        ItemEnchantmentsComponent enchants = stack.getOrDefault(
                DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
        if (enchants != null && !enchants.isEmpty()) {
            enchants.getEnchantmentEntries().forEach(entry ->
                    enchantments.add(Enchantment.getName(entry.getKey(), entry.getIntValue()).getString()));
        }

        List<SoulTypes.ItemFacts> contents = new ArrayList<>();
        if (depth < 1) {
            BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                bundle.iterate().forEach(inner -> contents.add(extractItemFacts(inner, depth + 1)));
            }
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container != null) {
                container.streamNonEmpty().forEach(inner -> contents.add(extractItemFacts(inner, depth + 1)));
            }
        }

        double wear = stack.isDamageable() && stack.getMaxDamage() > 0
                ? stack.getDamage() / (double) stack.getMaxDamage()
                : 0.0;

        return new SoulTypes.ItemFacts(name, typeName, stack.getCount(), stack.getMaxCount(),
                enchantments, contents, wear);
    }

    /**
     * Reads live danger/entity/combat/survival/companion/mount/base/hunt/hobby state on the
     * server thread and projects it into plain {@link SituationInputs}, then hands off to the
     * pure {@link #buildSituation(SituationInputs)} seam for filtering/sorting/day-floor math.
     *
     * <p>Every source group is wrapped defensively: a throwing or absent source falls back to
     * that group's neutral default rather than failing the whole capture, mirroring
     * {@link #captureBot(MinecraftServer, ServerPlayerEntity)}'s recruitment-read style.
     *
     * @param ownerName the bot's already-captured {@link SoulTypes.BotSnapshot#ownerName()}, used
     *     to exclude the owner/commander from the aggregated nearby-animal view below.
     * @param reachability this turn's pre-classified reachability -- gates the shoulder-pet read
     *     below to LOCAL the same way {@link #capture} gates {@code playerSnapshot} construction.
     * @param player the local player, or {@code null} when there is no shared presence (REMOTE) --
     *     used only to read their shoulder-pet NBT when {@code reachability} is LOCAL.
     */
    private static SoulTypes.SituationSnapshot captureSituation(MinecraftServer server, ServerPlayerEntity bot,
            String ownerName, SoulTypes.Reachability reachability, ServerPlayerEntity player) {
        long nowEpochMs = System.currentTimeMillis();

        double dangerDistance = -1.0D;
        List<RawEntity> entities = List.of();
        boolean enclosed = false;
        boolean hasHeadroom = false;
        boolean hasEscapeRoute = false;
        try {
            // Read enclosure/danger/entity state directly from its underlying sources instead of
            // BotEventHandler.createInitialState(bot): that helper side-effects
            // BotStuckService.setLastSafePosition and pays for several fields this capture
            // discards (hotbar, armor, hunger, risk map, ...).
            BotStuckService.EnvironmentSnapshot environmentSnapshot = BotStuckService.analyzeEnvironment(bot);
            enclosed = environmentSnapshot.enclosed();
            hasHeadroom = environmentSnapshot.hasHeadroom();
            hasEscapeRoute = environmentSnapshot.hasEscapeRoute();

            // Lava/cliff distance separately rather than DangerZoneDetector.detectDangerZone's
            // lavaDistance+cliffDistance sum (meaningless once both hazards are present); take the
            // nearer of whichever hazard(s) are actually detected.
            double lavaDistance = LavaDetector.detectNearestLava(bot, 10, 10);
            double cliffDistance = CliffDetector.detectCliffWithBoundingBox(bot, 5, 5);
            boolean lavaPresent = lavaDistance > 0.0D && lavaDistance != Double.MAX_VALUE;
            boolean cliffPresent = cliffDistance > 0.0D && cliffDistance != Double.MAX_VALUE;
            if (lavaPresent && cliffPresent) {
                dangerDistance = Math.min(lavaDistance, cliffDistance);
            } else if (lavaPresent) {
                dangerDistance = lavaDistance;
            } else if (cliffPresent) {
                dangerDistance = cliffDistance;
            }

            // Radius 16 (not the shared AutoFaceEntity/BotEventHandler default of 10) so ambient
            // fliers (parrots, bats) and distant animals are visible to the soul prompt even when
            // outside the bot's own combat-scan range -- this capture is the only caller widened.
            List<Entity> nearby = AutoFaceEntity.detectNearbyEntities(bot, 16);
            List<RawEntity> collected = new ArrayList<>(nearby.size());
            for (Entity e : nearby) {
                // detectNearbyEntities applies no entity-type filter (see its own Javadoc/callers
                // in AutoFaceEntity) -- without this guard, dropped item stacks, arrows, and boats
                // show up as fabricated "Animals nearby: Oak Planks x3" entries. Only living,
                // non-decorative entities belong in the situation snapshot; armor stands are
                // LivingEntity in vanilla but are decoration, not creatures.
                if (!(e instanceof LivingEntity) || e instanceof ArmorStandEntity) {
                    continue;
                }
                EntityDetails details = EntityDetails.from(bot, e);
                // Name from the entity TYPE first ("wolf", "parrot"), not the display name --
                // a display name is the vanilla custom name (e.g. "Rex") when one is set, and a
                // small local model can't map an arbitrary pet name back to a species. A custom
                // name is still surfaced, just annotated onto the species: "wolf (Rex)".
                String typePath = EntityType.getId(e.getType()).getPath();
                String customName = e.hasCustomName() ? e.getCustomName().getString() : null;
                collected.add(new RawEntity(formatEntityName(typePath, customName), details.isHostile(),
                        details.getX() - bot.getX(), details.getY() - bot.getY(), details.getZ() - bot.getZ()));
            }
            // A tamed parrot auto-perches on its owner's shoulder; while perched it is not a world
            // entity at all -- it is stored as NbtCompound on the holding ServerPlayerEntity
            // (getLeftShoulderNbt/getRightShoulderNbt), so detectNearbyEntities above can never see
            // it. Read both shoulders on the bot itself and, when LOCAL, on the present player too,
            // and fold them into the same collected list so the existing aggregation below (name
            // grouping, owner/self exclusion, cap) handles them uniformly with ground sightings.
            appendShoulderPets(collected, bot, "your shoulder");
            if (reachability == SoulTypes.Reachability.LOCAL && player != null) {
                appendShoulderPets(collected, player, player.getName().getString() + "'s shoulder");
            }
            entities = collected;
        } catch (Throwable ignored) {
            // Danger/entity/enclosure state is best-effort context, never load-bearing for capture.
        }

        String behaviorMode = "";
        boolean following = false;
        try {
            BotEventHandler.Mode mode = BotEventHandler.getCurrentMode(bot);
            behaviorMode = mode != null ? mode.name() : "";
            // isFollowingPlayer excludes return-to-base (which also runs Mode.FOLLOW internally)
            // -- it is true only when the bot is actively following a player entity.
            following = BotEventHandler.isFollowingPlayer(bot);
        } catch (Throwable ignored) {
        }

        String standingOn = "";
        List<String> rawNearbyBlocks = List.of();
        try {
            ServerWorld world = (ServerWorld) bot.getEntityWorld();
            standingOn = world.getBlockState(bot.getBlockPos().down()).getBlock().getName().getString();
            // Mirrors BotEventHandler's own nearby-block scan (BlockDistanceLimitedSearch(bot, 3, 5))
            // so the soul prompt's block awareness matches what the bot's own AI already scans.
            rawNearbyBlocks = new BlockDistanceLimitedSearch(bot, 3, 5).detectNearbyBlocks();
        } catch (Throwable ignored) {
        }

        List<SoulTypes.RawFacility> rawFacilities = List.of();
        try {
            rawFacilities = scanFacilities((ServerWorld) bot.getEntityWorld(), bot);
        } catch (Throwable ignored) {
        }

        int blockLight = -1;
        int skyLight = -1;
        try {
            ServerWorld world = (ServerWorld) bot.getEntityWorld();
            blockLight = world.getLightLevel(LightType.BLOCK, bot.getBlockPos());
            skyLight = world.getLightLevel(LightType.SKY, bot.getBlockPos());
        } catch (Throwable ignored) {
        }

        List<String> rawArmorStands = List.of();
        try {
            rawArmorStands = scanArmorStands((ServerWorld) bot.getEntityWorld(), bot);
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
        Optional<String> atBase = Optional.empty();
        try {
            ServerWorld world = (ServerWorld) bot.getEntityWorld();
            List<BotHomeService.BaseEntry> bases = BotHomeService.listBases(server, world);
            knownBaseCount = bases.size();
            Optional<BlockPos> sleepPos = BotHomeService.getLastSleep(bot);
            if (sleepPos.isPresent()) {
                lastSleepLabel = nearestBaseLabel(bases, sleepPos.get(), 32.0D * 32.0D);
            }
            atBase = nearestBaseLabel(bases, bot.getBlockPos(), 32.0D * 32.0D);
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

        SituationInputs inputs = new SituationInputs(dangerDistance, entities, ownerName, bot.getName().getString(),
                standingOn, rawNearbyBlocks, rawFacilities, rawArmorStands, blockLight, skyLight,
                enclosed, hasHeadroom, hasEscapeRoute,
                behaviorMode, following, inCombat, postCombatLinger, recentKillCount,
                inShelter, surfaceRecoveryActive, breakingFree, nightTravelActive,
                recruitedAtEpochMs, deathCount, nowEpochMs,
                mount, knownBaseCount, lastSleepLabel, atBase, hunt, lastHobby);
        return buildSituation(inputs);
    }

    /**
     * Reads both shoulder slots on {@code holder} (a tamed parrot auto-perches there; while
     * perched it is stored as {@link NbtCompound}, not a world {@link Entity} -- see the
     * {@code captureSituation} call site) and appends one {@link RawEntity} per occupied slot to
     * {@code out}, labeled with {@code ownerLabel} (e.g. {@code "your shoulder"} or
     * {@code "Bradley's shoulder"}).
     */
    private static void appendShoulderPets(List<RawEntity> out, ServerPlayerEntity holder, String ownerLabel) {
        appendShoulderPet(out, holder.getLeftShoulderNbt(), ownerLabel);
        appendShoulderPet(out, holder.getRightShoulderNbt(), ownerLabel);
    }

    /**
     * Decodes the "id" key the same way vanilla's {@code ServerPlayerEntity#spawnShoulderEntity}
     * does when it respawns the shoulder passenger back into the world (both read
     * {@code EntityType.CODEC} off the raw {@code "id"} string tag) -- an empty NbtCompound means
     * that shoulder slot is unoccupied, and a present-but-undecodable id is skipped rather than
     * fabricating a sighting.
     */
    private static void appendShoulderPet(List<RawEntity> out, NbtCompound shoulderNbt, String ownerLabel) {
        if (shoulderNbt == null || shoulderNbt.isEmpty()) {
            return;
        }
        shoulderNbt.get("id", EntityType.CODEC).ifPresent(type -> out.add(new RawEntity(
                shoulderEntry(EntityType.getId(type).getPath(), ownerLabel), false, 0.0D, 0.0D, 0.0D)));
    }

    /**
     * Nearest {@link BotHomeService.BaseEntry#label()} to {@code pos} among {@code bases}, within
     * {@code maxDistanceSq} blocks squared. Shared by the last-sleep-location and current-position
     * ("am I at a base right now") lookups above -- both are "nearest known base to some point"
     * with the same 32-block radius, differing only in which point they measure from.
     */
    private static Optional<String> nearestBaseLabel(List<BotHomeService.BaseEntry> bases, BlockPos pos,
            double maxDistanceSq) {
        double bestDistanceSq = maxDistanceSq;
        String bestLabel = null;
        for (BotHomeService.BaseEntry base : bases) {
            double distanceSq = pos.getSquaredDistance(base.pos());
            if (distanceSq <= bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestLabel = base.label();
            }
        }
        return Optional.ofNullable(bestLabel);
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
    record RawEntity(String name, boolean hostile, double dx, double dy, double dz) {
        RawEntity {
            name = name == null ? "" : name;
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
            String ownerName,
            String botName,
            String standingOn,
            List<String> nearbyBlocks,          // raw scan output, duplicates expected -- see buildSituation
            List<SoulTypes.RawFacility> rawFacilities, // functional-block sightings, duplicates expected
            List<String> armorStands,           // described armor-stand lines, uncapped
            int blockLight, int skyLight,       // light at the bot's feet; -1 = unknown
            boolean enclosed, boolean hasHeadroom, boolean hasEscapeRoute,
            String behaviorMode,
            boolean following,
            boolean inCombat, boolean postCombatLinger, int recentKillCount,
            boolean inShelter, boolean surfaceRecoveryActive, boolean breakingFree,
            boolean nightTravelActive,
            long recruitedAtEpochMs, int deathCount, long nowEpochMs,
            Optional<SoulTypes.MountSummary> mount,
            int knownBaseCount,
            Optional<String> lastSleepLabel,
            Optional<String> atBase,
            Optional<SoulTypes.HuntSummary> hunt,
            Optional<String> lastHobby) {
        SituationInputs {
            entities = entities == null ? List.of() : List.copyOf(entities);
            ownerName = ownerName == null ? "" : ownerName;
            botName = botName == null ? "" : botName;
            standingOn = standingOn == null ? "" : standingOn;
            nearbyBlocks = nearbyBlocks == null ? List.of() : List.copyOf(nearbyBlocks);
            rawFacilities = rawFacilities == null ? List.of() : List.copyOf(rawFacilities);
            armorStands = armorStands == null ? List.of() : List.copyOf(armorStands);
            behaviorMode = behaviorMode == null ? "" : behaviorMode;
            mount = mount == null ? Optional.empty() : mount;
            lastSleepLabel = lastSleepLabel == null ? Optional.empty() : lastSleepLabel;
            atBase = atBase == null ? Optional.empty() : atBase;
            hunt = hunt == null ? Optional.empty() : hunt;
            lastHobby = lastHobby == null ? Optional.empty() : lastHobby;
        }
    }

    /**
     * Pure transform: filters to hostiles, computes rounded 3D distance, sorts nearest-first and
     * caps at 5; aggregates non-hostile entities (excluding the owner and the bot itself) by name,
     * most-numerous first, capped at 4; floors {@code companionDays} from epoch millis; passes
     * every other group through unchanged. Contains all filtering/sorting/capping/day-floor/default
     * logic for the situation snapshot — {@link #captureSituation(MinecraftServer, ServerPlayerEntity, String)}
     * only reads and forwards.
     */
    static SoulTypes.SituationSnapshot buildSituation(SituationInputs inputs) {
        int dangerDistance = inputs.dangerDistance() <= 0.0D
                ? -1
                : (int) Math.round(inputs.dangerDistance());

        List<SoulTypes.HostileSighting> hostiles = inputs.entities().stream()
                .filter(RawEntity::hostile)
                .map(e -> new SoulTypes.HostileSighting(e.name(), cardinalDirection(e.dx(), e.dz()),
                        (int) Math.round(Math.sqrt(e.dx() * e.dx() + e.dy() * e.dy() + e.dz() * e.dz()))))
                .sorted(Comparator.comparingInt(SoulTypes.HostileSighting::distanceBlocks))
                .limit(5)
                .collect(Collectors.toList());

        List<String> nearbyAnimals = inputs.entities().stream()
                .filter(e -> !e.hostile())
                .filter(e -> !e.name().isBlank())
                .filter(e -> !e.name().equalsIgnoreCase(inputs.ownerName()))
                .filter(e -> !e.name().equalsIgnoreCase(inputs.botName()))
                .collect(Collectors.groupingBy(RawEntity::name, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(4)
                .map(entry -> entry.getValue() == 1L ? entry.getKey() : entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.toList());

        // Dedupe the raw (possibly duplicate-heavy) block scan by type name, most-numerous first,
        // capped at 4. Air is already excluded upstream by BlockDistanceLimitedSearch.canReachBlock.
        List<String> nearbyBlocks = inputs.nearbyBlocks().stream()
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.groupingBy(name -> name, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(4)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        int companionDays = inputs.recruitedAtEpochMs() <= 0L
                ? -1
                : (int) Math.floorDiv(inputs.nowEpochMs() - inputs.recruitedAtEpochMs(), 86_400_000L);

        return new SoulTypes.SituationSnapshot(dangerDistance, hostiles, nearbyAnimals,
                inputs.standingOn(), nearbyBlocks,
                SoulBlockKnowledge.digestFacilities(inputs.rawFacilities()),
                inputs.rawFacilities(),
                inputs.armorStands().size() > MAX_ARMOR_STANDS
                        ? inputs.armorStands().subList(0, MAX_ARMOR_STANDS)
                        : inputs.armorStands(),
                inputs.blockLight(), inputs.skyLight(),
                inputs.enclosed(), inputs.hasHeadroom(), inputs.hasEscapeRoute(),
                inputs.behaviorMode(), inputs.following(),
                inputs.inCombat(), inputs.postCombatLinger(), inputs.recentKillCount(),
                inputs.inShelter(), inputs.surfaceRecoveryActive(), inputs.breakingFree(),
                inputs.nightTravelActive(), companionDays, inputs.deathCount(),
                inputs.mount(), inputs.knownBaseCount(), inputs.lastSleepLabel(), inputs.atBase(),
                inputs.hunt(), inputs.lastHobby());
    }

    // ─────── Pure helpers (unit-testable without a Minecraft server) ───────

    /** Rounds a coordinate to the nearest 8-block increment. */
    static int roundToEight(int value) {
        return (int) Math.round(value / 8.0D) * 8;
    }

    /**
     * Species-first entity label: {@code typePath} alone ("wolf", "parrot") when the entity has
     * no custom name, or {@code "wolf (Rex)"} when it does. A small local model can classify a
     * species reliably but can't map an arbitrary player-chosen name back to one, so the species
     * always leads and the custom name is annotated onto it rather than replacing it.
     */
    static String formatEntityName(String typePath, String customNameOrNull) {
        String base = typePath == null ? "" : typePath;
        if (customNameOrNull == null || customNameOrNull.isBlank()) {
            return base;
        }
        return base + " (" + customNameOrNull + ")";
    }

    /** Formats one shoulder-perched pet sighting, e.g. {@code "parrot (on your shoulder)"}. */
    static String shoulderEntry(String species, String ownerLabel) {
        return species + " (on " + ownerLabel + ")";
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

}
