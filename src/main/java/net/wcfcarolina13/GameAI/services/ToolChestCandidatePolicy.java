package net.wcfcarolina13.GameAI.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure merge of the chests a chest tool search may ask ({@code ToolProvisionService#retrieveToolFromChests}):
 * the bot's registered chests and, at Woodcut's start, the chests scanned near the bot (finding 8,
 * Bradley 2026-10-19: a player-placed base chest is not in the registry). One entry per chest:
 * a position seen twice, or the other half of a double chest already taken, is dropped, keeping
 * the better entry. Order: best tool first, then nearest.
 *
 * <p>No Minecraft types: positions are packed {@code long}s ({@code BlockPos.asLong}), the other
 * half of a double chest is passed in as data, and the tool is whatever the caller ranks.
 */
public final class ToolChestCandidatePolicy {

    private ToolChestCandidatePolicy() {
    }

    /**
     * One chest that holds a matching tool.
     *
     * @param pos     the chest half's packed position
     * @param partner the packed position of its other half, or {@code null} for a single chest or
     *                when unknown (registry entries)
     * @param best    the best matching tool in it
     * @param distSq  squared distance from the bot
     */
    public record Candidate<T>(long pos, Long partner, T best, double distSq) {
    }

    /**
     * Registry and scanned candidates as one list: sorted by {@code tier} (less is better), then
     * nearest, registry first on a tie; then each entry whose position was already taken, as
     * itself or as an earlier entry's other half, is dropped. Null lists and null entries are
     * ignored.
     */
    public static <T> List<Candidate<T>> merge(List<Candidate<T>> registered,
                                               List<Candidate<T>> scanned,
                                               Comparator<T> tier) {
        List<Candidate<T>> all = new ArrayList<>();
        addAll(all, registered);
        addAll(all, scanned);
        Comparator<Candidate<T>> order = Comparator.comparingDouble(Candidate::distSq);
        if (tier != null) {
            order = Comparator.<Candidate<T>, T>comparing(Candidate::best, Comparator.nullsLast(tier))
                    .thenComparingDouble(Candidate::distSq);
        }
        all.sort(order); // stable: registry entries stay ahead on a tie
        Set<Long> taken = new HashSet<>();
        List<Candidate<T>> out = new ArrayList<>();
        for (Candidate<T> c : all) {
            if (taken.contains(c.pos())) {
                continue;
            }
            taken.add(c.pos());
            if (c.partner() != null) {
                if (taken.contains(c.partner())) {
                    continue;
                }
                taken.add(c.partner());
            }
            out.add(c);
        }
        return out;
    }

    private static <T> void addAll(List<Candidate<T>> into, List<Candidate<T>> from) {
        if (from == null) {
            return;
        }
        for (Candidate<T> c : from) {
            if (c != null) {
                into.add(c);
            }
        }
    }
}
