package net.wcfcarolina13.PathFinding.unified;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.PathFinding.PathFinder;
import net.wcfcarolina13.PathFinding.Segment;

/**
 * {@link PathPlan} to today's executor types (design section 6). The decisions (jump flags, the
 * walk-run / action split) are pure in {@link PlanUnits}; this class only builds {@link PathFinder.PathNode}
 * and {@link Segment}, which carry {@link BlockPos}.
 *
 * <ul>
 *   <li>{@link #toPathNodes(PathPlan, SearchContext)}: one node per move's landing plus the start,
 *       {@code walkable = true}, {@code jump} from {@link PlanUnits#needsJump} (slab-aware through the
 *       terrain), {@code blockName} the floor block when the terrain is a {@link WorldTerrainView}.
 *       {@code PathFinder.simplifyPath} keeps working on it.</li>
 *   <li>{@link #toSegments(ExecUnit.WalkRun, TerrainView, boolean)}: today's
 *       {@code PathFinder.convertPathToSegments} over the run's nodes (6-block Manhattan split, jump and
 *       axis breaks), so {@code walkSegment} walks a run unchanged.</li>
 *   <li>{@link #toUnits(PathPlan, SearchContext)}: {@link PlanUnits#units}.</li>
 * </ul>
 */
public final class PlanAdapter {

    private PlanAdapter() {
    }

    public static BlockPos pos(PlanUnits.PlanNode node) {
        return new BlockPos(node.x(), node.y(), node.z());
    }

    public static BlockPos pos(long packed) {
        return BlockPos.fromLong(packed);
    }

    /** Every node of the plan from the context's start. Empty on NO_PATH. */
    public static List<PathFinder.PathNode> toPathNodes(PathPlan plan, SearchContext ctx) {
        return toPathNodes(PlanUnits.nodes(plan, ctx.startX(), ctx.startY(), ctx.startZ(), ctx.terrain()),
                ctx.terrain());
    }

    public static List<PathFinder.PathNode> toPathNodes(List<PlanUnits.PlanNode> nodes, TerrainView terrain) {
        List<PathFinder.PathNode> out = new ArrayList<>(nodes.size());
        WorldTerrainView world = terrain instanceof WorldTerrainView w ? w : null;
        for (PlanUnits.PlanNode n : nodes) {
            String floor = world != null ? world.blockName(n.x(), n.y() - 1, n.z()) : "unknown";
            out.add(new PathFinder.PathNode(pos(n), floor, true, n.jump()));
        }
        return out;
    }

    /** The walk run as today's segment queue. */
    public static Queue<Segment> toSegments(ExecUnit.WalkRun run, TerrainView terrain, boolean sprint) {
        return PathFinder.convertPathToSegments(toPathNodes(run.nodes(), terrain), sprint);
    }

    public static List<ExecUnit> toUnits(PathPlan plan, SearchContext ctx) {
        return PlanUnits.units(plan, ctx.terrain());
    }
}
