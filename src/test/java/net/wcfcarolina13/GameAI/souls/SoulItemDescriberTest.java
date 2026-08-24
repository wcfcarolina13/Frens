package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoulItemDescriberTest {

    private static SoulTypes.ItemFacts plain(String name, int count, int maxCount) {
        return new SoulTypes.ItemFacts(name, name, count, maxCount, List.of(), List.of(), 0.0);
    }

    @Test
    void describesSingleItemAsBareName() {
        assertEquals("Iron Sword", SoulItemDescriber.describe(plain("Iron Sword", 1, 1)));
    }

    @Test
    void describesStackWithCountPrefix() {
        assertEquals("43x Torch", SoulItemDescriber.describe(plain("Torch", 43, 64)));
    }

    @Test
    void describesEnchantmentsInParentheses() {
        SoulTypes.ItemFacts sword = new SoulTypes.ItemFacts("Iron Sword", "Iron Sword", 1, 1,
                List.of("Sharpness V", "Unbreaking III"), List.of(), 0.0);
        assertEquals("Iron Sword (Sharpness V, Unbreaking III)", SoulItemDescriber.describe(sword));
    }

    @Test
    void describesHeavyWearAlongsideEnchantments() {
        SoulTypes.ItemFacts pick = new SoulTypes.ItemFacts("Iron Pickaxe", "Iron Pickaxe", 1, 1,
                List.of("Efficiency III"), List.of(), 0.8);
        assertEquals("Iron Pickaxe (Efficiency III, badly worn)", SoulItemDescriber.describe(pick));
    }

    @Test
    void describesModerateWearWithoutEnchantments() {
        SoulTypes.ItemFacts axe = new SoulTypes.ItemFacts("Stone Axe", "Stone Axe", 1, 1,
                List.of(), List.of(), 0.5);
        assertEquals("Stone Axe (worn)", SoulItemDescriber.describe(axe));
    }

    @Test
    void lightWearIsNotMentioned() {
        SoulTypes.ItemFacts shovel = new SoulTypes.ItemFacts("Iron Shovel", "Iron Shovel", 1, 1,
                List.of(), List.of(), 0.2);
        assertEquals("Iron Shovel", SoulItemDescriber.describe(shovel));
    }

    @Test
    void describesBundleContentsInline() {
        SoulTypes.ItemFacts bundle = new SoulTypes.ItemFacts("Red Bundle", "Red Bundle", 1, 1,
                List.of(), List.of(plain("Torch", 32, 64), plain("Bread", 5, 64)), 0.0);
        assertEquals("Red Bundle holding 32x Torch, 5x Bread", SoulItemDescriber.describe(bundle));
    }

    @Test
    void describesEmptyContainerWithoutContentsClause() {
        SoulTypes.ItemFacts bundle = new SoulTypes.ItemFacts("Bundle", "Bundle", 1, 1,
                List.of(), List.of(), 0.0);
        assertEquals("Bundle", SoulItemDescriber.describe(bundle));
    }

    @Test
    void capsContainerContentsAtFourKinds() {
        SoulTypes.ItemFacts shulker = new SoulTypes.ItemFacts("Shulker Box", "Shulker Box", 1, 1,
                List.of(),
                List.of(plain("Torch", 64, 64), plain("Bread", 32, 64), plain("Oak Log", 20, 64),
                        plain("Cobblestone", 12, 64), plain("String", 4, 64), plain("Bone", 2, 64)),
                0.0);
        assertEquals("Shulker Box holding 64x Torch, 32x Bread, 20x Oak Log, 12x Cobblestone and 2 more kinds",
                SoulItemDescriber.describe(shulker));
    }

    @Test
    void describesCustomNamedItemWithItsType() {
        SoulTypes.ItemFacts named = new SoulTypes.ItemFacts("Fang", "Iron Sword", 1, 1,
                List.of(), List.of(), 0.0);
        assertEquals("\"Fang\" (Iron Sword)", SoulItemDescriber.describe(named));
    }

    @Test
    void digestPromotesLowStackAndComponentItemsOverBulk() {
        SoulTypes.ItemFacts bed = plain("Red Bed", 1, 1);
        SoulTypes.ItemFacts pearls = plain("Ender Pearl", 3, 16);
        SoulTypes.ItemFacts enchantedSword = new SoulTypes.ItemFacts("Iron Sword", "Iron Sword", 1, 1,
                List.of("Sharpness V"), List.of(), 0.0);
        SoulTypes.ItemFacts cobble = plain("Cobblestone", 64, 64);

        SoulItemDescriber.InventoryDigest digest = SoulItemDescriber.digest(
                List.of(cobble, bed, pearls, enchantedSword));

        assertEquals(List.of("Iron Sword (Sharpness V)", "Red Bed", "3x Ender Pearl"), digest.notable());
        assertEquals(List.of("64x Cobblestone"), digest.bulk());
    }

    @Test
    void digestMergesBulkStacksByNameAndSortsByCount() {
        SoulItemDescriber.InventoryDigest digest = SoulItemDescriber.digest(List.of(
                plain("Cobblestone", 64, 64), plain("Oak Log", 40, 64), plain("Cobblestone", 30, 64)));

        assertEquals(List.of(), digest.notable());
        assertEquals(List.of("94x Cobblestone", "40x Oak Log"), digest.bulk());
    }

    @Test
    void digestCapsNotableAtSixBySalienceThenCount() {
        SoulTypes.ItemFacts namedPick = new SoulTypes.ItemFacts("Old Faithful", "Iron Pickaxe", 1, 1,
                List.of(), List.of(), 0.0);
        SoulTypes.ItemFacts bundle = new SoulTypes.ItemFacts("Bundle", "Bundle", 1, 1,
                List.of(), List.of(plain("Torch", 10, 64)), 0.0);
        SoulItemDescriber.InventoryDigest digest = SoulItemDescriber.digest(List.of(
                plain("Bucket", 2, 16), plain("Oak Sign", 5, 16), plain("Iron Shovel", 1, 1),
                plain("Stone Axe", 1, 1), plain("Egg", 12, 16),
                namedPick, bundle));

        assertEquals(6, digest.notable().size());
        // The component-carrying items outrank the plain low-stack ones.
        assertEquals("\"Old Faithful\" (Iron Pickaxe)", digest.notable().get(0));
        assertEquals("Bundle holding 10x Torch", digest.notable().get(1));
    }

    @Test
    void digestCapsBulkAtSixKinds() {
        SoulItemDescriber.InventoryDigest digest = SoulItemDescriber.digest(List.of(
                plain("Cobblestone", 64, 64), plain("Dirt", 60, 64), plain("Oak Log", 50, 64),
                plain("Sand", 40, 64), plain("Gravel", 30, 64), plain("Andesite", 20, 64),
                plain("Diorite", 10, 64)));

        assertEquals(6, digest.bulk().size());
        assertEquals("64x Cobblestone", digest.bulk().get(0));
    }

    @Test
    void groupCountsMergesRepeatsAndKeepsOrder() {
        assertEquals("Clock, 2x Map, Diamond Sword",
                SoulItemDescriber.groupCounts(List.of("Clock", "Map", "Diamond Sword", "Map")));
    }
}
