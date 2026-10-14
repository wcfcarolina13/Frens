package net.wcfcarolina13.PlayerUtils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScaffoldToolPolicyTest {

    @Test
    void teardownUsesPickaxeOnlyWhenTheBlockNeedsATool() {
        assertEquals("pickaxe", ScaffoldToolPolicy.teardownKeyword(true));
        assertNull(ScaffoldToolPolicy.teardownKeyword(false));
    }
}
