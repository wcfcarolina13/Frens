package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.MakeRoomDropPolicy.SlotView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MakeRoomDropPolicyTest {

    private static SlotView cheap(int id, int count) {
        return new SlotView(id, count, true, false, false, false, false, false);
    }

    private static SlotView valuable(int id, int count) {
        return new SlotView(id, count, false, false, false, false, false, false);
    }

    private static SlotView food(int id, int count, boolean cheap) {
        return new SlotView(id, count, cheap, false, false, false, true, false);
    }

    private static SlotView reserved(int id, int count, boolean cheap) {
        return new SlotView(id, count, cheap, false, false, true, false, false);
    }

    private static SlotView tool(int id) {
        return new SlotView(id, 1, false, false, true, false, false, true);
    }

    private static SlotView protectedItem(int id, int count) {
        return new SlotView(id, count, false, true, false, false, false, false);
    }

    @Test
    void cheapOnlyFindsNothingInAPackOfValuables() {
        List<SlotView> pack = List.of(valuable(0, 64), valuable(1, 12), tool(2), protectedItem(3, 1));
        assertEquals(-1, MakeRoomDropPolicy.pick(pack, true));
    }

    @Test
    void withoutCheapOnlyTheLargestValuableIsTheLastResort() {
        List<SlotView> pack = List.of(valuable(0, 12), valuable(1, 64), tool(2));
        assertEquals(1, MakeRoomDropPolicy.pick(pack, false));
    }

    @Test
    void foodIsNeverDroppedEvenWhenCheapOrTheOnlyChoice() {
        assertEquals(-1, MakeRoomDropPolicy.pick(List.of(food(0, 64, false), food(1, 3, true)), false));
        assertEquals(-1, MakeRoomDropPolicy.pick(List.of(food(0, 64, false), food(1, 3, true)), true));
        assertEquals(2, MakeRoomDropPolicy.pick(List.of(food(0, 1, true), valuable(1, 64), cheap(2, 40)), true));
    }

    @Test
    void reservedIsNeverDropped() {
        assertEquals(-1, MakeRoomDropPolicy.pick(List.of(reserved(0, 1, true), reserved(1, 64, false)), false));
        assertEquals(1, MakeRoomDropPolicy.pick(List.of(reserved(0, 1, true), cheap(1, 64)), true));
    }

    @Test
    void toolsAndProtectedItemsAreNeverDroppedWithoutCheapOnlyEither() {
        assertEquals(-1, MakeRoomDropPolicy.pick(List.of(tool(0), protectedItem(1, 64)), false));
    }

    @Test
    void theSmallestCheapStackGoesFirst() {
        List<SlotView> pack = List.of(cheap(0, 64), valuable(1, 2), cheap(2, 9), cheap(3, 33));
        assertEquals(2, MakeRoomDropPolicy.pick(pack, true));
        assertEquals(2, MakeRoomDropPolicy.pick(pack, false));
    }

    @Test
    void emptySlotsAndANullListPickNothing() {
        assertEquals(-1, MakeRoomDropPolicy.pick(null, false));
        assertEquals(-1, MakeRoomDropPolicy.pick(List.of(cheap(0, 0)), false));
    }

    @Test
    void mutualAidDropsCheapStacksOnlyAndTheSweeperKeepsTheOldRule() throws IOException {
        String aid = mainSource("src/main/java/net/wcfcarolina13/GameAI/services/BotMutualAidService.java");
        assertEquals(3, count(aid, "CraftingHelper.dropCheapStackForSpace("), "the three make-room sites");
        assertEquals(3, count(aid, ".keySet(), true)"), "every mutual-aid make-room drop is cheapOnly");
        assertTrue(aid.contains("My pack's full of things I won't throw away."), "the owner line");
        String sweeper = mainSource("src/main/java/net/wcfcarolina13/GameAI/DropSweeper.java");
        assertTrue(sweeper.contains("CraftingHelper.dropCheapStackForSpace(player, source, reserved)"),
                "the sweeper keeps the largest-stack fallback");
        String helper = mainSource("src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java");
        assertTrue(helper.contains("MakeRoomDropPolicy.pick(slots, cheapOnly)"), "the drop goes through the policy");
        assertFalse(helper.contains("registerSelfDrop(bot, dropped);"), "a valuable drop is not held for the full despawn");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private static String mainSource(String relative) throws IOException {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path at = dir; at != null; at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException(relative + " not found above " + dir);
    }
}
