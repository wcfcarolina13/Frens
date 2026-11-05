package net.wcfcarolina13.GameAI.services;

import java.util.List;
import java.util.Objects;

/**
 * Pure guard for the straight-line "blind" walks MovementService falls back to when no path
 * came back (walkDirect after an empty plan, the near-goal walkDirect shortcut, and
 * pursuitUntilClose after a failed walk). Those walks only check that the next feet/head cell
 * is passable; nothing looked at the floor. This policy reads a precomputed line of
 * {@link CellFact}s (sampled on the server thread, one per block along the line, starting with
 * the cell after the bot's own) and says whether the walk may start and how far it may go.
 *
 * <p>Vanilla fall damage starts once a fall exceeds 3 blocks (minecraft.wiki Damage, "Fall
 * damage": {@code max(0, floor((fallDistance - 3) * multiplier))}), so a drop of 4 or more
 * below a cell on the line is a hazard; lava, fire-class or otherwise deadly blocks, and water
 * two or more cells deep are hazards regardless of depth.</p>
 *
 * <p>No Minecraft types here so the decision stays unit-testable over plain lists.</p>
 */
public final class BlindWalkGuardPolicy {

    /** Largest drop (air cells under the feet cell before a floor) that costs no health. */
    public static final int MAX_SAFE_DROP = 3;

    /** A hazard within this many cells of the bot refuses the walk outright instead of capping it. */
    public static final int DEFAULT_REFUSE_HORIZON = 3;

    /** Cells kept between the end of a capped walk and the hazard that capped it. */
    public static final int CAP_MARGIN = 1;

    /**
     * One feet cell on the straight line.
     *
     * @param passable  feet and head cells can be entered (openable doors count; a wall, or a
     *                  rise of two or more, does not)
     * @param floorDrop air cells below the feet cell before a solid floor or water, capped by the
     *                  sampler (a value above {@link #MAX_SAFE_DROP} is a drop, however deep)
     * @param lava      lava in the feet cell or anywhere in the scanned column below it
     * @param fire      fire or another deadly block (campfire, magma, cactus ...) at the feet or
     *                  the landing floor
     * @param deepWater two or more water cells under the feet cell or the landing point
     */
    public record CellFact(boolean passable, int floorDrop, boolean lava, boolean fire, boolean deepWater) {

        /** Flat, dry, passable ground. */
        public static CellFact flat() {
            return new CellFact(true, 0, false, false, false);
        }

        /** Passable cell with {@code drop} air cells below it before a floor. */
        public static CellFact drop(int drop) {
            return new CellFact(true, drop, false, false, false);
        }

        /** Something the bot cannot step into or onto (two-high wall, closed iron door, ...). */
        public static CellFact wall() {
            return new CellFact(false, 0, false, false, false);
        }

        public static CellFact lavaCell() {
            return new CellFact(true, 0, true, false, false);
        }

        public static CellFact fireCell() {
            return new CellFact(true, 0, false, true, false);
        }

        public static CellFact deepWaterCell() {
            return new CellFact(true, 0, false, false, true);
        }
    }

    public enum Verdict {
        /** Walk the whole line as today. */
        ALLOW,
        /** Walk only the first {@code safeCells} cells; a hazard lies beyond them. */
        ALLOW_UP_TO,
        /** Do not start the blind walk. */
        REFUSE
    }

    /**
     * @param verdict   what to do
     * @param safeCells cells (from the start of the line) the walk may cover; for ALLOW this is the
     *                  number inspected, for ALLOW_UP_TO the cap (always at least 1), for REFUSE 0
     * @param reason    human-readable hazard, or {@code null} when nothing was found
     */
    public record Decision(Verdict verdict, int safeCells, String reason) {

        public boolean allowed() {
            return verdict != Verdict.REFUSE;
        }

        public boolean capped() {
            return verdict == Verdict.ALLOW_UP_TO;
        }

        static Decision allow(int inspected) {
            return new Decision(Verdict.ALLOW, Math.max(0, inspected), null);
        }

        static Decision allowUpTo(int safeCells, String reason) {
            return new Decision(Verdict.ALLOW_UP_TO, safeCells, reason);
        }

        static Decision refuse(String reason) {
            return new Decision(Verdict.REFUSE, 0, reason);
        }
    }

    private BlindWalkGuardPolicy() {
    }

    /** {@link #decide(List, int, int)} with the vanilla fall threshold and the default horizon. */
    public static Decision decide(List<CellFact> line) {
        return decide(line, MAX_SAFE_DROP, DEFAULT_REFUSE_HORIZON);
    }

    /**
     * Walks the line in order. The first hazard decides: inside the first {@code refuseHorizon}
     * cells it refuses the walk; further out it caps the walk {@link #CAP_MARGIN} cells short of
     * the hazard (never below one cell, else it refuses). A non-passable cell ends the inspection
     * without refusing: the bot stops at the wall as it does today and the step-up, door and
     * mining assists keep their current behaviour. An empty or null line allows.
     *
     * @param line          cells along the straight line, nearest first, the bot's own cell excluded
     * @param maxSafeDrop   drops deeper than this are hazards (3 in vanilla)
     * @param refuseHorizon hazards at index below this refuse instead of capping
     */
    public static Decision decide(List<CellFact> line, int maxSafeDrop, int refuseHorizon) {
        if (line == null || line.isEmpty()) {
            return Decision.allow(0);
        }
        int horizon = Math.max(1, refuseHorizon);
        for (int i = 0; i < line.size(); i++) {
            CellFact cell = line.get(i);
            if (cell == null) {
                continue;
            }
            if (!cell.passable()) {
                // Hazards on a wall cell still count (lava or fire at the feet of a rise).
                String wallHazard = hazardOf(cell, maxSafeDrop);
                if (wallHazard != null && i < horizon) {
                    return Decision.refuse(describe(wallHazard, i));
                }
                return Decision.allow(i);
            }
            String hazard = hazardOf(cell, maxSafeDrop);
            if (hazard == null) {
                continue;
            }
            if (i < horizon) {
                return Decision.refuse(describe(hazard, i));
            }
            int safeCells = i - CAP_MARGIN;
            if (safeCells < 1) {
                return Decision.refuse(describe(hazard, i));
            }
            return Decision.allowUpTo(safeCells, describe(hazard, i));
        }
        return Decision.allow(line.size());
    }

    /**
     * Names the hazard in one cell, or {@code null} when it is safe to step into. Lava wins over
     * fire wins over deep water wins over the drop, so the log names the deadliest thing.
     */
    public static String hazardOf(CellFact cell, int maxSafeDrop) {
        Objects.requireNonNull(cell, "cell");
        if (cell.lava()) {
            return "lava";
        }
        if (cell.fire()) {
            return "fire/deadly block";
        }
        if (cell.deepWater()) {
            return "deep water";
        }
        if (cell.floorDrop() > maxSafeDrop) {
            return "drop of " + cell.floorDrop() + "+";
        }
        return null;
    }

    private static String describe(String hazard, int index) {
        int ahead = index + 1;
        return hazard + " " + ahead + (ahead == 1 ? " block" : " blocks") + " ahead";
    }
}
