package net.wcfcarolina13.GameAI.souls;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Change-driven cues (ontology Phase 1): what differs between two scenes becomes a high-weight anchor. */
class SoulSceneDiffTest {

    private static SoulTypes.GroundingSnapshot grounding(String weather, String timePhase, String biome,
                                                         float health, int hunger, String held,
                                                         List<String> animals, List<String> facilities,
                                                         Optional<String> atBase, String playerHeld) {
        SoulTypes.BotSnapshot bot = new SoulTypes.BotSnapshot(UUID.randomUUID(), "Jake", "overworld", biome,
                0, 64, 0, true, timePhase, weather, health, 20f, hunger, 4, held, 4, 36,
                List.of(), "content", "IDLE", "", "", "", "Bradley", true, 0, true, Optional.empty());
        SoulTypes.SituationSnapshot base = SoulTypes.SituationSnapshot.empty();
        SoulTypes.SituationSnapshot situation = new SoulTypes.SituationSnapshot(base.dangerDistance(), base.hostiles(),
                animals, "grass block", base.nearbyBlocks(), facilities,
                base.facilitySightings(), base.armorStands(), base.blockLight(), base.skyLight(), base.enclosed(),
                base.hasHeadroom(), base.hasEscapeRoute(), base.behaviorMode(), base.following(), base.inCombat(),
                base.postCombatLinger(), base.recentKillCount(), base.inShelter(), base.surfaceRecoveryActive(),
                base.breakingFree(), base.nightTravelActive(), base.companionDays(), base.deathCount(), base.mount(),
                base.knownBaseCount(), base.lastSleepLabel(), atBase, base.hunt(), base.lastHobby());
        Optional<SoulTypes.PlayerSnapshot> player = playerHeld == null ? Optional.empty()
                : Optional.of(new SoulTypes.PlayerSnapshot(UUID.randomUUID(), "Bradley", 3, "north",
                        20f, 20f, 18, playerHeld, false, "", ""));
        return new SoulTypes.GroundingSnapshot(SoulTypes.Reachability.LOCAL, bot, player, situation,
                Instant.EPOCH, List.of());
    }

    private static Set<String> topics(List<SoulBanterSeed.Anchor> anchors) {
        Set<String> out = new HashSet<>();
        for (SoulBanterSeed.Anchor a : anchors) {
            out.add(a.topic());
        }
        return out;
    }

    @Test
    void firstCallReportsOnlyFirstSightingsAndPrimesTheRegistry() {
        Set<String> seen = new HashSet<>();
        SoulTypes.GroundingSnapshot now = grounding("rain", "dusk", "taiga", 20f, 18, "iron axe",
                List.of("wolves"), List.of("campfire (cook food)"), Optional.empty(), "bow");
        List<SoulBanterSeed.Anchor> anchors = SoulSceneDiff.diff(null, now, seen, "Bradley");
        assertEquals(Set.of("animals", "facilities"), topics(anchors), anchors.toString());
        assertTrue(seen.containsAll(Set.of("animal:wolves", "facility:campfire", "biome:taiga")));
        // Same scene again: nothing is new.
        assertTrue(SoulSceneDiff.diff(now, now, seen, "Bradley").isEmpty());
    }

    @Test
    void changesBetweenScenesBecomeHighWeightAnchors() {
        Set<String> seen = new HashSet<>();
        SoulTypes.GroundingSnapshot before = grounding("rain", "dusk", "taiga", 20f, 18, "iron axe",
                List.of(), List.of(), Optional.empty(), "bow");
        SoulSceneDiff.diff(null, before, seen, "Bradley");
        SoulTypes.GroundingSnapshot after = grounding("clear", "night", "swamp", 12f, 6, "bow",
                List.of("frogs"), List.of(), Optional.of("Riverside"), "torch");
        List<SoulBanterSeed.Anchor> anchors = SoulSceneDiff.diff(before, after, seen, "Bradley");
        Set<String> got = topics(anchors);
        assertTrue(got.containsAll(Set.of("the weather", "the hour", "the land", "health", "food", "gear",
                "the player", "home", "animals")), got.toString());
        for (SoulBanterSeed.Anchor a : anchors) {
            assertEquals(SoulSceneDiff.CHANGE_WEIGHT, a.weight());
        }
        String all = anchors.toString();
        assertTrue(all.contains("rain just stopped"), all);
        assertTrue(all.contains("night has fallen"), all);
        assertTrue(all.contains("crossed into swamp"), all);
        assertTrue(all.contains("back at Riverside"), all);
        assertTrue(all.contains("Bradley is holding torch now"), all);
        assertFalse(all.contains("first time in swamp") && !all.contains("crossed into"), "biome change phrased as a crossing");
    }

    @Test
    void facilityNamesDropCountsAndUtilityPhrases() {
        assertEquals("chest", SoulSceneDiff.facilityName("3x chest (storage)"));
        assertEquals("campfire", SoulSceneDiff.facilityName("campfire (cook food)"));
        assertEquals("bed", SoulSceneDiff.facilityName("bed"));
    }
}
