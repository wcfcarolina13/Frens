package net.wcfcarolina13.GameAI.skills.impl;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

/** Layout protection for routine clearing, with explicit recovery exceptions. */
final class FortifyMiningPolicy {
    private FortifyMiningPolicy() {}

    enum Context {
        SCAFFOLD_CLEANUP, PLACEMENT_CLEARING,
        CARVE_RECOVERY, ESCAPE_HEADROOM, GATE_EXIT, TOWER_RING_BREAKOUT
    }

    static boolean mayMine(boolean satisfiedLayout, boolean plannedForDig, Context context) {
        return plannedForDig || !satisfiedLayout || switch (context) {
            case SCAFFOLD_CLEANUP, PLACEMENT_CLEARING -> false;
            case CARVE_RECOVERY, ESCAPE_HEADROOM, GATE_EXIT, TOWER_RING_BREAKOUT -> true;
        };
    }

    /** Cache the candidates once per layout, but check satisfaction against the current world. */
    static <P> Set<P> satisfiedPositions(Set<P> layoutPositions, Set<P> plannedForDig,
                                         Predicate<P> satisfied) {
        Set<P> candidates = new HashSet<>(layoutPositions);
        candidates.removeAll(plannedForDig);
        return Collections.unmodifiableSet(new AbstractSet<>() {
            @Override
            @SuppressWarnings("unchecked")
            public boolean contains(Object position) {
                return candidates.contains(position) && satisfied.test((P) position);
            }

            @Override
            public Iterator<P> iterator() {
                return candidates.stream().filter(satisfied).iterator();
            }

            @Override
            public int size() {
                int count = 0;
                for (P position : candidates) if (satisfied.test(position)) count++;
                return count;
            }
        });
    }

    static boolean outsideTower(int x, int z, int towerX, int towerZ) {
        return Math.abs(x - towerX) > 1 || Math.abs(z - towerZ) > 1;
    }
}
