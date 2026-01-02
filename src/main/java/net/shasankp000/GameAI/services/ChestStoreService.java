package net.shasankp000.GameAI.services;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
import net.shasankp000.Entity.LookController;
import net.shasankp000.ChatUtils.ChatUtils;
import net.shasankp000.GameAI.BotActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class ChestStoreService {

    private static final Logger LOGGER = LoggerFactory.getLogger("chest-store");
    private static final MovementFlags DEFAULT_MOVEMENT = new MovementFlags(null, true, true, true);
    private static final MovementFlags WALK_ONLY = new MovementFlags(Boolean.FALSE, true, false, false);
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
            Items.ENDER_PEARL
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
        CompletableFuture.runAsync(() -> {
            BlockPos chestPos = lookedAt;
            if (chestPos == null) {
                chestPos = resolveRememberedChest(source, botId);
            }
            if (chestPos == null) {
                chestPos = findNearbyChest(source, botId, DEFAULT_CHEST_SEARCH_RADIUS, DEFAULT_CHEST_SEARCH_YSPAN);
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
            int moved = performStoreTransfer(source, botId, chestPos, amount, filter, deposit, DEFAULT_MOVEMENT);
            String action = deposit ? "Deposited" : "Withdrew";
            String fail = deposit ? "deposit" : "withdraw";
            server.execute(() -> ChatUtils.sendSystemMessage(source,
                    moved > 0 ? action + " " + moved + " items." : "Couldn't " + fail + " (unreachable, blocked, or no matching items)."));
        });
        return 1;
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
        Item item = stack.getItem();
        if (OFFLOAD_PROTECTED_ITEMS.contains(item)) {
            return true;
        }
        return COOKED_FOOD_ITEMS.contains(item);
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
            if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                return pos.toImmutable();
            }
        }
        return null;
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
                if (!state.isOf(Blocks.CHEST) && !state.isOf(Blocks.TRAPPED_CHEST)) {
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

    public static BlockPos placeChestNearBot(ServerCommandSource source, ServerPlayerEntity bot, boolean announce) {
        if (source == null || bot == null) {
            return null;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return null;
        }
        if (countItem(bot, Items.CHEST) <= 0) {
            int crafted = CraftingHelper.craftGeneric(source, bot, source.getPlayer(), "chest", 1, null);
            if (crafted <= 0 && countItem(bot, Items.CHEST) <= 0) {
                LOGGER.warn("Store: no chest in inventory and couldn't craft one.");
                return null;
            }
        }

        BlockPos origin = bot.getBlockPos();
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            candidates.add(origin.offset(dir));
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                candidates.add(origin.add(dx, 0, dz));
            }
        }

        BlockPos placed = callOnServer(server, () -> {
            if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
                return null;
            }
            for (BlockPos pos : candidates) {
                BlockPos below = pos.down();
                if (!world.getBlockState(below).isSolidBlock(world, below)) {
                    continue;
                }
                if (!world.getFluidState(pos).isEmpty() || !world.getFluidState(below).isEmpty()) {
                    continue;
                }
                BlockState state = world.getBlockState(pos);
                if (!state.isAir() && !state.isReplaceable()) {
                    continue;
                }
                // Ensure at least one adjacent standable spot exists for interacting with the chest after placing.
                if (!hasAnyAdjacentStand(world, pos)) {
                    continue;
                }
                boolean ok = BotActions.placeBlockAt(bot, pos, Direction.UP, List.of(Items.CHEST));
                if (!ok) {
                    continue;
                }
                BlockState now = world.getBlockState(pos);
                if (now.isOf(Blocks.CHEST) || now.isOf(Blocks.TRAPPED_CHEST)) {
                    return pos.toImmutable();
                }
            }
            return null;
        }, 2000, null);

        if (placed != null && announce) {
            BlockPos announcePos = placed;
            server.execute(() -> ChatUtils.sendSystemMessage(source,
                    "Placed a chest at " + announcePos.getX() + ", " + announcePos.getY() + ", " + announcePos.getZ() + "."));
        }
        if (placed != null) {
            LAST_PLACED_CHEST.put(bot.getUuid(), new WorldPos(source.getWorld().getRegistryKey(), placed.toImmutable()));
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
        if (bot == null || item == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < bot.getInventory().size(); i++) {
            ItemStack stack = bot.getInventory().getStack(i);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static int depositAll(ServerCommandSource source, ServerPlayerEntity bot, BlockPos chestPos) {
        return depositAllExcept(source, bot, chestPos, Set.of());
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
                stack -> !excluded.contains(stack.getItem()), true, DEFAULT_MOVEMENT);
    }

    public static int depositMatching(ServerCommandSource source, ServerPlayerEntity bot, BlockPos chestPos, Predicate<ItemStack> matcher) {
        if (bot == null || chestPos == null || source == null || matcher == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }

        return performStoreTransferWithBot(source, bot, chestPos, Integer.MAX_VALUE, matcher, true, DEFAULT_MOVEMENT);
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
        return performStoreTransferWithBot(source, bot, chestPos, Integer.MAX_VALUE, matcher, true, WALK_ONLY);
    }

    private static int performStoreTransferWithBot(ServerCommandSource source,
                                                   ServerPlayerEntity bot,
                                                   BlockPos chestPos,
                                                   int amount,
                                                   Predicate<ItemStack> filter,
                                                   boolean deposit,
                                                   MovementFlags movement) {
        if (source == null || bot == null || chestPos == null || filter == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }

        debugChest("Store transfer start: deposit=" + deposit
                + " chest=" + chestPos.toShortString()
                + " botPos=" + bot.getBlockPos().toShortString()
                + " thread=" + Thread.currentThread().getName()
                + " serverThread=" + server.isOnThread()
                + " sourceWorld=" + worldKeyName(source.getWorld())
                + " botWorld=" + worldKeyName(bot.getEntityWorld()));
        Boolean chestOk = callOnServer(server, () -> source.getWorld().getBlockEntity(chestPos) instanceof ChestBlockEntity, 800, Boolean.FALSE);
        if (!Boolean.TRUE.equals(chestOk)) {
            debugChest("Store transfer abort: chest missing at " + chestPos.toShortString());
            return 0;
        }

        if (deposit) {
            int have = callOnServer(server, () -> countMatching(bot.getInventory(), filter), 800, 0);
            debugChest("Store transfer matching count=" + have);
            if (have <= 0) {
                return 0;
            }
        }

        java.util.List<BlockPos> stands = callOnServer(server,
                () -> findStandCandidatesNearChest(source.getWorld(), bot, chestPos),
                1200,
                java.util.List.of());
        debugChest("Store transfer stand candidates=" + stands.size() + " stands=" + formatPositions(stands, 4));
        if (stands.isEmpty()) {
            return 0;
        }

        MovementFlags flags = movement != null ? movement : DEFAULT_MOVEMENT;
        boolean reached = false;
        for (BlockPos stand : stands) {
            BlockPos door = BlockInteractionService.findDoorAlongLine(bot, Vec3d.ofCenter(stand), 6.0D);
            if (door != null) {
                callOnServer(server, () -> MovementService.tryOpenDoorAt(bot, door), 800, Boolean.FALSE);
                maybeStepThroughDoor(bot, door, stand);
            }

            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    stand,
                    stand,
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
            double distSq = bot.getBlockPos().getSquaredDistance(stand);
            debugChest("Store transfer move: stand=" + stand.toShortString()
                    + " success=" + move.success()
                    + " distSq=" + String.format(Locale.ROOT, "%.2f", distSq)
                    + " detail=" + move.detail());
            if (move.success() || distSq <= BlockInteractionService.SURVIVAL_REACH_SQ) {
                reached = true;
                break;
            }
        }
        if (!reached) {
            debugChest("Store transfer abort: failed to reach stand near chest " + chestPos.toShortString());
            return 0;
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
            LOGGER.info("Store interact blocked: botPos={} chestPos={}", bot.getBlockPos().toShortString(), chestPos.toShortString());
            debugChest("Store transfer abort: cannot interact with chest " + chestPos.toShortString());
            return 0;
        }

        Integer moved = callOnServer(server, () -> {
            var be2 = source.getWorld().getBlockEntity(chestPos);
            if (!(be2 instanceof ChestBlockEntity chest)) {
                return 0;
            }
            if (deposit) {
                return moveItems(bot.getInventory(), chest, filter, amount);
            }
            return moveItems(chest, bot.getInventory(), filter, amount);
        }, 2500, 0);
        int movedCount = moved != null ? moved : 0;
        debugChest("Store transfer done: moved=" + movedCount + " chest=" + chestPos.toShortString());
        return movedCount;
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
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
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
        Boolean chestOk = callOnServer(server, () -> source.getWorld().getBlockEntity(chestPos) instanceof ChestBlockEntity, 800, Boolean.FALSE);
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
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos stand = chestPos.offset(dir);
            BlockPos below = stand.down();
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty()) {
                continue;
            }
            if (!world.getBlockState(stand).getCollisionShape(world, stand).isEmpty()) {
                continue;
            }
            if (!world.getBlockState(stand.up()).getCollisionShape(world, stand.up()).isEmpty()) {
                continue;
            }
            options.add(stand.toImmutable());
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
