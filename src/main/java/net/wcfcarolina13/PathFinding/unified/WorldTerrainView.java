package net.wcfcarolina13.PathFinding.unified;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.wcfcarolina13.GameAI.services.BotCampfireAvoidanceService;
import net.wcfcarolina13.GameAI.services.BotHazardService;
import net.wcfcarolina13.GameAI.services.BotTerritoryAuthorizationService;
import net.wcfcarolina13.GameAI.services.LockableBlockService;
import net.wcfcarolina13.GameAI.services.ObstructionMinePolicy;
import net.wcfcarolina13.GameAI.services.ProtectedStructureBlockHelper;
import net.wcfcarolina13.GameAI.services.ProtectedZoneService;
import net.wcfcarolina13.GameAI.services.WalkablePartialBlocks;
import net.wcfcarolina13.GameAI.services.construction.ConstructionProtectionService;
import net.wcfcarolina13.GameAI.services.navigation.NavHazardCache;
import net.wcfcarolina13.GameAI.services.navigation.PassageAnchorService;
import net.wcfcarolina13.GameAI.skills.support.TreeDetector;
import net.wcfcarolina13.PathFinding.WadeableWater;

/**
 * The production {@link TerrainView} over a {@link ServerWorld} (1.1.242 slice B, design section 2.1).
 * One view per search, confined to the thread that builds it (never shared: Baritone #5136). Every
 * cell is read once and memoized, so {@link #props} is deterministic for the view's life.
 *
 * <p>Semantics are the old finders' own: passability is {@code BaritoneStylePathFinder.isPassable}
 * (deadly blocks refused, wooden doors and trapdoors passable unless locked, iron ones only open, fence
 * gates never, fluids never, air or {@link WalkablePartialBlocks#isPathable}), a floor is an opaque
 * non-air block ({@code isSolidBlock}), wading is {@link WadeableWater}, the campfire berth is PF's
 * {@code CampfireMemo} (exposed campfire, x/z +-2, y +-1, section palette check first).</p>
 *
 * <p>Chunk reads never force-load: an unloaded chunk reads as 0 (no LOADED bit). On the server thread
 * the chunk comes from {@code getWorldChunk} (no load, no hop). Off the server thread the view checks
 * {@code isChunkLoaded} and then asks the chunk manager with {@code create=false}, which, like the old
 * finders' {@code world.getChunk}, hops to the server thread once per chunk (javap-verified on 1.21.11:
 * {@code ServerChunkManager.getChunk} joins a main-thread task off-thread; {@code getWorldChunk} returns
 * null off-thread). Each chunk is fetched once per view, not once per 4-slot cache miss.</p>
 *
 * <p>Mutation bits are computed only when the view was built for a search that may break or place:
 * PROTECTED ({@link ProtectedZoneService#isProtected} with the bot owner, strict; the territory claim
 * through {@link BotTerritoryAuthorizationService#authorizeBlockMutation}; a construction-protected
 * cell), NEVER_BREAK and PLACEABLE. NEVER_BREAK is {@link ProtectedStructureBlockHelper#isNeverBreakBlock},
 * any block entity and any door always, and, on a view that may break, every refusal of the executor's
 * {@code MovementService.plannedStepTrimAllowed} that reads only the block state or the terrain
 * (review C2, 1.1.242): logs, planks, wool, fences, walls, fence gates, glass-like, dirt paths, beds,
 * shulker boxes, player-placed (persistent) leaves, hardness below 0 or 12 and up, and any cell within
 * {@link TreeDetector#isNearHumanBlocks}{@code (pos, 6)} of a human block (an unloaded cell in that
 * window counts as one). Left to the executor, which refuses at execution and records a failed route:
 * {@code MiningHazardDetector} (needs the live bot), reach, the per-block mine cooldowns and remembered
 * failed routes (runtime memory), the painting/redstone neighbour scan of {@code isNeverBreakAt}, and the
 * executor's held-tool timing (the view prices the best hotbar tool).</p>
 */
public final class WorldTerrainView implements TerrainView {

    private static final int BERTH_XZ = 2;
    private static final int BERTH_Y = 1;
    private static final int UNSET = Integer.MIN_VALUE;

    /**
     * What a search reads from the bot, copied once before the search (design section 8): never read
     * live during the search. Immutable after {@link #of}; safe to hand to the PathCompare thread.
     *
     * @param territoryBot  the bot, only for {@link BotTerritoryAuthorizationService#authorizeBlockMutation}
     *                      (it reads the owner from it); null skips the territory claim check
     * @param hotbar        copies of the 9 hotbar stacks (the miner selects from the hotbar)
     * @param canSprint     food above 6
     * @param scaffoldCount plain DIRT anywhere in the inventory (Bradley's ruling: the stair is dirt only)
     */
    public record BotSnapshot(UUID botUuid, UUID ownerUuid, ServerPlayerEntity territoryBot, List<ItemStack> hotbar,
                              boolean canSprint, int scaffoldCount) {

        public static final BotSnapshot NONE = new BotSnapshot(null, null, null, List.of(), true, 0);

        public BotSnapshot {
            hotbar = hotbar == null ? List.of() : List.copyOf(hotbar);
        }

        /** Reads the bot's inventory and hunger once (the old finders' callers already read these off-thread). */
        public static BotSnapshot of(ServerPlayerEntity bot) {
            if (bot == null) {
                return NONE;
            }
            var inventory = bot.getInventory();
            List<ItemStack> hotbar = new ArrayList<>(9);
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (!stack.isEmpty()) {
                    hotbar.add(stack.copy());
                }
            }
            int dirt = 0;
            for (int slot = 0; slot < inventory.size(); slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (!stack.isEmpty() && stack.isOf(Items.DIRT)) {
                    dirt += stack.getCount();
                }
            }
            boolean sprint = bot.getHungerManager().getFoodLevel() > 6;
            return new BotSnapshot(bot.getUuid(), BotTerritoryAuthorizationService.resolveBotOwnerUuid(bot), bot,
                    hotbar, sprint, dirt);
        }
    }

    private final ServerWorld world;
    private final BotSnapshot bot;
    private final boolean mutationBreak;
    private final boolean mutationPlace;
    private final boolean onServerThread;
    private final int minY;
    private final int maxY;

    private final BlockPos.Mutable cursor = new BlockPos.Mutable();
    private final Long2ObjectOpenHashMap<WorldChunk> chunks = new Long2ObjectOpenHashMap<>();
    private long lastChunkKey = Long.MIN_VALUE;
    private WorldChunk lastChunk;
    private final Long2IntOpenHashMap propsMemo = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap breakMemo = new Long2IntOpenHashMap();
    private final Long2DoubleOpenHashMap hazardMemo = new Long2DoubleOpenHashMap();
    private final Long2DoubleOpenHashMap anchorMemo = new Long2DoubleOpenHashMap();
    private final LongOpenHashSet scannedSections = new LongOpenHashSet();
    private final LongOpenHashSet berthCells = new LongOpenHashSet();
    /** Per chunk section: 1 no human block, 2 has one, 3 unloaded. */
    private final Long2ByteOpenHashMap humanSections = new Long2ByteOpenHashMap();

    /**
     * @param allowBreak compute PROTECTED / NEVER_BREAK for solid cells (the search may break)
     * @param allowPlace compute PROTECTED / PLACEABLE for open cells (the search may place)
     */
    public WorldTerrainView(ServerWorld world, BotSnapshot bot, boolean allowBreak, boolean allowPlace) {
        if (world == null) {
            throw new IllegalArgumentException("world");
        }
        this.world = world;
        this.bot = bot == null ? BotSnapshot.NONE : bot;
        this.mutationBreak = allowBreak;
        this.mutationPlace = allowPlace;
        MinecraftServer server = world.getServer();
        this.onServerThread = server != null && server.isOnThread();
        this.minY = world.getBottomY();
        this.maxY = world.getTopYInclusive();
        propsMemo.defaultReturnValue(UNSET);
        breakMemo.defaultReturnValue(UNSET);
        hazardMemo.defaultReturnValue(Double.NaN);
        anchorMemo.defaultReturnValue(Double.NaN);
    }

    /** Same world and snapshot, fresh memos: for a re-check on another thread (PathCompare, CostVerifier). */
    public WorldTerrainView fresh() {
        return new WorldTerrainView(world, bot, mutationBreak, mutationPlace);
    }

    public ServerWorld world() {
        return world;
    }

    public BotSnapshot snapshot() {
        return bot;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    // ---------------------------------------------------------------- chunk access

    private WorldChunk chunk(int cx, int cz) {
        long key = ChunkPos.toLong(cx, cz);
        if (key == lastChunkKey) {
            return lastChunk;
        }
        WorldChunk c;
        if (chunks.containsKey(key)) {
            c = chunks.get(key);
        } else {
            c = loadChunk(cx, cz);
            chunks.put(key, c);
        }
        lastChunkKey = key;
        lastChunk = c;
        return c;
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

    /** The block state, or null when the cell is outside the world height or its chunk is not loaded. */
    private BlockState state(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return null;
        }
        WorldChunk c = chunk(x >> 4, z >> 4);
        if (c == null) {
            return null;
        }
        return c.getBlockState(cursor.set(x, y, z));
    }

    private static boolean isSolid(BlockState s) {
        return s != null && !s.isAir() && s.isOpaque();
    }

    // ---------------------------------------------------------------- TerrainView

    @Override
    public int props(int x, int y, int z) {
        long key = CellPos.pack(x, y, z);
        int p = propsMemo.get(key);
        if (p == UNSET) {
            p = computeProps(x, y, z);
            propsMemo.put(key, p);
        }
        return p;
    }

    private int computeProps(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s == null) {
            return 0;
        }
        int p = CellProps.LOADED;
        boolean air = s.isAir();
        FluidState fluid = s.getFluidState();
        BlockPos pos = new BlockPos(x, y, z);

        if (isSolid(s)) {
            p |= CellProps.SOLID_FLOOR;
        }
        if (fluid.isIn(FluidTags.LAVA)) {
            p |= CellProps.LAVA | CellProps.HAZARD;
        }
        boolean deadly = !air && BotHazardService.isDeadlyBlock(s);
        if (deadly) {
            p |= CellProps.HAZARD;
        }
        if (s.getBlock() instanceof FallingBlock) {
            p |= CellProps.GRAVITY;
        }
        if (s.getBlock() instanceof SlabBlock && s.contains(SlabBlock.TYPE) && s.get(SlabBlock.TYPE) == SlabType.BOTTOM) {
            p |= CellProps.SLAB_TOP;
        }

        boolean locked = false;
        if (s.getBlock() instanceof DoorBlock || s.getBlock() instanceof TrapdoorBlock
                || s.getBlock() instanceof FenceGateBlock) {
            locked = LockableBlockService.isLocked(world, pos);
            if (locked) {
                p |= CellProps.LOCKED_DOOR;
            }
        }

        boolean passable = !deadly && !locked && isPassable(s, pos);
        if (passable) {
            p |= CellProps.PASSABLE_BODY;
            // A collision partial over water (a lily pad) is not a head a wading bot fits under;
            // slice A reads "SOLID_FLOOR on a passable head" as !headFitsOverWater.
            if (!WadeableWater.headFitsOverWater(s, world, pos)) {
                BlockState below = state(x, y - 1, z);
                if (below != null && below.getFluidState().isIn(FluidTags.WATER)) {
                    p |= CellProps.SOLID_FLOOR;
                }
            }
        } else if (!locked && WadeableWater.isWadeable(s, world, pos)) {
            p |= isSolid(state(x, y - 1, z)) ? CellProps.WATER_WADE : CellProps.WATER_DEEP;
        }

        if (!air && fluid.isEmpty() && (s.getBlock() instanceof DoorBlock
                || s.hasBlockEntity() || ProtectedStructureBlockHelper.isNeverBreakBlock(s))) {
            p |= CellProps.NEVER_BREAK;
        }

        boolean breakCandidate = mutationBreak && !air && fluid.isEmpty() && !passable;
        if (breakCandidate && (p & CellProps.NEVER_BREAK) == 0
                && (refusedForPath(s, pos) || nearHumanBlocks(x, y, z))) {
            p |= CellProps.NEVER_BREAK;
        }
        boolean placeCandidate = mutationPlace && (air || s.isReplaceable()) && fluid.isEmpty()
                && isSolid(state(x, y - 1, z));
        if (breakCandidate || placeCandidate) {
            boolean tree = s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.LEAVES);
            if (isProtectedCell(pos, tree)) {
                p |= CellProps.PROTECTED;
            } else if (placeCandidate) {
                p |= CellProps.PLACEABLE;
            }
        }

        if (inCampfireBerth(x, y, z)) {
            p |= CellProps.HAZARD;
        }
        return p;
    }

    /**
     * The pure state refusals of {@code MovementService.plannedStepTrimAllowed} (keep in step with it):
     * build materials and trunks, fences and walls, glass-like, dirt paths, beds, shulker boxes,
     * player-placed leaves, and blocks too hard (hardness below 0 or 12 and up).
     */
    private boolean refusedForPath(BlockState s, BlockPos pos) {
        if (s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.PLANKS) || s.isIn(BlockTags.WOOL)
                || s.isIn(BlockTags.FENCES) || s.isIn(BlockTags.WALLS) || s.isIn(BlockTags.FENCE_GATES)
                || s.isIn(BlockTags.BEDS) || s.isIn(BlockTags.SHULKER_BOXES)
                || s.isOf(Blocks.DIRT_PATH) || ProtectedStructureBlockHelper.isProtectedGlassLike(s)) {
            return true;
        }
        if (s.isIn(BlockTags.LEAVES)) {
            return s.contains(LeavesBlock.PERSISTENT) && s.get(LeavesBlock.PERSISTENT);
        }
        float hardness = s.getHardness(world, pos);
        return hardness < 0.0F || hardness >= 12.0F;
    }

    /**
     * {@link TreeDetector#isNearHumanBlocks}{@code (pos, 6)} over this view's chunk cache (the world read
     * would hop to the server thread per cell off-thread): x/z +-{@link TreeDetector#humanScanRadius},
     * y -1..+8. Sections with no human block in their palette are skipped; an unloaded cell in the
     * window counts as near (refuse rather than force a load). Memoized with the cell's props.
     */
    private boolean nearHumanBlocks(int x, int y, int z) {
        int r = TreeDetector.humanScanRadius();
        int y0 = y - 1;
        int y1 = y + 8;
        boolean any = false;
        for (int sx = (x - r) >> 4; sx <= (x + r) >> 4; sx++) {
            for (int sz = (z - r) >> 4; sz <= (z + r) >> 4; sz++) {
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    byte h = humanSection(sx, sy, sz);
                    if (h == 3) {
                        return true;
                    }
                    any |= h == 2;
                }
            }
        }
        if (!any) {
            return false;
        }
        for (int cy = y0; cy <= y1; cy++) {
            if (cy < minY || cy > maxY) {
                continue;
            }
            for (int cz = z - r; cz <= z + r; cz++) {
                for (int cx = x - r; cx <= x + r; cx++) {
                    BlockState b = state(cx, cy, cz);
                    if (b == null || TreeDetector.isHumanBlock(b)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private byte humanSection(int sx, int sy, int sz) {
        long key = ChunkSectionPos.asLong(sx, sy, sz);
        byte h = humanSections.get(key);
        if (h != 0) {
            return h;
        }
        WorldChunk chunk = chunk(sx, sz);
        if (chunk == null) {
            h = 3;
        } else {
            int index = chunk.getSectionIndex(sy << 4);
            ChunkSection section = index >= 0 && index < chunk.countVerticalSections() ? chunk.getSection(index) : null;
            h = section != null && !section.isEmpty() && section.hasAny(TreeDetector::isHumanBlock) ? (byte) 2 : (byte) 1;
        }
        humanSections.put(key, h);
        return h;
    }

    /** {@code BaritoneStylePathFinder.isPassable} minus the deadly and lock checks done by the caller. */
    private boolean isPassable(BlockState s, BlockPos pos) {
        if (s.isAir()) {
            return true;
        }
        if (s.getBlock() instanceof DoorBlock) {
            return !s.isOf(Blocks.IRON_DOOR) || s.get(DoorBlock.OPEN);
        }
        if (s.getBlock() instanceof TrapdoorBlock) {
            return !s.isOf(Blocks.IRON_TRAPDOOR) || s.getCollisionShape(world, pos).isEmpty();
        }
        // Fence gates are never passable here (pens): handled reactively by the follow planner.
        if (s.getBlock() instanceof FenceGateBlock) {
            return false;
        }
        if (!s.getFluidState().isEmpty()) {
            return false;
        }
        return WalkablePartialBlocks.isPathable(s, world, pos);
    }

    /** Strict protection plus the bot's territory claims and its construction plan, as {@code placeOk} reads them. */
    private boolean isProtectedCell(BlockPos pos, boolean treeBlock) {
        if (ProtectedZoneService.isProtected(pos, world, bot.ownerUuid())) {
            return true;
        }
        if (bot.botUuid() != null && ConstructionProtectionService.protectionReason(bot.botUuid(), pos) != null) {
            return true;
        }
        return bot.territoryBot() != null
                && !BotTerritoryAuthorizationService.authorizeBlockMutation(bot.territoryBot(), world, pos, treeBlock)
                .allowed();
    }

    @Override
    public int breakTicks(int x, int y, int z) {
        long key = CellPos.pack(x, y, z);
        int t = breakMemo.get(key);
        if (t == UNSET) {
            t = computeBreakTicks(x, y, z);
            breakMemo.put(key, t);
        }
        return t;
    }

    /**
     * {@code MovementService.bestHotbarGroundBreakTicks} over the snapshotted hotbar: the fastest of the
     * bare hand and every hotbar stack, on the ground, with vanilla's harvest divisor.
     */
    private int computeBreakTicks(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s == null || s.isAir() || !s.getFluidState().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        BlockPos pos = new BlockPos(x, y, z);
        float hardness = s.getHardness(world, pos);
        boolean requiresTool = s.isToolRequired();
        int best = ObstructionMinePolicy.groundBreakTicks(1.0F, hardness,
                ObstructionMinePolicy.canHarvest(requiresTool, false));
        for (ItemStack stack : bot.hotbar()) {
            int ticks = ObstructionMinePolicy.groundBreakTicks(stack.getMiningSpeedMultiplier(s), hardness,
                    ObstructionMinePolicy.canHarvest(requiresTool, stack.isSuitableFor(s)));
            best = Math.min(best, ticks);
        }
        return best;
    }

    // ---------------------------------------------------------------- cost functions for SearchContext

    /** {@code NavHazardCache.penaltyFor} in ticks (one old unit = {@link ActionCosts#STEP_TICKS}), memoized. */
    public double hazardPenaltyTicks(long cell) {
        double v = hazardMemo.get(cell);
        if (Double.isNaN(v)) {
            v = NavHazardCache.penaltyFor(world, CellPos.x(cell), CellPos.y(cell), CellPos.z(cell)) * ActionCosts.STEP_TICKS;
            hazardMemo.put(cell, v);
        }
        return v;
    }

    /** {@code PassageAnchorService.bonusFor} in ticks, memoized. MoveCosts clamps it to the penalty part. */
    public double anchorBonusTicks(long cell) {
        double v = anchorMemo.get(cell);
        if (Double.isNaN(v)) {
            v = PassageAnchorService.bonusFor(world, CellPos.x(cell), CellPos.y(cell), CellPos.z(cell)) * ActionCosts.STEP_TICKS;
            anchorMemo.put(cell, v);
        }
        return v;
    }

    /** The floor block's name for {@code PathNode.blockName} (callers log it). "unloaded" off the map. */
    public String blockName(int x, int y, int z) {
        BlockState s = state(x, y, z);
        if (s == null) {
            return "unloaded";
        }
        return s.getBlock().getName().getString().toLowerCase();
    }

    // ---------------------------------------------------------------- campfire berth (PF CampfireMemo)

    private boolean inCampfireBerth(int x, int y, int z) {
        for (int sx = (x - BERTH_XZ) >> 4; sx <= (x + BERTH_XZ) >> 4; sx++) {
            for (int sz = (z - BERTH_XZ) >> 4; sz <= (z + BERTH_XZ) >> 4; sz++) {
                for (int sy = (y - BERTH_Y) >> 4; sy <= (y + BERTH_Y) >> 4; sy++) {
                    scanSection(sx, sy, sz);
                }
            }
        }
        return !berthCells.isEmpty() && berthCells.contains(CellPos.pack(x, y, z));
    }

    private void scanSection(int sx, int sy, int sz) {
        long key = ChunkSectionPos.asLong(sx, sy, sz);
        if (!scannedSections.add(key)) {
            return;
        }
        WorldChunk chunk = chunk(sx, sz);
        if (chunk == null) {
            return;
        }
        int index = chunk.getSectionIndex(sy << 4);
        if (index < 0 || index >= chunk.countVerticalSections()) {
            return;
        }
        ChunkSection section = chunk.getSection(index);
        if (section == null || section.isEmpty() || !section.hasAny(WorldTerrainView::isCampfire)) {
            return;
        }
        int baseX = sx << 4;
        int baseY = sy << 4;
        int baseZ = sz << 4;
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    if (!isCampfire(section.getBlockState(lx, ly, lz))) {
                        continue;
                    }
                    BlockPos campfire = new BlockPos(baseX + lx, baseY + ly, baseZ + lz);
                    if (!BotCampfireAvoidanceService.isCampfireExposed(world, campfire)) {
                        continue;
                    }
                    for (int dx = -BERTH_XZ; dx <= BERTH_XZ; dx++) {
                        for (int dy = -BERTH_Y; dy <= BERTH_Y; dy++) {
                            for (int dz = -BERTH_XZ; dz <= BERTH_XZ; dz++) {
                                berthCells.add(CellPos.pack(campfire.getX() + dx, campfire.getY() + dy,
                                        campfire.getZ() + dz));
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean isCampfire(BlockState state) {
        return state != null && (state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE));
    }
}
