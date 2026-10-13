package net.wcfcarolina13.GraphicalUserInterface;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Plans the tab row of the bot inventory's topics overlay ("Actions | Dialogue | ✦ | Admin").
 * The same plan feeds drawing and click hit-tests, so they can't disagree.
 *
 * <p>Order of attempts, first fit wins:
 * <ol>
 *   <li>Full labels in equal-width tabs (the look the overlay always had when there is room).</li>
 *   <li>Full labels sized by label width ({@link UiFitPolicy#fitTabWidths}).</li>
 *   <li>Each shorter label set in turn ("Actions" to "Do", "Dialogue" to "Talk"), sized by width.</li>
 *   <li>Equal shares with {@link UiFitPolicy#pickLabel} per tab, which elides the shortest label.</li>
 * </ol>
 * A tab whose shown label differs from its full name should get a hover tooltip with the full name.
 * No Minecraft types here.
 */
public final class OverlayTabPolicy {

    /**
     * One tab. {@code labelsLongestFirst} must hold at least one label. {@code fixedWidth >= 0}
     * keeps the tab at that width (the compact ✦ tab), otherwise the tab shares the free space.
     */
    public record Tab(List<String> labelsLongestFirst, String fullName, int fixedWidth) {
        public static Tab flexible(String fullName, String... labelsLongestFirst) {
            return new Tab(List.of(labelsLongestFirst), fullName, -1);
        }

        public static Tab fixed(String label, String fullName, int width) {
            return new Tab(List.of(label), fullName, width);
        }

        boolean isFixed() {
            return fixedWidth >= 0;
        }
    }

    /** Chosen label and width per tab, in input order. */
    public record Plan(List<String> labels, int[] widths, List<String> fullNames) {
        public boolean isShortened(int index) {
            return !labels.get(index).equals(fullNames.get(index));
        }
    }

    private OverlayTabPolicy() {
    }

    public static Plan plan(List<Tab> tabs, int available, int padding, ToIntFunction<String> width) {
        int n = tabs.size();
        int pad = Math.max(0, padding);
        List<String> fullNames = new ArrayList<>(n);
        List<Integer> flexible = new ArrayList<>();
        int fixedSum = 0;
        int maxLevels = 1;
        for (int i = 0; i < n; i++) {
            Tab tab = tabs.get(i);
            fullNames.add(tab.fullName());
            if (tab.isFixed()) {
                fixedSum += tab.fixedWidth();
            } else {
                flexible.add(i);
                maxLevels = Math.max(maxLevels, tab.labelsLongestFirst().size());
            }
        }
        int space = Math.max(0, available - fixedSum);

        // 1. Full labels, equal widths.
        List<String> full = labelsAtLevel(tabs, 0);
        if (!flexible.isEmpty()) {
            int equal = space / flexible.size();
            boolean allFit = true;
            for (int i : flexible) {
                if (width.applyAsInt(full.get(i)) + pad * 2 > equal) {
                    allFit = false;
                    break;
                }
            }
            if (allFit) {
                int[] widths = new int[n];
                for (int i = 0; i < n; i++) {
                    widths[i] = tabs.get(i).isFixed() ? tabs.get(i).fixedWidth() : equal;
                }
                return new Plan(full, widths, fullNames);
            }
        }

        // 2 and 3. Full labels, then each shorter set, sized by label width.
        for (int level = 0; level < maxLevels; level++) {
            List<String> labels = labelsAtLevel(tabs, level);
            List<Integer> labelWidths = new ArrayList<>(flexible.size());
            for (int i : flexible) {
                labelWidths.add(width.applyAsInt(labels.get(i)));
            }
            int[] fitted = UiFitPolicy.fitTabWidths(labelWidths, space, pad);
            if (fitted != null) {
                return new Plan(labels, merge(tabs, flexible, fitted), fullNames);
            }
        }

        // 4. Equal shares, shortest label elided where needed.
        int[] shares = new int[flexible.size()];
        if (!flexible.isEmpty()) {
            int base = space / flexible.size();
            int remainder = space % flexible.size();
            for (int k = 0; k < shares.length; k++) {
                shares[k] = base + (k < remainder ? 1 : 0);
            }
        }
        List<String> labels = new ArrayList<>(labelsAtLevel(tabs, 0));
        for (int k = 0; k < flexible.size(); k++) {
            int i = flexible.get(k);
            labels.set(i, UiFitPolicy.pickLabel(tabs.get(i).labelsLongestFirst(), shares[k] - pad * 2, width));
        }
        return new Plan(labels, merge(tabs, flexible, shares), fullNames);
    }

    private static List<String> labelsAtLevel(List<Tab> tabs, int level) {
        List<String> labels = new ArrayList<>(tabs.size());
        for (Tab tab : tabs) {
            List<String> candidates = tab.labelsLongestFirst();
            labels.add(candidates.get(Math.min(level, candidates.size() - 1)));
        }
        return labels;
    }

    private static int[] merge(List<Tab> tabs, List<Integer> flexible, int[] flexibleWidths) {
        int[] widths = new int[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).isFixed()) {
                widths[i] = tabs.get(i).fixedWidth();
            }
        }
        for (int k = 0; k < flexible.size(); k++) {
            widths[flexible.get(k)] = flexibleWidths[k];
        }
        return widths;
    }
}
