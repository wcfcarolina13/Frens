package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.DeathRetrievalPolicy.Offset;
import net.wcfcarolina13.GameAI.services.DeathRetrievalPolicy.ReturnMode;
import net.wcfcarolina13.GameAI.services.DeathRetrievalPolicy.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathRetrievalPolicyTest {

    private static final long NOW = 10_000_000L;
    private static final int R = DeathRetrievalPolicy.STAND_RADIUS;
    private static final int LO = DeathRetrievalPolicy.STAND_DY_MIN;
    private static final int HI = DeathRetrievalPolicy.STAND_DY_MAX;

    // ── Cause rule ──────────────────────────────────────────────────────────

    @Test
    void lavaAndVoidAreRefused() {
        assertEquals(Verdict.REFUSE_LAVA, DeathRetrievalPolicy.judge("minecraft:lava", null, NOW, NOW));
        assertEquals(Verdict.REFUSE_VOID, DeathRetrievalPolicy.judge("minecraft:out_of_world", null, NOW, NOW));
        // Even when a mob pushed them in.
        assertEquals(Verdict.REFUSE_LAVA, DeathRetrievalPolicy.judge("minecraft:lava", "minecraft:zombie", NOW, NOW));
        assertTrue(Verdict.REFUSE_LAVA.refused());
        assertTrue(Verdict.REFUSE_VOID.refused());
    }

    @Test
    void mobKillsWarnButGo() {
        assertEquals(Verdict.WARN_MOB, DeathRetrievalPolicy.judge("minecraft:mob_attack", "minecraft:zombie", NOW, NOW));
        assertEquals(Verdict.WARN_MOB, DeathRetrievalPolicy.judge("minecraft:mob_projectile", null, NOW, NOW));
        assertEquals(Verdict.WARN_MOB, DeathRetrievalPolicy.judge("minecraft:explosion", "minecraft:creeper", NOW, NOW));
        assertFalse(Verdict.WARN_MOB.refused());
    }

    @Test
    void otherCausesAndUnknownGo() {
        for (String cause : List.of("minecraft:generic_kill", "minecraft:fall", "minecraft:hot_floor",
                "minecraft:drown", "minecraft:on_fire", "unknown")) {
            assertEquals(Verdict.GO, DeathRetrievalPolicy.judge(cause, null, NOW, NOW), cause);
        }
        assertEquals(Verdict.GO, DeathRetrievalPolicy.judge(null, null, null, NOW));
        assertEquals(Verdict.GO, DeathRetrievalPolicy.judge(null, " ", 0L, NOW));
    }

    // ── Age window ──────────────────────────────────────────────────────────

    @Test
    void deathsOlderThanTwentyMinutesAreRefused() {
        long max = DeathRetrievalPolicy.MAX_AGE_MS;
        assertEquals(Verdict.GO, DeathRetrievalPolicy.judge("minecraft:fall", null, NOW - max, NOW));
        assertEquals(Verdict.REFUSE_TOO_OLD, DeathRetrievalPolicy.judge("minecraft:fall", null, NOW - max - 1, NOW));
        assertEquals(Verdict.REFUSE_TOO_OLD, DeathRetrievalPolicy.judge("minecraft:mob_attack", "minecraft:zombie", NOW - max - 1, NOW));
        assertEquals(20L * 60L * 1000L, max);
    }

    @Test
    void unknownOrFutureTimesCountAsFresh() {
        assertEquals(0L, DeathRetrievalPolicy.ageMs(null, NOW));
        assertEquals(0L, DeathRetrievalPolicy.ageMs(0L, NOW));
        assertEquals(0L, DeathRetrievalPolicy.ageMs(NOW + 5_000L, NOW));
        assertEquals(5_000L, DeathRetrievalPolicy.ageMs(NOW - 5_000L, NOW));
    }

    // ── Stand point ─────────────────────────────────────────────────────────

    private static boolean[] grid() {
        return new boolean[DeathRetrievalPolicy.gridSize(R, LO, HI)];
    }

    private static void mark(boolean[] g, int dx, int dy, int dz) {
        g[DeathRetrievalPolicy.gridIndex(dx, dy, dz, R, LO)] = true;
    }

    @Test
    void gridIndexCoversEveryCellOnce() {
        boolean[] seen = grid();
        for (int dy = LO; dy <= HI; dy++)
            for (int dx = -R; dx <= R; dx++)
                for (int dz = -R; dz <= R; dz++) {
                    int i = DeathRetrievalPolicy.gridIndex(dx, dy, dz, R, LO);
                    assertFalse(seen[i]);
                    seen[i] = true;
                }
        for (boolean b : seen) assertTrue(b);
    }

    @Test
    void theDeathSpotItselfWinsWhenSafe() {
        boolean[] g = grid();
        mark(g, 0, 0, 0);
        mark(g, 1, 0, 0);
        assertEquals(new Offset(0, 0, 0), DeathRetrievalPolicy.chooseStandPoint(g, R, LO, HI));
    }

    @Test
    void sameLevelBeatsAStepUpAtTheSameDistance() {
        boolean[] g = grid();
        mark(g, 0, 1, 0);   // score 2
        mark(g, 1, 0, 0);   // score 1
        assertEquals(new Offset(1, 0, 0), DeathRetrievalPolicy.chooseStandPoint(g, R, LO, HI));
        boolean[] g2 = grid();
        mark(g2, 0, 1, 0);  // score 2
        mark(g2, 2, 0, 0);  // score 4
        assertEquals(new Offset(0, 1, 0), DeathRetrievalPolicy.chooseStandPoint(g2, R, LO, HI));
    }

    @Test
    void nearestSafeCellIsChosenFromAFarRing() {
        boolean[] g = grid();
        mark(g, 3, -2, 3);
        mark(g, -3, 0, 2);
        assertEquals(new Offset(-3, 0, 2), DeathRetrievalPolicy.chooseStandPoint(g, R, LO, HI));
    }

    @Test
    void noSafeCellOrWrongGridGivesNull() {
        assertNull(DeathRetrievalPolicy.chooseStandPoint(grid(), R, LO, HI));
        assertNull(DeathRetrievalPolicy.chooseStandPoint(new boolean[3], R, LO, HI));
        assertNull(DeathRetrievalPolicy.chooseStandPoint(null, R, LO, HI));
    }

    // ── Action parsing ──────────────────────────────────────────────────────

    @Test
    void actionTypesRoundTrip() {
        assertEquals("retrieve:sender", DeathRetrievalPolicy.actionType(ReturnMode.SENDER));
        assertEquals("retrieve:stay", DeathRetrievalPolicy.actionType(ReturnMode.STAY));
        for (ReturnMode m : ReturnMode.values()) {
            assertEquals(Optional.of(m), DeathRetrievalPolicy.parseAction(DeathRetrievalPolicy.actionType(m)));
        }
    }

    @Test
    void otherActionTypesAreNotRetrievals() {
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseAction("withdraw:player"));
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseAction("skill_resume:bot skill woodcut"));
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseAction("retrieve:"));
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseAction("retrieve:home"));
        assertEquals(Optional.empty(), DeathRetrievalPolicy.parseAction(null));
    }

    @Test
    void meMeansTheSender() {
        assertTrue(DeathRetrievalPolicy.isMe(null));
        assertTrue(DeathRetrievalPolicy.isMe(""));
        assertTrue(DeathRetrievalPolicy.isMe("me"));
        assertTrue(DeathRetrievalPolicy.isMe("ME"));
        assertFalse(DeathRetrievalPolicy.isMe("Jake"));
    }

    // ── Text ────────────────────────────────────────────────────────────────

    @Test
    void labels() {
        assertEquals("the Nether", DeathRetrievalPolicy.dimensionLabel("minecraft:the_nether"));
        assertEquals("the Overworld", DeathRetrievalPolicy.dimensionLabel("minecraft:overworld"));
        assertEquals("mymod:moon", DeathRetrievalPolicy.dimensionLabel("mymod:moon"));
        assertEquals("cave spider", DeathRetrievalPolicy.mobLabel("minecraft:cave_spider"));
        assertEquals("a mob", DeathRetrievalPolicy.mobLabel(null));
    }

    @Test
    void keyLinesReadPlainly() {
        assertEquals("No point going: your death was in lava, so the items burned up.",
                DeathRetrievalPolicy.refusal(Verdict.REFUSE_LAVA, "your", 0));
        assertEquals("No point going: Jake's death was 25 minutes ago. Dropped items vanish after about 5 minutes while the area is loaded.",
                DeathRetrievalPolicy.refusal(Verdict.REFUSE_TOO_OLD, "Jake's", 25L * 60_000L));
        assertEquals("Bob is heading to your death spot at 1, 64, -3 in the Nether. Items on the ground last about 5 minutes while the area is loaded.",
                DeathRetrievalPolicy.departing("Bob", "your", 1, 64, -3, "minecraft:the_nether"));
        assertEquals("Careful: a zombie did that, and Bob may run into it.",
                DeathRetrievalPolicy.mobWarning("Bob", "minecraft:zombie"));
        assertEquals("Bob picked up 1 item at the death spot.", DeathRetrievalPolicy.result("Bob", 1));
        assertEquals("Bob picked up 12 items at the death spot.", DeathRetrievalPolicy.result("Bob", 12));
        assertEquals("Bob found nothing to pick up at the death spot.", DeathRetrievalPolicy.result("Bob", 0));
        assertEquals("Bob is coming back to you (about 9s).", DeathRetrievalPolicy.returning("Bob", 9));
    }

    @Test
    void sweepReportRelaysTheSweepsOwnCount() {
        assertEquals("Bob at the death spot: Picked up 12 items.",
                DeathRetrievalPolicy.sweepReport("Bob", "Picked up 12 items.", 0));
        assertEquals("Bob at the death spot: My inventory is full, so I stopped after picking up 3 items.",
                DeathRetrievalPolicy.sweepReport("Bob", "My inventory is full, so I stopped after picking up 3 items.", 0));
        assertEquals("Bob picked up 4 items at the death spot.", DeathRetrievalPolicy.sweepReport("Bob", " ", 4));
        assertEquals("Bob found nothing to pick up at the death spot.", DeathRetrievalPolicy.sweepReport("Bob", null, 0));
    }

    @Test
    void noPlayerLineHasAnEmDashOrSemicolon() {
        List<String> lines = List.of(
                DeathRetrievalPolicy.usage(),
                DeathRetrievalPolicy.consoleNeedsDeadBot(),
                DeathRetrievalPolicy.noRecordMe(),
                DeathRetrievalPolicy.noRecordBot("Jake"),
                DeathRetrievalPolicy.notOwner("Bob"),
                DeathRetrievalPolicy.notOwnerOfDead("Jake"),
                DeathRetrievalPolicy.alreadyTraveling("Bob"),
                DeathRetrievalPolicy.refusal(Verdict.REFUSE_LAVA, "your", 0),
                DeathRetrievalPolicy.refusal(Verdict.REFUSE_VOID, "your", 0),
                DeathRetrievalPolicy.refusal(Verdict.REFUSE_TOO_OLD, "your", 3_600_000L),
                DeathRetrievalPolicy.worldUnavailable("mymod:moon"),
                DeathRetrievalPolicy.noStandSpot("Bob"),
                DeathRetrievalPolicy.cannotSetOff("Bob"),
                DeathRetrievalPolicy.departing("Bob", "your", 0, 0, 0, "minecraft:overworld"),
                DeathRetrievalPolicy.mobWarning("Bob", null),
                DeathRetrievalPolicy.arrivedAtFallback("Bob"),
                DeathRetrievalPolicy.sweepFailed("Bob", "Another skill is already running."),
                DeathRetrievalPolicy.result("Bob", 3),
                DeathRetrievalPolicy.returning("Bob", 0),
                DeathRetrievalPolicy.stayingOffline("Bob"),
                DeathRetrievalPolicy.stayingCannotTravel("Bob"),
                DeathRetrievalPolicy.stayingSentFromConsole("Bob"));
        for (String line : lines) {
            assertFalse(line.contains("—"), line);
            assertFalse(line.contains(";"), line);
            assertFalse(line.isBlank());
        }
    }
}
