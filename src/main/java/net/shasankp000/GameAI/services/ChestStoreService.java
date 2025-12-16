package net.shasankp000.GameAI.services;

import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.shasankp000.ChatUtils.ChatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ChestStoreService {

    private static final Logger LOGGER = LoggerFactory.getLogger("chest-store");

    private ChestStoreService() {}

    public static int handleDeposit(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw) {
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
        String itemName = itemRaw != null ? itemRaw.toLowerCase() : "";
        server.execute(() -> ChatUtils.sendSystemMessage(source, "Heading to the chest to deposit items..."));
        UUID botId = bot.getUuid();
        CompletableFuture.runAsync(() -> {
            int moved = performStoreTransfer(source, botId, chestPos, amount, itemName, true);
            server.execute(() -> ChatUtils.sendSystemMessage(source,
                    moved > 0 ? "Deposited " + moved + " " + itemName + "." : "Couldn't deposit (unreachable, blocked, or no matching items)."));
        });
        return 1;
    }

    public static int handleWithdraw(ServerCommandSource source, ServerPlayerEntity bot, String amountRaw, String itemRaw) {
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
        String itemName = itemRaw != null ? itemRaw.toLowerCase() : "";
        server.execute(() -> ChatUtils.sendSystemMessage(source, "Heading to the chest to withdraw items..."));
        UUID botId = bot.getUuid();
        CompletableFuture.runAsync(() -> {
            int moved = performStoreTransfer(source, botId, chestPos, amount, itemName, false);
            server.execute(() -> ChatUtils.sendSystemMessage(source,
                    moved > 0 ? "Withdrew " + moved + " " + itemName + "." : "Couldn't withdraw (unreachable, blocked, or missing item)."));
        });
        return 1;
    }

    public static int depositAll(ServerCommandSource source, ServerPlayerEntity bot, BlockPos chestPos) {
        if (bot == null || chestPos == null || source == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }

        // Blocking call to ensure it finishes before skill continues
        return callOnServer(server, () -> {
            int moved = performStoreTransfer(source, bot.getUuid(), chestPos, Integer.MAX_VALUE, "", true);
            return moved;
        }, 5000, 0);
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
                                           String itemName,
                                           boolean deposit) {
        if (source == null || botId == null || chestPos == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }

        // Resolve the bot and the chest on the server thread.
        ServerPlayerEntity bot = callOnServer(server, () -> server.getPlayerManager().getPlayer(botId), 800, null);
        if (bot == null || bot.isRemoved()) {
            return 0;
        }
        Boolean chestOk = callOnServer(server, () -> source.getWorld().getBlockEntity(chestPos) instanceof ChestBlockEntity, 800, Boolean.FALSE);
        if (!Boolean.TRUE.equals(chestOk)) {
            return 0;
        }

        if (deposit && itemName != null && !itemName.isBlank()) {
            int have = callOnServer(server, () -> countMatching(bot.getInventory(), itemName), 800, 0);
            if (have <= 0) {
                return 0;
            }
        }

        // Move next to the chest (no teleport; relies on door opening in MovementService).
        java.util.List<BlockPos> stands = callOnServer(server,
                () -> findStandCandidatesNearChest(source.getWorld(), bot, chestPos),
                1200,
                java.util.List.of());
        if (stands.isEmpty()) {
            return 0;
        }
        boolean reached = false;
        for (BlockPos stand : stands) {
            // If there's a door between us and the target stand, try opening it first.
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
            MovementService.MovementResult move = MovementService.execute(bot.getCommandSource(), bot, plan, false, true, true, false);
            double distSq = bot.getBlockPos().getSquaredDistance(stand);
            LOGGER.info("Store move: targetStand={} success={} dist={}", stand.toShortString(), move.success(), String.format(Locale.ROOT, "%.2f", Math.sqrt(distSq)));
            if (move.success() || distSq <= BlockInteractionService.SURVIVAL_REACH_SQ) {
                reached = true;
                break;
            }
        }
        if (!reached) {
            return 0;
        }

        // Open a blocking door near the chest if needed (door-in-enclosure case).
        if (!BlockInteractionService.canInteract(bot, chestPos)) {
            BlockPos door = BlockInteractionService.findBlockingDoor(bot, chestPos, BlockInteractionService.SURVIVAL_REACH_SQ);
            if (door != null) {
                callOnServer(server, () -> MovementService.tryOpenDoorAt(bot, door), 800, Boolean.FALSE);
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
                return moveItems(bot.getInventory(), chest, itemName, amount, true);
            }
            return moveItems(chest, bot.getInventory(), itemName, amount, false);
        }, 2500, 0);
        return moved != null ? moved : 0;
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

    private static int countMatching(Inventory inv, String itemName) {
        if (inv == null || itemName == null || itemName.isBlank()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase(Locale.ROOT);
            if (name.contains(itemName.toLowerCase(Locale.ROOT))) {
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
            // door is still closed; nothing to do
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

    private static int moveItems(Inventory from, Inventory to, String itemName, int amount, boolean exactName) {
        int moved = 0;
        for (int i = 0; i < from.size() && moved < amount; i++) {
            ItemStack stack = from.getStack(i);
            if (stack.isEmpty()) continue;
            String name = stack.getName().getString().toLowerCase();
            if (!name.contains(itemName)) continue;
            int toMove = Math.min(stack.getCount(), amount - moved);
            ItemStack split = stack.split(toMove);
            if (split.isEmpty()) {
                continue;
            }
            ItemStack remainder = insertInto(to, split);
            if (!remainder.isEmpty()) {
                // Put back what didn't fit
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
