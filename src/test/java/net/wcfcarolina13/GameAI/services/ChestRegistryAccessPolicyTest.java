package net.wcfcarolina13.GameAI.services;

import net.wcfcarolina13.GameAI.services.ChestRegistryAccessPolicy.Access;
import net.wcfcarolina13.GameAI.services.ChestRegistryAccessPolicy.ChestKey;
import net.wcfcarolina13.GameAI.services.ChestRegistryAccessPolicy.Mode;
import net.wcfcarolina13.GameAI.services.ChestRegistryAccessPolicy.Target;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestRegistryAccessPolicyTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // ── authorize ──────────────────────────────────────────────────────────

    @Test
    void nullRequesterIsDeniedBeforeAnythingElse() {
        assertEquals(Access.DENY_NO_REQUESTER,
                ChestRegistryAccessPolicy.authorize(null, OWNER, true, true, true));
        assertEquals(Access.DENY_NO_REQUESTER,
                ChestRegistryAccessPolicy.authorize(null, null, false, false, false));
    }

    @Test
    void realPlayerTargetIsDeniedEvenForOperatorOrHost() {
        assertEquals(Access.DENY_NOT_A_BOT,
                ChestRegistryAccessPolicy.authorize(STRANGER, null, false, true, false));
        assertEquals(Access.DENY_NOT_A_BOT,
                ChestRegistryAccessPolicy.authorize(STRANGER, null, false, false, true));
        assertEquals(Access.DENY_NOT_A_BOT,
                ChestRegistryAccessPolicy.authorize(OWNER, OWNER, false, false, false));
    }

    @Test
    void ownerIsAllowed() {
        assertEquals(Access.ALLOW_OWNER,
                ChestRegistryAccessPolicy.authorize(OWNER, OWNER, true, false, false));
    }

    @Test
    void ownerWinsOverOperatorAndHost() {
        assertEquals(Access.ALLOW_OWNER,
                ChestRegistryAccessPolicy.authorize(OWNER, OWNER, true, true, true));
    }

    @Test
    void operatorIsAllowedOnSomeoneElsesBot() {
        assertEquals(Access.ALLOW_OPERATOR,
                ChestRegistryAccessPolicy.authorize(STRANGER, OWNER, true, true, false));
    }

    @Test
    void operatorIsAllowedOnUnownedBot() {
        assertEquals(Access.ALLOW_OPERATOR,
                ChestRegistryAccessPolicy.authorize(STRANGER, null, true, true, false));
    }

    @Test
    void operatorWinsOverHost() {
        assertEquals(Access.ALLOW_OPERATOR,
                ChestRegistryAccessPolicy.authorize(STRANGER, OWNER, true, true, true));
    }

    @Test
    void hostIsAllowedOnOwnedAndUnownedBots() {
        assertEquals(Access.ALLOW_HOST,
                ChestRegistryAccessPolicy.authorize(STRANGER, OWNER, true, false, true));
        assertEquals(Access.ALLOW_HOST,
                ChestRegistryAccessPolicy.authorize(STRANGER, null, true, false, true));
    }

    @Test
    void unownedBotIsDeniedToOrdinaryPlayers() {
        assertEquals(Access.DENY_UNOWNED,
                ChestRegistryAccessPolicy.authorize(STRANGER, null, true, false, false));
    }

    @Test
    void someoneElsesBotIsDeniedToOrdinaryPlayers() {
        assertEquals(Access.DENY_NOT_OWNER,
                ChestRegistryAccessPolicy.authorize(STRANGER, OWNER, true, false, false));
    }

    @Test
    void onlyAllowOutcomesAreAllowed() {
        assertTrue(Access.ALLOW_OWNER.allowed());
        assertTrue(Access.ALLOW_OPERATOR.allowed());
        assertTrue(Access.ALLOW_HOST.allowed());
        assertFalse(Access.DENY_NO_REQUESTER.allowed());
        assertFalse(Access.DENY_NOT_A_BOT.allowed());
        assertFalse(Access.DENY_UNOWNED.allowed());
        assertFalse(Access.DENY_NOT_OWNER.allowed());
    }

    // ── checkTarget ────────────────────────────────────────────────────────

    @Test
    void liveRecordAtCoordinatesIsOk() {
        List<ChestKey> records = List.of(new ChestKey(1, 64, -3, false), new ChestKey(10, 70, 10, false));
        assertEquals(Target.OK, ChestRegistryAccessPolicy.checkTarget(records, 10, 70, 10));
    }

    @Test
    void destroyedRecordAtCoordinatesIsDestroyed() {
        List<ChestKey> records = List.of(new ChestKey(10, 70, 10, true));
        assertEquals(Target.DESTROYED, ChestRegistryAccessPolicy.checkTarget(records, 10, 70, 10));
    }

    @Test
    void unknownCoordinatesAreNotRegistered() {
        List<ChestKey> records = List.of(new ChestKey(10, 70, 10, false));
        assertEquals(Target.NOT_REGISTERED, ChestRegistryAccessPolicy.checkTarget(records, 0, 0, 0));
        assertEquals(Target.NOT_REGISTERED, ChestRegistryAccessPolicy.checkTarget(List.of(), 10, 70, 10));
    }

    @Test
    void duplicateRecordsWithOneLiveAreOkInEitherOrder() {
        List<ChestKey> destroyedFirst = List.of(new ChestKey(5, 60, 5, true), new ChestKey(5, 60, 5, false));
        List<ChestKey> liveFirst = List.of(new ChestKey(5, 60, 5, false), new ChestKey(5, 60, 5, true));
        assertEquals(Target.OK, ChestRegistryAccessPolicy.checkTarget(destroyedFirst, 5, 60, 5));
        assertEquals(Target.OK, ChestRegistryAccessPolicy.checkTarget(liveFirst, 5, 60, 5));
    }

    @Test
    void duplicateRecordsAllDestroyedAreDestroyed() {
        List<ChestKey> records = List.of(new ChestKey(5, 60, 5, true), new ChestKey(5, 60, 5, true));
        assertEquals(Target.DESTROYED, ChestRegistryAccessPolicy.checkTarget(records, 5, 60, 5));
    }

    @Test
    void nullListIsNotRegistered() {
        assertEquals(Target.NOT_REGISTERED, ChestRegistryAccessPolicy.checkTarget(null, 10, 70, 10));
    }

    @Test
    void offByOneOnAnyAxisIsNotRegistered() {
        List<ChestKey> records = List.of(new ChestKey(10, 70, 10, false));
        assertEquals(Target.NOT_REGISTERED, ChestRegistryAccessPolicy.checkTarget(records, 11, 70, 10));
        assertEquals(Target.NOT_REGISTERED, ChestRegistryAccessPolicy.checkTarget(records, 9, 70, 10));
        assertEquals(Target.NOT_REGISTERED, ChestRegistryAccessPolicy.checkTarget(records, 10, 71, 10));
        assertEquals(Target.NOT_REGISTERED, ChestRegistryAccessPolicy.checkTarget(records, 10, 69, 10));
        assertEquals(Target.NOT_REGISTERED, ChestRegistryAccessPolicy.checkTarget(records, 10, 70, 11));
        assertEquals(Target.NOT_REGISTERED, ChestRegistryAccessPolicy.checkTarget(records, 10, 70, 9));
    }

    // ── parseMode / parseReturnTo ──────────────────────────────────────────

    @Test
    void parseModeAcceptsOnlyTheScreenValues() {
        assertEquals(Optional.of(Mode.COLLECT), ChestRegistryAccessPolicy.parseMode(null));
        assertEquals(Optional.of(Mode.GO), ChestRegistryAccessPolicy.parseMode("go"));
        assertEquals(Optional.of(Mode.COLLECT), ChestRegistryAccessPolicy.parseMode("collect"));
        assertEquals(Optional.empty(), ChestRegistryAccessPolicy.parseMode("GO"));
        assertEquals(Optional.empty(), ChestRegistryAccessPolicy.parseMode("Collect"));
        assertEquals(Optional.empty(), ChestRegistryAccessPolicy.parseMode(""));
        assertEquals(Optional.empty(), ChestRegistryAccessPolicy.parseMode("store"));
        assertEquals(Optional.empty(), ChestRegistryAccessPolicy.parseMode(" go"));
    }

    @Test
    void parseReturnToAcceptsOnlyTheScreenValues() {
        assertEquals(Optional.of("stay"), ChestRegistryAccessPolicy.parseReturnTo(null));
        assertEquals(Optional.of("stay"), ChestRegistryAccessPolicy.parseReturnTo("stay"));
        assertEquals(Optional.of("player"), ChestRegistryAccessPolicy.parseReturnTo("player"));
        assertEquals(Optional.of("home"), ChestRegistryAccessPolicy.parseReturnTo("home"));
        assertEquals(Optional.empty(), ChestRegistryAccessPolicy.parseReturnTo("HOME"));
        assertEquals(Optional.empty(), ChestRegistryAccessPolicy.parseReturnTo(""));
        assertEquals(Optional.empty(), ChestRegistryAccessPolicy.parseReturnTo("stay:player"));
        assertEquals(Optional.empty(), ChestRegistryAccessPolicy.parseReturnTo("origin"));
    }

    // ── withinArrivalReach ─────────────────────────────────────────────────

    @Test
    void arrivalReachIsInclusiveAtEightBlocks() {
        assertEquals(8.0, ChestRegistryAccessPolicy.ARRIVAL_MAX_DISTANCE);
        assertTrue(ChestRegistryAccessPolicy.withinArrivalReach(0.0));
        assertTrue(ChestRegistryAccessPolicy.withinArrivalReach(5.5 * 5.5));
        assertTrue(ChestRegistryAccessPolicy.withinArrivalReach(64.0));
        assertFalse(ChestRegistryAccessPolicy.withinArrivalReach(64.01));
    }

    @Test
    void arrivalReachRejectsNaNNegativeAndInfinity() {
        assertFalse(ChestRegistryAccessPolicy.withinArrivalReach(Double.NaN));
        assertFalse(ChestRegistryAccessPolicy.withinArrivalReach(-1.0));
        assertFalse(ChestRegistryAccessPolicy.withinArrivalReach(-0.0001));
        assertFalse(ChestRegistryAccessPolicy.withinArrivalReach(Double.POSITIVE_INFINITY));
    }
}
