package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure tests for the woodcut chat sentences. No net.minecraft types. */
class WoodcutChatLinesTest {

    @Test
    void wholeTree() {
        assertEquals("Got 3 logs from that tree.", WoodcutChatLines.treeHarvested(3, false));
        assertEquals("Got 1 log from that tree.", WoodcutChatLines.treeHarvested(1, false));
    }

    @Test
    void restNeedsAnAxe() {
        assertEquals("Got 3 logs, the rest of that tree needs an axe.", WoodcutChatLines.treeHarvested(3, true));
        assertEquals("Got 1 log, the rest of that tree needs an axe.", WoodcutChatLines.treeHarvested(1, true));
        assertEquals("That tree needs an axe.", WoodcutChatLines.treeHarvested(0, true));
    }

    @Test
    void nothingToSay() {
        assertNull(WoodcutChatLines.treeHarvested(0, false));
        assertNull(WoodcutChatLines.treeHarvested(-2, false));
    }

    @Test
    void felledTreeAlwaysSaysSomething() {
        assertEquals("Got 4 logs from that tree.", WoodcutChatLines.treeFelled(4));
        assertEquals("Got 1 log from that tree.", WoodcutChatLines.treeFelled(1));
        assertEquals("Done with that tree.", WoodcutChatLines.treeFelled(0));
        assertEquals("Done with that tree.", WoodcutChatLines.treeFelled(-1));
    }

    @Test
    void noDevTextDashesOrSemicolons() {
        for (int logs = 0; logs <= 12; logs++) {
            for (boolean rest : new boolean[]{false, true}) {
                String line = WoodcutChatLines.treeHarvested(logs, rest);
                if (line == null) {
                    continue;
                }
                assertFalse(line.contains("—") || line.contains("–") || line.contains(";"), line);
                assertFalse(line.contains(" min ") || line.contains("cleared ("), line);
            }
            String felled = WoodcutChatLines.treeFelled(logs);
            assertFalse(felled.contains("—") || felled.contains("–") || felled.contains(";"), felled);
        }
    }
}
