package net.wcfcarolina13.GameAI.souls;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure prose rendering for {@link SoulTypes.ItemFacts}. No Minecraft types: capture extracts
 * facts, this class turns them into short fragments a small local model can attend to.
 */
final class SoulItemDescriber {

    private SoulItemDescriber() {
    }

    private static final double WEAR_BADLY_WORN = 0.75;
    private static final double WEAR_WORN = 0.4;
    private static final int MAX_CONTENTS_KINDS = 4;
    private static final int MAX_NOTABLE = 6;
    private static final int MAX_BULK = 6;
    private static final int NOTABLE_THRESHOLD = 25;

    /** Inventory partitioned into described notable items and count-sorted bulk lines. */
    record InventoryDigest(List<String> notable, List<String> bulk) {
        InventoryDigest {
            notable = notable == null ? List.of() : List.copyOf(notable);
            bulk = bulk == null ? List.of() : List.copyOf(bulk);
        }
    }

    /**
     * Conversational importance of a stack, with no per-item allowlist: items carrying data
     * components (custom name, enchantments, container contents) rank highest, then anything
     * whose max stack size marks it as inherently scarce (1, then 16), then bulk by count.
     */
    static int salience(SoulTypes.ItemFacts item) {
        if (item.customNamed()) {
            return 100;
        }
        if (!item.enchantments().isEmpty()) {
            return 90;
        }
        if (!item.contents().isEmpty()) {
            return 80;
        }
        if (item.maxCount() == 1) {
            return 40;
        }
        if (item.maxCount() <= 16) {
            return NOTABLE_THRESHOLD;
        }
        return Math.min(item.count() / 16, 10);
    }

    /** "Clock, 2x Map" — merges repeated display names with counts, first-seen order. */
    static String groupCounts(List<String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : names) {
            counts.merge(name, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(e -> e.getValue() == 1 ? e.getKey() : e.getValue() + "x " + e.getKey())
                .collect(Collectors.joining(", "));
    }

    static InventoryDigest digest(List<SoulTypes.ItemFacts> items) {
        List<SoulTypes.ItemFacts> notable = new ArrayList<>();
        Map<String, Integer> bulkCounts = new LinkedHashMap<>();
        for (SoulTypes.ItemFacts item : items) {
            if (salience(item) >= NOTABLE_THRESHOLD) {
                notable.add(item);
            } else {
                bulkCounts.merge(item.name(), item.count(), Integer::sum);
            }
        }
        notable.sort(Comparator.comparingInt(SoulItemDescriber::salience).reversed()
                .thenComparing(Comparator.comparingInt(SoulTypes.ItemFacts::count).reversed()));
        List<String> notableLines = notable.stream()
                .limit(MAX_NOTABLE)
                .map(SoulItemDescriber::describe)
                .collect(Collectors.toList());
        List<String> bulkLines = bulkCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(MAX_BULK)
                .map(entry -> entry.getValue() + "x " + entry.getKey())
                .collect(Collectors.toList());
        return new InventoryDigest(notableLines, bulkLines);
    }

    static String describe(SoulTypes.ItemFacts item) {
        StringBuilder sb = new StringBuilder();
        if (item.count() > 1) {
            sb.append(item.count()).append("x ");
        }
        if (item.customNamed()) {
            sb.append('"').append(item.name()).append("\" (").append(item.typeName()).append(')');
        } else {
            sb.append(item.name());
        }

        List<String> annotations = new ArrayList<>(item.enchantments());
        if (item.wearFraction() >= WEAR_BADLY_WORN) {
            annotations.add("badly worn");
        } else if (item.wearFraction() >= WEAR_WORN) {
            annotations.add("worn");
        }
        if (!annotations.isEmpty()) {
            sb.append(" (").append(String.join(", ", annotations)).append(')');
        }
        if (!item.contents().isEmpty()) {
            sb.append(" holding ");
            List<String> inner = new ArrayList<>();
            for (int i = 0; i < item.contents().size() && i < MAX_CONTENTS_KINDS; i++) {
                inner.add(describe(item.contents().get(i)));
            }
            sb.append(String.join(", ", inner));
            int remaining = item.contents().size() - MAX_CONTENTS_KINDS;
            if (remaining > 0) {
                sb.append(" and ").append(remaining).append(" more kinds");
            }
        }
        return sb.toString();
    }
}
