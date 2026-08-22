package net.wcfcarolina13.GameAI.services;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotZzzSleepServiceTest {

    @AfterEach
    void restoreExecutor() {
        BotZzzSleepService.restartExecutor();
    }

    @Test
    void chatTriggerUsesSameSixteenBlockRadiusAsCoSleep() {
        assertTrue(BotSleepProximityPolicy.isWithinCommanderSleepRadius(16.0D * 16.0D));
        assertFalse(BotSleepProximityPolicy.isWithinCommanderSleepRadius(19.1D * 19.1D));
    }

    @Test
    void idleResumeWaitsForZzzCycleToFinish() {
        assertFalse(BotIdleResumeService.canResumeAfterSleepCycle(true));
        assertTrue(BotIdleResumeService.canResumeAfterSleepCycle(false));
    }

    @Test
    void bedFootDeduplicationCopiesMutableScannerPositions() {
        Set<BlockPos> seen = new HashSet<>();
        BlockPos.Mutable scannerPosition = new BlockPos.Mutable(277, 48, 1295);

        assertTrue(SleepBedCandidatePolicy.addUniqueFoot(seen, scannerPosition));
        scannerPosition.set(300, 70, 1400);

        assertFalse(SleepBedCandidatePolicy.addUniqueFoot(seen, new BlockPos(277, 48, 1295)));
        assertEquals(Set.of(new BlockPos(277, 48, 1295)), seen);
    }

    @Test
    void sleepAttemptRunsAwayFromCallingThread() throws Exception {
        String callerThread = Thread.currentThread().getName();
        CompletableFuture<String> workerThread = new CompletableFuture<>();

        BotZzzSleepService.submitSleepAttempt(
                () -> workerThread.complete(Thread.currentThread().getName()));

        String observedThread = workerThread.get(2, TimeUnit.SECONDS);
        assertNotEquals(callerThread, observedThread);
        assertTrue(observedThread.startsWith("zzz-sleep-"));
    }
}
