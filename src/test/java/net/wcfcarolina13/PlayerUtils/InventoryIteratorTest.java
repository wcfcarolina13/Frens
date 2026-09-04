package net.wcfcarolina13.PlayerUtils;

import net.wcfcarolina13.PlayerUtils.InventoryIterator.SlotRef;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the generic traversal core of {@link InventoryIterator} with String stand-ins for
 * ItemStacks — the project forbids {@code net.minecraft.*} types in the test source set.
 *
 * <p>Convention used here: "" is an empty stack; "bundle[a,b,c]" is a bundle holding a, b, c.
 */
class InventoryIteratorTest {

    private static final Predicate<String> IS_EMPTY = s -> s == null || s.isEmpty();

    private static final Function<String, List<String>> CHILDREN = s -> {
        if (s == null || !s.startsWith("bundle[") || !s.endsWith("]")) {
            return List.of();
        }
        String inner = s.substring("bundle[".length(), s.length() - 1);
        if (inner.isEmpty()) {
            return List.of();
        }
        return List.of(inner.split(",", -1));
    };

    private static List<SlotRef<String>> run(String... slots) {
        return InventoryIterator.flatten(slots.length, i -> slots[i], IS_EMPTY, CHILDREN)
                .collect(Collectors.toList());
    }

    private static String render(SlotRef<String> ref) {
        return ref.slot() + ":" + ref.bundleIndex() + ":" + ref.stack();
    }

    @Test
    void emitsDirectSlotsInIndexOrderAndSkipsEmpties() {
        List<SlotRef<String>> refs = run("stone", "", "dirt", "", "torch");

        assertEquals(List.of("0:-1:stone", "2:-1:dirt", "4:-1:torch"),
                refs.stream().map(InventoryIteratorTest::render).collect(Collectors.toList()));
        assertTrue(refs.stream().allMatch(SlotRef::isDirect));
    }

    @Test
    void emitsParentBeforeChildrenWithSequentialBundleIndices() {
        List<SlotRef<String>> refs = run("stone", "bundle[torch,coal,bread]", "dirt");

        assertEquals(List.of(
                        "0:-1:stone",
                        "1:-1:bundle[torch,coal,bread]",
                        "1:0:torch",
                        "1:1:coal",
                        "1:2:bread",
                        "2:-1:dirt"),
                refs.stream().map(InventoryIteratorTest::render).collect(Collectors.toList()));
    }

    @Test
    void isDirectDistinguishesParentsFromChildren() {
        List<SlotRef<String>> refs = run("bundle[torch]");

        assertTrue(refs.get(0).isDirect());
        assertEquals(-1, refs.get(0).bundleIndex());
        assertFalse(refs.get(1).isDirect());
        assertEquals(0, refs.get(1).bundleIndex());
    }

    @Test
    void emptyChildrenAreSkippedButStillConsumeTheirIndex() {
        List<SlotRef<String>> refs = run("bundle[torch,,coal]");

        assertEquals(List.of("0:-1:bundle[torch,,coal]", "0:0:torch", "0:2:coal"),
                refs.stream().map(InventoryIteratorTest::render).collect(Collectors.toList()));
    }

    @Test
    void emptyBundleEmitsOnlyTheParent() {
        List<SlotRef<String>> refs = run("bundle[]");

        assertEquals(1, refs.size());
        assertTrue(refs.get(0).isDirect());
    }

    @Test
    void doesNotRecurseIntoNestedBundles() {
        // Vanilla forbids bundles in bundles; assert we do not expand one anyway.
        List<SlotRef<String>> refs = run("bundle[bundle[torch],coal]");

        assertEquals(List.of("0:-1:bundle[bundle[torch],coal]", "0:0:bundle[torch]", "0:1:coal"),
                refs.stream().map(InventoryIteratorTest::render).collect(Collectors.toList()));
    }

    @Test
    void countStyleReductionSumsDirectAndBundledMatches() {
        String[] slots = {"torch", "bundle[torch,coal]", "", "torch"};

        long total = InventoryIterator.flatten(slots.length, i -> slots[i], IS_EMPTY, CHILDREN)
                .filter(ref -> "torch".equals(ref.stack()))
                .count();
        long direct = InventoryIterator.flatten(slots.length, i -> slots[i], IS_EMPTY, CHILDREN)
                .filter(SlotRef::isDirect)
                .filter(ref -> "torch".equals(ref.stack()))
                .count();

        assertEquals(3, total);
        assertEquals(2, direct);
    }

    @Test
    void handlesFullFortyOneSlotInventoryWithTwoBundles() {
        String[] slots = new String[41]; // 36 main + 4 armor + 1 offhand
        java.util.Arrays.fill(slots, "");
        slots[0] = "pickaxe";
        slots[5] = "bundle[torch,torch,coal]";
        slots[20] = "dirt";
        slots[35] = "bundle[bread,,apple]";
        slots[36] = "helmet";
        slots[40] = "shield";

        List<SlotRef<String>> refs = InventoryIterator.flatten(slots.length, i -> slots[i], IS_EMPTY, CHILDREN)
                .collect(Collectors.toList());

        assertEquals(List.of(
                        "0:-1:pickaxe",
                        "5:-1:bundle[torch,torch,coal]",
                        "5:0:torch",
                        "5:1:torch",
                        "5:2:coal",
                        "20:-1:dirt",
                        "35:-1:bundle[bread,,apple]",
                        "35:0:bread",
                        "35:2:apple",
                        "36:-1:helmet",
                        "40:-1:shield"),
                refs.stream().map(InventoryIteratorTest::render).collect(Collectors.toList()));

        // Slot refs are enough to locate every bundled entry unambiguously.
        Map<String, SlotRef<String>> byKey = new HashMap<>();
        for (SlotRef<String> ref : refs) {
            byKey.put(ref.slot() + "/" + ref.bundleIndex(), ref);
        }
        assertEquals(refs.size(), byKey.size());
        assertEquals("apple", byKey.get("35/2").stack());
    }

    @Test
    void emptyInventoryYieldsNothing() {
        assertEquals(0, run("", "", "").size());
        assertEquals(0, InventoryIterator.flatten(0, i -> "x", IS_EMPTY, CHILDREN).count());
    }

    @Test
    void nullBundleChildrenFunctionIsTolerated() {
        List<SlotRef<String>> refs = InventoryIterator
                .flatten(2, i -> i == 0 ? "stone" : "bundle[torch]", IS_EMPTY, null)
                .collect(Collectors.toList());

        assertEquals(2, refs.size());
        assertTrue(refs.stream().allMatch(SlotRef::isDirect));
    }
}
