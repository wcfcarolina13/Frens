package net.wcfcarolina13.PathFinding;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathFinderSegmentTest {

    @Test
    void convertPathToSegmentsKeepsDiagonalRunsTogether() {
        List<PathFinder.PathNode> path = List.of(
                new PathFinder.PathNode(new BlockPos(0, 64, 0), "air", true, false),
                new PathFinder.PathNode(new BlockPos(1, 64, 1), "air", true, false),
                new PathFinder.PathNode(new BlockPos(2, 64, 2), "air", true, false)
        );

        Queue<Segment> segments = PathFinder.convertPathToSegments(path, false);

        assertEquals(1, segments.size());
        Segment segment = segments.poll();
        assertNotNull(segment);
        assertEquals(new BlockPos(0, 64, 0), segment.start());
        assertEquals(new BlockPos(2, 64, 2), segment.end());
    }

    @Test
    void movementAxisClassifiesDiagonalDirections() {
        assertEquals("diag", PathFinder.movementAxis(1, 0, -1));
        assertEquals("1,-1", PathFinder.movementDirectionKey(1, 0, -1));
        assertEquals("x", PathFinder.movementAxis(1, 0, 0));
        assertEquals("y", PathFinder.movementAxis(0, 1, 0));
        assertTrue(PathFinder.movementDirectionKey(0, 0, 0) == null);
    }
}
