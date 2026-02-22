package net.wcfcarolina13.GameAI.services.construction;

import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.wcfcarolina13.GameAI.services.construction.VillageFortificationLayoutService.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Spawns server-side coloured dust particles at ground level along a
 * fortification layout perimeter.  Used by dry_run and status commands
 * to give visual feedback about planned or existing walls.
 *
 * <p>Particles are spawned at foundation-Y + 0.5 (one per XZ column)
 * to keep the total count manageable (~150-300 for a typical wall).
 *
 * <p>Colour scheme:
 * <ul>
 *   <li><b>Orange</b> (0xFF6600) — Tower vertex positions (3×3 footprint)</li>
 *   <li><b>Blue</b>   (0x3399FF) — Wall edge positions</li>
 *   <li><b>Gold</b>   (0xFFCC00) — Gatehouse positions</li>
 *   <li><b>Red</b>    (0xFF0000) — Missing / damaged (status mode only)</li>
 *   <li><b>Green</b>  (0x00FF00) — Hull vertex markers (elevated Y+6)</li>
 * </ul>
 */
public final class FortificationVisualizerService {

    private static final Logger LOGGER = LoggerFactory.getLogger("fortification-visualizer");

    // Particle colours
    private static final int COLOR_TOWER    = 0xFF6600;
    private static final int COLOR_WALL     = 0x3399FF;
    private static final int COLOR_GATE     = 0xFFCC00;
    private static final int COLOR_MISSING  = 0xFF0000;
    private static final int COLOR_HULL     = 0x00FF00;
    private static final int COLOR_MOAT     = 0x0066CC;
    private static final int COLOR_OVERHANG = 0x9933FF;
    private static final int COLOR_CLEAR    = 0xFF3333;
    private static final float PARTICLE_SCALE = 1.2f;

    private FortificationVisualizerService() {}

    /**
     * Spawn ground-footprint particles for a fortification layout.
     * Only spawns at ground level (one particle per XZ column).
     *
     * @param world        the server world
     * @param layout       the layout to visualize
     * @param missingPositions set of positions that are missing/damaged (highlighted red); empty for dry_run
     * @param viewer       the player who should see the particles (used for range check)
     */
    public static void spawnLayoutParticles(ServerWorld world, FortificationLayout layout,
                                             Set<BlockPos> missingPositions, ServerPlayerEntity viewer) {
        // Deduplicate by XZ column — prefer above-ground block types (FOUNDATION/WALL)
        // over underground moat blocks. Use terrain Y + 1 as the particle height.
        Map<Long, GroundEntry> groundMap = new LinkedHashMap<>();

        for (ProceduralWallBlock block : layout.allBlocks()) {
            // Skip underground/dig types for particle visualization
            WallBlockType type = block.type();
            if (type == WallBlockType.MOAT_DIG || type == WallBlockType.EXTERIOR_CLEAR) continue;

            BlockPos pos = block.worldPos();
            long key = packXZ(pos.getX(), pos.getZ());
            GroundEntry existing = groundMap.get(key);
            // Prefer FOUNDATION type, then the one closest to terrain level
            boolean betterType = existing == null
                    || (type == WallBlockType.FOUNDATION && existing.type != WallBlockType.FOUNDATION)
                    || (existing.type != WallBlockType.FOUNDATION && pos.getY() > existing.y);
            if (betterType) {
                groundMap.put(key, new GroundEntry(pos.getX(), pos.getY(), pos.getZ(), type, block.edgeIndex()));
            }
        }

        int gatehouseEdge = layout.gatehouseEdgeIndex();

        // Schedule particle spawning on the server thread
        world.getServer().execute(() -> {
            int spawned = 0;
            for (GroundEntry entry : groundMap.values()) {
                boolean isMissing = false;
                if (!missingPositions.isEmpty()) {
                    for (BlockPos mp : missingPositions) {
                        if (mp.getX() == entry.x && mp.getZ() == entry.z) {
                            isMissing = true;
                            break;
                        }
                    }
                }

                int color;
                if (isMissing) {
                    color = COLOR_MISSING;
                } else {
                    color = colorForType(entry.type, entry.edgeIndex, gatehouseEdge);
                }

                // Spawn at terrain Y + 1.5 to ensure visibility above ground
                int terrainY = VillageFortificationLayoutService.terrainY(world, entry.x, entry.z);
                DustParticleEffect dust = new DustParticleEffect(color, PARTICLE_SCALE);
                double px = entry.x + 0.5;
                double py = terrainY + 1.5;
                double pz = entry.z + 0.5;

                world.spawnParticles(dust, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
                spawned++;
            }

            // Hull vertex markers (elevated green particles at Y+6)
            for (WallPoint vertex : layout.hullVertices()) {
                int ty = VillageFortificationLayoutService.terrainY(world, vertex.x(), vertex.z());
                DustParticleEffect hullDust = new DustParticleEffect(COLOR_HULL, 1.5f);
                world.spawnParticles(hullDust,
                        vertex.x() + 0.5, ty + 6.5, vertex.z() + 0.5,
                        1, 0.0, 0.0, 0.0, 0.0);
            }

            LOGGER.debug("Spawned {} ground particles + {} hull markers for layout visualization",
                    spawned, layout.hullVertices().size());
        });
    }

    /**
     * Spawn particles showing only missing/damaged positions (red) overlaid on existing (normal colours).
     * Used by the {@code status} subcommand.
     */
    public static void spawnStatusParticles(ServerWorld world, FortificationLayout layout,
                                             List<ProceduralWallBlock> missingBlocks, ServerPlayerEntity viewer) {
        Set<BlockPos> missingSet = new HashSet<>();
        for (ProceduralWallBlock block : missingBlocks) {
            missingSet.add(block.worldPos());
        }
        spawnLayoutParticles(world, layout, missingSet, viewer);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static int colorForType(WallBlockType type, int edgeIndex, int gatehouseEdge) {
        return switch (type) {
            case TOWER_BASE, TOWER_WALL, TOWER_CAP -> COLOR_TOWER;
            case GATEHOUSE_PILLAR, GATEHOUSE_LINTEL, GATEHOUSE_CAP -> COLOR_GATE;
            case MOAT_DIG, MOAT_FLOOR, MOAT_INNER_FACE -> COLOR_MOAT;
            case MOAT_OVERHANG -> COLOR_OVERHANG;
            case EXTERIOR_CLEAR -> COLOR_CLEAR;
            case FOUNDATION, WALL, MERLON, WALL_TOP_SLAB -> {
                if (edgeIndex == gatehouseEdge) yield COLOR_GATE;
                yield COLOR_WALL;
            }
        };
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private record GroundEntry(int x, int y, int z, WallBlockType type, int edgeIndex) {}
}
