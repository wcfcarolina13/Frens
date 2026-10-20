package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.MutualAidDefensePolicy.Decision;
import net.wcfcarolina13.GameAI.services.MutualAidDefensePolicy.Step;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutualAidDefensePolicyTest {

    private static final double REACH_SQ = 49.0D;

    // ── step ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void withinReachArrives() {
        Step step = MutualAidDefensePolicy.step(49.0D, REACH_SQ, 100.0D, 3, 10L, 48L);
        assertEquals(Decision.ARRIVED, step.decision());
        assertEquals(49.0D, step.bestDistSq());
        assertEquals(0, step.noProgressTicks());
    }

    @Test
    void arrivalWinsOverAPassedDeadlineAndAStall() {
        Step step = MutualAidDefensePolicy.step(20.0D, REACH_SQ, 20.0D, 40, 100L, 48L);
        assertEquals(Decision.ARRIVED, step.decision());
    }

    @Test
    void gettingCloserContinuesAndResetsTheStall() {
        Step step = MutualAidDefensePolicy.step(90.0D, REACH_SQ, 100.0D, 9, 10L, 48L);
        assertEquals(Decision.CONTINUE, step.decision());
        assertEquals(90.0D, step.bestDistSq());
        assertEquals(0, step.noProgressTicks());
    }

    @Test
    void aStepWithinTheEpsilonIsNoProgress() {
        Step step = MutualAidDefensePolicy.step(99.96D, REACH_SQ, 100.0D, 2, 10L, 48L);
        assertEquals(Decision.CONTINUE, step.decision());
        assertEquals(100.0D, step.bestDistSq(), "the best distance is kept");
        assertEquals(3, step.noProgressTicks());
    }

    @Test
    void stallingForTheLimitAborts() {
        int limit = MutualAidDefensePolicy.NO_PROGRESS_LIMIT_TICKS;
        assertEquals(Decision.CONTINUE,
                MutualAidDefensePolicy.step(100.0D, REACH_SQ, 100.0D, limit - 2, 10L, 48L).decision());
        Step last = MutualAidDefensePolicy.step(100.0D, REACH_SQ, 100.0D, limit - 1, 10L, 48L);
        assertEquals(Decision.ABORT, last.decision());
        assertEquals(limit, last.noProgressTicks());
    }

    @Test
    void movingAwayCountsAsNoProgress() {
        Step step = MutualAidDefensePolicy.step(120.0D, REACH_SQ, 100.0D, 0, 10L, 48L);
        assertEquals(Decision.CONTINUE, step.decision());
        assertEquals(100.0D, step.bestDistSq());
        assertEquals(1, step.noProgressTicks());
    }

    @Test
    void theDeadlineAbortsEvenWhileGettingCloser() {
        assertEquals(Decision.CONTINUE, MutualAidDefensePolicy.step(90.0D, REACH_SQ, 100.0D, 0, 47L, 48L).decision());
        assertEquals(Decision.ABORT, MutualAidDefensePolicy.step(90.0D, REACH_SQ, 100.0D, 0, 48L, 48L).decision());
    }

    @Test
    void aSteadyApproachArrivesBeforeTheHostileDeadline() {
        // 20 blocks out, closing 0.3 blocks a tick: a walk the old 2.4 s nudge budget allowed.
        long start = 1_000L;
        long deadline = start + MutualAidDefensePolicy.HOSTILE_DEADLINE_TICKS;
        double dist = 20.0D;
        double best = dist * dist;
        int stalled = 0;
        Decision decision = Decision.CONTINUE;
        long tick = start;
        while (decision == Decision.CONTINUE) {
            tick++;
            dist -= 0.3D;
            Step step = MutualAidDefensePolicy.step(dist * dist, REACH_SQ, best, stalled, tick, deadline);
            decision = step.decision();
            best = step.bestDistSq();
            stalled = step.noProgressTicks();
        }
        assertEquals(Decision.ARRIVED, decision);
        assertTrue(tick < deadline);
    }

    // ── budgets ──────────────────────────────────────────────────────────────────────────────

    @Test
    void budgetsMatchTheOldNudgeDeadlines() {
        assertEquals(2_400L / 50L, MutualAidDefensePolicy.HOSTILE_DEADLINE_TICKS);
        assertEquals(1_600L / 50L, MutualAidDefensePolicy.ALLY_DEADLINE_TICKS);
        assertEquals(3_200L / 50L, MutualAidDefensePolicy.SEEK_FOOD_DEADLINE_TICKS);
        assertEquals(14, MutualAidDefensePolicy.NO_PROGRESS_LIMIT_TICKS);
    }

    @Test
    void sprintAndImpulseMatchTheOldNudge() {
        assertFalse(MutualAidDefensePolicy.shouldSprint(9.0D));
        assertTrue(MutualAidDefensePolicy.shouldSprint(9.01D));
        assertEquals(0.18D, MutualAidDefensePolicy.impulse(16.0D, 0.18D));
        assertEquals(0.18D, MutualAidDefensePolicy.impulse(16.01D, 0.18D));
        assertEquals(0.16D, MutualAidDefensePolicy.impulse(30.0D, 0.10D));
        assertEquals(0.20D, MutualAidDefensePolicy.impulse(30.0D, 0.20D));
    }

    // ── source guards ────────────────────────────────────────────────────────────────────────

    @Test
    void theDefenseTickPathNeverBlocks() throws IOException {
        String source = mutualAidSource();
        for (String signature : new String[]{
                "void processDefensiveSupport(",
                "void respondToDefenseRequest(",
                "void advanceEngagements(",
                "void advanceEngagement(",
                "void stepToward(",
                "void endEngagement("}) {
            String body = bodyOf(source, signature);
            assertFalse(body.contains("nudgeTowardUntilClose("), signature + " must not nudge");
            assertFalse(body.contains("approachBot("), signature + " must not call the blocking approach");
            assertFalse(body.contains("Thread.sleep("), signature + " must not sleep");
        }
        String process = bodyOf(source, "void processDefensiveSupport(");
        int advance = process.indexOf("advanceEngagements(server, nowTick)");
        int loop = process.indexOf("for (ServerPlayerEntity defender");
        assertTrue(advance >= 0 && advance < loop, "engagements under way step before new ones start");
        assertTrue(process.contains("ENGAGEMENTS.containsKey(defender.getUuid())"), "an engaged defender is skipped");
    }

    @Test
    void everyEngagementEndStopsTheBot() throws IOException {
        String end = bodyOf(mutualAidSource(), "void endEngagement(");
        int remove = end.indexOf("ENGAGEMENTS.remove(bot.getUuid())");
        int stop = end.indexOf("BotActions.stop(bot)");
        assertTrue(remove >= 0 && stop > remove, "the engagement is removed, then the bot stopped");
        assertTrue(stop < end.indexOf("switch"), "the bot stops before any arrival action");
        String advance = bodyOf(mutualAidSource(), "void advanceEngagement(");
        assertEquals(3, count(advance, "endEngagement("), "a lost target or ineligible bot, arrival and abort all end the same way");
    }

    @Test
    void seekingFoodOnTheServerThreadStartsAnEngagementInsteadOfWalking() throws IOException {
        String seek = bodyOf(mutualAidSource(), "public static boolean trySeekFoodFromNearbyBot(");
        assertFalse(seek.contains("nudgeTowardUntilClose("));
        int onThread = seek.indexOf("if (onServerThread)");
        int start = seek.indexOf("startEngagement(seeker, EngagementKind.SEEK_FOOD");
        int approach = seek.indexOf("approachBot(");
        assertTrue(seek.contains("boolean onServerThread = server.isOnThread();"));
        assertTrue(onThread >= 0 && start > onThread && approach > start,
                "the blocking approach is only reached off the server thread");
        assertTrue(seek.substring(onThread, approach + "approachBot(".length()).contains("} else if (!approachBot("),
                "the blocking approach is the else branch of the server-thread check");
    }

    @Test
    void engagementsArePrunedAndBlockOtherAid() throws IOException {
        String source = mutualAidSource();
        assertTrue(source.contains("pruneMap(ENGAGEMENTS, knownBots);"));
        assertTrue(bodyOf(source, "boolean isPendingParticipant(").contains("ENGAGEMENTS.containsKey(botId)"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    private static String mutualAidSource() throws IOException {
        String relative = "src/main/java/net/wcfcarolina13/GameAI/services/BotMutualAidService.java";
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path at = dir; at != null; at = at.getParent()) {
            Path candidate = at.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException(relative + " not found above " + dir);
    }

    /** The body, braces included, of the one declaration containing {@code signature}. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, signature + " not found");
        assertEquals(-1, source.indexOf(signature, at + 1), signature + " declared more than once");
        int open = source.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError(signature + " body not closed");
    }
}
