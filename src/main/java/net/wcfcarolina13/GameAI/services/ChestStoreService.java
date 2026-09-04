package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.wcfcarolina13.Entity.LookController;
import net.wcfcarolina13.ChatUtils.ChatUtils;
import net.wcfcarolina13.GameAI.BotActions;
import net.wcfcarolina13.PlayerUtils.MiningTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.wcfcarolina13.PlayerUtils.InventoryIterator;

public final class ChestStoreService {

    private static final Logger LOGGER = LoggerFactory.getLogger("chest-store");
    private static final MovementFlags DEFAULT_MOVEMENT = new MovementFlags(null, true, true, true);
    private static final MovementFlags WALK_ONLY = new MovementFlags(Boolean.FALSE, true, false, false);
    private static final MovementFlags OBSTACLE_AWARE_PROBE = new MovementFlags(Boolean.FALSE, true, true, false);
    private static final MovementFlags CHEST_NAVIGATION = new MovementFlags(Boolean.FALSE, false, true, false);
    private static final int DEFAULT_CHEST_SEARCH_RADIUS = 12;
    private static final int DEFAULT_CHEST_SEARCH_YSPAN = 6;
    private static final double MAX_REMEMBERED_CHEST_DIST_SQ = 140.0D * 140.0D;
    private static final Map<UUID, WorldPos> LAST_PLACED_CHEST = new ConcurrentHashMap<>();

    private static final Set<Item> DEFAULT_STORE_ITEMS = Set.of(
            // Materials commonly used for building / scaffolding
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.STONE,
            Items.ANDESITE,
            Items.DIORITE,
            Items.GRANITE,
            Items.TUFF,
            Items.DEEPSLATE,
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.GRASS_BLOCK,
            Items.SAND,
            Items.RED_SAND,
            Items.GRAVEL,
            Items.NETHERRACK,
            Items.BLACKSTONE,
            Items.BASALT,
            Items.SMOOTH_BASALT,
            Items.CLAY,
            Items.BRICKS,
            Items.GLASS,
            Items.LADDER,
            Items.SCAFFOLDING,
            Items.STICK,
            Items.COAL,
            Items.CHARCOAL,

            // Common mob drops
            Items.ROTTEN_FLESH,
            Items.BONE,
            Items.STRING,
            Items.GUNPOWDER,
            Items.SPIDER_EYE,
            Items.SLIME_BALL,
            Items.LEATHER,
            Items.FEATHER,
            Items.ENDER_PEARL,
            Items.PHANTOM_MEMBRANE,
            Items.INK_SAC,
            Items.GLOW_INK_SAC,
            Items.RABBIT_HIDE,
            Items.RABBIT_FOOT,
            Items.ARMADILLO_SCUTE,
            Items.HONEYCOMB,
            Items.COBWEB,

            // Raw ores and minerals
            Items.RAW_IRON,
            Items.RAW_COPPER,
            Items.RAW_GOLD,
            Items.LAPIS_LAZULI,
            Items.REDSTONE,
            Items.DIAMOND,
            Items.EMERALD,
            Items.AMETHYST_SHARD,

            // Misc
            Items.EGG,
            Items.DANDELION,
            Items.POPPY,
            Items.BLUE_ORCHID,
            Items.ALLIUM,
            Items.AZURE_BLUET,
            Items.RED_TULIP,
            Items.ORANGE_TULIP,
            Items.WHITE_TULIP,
            Items.PINK_TULIP,
            Items.OXEYE_DAISY,
            Items.CORNFLOWER,
            Items.LILY_OF_THE_VALLEY,
            Items.TORCHFLOWER,
            Items.SUNFLOWER,
            Items.LILAC,
            Items.ROSE_BUSH,
            Items.PEONY,
            Items.PITCHER_PLANT
    );

    private static final Set<Item> COOKED_FOOD_ITEMS = Set.of(
            Items.COOKED_BEEF,
            Items.COOKED_PORKCHOP,
            Items.COOKED_MUTTON,
            Items.COOKED_CHICKEN,
            Items.COOKED_RABBIT,
            Items.COOKED_COD,
            Items.COOKED_SALMON,
            Items.BAKED_POTATO,
            Items.DRIED_KELP,
            Items.BREAD,
            Items.PUMPKIN_PIE,
            Items.RABBIT_STEW,
            Items.MUSHROOM_STEW,
            Items.BEETROOT_SOUP,
            Items.SUSPICIOUS_STEW
    );

    /** Minimum scaffold blocks to keep in inventory across all offload paths. */
    private static final int SCAFFOLD_RESERVE = 32;
    private static final Set<Item> SCAFFOLD_ITEMS = Set.of(
            Items.DIRT, Items.COARSE_DIRT, Items.ROOTED_DIRT,
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE,
            Items.GRAVEL, Items.SAND, Items.RED_SAND,
            Items.NETHERRACK, Items.SCAFFOLDING,
            Items.DIORITE, Items.ANDESITE, Items.GRANITE, Items.TUFF
    );

    private static final Set<Item> OFFLOAD_PROTECTED_ITEMS = Set.of(
            Items.TORCH,
            Items.SOUL_TORCH,
            Items.REDSTONE_TORCH,
            Items.LEAD,
            Items.COMPASS,
            Items.RECOVERY_COMPASS,
            Items.CLOCK,
            Items.ARROW,
            Items.SPECTRAL_ARROW,
            Items.TIPPED_ARROW
    );

    private ChestStoreService() {}

    private record MovementFlags(Boolean allowTeleportOverride, boolean fastReplan, boolean allowPursuit, boolean allowSnap) {}
    private record WorldPos(RegistryKey<World> worldKey, BlockPos pos) {}
    private record TransferAttemptResult(int moved, boolean chestPresent, boolean reachedStand, boolean interacted, String failureReason) {
        // Convenience constructor that defaults the reason. Used in success paths and legacy abort paths.
        TransferAttemptResult(int moved, boolean chestPresent, boolean reachedStand, boolean interacted) {
            this(moved, chestPresent, reachedStand, interacted, null);
        }
    }
    private record ChestStandCandidate(BlockPos pos, boolean directInteract, boolean staging, int score) {}
    private record ChestApproachResult(boolean reached, boolean interacted, BlockPos finalStand, String failureReason) {}
    public record StorageChestCandidate(BlockPos pos,
                                        String source,
                                        boolean preferredContents,
                                        boolean knownCapacity,
                                        int emptySlots,
                                        double distSq) {}
    public record DepositProbeResult(int moved, boolean chestPresent, boolean reachedStand, boolean interacted) {}

    public static int handleDeposit(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw) {
        return handleTransfer(source, bot, amountRaw, itemRaw, true);
    }

    public static int handleWithdraw(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw) {
        return handleTransfer(source, bot, amountRaw, itemRaw, false);
    }

    private static int handleTransfer(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw, boolean deposit) {
        if (bot == null || source == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }
        int amount = parseAmount(amountRaw, Integer.MAX_VALUE);
        String itemName = itemRaw != null ? itemRaw.trim().toLowerCase(Locale.ROOT) : "";
        BlockPos lookedAt = resolveChestPos(source);

        UUID botId = bot.getUuid();
        // This is a fresh user-initiated command; clear any stale ABORT_LATCH left over by
        // a previous /bot come or /bot follow that called forceAbort and didn't go through
        // beginSkill (which would have cleared the latch). Without this, the very first
        // isAbortRequested() check inside reachChestInteractionStand returns true and the
        // deposit aborts before trying any stand candidate. Mid-walk /bot stop still works
        // because it will set the latch fresh after this clear.
        TaskService.clearAbortLatch(botId);
        CompletableFuture.runAsync(() -> {
            BlockPos chestPos = lookedAt;
            if (chestPos == null) {
                ServerPlayerEntity liveBot = callOnServer(server, () -> server.getPlayerManager().getPlayer(botId), 800, null);
                if (liveBot != null && !liveBot.isRemoved()) {
                    List<StorageChestCandidate> candidates = listDepositChestCandidates(
                            source,
                            liveBot,
                            null,
                            DEFAULT_CHEST_SEARCH_RADIUS,
                            DEFAULT_CHEST_SEARCH_YSPAN,
                            MAX_REMEMBERED_CHEST_DIST_SQ);
                    if (!candidates.isEmpty()) {
                        chestPos = candidates.get(0).pos();
                    }
                }
            }
            if (chestPos == null && deposit) {
                ServerPlayerEntity liveBot = callOnServer(server, () -> server.getPlayerManager().getPlayer(botId), 800, null);
                if (liveBot != null && !liveBot.isRemoved()) {
                    chestPos = placeChestNearBot(source, liveBot, true);
                }
            }
            if (chestPos == null) {
                String msg = deposit
                        ? "No chest targeted or nearby; I couldn't place one to deposit into."
                        : "No chest targeted or nearby to withdraw from. Look at a chest or stand near one.";
                server.execute(() -> ChatUtils.sendSystemMessage(source, msg));
                return;
            }

            server.execute(() -> ChatUtils.sendSystemMessage(source,
                    "Heading to the chest to " + (deposit ? "deposit" : "withdraw") + " items..."));

            Predicate<ItemStack> filter = buildFilterForTransfer(source, chestPos, itemName, deposit);
            BlockPos chestPosFinal = chestPos;
            TransferAttemptResult result = performStoreTransferDetailed(source, botId, chestPosFinal, amount, filter, deposit, DEFAULT_MOVEMENT);
            String action = deposit ? "Deposited" : "Withdrew";
            String fail = deposit ? "deposit" : "withdraw";
            server.execute(() -> {
                if (result.moved() > 0) {
                    ChatUtils.sendSystemMessage(source, action + " " + result.moved() + " items.");
                } else {
                    String reason = result.failureReason() != null ? result.failureReason() : "unknown";
                    ChatUtils.sendSystemMessage(source,
                            "Couldn't " + fail + " at chest " + chestPosFinal.toShortString() + " (" + reason + ").");
                }
            });
        });
        return 1;
    }

    /**
     * User-facing detailed variant of {@link #performStoreTransfer}. Returns the full
     * {@link TransferAttemptResult} so {@link #handleTransfer} can build a specific
     * failure message instead of the unhelpful generic "unreachable, blocked, or no
     * matching items" string. Mirrors {@link #performStoreTransfer}'s precondition
     * checks but threads a {@code failureReason} through every abort path.
     */
    private static TransferAttemptResult performStoreTransferDetailed(ServerCommandSource source,
                                                                       UUID botId,
                                                                       BlockPos chestPos,
                                                                       int amount,
                                                                       Predicate<ItemStack> filter,
                                                                       boolean deposit,
                                                                       MovementFlags movement) {
        if (source == null || botId == null || chestPos == null) {
            return new TransferAttemptResult(0, false, false, false, "invalid-arguments");
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return new TransferAttemptResult(0, false, false, false, "server-null");
        }
        ServerPlayerEntity bot = callOnServer(server, () -> server.getPlayerManager().getPlayer(botId), 800, null);
        if (bot == null || bot.isRemoved()) {
            return new TransferAttemptResult(0, false, false, false, "bot-missing");
        }
        return performStoreTransferWithBotDetailed(source, bot, chestPos, amount, filter, deposit, movement);
    }

    private static Predicate<ItemStack> buildFilterForTransfer(ServerCommandSource source, BlockPos chestPos, String itemName, boolean deposit) {
        if (itemName == null || itemName.isBlank()) {
            if (deposit) {
                Set<Item> chestItems = snapshotChestItemTypes(source, chestPos);
                if (!chestItems.isEmpty()) {
                    LOGGER.info("Store default deposit: matching {} item types already in chest at {}",
                            chestItems.size(), chestPos != null ? chestPos.toShortString() : "null");
                    return stack -> {
                        if (stack == null || stack.isEmpty()) {
                            return false;
                        }
                        return chestItems.contains(stack.getItem()) || isDefaultStoreItem(stack);
                    };
                }
                return ChestStoreService::isDefaultStoreItem;
            }
            return ChestStoreService::isDefaultStoreItem;
        }
        return buildFilter(itemName);
    }

    private static Predicate<ItemStack> buildFilter(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return ChestStoreService::isDefaultStoreItem;
        }
        if ("all".equals(itemName) || "*".equals(itemName) || "everything".equals(itemName)) {
            return stack -> true;
        }
        return stack -> stack.getName().getString().toLowerCase(Locale.ROOT).contains(itemName);
    }

    private static boolean isDefaultStoreItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (isOffloadProtected(stack)) {
            return false;
        }
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.LOGS_THAT_BURN)
                || stack.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) {
            return true;
        }
        if (DEFAULT_STORE_ITEMS.contains(stack.getItem())) {
            return true;
        }
        if (stack.getItem() instanceof BlockItem) {
            // Catch most "builder blocks" without over-matching tools/food.
            String name = stack.getName().getString().toLowerCase(Locale.ROOT);
            return name.contains("stone")
                    || name.contains("cobble")
                    || name.contains("dirt")
                    || name.contains("sand")
                    || name.contains("gravel")
                    || name.contains("leaf")
                    || name.contains("leaves");
        }
        return false;
    }

    public static boolean isOffloadProtected(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        if (stack.isDamageable()) {
            return true;
        }
        // Bundles are user-managed containers — never offload them. Their contents may
        // include lodestone compasses, fast-travel artifacts, or other irreplaceable
        // tools that the OFFLOAD_PROTECTED_ITEMS check cannot see through the bundle wrapper.
        // Covers vanilla Items.BUNDLE plus all 16 dyed bundle variants via the tag.
        if (stack.isIn(net.minecraft.registry.tag.ItemTags.BUNDLES)) {
            return true;
        }
        Item item = stack.getItem();
        if (OFFLOAD_PROTECTED_ITEMS.contains(item)) {
            return true;
        }
        return COOKED_FOOD_ITEMS.contains(item);
    }

    /**
     * Count total scaffold-type blocks in the bot's inventory.
     */
    public static int countScaffoldInInventory(ServerPlayerEntity bot) {
        // Bundle-aware: scaffold blocks packed into a bundle still belong to the bot's reserve.
        return InventoryIterator.count(bot,
                stack -> !stack.isEmpty() && SCAFFOLD_ITEMS.contains(stack.getItem()));
    }

    /**
     * Wrap a deposit filter to enforce a scaffold reserve: once the bot's scaffold
     * inventory drops to SCAFFOLD_RESERVE, stop allowing scaffold-type items through.
     */
    static Predicate<ItemStack> withScaffoldReserve(ServerPlayerEntity bot, Predicate<ItemStack> inner) {
        if (bot == null) return inner;
        return stack -> {
            if (!inner.test(stack)) return false;
            if (SCAFFOLD_ITEMS.contains(stack.getItem()) && countScaffoldInInventory(bot) <= SCAFFOLD_RESERVE) {
                return false; // keep scaffold reserve
            }
            return true;
        };
    }

    private static BlockPos resolveRememberedChest(ServerCommandSource source, UUID botId) {
        if (source == null || botId == null) {
            return null;
        }
        WorldPos remembered = LAST_PLACED_CHEST.get(botId);
        if (remembered == null || remembered.pos() == null || remembered.worldKey() == null) {
            return null;
        }
        if (source.getWorld() == null || !remembered.worldKey().equals(source.getWorld().getRegistryKey())) {
            return null;
        }
        ServerPlayerEntity bot = source.getServer() != null ? source.getServer().getPlayerManager().getPlayer(botId) : null;
        if (bot == null) {
            return null;
        }
        BlockPos pos = remembered.pos();
        if (bot.getBlockPos().getSquaredDistance(pos) > MAX_REMEMBERED_CHEST_DIST_SQ) {
            return null;
        }
        if (source.getWorld().isChunkLoaded(pos)) {
            BlockState state = source.getWorld().getBlockState(pos);
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    public static List<StorageChestCandidate> listDepositChestCandidates(ServerCommandSource source,
                                                                         ServerPlayerEntity bot,
                                                                         java.util.function.Predicate<BotChestRegistryService.ItemSnapshot> preferredSnapshot,
                                                                         int localRadius,
                                                                         int localYSpan,
                                                                         double rememberedMaxDistSq) {
        if (source == null || bot == null) {
            return List.of();
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return List.of();
        }
        BlockPos origin = bot.getBlockPos();
        List<StorageChestCandidate> ordered = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        BlockPos remembered = resolveRememberedChest(source, bot.getUuid());
        if (remembered != null && seen.add(remembered.asLong())) {
            ordered.add(new StorageChestCandidate(
                    remembered.toImmutable(),
                    "last-placed",
                    preferredSnapshot == null,
                    false,
                    -1,
                    origin.getSquaredDistance(remembered)));
        }

        for (BlockPos local : findNearbyChestPositions(world, origin, localRadius, localYSpan)) {
            if (local != null && seen.add(local.asLong())) {
                ordered.add(new StorageChestCandidate(
                        local.toImmutable(),
                        "local-scan",
                        true,
                        false,
                        -1,
                        origin.getSquaredDistance(local)));
            }
        }

        List<BotChestRegistryService.DepositCandidate> rememberedCandidates =
                BotChestRegistryService.listDepositCandidatesForOwner(bot, world, origin, preferredSnapshot, rememberedMaxDistSq);
        for (BotChestRegistryService.DepositCandidate candidate : rememberedCandidates) {
            if (candidate == null || candidate.pos() == null || !seen.add(candidate.pos().asLong())) {
                continue;
            }
            ordered.add(new StorageChestCandidate(
                    candidate.pos().toImmutable(),
                    candidate.containsPreferredItems() ? "remembered-owner-wood" : "remembered-owner-fallback",
                    candidate.containsPreferredItems(),
                    candidate.knownCapacity(),
                    candidate.emptySlots(),
                    candidate.distSq()));
        }
        return List.copyOf(ordered);
    }

    private static Set<Item> snapshotChestItemTypes(ServerCommandSource source, BlockPos chestPos) {
        if (source == null || chestPos == null) {
            return Set.of();
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return Set.of();
        }
        return callOnServer(server, () -> {
            var be = source.getWorld().getBlockEntity(chestPos);
            if (!(be instanceof Inventory inv)) {
                return Set.of();
            }
            Set<Item> types = new HashSet<>();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = inv.getStack(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                types.add(stack.getItem());
            }
            return Set.copyOf(types);
        }, 1200, Set.of());
    }

    private static BlockPos findNearbyChest(ServerCommandSource source, UUID botId, int radius, int ySpan) {
        if (source == null || botId == null) {
            return null;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return null;
        }
        return callOnServer(server, () -> {
            ServerPlayerEntity bot = server.getPlayerManager().getPlayer(botId);
            if (bot == null || bot.isRemoved()) {
                return null;
            }
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                return null;
            }
            BlockPos origin = bot.getBlockPos();
            double best = Double.MAX_VALUE;
            BlockPos bestPos = null;
            for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -ySpan, -radius), origin.add(radius, ySpan, radius))) {
                if (!world.isChunkLoaded(pos)) {
                    continue;
                }
                BlockState state = world.getBlockState(pos);
                if (!state.isOf(Blocks.CHEST) && !state.isOf(Blocks.TRAPPED_CHEST) && !state.isOf(Blocks.BARREL)) {
                    continue;
                }
                double d = origin.getSquaredDistance(pos);
                if (d < best) {
                    best = d;
                    bestPos = pos.toImmutable();
                }
            }
            return bestPos;
        }, 1200, null);
    }

    private static List<BlockPos> findNearbyChestPositions(ServerWorld world, BlockPos origin, int radius, int ySpan) {
        if (world == null || origin == null) {
            return List.of();
        }
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.iterate(origin.add(-radius, -ySpan, -radius), origin.add(radius, ySpan, radius))) {
            if (!world.isChunkLoaded(pos)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (!state.isOf(Blocks.CHEST) && !state.isOf(Blocks.TRAPPED_CHEST) && !state.isOf(Blocks.BARREL)) {
                continue;
            }
            found.add(pos.toImmutable());
        }
        found.sort(java.util.Comparator.comparingDouble(origin::getSquaredDistance));
        return found;
    }

    public static BlockPos placeChestNearBot(ServerCommandSource source, ServerPlayerEntity bot, boolean announce) {
        if (source == null || bot == null) {
            return null;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return null;
        }
        if (countItem(bot, Items.CHEST) <= 0) {
            boolean crafted = ToolProvisionService.ensureChest(bot, source, source.getPlayer(), 1);
            if (!crafted && countItem(bot, Items.CHEST) <= 0) {
                LOGGER.warn("Store: no chest in inventory and couldn't craft one.");
                return null;
            }
        }

        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }
        CraftingHelper.PreparedPlacement prepared = CraftingHelper.prepareNearbyUtilityPlacement(
                source, bot, world, bot.getBlockPos(), "chest", 4.5D * 4.5D);
        if (prepared == null) {
            return null;
        }
        BotActions.PlaceResult placeResult = BotActions.tryPlaceBlockAt(bot, prepared.placePos(), Direction.UP, List.of(Items.CHEST));
        if (!placeResult.success()) {
            LOGGER.warn("Store chest placement failed at {} detail={}",
                    prepared.placePos().toShortString(), placeResult.reason());
            return null;
        }
        BlockPos placed = null;
        BlockState now = world.getBlockState(prepared.placePos());
        if (now.isOf(Blocks.CHEST) || now.isOf(Blocks.TRAPPED_CHEST)) {
            placed = prepared.placePos().toImmutable();
        }

        if (placed != null && announce) {
            BlockPos announcePos = placed;
            server.execute(() -> ChatUtils.sendSystemMessage(source,
                    "Placed a chest at " + announcePos.getX() + ", " + announcePos.getY() + ", " + announcePos.getZ() + "."));
        }
        if (placed != null) {
            LAST_PLACED_CHEST.put(bot.getUuid(), new WorldPos(source.getWorld().getRegistryKey(), placed.toImmutable()));
            // Register in the persistent chest registry
            if (source.getWorld() instanceof ServerWorld sw) {
                BotChestRegistryService.registerChest(bot, placed.toImmutable(), sw, "supply");
            }
        }
        return placed;
    }

    private static boolean hasAnyAdjacentStand(ServerWorld world, BlockPos chestPos) {
        if (world == null || chestPos == null) {
            return false;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos stand = chestPos.offset(dir);
            BlockPos below = stand.down();
            if (!world.getFluidState(stand).isEmpty() || !world.getFluidState(below).isEmpty()) {
                continue;
            }
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                continue;
            }
            if (!world.getBlockState(stand).getCollisionShape(world, stand).isEmpty()) {
                continue;
            }
            if (!world.getBlockState(stand.up()).getCollisionShape(world, stand.up()).isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static int countItem(ServerPlayerEntity bot, Item item) {
        if (item == null) {
            return 0;
        }
        // Bundle-aware count.
        return InventoryIterator.count(bot, stack -> stack.isOf(item));
    }

    public static int depositAll(ServerCommandSource source, ServerPlayerEntity bot, BlockPos chestPos) {
        return depositAllExcept(source, bot, chestPos, Set.of());
    }

    /** Walk to a specific chest and withdraw all items. Used by Quick Fetch. */
    public static int withdrawAllFrom(ServerCommandSource source, ServerPlayerEntity bot, BlockPos chestPos) {
        if (bot == null || chestPos == null || source == null) return 0;
        if (source.getServer() == null) return 0;
        return performStoreTransferWithBot(source, bot, chestPos, Integer.MAX_VALUE,
                stack -> true, false, DEFAULT_MOVEMENT);
    }

    public static int depositAllExcept(ServerCommandSource source, ServerPlayerEntity bot, BlockPos chestPos, Set<Item> excluded) {
        if (bot == null || chestPos == null || source == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }

        return performStoreTransferWithBot(source, bot, chestPos, Integer.MAX_VALUE,
                withScaffoldReserve(bot, stack -> !excluded.contains(stack.getItem())), true, DEFAULT_MOVEMENT);
    }

    public static int depositMatching(ServerCommandSource source, ServerPlayerEntity bot, BlockPos chestPos, Predicate<ItemStack> matcher) {
        if (bot == null || chestPos == null || source == null || matcher == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }

        return performStoreTransferWithBot(source, bot, chestPos, Integer.MAX_VALUE, withScaffoldReserve(bot, matcher), true, DEFAULT_MOVEMENT);
    }

    public static int depositMatchingWalkOnly(ServerCommandSource source, ServerPlayerEntity bot, BlockPos chestPos, Predicate<ItemStack> matcher) {
        if (bot == null || chestPos == null || source == null || matcher == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }

        debugChest("Deposit walk-only: chest=" + chestPos.toShortString()
                + " botPos=" + bot.getBlockPos().toShortString()
                + " thread=" + Thread.currentThread().getName()
                + " serverThread=" + server.isOnThread()
                + " sourceWorld=" + worldKeyName(source.getWorld())
                + " botWorld=" + worldKeyName(bot.getEntityWorld()));
        return performStoreTransferWithBot(source, bot, chestPos, Integer.MAX_VALUE, withScaffoldReserve(bot, matcher), true, WALK_ONLY);
    }

    public static int withdrawMatchingWalkOnly(ServerCommandSource source,
                                               ServerPlayerEntity bot,
                                               BlockPos chestPos,
                                               int amount,
                                               Predicate<ItemStack> matcher) {
        if (bot == null || chestPos == null || source == null || matcher == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }

        int effectiveAmount = amount <= 0 ? Integer.MAX_VALUE : amount;
        debugChest("Withdraw walk-only: chest=" + chestPos.toShortString()
                + " botPos=" + bot.getBlockPos().toShortString()
                + " thread=" + Thread.currentThread().getName()
                + " serverThread=" + server.isOnThread()
                + " sourceWorld=" + worldKeyName(source.getWorld())
                + " botWorld=" + worldKeyName(bot.getEntityWorld())
                + " amount=" + effectiveAmount);
        return performStoreTransferWithBot(source, bot, chestPos, effectiveAmount, matcher, false, WALK_ONLY);
    }

    public static DepositProbeResult probeDepositMatchingWalkOnly(ServerCommandSource source,
                                                                 ServerPlayerEntity bot,
                                                                 BlockPos chestPos,
                                                                 Predicate<ItemStack> matcher) {
        if (bot == null || chestPos == null || source == null || matcher == null) {
            return new DepositProbeResult(0, false, false, false);
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return new DepositProbeResult(0, false, false, false);
        }

        debugChest("Deposit probe walk-only: chest=" + chestPos.toShortString()
                + " botPos=" + bot.getBlockPos().toShortString()
                + " thread=" + Thread.currentThread().getName()
                + " serverThread=" + server.isOnThread()
                + " sourceWorld=" + worldKeyName(source.getWorld())
                + " botWorld=" + worldKeyName(bot.getEntityWorld()));
        TransferAttemptResult result = performStoreTransferWithBotDetailed(
                source,
                bot,
                chestPos,
                Integer.MAX_VALUE,
                withScaffoldReserve(bot, matcher),
                true,
                WALK_ONLY);
        return new DepositProbeResult(result.moved(), result.chestPresent(), result.reachedStand(), result.interacted());
    }

    public static DepositProbeResult probeDepositMatchingObstacleAware(ServerCommandSource source,
                                                                       ServerPlayerEntity bot,
                                                                       BlockPos chestPos,
                                                                       Predicate<ItemStack> matcher) {
        if (bot == null || chestPos == null || source == null || matcher == null) {
            return new DepositProbeResult(0, false, false, false);
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return new DepositProbeResult(0, false, false, false);
        }

        debugChest("Deposit probe obstacle-aware: chest=" + chestPos.toShortString()
                + " botPos=" + bot.getBlockPos().toShortString()
                + " thread=" + Thread.currentThread().getName()
                + " serverThread=" + server.isOnThread()
                + " sourceWorld=" + worldKeyName(source.getWorld())
                + " botWorld=" + worldKeyName(bot.getEntityWorld()));
        double distSq = bot.getBlockPos().getSquaredDistance(chestPos);
        MovementFlags flags = distSq <= 50.0D * 50.0D ? CHEST_NAVIGATION : OBSTACLE_AWARE_PROBE;
        TransferAttemptResult result = performStoreTransferWithBotDetailed(
                source,
                bot,
                chestPos,
                Integer.MAX_VALUE,
                withScaffoldReserve(bot, matcher),
                true,
                flags);
        return new DepositProbeResult(result.moved(), result.chestPresent(), result.reachedStand(), result.interacted());
    }

    /**
     * Deposits hunt loot: everything except equipped tools, cooked food, and other protected items.
     * Raw meat, leather, feathers, bones, wool, and other drops are deposited.
     */
    public static int depositHuntLoot(ServerCommandSource source, ServerPlayerEntity bot, BlockPos chestPos) {
        return depositMatching(source, bot, chestPos, stack -> !isOffloadProtected(stack));
    }

    private static int performStoreTransferWithBot(ServerCommandSource source,
                                                   ServerPlayerEntity bot,
                                                   BlockPos chestPos,
                                                   int amount,
                                                   Predicate<ItemStack> filter,
                                                   boolean deposit,
                                                   MovementFlags movement) {
        return performStoreTransferWithBotDetailed(source, bot, chestPos, amount, filter, deposit, movement).moved();
    }

    private static TransferAttemptResult performStoreTransferWithBotDetailed(ServerCommandSource source,
                                                                             ServerPlayerEntity bot,
                                                                             BlockPos chestPos,
                                                                             int amount,
                                                                             Predicate<ItemStack> filter,
                                                                             boolean deposit,
                                                                             MovementFlags movement) {
        if (source == null || bot == null || chestPos == null || filter == null) {
            LOGGER.info("Store transfer abort: invalid arguments (source/bot/chestPos/filter null)");
            return new TransferAttemptResult(0, false, false, false, "invalid-arguments");
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            LOGGER.info("Store transfer abort: server null");
            return new TransferAttemptResult(0, false, false, false, "server-null");
        }

        debugChest("Store transfer start: deposit=" + deposit
                + " chest=" + chestPos.toShortString()
                + " botPos=" + bot.getBlockPos().toShortString()
                + " moveProfile=" + movementProfileLabel(movement)
                + " thread=" + Thread.currentThread().getName()
                + " serverThread=" + server.isOnThread()
                + " sourceWorld=" + worldKeyName(source.getWorld())
                + " botWorld=" + worldKeyName(bot.getEntityWorld()));
        Boolean chestOk = callOnServer(server, () -> source.getWorld().getBlockEntity(chestPos) instanceof Inventory, 800, Boolean.FALSE);
        if (!Boolean.TRUE.equals(chestOk)) {
            LOGGER.info("Store transfer abort: chest missing at {} (bot={})",
                    chestPos.toShortString(), bot.getName().getString());
            return new TransferAttemptResult(0, false, false, false, "chest-missing");
        }

        if (deposit) {
            int have = callOnServer(server, () -> countMatching(bot.getInventory(), filter), 800, 0);
            debugChest("Store transfer matching count=" + have);
            if (have <= 0) {
                LOGGER.info("Store transfer abort: no matching items in inventory chest={} bot={}",
                        chestPos.toShortString(), bot.getName().getString());
                return new TransferAttemptResult(0, true, false, false, "no-matching-items");
            }
        }

        java.util.List<BlockPos> stands = callOnServer(server,
                () -> findStandCandidatesNearChest(source.getWorld(), bot, chestPos),
                1200,
                java.util.List.of());
        debugChest("Store transfer stand candidates=" + stands.size() + " stands=" + formatPositions(stands, 4));
        if (stands.isEmpty()) {
            LOGGER.info("Store transfer abort: no stand candidates from findStandCandidatesNearChest at {} (bot={})",
                    chestPos.toShortString(), bot.getName().getString());
            return new TransferAttemptResult(0, true, false, false, "no-stand-candidates");
        }

        MovementFlags flags = movement != null ? movement : DEFAULT_MOVEMENT;
        debugChest("Store transfer move profile=" + movementProfileLabel(flags)
                + " allowTpOverride=" + flags.allowTeleportOverride()
                + " fastReplan=" + flags.fastReplan()
                + " allowPursuit=" + flags.allowPursuit()
                + " allowSnap=" + flags.allowSnap());
        ChestApproachResult approach = reachChestInteractionStand(source, bot, source.getWorld(), chestPos, flags);
        if (!approach.reached()) {
            LOGGER.info("Store transfer abort: failed to reach chest stand at {} (bot={} reason={} botPos={})",
                    chestPos.toShortString(), bot.getName().getString(),
                    approach.failureReason(), bot.getBlockPos().toShortString());
            return new TransferAttemptResult(0, true, false, false, "approach:" + approach.failureReason());
        }
        if (approach.finalStand() != null) {
            debugChest("Store transfer reached stand=" + approach.finalStand().toShortString()
                    + " interacted=" + approach.interacted()
                    + " reason=" + approach.failureReason());
        }
        if (!approach.interacted() && !BlockInteractionService.canInteract(bot, chestPos)) {
            LOGGER.info("Store transfer abort: reached stand but cannot interact chest={} bot={} botPos={} stand={}",
                    chestPos.toShortString(), bot.getName().getString(),
                    bot.getBlockPos().toShortString(),
                    approach.finalStand() != null ? approach.finalStand().toShortString() : "null");
            return new TransferAttemptResult(0, true, true, false, "stand-out-of-reach");
        }

        LookController.faceBlock(bot, chestPos);
        if (!BlockInteractionService.canInteract(bot, chestPos)) {
            // Try opening a nearby door and retry once before failing.
            boolean opened = MovementService.tryOpenDoorToward(bot, chestPos);
            if (opened) {
                LookController.faceBlock(bot, chestPos);
            }
        }
        if (!BlockInteractionService.canInteract(bot, chestPos)) {
            LOGGER.info("Store interact blocked after door retry: chest={} bot={} botPos={}",
                    chestPos.toShortString(), bot.getName().getString(), bot.getBlockPos().toShortString());
            return new TransferAttemptResult(0, true, true, false, "interact-blocked");
        }

        Integer moved = callOnServer(server, () -> {
            BlockState state = source.getWorld().getBlockState(chestPos);
            Inventory storage;
            if (state.getBlock() instanceof net.minecraft.block.ChestBlock chestBlock) {
                storage = net.minecraft.block.ChestBlock.getInventory(chestBlock, state, source.getWorld(), chestPos, true);
            } else {
                var be2 = source.getWorld().getBlockEntity(chestPos);
                if (!(be2 instanceof Inventory inv)) {
                    return 0;
                }
                storage = inv;
            }
            if (storage == null) {
                return 0;
            }
            int result;
            if (deposit) {
                result = moveItems(bot.getInventory(), storage, filter, amount);
            } else {
                result = moveItems(storage, bot.getInventory(), filter, amount);
            }
            // Capture contents snapshot after any successful interaction so full/empty metadata stays fresh.
            if (source.getWorld() instanceof ServerWorld sw) {
                BotChestRegistryService.updateContentsSnapshot(bot, chestPos, sw, storage);
            }
            return result;
        }, 2500, 0);
        int movedCount = moved != null ? moved : 0;
        debugChest("Store transfer done: moved=" + movedCount + " chest=" + chestPos.toShortString());
        return new TransferAttemptResult(movedCount, true, true, true);
    }

    private static ChestApproachResult reachChestInteractionStand(ServerCommandSource source,
                                                                  ServerPlayerEntity bot,
                                                                  ServerWorld world,
                                                                  BlockPos chestPos,
                                                                  MovementFlags flags) {
        if (source == null || bot == null || world == null || chestPos == null) {
            return new ChestApproachResult(false, false, null, "invalid");
        }
        List<ChestStandCandidate> candidates = collectChestStandCandidates(world, bot, chestPos);
        debugChest("Store transfer stand candidates=" + candidates.size() + " stands="
                + candidates.stream()
                .limit(6)
                .map(candidate -> candidate.pos().toShortString()
                        + ":" + candidate.score()
                        + ":" + (candidate.directInteract() ? "direct" : "staging"))
                .toList());
        if (candidates.isEmpty()) {
            return new ChestApproachResult(false, false, null, "no-stands");
        }

        Set<Long> failedStands = new HashSet<>();
        String lastFailure = "stand-blocked";
        int consecutiveNoMove = 0;
        for (ChestStandCandidate candidate : candidates) {
            if (TaskService.isAbortRequested(bot.getUuid())) {
                return new ChestApproachResult(false, false, null, "aborted");
            }
            if (candidate == null || candidate.pos() == null || failedStands.contains(candidate.pos().asLong())) {
                continue;
            }
            BlockPos preMovePos = bot.getBlockPos();
            ChestApproachResult attempt = attemptChestStand(source, bot, world, chestPos, candidate, flags, failedStands);
            if (attempt.reached()) {
                return attempt;
            }
            failedStands.add(candidate.pos().asLong());
            lastFailure = attempt.failureReason();
            boolean botMoved = !bot.getBlockPos().equals(preMovePos);
            consecutiveNoMove = botMoved ? 0 : consecutiveNoMove + 1;
            if (consecutiveNoMove >= 3) {
                LOGGER.info("Store: early exit after {} consecutive no-move failures at chest {}",
                        consecutiveNoMove, chestPos.toShortString());
                break;
            }
        }
        return new ChestApproachResult(false, false, null, lastFailure);
    }

    private static ChestApproachResult attemptChestStand(ServerCommandSource source,
                                                         ServerPlayerEntity bot,
                                                         ServerWorld world,
                                                         BlockPos chestPos,
                                                         ChestStandCandidate candidate,
                                                         MovementFlags flags,
                                                         Set<Long> failedStands) {
        if (source == null || bot == null || world == null || chestPos == null || candidate == null || candidate.pos() == null) {
            return new ChestApproachResult(false, false, null, "invalid-candidate");
        }

        debugChest("Store transfer stand attempt: chest=" + chestPos.toShortString()
                + " stand=" + candidate.pos().toShortString()
                + " type=" + (candidate.directInteract() ? "direct" : "staging")
                + " score=" + candidate.score());
        preclearStorageApproach(world, bot, chestPos, candidate.pos());

        Direction towardStand = Direction.getFacing(
                candidate.pos().getX() - bot.getBlockPos().getX(), 0,
                candidate.pos().getZ() - bot.getBlockPos().getZ());
        if (towardStand.getAxis().isHorizontal()) {
            MovementService.clearLeafObstructionDetailed(bot, towardStand);
        }

        BlockPos door = BlockInteractionService.findDoorAlongLine(bot, Vec3d.ofCenter(candidate.pos()), 6.0D);
        if (door != null) {
            callOnServer(source.getServer(), () -> MovementService.tryOpenDoorAt(bot, door), 800, Boolean.FALSE);
            maybeStepThroughDoor(bot, door, candidate.pos());
        }

        // Early-out: if already within interaction range after preclear/leaf-clear, skip movement entirely
        if (BlockInteractionService.canInteract(bot, chestPos)) {
            debugChest("Store transfer early interact: stand=" + candidate.pos().toShortString()
                    + " botPos=" + bot.getBlockPos().toShortString());
            return new ChestApproachResult(true, true, bot.getBlockPos().toImmutable(), "early-interact");
        }

        MovementService.MovementPlan plan = new MovementService.MovementPlan(
                MovementService.Mode.DIRECT,
                candidate.pos(),
                candidate.pos(),
                null,
                null,
                bot.getHorizontalFacing());
        MovementService.MovementResult move = MovementService.execute(
                bot.getCommandSource(),
                bot,
                plan,
                flags.allowTeleportOverride(),
                flags.fastReplan(),
                flags.allowPursuit(),
                flags.allowSnap()
        );
        double distSq = bot.getBlockPos().getSquaredDistance(candidate.pos());
        boolean interacted = BlockInteractionService.canInteract(bot, chestPos);
        debugChest("Store transfer move: stand=" + candidate.pos().toShortString()
                + " type=" + (candidate.directInteract() ? "direct" : "staging")
                + " success=" + move.success()
                + " distSq=" + String.format(Locale.ROOT, "%.2f", distSq)
                + " interacted=" + interacted
                + " detail=" + move.detail());
        if (move.success() || distSq <= BlockInteractionService.SURVIVAL_REACH_SQ || interacted) {
            if (interacted) {
                return new ChestApproachResult(true, true, bot.getBlockPos().toImmutable(), "interact-range");
            }
            if (!candidate.directInteract()) {
                ChestApproachResult handoff = tryDirectChestStandHandoff(source, bot, world, chestPos, candidate.pos(), flags, failedStands);
                if (handoff.reached()) {
                    return handoff;
                }
                MovementService.clearRecentWalkAttempt(bot.getUuid());
                return new ChestApproachResult(false, false, candidate.pos(), handoff.failureReason());
            }
            return new ChestApproachResult(true, interacted, candidate.pos(), "stand-reached");
        }

        // Retry once after clearing leaf obstructions mid-path
        if (towardStand.getAxis().isHorizontal() && !TaskService.isAbortRequested(bot.getUuid())) {
            MovementService.LeafClearResult leafClear = MovementService.clearLeafObstructionDetailed(bot, towardStand);
            if (leafClear.countsAsCleared()) {
                MovementService.clearRecentWalkAttempt(bot.getUuid());
                move = MovementService.execute(bot.getCommandSource(), bot, plan,
                        flags.allowTeleportOverride(), flags.fastReplan(), flags.allowPursuit(), flags.allowSnap());
                distSq = bot.getBlockPos().getSquaredDistance(candidate.pos());
                interacted = BlockInteractionService.canInteract(bot, chestPos);
                if (move.success() || distSq <= BlockInteractionService.SURVIVAL_REACH_SQ || interacted) {
                    if (interacted) {
                        return new ChestApproachResult(true, true, bot.getBlockPos().toImmutable(), "interact-range-retry");
                    }
                    return new ChestApproachResult(true, interacted, candidate.pos(), "stand-reached-retry");
                }
            }
        }

        MovementService.clearRecentWalkAttempt(bot.getUuid());
        String detail = move.detail() == null ? "" : move.detail().toLowerCase(Locale.ROOT);
        if (detail.contains("walk blocked") || detail.contains("after replans") || detail.contains("pursuit failed")) {
            return new ChestApproachResult(false, false, candidate.pos(), "precision-churn");
        }
        return new ChestApproachResult(false, false, candidate.pos(), "stand-blocked");
    }

    private static ChestApproachResult tryDirectChestStandHandoff(ServerCommandSource source,
                                                                  ServerPlayerEntity bot,
                                                                  ServerWorld world,
                                                                  BlockPos chestPos,
                                                                  BlockPos stagingStand,
                                                                  MovementFlags flags,
                                                                  Set<Long> failedStands) {
        List<ChestStandCandidate> directCandidates = collectChestStandCandidates(world, bot, chestPos).stream()
                .filter(ChestStandCandidate::directInteract)
                .filter(candidate -> !failedStands.contains(candidate.pos().asLong()))
                .sorted(Comparator.comparingDouble(candidate -> candidate.pos().getSquaredDistance(stagingStand)))
                .toList();
        for (ChestStandCandidate direct : directCandidates) {
            if (direct == null || direct.pos() == null) {
                continue;
            }
            ChestApproachResult attempt = attemptChestStand(source, bot, world, chestPos, direct, flags, failedStands);
            if (attempt.reached()) {
                return attempt;
            }
            failedStands.add(direct.pos().asLong());
        }
        return new ChestApproachResult(false, false, stagingStand, "stand-handoff-failed");
    }

    private static List<ChestStandCandidate> collectChestStandCandidates(ServerWorld world,
                                                                         ServerPlayerEntity bot,
                                                                         BlockPos chestPos) {
        if (world == null || bot == null || chestPos == null) {
            return List.of();
        }
        LinkedHashSet<BlockPos> directCandidates = new LinkedHashSet<>();
        LinkedHashSet<BlockPos> stagingCandidates = new LinkedHashSet<>();

        for (Direction dir : Direction.Type.HORIZONTAL) {
            addChestStandCandidate(world, directCandidates, chestPos.offset(dir));
        }
        int yDelta = Math.abs(bot.getBlockPos().getY() - chestPos.getY());
        int yRange = yDelta >= 2 ? 3 : 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring < 2) {
                    continue;
                }
                for (int dy = -yRange; dy <= yRange; dy++) {
                    addChestStandCandidate(world, stagingCandidates, chestPos.add(dx, dy, dz));
                }
            }
        }
        int stagingRadius = yDelta >= 2 ? 5 : 3;
        SafePositionService.SurfaceStagingCandidate nearby =
                SafePositionService.findBestSurfaceStaging(world, chestPos, stagingRadius, false, bot.getBlockPos());
        if (nearby != null) {
            addChestStandCandidate(world, stagingCandidates, nearby.pos());
        }

        List<ChestStandCandidate> scored = new ArrayList<>();
        for (BlockPos pos : directCandidates) {
            scored.add(buildChestStandCandidate(world, bot, chestPos, pos, true));
        }
        for (BlockPos pos : stagingCandidates) {
            if (directCandidates.contains(pos)) {
                continue;
            }
            scored.add(buildChestStandCandidate(world, bot, chestPos, pos, false));
        }
        scored.removeIf(candidate -> candidate == null || candidate.pos() == null);
        scored.sort(Comparator.comparingInt(ChestStandCandidate::score).reversed());
        return List.copyOf(scored);
    }

    private static ChestStandCandidate buildChestStandCandidate(ServerWorld world,
                                                                ServerPlayerEntity bot,
                                                                BlockPos chestPos,
                                                                BlockPos stand,
                                                                boolean direct) {
        if (!isStorageStandCandidate(world, stand)) {
            return null;
        }
        SafePositionService.SurfaceCandidateAssessment assessment =
                SafePositionService.analyzeSurfaceCandidate(world, stand, bot.getBlockPos());
        int score = SafePositionService.scoreSurfaceAssessment(assessment, stand, bot.getBlockPos(), chestPos);
        double chestDistance = Math.sqrt(stand.getSquaredDistance(chestPos));
        boolean directInteract = direct && chestDistance <= 4.5D;
        score += directInteract ? 90 : 20;
        score -= (int) Math.round(chestDistance * 8.0D);
        int standYDelta = Math.abs(stand.getY() - bot.getBlockPos().getY());
        score -= standYDelta * 12;
        if (FollowMovementService.isDangerousDropCell(world, stand)) {
            score -= 180;
        }
        return new ChestStandCandidate(stand.toImmutable(), directInteract, !directInteract, score);
    }

    private static void addChestStandCandidate(ServerWorld world, Set<BlockPos> out, BlockPos candidate) {
        if (out == null || candidate == null || world == null) {
            return;
        }
        if (isStorageStandCandidate(world, candidate)) {
            out.add(candidate.toImmutable());
        }
    }

    private static boolean isStorageStandCandidate(ServerWorld world, BlockPos feet) {
        if (world == null || feet == null || !world.isChunkLoaded(feet) || !world.isChunkLoaded(feet.down())) {
            return false;
        }
        SafePositionService.SurfaceCandidateAssessment assessment = SafePositionService.analyzeSurfaceCandidate(world, feet, feet);
        if (!assessment.standable()) {
            return false;
        }
        return assessment.steepDropNeighbors() <= 1 && assessment.blockedCardinals() <= 3;
    }

    private static void preclearStorageApproach(ServerWorld world,
                                                ServerPlayerEntity bot,
                                                BlockPos chestPos,
                                                BlockPos stand) {
        if (world == null || bot == null || stand == null) {
            return;
        }
        // Clear corridor from bot toward stand (up to 4 blocks, limited by reach)
        BlockPos botPos = bot.getBlockPos();
        int dx = Integer.compare(stand.getX(), botPos.getX());
        int dz = Integer.compare(stand.getZ(), botPos.getZ());
        if (dx != 0 || dz != 0) {
            for (int step = 1; step <= 4; step++) {
                BlockPos along = botPos.add(dx * step, 0, dz * step);
                clearSoftStorageBlock(world, bot, along);
                clearSoftStorageBlock(world, bot, along.up());
            }
        }
        // Clear at stand and toward chest
        clearSoftStorageBlock(world, bot, stand);
        clearSoftStorageBlock(world, bot, stand.up());
        if (chestPos != null) {
            int cdx = Integer.compare(chestPos.getX(), stand.getX());
            int cdz = Integer.compare(chestPos.getZ(), stand.getZ());
            if (cdx != 0 || cdz != 0) {
                BlockPos towardChest = stand.add(cdx, 0, cdz);
                clearSoftStorageBlock(world, bot, towardChest);
                clearSoftStorageBlock(world, bot, towardChest.up());
            }
        }
    }

    private static void clearSoftStorageBlock(ServerWorld world, ServerPlayerEntity bot, BlockPos pos) {
        if (world == null || bot == null || pos == null || !bot.isAlive()) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        boolean soft = state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS)
                || state.isOf(Blocks.SNOW) || state.isReplaceable();
        if (!soft) {
            return;
        }
        if (bot.squaredDistanceTo(Vec3d.ofCenter(pos)) > BlockInteractionService.SURVIVAL_REACH_SQ) {
            return;
        }
        LookController.faceBlock(bot, pos);
        try {
            MiningTool.mineBlock(bot, pos, true, false).get(6, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static String movementProfileLabel(MovementFlags flags) {
        if (flags == null || flags.equals(DEFAULT_MOVEMENT)) {
            return "default";
        }
        if (flags.equals(WALK_ONLY)) {
            return "walk-only";
        }
        if (flags.equals(OBSTACLE_AWARE_PROBE)) {
            return "obstacle-aware";
        }
        if (flags.equals(CHEST_NAVIGATION)) {
            return "chest-navigation";
        }
        return "custom";
    }

    private static int parseAmount(String raw, int fallback) {
        if ("all".equalsIgnoreCase(raw)) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static BlockPos resolveChestPos(ServerCommandSource source) {
        var player = source.getPlayer();
        if (player == null) {
            return null;
        }
        var hit = player.raycast(6.0D, 1.0F, false);
        if (!(hit instanceof BlockHitResult bhr)) {
            return null;
        }
        BlockPos pos = bhr.getBlockPos();
        var state = source.getWorld().getBlockState(pos);
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)) {
            return pos.toImmutable();
        }
        return null;
    }

    private static int performStoreTransfer(ServerCommandSource source,
                                           UUID botId,
                                           BlockPos chestPos,
                                           int amount,
                                           Predicate<ItemStack> filter,
                                           boolean deposit,
                                           MovementFlags movement) {
        if (source == null || botId == null || chestPos == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }

        ServerPlayerEntity bot = callOnServer(server, () -> server.getPlayerManager().getPlayer(botId), 800, null);
        if (bot == null || bot.isRemoved()) {
            return 0;
        }
        Boolean chestOk = callOnServer(server, () -> source.getWorld().getBlockEntity(chestPos) instanceof Inventory, 800, Boolean.FALSE);
        if (!Boolean.TRUE.equals(chestOk)) {
            return 0;
        }

        if (deposit) {
            int have = callOnServer(server, () -> countMatching(bot.getInventory(), filter), 800, 0);
            if (have <= 0) {
                return 0;
            }
        }

        return performStoreTransferWithBot(source, bot, chestPos, amount, filter, deposit, movement);
    }

    private static java.util.List<BlockPos> findStandCandidatesNearChest(net.minecraft.world.World rawWorld, ServerPlayerEntity bot, BlockPos chestPos) {
        if (rawWorld == null || bot == null || chestPos == null) {
            return java.util.List.of();
        }
        if (!(rawWorld instanceof net.minecraft.server.world.ServerWorld world)) {
            return java.util.List.of();
        }
        java.util.List<BlockPos> options = new java.util.ArrayList<>();
        // Try stand candidates at the chest's Y level AND one block below. Elevated
        // chests (e.g. stacked on top of another chest, on a platform, or shelf-style
        // along a wall) have no footing at chest_y in the surrounding cells — the bot
        // must stand at chest_y - 1 with head at chest_y reaching up to the face.
        for (int yOffset : new int[]{0, -1}) {
            for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                BlockPos stand = chestPos.offset(dir).add(0, yOffset, 0);
                BlockPos below = stand.down();
                if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                    continue;
                }
                BlockState standState = world.getBlockState(stand);
                if (!standState.getCollisionShape(world, stand).isEmpty()
                        && !WalkablePartialBlocks.isStandable(standState, world, stand)) {
                    continue;
                }
                BlockPos head = stand.up();
                BlockState headState = world.getBlockState(head);
                if (!headState.getCollisionShape(world, head).isEmpty()
                        && !WalkablePartialBlocks.isPathable(headState, world, head)) {
                    continue;
                }
                BlockPos immutable = stand.toImmutable();
                if (!options.contains(immutable)) {
                    options.add(immutable);
                }
            }
        }
        options.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(bot.getBlockPos())));
        return options;
    }

    private static <T> T callOnServer(MinecraftServer server, java.util.function.Supplier<T> task, long timeoutMs, T fallback) {
        if (server == null || task == null) {
            return fallback;
        }
        if (server.isOnThread()) {
            try {
                return task.get();
            } catch (Throwable t) {
                return fallback;
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.complete(fallback);
            }
        });
        try {
            return future.get(Math.max(250L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int countMatching(Inventory inv, Predicate<ItemStack> filter) {
        if (inv == null || filter == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (filter.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void maybeStepThroughDoor(ServerPlayerEntity bot, BlockPos doorPos, BlockPos goal) {
        if (bot == null || doorPos == null || goal == null) {
            return;
        }
        if (!(bot.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld world)) {
            return;
        }
        BlockPos base = doorPos;
        if (!(world.getBlockState(base).getBlock() instanceof net.minecraft.block.DoorBlock)
                && world.getBlockState(base.down()).getBlock() instanceof net.minecraft.block.DoorBlock) {
            base = base.down();
        }
        var state = world.getBlockState(base);
        if (!(state.getBlock() instanceof net.minecraft.block.DoorBlock)) {
            return;
        }
        if (state.contains(net.minecraft.block.DoorBlock.OPEN) && !Boolean.TRUE.equals(state.get(net.minecraft.block.DoorBlock.OPEN))) {
            return;
        }
        net.minecraft.util.math.Direction toward = net.minecraft.util.math.Direction.getFacing(
                goal.getX() - base.getX(),
                0,
                goal.getZ() - base.getZ()
        );
        if (!toward.getAxis().isHorizontal()) {
            toward = bot.getHorizontalFacing();
        }
        BlockPos step = base.offset(toward);
        MovementService.nudgeTowardUntilClose(bot, step, 2.25D, 1400L, 0.22, "store-doorway-step");
    }

    // Direct slots only, deliberately: this is a generic Inventory->Inventory mover (chest side
    // included), so it has no bot handle to extract with. FOLLOW-UP: items sitting inside a bundle
    // are never offloaded to a chest — add a bot-aware pre-pass if offloading bundled stock matters.
    private static int moveItems(Inventory from, Inventory to, Predicate<ItemStack> filter, int amount) {
        int moved = 0;
        for (int i = 0; i < from.size() && moved < amount; i++) {
            ItemStack stack = from.getStack(i);
            if (stack.isEmpty()) continue;
            if (!filter.test(stack)) continue;
            int toMove = Math.min(stack.getCount(), amount - moved);
            ItemStack split = stack.split(toMove);
            if (split.isEmpty()) {
                continue;
            }
            ItemStack remainder = insertInto(to, split);
            if (!remainder.isEmpty()) {
                stack.increment(remainder.getCount());
                from.setStack(i, stack);
                break;
            }
            moved += toMove;
        }
        return moved;
    }

    private static ItemStack insertInto(Inventory inv, ItemStack stack) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty()) {
                inv.setStack(i, stack.copy());
                return ItemStack.EMPTY;
            }
            if (ItemStack.areItemsEqual(slot, stack) && ItemStack.areEqual(slot, stack) && slot.getCount() < slot.getMaxCount()) {
                int canAdd = Math.min(slot.getMaxCount() - slot.getCount(), stack.getCount());
                slot.increment(canAdd);
                stack.decrement(canAdd);
                if (stack.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return stack;
    }

    private static String worldKeyName(World world) {
        if (world == null) {
            return "null";
        }
        RegistryKey<World> key = world.getRegistryKey();
        return key != null ? key.getValue().toString() : "unknown";
    }

    private static String formatPositions(List<BlockPos> positions, int limit) {
        if (positions == null || positions.isEmpty()) {
            return "[]";
        }
        int cap = Math.max(1, limit);
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int count = Math.min(positions.size(), cap);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(positions.get(i).toShortString());
        }
        if (positions.size() > cap) {
            sb.append(", +").append(positions.size() - cap).append(" more");
        }
        sb.append(']');
        return sb.toString();
    }

    private static void debugChest(String message) {
        DebugToggleService.debug(LOGGER, "[ChestDebug] {}", message);
    }
}
