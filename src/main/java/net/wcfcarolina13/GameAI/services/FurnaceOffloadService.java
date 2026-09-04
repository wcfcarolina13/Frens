package net.wcfcarolina13.GameAI.services;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.FuelOffloadPolicy.Candidate;
import net.wcfcarolina13.GameAI.skills.SkillPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fallback offload path: when the bot's inventory is full and no chest can be found <em>or</em>
 * placed, dump cheap fuel-eligible items (leaf litter, leaves/saplings, sticks, surplus planks)
 * into a nearby furnace's fuel slot. Better than dropping them — they get burned later.
 *
 * <p><b>Threading:</b> {@link #depositFuel} is a WORKER-THREAD entry point. It must not be called
 * from the server thread (it walks synchronously). All inventory and furnace mutations hop to the
 * server thread via {@code server.execute} + {@link CompletableFuture}.
 */
public final class FurnaceOffloadService {

    private static final Logger LOGGER = LoggerFactory.getLogger("furnace-offload");
    private static final double STATION_REACH_SQ = 4.5D * 4.5D;
    private static final int Y_SPAN = 4;
    private static final int FUEL_SLOT = 1;
    private static final int MAX_FURNACES = 4;
    private static final long SERVER_CALL_TIMEOUT_MS = 1200L;

    /**
     * Planks kept back for scaffolding / building. Mirrors ChestStoreService's scaffold reserve
     * so the furnace fallback can never strip the bot of its pillaring stock.
     */
    public static final int DEFAULT_PLANK_RESERVE = Math.max(ChestStoreService.scaffoldReserve(), 32);

    private FurnaceOffloadService() {}

    /**
     * Walks to nearby furnaces and inserts cheap fuel into their fuel slot.
     *
     * <p>Call from a worker thread only.
     *
     * @return true if at least one item was moved into a furnace
     */
    public static boolean depositFuel(ServerCommandSource source, ServerPlayerEntity bot, int radius) {
        if (source == null || bot == null) {
            return false;
        }
        MinecraftServer server = source.getServer();
        if (server == null || server.isOnThread() || TaskService.isServerStopping()) {
            return false;
        }
        if (!(bot.getEntityWorld() instanceof ServerWorld world)) {
            return false;
        }
        UUID botId = bot.getUuid();
        if (TaskService.isAbortRequested(botId)) {
            return false;
        }

        List<Candidate> snapshot = callOnServer(server, () -> snapshotFuelCandidates(bot, world), List.of());
        List<Candidate> giveaways = FuelOffloadPolicy.giveaways(snapshot, DEFAULT_PLANK_RESERVE);
        if (giveaways.isEmpty()) {
            return false;
        }

        BlockPos origin = bot.getBlockPos();
        List<BlockPos> furnaces = callOnServer(server,
                () -> SmeltingService.findNearbyFurnaces(world, origin, radius, Y_SPAN), List.of());
        if (furnaces.isEmpty()) {
            return false;
        }

        boolean movedAnything = false;
        int visited = 0;
        for (BlockPos furnacePos : furnaces) {
            if (visited >= MAX_FURNACES || TaskService.isAbortRequested(botId)) {
                break;
            }
            if (ProtectedZoneService.isProtectedForBot(botId, furnacePos, world, null)) {
                continue;
            }
            if (!callOnServer(server, () -> fuelSlotAccepts(world, furnacePos), Boolean.FALSE)) {
                continue;
            }
            visited++;
            if (!walkToFurnace(source, bot, world, furnacePos)) {
                continue;
            }
            ServerPlayerEntity liveBot = callOnServer(server,
                    () -> server.getPlayerManager().getPlayer(botId), null);
            if (liveBot == null || liveBot.isRemoved()) {
                break;
            }
            int moved = callOnServer(server, () -> insertFuel(liveBot, world, furnacePos), 0);
            if (moved > 0) {
                movedAnything = true;
                LOGGER.info("[furnace-offload] {} gave {} item(s) to furnace at {}",
                        liveBot.getName().getString(), moved, furnacePos.toShortString());
            }
            // Re-check what is left; stop early when nothing qualifies any more.
            List<Candidate> left = FuelOffloadPolicy.giveaways(
                    callOnServer(server, () -> snapshotFuelCandidates(liveBot, world), List.<Candidate>of()),
                    DEFAULT_PLANK_RESERVE);
            if (left.isEmpty()) {
                break;
            }
        }
        return movedAnything;
    }

    // --- server-thread helpers -------------------------------------------------

    /** Server thread only. */
    private static List<Candidate> snapshotFuelCandidates(ServerPlayerEntity bot, ServerWorld world) {
        List<Candidate> out = new ArrayList<>();
        if (bot == null || world == null) {
            return out;
        }
        var inv = bot.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            if (ChestStoreService.isOffloadProtected(stack)) continue;
            if (!SmeltingService.isFuelItem(stack, world)) continue;
            int tier = FuelOffloadPolicy.tierFor(
                    stack.isOf(Items.LEAF_LITTER),
                    stack.isIn(ItemTags.LEAVES) || stack.isIn(ItemTags.SAPLINGS),
                    stack.isOf(Items.STICK),
                    stack.isIn(ItemTags.PLANKS));
            if (tier == FuelOffloadPolicy.TIER_NEVER) continue;
            out.add(new Candidate(stack.getItem().toString(), i, stack.getCount(), tier));
        }
        return out;
    }

    /** Server thread only: is this furnace's fuel slot empty, or holding something we can top up? */
    private static boolean fuelSlotAccepts(ServerWorld world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return false;
        }
        ItemStack fuel = furnace.getStack(FUEL_SLOT);
        if (fuel == null || fuel.isEmpty()) {
            return true;
        }
        if (fuel.getCount() >= fuel.getMaxCount()) {
            return false;
        }
        // Occupied by a different item — leave it alone; vanilla furnaces hold one fuel type.
        return true;
    }

    /** Server thread only. Returns the number of items moved. */
    private static int insertFuel(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (!(world.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
            return 0;
        }
        if (!BlockInteractionService.canInteract(bot, pos, STATION_REACH_SQ)) {
            return 0;
        }
        List<Candidate> giveaways = FuelOffloadPolicy.giveaways(
                snapshotFuelCandidates(bot, world), DEFAULT_PLANK_RESERVE);
        int moved = 0;
        var inv = bot.getInventory();
        for (Candidate c : giveaways) {
            ItemStack fuel = furnace.getStack(FUEL_SLOT);
            ItemStack src = inv.getStack(c.slot());
            if (src == null || src.isEmpty()) continue;

            int space;
            if (fuel == null || fuel.isEmpty()) {
                space = src.getMaxCount();
            } else if (!ItemStack.areItemsAndComponentsEqual(fuel, src)) {
                continue; // different fuel already loaded
            } else {
                space = fuel.getMaxCount() - fuel.getCount();
            }
            if (space <= 0) {
                break; // slot full
            }
            int give = Math.min(space, Math.min(c.count(), src.getCount()));
            if (give <= 0) continue;

            if (fuel == null || fuel.isEmpty()) {
                ItemStack copy = src.copy();
                copy.setCount(give);
                furnace.setStack(FUEL_SLOT, copy);
            } else {
                fuel.increment(give);
                furnace.setStack(FUEL_SLOT, fuel);
            }
            src.decrement(give);
            if (src.isEmpty()) {
                inv.setStack(c.slot(), ItemStack.EMPTY);
            }
            moved += give;
        }
        if (moved > 0) {
            furnace.markDirty();
        }
        return moved;
    }

    // --- movement --------------------------------------------------------------

    private static boolean walkToFurnace(ServerCommandSource source,
                                         ServerPlayerEntity bot,
                                         ServerWorld world,
                                         BlockPos furnacePos) {
        if (bot.getBlockPos().getSquaredDistance(furnacePos) <= STATION_REACH_SQ) {
            return true;
        }
        BlockPos approach = SmeltingService.approachFor(world, furnacePos);
        if (approach == null) {
            return false;
        }
        boolean allowTeleport = SkillPreferences.teleportDuringSkills(bot);
        try {
            MovementService.MovementPlan plan = new MovementService.MovementPlan(
                    MovementService.Mode.DIRECT,
                    approach, approach, null, null, bot.getHorizontalFacing());
            MovementService.MovementResult res = MovementService.execute(
                    bot.getCommandSource(), bot, plan, allowTeleport, true);
            if (res.success()) {
                return true;
            }
        } catch (Exception e) {
            LOGGER.warn("[furnace-offload] movement failed for {}: {}",
                    bot.getName().getString(), e.getMessage());
        }
        return bot.getBlockPos().getSquaredDistance(furnacePos) <= STATION_REACH_SQ;
    }

    private static <T> T callOnServer(MinecraftServer server, java.util.function.Supplier<T> task, T fallback) {
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
            return future.get(SERVER_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }
}
