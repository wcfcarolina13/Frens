package net.wcfcarolina13.GameAI.services;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Pure decisions behind the leaf-litter fuel fallback (backlog 1238): when emergency auto-cook or
 * {@code /bot cook} finds no fuel on the bot, it may run one bounded leaf-litter collection (the
 * idle hobby's survival-mode run) and then check for fuel once more. No Minecraft types, so JUnit
 * can test it.
 */
public final class CookFuelFallbackPolicy {

    /** Ticks between two fallback runs for one bot (2 minutes), whatever the first one gathered. */
    public static final long GATHER_COOLDOWN_TICKS = 20L * 120L;

    private CookFuelFallbackPolicy() {
    }

    /**
     * Emergency auto-cook: gather only when the only thing missing is fuel, leaf litter is nearby,
     * the bot's leaf_litter hobby is on, no run is already in flight, and the cooldown has passed.
     */
    public static boolean shouldGatherForEmergencyCook(boolean hungryEnough,
                                                       boolean hasCookableFood,
                                                       boolean hasFuel,
                                                       boolean leafLitterHobbyEnabled,
                                                       boolean gatherInFlight,
                                                       long nowTick,
                                                       long nextGatherTick,
                                                       BooleanSupplier leafLitterNearby) {
        // The block scan is asked last, so a bot on cooldown is not scanned every decision.
        return hungryEnough
                && hasCookableFood
                && !hasFuel
                && leafLitterHobbyEnabled
                && !gatherInFlight
                && nowTick >= nextGatherTick
                && leafLitterNearby != null
                && leafLitterNearby.getAsBoolean();
    }

    /**
     * {@code /bot cook}: gather only when the command asks the bot to load fuel in a way leaf
     * litter can satisfy, the bot has something to cook, has no fuel, and leaf litter is nearby.
     * Without a fuel argument the command never loads fuel, so gathering would not help.
     */
    public static boolean shouldGatherForCookCommand(String fuelSpec,
                                                     boolean hasMatchingCookables,
                                                     boolean hasFuel,
                                                     BooleanSupplier leafLitterNearby) {
        return specCanUseLeafLitter(fuelSpec)
                && hasMatchingCookables
                && !hasFuel
                && leafLitterNearby != null
                && leafLitterNearby.getAsBoolean();
    }

    /**
     * True when {@code SmeltingService.ensureFuel} would load leaf litter for this fuel argument:
     * the auto words (cheapest fuel, which leaf litter is) or leaf litter named outright.
     */
    public static boolean specCanUseLeafLitter(String fuelSpec) {
        if (fuelSpec == null) {
            return false;
        }
        String spec = fuelSpec.trim().toLowerCase(Locale.ROOT);
        return switch (spec) {
            case "true", "on", "auto", "use", "leaf_litter", "minecraft:leaf_litter" -> true;
            default -> false;
        };
    }

    /** After the run: cook only if the run was not stopped and fuel is now on the bot. */
    public static boolean shouldCookAfterGather(boolean runMayContinue, boolean hasFuelNow) {
        return runMayContinue && hasFuelNow;
    }

    public static long nextGatherTick(long nowTick) {
        return nowTick + GATHER_COOLDOWN_TICKS;
    }
}
