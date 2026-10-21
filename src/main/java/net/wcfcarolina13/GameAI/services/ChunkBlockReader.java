package net.wcfcarolina13.GameAI.services;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Reads block and fluid states through a per-instance chunk cache, so a scan asks the
 * chunk manager for each chunk once instead of once per cell.
 *
 * <p>Why: off the server thread, {@code ServerChunkManager.getChunk} joins a main-thread
 * future, so every {@code world.getBlockState}, {@code getFluidState} or
 * {@code isChunkLoaded} from a worker is a server round trip (~7 µs). A scan of tens of
 * thousands of cells pays that per read. Here a chunk costs at most two such calls
 * (loaded check, then fetch) and every later read of it is a plain palette lookup.
 *
 * <p>On the server thread the chunk comes from {@code getWorldChunk} (no load, no hop).
 * Off it, {@code isChunkLoaded} then {@code getChunk(FULL, false)}. Unloaded chunks are
 * never loaded and are cached as unloaded for the life of the instance.
 *
 * <p>Off-thread reads go straight to the chunk's section palettes while the server may be
 * writing them. That race is accepted, as in {@code WorldTerrainView} and
 * {@code FishingSkill.collectChestAnchors}: a read can see a state one tick stale, which
 * planning and scanning already tolerate.
 *
 * <p>Not thread-safe. Build one instance per scan or call and drop it afterwards; the
 * cache never refreshes, so a long-lived instance would keep answering from old chunks.
 */
public final class ChunkBlockReader {

    private final ServerWorld world;
    private final boolean onServerThread;
    private final int bottomY;
    private final int topYInclusive;
    /** Chunk by packed chunk pos; a present key with a null value means "not loaded". */
    private final Long2ObjectOpenHashMap<WorldChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private long lastChunkKey = Long.MIN_VALUE;
    private WorldChunk lastChunk;
    private long unloadedReadCount;

    public ChunkBlockReader(ServerWorld world) {
        this.world = world;
        MinecraftServer server = world.getServer();
        this.onServerThread = server != null && server.isOnThread();
        this.bottomY = world.getBottomY();
        this.topYInclusive = world.getTopYInclusive();
    }

    public ServerWorld world() {
        return world;
    }

    /** The loaded chunk at these chunk coordinates, or null when it is not loaded. Never loads. */
    public WorldChunk chunk(int chunkX, int chunkZ) {
        long key = ChunkPos.toLong(chunkX, chunkZ);
        if (key == lastChunkKey) {
            if (lastChunk == null) unloadedReadCount++;
            return lastChunk;
        }
        WorldChunk c;
        if (chunks.containsKey(key)) {
            c = chunks.get(key);
        } else {
            c = loadChunk(chunkX, chunkZ);
            chunks.put(key, c);
        }
        lastChunkKey = key;
        lastChunk = c;
        if (c == null) unloadedReadCount++;
        return c;
    }

    /** Counts missing-chunk reads, including cached misses; out-of-height cells do not count. */
    public long unloadedReadCount() {
        return unloadedReadCount;
    }

    private WorldChunk loadChunk(int cx, int cz) {
        if (onServerThread) {
            return world.getChunkManager().getWorldChunk(cx, cz);
        }
        if (!world.isChunkLoaded(cx, cz)) {
            return null;
        }
        Chunk c = world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
        return c instanceof WorldChunk wc ? wc : null;
    }

    /** True when the chunk holding block column (x, z) is loaded. Same answer as {@code world.isChunkLoaded(pos)}. */
    public boolean isLoaded(int x, int z) {
        return chunk(x >> 4, z >> 4) != null;
    }

    /** The block state, or null when the cell is outside the world height or its chunk is not loaded. */
    public BlockState state(int x, int y, int z) {
        if (y < bottomY || y > topYInclusive) {
            return null;
        }
        WorldChunk c = chunk(x >> 4, z >> 4);
        if (c == null) {
            return null;
        }
        return c.getBlockState(cursor.set(x, y, z));
    }

    /** See {@link #state(int, int, int)}. */
    public BlockState state(BlockPos pos) {
        return state(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The fluid state, or null when the cell is outside the world height or its chunk is not loaded. */
    public FluidState fluid(int x, int y, int z) {
        if (y < bottomY || y > topYInclusive) {
            return null;
        }
        WorldChunk c = chunk(x >> 4, z >> 4);
        if (c == null) {
            return null;
        }
        return c.getFluidState(cursor.set(x, y, z));
    }

    /** See {@link #fluid(int, int, int)}. */
    public FluidState fluid(BlockPos pos) {
        return fluid(pos.getX(), pos.getY(), pos.getZ());
    }
}
