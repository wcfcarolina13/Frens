package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.PlayerUtils.MiningTool;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Coalesces placement retries onto one timed clutter break per bot. */
public final class PlacementClutterClearingService {
    private record Pending(BlockPos target, CompletableFuture<String> result) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private PlacementClutterClearingService() {}

    /** Server thread only. A different target reports clearing-busy until the current break finishes. */
    public static CompletableFuture<String> start(ServerPlayerEntity bot, BlockPos target) {
        UUID id = bot.getUuid();
        Pending current = PENDING.get(id);
        if (current != null && !current.result().isDone()) {
            return current.target().equals(target) ? current.result()
                    : CompletableFuture.completedFuture("clearing-busy");
        }
        // Placement already authorizes this cell; mapped village hulls must not veto clutter clearing.
        CompletableFuture<String> result = MiningTool.mineBlock(bot, target, true, false);
        Pending pending = new Pending(target.toImmutable(), result);
        PENDING.put(id, pending);
        result.whenComplete((message, error) -> PENDING.remove(id, pending));
        return result;
    }
}
