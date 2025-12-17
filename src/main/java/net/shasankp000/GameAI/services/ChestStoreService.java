package net.shasankp000.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.shasankp000.Entity.LookController;
import net.shasankp000.ChatUtils.ChatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class ChestStoreService {

    private static final Logger LOGGER = LoggerFactory.getLogger("chest-store");
    private static final MovementFlags DEFAULT_MOVEMENT = new MovementFlags(null, true, true, true);
    private static final MovementFlags WALK_ONLY = new MovementFlags(Boolean.FALSE, true, false, false);

    private ChestStoreService() {}

    private record MovementFlags(Boolean allowTeleportOverride, boolean fastReplan, boolean allowPursuit, boolean allowSnap) {}

    public static int handleDeposit(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw) {
        return handleTransfer(source, bot, amountRaw, itemRaw, true);
    }

    public static int handleWithdraw(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw) {
        return handleTransfer(source, bot, amountRaw, itemRaw, false);
    }

    private static int handleTransfer(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw, boolean deposit) {
        BlockPos chestPos = resolveChestPos(source);
        if (chestPos == null || bot == null) {
            ChatUtils.sendSystemMessage(source, "Look at a chest.");
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }
        int amount = parseAmount(amountRaw, Integer.MAX_VALUE);
        String itemName = itemRaw != null ? itemRaw.toLowerCase(Locale.ROOT) : "";
        
        server.execute(() -> ChatUtils.sendSystemMessage(source, "Heading to the chest to " + (deposit ? "deposit" : "withdraw") + " items..."));
        
        Predicate<ItemStack> filter = stack -> {
            if (itemName.isBlank()) return true;
            return stack.getName().getString().toLowerCase(Locale.ROOT).contains(itemName);
        };

        UUID botId = bot.getUuid();
        CompletableFuture.runAsync(() -> {
            int moved = performStoreTransfer(source, botId, chestPos, amount, filter, deposit, DEFAULT_MOVEMENT);
            String action = deposit ? "Deposited" : "Withdrew";
            String fail = deposit ? "deposit" : "withdraw";
            server.execute(() -> ChatUtils.sendSystemMessage(source,
                    moved > 0 ? action + " " + moved + " items." : "Couldn't " + fail + " (unreachable, blocked, or no matching items)."));
        });
        return 1;
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

        java.util.List<BlockPos> stands = callOnServer(server,
                () -> findStandCandidatesNearChest(source.getWorld(), bot, chestPos),
                1200,
                java.util.List.of());
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
            if (move.success() || distSq <= BlockInteractionService.SURVIVAL_REACH_SQ) {
                reached = true;
                break;
            }
        }
        if (!reached) {
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
        return moved != null ? moved : 0;
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
}
