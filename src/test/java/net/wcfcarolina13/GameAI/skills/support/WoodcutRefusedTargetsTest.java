package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WoodcutRefusedTargetsTest {

    private static final long LEAF = 42L;
    private static final Long SPAWN_TREE = 1000L;
    private static final Long WILD_TREE = 2000L;

    @Test
    void leafRefusedForSpawnTreeIsRetriedForAnotherTree() {
        WoodcutRefusedTargets refused = new WoodcutRefusedTargets();
        refused.remember(LEAF, SPAWN_TREE, "spawn-base");
        assertEquals("spawn-base", refused.get(LEAF, SPAWN_TREE));
        assertNull(refused.get(LEAF, WILD_TREE));
        assertNull(refused.get(LEAF, null));
    }

    @Test
    void noTargetIsItsOwnKey() {
        WoodcutRefusedTargets refused = new WoodcutRefusedTargets();
        refused.remember(LEAF, null, "protected claim");
        assertEquals("protected claim", refused.get(LEAF, null));
        assertNull(refused.get(LEAF, 0L));
    }

    @Test
    void firstReasonWinsAndNullReasonDefaults() {
        WoodcutRefusedTargets refused = new WoodcutRefusedTargets();
        refused.remember(LEAF, WILD_TREE, null);
        refused.remember(LEAF, WILD_TREE, "admin-zone");
        assertEquals("protected", refused.get(LEAF, WILD_TREE));
        assertEquals(1, refused.size());
    }
}
