package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotbarLockTableTest {
    private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void lockAppearsWholeAndExpiresAfterItsTick() {
        HotbarLockTable table = new HotbarLockTable();
        table.lock(BOT, 12, 100, 20, null);
        HotbarLockTable.Lock lock = table.active(BOT, 120);
        assertEquals(new HotbarLockTable.Lock(8, 120, "unknown"), lock, "slot clamped, reason defaulted");
        assertNull(table.active(BOT, 121));
        assertNull(table.active(BOT, 110), "the expired lock was dropped");
    }

    @Test
    void ttlIsAtLeastOneTickAndSlotNeverNegative() {
        HotbarLockTable table = new HotbarLockTable();
        table.lock(BOT, -3, 50, 0, "ride");
        assertEquals(new HotbarLockTable.Lock(0, 51, "ride"), table.active(BOT, 51));
    }

    @Test
    void aNewerLockSurvivesExpiryOfTheOldOne() {
        HotbarLockTable table = new HotbarLockTable();
        table.lock(BOT, 2, 0, 10, "old");
        table.lock(BOT, 5, 30, 10, "new");
        assertEquals(5, table.active(BOT, 35).slot());
        assertNull(table.active(BOT, 41));
    }

    @Test
    void clearDropsTheLockAndTheLogCooldown() {
        HotbarLockTable table = new HotbarLockTable();
        table.lock(BOT, 1, 0, 100, "x");
        assertTrue(table.logDue(BOT, 10, 40));
        table.clear(BOT);
        assertNull(table.active(BOT, 10));
        assertTrue(table.logDue(BOT, 11, 40), "cooldown reset by clear");
    }

    @Test
    void clearAllDropsLocksAndCooldowns() {
        HotbarLockTable table = new HotbarLockTable();
        table.lock(BOT, 3, 1_000_000, 100, "late in world A");
        assertTrue(table.logDue(BOT, 1_000_000, 40));
        table.clearAll();
        assertNull(table.active(BOT, 5), "world B tick 5 sees no lock");
        assertTrue(table.logDue(BOT, 5, 40));
    }

    @Test
    void logIsDueOncePerCooldown() {
        HotbarLockTable table = new HotbarLockTable();
        assertTrue(table.logDue(BOT, 0, 40));
        assertFalse(table.logDue(BOT, 39, 40));
        assertTrue(table.logDue(BOT, 40, 40));
    }

    @Test
    void nullIdsAreIgnored() {
        HotbarLockTable table = new HotbarLockTable();
        table.lock(null, 1, 0, 10, "x");
        table.clear(null);
        assertNull(table.active(null, 0));
        assertFalse(table.logDue(null, 0, 40));
    }

    @Test
    void workerSharedStaticMapsAreConcurrent() throws IOException {
        // Skills run on per-bot workers; two bots on the same skill write these maps at once.
        String[][] fields = {
                {"GameAI/skills/impl/WoolSkill.java", "LAST_SEEN_SHEEP"},
                {"GameAI/skills/impl/WoolSkill.java", "EXPLORED_ZONES"},
                {"GameAI/skills/impl/CollectDirtSkill.java", "LAST_INVENTORY_FULL_MESSAGE"},
                {"GameAI/skills/impl/CollectDirtSkill.java", "LAST_ESCAPE_ATTEMPT"},
                {"GameAI/services/construction/RoofAccessService.java", "roofPillars"},
        };
        for (String[] f : fields) {
            String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13", f[0]));
            int at = src.indexOf(" " + f[1] + " = ");
            assertTrue(at >= 0, f[1]);
            String init = src.substring(at, src.indexOf(';', at));
            assertTrue(init.contains("ConcurrentHashMap"), f[0] + " " + f[1] + ": " + init);
        }
        // Inner collections too: /bot stop interrupts a worker and a new skill can start for the
        // same bot while the old one is still unwinding.
        assertTrue(Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/skills/impl/WoolSkill.java"))
                .contains("EXPLORED_ZONES.computeIfAbsent(bot.getUuid(), k -> new java.util.concurrent.ConcurrentHashMap<>())"));
        assertTrue(Files.readString(Path.of(
                        "src/main/java/net/wcfcarolina13/GameAI/services/construction/RoofAccessService.java"))
                .contains("k -> new java.util.concurrent.CopyOnWriteArrayList<>()"));
        String hotbar = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/HotbarLockService.java"));
        assertFalse(hotbar.contains("new HashMap"), "hotbar lock state lives in HotbarLockTable");
    }
}
