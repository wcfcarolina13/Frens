package net.wcfcarolina13.GameAI.skills.support;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the cross-run memory of GOAL_NOT_STANDABLE woodcut stands. No net.minecraft types. */
class WoodcutUnstandableStandsTest {

    // The two literal detail shapes MovementService builds for a unified refusal
    // (walkTo "no walkable path" and the unified leg loop's partial STOP).
    private static final String NO_WALKABLE_PATH =
            "move-to-stand: no walkable path reason=GOAL_NOT_STANDABLE plan=unified";
    private static final String PARTIAL_STOPPED =
            "move-to-stand: partial plan stopped after 2 legs reason=GOAL_NOT_STANDABLE plan=unified";

    @Test
    void recognisesBothMovementServiceDetailStrings() {
        assertTrue(WoodcutUnstandableStands.isGoalNotStandable(NO_WALKABLE_PATH));
        assertTrue(WoodcutUnstandableStands.isGoalNotStandable(PARTIAL_STOPPED));
        assertTrue(WoodcutUnstandableStands.isGoalNotStandable("reason=GOAL_NOT_STANDABLE"));
    }

    @Test
    void movementServiceStillBuildsBothDetailStrings() throws java.io.IOException {
        String movement = source("src/main/java/net/wcfcarolina13/GameAI/services/MovementService.java");
        assertTrue(movement.contains("label + \": no walkable path reason=\" + reason + \" plan=\" + planTag"),
                "MovementService no-walkable-path detail changed; update WoodcutUnstandableStands");
        assertTrue(movement.contains("\"partial plan stopped after \" + legs + \" legs reason=\" + plan.reason()"),
                "MovementService partial-stop detail changed; update WoodcutUnstandableStands");
        String reasons = source("src/main/java/net/wcfcarolina13/PathFinding/unified/NoPathReason.java");
        assertTrue(reasons.contains("GOAL_NOT_STANDABLE"));
    }

    private static String source(String relative) throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (java.nio.file.Path at = dir; at != null; at = at.getParent()) {
            java.nio.file.Path candidate = at.resolve(relative);
            if (java.nio.file.Files.isRegularFile(candidate)) {
                return java.nio.file.Files.readString(candidate, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException(relative + " not found above " + dir);
    }

    @Test
    void otherReasonsAndBaritoneDetailsDoNotCount() {
        assertFalse(WoodcutUnstandableStands.isGoalNotStandable(null));
        assertFalse(WoodcutUnstandableStands.isGoalNotStandable(""));
        assertFalse(WoodcutUnstandableStands.isGoalNotStandable("move-to-stand: no walkable path"));
        assertFalse(WoodcutUnstandableStands.isGoalNotStandable(
                "move-to-stand: no walkable path reason=BOXED_IN plan=unified"));
        assertFalse(WoodcutUnstandableStands.isGoalNotStandable(
                "move-to-stand: deadline after 3 legs plan=unified"));
        assertFalse(WoodcutUnstandableStands.isGoalNotStandable("reason=GOAL_NOT_STANDABLE_X"));
        assertFalse(WoodcutUnstandableStands.isGoalNotStandable("xreason=GOAL_NOT_STANDABLE"));
    }

    @Test
    void rememberedStandIsBlockedUntilTtlExpires() {
        AtomicLong now = new AtomicLong(1_000L);
        WoodcutUnstandableStands stands = new WoodcutUnstandableStands(now::get);
        UUID bot = UUID.randomUUID();
        assertFalse(stands.isBlocked(bot, 42L));
        stands.record(bot, 42L);
        assertTrue(stands.isBlocked(bot, 42L));
        now.addAndGet(WoodcutUnstandableStands.TTL_MS - 1);
        assertTrue(stands.isBlocked(bot, 42L));
        now.addAndGet(1);
        assertFalse(stands.isBlocked(bot, 42L));
    }

    @Test
    void memoryIsPerBotAndPerStand() {
        WoodcutUnstandableStands stands = new WoodcutUnstandableStands(() -> 0L);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        stands.record(a, 7L);
        assertTrue(stands.isBlocked(a, 7L));
        assertFalse(stands.isBlocked(a, 8L));
        assertFalse(stands.isBlocked(b, 7L));
        assertFalse(stands.isBlocked(null, 7L));
        stands.record(null, 7L);
        stands.clear(a);
        assertFalse(stands.isBlocked(a, 7L));
    }

    @Test
    void recordingAgainRefreshesTheExpiry() {
        AtomicLong now = new AtomicLong(0L);
        WoodcutUnstandableStands stands = new WoodcutUnstandableStands(now::get);
        UUID bot = UUID.randomUUID();
        stands.record(bot, 1L);
        now.set(WoodcutUnstandableStands.TTL_MS - 10);
        stands.record(bot, 1L);
        now.set(WoodcutUnstandableStands.TTL_MS + 10);
        assertTrue(stands.isBlocked(bot, 1L));
    }
}
