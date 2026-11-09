package net.wcfcarolina13.GameAI.services;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookFuelFallbackPolicyTest {

    private static final BooleanSupplier LITTER = () -> true;
    private static final BooleanSupplier NO_LITTER = () -> false;

    @Test
    void emergencyCookGathersWhenOnlyFuelIsMissing() {
        assertTrue(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, false, true, false, 100L, 100L, LITTER));
    }

    @Test
    void emergencyCookDoesNotGatherWhenAnythingElseIsMissing() {
        assertFalse(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(false, true, false, true, false, 100L, 0L, LITTER), "not hungry");
        assertFalse(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, false, false, true, false, 100L, 0L, LITTER), "nothing to cook");
        assertFalse(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, true, true, false, 100L, 0L, LITTER), "already has fuel");
        assertFalse(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, false, false, false, 100L, 0L, LITTER), "leaf_litter hobby off");
        assertFalse(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, false, true, true, 100L, 0L, LITTER), "run in flight");
        assertFalse(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, false, true, false, 100L, 0L, NO_LITTER), "no litter nearby");
        assertFalse(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, false, true, false, 100L, 0L, null), "no scan");
    }

    @Test
    void emergencyCookWaitsOutTheCooldownAfterARun() {
        long next = CookFuelFallbackPolicy.nextGatherTick(1_000L);
        assertEquals(1_000L + CookFuelFallbackPolicy.GATHER_COOLDOWN_TICKS, next);
        assertFalse(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, false, true, false, next - 1, next, LITTER));
        assertTrue(CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, false, true, false, next, next, LITTER));
    }

    @Test
    void blockScanRunsOnlyWhenEverythingElseAllowsARun() {
        AtomicInteger scans = new AtomicInteger();
        BooleanSupplier counting = () -> { scans.incrementAndGet(); return true; };
        CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, false, true, false, 10L, 500L, counting);
        CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, true, true, false, 600L, 500L, counting);
        CookFuelFallbackPolicy.shouldGatherForCookCommand(null, true, false, counting);
        assertEquals(0, scans.get());
        CookFuelFallbackPolicy.shouldGatherForEmergencyCook(true, true, false, true, false, 600L, 500L, counting);
        assertEquals(1, scans.get());
    }

    @Test
    void cookCommandGathersOnlyForAFuelArgumentLeafLitterCanMeet() {
        assertTrue(CookFuelFallbackPolicy.shouldGatherForCookCommand("auto", true, false, LITTER));
        assertTrue(CookFuelFallbackPolicy.shouldGatherForCookCommand("leaf_litter", true, false, LITTER));
        assertFalse(CookFuelFallbackPolicy.shouldGatherForCookCommand(null, true, false, LITTER), "no fuel argument loads no fuel");
        assertFalse(CookFuelFallbackPolicy.shouldGatherForCookCommand("coal", true, false, LITTER), "named other fuel");
        assertFalse(CookFuelFallbackPolicy.shouldGatherForCookCommand("off", true, false, LITTER));
    }

    @Test
    void cookCommandDoesNotGatherWithoutCookablesWithFuelOrWithoutLitter() {
        assertFalse(CookFuelFallbackPolicy.shouldGatherForCookCommand("auto", false, false, LITTER));
        assertFalse(CookFuelFallbackPolicy.shouldGatherForCookCommand("auto", true, true, LITTER));
        assertFalse(CookFuelFallbackPolicy.shouldGatherForCookCommand("auto", true, false, NO_LITTER));
    }

    @Test
    void fuelArgumentWordsMatchEnsureFuel() {
        for (String spec : new String[] {"true", "on", "auto", "use", " AUTO ", "leaf_litter", "minecraft:leaf_litter"}) {
            assertTrue(CookFuelFallbackPolicy.specCanUseLeafLitter(spec), spec);
        }
        for (String spec : new String[] {null, "", "false", "off", "none", "coal", "oak_planks"}) {
            assertFalse(CookFuelFallbackPolicy.specCanUseLeafLitter(spec), String.valueOf(spec));
        }
    }

    @Test
    void cooksAfterTheRunOnlyWithFuelAndNoStop() {
        assertTrue(CookFuelFallbackPolicy.shouldCookAfterGather(true, true));
        assertFalse(CookFuelFallbackPolicy.shouldCookAfterGather(true, false), "still no fuel: give up as before");
        assertFalse(CookFuelFallbackPolicy.shouldCookAfterGather(false, true), "run stopped");
    }
}
