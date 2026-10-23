package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RitualAttemptPolicyTest {
    @Test void duplicateAliasesAreReservedWithoutCharging() {
        var policy = new RitualAttemptPolicy();
        Object server = new Object();
        var attempt = policy.claim(server, "Jake");
        assertNotNull(attempt);
        assertNull(policy.claim(server, " jAkE "));
        assertTrue(policy.isCurrent(attempt));
    }

    @Test void connectionMustSucceedBeforePaymentAndAliveState() {
        var policy = new RitualAttemptPolicy();
        var attempt = policy.claim(new Object(), "Jake");
        List<String> events = new ArrayList<>();
        policy.execute(attempt, () -> true, () -> events.add("connected"), () -> {
            assertEquals(List.of("connected"), events);
            events.add("paid and alive");
        });
        assertEquals(List.of("connected", "paid and alive"), events);
        assertFalse(policy.isCurrent(attempt));
    }

    @Test void rejectedAdmissionLeavesTheOfferingAndDeathStateUntouched() {
        var policy = new RitualAttemptPolicy();
        var attempt = policy.claim(new Object(), "Jake");
        AtomicInteger mutations = new AtomicInteger();
        assertThrows(CancellationException.class, () -> policy.execute(attempt, () -> false,
                mutations::incrementAndGet, mutations::incrementAndGet));
        assertEquals(0, mutations.get());
        assertFalse(policy.isCurrent(attempt));
    }

    @Test void failedConnectionOrTeleportNeverChargesOrMarksAlive() {
        var policy = new RitualAttemptPolicy();
        var attempt = policy.claim(new Object(), "Jake");
        AtomicInteger paid = new AtomicInteger();
        var failure = new IllegalStateException("connection failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                policy.execute(attempt, () -> true, () -> { throw failure; }, paid::incrementAndGet)));
        assertEquals(0, paid.get());
        assertFalse(policy.isCurrent(attempt));
    }

    @Test void commitFailureIsObservedWithoutRetryingPayment() {
        var policy = new RitualAttemptPolicy();
        var attempt = policy.claim(new Object(), "Jake");
        AtomicInteger payments = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> policy.execute(attempt, () -> true, () -> { }, () -> {
            payments.incrementAndGet();
            throw new IllegalStateException("anchor refused charge");
        }));
        assertThrows(CancellationException.class, () -> policy.execute(attempt, () -> true,
                () -> fail("old attempt must not connect"), payments::incrementAndGet));
        assertEquals(1, payments.get());
    }

    @Test void staleFailureCleanupCannotRemoveANewerAttempt() {
        var policy = new RitualAttemptPolicy();
        Object server = new Object();
        var old = policy.claim(server, "Jake");
        policy.release(old);
        var next = policy.claim(server, "Jake");
        policy.release(old);
        assertTrue(policy.isCurrent(next));
        assertThrows(CancellationException.class, () -> policy.execute(old, () -> true,
                () -> fail("stale connection"), () -> fail("stale payment")));
        assertTrue(policy.isCurrent(next));
    }

    @Test void stopCancelsPendingLookupAndReleasesReservationWithoutPayment() {
        var policy = new RitualAttemptPolicy();
        Object server = new Object();
        var attempt = policy.claim(server, "Jake");
        var lookup = new CompletableFuture<String>();
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicInteger paid = new AtomicInteger();
        var spawned = DeferredSpawnPolicy.connectAfter(server, lookup, Runnable::run, active::get,
                profile -> policy.execute(attempt, active::get, () -> { }, paid::incrementAndGet));
        spawned.whenComplete((ignored, error) -> policy.release(attempt));
        active.set(false);
        DeferredSpawnPolicy.cancelPending(server);
        assertTrue(spawned.isCancelled());
        assertFalse(policy.isCurrent(attempt));
        lookup.complete("late profile");
        assertEquals(0, paid.get());
    }

    @Test void ownerOrWorldChangeBeforeLookupCompletionRejectsConnectionAndPayment() {
        var policy = new RitualAttemptPolicy();
        var attempt = policy.claim(new Object(), "Jake");
        var lookup = new CompletableFuture<String>();
        AtomicBoolean authority = new AtomicBoolean(true);
        AtomicInteger mutations = new AtomicInteger();
        var result = lookup.thenAccept(profile -> policy.execute(attempt, authority::get,
                mutations::incrementAndGet, mutations::incrementAndGet));
        authority.set(false);
        lookup.complete("profile");
        assertTrue(result.isCompletedExceptionally());
        assertEquals(0, mutations.get());
        assertFalse(policy.isCurrent(attempt));
    }

    @Test void successfulJoinMayChangeOnlineStateWithoutBlockingItsOwnCommit() {
        var policy = new RitualAttemptPolicy();
        var attempt = policy.claim(new Object(), "Jake");
        AtomicBoolean online = new AtomicBoolean(false);
        AtomicInteger paid = new AtomicInteger();
        policy.execute(attempt, () -> !online.get(), () -> online.set(true), paid::incrementAndGet);
        assertTrue(online.get());
        assertEquals(1, paid.get());
    }

    @Test void distinctServerIdentitiesAndAliasesRemainIndependent() {
        var policy = new RitualAttemptPolicy();
        Object first = new String("server");
        Object second = new String("server");
        var a = policy.claim(first, "Jake");
        var b = policy.claim(second, "Jake");
        var c = policy.claim(first, "Bob");
        policy.release(a);
        assertTrue(policy.isCurrent(b));
        assertTrue(policy.isCurrent(c));
        assertNull(policy.claim(null, "Jake"));
        assertNull(policy.claim(first, " "));
    }

    @Test void sourceWiresBothSpawnPathsThroughInlineConnectionAndCommit() throws Exception {
        String spawn = Files.readString(Path.of("src/main/java/net/wcfcarolina13/Entity/createFakePlayer.java"));
        assertTrue(spawn.contains("flying, Runnable::run)"));
        assertTrue(spawn.contains("p -> connectionAttempt.accept(() -> spawnFake("));
        assertTrue(spawn.contains("connectionAttempt.accept(() -> spawnFake(server, worldIn, localProfile"));
        String ritual = source();
        assertTrue(ritual.contains("ATTEMPTS.execute(attempt,"));
        assertTrue(ritual.contains("mode, false, connectionAttempt)"));
        assertTrue(ritual.contains("if (!existing.teleport("));
        assertFalse(ritual.contains("server.execute("));
        assertTrue(ritual.contains("result.whenComplete((ignored, error) -> {\n                ATTEMPTS.release(attempt);"));
    }

    @Test void sourceChecksCurrentAnchorBeforeChargingAndKeepsDeathRecords() throws Exception {
        String ritual = source();
        int commit = ritual.indexOf("connect, () -> {");
        int anchor = ritual.indexOf("if (!world.setBlockState(", commit);
        int items = ritual.indexOf("player.getStackInHand(hand).decrement(1)", commit);
        int alive = ritual.indexOf("markAlive.run();", commit);
        assertTrue(commit >= 0 && anchor > commit && items > anchor && alive > items);
        assertTrue(ritual.contains("anchor.get(RespawnAnchorBlock.CHARGES) - 1"));
        assertTrue(ritual.contains("Frens.CONFIG == config"));
        assertTrue(ritual.contains("java.util.Objects.equals(owner, ownerIdentity(config, alias))"));
        assertTrue(ritual.contains("BotDeathRecordService.getBotRecord(server, alias) == death"));
        assertTrue(ritual.contains("The companion may already be connected"));
        assertFalse(ritual.contains("BotDeathRecordService.clear"));
        assertFalse(ritual.contains("BotDeathRecordService.remove"));
    }

    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/CompanionResurrectionService.java"));
    }
}
