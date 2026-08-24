package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoulBlockKnowledgeTest {

    // --- Utility phrases keyed by block id path (creative "Functional Blocks" / job-site conventions) ---

    @Test
    void knowsCoreWorkstations() {
        assertEquals(Optional.of("crafting station"), SoulBlockKnowledge.phraseFor("crafting_table"));
        assertEquals(Optional.of("smelts ore, cooks food"), SoulBlockKnowledge.phraseFor("furnace"));
        assertEquals(Optional.of("stores items"), SoulBlockKnowledge.phraseFor("chest"));
    }

    @Test
    void knowsVillagerJobSites() {
        assertTrue(SoulBlockKnowledge.phraseFor("blast_furnace").orElse("").contains("armorer"));
        assertTrue(SoulBlockKnowledge.phraseFor("lectern").orElse("").contains("librarian"));
        assertTrue(SoulBlockKnowledge.phraseFor("composter").orElse("").contains("farmer"));
    }

    @Test
    void matchesColoredFamiliesBySuffix() {
        assertTrue(SoulBlockKnowledge.phraseFor("red_bed").orElse("").contains("respawn"));
        assertTrue(SoulBlockKnowledge.phraseFor("cyan_shulker_box").orElse("").contains("storage"));
        assertTrue(SoulBlockKnowledge.phraseFor("chipped_anvil").orElse("").contains("repairs"));
    }

    @Test
    void unknownBlockHasNoPhrase() {
        assertEquals(Optional.empty(), SoulBlockKnowledge.phraseFor("weathered_copper_grate"));
    }

    @Test
    void mundaneBlockEntitiesAreExcludedFromFacilities() {
        assertTrue(SoulBlockKnowledge.isMundane("oak_sign"));
        assertTrue(SoulBlockKnowledge.isMundane("red_banner"));
        assertTrue(SoulBlockKnowledge.isMundane("player_head"));
        assertTrue(SoulBlockKnowledge.isMundane("skeleton_skull"));
        assertFalse(SoulBlockKnowledge.isMundane("chest"));
    }

    // --- Facility digest: dedupe + count + cap + phrase attachment ---

    private static SoulTypes.RawFacility fac(String idPath, String name) {
        return new SoulTypes.RawFacility(idPath, name);
    }

    @Test
    void digestGroupsCountsAndAnnotates() {
        List<String> lines = SoulBlockKnowledge.digestFacilities(List.of(
                fac("chest", "Chest"), fac("chest", "Chest"), fac("furnace", "Furnace")));

        assertEquals(List.of("2x Chest (stores items)", "Furnace (smelts ore, cooks food)"), lines);
    }

    @Test
    void digestKeepsUnknownFunctionalBlocksNamedWithoutPhrase() {
        List<String> lines = SoulBlockKnowledge.digestFacilities(List.of(
                fac("modded_widget", "Arcane Widget")));

        assertEquals(List.of("Arcane Widget"), lines);
    }

    @Test
    void digestDropsMundaneEntriesAndCapsAtSixKinds() {
        List<String> lines = SoulBlockKnowledge.digestFacilities(List.of(
                fac("oak_sign", "Oak Sign"),
                fac("chest", "Chest"), fac("furnace", "Furnace"), fac("barrel", "Barrel"),
                fac("smoker", "Smoker"), fac("loom", "Loom"), fac("lectern", "Lectern"),
                fac("bell", "Bell")));

        assertEquals(6, lines.size());
        assertFalse(String.join(",", lines).contains("Oak Sign"));
    }
}
