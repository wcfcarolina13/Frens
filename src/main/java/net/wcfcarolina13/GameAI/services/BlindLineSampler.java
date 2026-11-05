package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for the blind-walk guard: which XZ cells lie on the straight line from the bot's
 * exact position to the centre of the destination cell, nearest first, the bot's own cell left
 * out. The line is sampled one block at a time along the normalized XZ vector, the last sample
 * is clamped to the destination centre, and sampling stops as soon as the destination cell has
 * been produced, so no cell past the destination is ever inspected: a goal on a lip (lava-bucket
 * spot, moat edge, cliff-top stand) is judged by the goal cell itself, not by the air beyond it.
 *
 * <p>No Minecraft types here so the rule stays unit-testable over plain numbers.</p>
 */
public final class BlindLineSampler {

    /** One XZ cell on the line. */
    public record Cell(int x, int z) {
    }

    private BlindLineSampler() {
    }

    /**
     * Number of 1-block samples needed to reach the destination centre from {@code horizontal}
     * blocks away, capped at {@code maxCells}: {@code min(maxCells, ceil(horizontal))}, never
     * below zero. Zero means the bot is already at the destination centre.
     */
    public static int stepCount(double horizontal, int maxCells) {
        if (!(horizontal > 0.0D) || maxCells <= 0) {
            return 0;
        }
        return (int) Math.min(maxCells, Math.ceil(horizontal));
    }

    /**
     * Distance along the line for sample {@code k}: {@code k} blocks, clamped to the destination
     * centre so the final sample can never land past it.
     */
    public static double sampleDistance(int k, double horizontal) {
        return Math.min(k, horizontal);
    }

    /**
     * Cells to inspect, nearest first, without the bot's own cell and without duplicates. The
     * list ends with the destination cell whenever the destination is within {@code maxCells}
     * blocks; it is empty when the bot already stands in the destination cell.
     *
     * @param startX   bot's exact X
     * @param startZ   bot's exact Z
     * @param destX    destination cell X
     * @param destZ    destination cell Z
     * @param maxCells longest line inspected (farther destinations are sampled up to this many
     *                 cells and the destination cell is then not reached)
     */
    public static List<Cell> cells(double startX, double startZ, int destX, int destZ, int maxCells) {
        List<Cell> out = new ArrayList<>();
        int lastX = (int) Math.floor(startX);
        int lastZ = (int) Math.floor(startZ);
        if (lastX == destX && lastZ == destZ) {
            return out;
        }
        double dx = destX + 0.5D - startX;
        double dz = destZ + 0.5D - startZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int steps = stepCount(horizontal, maxCells);
        if (steps == 0) {
            return out;
        }
        double ux = dx / horizontal;
        double uz = dz / horizontal;
        for (int k = 1; k <= steps; k++) {
            double t = sampleDistance(k, horizontal);
            int x = (int) Math.floor(startX + ux * t);
            int z = (int) Math.floor(startZ + uz * t);
            if (x == lastX && z == lastZ) {
                continue;
            }
            lastX = x;
            lastZ = z;
            out.add(new Cell(x, z));
            if (x == destX && z == destZ) {
                break;
            }
        }
        return out;
    }

    // --- where on a cell's collision shape the bot stands ---

    /** A point inside a cell in block-local XZ coordinates (0..1). */
    public record LocalPoint(double x, double z) {
    }

    /** One axis-aligned box of a block's collision shape, in block-local coordinates (0..1). */
    public record ShapeBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    /**
     * Block-local point where the line from {@code (fromX, fromZ)} (the previous cell's centre,
     * the bot's exact position, or the next cell's centre when looking for the exit) crosses into
     * cell {@code (cellX, cellZ)}: the midpoint of {@code from} and the cell centre, clamped at
     * least {@code inset} inside the cell. For a cardinal neighbour the midpoint lies on the
     * shared face, so the clamp pulls it just inside; for a diagonal neighbour it is the shared
     * corner. Measuring a collision shape here (rather than at the cell centre, which for a stair
     * sits exactly on the 0.5/1.0 split) gives the height the bot actually meets first.
     */
    public static LocalPoint edgePoint(double fromX, double fromZ, int cellX, int cellZ, double inset) {
        double in = Math.max(0.0D, Math.min(0.5D, inset));
        double midX = (fromX + cellX + 0.5D) / 2.0D - cellX;
        double midZ = (fromZ + cellZ + 0.5D) / 2.0D - cellZ;
        return new LocalPoint(clamp(midX, in, 1.0D - in), clamp(midZ, in, 1.0D - in));
    }

    /**
     * Height (block-local, 0..1 or more) of the collision shape at XZ point
     * {@code (localX, localZ)}: the highest top among the boxes whose XZ footprint contains the
     * point, or zero when no box does (the column there is open). A bottom slab gives 0.5
     * everywhere; a bottom stair gives 0.5 over its front half and 1.0 over its back half; a
     * full block gives 1.0.
     */
    public static double heightAt(List<ShapeBox> boxes, double localX, double localZ) {
        if (boxes == null || boxes.isEmpty()) {
            return 0.0D;
        }
        double top = 0.0D;
        for (ShapeBox box : boxes) {
            if (box == null) {
                continue;
            }
            if (localX >= box.minX() && localX <= box.maxX() && localZ >= box.minZ() && localZ <= box.maxZ()) {
                top = Math.max(top, box.maxY());
            }
        }
        return top;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
