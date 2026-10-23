package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HobbyBackoffPolicyTest {

    private static final long NOW = 10_000L;

    private static HobbyBackoffPolicy.Attempt failure(int consecutiveFailures) {
        return new HobbyBackoffPolicy.Attempt("woodcut", false, false, consecutiveFailures, NOW);
    }

    @Test
    void firstFailureBacksOffExactlySixtySeconds() {
        assertEquals(NOW + 1200L, HobbyBackoffPolicy.nextAllowedTick(failure(0)));
        assertEquals(1200L, HobbyBackoffPolicy.BASE_FAILURE_TICKS);
    }

    @Test
    void failuresDoubleUntilTheCap() {
        assertEquals(NOW + 1200L, HobbyBackoffPolicy.nextAllowedTick(failure(0)));
        assertEquals(NOW + 2400L, HobbyBackoffPolicy.nextAllowedTick(failure(1)));
        assertEquals(NOW + 4800L, HobbyBackoffPolicy.nextAllowedTick(failure(2)));
        assertEquals(NOW + 9600L, HobbyBackoffPolicy.nextAllowedTick(failure(3)));
        // 1200 << 4 == 19200, clamped to the 10-minute ceiling.
        assertEquals(NOW + HobbyBackoffPolicy.MAX_BACKOFF_TICKS, HobbyBackoffPolicy.nextAllowedTick(failure(4)));
    }

    @Test
    void backoffIsMonotonicUpToTheCap() {
        long previous = NOW;
        for (int failures = 0; failures <= 4; failures++) {
            long next = HobbyBackoffPolicy.nextAllowedTick(failure(failures));
            assertTrue(next >= previous, "backoff must not shrink at failures=" + failures);
            previous = next;
        }
    }

    @Test
    void capIsNeverExceededAndNeverOverflows() {
        int[] hugeCounts = {5, 16, 31, 32, 53, 64, 1_000, 1_000_000, Integer.MAX_VALUE};
        for (int failures : hugeCounts) {
            long next = HobbyBackoffPolicy.nextAllowedTick(failure(failures));
            // Never negative (the overflow bug an unclamped shift would introduce) …
            assertTrue(next > NOW, "backoff overflowed or vanished at failures=" + failures);
            // … and never longer than the documented ceiling.
            assertEquals(NOW + HobbyBackoffPolicy.MAX_BACKOFF_TICKS, next,
                    "backoff exceeded the cap at failures=" + failures);
        }
    }

    @Test
    void negativeFailureCountIsTreatedAsTheFirstFailure() {
        assertEquals(NOW + HobbyBackoffPolicy.BASE_FAILURE_TICKS,
                HobbyBackoffPolicy.nextAllowedTick(failure(-3)));
    }

    @Test
    void successNeverBacksOff() {
        HobbyBackoffPolicy.Attempt ok = new HobbyBackoffPolicy.Attempt("woodcut", true, false, 7, NOW);
        assertEquals(NOW, HobbyBackoffPolicy.nextAllowedTick(ok));
    }

    @Test
    void abortNeverBacksOff() {
        HobbyBackoffPolicy.Attempt aborted = new HobbyBackoffPolicy.Attempt("woodcut", false, true, 7, NOW);
        assertEquals(NOW, HobbyBackoffPolicy.nextAllowedTick(aborted));
    }

    @Test
    void nullAttemptIsHarmless() {
        assertEquals(0L, HobbyBackoffPolicy.nextAllowedTick(null));
    }

    @Test
    void successResetsTheFailureCount() {
        assertEquals(0, HobbyBackoffPolicy.nextFailureCount(0, true));
        assertEquals(0, HobbyBackoffPolicy.nextFailureCount(9, true));
        assertEquals(0, HobbyBackoffPolicy.nextFailureCount(9, true, false));
    }

    @Test
    void failureIncrementsTheFailureCount() {
        assertEquals(1, HobbyBackoffPolicy.nextFailureCount(0, false));
        assertEquals(2, HobbyBackoffPolicy.nextFailureCount(1, false));
        assertEquals(4, HobbyBackoffPolicy.nextFailureCount(3, false, false));
    }

    @Test
    void abortDoesNotIncrementTheFailureCount() {
        assertEquals(3, HobbyBackoffPolicy.nextFailureCount(3, false, true));
        assertEquals(0, HobbyBackoffPolicy.nextFailureCount(0, false, true));
        // An abort on an otherwise-successful run still leaves the counter alone.
        assertEquals(5, HobbyBackoffPolicy.nextFailureCount(5, true, true));
    }

    @Test
    void failureCountSaturatesInsteadOfOverflowing() {
        assertEquals(HobbyBackoffPolicy.MAX_FAILURE_COUNT,
                HobbyBackoffPolicy.nextFailureCount(HobbyBackoffPolicy.MAX_FAILURE_COUNT, false));
        assertEquals(HobbyBackoffPolicy.MAX_FAILURE_COUNT,
                HobbyBackoffPolicy.nextFailureCount(Integer.MAX_VALUE, false));
        assertEquals(1, HobbyBackoffPolicy.nextFailureCount(-4, false));
    }

    @Test
    void repeatedFailuresWalkTheSequenceThenHoldAtTheCap() {
        // The storm scenario end to end: a woodcut that fails deterministically every time.
        int count = 0;
        long[] expected = {1200L, 2400L, 4800L, 9600L, 12_000L, 12_000L, 12_000L};
        for (int i = 0; i < expected.length; i++) {
            long next = HobbyBackoffPolicy.nextAllowedTick(failure(count));
            assertEquals(NOW + expected[i], next, "unexpected backoff at attempt " + (i + 1));
            count = HobbyBackoffPolicy.nextFailureCount(count, false, false);
        }
        assertEquals(expected.length, count);
    }

    @Test
    void describeNamesTheHobbyAndRemainingSeconds() {
        String text = HobbyBackoffPolicy.describe("woodcut", 2, 1200L);
        assertTrue(text.contains("woodcut"), text);
        assertTrue(text.contains("failures=2"), text);
        assertTrue(text.contains("60s"), text);
    }

    // --- key normalisation (1.1.216 review finding 8) ---

    @Test
    void theSkillPrefixNormalizesToTheSameKeyAsTheBareName() {
        // TaskService.beginSkill builds ticket names as "skill:" + skillName. The read site
        // stripped that prefix while the write site did not, so a caller passing a ticket name
        // would have stored "skill:woodcut" against a gate reading "woodcut" and silently
        // disabled the backoff. One normaliser, both sites.
        assertEquals("woodcut", HobbyBackoffPolicy.normalizeHobbyKey("skill:woodcut"));
        assertEquals("woodcut", HobbyBackoffPolicy.normalizeHobbyKey("woodcut"));
        assertEquals(HobbyBackoffPolicy.normalizeHobbyKey("woodcut"),
                HobbyBackoffPolicy.normalizeHobbyKey("skill:woodcut"));
    }

    @Test
    void normalizationTrimsAndLowerCasesAroundThePrefix() {
        assertEquals("woodcut", HobbyBackoffPolicy.normalizeHobbyKey("  SKILL:Woodcut  "));
        assertEquals("woodcut", HobbyBackoffPolicy.normalizeHobbyKey("Skill: woodcut"));
        assertEquals("collect_dirt", HobbyBackoffPolicy.normalizeHobbyKey("skill:collect_dirt"));
    }

    @Test
    void normalizationHandlesEmptyAndNullNames() {
        assertEquals("", HobbyBackoffPolicy.normalizeHobbyKey(null));
        assertEquals("", HobbyBackoffPolicy.normalizeHobbyKey("   "));
        // A bare prefix carries no hobby name and must not be mistaken for one.
        assertEquals("", HobbyBackoffPolicy.normalizeHobbyKey("skill:"));
    }

    // --- availability probe (1.1.216 regression fix) ---
    //
    // The scheduler's flat-cooldown early return happens before anything re-reads the world, so a
    // bot deep in a backoff needs a throttled probe to notice an axe arriving in a nearby chest.
    // The reset trigger it replaces — a coarse accessible-supply signature — was position
    // dependent and flipped on movement alone, which wiped the backoff and re-opened the storm.

    @Test
    void axeReadinessDistinguishesInputsFromAHeldTool() {
        assertEquals(0, HobbyBackoffPolicy.axeReadiness(false, false));
        assertEquals(1, HobbyBackoffPolicy.axeReadiness(false, true));
        assertEquals(2, HobbyBackoffPolicy.axeReadiness(true, false));
        assertEquals(2, HobbyBackoffPolicy.axeReadiness(true, true));
    }

    @Test
    void onlyAnImprovementBeyondTheFailedAttemptResetsBackoff() {
        for (int failed = 0; failed <= 2; failed++) {
            for (int current = 0; current <= 2; current++) {
                assertEquals(current > failed, HobbyBackoffPolicy.availabilityImproved(failed, current));
            }
        }
        assertFalse(HobbyBackoffPolicy.availabilityImproved(null, 2));
    }

    @Test
    void losingAndRegainingTheSameToolDoesNotInvalidateAFailureWithThatTool() {
        int failedReadiness = HobbyBackoffPolicy.axeReadiness(true, false);
        assertFalse(HobbyBackoffPolicy.availabilityImproved(failedReadiness, 0));
        assertFalse(HobbyBackoffPolicy.availabilityImproved(failedReadiness, 1));
        assertFalse(HobbyBackoffPolicy.availabilityImproved(failedReadiness, 2));
    }

    @Test
    void aRetryFailureWithImprovedToolsRequiresAnotherImprovement() {
        assertTrue(HobbyBackoffPolicy.availabilityImproved(0, 1));
        assertFalse(HobbyBackoffPolicy.availabilityImproved(1, 1));
        assertTrue(HobbyBackoffPolicy.availabilityImproved(1, 2));
        assertFalse(HobbyBackoffPolicy.availabilityImproved(2, 2));
    }

    @Test
    void theProbeOnlyRunsWhileABackoffIsRunning() {
        // A plain in-flight cooldown does not justify a container scan.
        assertFalse(HobbyBackoffPolicy.shouldProbeAvailability(false, null, NOW,
                HobbyBackoffPolicy.AVAILABILITY_PROBE_TICKS));
        assertFalse(HobbyBackoffPolicy.shouldProbeAvailability(false, NOW - 10_000L, NOW,
                HobbyBackoffPolicy.AVAILABILITY_PROBE_TICKS));
    }

    @Test
    void aBotThatHasNeverProbedMayProbeImmediately() {
        assertTrue(HobbyBackoffPolicy.shouldProbeAvailability(true, null, NOW,
                HobbyBackoffPolicy.AVAILABILITY_PROBE_TICKS));
    }

    @Test
    void theThrottleAdmitsAtMostOneProbePerHundredTicks() {
        assertEquals(100L, HobbyBackoffPolicy.AVAILABILITY_PROBE_TICKS);
        long interval = HobbyBackoffPolicy.AVAILABILITY_PROBE_TICKS;
        // Same tick, one tick later, one tick short of the window: all refused.
        assertFalse(HobbyBackoffPolicy.shouldProbeAvailability(true, NOW, NOW, interval));
        assertFalse(HobbyBackoffPolicy.shouldProbeAvailability(true, NOW, NOW + 1L, interval));
        assertFalse(HobbyBackoffPolicy.shouldProbeAvailability(true, NOW, NOW + interval - 1L, interval));
        // Exactly the window: admitted.
        assertTrue(HobbyBackoffPolicy.shouldProbeAvailability(true, NOW, NOW + interval, interval));
        assertTrue(HobbyBackoffPolicy.shouldProbeAvailability(true, NOW, NOW + interval + 1L, interval));
    }

    @Test
    void theThrottleAdmitsExactlyOneProbePerWindowOverALongBackoff() {
        // Walk every tick of a full 10-minute backoff and count the admitted probes: one per 100
        // ticks, never the per-tick scan storm the throttle exists to prevent.
        long interval = HobbyBackoffPolicy.AVAILABILITY_PROBE_TICKS;
        Long last = null;
        int probes = 0;
        for (long tick = NOW; tick < NOW + HobbyBackoffPolicy.MAX_BACKOFF_TICKS; tick++) {
            if (HobbyBackoffPolicy.shouldProbeAvailability(true, last, tick, interval)) {
                probes++;
                last = tick;
            }
        }
        assertEquals((int) (HobbyBackoffPolicy.MAX_BACKOFF_TICKS / interval), probes);
    }

    @Test
    void aRestartedTickCounterDoesNotStrandTheProbe() {
        // Integrated-server world reload: server ticks restart, so a recorded probe can sit in the
        // future. Probe now rather than waiting for the counter to climb back.
        assertTrue(HobbyBackoffPolicy.shouldProbeAvailability(true, NOW + 5_000L, NOW,
                HobbyBackoffPolicy.AVAILABILITY_PROBE_TICKS));
    }

    @Test
    void aNonPositiveIntervalStillProbesAtMostOncePerTick() {
        assertTrue(HobbyBackoffPolicy.shouldProbeAvailability(true, NOW - 1L, NOW, 0L));
        assertTrue(HobbyBackoffPolicy.shouldProbeAvailability(true, NOW, NOW, -5L));
    }

    @Test
    void onlyALeadingPrefixIsStripped() {
        // "skill" is not a prefix, and an embedded one is part of the name.
        assertEquals("skillet", HobbyBackoffPolicy.normalizeHobbyKey("skillet"));
        assertEquals("woodcut:skill:x", HobbyBackoffPolicy.normalizeHobbyKey("woodcut:skill:x"));
    }
}
