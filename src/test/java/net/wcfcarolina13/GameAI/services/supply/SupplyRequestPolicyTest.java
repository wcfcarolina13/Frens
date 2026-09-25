package net.wcfcarolina13.GameAI.services.supply;

import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Assessment;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Config;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.ItemKey;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Pos;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Stock;
import net.wcfcarolina13.GameAI.services.supply.SupplyRequestPolicy.Verdict;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyRequestPolicyTest {

    private static final Config CFG = Config.defaults();
    private static final UUID OWNER = new UUID(1, 1);
    private static final UUID STRANGER = new UUID(1, 2);
    private static final String WORLD = "New World/minecraft:overworld";

    private static ItemKey withComponents(String itemId, String... components) {
        return new ItemKey(itemId, "fp:" + String.join(",", components), Set.of(components));
    }

    // ── allowlist, tier, components ──────────────────────────────────────────────────────────

    @Test
    void commonMaterialsAndStarterEquipmentAreEligible() {
        for (String id : new String[]{"minecraft:cobblestone", "minecraft:oak_planks", "minecraft:torch",
                "minecraft:bread", "minecraft:wheat_seeds", "minecraft:stone_axe",
                "minecraft:wooden_pickaxe", "minecraft:copper_sword", "minecraft:leather_boots"}) {
            assertEquals(Verdict.ELIGIBLE, SupplyRequestPolicy.classify(ItemKey.plain(id), CFG), id);
        }
    }

    @Test
    void valuablesAreNotAllowlisted() {
        for (String id : new String[]{"minecraft:diamond", "minecraft:diamond_sword", "minecraft:netherite_axe",
                "minecraft:golden_apple", "minecraft:emerald", "minecraft:chainmail_helmet", ""}) {
            assertEquals(Verdict.NOT_ALLOWLISTED, SupplyRequestPolicy.classify(ItemKey.plain(id), CFG), id);
        }
        assertEquals(Verdict.NOT_ALLOWLISTED, SupplyRequestPolicy.classify(null, CFG));
    }

    @Test
    void ironIsBlockedByDefaultAndAllowedByAddingOneTier() {
        ItemKey ironAxe = ItemKey.plain("minecraft:iron_axe");
        ItemKey ironHelmet = ItemKey.plain("minecraft:iron_helmet");
        assertEquals(Verdict.TIER_NOT_ALLOWED, SupplyRequestPolicy.classify(ironAxe, CFG));
        assertEquals(Verdict.TIER_NOT_ALLOWED, SupplyRequestPolicy.classify(ironHelmet, CFG));

        Set<String> tiers = new HashSet<>(SupplyRequestPolicy.DEFAULT_ALLOWED_TIERS);
        tiers.add("iron");
        Config withIron = CFG.withAllowedTiers(tiers);
        assertEquals(Verdict.ELIGIBLE, SupplyRequestPolicy.classify(ironAxe, withIron));
        assertEquals(Verdict.ELIGIBLE, SupplyRequestPolicy.classify(ironHelmet, withIron));
        // Adding a tier never reaches items outside the table.
        assertEquals(Verdict.NOT_ALLOWLISTED,
                SupplyRequestPolicy.classify(ItemKey.plain("minecraft:diamond_axe"), withIron));
    }

    @Test
    void namedEnchantedLoreAndCustomDataStacksAreProtected() {
        for (String component : new String[]{"minecraft:custom_name", "minecraft:enchantments",
                "minecraft:lore", "minecraft:custom_data", "minecraft:repair_cost"}) {
            assertEquals(Verdict.PROTECTED_COMPONENTS,
                    SupplyRequestPolicy.classify(withComponents("minecraft:cobblestone", component), CFG), component);
            assertEquals(Verdict.PROTECTED_COMPONENTS,
                    SupplyRequestPolicy.classify(withComponents("minecraft:stone_axe", "minecraft:damage", component), CFG),
                    component);
        }
    }

    @Test
    void wornToolsStayEligible() {
        assertEquals(Verdict.ELIGIBLE,
                SupplyRequestPolicy.classify(withComponents("minecraft:stone_axe", "minecraft:damage"), CFG));
    }

    @Test
    void itemKeyToleratesNullsAndCopiesItsComponentSet() {
        Set<String> components = new HashSet<>();
        components.add("minecraft:damage");
        components.add(null);
        ItemKey key = new ItemKey(null, null, components);
        assertEquals("", key.itemId());
        assertEquals("", key.componentsFp());
        assertEquals(Set.of("minecraft:damage"), key.nonDefaultComponentIds());
        components.add("minecraft:custom_name");
        assertEquals(Set.of("minecraft:damage"), key.nonDefaultComponentIds());
    }

    // ── reserves ─────────────────────────────────────────────────────────────────────────────

    @Test
    void grantableFormula() {
        assertEquals(4, SupplyRequestPolicy.grantable(20, 16, 10, 10));
        assertEquals(3, SupplyRequestPolicy.grantable(64, 16, 10, 3));
        assertEquals(2, SupplyRequestPolicy.grantable(64, 16, 2, 10));
        assertEquals(0, SupplyRequestPolicy.grantable(16, 16, 10, 10));
        assertEquals(0, SupplyRequestPolicy.grantable(5, 16, 10, 10));
        assertEquals(0, SupplyRequestPolicy.grantable(64, 16, -1, 10));
        assertEquals(0, SupplyRequestPolicy.grantable(-5, -5, 10, 10));
    }

    @Test
    void materialReserveIsNeverBreached() {
        ItemKey cobble = ItemKey.plain("minecraft:cobblestone");
        for (int stock = 0; stock <= 80; stock++) {
            for (int requested = 0; requested <= 70; requested += 7) {
                int take = SupplyRequestPolicy.grantable(cobble, Stock.of(stock), requested, 64, CFG);
                assertTrue(take >= 0 && take <= requested, "stock=" + stock + " requested=" + requested);
                if (take > 0) {
                    assertTrue(stock - take >= SupplyRequestPolicy.DEFAULT_MATERIAL_RESERVE,
                            "stock=" + stock + " requested=" + requested);
                }
            }
        }
    }

    @Test
    void equipmentLeavesOneOfItsTypeAndNeverExceedsTheExactItem() {
        ItemKey stoneAxe = ItemKey.plain("minecraft:stone_axe");
        // The only axe in the chest stays.
        assertEquals(0, SupplyRequestPolicy.grantable(stoneAxe, Stock.of(1), 1, 1, CFG));
        // One stone axe plus a wooden axe: the stone axe may go, the wooden one is the spare.
        assertEquals(1, SupplyRequestPolicy.grantable(stoneAxe, new Stock(1, 2), 1, 1, CFG));
        // Plenty of axes of the type, but only one stone axe exists.
        assertEquals(1, SupplyRequestPolicy.grantable(stoneAxe, new Stock(1, 5), 3, 3, CFG));
        // Capped by need.
        assertEquals(1, SupplyRequestPolicy.grantable(stoneAxe, new Stock(4, 4), 3, 1, CFG));
        // A type count below the item count is corrected upward, never trusted downward.
        assertEquals(2, SupplyRequestPolicy.grantable(stoneAxe, new Stock(3, 0), 3, 3, CFG));
        assertEquals("axe", SupplyRequestPolicy.equipmentType("minecraft:iron_axe", CFG));
        assertEquals(null, SupplyRequestPolicy.equipmentType("minecraft:cobblestone", CFG));
    }

    @Test
    void assessOrdersOwnerEligibilityNeedThenReserve() {
        ItemKey cobble = ItemKey.plain("minecraft:cobblestone");
        assertEquals(Verdict.NO_OWNER,
                SupplyRequestPolicy.assess(null, cobble, Stock.of(64), 8, 8, CFG).verdict());
        assertEquals(Verdict.NOT_ALLOWLISTED,
                SupplyRequestPolicy.assess(OWNER, ItemKey.plain("minecraft:diamond"), Stock.of(64), 8, 8, CFG).verdict());
        assertEquals(Verdict.NO_NEED,
                SupplyRequestPolicy.assess(OWNER, cobble, Stock.of(64), 8, 0, CFG).verdict());
        assertEquals(Verdict.NO_NEED,
                SupplyRequestPolicy.assess(OWNER, cobble, Stock.of(64), 0, 8, CFG).verdict());
        assertEquals(Verdict.RESERVE_EXHAUSTED,
                SupplyRequestPolicy.assess(OWNER, cobble, Stock.of(16), 8, 8, CFG).verdict());
        Assessment ok = SupplyRequestPolicy.assess(OWNER, cobble, Stock.of(20), 8, 8, CFG);
        assertEquals(Verdict.ELIGIBLE, ok.verdict());
        assertEquals(4, ok.quantity());
    }

    // ── chest identity ───────────────────────────────────────────────────────────────────────

    @Test
    void bothHalvesOfADoubleChestShareOneKey() {
        Pos a = new Pos(10, 64, -3);
        Pos b = new Pos(9, 64, -3);
        ChestKey fromA = ChestKey.canonical(WORLD, a, b);
        ChestKey fromB = ChestKey.canonical(WORLD, b, a);
        assertEquals(fromA, fromB);
        assertEquals(new ChestKey(WORLD, 9, 64, -3), fromA);
    }

    @Test
    void canonicalComparesNumbersNotText() {
        // As strings "10" < "9"; as ints 9 < 10.
        ChestKey key = ChestKey.canonical(WORLD, new Pos(10, 64, 0), new Pos(9, 64, 0));
        assertEquals(9, key.x());
        ChestKey zPair = ChestKey.canonical(WORLD, new Pos(0, 64, -9), new Pos(0, 64, -10));
        assertEquals(-10, zPair.z());
    }

    @Test
    void singleChestAndBogusPartnerKeyThemselves() {
        Pos a = new Pos(5, 70, 5);
        assertEquals(new ChestKey(WORLD, 5, 70, 5), ChestKey.canonical(WORLD, a, null));
        // Not a horizontal neighbour: ignored, fail-safe to a separate chest.
        assertEquals(new ChestKey(WORLD, 5, 70, 5), ChestKey.canonical(WORLD, a, new Pos(4, 69, 5)));
        assertEquals(new ChestKey(WORLD, 5, 70, 5), ChestKey.canonical(WORLD, a, new Pos(3, 70, 5)));
    }

    @Test
    void sameCoordinatesInAnotherWorldIsAnotherChest() {
        Pos a = new Pos(1, 64, 1);
        assertNotEquals(ChestKey.canonical(WORLD, a, null),
                ChestKey.canonical("New World/minecraft:the_nether", a, null));
        assertNotEquals(ChestKey.canonical(WORLD, a, null),
                ChestKey.canonical("Other Save/minecraft:overworld", a, null));
    }

    @Test
    void chestKeyRequiresAWorld() {
        assertThrows(IllegalArgumentException.class, () -> new ChestKey(null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChestKey(" ", 0, 0, 0));
    }

    // ── who may answer ───────────────────────────────────────────────────────────────────────

    @Test
    void onlyTheOwnerMayRespondByDefault() {
        assertTrue(SupplyRequestPolicy.mayRespond(OWNER, OWNER, false, CFG));
        assertTrue(SupplyRequestPolicy.mayRespond(OWNER, OWNER, true, CFG));
        assertFalse(SupplyRequestPolicy.mayRespond(STRANGER, OWNER, false, CFG));
        assertFalse(SupplyRequestPolicy.mayRespond(STRANGER, OWNER, true, CFG));
        assertFalse(SupplyRequestPolicy.mayRespond(null, OWNER, true, CFG));
    }

    @Test
    void operatorMayRespondOnlyWhenConfigured() {
        Config opsMay = CFG.withOperatorMayApprove(true);
        assertTrue(SupplyRequestPolicy.mayRespond(STRANGER, OWNER, true, opsMay));
        assertFalse(SupplyRequestPolicy.mayRespond(STRANGER, OWNER, false, opsMay));
    }

    @Test
    void unownedBotHasNoApprover() {
        assertFalse(SupplyRequestPolicy.mayRespond(OWNER, null, false, CFG));
        assertFalse(SupplyRequestPolicy.mayRespond(OWNER, null, true, CFG.withOperatorMayApprove(true)));
    }

    @Test
    void defaultsMatchTheRulings() {
        assertEquals(16, CFG.materialReserve());
        assertEquals(1, CFG.equipmentSpare());
        assertEquals(Set.of("minecraft:damage"), CFG.allowedComponentIds());
        assertFalse(CFG.operatorMayApprove());
        assertFalse(CFG.allowedTiers().contains("iron"));
        assertEquals(Set.of("wooden", "stone", "copper", "leather"), CFG.allowedTiers());
    }
}
