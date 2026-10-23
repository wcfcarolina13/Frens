package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BeehiveVerificationPolicyTest {
    private static final class Hive {
        long revision;
        boolean relevant = true;
    }

    @Test
    void blockReadsRunWithoutTheRegistryLock() {
        Object lock = new Object();
        Hive hive = new Hive();
        List<Hive> records = new ArrayList<>(List.of(hive));
        assertTrue(BeehiveVerificationPolicy.removeMissing(lock, records, h -> {
            assertTrue(Thread.holdsLock(lock));
            return h.revision;
        }, h -> h.relevant, h -> {
            assertFalse(Thread.holdsLock(lock));
            return true;
        }));
        assertTrue(records.isEmpty());
    }

    @Test
    void aWorkerReadCanWaitForAServerLookupOfTheSameRegistry() {
        Object lock = new Object();
        List<Hive> records = new ArrayList<>(List.of(new Hive()));
        try (var server = Executors.newSingleThreadExecutor()) {
            assertTrue(BeehiveVerificationPolicy.removeMissing(lock, records, h -> h.revision,
                    h -> true, h -> {
                        try {
                            return server.submit(() -> {
                                synchronized (lock) {
                                    return records.contains(h);
                                }
                            }).get(1, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new AssertionError("server lookup was blocked by the worker's registry lock", e);
                        }
                    }));
        }
    }

    @Test
    void refreshedRegistrationSurvivesAnOlderMissingBlockRead() {
        Object lock = new Object();
        Hive hive = new Hive();
        List<Hive> records = new ArrayList<>(List.of(hive));
        assertFalse(BeehiveVerificationPolicy.removeMissing(lock, records, h -> h.revision,
                h -> true, h -> {
                    synchronized (lock) {
                        h.revision++;
                    }
                    return true;
                }));
        assertEquals(List.of(hive), records);
    }

    @Test
    void unknownOrStillPresentBlocksRetainProtection() {
        List<Hive> records = new ArrayList<>(List.of(new Hive(), new Hive()));
        assertFalse(BeehiveVerificationPolicy.removeMissing(new Object(), records, h -> h.revision,
                h -> true, h -> false));
        assertEquals(2, records.size());
    }

    @Test
    void onlyRelevantKnownMissingRecordsAreRemoved() {
        Hive missing = new Hive();
        Hive present = new Hive();
        Hive unrelated = new Hive();
        unrelated.relevant = false;
        List<Hive> records = new ArrayList<>(List.of(missing, present, unrelated));
        assertTrue(BeehiveVerificationPolicy.removeMissing(new Object(), records, h -> h.revision,
                h -> h.relevant, h -> {
                    assertNotSame(unrelated, h);
                    return h == missing;
                }));
        assertEquals(List.of(present, unrelated), records);
    }

    @Test
    void registrationAddedDuringTheReadIsKeptForTheNextPass() {
        Object lock = new Object();
        Hive original = new Hive();
        Hive added = new Hive();
        List<Hive> records = new ArrayList<>(List.of(original));
        assertTrue(BeehiveVerificationPolicy.removeMissing(lock, records, h -> h.revision,
                h -> true, h -> {
                    synchronized (lock) {
                        records.add(added);
                    }
                    return true;
                }));
        assertEquals(List.of(added), records);
    }

    @Test
    void replacementRecordIsNotRemovedWithTheOldRecord() {
        Object lock = new Object();
        Hive replacement = new Hive();
        List<Hive> records = new ArrayList<>(List.of(new Hive()));
        assertFalse(BeehiveVerificationPolicy.removeMissing(lock, records, h -> h.revision,
                h -> true, h -> {
                    synchronized (lock) {
                        records.clear();
                        records.add(replacement);
                    }
                    return true;
                }));
        assertEquals(List.of(replacement), records);
    }

    @Test
    void failedReadsLeaveAllProtectionIntact() {
        Hive first = new Hive();
        Hive second = new Hive();
        List<Hive> records = new ArrayList<>(List.of(first, second));
        assertThrows(IllegalStateException.class, () -> BeehiveVerificationPolicy.removeMissing(
                new Object(), records, h -> h.revision, h -> true, h -> {
                    if (h == second) throw new IllegalStateException("read unavailable");
                    return true;
                }));
        assertEquals(List.of(first, second), records);
    }
}
