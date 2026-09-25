package net.wcfcarolina13.GameAI.services.dialogue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for the cross-lane speech floor. No Minecraft types. */
class SpeechFloorPolicyTest {

    private static final long NOW = 1_000_000L;

    @Test
    void eachSourceCarriesItsOwnFloorDuration() {
        assertEquals(4_000L, SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        assertEquals(6_000L, SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
        assertEquals(20_000L, SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_END));
        assertEquals(SpeechFloorPolicy.SCRIPTED_LINE_FLOOR_MS,
                SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        assertEquals(SpeechFloorPolicy.SCENE_LINE_FLOOR_MS,
                SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
        assertEquals(SpeechFloorPolicy.POST_SCENE_QUIET_MS,
                SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_END));
        assertEquals(30_000L, SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_PENDING));
        assertEquals(SpeechFloorPolicy.SCENE_PENDING_FLOOR_MS,
                SpeechFloorPolicy.floorDurationMs(SpeechFloorPolicy.Source.SOUL_SCENE_PENDING));
    }

    @Test
    void nullSourceArmsNothing() {
        assertEquals(0L, SpeechFloorPolicy.floorDurationMs(null));
        assertEquals(NOW, SpeechFloorPolicy.armedUntil(NOW, 0L, null));
    }

    @Test
    void neverArmedFloorIsOpen() {
        assertTrue(SpeechFloorPolicy.isOpen(NOW, 0L));
        assertTrue(SpeechFloorPolicy.isOpen(NOW, -5L));
        assertEquals(0L, SpeechFloorPolicy.remainingMs(NOW, 0L));
    }

    @Test
    void isOpenTreatsTheExpiryInstantAsOpen() {
        assertFalse(SpeechFloorPolicy.isOpen(NOW, NOW + 1L));
        assertTrue(SpeechFloorPolicy.isOpen(NOW, NOW));          // boundary: nowMs == busyUntilMs
        assertTrue(SpeechFloorPolicy.isOpen(NOW, NOW - 1L));
    }

    @Test
    void armedUntilNeverShortensALongerExistingFloor() {
        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        assertEquals(NOW + 20_000L, sceneEnd);

        // A scripted line arriving inside the post-scene quiet must not cut it down to 4s.
        long afterScripted = SpeechFloorPolicy.armedUntil(
                NOW + 1_000L, sceneEnd, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertEquals(sceneEnd, afterScripted);

        // Nor may a scene line (6s) shorten it.
        assertEquals(sceneEnd, SpeechFloorPolicy.armedUntil(
                NOW + 1_000L, sceneEnd, SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
    }

    @Test
    void aSceneEndOverridesAShorterScriptedFloor() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertEquals(NOW + 4_000L, scripted);

        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, scripted, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        assertEquals(NOW + 20_000L, sceneEnd);
        assertFalse(SpeechFloorPolicy.isOpen(NOW + 5_000L, sceneEnd));
        assertTrue(SpeechFloorPolicy.isOpen(NOW + 20_000L, sceneEnd));
    }

    @Test
    void aScriptedLineExtendsAnAlmostExpiredFloor() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        long later = NOW + 3_500L;
        long extended = SpeechFloorPolicy.armedUntil(later, scripted, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertEquals(later + 4_000L, extended);
    }

    // --- source-aware preemption (1.1.216 review finding 1) ---

    @Test
    void aSceneOpensThroughAScriptedFloor() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        long midFloor = NOW + 1_000L;

        // The floor really is closed...
        assertFalse(SpeechFloorPolicy.isOpen(midFloor, scripted));
        // ...but a scene preempts scripted flavour, which is what stops the 5s banter evaluation
        // from being starved forever by a steady 4s scripted stream.
        assertTrue(SpeechFloorPolicy.isOpenFor(midFloor, scripted,
                SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
        assertTrue(SpeechFloorPolicy.isOpenFor(midFloor, scripted,
                SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, SpeechFloorPolicy.Source.SOUL_SCENE_END));
    }

    @Test
    void aScriptedRequestDoesNotOpenThroughASceneFloor() {
        long sceneLine = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_LINE);
        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        long midFloor = NOW + 1_000L;

        assertFalse(SpeechFloorPolicy.isOpenFor(midFloor, sceneLine,
                SpeechFloorPolicy.Source.SOUL_SCENE_LINE, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        assertFalse(SpeechFloorPolicy.isOpenFor(midFloor, sceneEnd,
                SpeechFloorPolicy.Source.SOUL_SCENE_END, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        // A scripted request respects a scripted floor too — the preemption is one-directional.
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertFalse(SpeechFloorPolicy.isOpenFor(midFloor, scripted,
                SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
    }

    @Test
    void anExpiredFloorIsOpenToEverySource() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        long after = NOW + 4_000L;
        for (SpeechFloorPolicy.Source requesting : SpeechFloorPolicy.Source.values()) {
            assertTrue(SpeechFloorPolicy.isOpenFor(after, scripted,
                    SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, requesting),
                    "expired floor must be open to " + requesting);
        }
    }

    @Test
    void anUnknownRequestingSourceRespectsEveryFloor() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW + 1_000L, scripted,
                SpeechFloorPolicy.Source.SCRIPTED_AMBIENT, null));
        // ...and a floor with no recorded owner is not preemptible either.
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW + 1_000L, scripted,
                null, SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
    }

    @Test
    void aSceneFloorIsNotShortenedNorRelabelledByALaterScriptedArm() {
        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        SpeechFloorPolicy.Source owner = SpeechFloorPolicy.armedBySource(
                NOW, 0L, null, SpeechFloorPolicy.Source.SOUL_SCENE_END);
        assertEquals(SpeechFloorPolicy.Source.SOUL_SCENE_END, owner);

        long later = NOW + 1_000L;
        long afterScripted = SpeechFloorPolicy.armedUntil(later, sceneEnd, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        SpeechFloorPolicy.Source ownerAfter = SpeechFloorPolicy.armedBySource(
                later, sceneEnd, owner, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);

        // Deadline unchanged (monotonic) AND still labelled as the scene's, so a scripted arm
        // inside a post-scene quiet cannot make that quiet preemptible by the next scene.
        assertEquals(sceneEnd, afterScripted);
        assertEquals(SpeechFloorPolicy.Source.SOUL_SCENE_END, ownerAfter);
        assertFalse(SpeechFloorPolicy.isOpenFor(later, afterScripted, ownerAfter,
                SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
    }

    @Test
    void armedBySourceFollowsWhicheverDeadlineSurvives() {
        // A longer incoming arm takes ownership...
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT);
        assertEquals(SpeechFloorPolicy.Source.SOUL_SCENE_END,
                SpeechFloorPolicy.armedBySource(NOW, scripted, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT,
                        SpeechFloorPolicy.Source.SOUL_SCENE_END));
        // ...and a scripted re-arm that outlasts an almost-expired scene floor owns it, so the
        // label and the deadline can never disagree.
        long sceneLine = SpeechFloorPolicy.armedUntil(NOW, 0L, SpeechFloorPolicy.Source.SOUL_SCENE_LINE);
        long nearlyOver = NOW + 5_000L;
        assertEquals(nearlyOver + 4_000L,
                SpeechFloorPolicy.armedUntil(nearlyOver, sceneLine, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        assertEquals(SpeechFloorPolicy.Source.SCRIPTED_AMBIENT,
                SpeechFloorPolicy.armedBySource(nearlyOver, sceneLine, SpeechFloorPolicy.Source.SOUL_SCENE_LINE,
                        SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        // A null incoming source arms nothing and changes no label.
        assertEquals(SpeechFloorPolicy.Source.SOUL_SCENE_LINE,
                SpeechFloorPolicy.armedBySource(NOW, sceneLine, SpeechFloorPolicy.Source.SOUL_SCENE_LINE, null));
    }

    @Test
    void sceneSourcesAreTheOnesThatPreempt() {
        assertTrue(SpeechFloorPolicy.isSceneSource(SpeechFloorPolicy.Source.SOUL_SCENE_LINE));
        assertTrue(SpeechFloorPolicy.isSceneSource(SpeechFloorPolicy.Source.SOUL_SCENE_END));
        assertFalse(SpeechFloorPolicy.isSceneSource(SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
        // A pending reservation is not speech and never a requester.
        assertFalse(SpeechFloorPolicy.isSceneSource(SpeechFloorPolicy.Source.SOUL_SCENE_PENDING));
        assertFalse(SpeechFloorPolicy.isSceneSource(null));
    }

    @Test
    void remainingMsClampsAtZero() {
        assertEquals(4_000L, SpeechFloorPolicy.remainingMs(NOW, NOW + 4_000L));
        assertEquals(0L, SpeechFloorPolicy.remainingMs(NOW, NOW));
        assertEquals(0L, SpeechFloorPolicy.remainingMs(NOW, NOW - 60_000L));
        assertEquals(0L, SpeechFloorPolicy.remainingMs(NOW, 0L));
    }

    @Test
    void aFloorBeyondTheSanityHorizonIsClamped() {
        // No source can arm past SCENE_PENDING_FLOOR_MS, so anything past the horizon is a clock jump.
        long bogus = NOW + SpeechFloorPolicy.MAX_FLOOR_HORIZON_MS + 1L;
        assertEquals(NOW + SpeechFloorPolicy.POST_SCENE_QUIET_MS, SpeechFloorPolicy.sanitize(NOW, bogus));
        assertEquals(SpeechFloorPolicy.POST_SCENE_QUIET_MS, SpeechFloorPolicy.remainingMs(NOW, bogus));
        assertFalse(SpeechFloorPolicy.isOpen(NOW, bogus));
        // ...and the audience is speakable again one quiet window later, not a year later.
        assertTrue(SpeechFloorPolicy.isOpen(NOW + SpeechFloorPolicy.POST_SCENE_QUIET_MS,
                SpeechFloorPolicy.sanitize(NOW, bogus)));

        // A value exactly at the horizon is plausible enough to keep.
        long atHorizon = NOW + SpeechFloorPolicy.MAX_FLOOR_HORIZON_MS;
        assertEquals(atHorizon, SpeechFloorPolicy.sanitize(NOW, atHorizon));

        // armedUntil sanitises its input too, so a bogus stored floor cannot survive an arm.
        assertEquals(NOW + SpeechFloorPolicy.POST_SCENE_QUIET_MS,
                SpeechFloorPolicy.armedUntil(NOW, bogus, SpeechFloorPolicy.Source.SCRIPTED_AMBIENT));
    }

    // --- pending-scene reservation (1.1.217 gap C1) ---

    private static final SpeechFloorPolicy.Source SCRIPTED = SpeechFloorPolicy.Source.SCRIPTED_AMBIENT;
    private static final SpeechFloorPolicy.Source LINE = SpeechFloorPolicy.Source.SOUL_SCENE_LINE;
    private static final SpeechFloorPolicy.Source END = SpeechFloorPolicy.Source.SOUL_SCENE_END;
    private static final SpeechFloorPolicy.Source PENDING = SpeechFloorPolicy.Source.SOUL_SCENE_PENDING;

    @Test
    void aPendingReservationVetoesScriptedButNotScenes() {
        long pending = SpeechFloorPolicy.pendingAfter(NOW, 0L, PENDING);
        assertEquals(NOW + 30_000L, pending);
        long mid = NOW + 10_000L; // mid-generation: no speech floor at all, only the reservation

        assertFalse(SpeechFloorPolicy.isOpenFor(mid, 0L, null, pending, SCRIPTED));
        // The scene that took the reservation (or any scene) speaks through it: it must never
        // veto its own first line.
        assertTrue(SpeechFloorPolicy.isOpenFor(mid, 0L, null, pending, LINE));
        assertTrue(SpeechFloorPolicy.isOpenFor(mid, 0L, null, pending, END));
        // An unknown requester is not a scene and respects it.
        assertFalse(SpeechFloorPolicy.isOpenFor(mid, 0L, null, pending, null));
    }

    @Test
    void aPendingReservationExpiresAtItsCeilingWithoutARelease() {
        long pending = SpeechFloorPolicy.pendingAfter(NOW, 0L, PENDING);
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW + 29_999L, 0L, null, pending, SCRIPTED));
        assertTrue(SpeechFloorPolicy.isOpenFor(NOW + 30_000L, 0L, null, pending, SCRIPTED));
    }

    @Test
    void aReleasedReservationReopensTheFloorForScripted() {
        // Release == the reservation slot goes back to 0 (SpeechFloorService.releasePending).
        long mid = NOW + 5_000L;
        assertTrue(SpeechFloorPolicy.isOpenFor(mid, 0L, null, 0L, SCRIPTED));
    }

    @Test
    void releasingAReservationNeverErasesTheSpeechFloorUnderIt() {
        // SoulLocalDirector fires without consulting the floor, so a reservation can be taken in
        // the middle of a post-scene quiet. The quiet lives in the speech slot and must survive.
        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, 0L, END);
        long pending = SpeechFloorPolicy.pendingAfter(NOW + 2_000L, 0L, PENDING);
        long afterRelease = 0L;
        long mid = NOW + 5_000L;

        assertFalse(SpeechFloorPolicy.isOpenFor(mid, sceneEnd, END, pending, SCRIPTED));
        assertFalse(SpeechFloorPolicy.isOpenFor(mid, sceneEnd, END, afterRelease, SCRIPTED));
        // ...and the quiet still holds the next scene off: a reservation is not a scene floor.
        assertFalse(SpeechFloorPolicy.isOpenFor(mid, sceneEnd, END, pending, LINE));
        assertFalse(SpeechFloorPolicy.isOpenFor(mid, sceneEnd, END, afterRelease, LINE));
    }

    @Test
    void aScenePreemptsAScriptedFloorAndAReservationTogether() {
        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SCRIPTED);
        long pending = SpeechFloorPolicy.pendingAfter(NOW, 0L, PENDING);
        assertTrue(SpeechFloorPolicy.isOpenFor(NOW + 1_000L, scripted, SCRIPTED, pending, LINE));
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW + 1_000L, scripted, SCRIPTED, pending, SCRIPTED));
    }

    @Test
    void aPendingArmNeverTouchesTheSpeechSlot() {
        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, 0L, END);
        assertEquals(sceneEnd, SpeechFloorPolicy.armedUntil(NOW + 1_000L, sceneEnd, PENDING));
        assertEquals(END, SpeechFloorPolicy.armedBySource(NOW + 1_000L, sceneEnd, END, PENDING));
        // On an empty slot it arms nothing (0 == never armed), so the slot stays open.
        assertEquals(0L, SpeechFloorPolicy.armedUntil(NOW, 0L, PENDING));
        assertEquals(null, SpeechFloorPolicy.armedBySource(NOW, 0L, null, PENDING));
    }

    @Test
    void aSceneArmSupersedesTheReservationAndScriptedDoesNot() {
        long pending = SpeechFloorPolicy.pendingAfter(NOW, 0L, PENDING);
        long firstLine = NOW + 12_000L;
        assertEquals(0L, SpeechFloorPolicy.pendingAfter(firstLine, pending, LINE));
        assertEquals(0L, SpeechFloorPolicy.pendingAfter(firstLine, pending, END));
        assertEquals(pending, SpeechFloorPolicy.pendingAfter(firstLine, pending, SCRIPTED));
        assertEquals(pending, SpeechFloorPolicy.pendingAfter(firstLine, pending, null));
        // A later reservation never shortens one already held.
        assertEquals(NOW + 5_000L + 30_000L,
                SpeechFloorPolicy.pendingAfter(NOW + 5_000L, pending, PENDING));
    }

    @Test
    void theReservationFitsInsideTheSanityHorizon() {
        long pending = SpeechFloorPolicy.pendingAfter(NOW, 0L, PENDING);
        assertEquals(pending, SpeechFloorPolicy.sanitize(NOW, pending));
        for (SpeechFloorPolicy.Source source : SpeechFloorPolicy.Source.values()) {
            assertTrue(SpeechFloorPolicy.floorDurationMs(source) <= SpeechFloorPolicy.MAX_FLOOR_HORIZON_MS,
                    source + " must arm inside the horizon or sanitize would clamp a legitimate floor");
        }
        assertTrue(SpeechFloorPolicy.MAX_SCENE_LINE_FLOOR_MS <= SpeechFloorPolicy.MAX_FLOOR_HORIZON_MS);
        // A bogus stored reservation (clock jump) is clamped like any other deadline.
        long bogus = NOW + SpeechFloorPolicy.MAX_FLOOR_HORIZON_MS + 1L;
        assertEquals(NOW + SpeechFloorPolicy.POST_SCENE_QUIET_MS,
                SpeechFloorPolicy.pendingAfter(NOW, bogus, SCRIPTED));
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW, 0L, null, bogus, SCRIPTED));
        assertEquals(SpeechFloorPolicy.POST_SCENE_QUIET_MS, SpeechFloorPolicy.remainingMs(NOW, bogus));
    }

    @Test
    void pendingAndScriptedOwnersArePreemptibleByAScene() {
        assertTrue(SpeechFloorPolicy.isPreemptibleByScene(SCRIPTED));
        assertTrue(SpeechFloorPolicy.isPreemptibleByScene(PENDING));
        assertFalse(SpeechFloorPolicy.isPreemptibleByScene(LINE));
        assertFalse(SpeechFloorPolicy.isPreemptibleByScene(END));
        assertFalse(SpeechFloorPolicy.isPreemptibleByScene(null));
        // Even if a reservation deadline were stored in the speech slot, only scripted respects it.
        long deadline = NOW + 30_000L;
        assertTrue(SpeechFloorPolicy.isOpenFor(NOW + 1_000L, deadline, PENDING, LINE));
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW + 1_000L, deadline, PENDING, SCRIPTED));
    }

    @Test
    void closedByNamesWhatTheRequesterIsWaitingOn() {
        long pending = SpeechFloorPolicy.pendingAfter(NOW, 0L, PENDING);
        long mid = NOW + 1_000L;

        assertEquals(null, SpeechFloorPolicy.closedBy(mid, 0L, null, 0L, SCRIPTED));
        assertEquals(PENDING, SpeechFloorPolicy.closedBy(mid, 0L, null, pending, SCRIPTED));
        // A scene is not held by the reservation.
        assertEquals(null, SpeechFloorPolicy.closedBy(mid, 0L, null, pending, LINE));

        long scripted = SpeechFloorPolicy.armedUntil(NOW, 0L, SCRIPTED);
        assertEquals(SCRIPTED, SpeechFloorPolicy.closedBy(mid, scripted, SCRIPTED, 0L, SCRIPTED));
        // Both closed: the reservation (30 s) outlasts the scripted floor (4 s) — name it.
        assertEquals(PENDING, SpeechFloorPolicy.closedBy(mid, scripted, SCRIPTED, pending, SCRIPTED));
        // Both closed, speech outlasts an almost-spent reservation: name the speech owner.
        long lateEnd = SpeechFloorPolicy.armedUntil(NOW + 25_000L, 0L, END);
        assertEquals(END, SpeechFloorPolicy.closedBy(NOW + 25_000L, lateEnd, END, pending, SCRIPTED));
        assertEquals(END, SpeechFloorPolicy.closedBy(NOW + 25_000L, lateEnd, END, pending, LINE));
    }

    @Test
    void labelsAreStableLogSpellings() {
        assertEquals("scripted", SCRIPTED.label());
        assertEquals("scene-line", LINE.label());
        assertEquals("scene-end", END.label());
        assertEquals("scene-pending", PENDING.label());
        assertEquals("scene-pending", SpeechFloorPolicy.label(PENDING));
        assertEquals("none", SpeechFloorPolicy.label(null));
    }

    // --- hold-aware scene-line floor (1.1.217 gap C2) ---

    @Test
    void aSceneLineFloorCoversItsHoldPlusMargin() {
        assertEquals(6_000L, SpeechFloorPolicy.armDurationMs(LINE, 0L));
        assertEquals(6_000L, SpeechFloorPolicy.armDurationMs(LINE, -1L));
        assertEquals(6_000L, SpeechFloorPolicy.armDurationMs(LINE, 2_000L));   // text-only beat
        assertEquals(6_000L, SpeechFloorPolicy.armDurationMs(LINE, 4_500L));   // hold + margin == floor
        assertEquals(6_100L, SpeechFloorPolicy.armDurationMs(LINE, 4_600L));
        assertEquals(8_000L + SpeechFloorPolicy.SCENE_LINE_HOLD_MARGIN_MS,
                SpeechFloorPolicy.armDurationMs(LINE, 8_000L));
        // Capped: a mis-reported audio length cannot mute scripted beyond one quiet window.
        assertEquals(SpeechFloorPolicy.MAX_SCENE_LINE_FLOOR_MS, SpeechFloorPolicy.armDurationMs(LINE, 60_000L));
        assertEquals(SpeechFloorPolicy.MAX_SCENE_LINE_FLOOR_MS,
                SpeechFloorPolicy.armDurationMs(LINE, Long.MAX_VALUE));
    }

    @Test
    void theHoldIsIgnoredForEveryOtherSource() {
        assertEquals(4_000L, SpeechFloorPolicy.armDurationMs(SCRIPTED, 12_000L));
        assertEquals(20_000L, SpeechFloorPolicy.armDurationMs(END, 12_000L));
        assertEquals(30_000L, SpeechFloorPolicy.armDurationMs(PENDING, 12_000L));
        assertEquals(0L, SpeechFloorPolicy.armDurationMs(null, 12_000L));
    }

    @Test
    void theThreeArgOverloadsAreHoldZero() {
        for (SpeechFloorPolicy.Source source : SpeechFloorPolicy.Source.values()) {
            assertEquals(SpeechFloorPolicy.armedUntil(NOW, NOW + 2_000L, source, 0L),
                    SpeechFloorPolicy.armedUntil(NOW, NOW + 2_000L, source));
            assertEquals(SpeechFloorPolicy.armedBySource(NOW, NOW + 2_000L, END, source, 0L),
                    SpeechFloorPolicy.armedBySource(NOW, NOW + 2_000L, END, source));
        }
    }

    @Test
    void anEightSecondLineGapNoLongerReopensTheFloorEarly() {
        // 1.1.216 field log: next line 8 s after the previous (audio + LINE_GAP_MS). The fixed 6 s
        // floor reopened at +6 s, two seconds before the next line.
        long hold = 8_000L;
        long fixed = SpeechFloorPolicy.armedUntil(NOW, 0L, LINE);
        long aware = SpeechFloorPolicy.armedUntil(NOW, 0L, LINE, hold);
        assertTrue(SpeechFloorPolicy.isOpenFor(NOW + 7_000L, fixed, LINE, SCRIPTED));
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW + 7_000L, aware, LINE, SCRIPTED));
        assertFalse(SpeechFloorPolicy.isOpenFor(NOW + hold, aware, LINE, SCRIPTED));
        assertTrue(SpeechFloorPolicy.isOpenFor(NOW + hold + SpeechFloorPolicy.SCENE_LINE_HOLD_MARGIN_MS,
                aware, LINE, SCRIPTED));
    }

    @Test
    void aHoldAwareArmNeverShortensAnExistingFloor() {
        long sceneEnd = SpeechFloorPolicy.armedUntil(NOW, 0L, END);
        assertEquals(sceneEnd, SpeechFloorPolicy.armedUntil(NOW + 1_000L, sceneEnd, LINE, 8_000L));

        long longLine = SpeechFloorPolicy.armedUntil(NOW, 0L, LINE, 8_000L);     // NOW + 9.5 s
        // A short line right after a long one cannot cut the long one's floor.
        assertEquals(longLine, SpeechFloorPolicy.armedUntil(NOW + 1_000L, longLine, LINE, 0L));
        // A longer hold later on extends it.
        assertEquals(NOW + 8_000L + 12_000L + SpeechFloorPolicy.SCENE_LINE_HOLD_MARGIN_MS,
                SpeechFloorPolicy.armedUntil(NOW + 8_000L, longLine, LINE, 12_000L));
    }

    @Test
    void theOwnerLabelFollowsTheHoldAwareDeadline() {
        // A post-scene quiet with 7 s left, then a scene line: whether the line takes the label
        // depends on the hold, exactly as the deadline does.
        long endWith7sLeft = NOW + 7_000L;
        assertEquals(NOW + 9_500L, SpeechFloorPolicy.armedUntil(NOW, endWith7sLeft, LINE, 8_000L));
        assertEquals(LINE, SpeechFloorPolicy.armedBySource(NOW, endWith7sLeft, END, LINE, 8_000L));
        assertEquals(endWith7sLeft, SpeechFloorPolicy.armedUntil(NOW, endWith7sLeft, LINE, 0L));
        assertEquals(END, SpeechFloorPolicy.armedBySource(NOW, endWith7sLeft, END, LINE, 0L));
    }
}
