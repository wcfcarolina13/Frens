package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which blocks inside a SERVER-owned base (the Spawn base) a bot placed as its own woodcut
 * scaffold (1.1.268 ruling). Only these may be broken there again by that same bot, through the
 * claim gate's {@code OWN_SCAFFOLD_BREAK} kind, which re-derives ownership from here itself.
 *
 * <p>Security: a position is registered only by {@code BotActions.tryPlaceBlockAt(..., ownScaffold)}
 * on an accepted vanilla placement of a scaffold material that needed the Spawn-base exemption,
 * so the registry only ever holds Spawn-base cells this bot really placed. Ownership is per bot
 * UUID and per dimension; the store is replaced (never merged) when the server changes, so an
 * entry from another save can never open a block in this one.
 *
 * <p>Thread-safe: callable from worker threads and the server thread.
 */
public final class OwnedScaffoldRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("spawn-scaffold");

    /** Bases are Overworld-only, so this is the only dimension that is ever persisted. */
    public static final String OVERWORLD_ID = "minecraft:overworld";

    private static final OwnedScaffoldLedger LEDGER = new OwnedScaffoldLedger();
    private static final Object LOAD_LOCK = new Object();
    /** identityHashCode of the server the ledger was loaded for; 0 = none. */
    private static volatile int loadedServerIdentity;

    private OwnedScaffoldRegistry() {
    }

    public static String dimensionId(ServerWorld world) {
        return world == null ? null : world.getRegistryKey().getValue().toString();
    }

    /** True when the live block state is a scaffold material ({@link ScaffoldOwnershipPolicy}). */
    public static boolean isScaffoldMaterial(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        return ScaffoldOwnershipPolicy.isScaffoldMaterial(Registries.BLOCK.getId(state.getBlock()).toString());
    }

    /**
     * Server-start hook (also run lazily on the first access for a new server): replaces the
     * ledger with this save's persisted own scaffold. Idempotent per server instance.
     */
    public static void loadFor(MinecraftServer server) {
        if (server == null) {
            return;
        }
        int identity = System.identityHashCode(server);
        if (identity == loadedServerIdentity) {
            return;
        }
        synchronized (LOAD_LOCK) {
            if (identity == loadedServerIdentity) {
                return;
            }
            Map<UUID, List<Long>> saved = loadPersisted(server);
            LEDGER.replaceAll(OVERWORLD_ID, saved);
            loadedServerIdentity = identity;
            int total = 0;
            for (List<Long> positions : saved.values()) {
                total += positions == null ? 0 : positions.size();
            }
            if (total > 0) {
                LOGGER.info("[spawn-scaffold] loaded {} remembered own scaffold position(s) for {} bot(s)",
                        total, saved.size());
            }
        }
    }

    private static void ensureLoaded(ServerWorld world) {
        if (world != null) {
            loadFor(world.getServer());
        }
    }

    /** Called only from the flagged placement success in BotActions; never from adoption paths. */
    public static boolean register(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) {
            return false;
        }
        ensureLoaded(world);
        String dim = dimensionId(world);
        boolean added = LEDGER.register(bot.getUuid(), dim, pos.asLong());
        if (added) {
            persist(world, bot.getUuid(), dim);
        }
        return added;
    }

    public static boolean isOwned(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) {
            return false;
        }
        ensureLoaded(world);
        return LEDGER.isOwned(bot.getUuid(), dimensionId(world), pos.asLong());
    }

    public static boolean forget(ServerPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (bot == null || world == null || pos == null) {
            return false;
        }
        ensureLoaded(world);
        String dim = dimensionId(world);
        boolean removed = LEDGER.forget(bot.getUuid(), dim, pos.asLong());
        if (removed) {
            persist(world, bot.getUuid(), dim);
        }
        return removed;
    }

    /** Snapshot of this bot's own scaffold positions in {@code world}. */
    public static List<BlockPos> ownedPositions(ServerPlayerEntity bot, ServerWorld world) {
        if (bot == null || world == null) {
            return List.of();
        }
        ensureLoaded(world);
        List<BlockPos> out = new ArrayList<>();
        for (Long packed : LEDGER.positions(bot.getUuid(), dimensionId(world))) {
            if (packed != null) {
                out.add(BlockPos.fromLong(packed));
            }
        }
        return out;
    }

    // ── Persistence (BotHomeService WorldData, Overworld only) ───────────────

    private static Map<UUID, List<Long>> loadPersisted(MinecraftServer server) {
        return Map.of();
    }

    private static void persist(ServerWorld world, UUID bot, String dim) {
        // Wired to BotHomeService in the persistence phase.
    }
}
