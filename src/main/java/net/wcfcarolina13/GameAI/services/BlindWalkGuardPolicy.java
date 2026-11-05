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
 * two or more cells deep are hazards regardless of depth. A bot that already stands in water
 * (swimming after a failed plan) treats deep water as plain ground for that walk, else the
 * walks toward shore would refuse on the bot's very first cell.</p>
 *
 * <p>A drop that ends in water is never a fall hazard: fall damage is negated by "falling into
 * the water of any depth, as long as the entity collides with the water before colliding with
 * the ground" (minecraft.wiki Damage, "Fall damage reduction"). Such a cell keeps its
 * {@link CellFact#deepWater()} flag, so the deep-water rule still applies to it.</p>
 *
 * <p>Half steps: a block whose collision top is no higher than the player's step height
 * (bottom slab 0.5, the front of a bottom stair 0.5, a campfire 7/16, snow layers) is walked
 * onto without a jump (minecraft.wiki Slab: "the maximum step height of the player is 0.6 of a
 * block"), so the sampler keeps its tracked feet cell and records the standing height instead
 * of counting a whole block of rise; a taller top (full block, cactus) is the one-block rise it
 * always was. See {@link #stepKind(double)} and {@link #effectiveDrop(int, double)}.</p>
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
     * @param fire         fire or another deadly block (campfire, magma, cactus ...) at the feet
     *                     or the landing floor the bot would stand on; on a non-passable cell only
     *                     a block that hurts on side contact (cactus, sweet berry bush, fire),
     *                     since the bot stops against a wall and never stands on it
     * @param deepWater    two or more water cells under the feet cell or the landing point
     * @param waterLanding the scan under the feet cell ended in water, so {@code floorDrop} is
     *                     no fall hazard (any depth of water negates fall damage)
     */
    public record CellFact(boolean passable, int floorDrop, boolean lava, boolean fire, boolean deepWater,
                           boolean waterLanding) {

        /** A cell whose drop, if any, ends on solid ground. */
        public CellFact(boolean passable, int floorDrop, boolean lava, boolean fire, boolean deepWater) {
            this(passable, floorDrop, lava, fire, deepWater, false);
        }

        /** Flat, dry, passable ground. */
        public static CellFact flat() {
            return new CellFact(true, 0, false, false, false);
        }

        /** Passable cell with {@code drop} air cells below it before a floor. */
        public static CellFact drop(int drop) {
            return new CellFact(true, drop, false, false, false);
        }

        /**
         * Passable cell with {@code drop} air cells below it before water; {@code deep} when the
         * water is two or more cells deep.
         */
        public static CellFact dropIntoWater(int drop, boolean deep) {
            return new CellFact(true, drop, false, false, deep, true);
        }

        /** Something the bot cannot step into or onto (two-high wall, closed iron door, ...). */
        public static CellFact wall() {
            return new CellFact(false, 0, false, false, false);
        }

        /**
         * A wall the bot would stop against. Only a block that hurts on side contact makes it a
         * hazard: cactus ("when most entities ... touch a cactus, it deals 1 damage every tick",
         * minecraft.wiki Cactus), sweet berry bush (damages entities "moving in the hitbox of the
         * bush", minecraft.wiki Sweet Berry Bush) and fire. Magma is not one: "Walking into the
         * side of a magma block doesn't cause damage" (minecraft.wiki Magma Block); it only hurts
         * as a floor, which the passable-cell floor scan reports. Lava and fire have empty
         * collision shapes, so they never form a wall and the lava flag is always false here.
         */
        public static CellFact wallFact(boolean hurtsOnSideContact) {
            return new CellFact(false, 0, false, hurtsOnSideContact, false);
        }

        /** {@link #wallFact(boolean)} for a cactus-class wall. */
        public static CellFact hazardousWall() {
            return wallFact(true);
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

    /** {@link #decide(List, int, int, boolean)} with the vanilla fall threshold, the default horizon and a dry start. */
    public static Decision decide(List<CellFact> line) {
        return decide(line, MAX_SAFE_DROP, DEFAULT_REFUSE_HORIZON, false);
    }

    /**
     * {@link #decide(List, int, int, boolean)} with the vanilla fall threshold and the default
     * horizon.
     *
     * @param startInWater the bot is touching water where it stands, so deep water on the line is
     *                     not a hazard for this walk (lava, fire and drops still are)
     */
    public static Decision decide(List<CellFact> line, boolean startInWater) {
        return decide(line, MAX_SAFE_DROP, DEFAULT_REFUSE_HORIZON, startInWater);
    }

    /** {@link #decide(List, int, int, boolean)} for a bot starting on dry ground. */
    public static Decision decide(List<CellFact> line, int maxSafeDrop, int refuseHorizon) {
        return decide(line, maxSafeDrop, refuseHorizon, false);
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
     * @param startInWater  the bot is already touching water, so {@link CellFact#deepWater()} is
     *                      ignored for this walk (the swim toward shore must be allowed)
     */
    public static Decision decide(List<CellFact> line, int maxSafeDrop, int refuseHorizon, boolean startInWater) {
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
                String wallHazard = hazardOf(cell, maxSafeDrop, startInWater);
                if (wallHazard != null && i < horizon) {
                    return Decision.refuse(describe(wallHazard, i));
                }
                return Decision.allow(i);
            }
            String hazard = hazardOf(cell, maxSafeDrop, startInWater);
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

    /** {@link #hazardOf(CellFact, int, boolean)} for a bot starting on dry ground. */
    public static String hazardOf(CellFact cell, int maxSafeDrop) {
        return hazardOf(cell, maxSafeDrop, false);
    }

    /**
     * Names the hazard in one cell, or {@code null} when it is safe to step into. Lava wins over
     * fire wins over deep water wins over the drop, so the log names the deadliest thing. With
     * {@code startInWater} deep water is never a hazard. A drop that lands in water is never a
     * hazard (water of any depth negates fall damage); its deep-water flag still counts.
     */
    public static String hazardOf(CellFact cell, int maxSafeDrop, boolean startInWater) {
        Objects.requireNonNull(cell, "cell");
        if (cell.lava()) {
            return "lava";
        }
        if (cell.fire()) {
            return "fire/deadly block";
        }
        if (cell.deepWater() && !startInWater) {
            return "deep water";
        }
        if (cell.floorDrop() > maxSafeDrop && !cell.waterLanding()) {
            return "drop of " + cell.floorDrop() + "+";
        }
        return null;
    }

    private static String describe(String hazard, int index) {
        int ahead = index + 1;
        return hazard + " " + ahead + (ahead == 1 ? " block" : " blocks") + " ahead";
    }

    // --- step rule: how the sampler follows a cell whose collision shape is not empty ---

    /**
     * Tallest rise a player walks up without jumping: "the maximum step height of the player is
     * 0.6 of a block" (minecraft.wiki Slab, "Behavior"). A bottom slab (0.5) is walked onto; a
     * cactus (15/16) or a full block is jumped onto.
     */
    public static final double MAX_STEP_HEIGHT = 0.6D;

    /**
     * Collision tops this close to 1.0 count as a full block (float noise in voxel shapes).
     * Kept for callers that compare against the full height; {@link #stepKind(double)} splits on
     * {@link #MAX_STEP_HEIGHT}.
     */
    public static final double FULL_HEIGHT_TOLERANCE = 1.0E-4D;

    /** What the sampler does with the collision shape it meets at the feet level of a line cell. */
    public enum StepKind {
        /** No collision at the entry point: the bot walks in at floor level. */
        OPEN,
        /**
         * A top no higher than {@link #MAX_STEP_HEIGHT} (bottom slab, stair front, campfire, snow
         * layers): the bot walks onto it without a jump, so the tracked feet cell stays and only
         * the standing height within the cell changes.
         */
        HALF_STEP,
        /**
         * A top above {@link #MAX_STEP_HEIGHT} (full block, top slab, stair back, cactus): a
         * one-block rise the bot must jump up (or a wall if it cannot).
         */
        STEP_UP
    }

    /**
     * Classifies the collision height {@code entryHeight} (0..1 within the feet cell, as
     * measured where the line enters the cell). A top above {@link #MAX_STEP_HEIGHT} is a
     * {@link StepKind#STEP_UP}; a top strictly between zero and that is a
     * {@link StepKind#HALF_STEP}; zero or less (empty, NaN) is {@link StepKind#OPEN}. A 0.5 slab
     * is walked onto rather than jumped, and a bot on it stands only half a block higher:
     * counting a whole block of rise for it made a legal 3-block drop read as 4.
     */
    public static StepKind stepKind(double entryHeight) {
        if (!(entryHeight > 0.0D)) {
            return StepKind.OPEN;
        }
        if (entryHeight > MAX_STEP_HEIGHT) {
            return StepKind.STEP_UP;
        }
        return StepKind.HALF_STEP;
    }

    /**
     * Height the sampler should reason about for a non-empty collision shape: the shape's height
     * at the entry point when the column there is filled, otherwise the shape's overall top (the
     * obstacle sits elsewhere in the cell, e.g. a fence post or a stair's side; the bot still
     * cannot walk through at floor level). Zero for an empty shape.
     */
    public static double entryHeight(double heightAtEntry, double shapeTop) {
        if (heightAtEntry > 0.0D) {
            return heightAtEntry;
        }
        return shapeTop > 0.0D ? shapeTop : 0.0D;
    }

    /**
     * Whole-block drop to hand to {@link CellFact#floorDrop()} when the bot leaves a cell standing
     * {@code standingHeight} (0..1) above its tracked feet level and {@code airCells} air cells lie
     * under the next feet cell before the floor. The real fall distance is
     * {@code airCells + standingHeight}; vanilla damage is {@code floor(fallDistance - 3)}
     * (minecraft.wiki Damage, "Fall damage"), which is positive exactly when
     * {@code floor(fallDistance) >= 4}, so {@code floor(airCells + standingHeight)} compares to
     * {@link #MAX_SAFE_DROP} the same way the real fall does: from a slab (0.5) three air cells
     * is a 3.5 fall and safe; from the back of a stair (1.0) three air cells is a 4.0 fall and a
     * hazard. Negative or NaN inputs count as zero; the standing height is clamped to 0..1.
     */
    public static int effectiveDrop(int airCells, double standingHeight) {
        int cells = Math.max(0, airCells);
        double height = standingHeight > 0.0D ? Math.min(1.0D, standingHeight) : 0.0D;
        return (int) Math.floor(cells + height);
    }
}
