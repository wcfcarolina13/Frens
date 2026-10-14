package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScaffoldToolPolicyTest {

    @Test
    void eachSingleTagPicksItsTool() {
        assertEquals("pickaxe", ScaffoldToolPolicy.keywordFor(true, false, false));
        assertEquals("shovel", ScaffoldToolPolicy.keywordFor(false, true, false));
        assertEquals("axe", ScaffoldToolPolicy.keywordFor(false, false, true));
    }

    @Test
    void noTagMeansHands() {
        assertNull(ScaffoldToolPolicy.keywordFor(false, false, false));
    }

    @Test
    void precedenceIsPickaxeThenShovelThenAxe() {
        assertEquals("pickaxe", ScaffoldToolPolicy.keywordFor(true, true, true));
        assertEquals("pickaxe", ScaffoldToolPolicy.keywordFor(true, false, true));
        assertEquals("shovel", ScaffoldToolPolicy.keywordFor(false, true, true));
    }

    @Test
    void teardownUsesPickaxeOnlyWhenTheBlockNeedsATool() {
        assertEquals("pickaxe", ScaffoldToolPolicy.teardownKeyword(true));
        assertNull(ScaffoldToolPolicy.teardownKeyword(false));
    }
}
