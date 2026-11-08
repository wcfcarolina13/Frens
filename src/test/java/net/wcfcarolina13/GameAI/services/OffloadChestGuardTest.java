package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffloadChestGuardTest {

    @AfterEach
    void clear() {
        OffloadChestGuard.exit();
    }

    @Test
    void onlyTheOutermostOffloadMayPlaceAChest() {
        assertTrue(OffloadChestGuard.tryEnter());
        assertFalse(OffloadChestGuard.tryEnter(), "a nested offload must not place another chest");
        OffloadChestGuard.exit();
        assertFalse(OffloadChestGuard.active());
        assertTrue(OffloadChestGuard.tryEnter(), "the next craft may place one again");
    }

    @Test
    void theFlagIsPerThread() throws InterruptedException {
        assertTrue(OffloadChestGuard.tryEnter());
        AtomicBoolean otherThread = new AtomicBoolean();
        Thread t = new Thread(() -> {
            otherThread.set(OffloadChestGuard.tryEnter());
            OffloadChestGuard.exit();
        });
        t.start();
        t.join();
        assertTrue(otherThread.get(), "another bot's worker is not blocked");
    }

    @Test
    void offloadPlacesAChestOnlyInsideTheGuard() throws Exception {
        String src = Files.readString(Path.of("src/main/java/net/wcfcarolina13/GameAI/services/CraftingHelper.java"));
        int guard = src.indexOf("if (!OffloadChestGuard.tryEnter())");
        int place = src.indexOf("ChestStoreService.placeChestNearBot(source, bot, false)", guard);
        int exit = src.indexOf("OffloadChestGuard.exit();", place);
        assertTrue(guard > 0 && place > guard && exit > place);
    }
}
