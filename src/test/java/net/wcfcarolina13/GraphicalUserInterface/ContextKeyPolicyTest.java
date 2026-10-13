package net.wcfcarolina13.GraphicalUserInterface;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextKeyPolicyTest {

    @Test
    void noBotsHintTeachesBothMeaningsOfTheKey() {
        List<String> lines = ContextKeyPolicy.noBotsHintLines("-");
        assertEquals(3, lines.size());
        assertEquals("Press [-] to open the roster. Click [x] to hide this.", lines.get(1));
        assertEquals("With a bot out, [-] sends it where you look.", lines.get(2));
    }

    @Test
    void usesTheBoundKeyAndFallsBackToTheDefault() {
        assertTrue(ContextKeyPolicy.noBotsHintLines("G").get(1).startsWith("Press [G]"));
        assertTrue(ContextKeyPolicy.noBotsHintLines(null).get(1).startsWith("Press [-]"));
        assertTrue(ContextKeyPolicy.noBotsHintLines(" ").get(1).startsWith("Press [-]"));
    }

    @Test
    void botsPresentHintPointsAtTheRealRosterPath() {
        List<String> lines = ContextKeyPolicy.botsPresentHintLines("-", "]");
        assertEquals("Now [-] sends a companion where you look.", lines.get(0));
        assertEquals("Roster: Guide (]) > Admin > Bot Controls > Spawn Bots", lines.get(1));
        assertEquals("Roster: Guide (]) > Admin > Bot Controls > Spawn Bots",
                ContextKeyPolicy.botsPresentHintLines(null, null).get(1));
    }

    @Test
    void noEmDashesSemicolonsOrOverlongLines() {
        List<String> all = new ArrayList<>(ContextKeyPolicy.noBotsHintLines("-"));
        all.addAll(ContextKeyPolicy.botsPresentHintLines("-", "]"));
        for (String line : all) {
            assertFalse(line.contains("—"), line);
            assertFalse(line.contains(";"), line);
            assertTrue(line.length() <= 60, line);
        }
    }
}
