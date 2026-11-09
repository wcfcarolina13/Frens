package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sandbox 2026-11-09: {@code /bot come} toward a player on the far rim of a ravine. The planner
 * found no route, follow fell back to direct pursuit, and the follow drop guard exempted the drop
 * because the target was grounded and level with the bot. The exemption now also asks the
 * blind-walk guard about the line itself.
 */
class FollowRavineEdgeGuardTest {

    /** Come goal on the far rim, level with the bot: what {@link DropGuardExemptionPolicy#exempt} saw. */
    private static boolean levelGroundedTargetExempt() {
        // targetPos is the goal block centre, so stepDown is -0.5 on level ground.
        return DropGuardExemptionPolicy.exempt(false, -0.5, true, 101, 0, 120, 0);
    }

    private static BlindWalkGuardPolicy.Verdict verdict(BlindWalkGuardPolicy.CellFact... cells) {
        return BlindWalkGuardPolicy.decide(List.of(cells)).verdict();
    }

    @Test
    void theOldExemptionLetTheRavineThrough() {
        assertTrue(levelGroundedTargetExempt(), "precondition: the target-only exemption passed the ravine");
    }

    @Test
    void ravineEdgeIsNoLongerExempt() {
        BlindWalkGuardPolicy.Verdict v = verdict(BlindWalkGuardPolicy.CellFact.drop(5),
                BlindWalkGuardPolicy.CellFact.drop(5), BlindWalkGuardPolicy.CellFact.drop(5));
        assertEquals(BlindWalkGuardPolicy.Verdict.REFUSE, v);
        assertFalse(DropGuardExemptionPolicy.exemptAfterBlindLine(levelGroundedTargetExempt(), v));
    }

    @Test
    void ravineOneStepFurtherOutStillRefuses() {
        BlindWalkGuardPolicy.Verdict v = verdict(BlindWalkGuardPolicy.CellFact.flat(),
                BlindWalkGuardPolicy.CellFact.drop(5));
        assertFalse(DropGuardExemptionPolicy.exemptAfterBlindLine(true, v));
    }

    @Test
    void oneToThreeBlockStepDownsStayExempt() {
        for (int drop = 1; drop <= BlindWalkGuardPolicy.MAX_SAFE_DROP; drop++) {
            BlindWalkGuardPolicy.Verdict v = verdict(BlindWalkGuardPolicy.CellFact.drop(drop),
                    BlindWalkGuardPolicy.CellFact.flat(), BlindWalkGuardPolicy.CellFact.flat());
            assertTrue(DropGuardExemptionPolicy.exemptAfterBlindLine(true, v), "drop " + drop);
        }
    }

    @Test
    void flatGroundStaysExempt() {
        BlindWalkGuardPolicy.Verdict v = verdict(BlindWalkGuardPolicy.CellFact.flat(),
                BlindWalkGuardPolicy.CellFact.flat());
        assertTrue(DropGuardExemptionPolicy.exemptAfterBlindLine(true, v));
    }

    @Test
    void deepDropIntoShallowWaterStaysExempt() {
        BlindWalkGuardPolicy.Verdict v = verdict(BlindWalkGuardPolicy.CellFact.dropIntoWater(5, false),
                BlindWalkGuardPolicy.CellFact.flat());
        assertTrue(DropGuardExemptionPolicy.exemptAfterBlindLine(true, v));
    }

    @Test
    void deepDropIntoDeepWaterFollowsTheBlindWalkRule() {
        BlindWalkGuardPolicy.Verdict v = verdict(BlindWalkGuardPolicy.CellFact.dropIntoWater(5, true));
        assertFalse(DropGuardExemptionPolicy.exemptAfterBlindLine(true, v));
    }

    @Test
    void hazardBeyondTheRefuseHorizonDoesNotBlockThisStep() {
        BlindWalkGuardPolicy.Verdict v = verdict(BlindWalkGuardPolicy.CellFact.drop(3),
                BlindWalkGuardPolicy.CellFact.flat(), BlindWalkGuardPolicy.CellFact.flat(),
                BlindWalkGuardPolicy.CellFact.flat(), BlindWalkGuardPolicy.CellFact.drop(5));
        assertEquals(BlindWalkGuardPolicy.Verdict.ALLOW_UP_TO, v);
        assertTrue(DropGuardExemptionPolicy.exemptAfterBlindLine(true, v));
    }

    @Test
    void noSampleKeepsTheOldAnswerAndNeverCreatesAnExemption() {
        assertTrue(DropGuardExemptionPolicy.exemptAfterBlindLine(true, null));
        assertFalse(DropGuardExemptionPolicy.exemptAfterBlindLine(false, null));
        assertFalse(DropGuardExemptionPolicy.exemptAfterBlindLine(false, BlindWalkGuardPolicy.Verdict.ALLOW));
    }

    @Test
    void dropGuardChecksTheLineBeforeHonouringTheExemption() throws IOException {
        String src = Files.readString(Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/FollowMovementService.java"));
        int start = src.indexOf("private static boolean tryDropoffGuard(ServerPlayerEntity bot, Vec3d targetPos, boolean stayOutOfHoles)");
        int end = src.indexOf("selectDropoffBypassCandidate(", start);
        assertTrue(start >= 0 && end > start, "tryDropoffGuard and its bypass not found");
        String body = src.substring(start, end);
        int exempt = body.indexOf("DropGuardExemptionPolicy.exempt(");
        int sample = body.indexOf("MovementService.blindLineDecisionOnServerThread(world, bot, targetBlock)");
        int check = body.indexOf("DropGuardExemptionPolicy.exemptAfterBlindLine(");
        int exemptReturn = body.indexOf("return false;", check);
        assertTrue(exempt >= 0 && sample > exempt, "line sampled inside the exemption branch");
        assertTrue(check > sample && exemptReturn > check, "the exempt return sits behind the line check");
        assertEquals(-1, body.substring(exempt, sample).indexOf("return false;"),
                "no exempt return before the line check");

        String movement = Files.readString(Path.of(
                "src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java"));
        int helper = movement.indexOf("static BlindWalkGuardPolicy.Decision blindLineDecisionOnServerThread(");
        int helperEnd = movement.indexOf("\n    }\n", helper);
        assertTrue(helper >= 0 && helperEnd > helper, "helper not found");
        String helperBody = movement.substring(helper, helperEnd);
        assertTrue(helperBody.contains("sampleBlindLine(world, player, destination, startInWater, cells, facts);")
                        && helperBody.contains("BlindWalkGuardPolicy.decide(facts, startInWater)"),
                "helper reuses the blind-walk sampler and policy");
    }
}
