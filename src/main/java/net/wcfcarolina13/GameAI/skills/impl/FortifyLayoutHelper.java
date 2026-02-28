package net.wcfcarolina13.GameAI.skills.impl;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.*;

import java.util.*;

/**
 * Layout query helper for {@link FortifyVillageSkill}.
 * <p>
 * Encapsulates block-satisfaction checks, material fallback lists,
 * edge ordering, and layout accounting — all pure queries with no
 * world mutations.  Shares the skill's {@code ignoredCavityPositions}
 * set via constructor reference so additions by the skill are visible
 * here automatically.
 */
final class FortifyLayoutHelper {

    // ── Material fallback lists ──────────────────────────────────

    static final List<Item> STONE_BRICK_FALLBACKS = List.of(
            Items.STONE_BRICKS, Items.COBBLESTONE, Items.STONE,
            Items.COBBLED_DEEPSLATE, Items.ANDESITE, Items.DIRT
    );
    static final List<Item> OAK_LOG_FALLBACKS = List.of(
            Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
            Items.JUNGLE_LOG, Items.COBBLESTONE, Items.DIRT
    );
    static final List<Item> CHISELED_FALLBACKS = List.of(
            Items.CHISELED_STONE_BRICKS, Items.STONE_BRICKS, Items.COBBLESTONE, Items.DIRT
    );
    static final List<Item> SLAB_FALLBACKS = List.of(
            Items.STONE_BRICK_SLAB, Items.COBBLESTONE_SLAB, Items.STONE_SLAB,
            Items.COBBLESTONE, Items.DIRT
    );
    static final List<Item> COBBLE_FALLBACKS = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DIRT
    );

    private final Set<BlockPos> ignoredCavityPositions;

    FortifyLayoutHelper(Set<BlockPos> ignoredCavityPositions) {
        this.ignoredCavityPositions = ignoredCavityPositions;
    }

    // ── Material lookup ──────────────────────────────────────────

    List<Item> buildCandidateList(Item primary) {
        if (primary == Items.STONE_BRICKS || primary == Items.STONE_BRICK_STAIRS) {
            return new ArrayList<>(STONE_BRICK_FALLBACKS);
        }
        if (primary == Items.STONE_BRICK_SLAB || primary == Items.COBBLESTONE_SLAB
                || primary == Items.STONE_SLAB) {
            return new ArrayList<>(SLAB_FALLBACKS);
        }
        if (primary == Items.CHISELED_STONE_BRICKS) {
            return new ArrayList<>(CHISELED_FALLBACKS);
        }
        if (primary == Items.OAK_LOG) {
            return new ArrayList<>(OAK_LOG_FALLBACKS);
        }
        if (primary == Items.COBBLESTONE || primary == Items.COBBLED_DEEPSLATE) {
            return new ArrayList<>(COBBLE_FALLBACKS);
        }
        List<Item> list = new ArrayList<>();
        list.add(primary);
        list.add(Items.COBBLESTONE);
        list.add(Items.DIRT);
        return list;
    }

    // ── Block satisfaction ────────────────────────────────────────

    static boolean isMoatRelatedType(WallBlockType type) {
        return type == WallBlockType.MOAT_DIG
                || type == WallBlockType.MOAT_FLOOR
                || type == WallBlockType.MOAT_INNER_FACE
                || type == WallBlockType.MOAT_OVERHANG
                || type == WallBlockType.EXTERIOR_CLEAR;
    }

    boolean isActiveFortifyBlock(ProceduralWallBlock block) {
        if (block == null) {
            return false;
        }
        // Moat blocks are handled by the separate FortifyMoatSkill
        if (isMoatRelatedType(block.type())) {
            return false;
        }
        return true;
    }

    boolean isPlannedBlockSatisfied(ProceduralWallBlock planned, BlockState current) {
        if (planned == null || current == null) {
            return false;
        }
        if (ignoredCavityPositions.contains(planned.worldPos())) {
            return true;
        }
        BlockState desired = planned.state();
        if (current.equals(desired)) {
            return true;
        }
        if (current.isAir() || current.isReplaceable()) {
            return false;
        }
        if (desired.isAir()) {
            return current.isAir();
        }

        // Foundation/tower-base blocks: any solid non-air block satisfies the requirement.
        // The terrain (grass, dirt, stone) already provides the structural base the wall needs.
        if (planned.type() == WallBlockType.FOUNDATION || planned.type() == WallBlockType.TOWER_BASE) {
            return true; // existing solid block serves as foundation
        }

        Item desiredItem = desired.getBlock().asItem();
        Item currentItem = current.getBlock().asItem();
        if (currentItem == Items.AIR) {
            return false;
        }
        List<Item> candidates = buildCandidateList(desiredItem);
        return candidates.contains(currentItem);
    }

    // ── Layout accounting ────────────────────────────────────────

    /** Compute per-edge planned block counts from a layout. */
    Map<Integer, Integer> computeEdgePlannedCounts(FortificationLayout layout) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (ProceduralWallBlock block : layout.allBlocks()) {
            if (!isActiveFortifyBlock(block)) {
                continue;
            }
            counts.merge(block.edgeIndex(), 1, Integer::sum);
        }
        return counts;
    }

    /** Count how many planned blocks are satisfied by desired/fallback material state. */
    int countPresentBlocks(ServerWorld world, List<ProceduralWallBlock> allBlocks) {
        int count = 0;
        for (ProceduralWallBlock block : allBlocks) {
            if (!isActiveFortifyBlock(block)) {
                continue;
            }
            BlockState current = world.getBlockState(block.worldPos());
            if (isPlannedBlockSatisfied(block, current)) {
                count++;
            }
        }
        return count;
    }

    // ── Edge ordering ────────────────────────────────────────────

    int chooseEdgeStartIndex(ServerPlayerEntity bot, FortificationLayout layout,
                             Set<Integer> completedEdges, int savedEdgeIndex) {
        int totalEdges = layout.edges().size();
        if (totalEdges <= 0) {
            return 0;
        }

        if (savedEdgeIndex >= 0 && savedEdgeIndex < totalEdges && !completedEdges.contains(savedEdgeIndex)) {
            return savedEdgeIndex;
        }

        if (savedEdgeIndex >= 0 && savedEdgeIndex < totalEdges) {
            for (int i = 1; i <= totalEdges; i++) {
                int idx = (savedEdgeIndex + i) % totalEdges;
                if (!completedEdges.contains(idx)) {
                    return idx;
                }
            }
        }

        BlockPos botPos = bot.getBlockPos();
        int nearest = -1;
        double nearestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < totalEdges; i++) {
            if (completedEdges.contains(i)) {
                continue;
            }
            WallEdge edge = layout.edges().get(i);
            double midX = (edge.start().x() + edge.end().x()) / 2.0;
            double midZ = (edge.start().z() + edge.end().z()) / 2.0;
            double dx = botPos.getX() - midX;
            double dz = botPos.getZ() - midZ;
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = i;
            }
        }
        return nearest >= 0 ? nearest : 0;
    }

    List<Integer> orderedRemainingEdges(FortificationLayout layout,
                                        Set<Integer> completedEdges,
                                        int startEdgeIndex) {
        int totalEdges = layout.edges().size();
        if (totalEdges <= 0) {
            return List.of();
        }
        int start = Math.floorMod(startEdgeIndex, totalEdges);
        List<Integer> order = new ArrayList<>(totalEdges);
        for (int i = 0; i < totalEdges; i++) {
            int idx = (start + i) % totalEdges;
            if (!completedEdges.contains(idx)) {
                order.add(idx);
            }
        }
        return order;
    }

    /**
     * Greedy nearest-neighbor edge selection: pick the remaining edge whose midpoint
     * is closest to the bot's current position.  Returns -1 if no edges remain.
     */
    int pickNearestRemainingEdge(ServerPlayerEntity bot, FortificationLayout layout,
                                 Set<Integer> visited) {
        int totalEdges = layout.edges().size();
        BlockPos botPos = bot.getBlockPos();
        int nearest = -1;
        double nearestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < totalEdges; i++) {
            if (visited.contains(i)) continue;
            WallEdge edge = layout.edges().get(i);
            double midX = (edge.start().x() + edge.end().x()) / 2.0;
            double midZ = (edge.start().z() + edge.end().z()) / 2.0;
            double dx = botPos.getX() - midX;
            double dz = botPos.getZ() - midZ;
            double distSq = dx * dx + dz * dz;
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = i;
            }
        }
        return nearest;
    }

    /**
     * Compute squared distance from a position to an edge/tower group used in patch mode.
     * For tower blocks (edgeIdx == -1), uses average position of the blocks.
     * For wall edges, uses the edge midpoint.
     */
    static double patchEdgeDistanceSq(BlockPos botPos, int edgeIdx,
                                      List<ProceduralWallBlock> blocks,
                                      FortificationLayout layout) {
        if (edgeIdx == -1) {
            if (blocks.isEmpty()) return Double.MAX_VALUE;
            double avgX = 0, avgZ = 0;
            for (ProceduralWallBlock b : blocks) {
                avgX += b.worldPos().getX();
                avgZ += b.worldPos().getZ();
            }
            avgX /= blocks.size();
            avgZ /= blocks.size();
            double dx = botPos.getX() - avgX;
            double dz = botPos.getZ() - avgZ;
            return dx * dx + dz * dz;
        }
        if (edgeIdx >= 0 && edgeIdx < layout.edges().size()) {
            WallEdge e = layout.edges().get(edgeIdx);
            double midX = (e.start().x() + e.end().x()) / 2.0;
            double midZ = (e.start().z() + e.end().z()) / 2.0;
            double dx = botPos.getX() - midX;
            double dz = botPos.getZ() - midZ;
            return dx * dx + dz * dz;
        }
        return Double.MAX_VALUE;
    }
}
