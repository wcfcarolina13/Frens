package net.wcfcarolina13.GameAI.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for creating simple schematics programmatically.
 * Useful for testing and for creating simple structures without .nbt files.
 */
public class SimpleSchematicBuilder {

    private final String name;
    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> paletteIndex = new HashMap<>();
    private final List<SchematicData.BlockPlacement> blocks = new ArrayList<>();
    private int maxX = 0, maxY = 0, maxZ = 0;

    public SimpleSchematicBuilder(String name) {
        this.name = name;
        // Index 0 is always air
        palette.add(Blocks.AIR.getDefaultState());
        paletteIndex.put(Blocks.AIR.getDefaultState(), 0);
    }

    /**
     * Add a block at the given relative position.
     */
    public SimpleSchematicBuilder block(int x, int y, int z, BlockState state) {
        if (state == null || state.isAir()) return this;

        int index = paletteIndex.computeIfAbsent(state, s -> {
            palette.add(s);
            return palette.size() - 1;
        });

        blocks.add(new SchematicData.BlockPlacement(new BlockPos(x, y, z), index));

        maxX = Math.max(maxX, x + 1);
        maxY = Math.max(maxY, y + 1);
        maxZ = Math.max(maxZ, z + 1);

        return this;
    }

    /**
     * Fill a cuboid region with the given block state.
     */
    public SimpleSchematicBuilder fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    block(x, y, z, state);
                }
            }
        }
        return this;
    }

    /**
     * Build a hollow box (walls, floor, ceiling).
     */
    public SimpleSchematicBuilder hollowBox(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    // Only place on edges
                    boolean isEdge = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                    if (isEdge) {
                        block(x, y, z, state);
                    }
                }
            }
        }
        return this;
    }

    /**
     * Build the schematic data.
     */
    public SchematicData build() {
        return new SchematicData(name, maxX, maxY, maxZ, List.copyOf(palette), List.copyOf(blocks));
    }

    // ========== Pre-built test schematics ==========

    /**
     * A simple 3x3x3 hollow cube - perfect for basic testing.
     */
    public static SchematicData testCube() {
        return new SimpleSchematicBuilder("test_cube")
                .hollowBox(0, 0, 0, 2, 2, 2, Blocks.COBBLESTONE.getDefaultState())
                .build();
    }

    /**
     * A tiny 1x2x1 pillar - the simplest possible structure.
     */
    public static SchematicData testPillar() {
        return new SimpleSchematicBuilder("test_pillar")
                .block(0, 0, 0, Blocks.COBBLESTONE.getDefaultState())
                .block(0, 1, 0, Blocks.COBBLESTONE.getDefaultState())
                .build();
    }

    /**
     * A small 3x3 platform.
     */
    public static SchematicData testPlatform() {
        return new SimpleSchematicBuilder("test_platform")
                .fill(0, 0, 0, 2, 0, 2, Blocks.OAK_PLANKS.getDefaultState())
                .build();
    }

    /**
     * A simple 5x3x6 hut with entrance platform and door opening.
     * Floor: oak planks (extends from z=-1 to z=4)
     * Walls: oak logs (at y=0-1, doorway on north wall at z=0)
     * Roof: oak planks (covers interior z=0-4 at y=2)
     */
    public static SchematicData smallHut() {
        SimpleSchematicBuilder builder = new SimpleSchematicBuilder("small_hut");
        BlockState floor = Blocks.OAK_PLANKS.getDefaultState();
        BlockState wall = Blocks.OAK_LOG.getDefaultState();
        BlockState roof = Blocks.OAK_PLANKS.getDefaultState();
        BlockState torch = Blocks.TORCH.getDefaultState();

        // Floor (5x6) at y=-1: extends from z=-1 (entrance) to z=4 (interior)
        builder.fill(0, -1, -1, 4, -1, 4, floor);
        
        // Clear entrance area in FRONT of door (z=-1) so player can approach
        builder.block(2, 0, -1, Blocks.AIR.getDefaultState());
        builder.block(2, 1, -1, Blocks.AIR.getDefaultState());

        // Walls (4 sides, 2 blocks high) - now at y=0 and y=1
        for (int y = 0; y <= 1; y++) {
            // North wall (z=0) - skip x=2 for doorway
            for (int x = 0; x <= 4; x++) {
                if (x == 2) continue; // Skip x=2 - door goes here
                builder.block(x, y, 0, wall);
            }
            // South wall (z=4) - complete
            for (int x = 0; x <= 4; x++) {
                builder.block(x, y, 4, wall);
            }
            // West wall (x=0) - interior sides only (z=1 to z=3)
            for (int z = 1; z <= 3; z++) {
                builder.block(0, y, z, wall);
            }
            // East wall (x=4) - interior sides only (z=1 to z=3)
            for (int z = 1; z <= 3; z++) {
                builder.block(4, y, z, wall);
            }
        }
        
        // NOTE: Door is NOT placed in schematic - should be placed separately by DoorPlacementService
        // The doorway opening is at (x=2, z=0, y=0-1).
        
        // Torch INSIDE the structure (south side interior), placed on floor at y=0
        builder.block(2, 0, 3, torch);

        // Roof (5x5) at y=2 (2 blocks above floor)
        builder.fill(0, 2, 0, 4, 2, 4, roof);

        return builder.build();
    }

    /**
     * A proper watchtower with climbable interior and observation platform.
     * Features:
     * - 4x4 footprint, 8 blocks high
     * - Stone brick base with log corner posts
     * - Interior ladder for climbing
     * - Observation platform with fence railings
     * - Arrow slits in walls
     * - Torch lighting
     */
    public static SchematicData watchtower() {
        SimpleSchematicBuilder builder = new SimpleSchematicBuilder("watchtower");
        BlockState stone = Blocks.STONE_BRICKS.getDefaultState();
        BlockState log = Blocks.SPRUCE_LOG.getDefaultState();
        BlockState planks = Blocks.SPRUCE_PLANKS.getDefaultState();
        BlockState fence = Blocks.SPRUCE_FENCE.getDefaultState();
        BlockState ladder = Blocks.LADDER.getDefaultState();
        BlockState torch = Blocks.TORCH.getDefaultState();
        BlockState slab = Blocks.SPRUCE_SLAB.getDefaultState();

        // Floor foundation (4x4)
        builder.fill(0, 0, 0, 3, 0, 3, stone);

        // Corner log posts (full height - 8 blocks)
        for (int y = 1; y <= 7; y++) {
            builder.block(0, y, 0, log);
            builder.block(3, y, 0, log);
            builder.block(0, y, 3, log);
            builder.block(3, y, 3, log);
        }

        // Stone brick walls (lower 4 blocks) with arrow slits
        for (int y = 1; y <= 4; y++) {
            // North wall (Z=0) - arrow slit at Y=2,3 in center
            builder.block(1, y, 0, (y == 2 || y == 3) ? Blocks.AIR.getDefaultState() : stone);
            builder.block(2, y, 0, (y == 2 || y == 3) ? Blocks.AIR.getDefaultState() : stone);
            // South wall (Z=3) - arrow slit at Y=2,3 in center
            builder.block(1, y, 3, (y == 2 || y == 3) ? Blocks.AIR.getDefaultState() : stone);
            builder.block(2, y, 3, (y == 2 || y == 3) ? Blocks.AIR.getDefaultState() : stone);
            // West wall (X=0) - arrow slit
            builder.block(0, y, 1, (y == 2 || y == 3) ? Blocks.AIR.getDefaultState() : stone);
            builder.block(0, y, 2, (y == 2 || y == 3) ? Blocks.AIR.getDefaultState() : stone);
            // East wall (X=3) - door opening at Y=1,2
            if (y <= 2) {
                // Door opening - leave as air
            } else {
                builder.block(3, y, 1, stone);
                builder.block(3, y, 2, stone);
            }
        }

        // Upper section (plank walls, Y=5-7) - more open
        for (int y = 5; y <= 6; y++) {
            builder.block(1, y, 0, planks);
            builder.block(2, y, 0, planks);
            builder.block(1, y, 3, planks);
            builder.block(2, y, 3, planks);
            builder.block(0, y, 1, planks);
            builder.block(0, y, 2, planks);
            builder.block(3, y, 1, planks);
            builder.block(3, y, 2, planks);
        }

        // Mid-level floor at Y=4 (interior platform for archery)
        builder.block(1, 4, 1, planks);
        builder.block(2, 4, 1, planks);
        builder.block(1, 4, 2, planks);
        builder.block(2, 4, 2, planks);

        // Ladder (inside, against west wall)
        for (int y = 1; y <= 7; y++) {
            builder.block(1, y, 2, ladder);
        }

        // Top observation platform (4x4) at Y=7
        builder.fill(0, 7, 0, 3, 7, 3, planks);

        // Fence railings around platform (Y=8)
        for (int x = 0; x <= 3; x++) {
            builder.block(x, 8, 0, fence);
            builder.block(x, 8, 3, fence);
        }
        builder.block(0, 8, 1, fence);
        builder.block(0, 8, 2, fence);
        builder.block(3, 8, 1, fence);
        builder.block(3, 8, 2, fence);

        // Torches at corners of platform
        builder.block(0, 8, 0, torch);
        builder.block(3, 8, 0, torch);
        builder.block(0, 8, 3, torch);
        builder.block(3, 8, 3, torch);

        return builder.build();
    }

    /**
     * A proper bridge with support pillars and railings.
     * Features:
     * - 9 blocks long, 3 blocks wide
     * - Support pillars at ends
     * - Fence railings on both sides
     * - Slight arch in the deck
     */
    public static SchematicData bridge5x1() {
        SimpleSchematicBuilder builder = new SimpleSchematicBuilder("bridge_5x1");
        BlockState planks = Blocks.OAK_PLANKS.getDefaultState();
        BlockState log = Blocks.OAK_LOG.getDefaultState();
        BlockState fence = Blocks.OAK_FENCE.getDefaultState();
        BlockState slab = Blocks.OAK_SLAB.getDefaultState();

        // Bridge is 9 blocks long (X=0-8), 3 blocks wide (Z=0-2)
        // Support pillars at each end go down 3 blocks

        // Left support pillar (X=0)
        for (int y = -3; y <= 0; y++) {
            builder.block(0, y, 0, log);
            builder.block(0, y, 2, log);
        }
        
        // Right support pillar (X=8)
        for (int y = -3; y <= 0; y++) {
            builder.block(8, y, 0, log);
            builder.block(8, y, 2, log);
        }

        // Cross-beams under deck at ends
        builder.block(0, 0, 1, log);
        builder.block(8, 0, 1, log);

        // Main deck (3 wide)
        // Slight arch - ends at Y=1, middle at Y=1 (flat for simplicity)
        for (int x = 0; x <= 8; x++) {
            int deckY = 1;
            builder.block(x, deckY, 0, planks);
            builder.block(x, deckY, 1, planks);
            builder.block(x, deckY, 2, planks);
        }

        // Fence railings on both sides
        for (int x = 0; x <= 8; x++) {
            builder.block(x, 2, 0, fence);
            builder.block(x, 2, 2, fence);
        }

        // Decorative posts at quarter points
        builder.block(2, 2, 0, log);
        builder.block(2, 2, 2, log);
        builder.block(6, 2, 0, log);
        builder.block(6, 2, 2, log);

        return builder.build();
    }

    /**
     * A straight defensive wall section (5 blocks long, 4 high).
     * Features:
     * - Solid stone base (2 blocks high)
     * - Crenellated top (alternating merlons)
     * - Walkway behind the wall
     * - Can be chained end-to-end
     */
    public static SchematicData defensiveWallSection() {
        SimpleSchematicBuilder builder = new SimpleSchematicBuilder("defensive_wall_section");
        BlockState stone = Blocks.STONE_BRICKS.getDefaultState();
        BlockState slab = Blocks.STONE_BRICK_SLAB.getDefaultState();
        BlockState stairs = Blocks.STONE_BRICK_STAIRS.getDefaultState();

        // Wall is 5 blocks long (X), 1 block deep (Z), 4 blocks high (Y)
        // Structure: wall on Z=0, walkway on Z=1

        // Foundation and main wall (5x4x1)
        for (int x = 0; x <= 4; x++) {
            // Main wall column
            for (int y = 0; y <= 3; y++) {
                builder.block(x, y, 0, stone);
            }
            // Walkway floor at Y=2 (behind wall at Z=1)
            builder.block(x, 2, 1, stone);
        }

        // Crenellations (merlons) - alternating raised blocks at top
        // Pattern: merlon at x=0, gap at x=1, merlon at x=2, gap at x=3, merlon at x=4
        builder.block(0, 4, 0, stone);
        builder.block(2, 4, 0, stone);
        builder.block(4, 4, 0, stone);

        return builder.build();
    }

    /**
     * A corner piece for defensive walls (connects two wall sections at 90 degrees).
     * Features:
     * - L-shaped corner
     * - Same height as wall section (4 high)
     * - Connects X-axis wall to Z-axis wall
     * - Corner tower post for structural emphasis
     */
    public static SchematicData defensiveWallCorner() {
        SimpleSchematicBuilder builder = new SimpleSchematicBuilder("defensive_wall_corner");
        BlockState stone = Blocks.STONE_BRICKS.getDefaultState();
        BlockState log = Blocks.OAK_LOG.getDefaultState();

        // Corner piece is 3x3 footprint
        // Wall extends along X=0 (going negative X) and Z=0 (going negative Z)
        // Corner tower at (0,0)

        // Corner tower column (taller, uses logs for emphasis)
        for (int y = 0; y <= 4; y++) {
            builder.block(0, y, 0, log);
        }
        // Tower top cap
        builder.block(0, 5, 0, stone);

        // Wall segment extending along X-axis (X=1,2 at Z=0)
        for (int x = 1; x <= 2; x++) {
            for (int y = 0; y <= 3; y++) {
                builder.block(x, y, 0, stone);
            }
            // Walkway
            builder.block(x, 2, 1, stone);
            // Crenellation on even positions
            if (x % 2 == 0) {
                builder.block(x, 4, 0, stone);
            }
        }

        // Wall segment extending along Z-axis (Z=1,2 at X=0)
        for (int z = 1; z <= 2; z++) {
            for (int y = 0; y <= 3; y++) {
                builder.block(0, y, z, stone);
            }
            // Walkway
            builder.block(1, 2, z, stone);
            // Crenellation on even positions
            if (z % 2 == 0) {
                builder.block(0, 4, z, stone);
            }
        }

        // Inner corner walkway connection
        builder.block(1, 2, 1, stone);

        return builder.build();
    }

    /**
     * A gatehouse section with an opening for passage.
     * Features:
     * - Archway opening (2 blocks high, 1 block wide)
     * - Reinforced sides
     * - Room for door placement
     */
    public static SchematicData defensiveGatehouse() {
        SimpleSchematicBuilder builder = new SimpleSchematicBuilder("defensive_gatehouse");
        BlockState stone = Blocks.STONE_BRICKS.getDefaultState();
        BlockState chiseled = Blocks.CHISELED_STONE_BRICKS.getDefaultState();

        // Gatehouse is 3 blocks wide (X), 2 blocks deep (Z), 5 blocks high (Y)
        // Opening in the center (X=1)

        // Left pillar (X=0)
        for (int z = 0; z <= 1; z++) {
            for (int y = 0; y <= 4; y++) {
                builder.block(0, y, z, stone);
            }
            builder.block(0, 5, z, chiseled); // Decorative cap
        }

        // Right pillar (X=2)
        for (int z = 0; z <= 1; z++) {
            for (int y = 0; y <= 4; y++) {
                builder.block(2, y, z, stone);
            }
            builder.block(2, 5, z, chiseled); // Decorative cap
        }

        // Archway lintel (above opening at Y=3)
        builder.block(1, 3, 0, stone);
        builder.block(1, 3, 1, stone);

        // Top walkway/roof
        for (int x = 0; x <= 2; x++) {
            builder.block(x, 4, 0, stone);
            builder.block(x, 4, 1, stone);
        }

        // Opening for door/passage is at (1, 0, 0-1) through (1, 2, 0-1) - left as air

        return builder.build();
    }

    /**
     * A small shelter structure (like the hovel but as a schematic).
     * Features:
     * - 5x5 footprint
     * - 3 blocks high walls
     * - Flat roof
     * - Door opening on one side (2 blocks high for door placement)
     * - Clear approach path outside doorway
     * - Interior torch
     */
    public static SchematicData smallShelter() {
        SimpleSchematicBuilder builder = new SimpleSchematicBuilder("small_shelter");
        BlockState floor = Blocks.OAK_PLANKS.getDefaultState();
        BlockState wall = Blocks.COBBLESTONE.getDefaultState();
        BlockState roof = Blocks.OAK_PLANKS.getDefaultState();
        BlockState torch = Blocks.TORCH.getDefaultState();

        // Floor (5x5) at Y=0, but leave doorway gap at X=2, Z=0 for clear entrance
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                // Skip floor under doorway for clear entrance path
                if (x == 2 && z == 0) continue;
                builder.block(x, 0, z, floor);
            }
        }

        // Walls (3 high, Y=1 to Y=3) with door opening at center of north side
        for (int y = 1; y <= 3; y++) {
            // North wall (Z=0) - door opening at X=2, Y=1 and Y=2 (2 blocks high)
            for (int x = 0; x <= 4; x++) {
                if (x == 2 && y <= 2) continue; // Door opening (2 blocks high)
                builder.block(x, y, 0, wall);
            }
            // South wall (Z=4) - solid
            for (int x = 0; x <= 4; x++) {
                builder.block(x, y, 4, wall);
            }
            // West wall (X=0) - sides only
            for (int z = 1; z <= 3; z++) {
                builder.block(0, y, z, wall);
            }
            // East wall (X=4) - sides only
            for (int z = 1; z <= 3; z++) {
                builder.block(4, y, z, wall);
            }
        }

        // NOTE: Door is NOT placed in schematic - should be placed separately by DoorPlacementService
        // after structure is complete. The doorway opening is at (X=2, Z=0, Y=1-2).

        // Torch inside (center of south wall)
        builder.block(2, 2, 3, torch);

        // Roof (5x5) at Y=4
        builder.fill(0, 4, 0, 4, 4, 4, roof);

        return builder.build();
    }

    /**
     * Get a pre-built schematic by name.
     */
    public static SchematicData getBuiltIn(String name) {
        return switch (name.toLowerCase()) {
            case "test_cube" -> testCube();
            case "test_pillar" -> testPillar();
            case "test_platform" -> testPlatform();
            case "small_hut" -> smallHut();
            case "watchtower" -> watchtower();
            case "bridge_5x1", "bridge" -> bridge5x1();
            case "defensive_wall_section", "wall_section", "wall" -> defensiveWallSection();
            case "defensive_wall_corner", "wall_corner" -> defensiveWallCorner();
            case "defensive_gatehouse", "gatehouse" -> defensiveGatehouse();
            case "small_shelter", "shelter" -> smallShelter();
            default -> null;
        };
    }

    /**
     * List all available built-in schematics.
     */
    public static List<String> listBuiltIn() {
        return List.of(
                "test_cube",
                "test_pillar",
                "test_platform",
                "small_hut",
                "small_shelter",
                "watchtower",
                "bridge_5x1",
                "defensive_wall_section",
                "defensive_wall_corner",
                "defensive_gatehouse"
        );
    }
}
