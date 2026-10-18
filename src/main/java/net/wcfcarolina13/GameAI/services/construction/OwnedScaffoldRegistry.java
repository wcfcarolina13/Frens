package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.BotHomeService;
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
 * so the registry only ever holds Spawn-base cells this bot really placed. Each entry keeps the
 * exact placed block id (a break needs the live block to match it) and the placement time
 * (ownership expires after 24 h). Ownership is per bot UUID and per dimension; the store is replaced (never merged) when the server changes, so an
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
        return ScaffoldOwnershipPolicy.isScaffoldMaterial(blockId(state));
    }

    /** Registry id of the live block ({@code minecraft:oak_planks}); null for a null state. */
    public static String blockId(BlockState state) {
        return state == null ? null : Registries.BLOCK.getId(state.getBlock()).toString();
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
            Map<UUID, Map<Long, OwnedScaffoldLedger.Owned>> saved = loadPersisted(server);
            int dropped = LEDGER.replaceAll(OVERWORLD_ID, saved, System.currentTimeMillis());
            loadedServerIdentity = identity;
            int total = 0;
            for (Map<Long, OwnedScaffoldLedger.Owned> positions : saved.values()) {
                total += positions == null ? 0 : positions.size();
            }
            if (total > 0) {
                LOGGER.info("[spawn-scaffold] loaded {} remembered own scaffold position(s) for {} bot(s), {} expired or without a block id dropped",
                        total - dropped, saved.size(), dropped);
            }
            if (dropped > 0) {
                for (UUID bot : saved.keySet()) {
                    persist(server, bot, OVERWORLD_ID);
                }
            }
        }
    }

    private static void ensureLoaded(ServerWorld world) {
        if (world != null) {
            loadFor(world.getServer());
        }
    }

    /**
     * Called only from the flagged placement success in BotActions; never from adoption paths.
     * Stores the exact placed block id and the placement time (ownership expires after 24 h).
     */
    public static boolean register(ServerPlayerEntity bot, ServerWorld world, BlockPos pos, String placedBlockId) {
        if (bot == null || world == null || pos == null || placedBlockId == null || placedBlockId.isBlank()) {
            return false;
        }
        ensureLoaded(world);
        String dim = dimensionId(world);
        boolean added = LEDGER.register(bot.getUuid(), dim, pos.asLong(), placedBlockId, System.currentTimeMillis());
        persist(world, bot.getUuid(), dim);
        return added;
    }

    /**
     * The ownership decision before any OWN_SCAFFOLD_BREAK ({@link ScaffoldOwnershipPolicy#ownedVerdict}):
     * BREAK only when this bot owns {@code pos}, the entry has not expired, and the live block id
     * equals the stored id exactly. Every FORGET_* verdict forgets the position here.
     *
     * @param liveState the block state the caller just read at {@code pos}
     */
    public static ScaffoldOwnershipPolicy.OwnedVerdict verdict(ServerPlayerEntity bot, ServerWorld world, BlockPos pos,
                                                               BlockState liveState) {
        if (bot == null || world == null || pos == null) {
            return ScaffoldOwnershipPolicy.OwnedVerdict.NOT_OWNED;
        }
        ensureLoaded(world);
        OwnedScaffoldLedger.Owned owned = LEDGER.entry(bot.getUuid(), dimensionId(world), pos.asLong());
        if (owned == null) {
            return ScaffoldOwnershipPolicy.OwnedVerdict.NOT_OWNED;
        }
        boolean air = liveState == null || liveState.isAir();
        ScaffoldOwnershipPolicy.OwnedVerdict verdict = ScaffoldOwnershipPolicy.ownedVerdict(owned.blockId(),
                owned.placedAtMs(), System.currentTimeMillis(), air ? null : blockId(liveState), air);
        if (ScaffoldOwnershipPolicy.shouldForget(verdict)) {
            forget(bot, world, pos);
            if (verdict != ScaffoldOwnershipPolicy.OwnedVerdict.FORGET_GONE) {
                LOGGER.info("[spawn-scaffold] {} own scaffold at {} forgotten ({}): placed {}, now {}",
                        bot.getName().getString(), pos.toShortString(), verdict, owned.blockId(),
                        air ? "air" : blockId(liveState));
            }
        }
        return verdict;
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

    private static final Object PERSIST_LOCK = new Object();

    private static Map<UUID, Map<Long, OwnedScaffoldLedger.Owned>> loadPersisted(MinecraftServer server) {
        try {
            return BotHomeService.loadOwnedSpawnScaffold(server);
        } catch (Throwable t) {
            LOGGER.warn("[spawn-scaffold] could not load remembered own scaffold: {}", t.getMessage());
            return Map.of();
        }
    }

    /** Writes this bot's Overworld entries through (snapshot and save under one lock, so order holds). */
    private static void persist(ServerWorld world, UUID bot, String dim) {
        if (world != null) {
            persist(world.getServer(), bot, dim);
        }
    }

    private static void persist(MinecraftServer server, UUID bot, String dim) {
        if (!OVERWORLD_ID.equals(dim) || server == null) {
            return;
        }
        synchronized (PERSIST_LOCK) {
            BotHomeService.saveOwnedSpawnScaffold(server, bot, LEDGER.entries(bot, dim));
        }
    }
}
