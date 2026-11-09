package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoFuelPolicyTest {

    @Test
    void plainFuelBurns() {
        for (String id : new String[]{"coal", "charcoal", "minecraft:coal_block", "stick", "leaf_litter", "dried_kelp_block", "dead_bush"}) {
            assertTrue(AutoFuelPolicy.isAutoFuel(id, false, false, false, false), id);
        }
        assertTrue(AutoFuelPolicy.isAutoFuel("oak_leaves", true, false, false, false));
        assertTrue(AutoFuelPolicy.isAutoFuel("oak_sapling", false, true, false, false));
        assertTrue(AutoFuelPolicy.isAutoFuel("oak_log", false, false, true, false));
        assertTrue(AutoFuelPolicy.isAutoFuel("oak_planks", false, false, false, true));
    }

    @Test
    void craftedThingsAndValuablesDoNotBurnUnasked() {
        for (String id : new String[]{"wooden_pickaxe", "wooden_sword", "chest", "crafting_table", "white_wool",
                "white_carpet", "bowl", "scaffolding", "bamboo", "oak_boat", "lava_bucket", "blaze_rod",
                "ladder", "oak_sign", "white_banner", "", "   "}) {
            assertFalse(AutoFuelPolicy.isAutoFuel(id, false, false, false, false), id);
        }
        assertFalse(AutoFuelPolicy.isAutoFuel(null, false, false, false, false));
    }

    @Test
    void theCheapestFuelPickAsksThePolicy() throws IOException {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/SmeltingService.java"));
        assertTrue(src.contains("AutoFuelPolicy.isAutoFuel("), "findCheapestFuel filters through the policy");
    }
}
